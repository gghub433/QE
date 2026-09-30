package lv.budseq;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Устройство, на котором запущен EQ: название (как его видит человек, а не код модели),
 * версия Android, что умеет (Bluetooth LE) и что мешает (экономия батареи, эффекты прошивки).
 * Узнаёт и Android-магнитолу: тогда фокус звука работает на её собственные динамики.
 */
public final class PhoneInfo {
    public final String brand, model, name, android;
    public final int sdk;
    public final boolean ble;
    /** Почему решили, что это магнитола (строка-причина), 0 — похоже на телефон. */
    public int headUnitWhy;

    private PhoneInfo(String brand, String model, String name, boolean ble) {
        this.brand = brand;
        this.model = model;
        this.name = name;
        this.android = Build.VERSION.RELEASE;
        this.sdk = Build.VERSION.SDK_INT;
        this.ble = ble;
    }

    private static PhoneInfo cached;

    public static synchronized PhoneInfo get(Context c) {
        if (cached == null) {
            String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
            String model = Build.MODEL == null ? "" : Build.MODEL;
            boolean ble = c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
            cached = new PhoneInfo(brand, model, marketName(c, brand, model), ble);
            cached.headUnitWhy = detectHeadUnit(c);
        }
        return cached;
    }

    // =====================================================================
    // Магнитола
    // =====================================================================

    public static final int KIND_AUTO = 0, KIND_PHONE = 1, KIND_HEAD_UNIT = 2;

    /** Платформы и марки Android-магнитол (Build.* в нижнем регистре). */
    private static final String[] HEAD_UNIT_WORDS = {
            "uis7862", "uis8581", "ac8227", "ac8257", "8227l", "mt8227", "microntek", "mtcd", "mtce", "mtcb",
            "fyt", "teyes", "joying", "dasaita", "xtrons", "atoto", "eonon", "seicane", "topway", "yt9216",
            "yt9213", "px5", "px6", "headunit", "head_unit", "autoradio", "carradio"};

    /** Выбор человека: авто / телефон / магнитола. */
    public static int kind(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("device_kind", KIND_AUTO);
    }

    public static void setKind(Context c, int k) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("device_kind", k).apply();
    }

    /** EQ стоит на магнитоле: звук идёт прямо в динамики машины. */
    public static boolean headUnit(Context c) {
        int k = kind(c);
        if (k != KIND_AUTO) return k == KIND_HEAD_UNIT;
        return get(c).headUnitWhy != 0;
    }

    static int detectHeadUnit(Context c) {
        PackageManager pm = c.getPackageManager();
        // Android Automotive OS — система самой машины (Volvo, Polestar, Renault, GM…)
        if (pm.hasSystemFeature("android.hardware.type.automotive")) return R.string.hu_why_aaos;
        if (isHeadUnitName(Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL + " " + Build.DEVICE
                + " " + Build.PRODUCT + " " + Build.BOARD + " " + Build.HARDWARE)) {
            return R.string.hu_why_platform;
        }
        // нет батареи и нет SIM, и это не ТВ-приставка — так выглядят магнитолы
        boolean tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        boolean phone = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        if (!tv && !phone && !hasBattery(c)) return R.string.hu_why_power;
        return 0;
    }

    static boolean isHeadUnitName(String s) {
        String all = s.toLowerCase(Locale.ROOT);
        for (String w : HEAD_UNIT_WORDS) if (all.contains(w)) return true;
        return false;
    }

    private static boolean hasBattery(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            return i == null || i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        } catch (Exception e) {
            return true;
        }
    }

    /** «Galaxy S23 Ultra», «Pixel 8», «Xiaomi 13T»… */
    private static String marketName(Context c, String brand, String model) {
        String sam = samsung(model);
        if (sam != null) return sam;
        // имя устройства в настройках: у большинства телефонов по умолчанию это и есть название модели
        try {
            String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (n != null && !n.trim().isEmpty() && !n.contains("'") && !n.contains("’")) return n.trim();
        } catch (Exception ignored) {
        }
        String b = cap(brand);
        return model.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT)) ? model : b + " " + model;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /** Коды популярных Samsung (SM-S918B → Galaxy S23 Ultra). */
    private static final String[][] SAMSUNG = {
            {"SM-S938", "Galaxy S25 Ultra"}, {"SM-S937", "Galaxy S25 Edge"}, {"SM-S936", "Galaxy S25+"}, {"SM-S931", "Galaxy S25"},
            {"SM-S928", "Galaxy S24 Ultra"}, {"SM-S926", "Galaxy S24+"}, {"SM-S921", "Galaxy S24"}, {"SM-S721", "Galaxy S24 FE"},
            {"SM-S918", "Galaxy S23 Ultra"}, {"SM-S916", "Galaxy S23+"}, {"SM-S911", "Galaxy S23"}, {"SM-S711", "Galaxy S23 FE"},
            {"SM-S908", "Galaxy S22 Ultra"}, {"SM-S906", "Galaxy S22+"}, {"SM-S901", "Galaxy S22"},
            {"SM-G998", "Galaxy S21 Ultra"}, {"SM-G996", "Galaxy S21+"}, {"SM-G991", "Galaxy S21"}, {"SM-G990", "Galaxy S21 FE"},
            {"SM-F966", "Galaxy Z Fold7"}, {"SM-F956", "Galaxy Z Fold6"}, {"SM-F946", "Galaxy Z Fold5"}, {"SM-F936", "Galaxy Z Fold4"},
            {"SM-F766", "Galaxy Z Flip7"}, {"SM-F741", "Galaxy Z Flip6"}, {"SM-F731", "Galaxy Z Flip5"}, {"SM-F721", "Galaxy Z Flip4"},
            {"SM-A566", "Galaxy A56"}, {"SM-A556", "Galaxy A55"}, {"SM-A546", "Galaxy A54"},
            {"SM-A366", "Galaxy A36"}, {"SM-A356", "Galaxy A35"}, {"SM-A346", "Galaxy A34"},
            {"SM-A266", "Galaxy A26"}, {"SM-A256", "Galaxy A25"}, {"SM-A166", "Galaxy A16"}, {"SM-A165", "Galaxy A16"},
            {"SM-A156", "Galaxy A15"}, {"SM-A155", "Galaxy A15"}, {"SM-M356", "Galaxy M35"}, {"SM-M156", "Galaxy M15"},
    };

    private static String samsung(String model) {
        String m = model.toUpperCase(Locale.ROOT);
        for (String[] s : SAMSUNG) if (m.startsWith(s[0])) return s[1];
        return null;
    }

    public boolean isSamsung() {
        return brand.equalsIgnoreCase("samsung");
    }

    /** Совет под прошивку: что может мешать EQ. 0 — советов нет. */
    public int tipRes() {
        String b = brand.toLowerCase(Locale.ROOT);
        if (b.equals("samsung")) return R.string.ph_tip_samsung;
        if (b.equals("xiaomi") || b.equals("redmi") || b.equals("poco")) return R.string.ph_tip_xiaomi;
        if (b.equals("huawei") || b.equals("honor")) return R.string.ph_tip_huawei;
        if (b.equals("oneplus") || b.equals("oppo") || b.equals("realme") || b.equals("vivo") || b.equals("iqoo")) {
            return R.string.ph_tip_bbk;
        }
        return 0;
    }

    /** Не усыпляет ли EQ экономия батареи. */
    public static boolean batteryUnrestricted(Context c) {
        try {
            PowerManager pm = c.getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /** Профиль «динамик телефона» с названием телефона; на магнитоле — «Динамики машины». */
    public String speakerName(Context c) {
        if (headUnit(c)) return c.getString(R.string.hu_speakers);
        return c.getString(R.string.phone_speaker_of, name);
    }
}
