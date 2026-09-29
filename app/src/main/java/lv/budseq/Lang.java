package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Язык интерфейса: системный или выбранный в приложении. */
public final class Lang {
    public static final String[] CODES = {"", "ru", "en", "lv", "uk"};
    public static final String[] NATIVE = {null, "Русский", "English", "Latviešu", "Українська"};

    private Lang() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static String get(Context c) {
        return prefs(c).getString("lang", "");
    }

    public static boolean chosen(Context c) {
        return prefs(c).getBoolean("lang_chosen", false);
    }

    public static void set(Context c, String code) {
        prefs(c).edit().putString("lang", code).putBoolean("lang_chosen", true).apply();
    }

    /** Оборачивает контекст в выбранную локаль (для Activity и Service). */
    public static Context wrap(Context base) {
        Theme.load(base);
        String code = get(base);
        if (code.isEmpty()) return base;
        Locale loc = new Locale(code);
        Locale.setDefault(loc);
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(loc);
        return base.createConfigurationContext(conf);
    }
}
