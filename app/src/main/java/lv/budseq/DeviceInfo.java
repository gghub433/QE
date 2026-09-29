package lv.budseq;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.os.Build;
import android.os.ParcelUuid;

import java.lang.reflect.Method;
import java.util.Locale;

/** Подключённое Bluetooth-устройство и определение его типа (как оно «выглядит»). */
public final class DeviceInfo {
    public static final int T_OTHER = 0, T_GALAXY_BUDS = 1, T_EARBUDS = 2, T_HEADPHONES = 3,
            T_SPEAKER = 4, T_CAR = 5, T_HEADSET = 6, T_TV = 7, T_WATCH = 8, T_KEYBOARD = 9,
            T_MOUSE = 10, T_GAMEPAD = 11, T_COMPUTER = 12, T_PHONE = 13;

    /** RFCOMM-сервис Galaxy Buds (Buds+, Live, Pro, Buds2 и новее). */
    private static final String SAMSUNG_SPP = "2e73a4ad-332d-41fc-90e2-16bef06523f2";

    public final String address;
    public String name;
    public int type;
    /** Тип, определённый автоматически (type может быть изменён вручную). */
    public int detectedType;
    public int battery = -1;
    public long connectedAt;
    public BluetoothDevice device;

    DeviceInfo(String address) {
        this.address = address;
    }

    /** Звуковые устройства: наушники, колонки, машина, ТВ. */
    public boolean isAudio() {
        return type >= T_GALAXY_BUDS && type <= T_TV;
    }

    /** Наушники любого вида — для них есть AutoEQ. */
    public boolean isHeadphones() {
        return type == T_GALAXY_BUDS || type == T_EARBUDS || type == T_HEADPHONES || type == T_HEADSET;
    }

    public boolean isGalaxyBuds() {
        return detectedType == T_GALAXY_BUDS;
    }

    public static DeviceInfo from(BluetoothDevice d) {
        DeviceInfo i = new DeviceInfo(d.getAddress());
        i.device = d;
        i.name = nameOf(d);
        i.detectedType = detect(d, i.name);
        i.type = i.detectedType;
        i.battery = batteryOf(d);
        i.connectedAt = System.currentTimeMillis();
        return i;
    }

    public static String nameOf(BluetoothDevice d) {
        String n = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) n = d.getAlias();
            if (n == null || n.isEmpty()) n = d.getName();
        } catch (SecurityException ignored) {
        }
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    /** Заряд, который устройство сообщает системе (через HFP/AVRCP). -1 = неизвестно. */
    public static int batteryOf(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object v = m.invoke(d);
            int b = v instanceof Integer ? (Integer) v : -1;
            return b >= 0 && b <= 100 ? b : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 1 = подключено, 0 = нет, -1 = узнать нельзя. */
    public static int connectedState(BluetoothDevice d) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object v = m.invoke(d);
            return v instanceof Boolean ? ((Boolean) v ? 1 : 0) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean isGalaxyBuds(BluetoothDevice d, String lowerName) {
        try {
            ParcelUuid[] uu = d.getUuids();
            if (uu != null) {
                for (ParcelUuid u : uu) {
                    if (SAMSUNG_SPP.equalsIgnoreCase(u.toString())) return true;
                }
            }
        } catch (SecurityException ignored) {
        }
        return lowerName.contains("galaxy buds");
    }

    public static int detect(BluetoothDevice d, String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (isGalaxyBuds(d, n)) return T_GALAXY_BUDS;

        int cls = -1, major = -1;
        try {
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null) {
                cls = bc.getDeviceClass();
                major = bc.getMajorDeviceClass();
            }
        } catch (SecurityException ignored) {
        }

        // 1) «сильные» классы — им доверяем сразу
        switch (cls) {
            case BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO:
            case BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO:
                if (byName(n) == T_HEADPHONES || byName(n) == T_EARBUDS) break;
                return T_SPEAKER;
            case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                return T_CAR;
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER:
            case BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR:
            case BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX:
                return T_TV;
            case BluetoothClass.Device.WEARABLE_WRIST_WATCH:
                return T_WATCH;
            default:
                break;
        }
        if (major == BluetoothClass.Device.Major.PERIPHERAL) {
            if ((cls & 0x40) != 0) return T_KEYBOARD;
            if ((cls & 0x80) != 0) return T_MOUSE;
            return T_GAMEPAD;
        }

        // 2) по названию
        int byName = byName(n);
        if (byName >= 0) return byName;

        // 3) «слабые» классы
        switch (cls) {
            case BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES:
                return T_HEADPHONES;
            case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                return T_EARBUDS;
            case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                return T_HEADSET;
            default:
                break;
        }
        switch (major) {
            case BluetoothClass.Device.Major.AUDIO_VIDEO:
                return T_HEADPHONES;
            case BluetoothClass.Device.Major.WEARABLE:
                return T_WATCH;
            case BluetoothClass.Device.Major.COMPUTER:
                return T_COMPUTER;
            case BluetoothClass.Device.Major.PHONE:
                return T_PHONE;
            default:
                return T_OTHER;
        }
    }

    private static boolean has(String n, String... keys) {
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /** Определение по названию. -1 = не узнали. */
    static int byName(String n) {
        if (n.isEmpty()) return -1;
        if (has(n, "airpods max")) return T_HEADPHONES;
        if (has(n, "airpods", "buds", "pods", "tws", "earbud", "wf-", "liberty", "elite", "earfun",
                "soundpeats", "qcy", "haylou", "enco", "nothing ear", "ear (", "ear(", "motif", "tozo")) {
            return T_EARBUDS;
        }
        if (has(n, "wh-", "headphone", "xm3", "xm4", "xm5", "qc35", "qc45", "quietcomfort", "momentum",
                "life q", "space one", "tune 5", "tune 7", "px7", "hd 4", "hd4", "studio", "solo", "major",
                "monitor ii")) {
            return T_HEADPHONES;
        }
        if (has(n, "watch", "fitbit", "mi band", "smart band", "amazfit", "garmin", "gear s")) return T_WATCH;
        if (has(n, "speaker", "flip", "charge", "jbl go", "clip", "xtreme", "pulse", "boombox", "partybox",
                "boom", "soundlink", "soundcore", "motion", "marshall", "emberton", "stanmore", "acton",
                "sonos", "srs-", "ult field", "tribit", "soundbar", "sound bar", "homepod", "echo", "portable")) {
            return T_SPEAKER;
        }
        if (has(n, "car", "auto", "toyota", "volkswagen", "vw ", "bmw", "audi", "ford", "honda", "kia",
                "hyundai", "mazda", "skoda", "škoda", "mercedes", "renault", "peugeot", "opel", "nissan",
                "volvo", "tesla", "lexus", "uconnect", "mylink", "sensus")) {
            return T_CAR;
        }
        if (n.equals("tv") || has(n, "[tv]", " tv", "tv ", "bravia", "television")) return T_TV;
        if (has(n, "keyboard", "keys")) return T_KEYBOARD;
        if (has(n, "mouse", "mx master", "mx anywhere")) return T_MOUSE;
        if (has(n, "controller", "gamepad", "dualsense", "dualshock", "xbox", "joy-con", "8bitdo")) return T_GAMEPAD;
        return -1;
    }

    public static int labelRes(int t) {
        switch (t) {
            case T_GALAXY_BUDS: return R.string.t_buds;
            case T_EARBUDS: return R.string.t_earbuds;
            case T_HEADPHONES: return R.string.t_headphones;
            case T_SPEAKER: return R.string.t_speaker;
            case T_CAR: return R.string.t_car;
            case T_HEADSET: return R.string.t_headset;
            case T_TV: return R.string.t_tv;
            case T_WATCH: return R.string.t_watch;
            case T_KEYBOARD: return R.string.t_keyboard;
            case T_MOUSE: return R.string.t_mouse;
            case T_GAMEPAD: return R.string.t_gamepad;
            case T_COMPUTER: return R.string.t_computer;
            case T_PHONE: return R.string.t_phone;
            default: return R.string.t_other;
        }
    }

    public static int iconRes(int t) {
        switch (t) {
            case T_GALAXY_BUDS:
            case T_EARBUDS: return R.drawable.ic_earbuds;
            case T_HEADPHONES: return R.drawable.ic_headset;
            case T_HEADSET: return R.drawable.ic_headset_mic;
            case T_SPEAKER: return R.drawable.ic_speaker;
            case T_CAR: return R.drawable.ic_car;
            case T_TV: return R.drawable.ic_tv;
            case T_WATCH: return R.drawable.ic_watch;
            case T_KEYBOARD: return R.drawable.ic_keyboard;
            case T_MOUSE: return R.drawable.ic_mouse;
            case T_GAMEPAD: return R.drawable.ic_gamepad;
            case T_COMPUTER: return R.drawable.ic_laptop;
            case T_PHONE: return R.drawable.ic_phone;
            default: return R.drawable.ic_bluetooth;
        }
    }
}
