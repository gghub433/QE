package lv.budseq;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Следит за всеми подключёнными Bluetooth-устройствами:
 * наушники, колонки, машина, часы, клавиатуры и т.д. + их заряд.
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
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
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
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, f, 0x2); // Context.RECEIVER_EXPORTED
        } else {
            app.registerReceiver(receiver, f);
        }
        refresh();
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        try { app.unregisterReceiver(receiver); } catch (Exception ignored) { }
    }

    /** Перечитать список подключённых устройств (например, после выдачи разрешения). */
    public void refresh() {
        if (app == null) return;
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
                if (b >= 0) info.battery = b;
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
        info.battery = level >= 0 && level <= 100 ? level : -1;
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
            if (devices.isEmpty()) return;
            devices.clear();
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

    public DeviceInfo primaryAudio() {
        for (DeviceInfo d : list()) {
            if (d.isAudio()) return d;
        }
        return null;
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
