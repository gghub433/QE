package lv.budseq;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Оформление: цвет акцента (кнопки, ползунки, выбранные чипы) и цвет волны.
 * Загружается в Lang.wrap — то есть до создания любого экрана, службы или виджета.
 * После смены цвета экран пересоздаётся.
 * «Живой» цвет: от обложки текущего трека — волна, выбранные чипы и свечение в машине
 * плавно (450 мс) перекрашиваются в него; без обложки — обратно в цвета темы.
 */
public final class Theme {
    public static final int DEFAULT_ACCENT = 0xFF3E7BFA;
    /** Палитра: синий (по умолчанию), фиолетовый, розовый, коралловый, янтарный, зелёный, бирюзовый, красный. */
    public static final int[] PALETTE = {
            0xFF3E7BFA, 0xFF8B5CF6, 0xFFE0457B, 0xFFFF6B3D, 0xFFF5B82E, 0xFF2EC27E, 0xFF14B8C4, 0xFFEF4444};
    /** Волна «как акцент». */
    public static final int WAVE_AS_ACCENT = 0;

    private static volatile int accent = DEFAULT_ACCENT, wave = WAVE_AS_ACCENT;
    private static volatile boolean cover = true;

    /** Слушатель «живого» цвета: акцент (чипы, свечение) и волна. */
    public interface LiveListener {
        void onLiveColor(int accentColor, int waveColor);
    }

    private static final CopyOnWriteArrayList<LiveListener> listeners = new CopyOnWriteArrayList<>();
    private static int coverColor;                 // 0 — обложки нет, цвета темы
    private static int liveAccent, liveWave;       // 0 — ещё не задан
    private static ValueAnimator anim;

    private Theme() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static void load(Context c) {
        SharedPreferences p = prefs(c);
        accent = p.getInt("accent", DEFAULT_ACCENT);
        wave = p.getInt("wave_color", WAVE_AS_ACCENT);
        cover = p.getBoolean("cover_color", true);
    }

    public static boolean coverEnabled() {
        return cover;
    }

    public static void setCoverEnabled(Context c, boolean on) {
        cover = on;
        prefs(c).edit().putBoolean("cover_color", on).apply();
        setCover(coverColor);
    }

    /** Цвет обложки сейчас (0 — нет или выключено). */
    public static int cover() {
        return cover ? coverColor : 0;
    }

    /** Текущий «живой» акцент — для чипов и свечения. */
    public static int liveAccent() {
        return liveAccent != 0 ? liveAccent : accent;
    }

    /** Текущий «живой» цвет волны. */
    public static int liveWave() {
        return liveWave != 0 ? liveWave : wave();
    }

    public static void addListener(LiveListener l) {
        listeners.add(l);
    }

    public static void removeListener(LiveListener l) {
        listeners.remove(l);
    }

    /**
     * Новая обложка: color — её доминирующий цвет (0 — нет обложки или она серая).
     * Вызывать из главного потока; перекраска плавная.
     */
    public static void setCover(int color) {
        coverColor = color;
        final int toA = cover && color != 0 ? color : accent;
        final int toW = cover && color != 0 ? color : wave();
        final int fromA = liveAccent(), fromW = liveWave();
        if (anim != null) anim.cancel();
        if (fromA == toA && fromW == toW) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(450);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                liveAccent = blend(fromA, toA, t);
                liveWave = blend(fromW, toW, t);
                for (LiveListener l : listeners) l.onLiveColor(liveAccent, liveWave);
            }
        });
        anim.start();
    }

    static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
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
        liveAccent = 0;
        prefs(c).edit().putInt("accent", color).apply();
    }

    public static void setWave(Context c, int color) {
        wave = color;
        liveWave = 0;
        prefs(c).edit().putInt("wave_color", color).apply();
    }
}
