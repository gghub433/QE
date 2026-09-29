package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Оформление: цвет акцента (кнопки, ползунки, выбранные чипы) и цвет волны.
 * Загружается в Lang.wrap — то есть до создания любого экрана, службы или виджета.
 * После смены цвета экран пересоздаётся.
 */
public final class Theme {
    public static final int DEFAULT_ACCENT = 0xFF3E7BFA;
    /** Палитра: синий (по умолчанию), фиолетовый, розовый, коралловый, янтарный, зелёный, бирюзовый, красный. */
    public static final int[] PALETTE = {
            0xFF3E7BFA, 0xFF8B5CF6, 0xFFE0457B, 0xFFFF6B3D, 0xFFF5B82E, 0xFF2EC27E, 0xFF14B8C4, 0xFFEF4444};
    /** Волна «как акцент». */
    public static final int WAVE_AS_ACCENT = 0;

    private static volatile int accent = DEFAULT_ACCENT, wave = WAVE_AS_ACCENT;

    private Theme() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static void load(Context c) {
        SharedPreferences p = prefs(c);
        accent = p.getInt("accent", DEFAULT_ACCENT);
        wave = p.getInt("wave_color", WAVE_AS_ACCENT);
    }

    public static int accent() {
        return accent;
    }

    /** Цвет волны из темы (без обложки). */
    public static int wave() {
        return wave == WAVE_AS_ACCENT ? accent : wave;
    }

    public static int waveSetting() {
        return wave;
    }

    public static void setAccent(Context c, int color) {
        accent = color;
        prefs(c).edit().putInt("accent", color).apply();
    }

    public static void setWave(Context c, int color) {
        wave = color;
        prefs(c).edit().putInt("wave_color", color).apply();
    }
}
