package lv.budseq;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Заряд AirPods по BLE-рекламе Apple (manufacturer id 0x004C, сообщение «proximity pairing» 0x07).
 * Открытый кейс или вынутые наушники рассылают: модель, заряд L/R/кейса (шаг 10%), зарядку, «в ухе».
 * Подключаться к AirPods не нужно — достаточно слушать эфир рядом (сигнал не слабее -70 dBm).
 */
public final class AirPods {
    private static final String TAG = "AirPods";
    private static final int APPLE = 0x004C;
    private static final long FRESH_MS = 15000;
    private static final int MIN_RSSI = -70;

    public interface Listener {
        void onAirPods(BudsLink.State s);
    }

    private static AirPods instance;

    public static synchronized AirPods get() {
        if (instance == null) instance = new AirPods();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile BudsLink.State state = new BudsLink.State();
    private BluetoothLeScanner scanner;
    private boolean scanning, fast;
    private long lastSeen;
    private int lastRssi = -127;
    /** Адрес подключённых AirPods (классический Bluetooth) — чтобы связать эфир с устройством. */
    private String address = "", name = "";

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            handle(r);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed " + errorCode);
            scanning = false;
        }
    };

    /** Данные устарели (кейс закрыли, отошли далеко) — считаем, что их нет. */
    private final Runnable staleCheck = new Runnable() {
        public void run() {
            if (state.connected && SystemClock.elapsedRealtime() - lastSeen > FRESH_MS) {
                BudsLink.State s = new BudsLink.State();
                s.address = address;
                s.name = name;
                publish(s);
            }
            if (scanning) main.postDelayed(this, 5000);
        }
    };

    public BudsLink.State state() { return state; }

    public void addListener(Listener l) { listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private void publish(final BudsLink.State s) {
        state = s;
        main.post(new Runnable() {
            public void run() {
                for (Listener l : listeners) l.onAirPods(s);
            }
        });
    }

    /** Разрешение на BLE-поиск: Android 12+ — «Устройства поблизости», раньше — геолокация. */
    public static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 31) return granted(c, "android.permission.BLUETOOTH_SCAN");
        return granted(c, "android.permission.ACCESS_FINE_LOCATION");
    }

    static String permission() {
        return Build.VERSION.SDK_INT >= 31 ? "android.permission.BLUETOOTH_SCAN" : "android.permission.ACCESS_FINE_LOCATION";
    }

    private static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Слушать эфир. fast = экран открыт (быстрый поиск), иначе экономный режим.
     * device — подключённые по Bluetooth AirPods (их имя и адрес попадут в состояние).
     */
    public synchronized void start(Context c, DeviceInfo device, boolean fastMode) {
        if (device != null) {
            address = device.address;
            name = device.name;
        }
        if (!hasPermission(c)) return;
        if (scanning && fast == fastMode) return;
        stopScan();
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) return;
            scanner = ad.getBluetoothLeScanner();
            if (scanner == null) return;
            List<ScanFilter> filters = new ArrayList<>();
            // только сообщения 0x07 (proximity pairing), длина любая
            filters.add(new ScanFilter.Builder()
                    .setManufacturerData(APPLE, new byte[]{7, 0}, new byte[]{(byte) 0xFF, 0})
                    .build());
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(fastMode ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            scanner.startScan(filters, settings, callback);
            scanning = true;
            fast = fastMode;
            main.removeCallbacks(staleCheck);
            main.postDelayed(staleCheck, 5000);
        } catch (SecurityException e) {
            Log.w(TAG, "no permission", e);
        } catch (Exception e) {
            Log.w(TAG, "start failed", e);
        }
    }

    public synchronized void stop() {
        stopScan();
        main.removeCallbacks(staleCheck);
        if (state.connected) publish(new BudsLink.State());
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(callback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    public boolean fresh() {
        return state.connected && SystemClock.elapsedRealtime() - lastSeen <= FRESH_MS;
    }

    private void handle(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        if (rec == null) return;
        byte[] data = rec.getManufacturerSpecificData(APPLE);
        int rssi = r.getRssi();
        if (data == null || rssi < MIN_RSSI) return;
        long now = SystemClock.elapsedRealtime();
        // рядом может быть чужой кейс: берём самый громкий из недавних
        if (state.connected && now - lastSeen < 3000 && rssi < lastRssi - 10) return;
        BudsLink.State s = parse(data);
        if (s == null) return;
        s.address = address;
        s.name = name.isEmpty() ? modelName(s.revision) : name;
        lastSeen = now;
        lastRssi = rssi;
        publish(s);
    }

    /**
     * Разбор сообщения proximity pairing (как в OpenPods):
     * [0]=0x07 [1]=длина [3..4]=модель [5]=статус [6]=заряд L/R [7]=зарядка + кейс.
     * Модель кладём в revision.
     */
    static BudsLink.State parse(byte[] d) {
        if (d == null || d.length < 9 || d[0] != 7) return null;
        int model = (d[3] & 0xFF) | ((d[4] & 0xFF) << 8);
        int status = d[5] & 0xFF;
        boolean flip = (status & 0x20) == 0;            // «текущий» наушник — правый
        int bat = d[6] & 0xFF, chg = (d[7] & 0xF0) >> 4, caseBat = d[7] & 0x0F;
        int left = flip ? (bat >> 4) : (bat & 0x0F);
        int right = flip ? (bat & 0x0F) : (bat >> 4);

        BudsLink.State s = new BudsLink.State();
        s.connected = true;
        s.revision = model;
        s.batL = level(left);
        s.batR = level(right);
        s.batCase = level(caseBat);
        s.chgL = (chg & (flip ? 0x02 : 0x01)) != 0;
        s.chgR = (chg & (flip ? 0x01 : 0x02)) != 0;
        s.chgCase = (chg & 0x04) != 0;
        boolean inEarL = (status & (flip ? 0x08 : 0x02)) != 0;
        boolean inEarR = (status & (flip ? 0x02 : 0x08)) != 0;
        s.placeL = place(s.batL, inEarL, s.chgL);
        s.placeR = place(s.batR, inEarR, s.chgR);
        return s;
    }

    /** 0..10 → 0..100%, 15 = нет данных (наушник не на связи). */
    private static int level(int n) {
        return n <= 10 ? Math.min(100, n * 10) : -1;
    }

    /** В кейсе наушник заряжается — это и есть признак «лежит в кейсе». */
    private static int place(int bat, boolean inEar, boolean charging) {
        if (bat < 0) return BudsLink.P_DISCONNECTED;
        if (inEar) return BudsLink.P_WEARING;
        return charging ? BudsLink.P_CASE : BudsLink.P_IDLE;
    }

    public static String modelName(int id) {
        switch (id) {
            case 0x2002: return "AirPods";
            case 0x200F: return "AirPods 2";
            case 0x2013: return "AirPods 3";
            case 0x2019: return "AirPods 4";
            case 0x201B: return "AirPods 4 ANC";
            case 0x200E: return "AirPods Pro";
            case 0x2014: return "AirPods Pro 2";
            case 0x2024: return "AirPods Pro 2 (USB-C)";
            case 0x200A: return "AirPods Max";
            default: return "AirPods";
        }
    }
}
