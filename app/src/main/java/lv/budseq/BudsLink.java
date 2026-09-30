package lv.budseq;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Связь с Galaxy Buds по их фирменному протоколу (RFCOMM/SPP).
 * Формат сообщений взят из открытого проекта GalaxyBudsClient:
 * [0xFD][size lo][size hi][msgId][payload...][crc lo][crc hi][0xDD]
 */
public final class BudsLink {
    private static final String TAG = "BudsLink";
    private static final UUID SPP = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2");

    private static final int SOM = 0xFD, EOM = 0xDD;
    private static final int MSG_STATUS = 96, MSG_EXT_STATUS = 97;
    private static final int MSG_MANAGER_INFO = 136;
    private static final int MSG_FIND_START = 160, MSG_FIND_STOP = 161;
    private static final int MSG_NOISE_CONTROLS_UPDATE = 119, MSG_NOISE_CONTROLS = 120;
    private static final int MSG_EQUALIZER = 134, MSG_LOCK_TOUCHPAD = 144;
    /** «Игровой режим» (Adjust sound sync): меньше задержка Bluetooth, Buds+ и новее. */
    private static final int MSG_GAME_MODE = 133;

    /** Режимы шумоподавления (как в протоколе Samsung). */
    public static final int NC_OFF = 0, NC_ANC = 1, NC_AMBIENT = 2;

    public static final int P_DISCONNECTED = 0, P_WEARING = 1, P_IDLE = 2, P_CASE = 3, P_CASE_CLOSED = 4;

    public static final class State {
        public boolean connected;
        public String name = "";
        public String address = "";
        public int batL = -1, batR = -1, batCase = -1;
        public int placeL = P_CASE_CLOSED, placeR = P_CASE_CLOSED;
        public boolean chgL, chgR, chgCase;
        /** Версия прошивки-статуса, шумоподавление (-1 = неизвестно), встроенный EQ (-1 = неизвестно). */
        public int revision = -1, noise = -1, fwEq = -1;
        /** -1 = неизвестно, 0 = сенсор работает, 1 = заблокирован. */
        public int touchLocked = -1;

        public boolean hasBattery() { return batL >= 0 || batR >= 0; }
        /**
         * Открыта ли крышка. Это знают только наушники, лежащие в кейсе (3 = в открытом, 4 = в закрытом).
         * Если оба наушника снаружи (в ушах / вынуты), состояние кейса неизвестно — считаем закрытым.
         */
        public boolean lidOpen() { return connected && (placeL == P_CASE || placeR == P_CASE); }
        public static boolean inCase(int p) { return p == P_CASE || p == P_CASE_CLOSED; }

        State copy() {
            State s = new State();
            s.connected = connected; s.name = name; s.address = address;
            s.batL = batL; s.batR = batR; s.batCase = batCase;
            s.placeL = placeL; s.placeR = placeR;
            s.chgL = chgL; s.chgR = chgR; s.chgCase = chgCase;
            s.revision = revision; s.noise = noise; s.fwEq = fwEq; s.touchLocked = touchLocked;
            return s;
        }
    }

    public interface Listener {
        void onBudsState(State s);
    }

    private static BudsLink instance;

    public static synchronized BudsLink get() {
        if (instance == null) instance = new BudsLink();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile State state = new State();
    private volatile BluetoothSocket socket;
    private Thread thread;

    private byte[] acc = new byte[4096];
    private int accLen;

    public State state() { return state; }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void publish(final State s) {
        state = s;
        main.post(new Runnable() {
            public void run() {
                for (Listener l : listeners) l.onBudsState(s);
            }
        });
    }

    public synchronized void connect(final BluetoothDevice dev, final int attempts) {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(new Runnable() {
            public void run() { loop(dev, attempts); }
        }, "buds-link");
        thread.start();
    }

    public void close() {
        BluetoothSocket s = socket;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private void loop(BluetoothDevice dev, int attempts) {
        BluetoothSocket sock = null;
        for (int i = 0; i < attempts && sock == null; i++) {
            try {
                BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
                s.connect();
                sock = s;
            } catch (Exception e) {
                Log.w(TAG, "connect attempt " + i + " failed: " + e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
        if (sock == null) return;
        socket = sock;
        accLen = 0;

        State s = new State();
        s.connected = true;
        s.name = DeviceInfo.nameOf(dev);
        s.address = dev.getAddress();
        s.placeL = P_CASE;
        s.placeR = P_CASE;
        publish(s);

        // Сообщаем наушникам, что к ним подключился менеджер (тип 2 = не Samsung)
        send(MSG_MANAGER_INFO, new byte[]{1, 2, (byte) Math.min(Build.VERSION.SDK_INT, 127)});

        try {
            InputStream in = sock.getInputStream();
            byte[] buf = new byte[1024];
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                append(buf, n);
                parse();
            }
        } catch (Exception e) {
            Log.i(TAG, "link closed: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
            socket = null;
            publish(new State());
        }
    }

    private void append(byte[] b, int n) {
        if (accLen + n > acc.length) acc = Arrays.copyOf(acc, Math.max(acc.length * 2, accLen + n));
        System.arraycopy(b, 0, acc, accLen, n);
        accLen += n;
    }

    private void parse() {
        int i = 0;
        while (true) {
            while (i < accLen && (acc[i] & 0xFF) != SOM) i++;
            if (accLen - i < 4) break;
            int header = (acc[i + 1] & 0xFF) | ((acc[i + 2] & 0xFF) << 8);
            int size = header & 0x3FF;          // id + payload + crc
            if (size < 3) { i++; continue; }
            int total = 1 + 2 + size + 1;
            if (accLen - i < total) break;
            if ((acc[i + total - 1] & 0xFF) != EOM) { i++; continue; }

            int crcCalc = crc16(acc, i + 3, size - 2);
            int crcGot = (acc[i + 3 + size - 2] & 0xFF) | ((acc[i + 3 + size - 1] & 0xFF) << 8);
            if (crcCalc == crcGot) {
                int id = acc[i + 3] & 0xFF;
                byte[] payload = Arrays.copyOfRange(acc, i + 4, i + 4 + size - 3);
                handle(id, payload);
            }
            i += total;
        }
        if (i > 0) {
            System.arraycopy(acc, i, acc, 0, accLen - i);
            accLen -= i;
        }
    }

    private void handle(int id, byte[] p) {
        if (id == MSG_STATUS && p.length >= 7) {
            State s = state.copy();
            s.batL = p[1] & 0xFF;
            s.batR = p[2] & 0xFF;
            s.placeL = (p[5] & 0xF0) >> 4;
            s.placeR = p[5] & 0x0F;
            s.batCase = p[6] & 0xFF;
            if (p.length >= 8) {
                int ch = p[7] & 0xFF;
                s.chgL = (ch & 0x10) != 0;
                s.chgR = (ch & 0x04) != 0;
                s.chgCase = (ch & 0x01) != 0;
            }
            publish(s);
        } else if (id == MSG_EXT_STATUS && p.length >= 8) {
            State s = state.copy();
            s.revision = p[0] & 0xFF;
            s.batL = p[2] & 0xFF;
            s.batR = p[3] & 0xFF;
            s.placeL = (p[6] & 0xF0) >> 4;
            s.placeR = p[6] & 0x0F;
            s.batCase = p[7] & 0xFF;
            // Раскладка для Buds Live / Pro / Buds2 и новее (у Buds+ она другая)
            if (p.length >= 13 && !isBudsPlus(s.name)) {
                s.fwEq = p[9] & 0xFF;
                s.touchLocked = (p[10] & 0x80) == 0 ? 1 : 0;
                s.noise = p[12] & 0xFF;
            }
            publish(s);
        } else if (id == MSG_NOISE_CONTROLS_UPDATE && p.length >= 1) {
            State s = state.copy();
            s.noise = p[0] & 0xFF;
            publish(s);
        }
    }

    // ---------- отправка ----------

    static boolean isBudsPlus(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("buds+");
    }

    /** Есть ли у наушников режимы шумоподавления (Buds Pro / Buds2 и новее). */
    public static boolean hasNoiseControl(State s) {
        return s.connected && s.noise >= 0;
    }

    public void setNoiseControl(int mode) {
        send(MSG_NOISE_CONTROLS, new byte[]{(byte) mode});
        State s = state.copy();
        s.noise = mode;
        publish(s);
    }

    /** Встроенный эквалайзер наушников: 0 = обычный, 1..5 = бас, мягкий, динамичный, чистый, высокие. */
    public void setFirmwareEq(int preset) {
        send(MSG_EQUALIZER, new byte[]{(byte) preset});
        State s = state.copy();
        s.fwEq = preset;
        publish(s);
    }

    public void setTouchLock(boolean locked) {
        State cur = state;
        byte on = 1;
        byte[] payload = cur.revision >= 7
                ? new byte[]{(byte) (locked ? 0 : 1), on, on, on, on, on, on}
                : new byte[]{(byte) (locked ? 0 : 1), on, on, on, on};
        send(MSG_LOCK_TOUCHPAD, payload);
        State s = cur.copy();
        s.touchLocked = locked ? 1 : 0;
        publish(s);
    }

    /** Игровой режим наушников: звук не отстаёт от картинки (батарея садится чуть быстрее). */
    public void setGameMode(boolean on) {
        send(MSG_GAME_MODE, new byte[]{(byte) (on ? 1 : 0)});
    }

        public void findStart() { send(MSG_FIND_START, new byte[0]); }
    public void findStop() { send(MSG_FIND_STOP, new byte[0]); }

    private void send(int id, byte[] payload) {
        int size = 1 + payload.length + 2;
        final byte[] out = new byte[size + 4];
        out[0] = (byte) SOM;
        out[1] = (byte) (size & 0xFF);
        out[2] = (byte) ((size >> 8) & 0xFF);
        out[3] = (byte) id;
        System.arraycopy(payload, 0, out, 4, payload.length);
        int crc = crc16(out, 3, 1 + payload.length);
        out[4 + payload.length] = (byte) (crc & 0xFF);
        out[5 + payload.length] = (byte) ((crc >> 8) & 0xFF);
        out[out.length - 1] = (byte) EOM;
        writer.execute(new Runnable() {
            public void run() {
                BluetoothSocket s = socket;
                if (s == null) return;
                try {
                    OutputStream os = s.getOutputStream();
                    os.write(out);
                    os.flush();
                } catch (Exception e) {
                    Log.w(TAG, "send failed", e);
                }
            }
        });
    }

    /** CRC-16/XMODEM (poly 0x1021, init 0). */
    private static int crc16(byte[] d, int off, int len) {
        int crc = 0;
        for (int k = 0; k < len; k++) {
            crc ^= (d[off + k] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
