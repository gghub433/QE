package lv.budseq;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Следит за всеми подключёнными Bluetooth-устройствами:
 * наушники, колонки, машина, часы, клавиатуры и т.д. + их заряд.
 * И за проводными выходами звука: наушники в 3,5 мм / USB-C, USB-ЦАП, колонки по AUX, док, HDMI,
 * машина по USB (Android Auto) — у них адрес вида wired:… (DeviceInfo.WIRED).
 */
public final class DeviceMonitor {

    public interface Listener {
        /** added != null — устройство только что подключилось (для всплывающего окна). */
        void onDevicesChanged(DeviceInfo added);
    }

    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final String ACTION_ALIAS = "android.bluetooth.device.action.ALIAS_CHANGED";

    private static DeviceMonitor instance;

    public static synchronized DeviceMonitor get() {
        if (instance == null) instance = new DeviceMonitor();
        return instance;
    }

    private final LinkedHashMap<String, DeviceInfo> devices = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private AudioManager am;
    private boolean started;

    /** Провод вставили или вынули — пересчитать проводные выходы. */
    private final AudioDeviceCallback wiredCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refreshWired(true); }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refreshWired(false); }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(a) || UiModeManager.ACTION_EXIT_CAR_MODE.equals(a)) {
                // Android Auto по проводу: телефон в «режиме машины»
                refreshWired(UiModeManager.ACTION_ENTER_CAR_MODE.equals(a));
                return;
            }
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) {
                int st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (st == BluetoothAdapter.STATE_OFF || st == BluetoothAdapter.STATE_TURNING_OFF) clear();
                else if (st == BluetoothAdapter.STATE_ON) refresh();
                return;
            }
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                add(d, true);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                remove(d);
            } else if (ACTION_BATTERY.equals(a)) {
                setBattery(d, i.getIntExtra(EXTRA_BATTERY, -1));
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(a) || ACTION_ALIAS.equals(a)) {
                rename(d);
            } else {
                // A2DP / HEADSET: подключение звукового профиля
                int st = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (st == BluetoothProfile.STATE_CONNECTED) add(d, true);
            }
        }
    };

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(ACTION_ALIAS);
        f.addAction(ACTION_BATTERY);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(UiModeManager.ACTION_ENTER_CAR_MODE);
        f.addAction(UiModeManager.ACTION_EXIT_CAR_MODE);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, f, 0x2); // Context.RECEIVER_EXPORTED
        } else {
            app.registerReceiver(receiver, f);
        }
        am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        refresh();
        try {
            // сразу после регистрации система сообщает текущие выходы — они уже в списке, «новыми» не станут
            if (am != null) am.registerAudioDeviceCallback(wiredCallback, main);
        } catch (Exception ignored) {
        }
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        try { app.unregisterReceiver(receiver); } catch (Exception ignored) { }
        try {
            if (am != null) am.unregisterAudioDeviceCallback(wiredCallback);
        } catch (Exception ignored) {
        }
    }

    /** Перечитать список подключённых устройств (например, после выдачи разрешения). */
    public void refresh() {
        if (app == null) return;
        refreshWired(false);
        final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return;
        try {
            if (!ad.isEnabled()) {
                clear();
                return;
            }
            boolean changed = false;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                int st = DeviceInfo.connectedState(d);
                if (st == 1) {
                    add(d, false);
                } else if (st == 0) {
                    synchronized (this) {
                        if (devices.remove(d.getAddress()) != null) changed = true;
                    }
                }
            }
            if (changed) notifyChanged(null);

            BluetoothProfile.ServiceListener sl = new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) add(d, false);
                    } catch (SecurityException ignored) {
                    }
                    try { ad.closeProfileProxy(profile, proxy); } catch (Exception ignored) { }
                }

                public void onServiceDisconnected(int profile) { }
            };
            ad.getProfileProxy(app, sl, BluetoothProfile.A2DP);
            ad.getProfileProxy(app, sl, BluetoothProfile.HEADSET);
        } catch (SecurityException e) {
            // ещё нет разрешения «Устройства поблизости»
        }
    }

    private void add(BluetoothDevice d, boolean fresh) {
        try {
            if (d.getBondState() != BluetoothDevice.BOND_BONDED) return;
        } catch (SecurityException e) {
            return;
        }
        DeviceInfo info;
        boolean isNew;
        synchronized (this) {
            info = devices.get(d.getAddress());
            isNew = info == null;
            if (isNew) {
                info = DeviceInfo.from(d);
                int override = DeviceSettings.get(app, info.address).typeOverride;
                if (override >= 0) info.type = override;
                devices.put(info.address, info);
            } else {
                info.device = d;
                int b = DeviceInfo.batteryOf(d);
                if (b >= 0) info.setBattery(b);
            }
        }
        if (info.isGalaxyBuds() && !BudsLink.get().state().connected) {
            final BluetoothDevice dev = d;
            main.postDelayed(new Runnable() {
                public void run() { BudsLink.get().connect(dev, 3); }
            }, fresh ? 1500 : 0);
        }
        if (isNew) notifyChanged(fresh ? info : null);
    }

    private void remove(BluetoothDevice d) {
        DeviceInfo info;
        synchronized (this) {
            info = devices.remove(d.getAddress());
        }
        if (info == null) return;
        if (info.isGalaxyBuds() && info.address.equals(BudsLink.get().state().address)) {
            BudsLink.get().close();
        }
        notifyChanged(null);
    }

    private void setBattery(BluetoothDevice d, int level) {
        DeviceInfo info;
        synchronized (this) {
            info = devices.get(d.getAddress());
        }
        if (info == null) {
            add(d, false);
            synchronized (this) {
                info = devices.get(d.getAddress());
            }
            if (info == null) return;
        }
        info.setBattery(level);
        notifyChanged(null);
    }

    private void rename(BluetoothDevice d) {
        DeviceInfo info;
        synchronized (this) {
            info = devices.get(d.getAddress());
        }
        if (info == null) return;
        info.name = DeviceInfo.nameOf(d);
        notifyChanged(null);
    }

    private void clear() {
        synchronized (this) {
            boolean any = false;
            Iterator<Map.Entry<String, DeviceInfo>> it = devices.entrySet().iterator();
            while (it.hasNext()) {
                if (!it.next().getValue().isWired()) {
                    it.remove();
                    any = true;
                }
            }
            if (!any) return;
        }
        BudsLink.get().close();
        notifyChanged(null);
    }

    private void notifyChanged(final DeviceInfo added) {
        main.post(new Runnable() {
            public void run() {
                for (Listener l : listeners) l.onDevicesChanged(added);
            }
        });
    }

    /** Звуковые устройства первыми, внутри — последние подключённые первыми. */
    public synchronized List<DeviceInfo> list() {
        ArrayList<DeviceInfo> l = new ArrayList<>(devices.values());
        Collections.sort(l, new Comparator<DeviceInfo>() {
            public int compare(DeviceInfo a, DeviceInfo b) {
                if (a.isAudio() != b.isAudio()) return a.isAudio() ? -1 : 1;
                return Long.compare(b.connectedAt, a.connectedAt);
            }
        });
        return l;
    }

    /**
     * Куда идёт звук: последнее подключённое звуковое устройство. Если подключены и провод, и Bluetooth —
     * на Android 13+ спрашиваем систему, какой выход сейчас играет музыку.
     */
    public DeviceInfo primaryAudio() {
        List<DeviceInfo> l = list();
        DeviceInfo wired = null, bt = null;
        for (DeviceInfo d : l) {
            if (!d.isAudio()) continue;
            if (d.isWired()) {
                if (wired == null) wired = d;
            } else if (bt == null) {
                bt = d;
            }
        }
        if (wired != null && bt != null) {
            int routed = routedType();
            if (isBluetoothType(routed)) return bt;
            if (isWiredType(routed)) return wired;
        }
        for (DeviceInfo d : l) {
            if (d.isAudio()) return d;
        }
        return null;
    }

    // =====================================================================
    // Провод: 3,5 мм, USB-C, USB-ЦАП, AUX, док, HDMI, машина по USB
    // =====================================================================

    /** Пересчитать проводные выходы. fresh — подключили только что (для всплывающего окна и звука). */
    void refreshWired(boolean fresh) {
        if (app == null) return;
        if (am == null) am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        boolean car = carMode();
        LinkedHashMap<String, DeviceInfo> found = new LinkedHashMap<>();
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                DeviceInfo w = wiredInfo(d, car);
                if (w == null) continue;
                DeviceInfo prev = found.get(w.address);
                // USB-гарнитура бывает видна и как «USB-устройство» — оставляем вариант «наушники»
                if (prev == null || w.type == DeviceInfo.T_HEADPHONES) found.put(w.address, w);
            }
        } catch (Exception ignored) {
        }
        if (car && found.isEmpty() && !hasBluetoothCar()) {
            // Android Auto по кабелю: звук уходит в машину, отдельного выхода система может не показать
            DeviceInfo w = new DeviceInfo(DeviceInfo.WIRED + "car");
            w.name = app.getString(R.string.wired_car);
            w.type = w.detectedType = DeviceInfo.T_CAR;
            found.put(w.address, w);
        }
        DeviceInfo added = null;
        boolean changed = false;
        synchronized (this) {
            Iterator<Map.Entry<String, DeviceInfo>> it = devices.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, DeviceInfo> e = it.next();
                if (e.getValue().isWired() && !found.containsKey(e.getKey())) {
                    it.remove();
                    changed = true;
                }
            }
            for (DeviceInfo w : found.values()) {
                if (devices.containsKey(w.address)) continue;
                int override = DeviceSettings.get(app, w.address).typeOverride;
                if (override >= 0) w.type = override;
                w.connectedAt = System.currentTimeMillis();
                devices.put(w.address, w);
                changed = true;
                if (fresh && added == null) added = w;
            }
        }
        if (changed) notifyChanged(added);
    }

    /** Проводной выход → устройство EQ (или null, если это не внешний выход). */
    private DeviceInfo wiredInfo(AudioDeviceInfo d, boolean car) {
        CharSequence pn = d.getProductName();
        String product = pn == null ? "" : pn.toString().trim();
        // встроенный разъём часто называется моделью телефона — такое имя ничего не говорит
        if (product.equalsIgnoreCase(Build.MODEL) || product.equalsIgnoreCase(Build.DEVICE)) product = "";
        String key, name;
        int type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                key = "jack";
                type = DeviceInfo.T_HEADPHONES;
                name = app.getString(R.string.wired_jack);
                break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                key = "usb:" + slug(product);
                type = DeviceInfo.T_HEADPHONES;
                name = product.isEmpty() ? app.getString(R.string.wired_usb_phones) : product;
                break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                key = "usb:" + slug(product);
                type = DeviceInfo.T_SPEAKER;
                name = product.isEmpty() ? app.getString(R.string.wired_usb) : product;
                break;
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                key = "aux";
                type = DeviceInfo.T_SPEAKER;
                name = app.getString(R.string.wired_aux);
                break;
            case AudioDeviceInfo.TYPE_DOCK:
                key = "dock";
                type = DeviceInfo.T_SPEAKER;
                name = app.getString(R.string.wired_dock);
                break;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case 29: // TYPE_HDMI_EARC (Android 12)
                key = "hdmi";
                type = DeviceInfo.T_TV;
                name = app.getString(R.string.wired_hdmi);
                break;
            default:
                return null;
        }
        if (!product.isEmpty()) {
            int byName = DeviceInfo.byName(product.toLowerCase(Locale.ROOT));
            if (byName >= DeviceInfo.T_EARBUDS && byName <= DeviceInfo.T_TV) type = byName;
        }
        // телефон в режиме машины (Android Auto, автомобильный док): провод идёт в машину
        if (car && type != DeviceInfo.T_HEADPHONES) type = DeviceInfo.T_CAR;
        DeviceInfo w = new DeviceInfo(DeviceInfo.WIRED + key);
        w.name = name;
        w.type = w.detectedType = type;
        w.detectedBy = DeviceInfo.BY_PORT;
        w.outputId = d.getId();
        return w;
    }

    private static String slug(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9а-яё]+", "-");
        return r.isEmpty() ? "audio" : r;
    }

    private boolean carMode() {
        try {
            UiModeManager um = (UiModeManager) app.getSystemService(Context.UI_MODE_SERVICE);
            return um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized boolean hasBluetoothCar() {
        for (DeviceInfo d : devices.values()) {
            if (!d.isWired() && d.type == DeviceInfo.T_CAR) return true;
        }
        return false;
    }

    /** Тип выхода, куда сейчас идёт музыка (Android 13+), иначе -1. */
    private int routedType() {
        if (am == null || Build.VERSION.SDK_INT < 33) return -1;
        try {
            AudioAttributes aa = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            Object list = AudioManager.class.getMethod("getAudioDevicesForAttributes", AudioAttributes.class).invoke(am, aa);
            if (list instanceof List && !((List<?>) list).isEmpty()) {
                Object dev = ((List<?>) list).get(0);
                Object t = dev.getClass().getMethod("getType").invoke(dev);
                return t instanceof Integer ? (Integer) t : -1;
            }
        } catch (Throwable ignored) {
            // метод недоступен на этой прошивке — решаем по времени подключения
        }
        return -1;
    }

    /** Bluetooth-выходы: SCO, A2DP, слуховой аппарат, LE Audio (гарнитура, колонка, трансляция). */
    static boolean isBluetoothType(int t) {
        return t == 7 || t == 8 || t == 23 || t == 26 || t == 27 || t == 30;
    }

    static boolean isWiredType(int t) {
        return t == 3 || t == 4 || t == 5 || t == 6 || t == 9 || t == 10 || t == 11 || t == 12 || t == 13
                || t == 19 || t == 22 || t == 29;
    }

    public synchronized DeviceInfo find(String address) {
        return address == null ? null : devices.get(address);
    }

    /** Пользователь поменял «как выглядит» — применить. */
    public void applyOverride(String address) {
        DeviceInfo info = find(address);
        if (info == null || app == null) return;
        int override = DeviceSettings.get(app, address).typeOverride;
        info.type = override >= 0 ? override : info.detectedType;
        notifyChanged(null);
    }
}
