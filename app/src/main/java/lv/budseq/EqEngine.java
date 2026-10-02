package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Virtualizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Звуковой движок на DynamicsProcessing (Android 9+):
 *  - эквалайзер на 9 / 15 / 31 полосу;
 *  - «панч»: компрессор только для низких частот;
 *  - выравнивание громкости (мягкий компрессор на всём диапазоне);
 *  - усиление громкости с лимитером, баланс Л/П;
 *  - профили: свои настройки для каждого устройства;
 *  - AutoEQ: коррекция наушников складывается с кривой пользователя (хранится отдельно).
 */
public final class EqEngine {
    private static final String TAG = "EQ";

    public static final float MIN_DB = -12f, MAX_DB = 12f;
    public static final float MAX_BOOST = 12f;
    public static final int[] BAND_COUNTS = {9, 15, 31};

    private static final float[] F9 = {63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final float[] F15 = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    private static final float[] F31 = {20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};

    /** Встроенные пресеты (в 9 полосах; для 15/31 пересчитываются). */
    public static final int[] PRESET_NAMES = {
            R.string.p_flat, R.string.p_hard_bass, R.string.p_bass_max, R.string.p_bass,
            R.string.p_v, R.string.p_vocal, R.string.p_clarity, R.string.p_soft};
    public static final float[] PRESET_PRE = {0, -6, -9, -3, -3, -2, -2, 0};
    public static final float[] PRESET_PUNCH = {0, 0.5f, 0.8f, 0.3f, 0.2f, 0, 0, 0};
    public static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {9, 7, 3, 0, -1, 0, 1, 2, 1},
            {12, 9, 4, -1, -2, -1, 1, 2, 0},
            {6, 5, 3, 1, 0, 0, 0, 0, 0},
            {5, 4, 1, -1, -2, -1, 1, 3, 4},
            {-2, -1, 0, 2, 3, 3, 2, 0, -1},
            {0, 0, -1, 0, 1, 2, 3, 3, 2},
            {1, 1, 0, 0, -1, -2, -3, -3, -4},
    };

    private static EqEngine instance;

    public static synchronized EqEngine get(Context ctx) {
        if (instance == null) instance = new EqEngine(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Map<Integer, DynamicsProcessing> effects = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ---- текущие настройки ----
    private int bands = 9;
    public float[] gains = new float[9];
    public float preamp;       // -12 … 0 dB
    public float punch;        // 0 … 1
    public float boost;        // 0 … 12 dB
    public float balance;      // -1 (лево) … 1 (право)
    /** Добавка к балансу от фокуса машины (CarFocusView). В профиле не сохраняется. */
    public float carBalance;

    /** Обход эффекта: A/B-сравнение (ровно, но та же громкость) и тест динамиков (совсем без обработки). */
    public static final int BYPASS_NONE = 0, BYPASS_AB = 1, BYPASS_TEST = 2;
    private int bypass = BYPASS_NONE;

    /** Сценарии поверх звука (ночь: тише и мягче верх, спорт: панч). В профиль не пишутся. */
    private float scnGain, scnTreble, scnPunch;
    /**
     * Звук машины (как в магнитоле, вкладки «Bass Boost», «Фильтр баса», «Объёмный звук»):
     * подъём низов до частоты, срез низов ниже частоты, объёмный звук 0…100. Только пока звук идёт в машину.
     */
    private float carBassDb;
    private int carBassHz = 80, carHpHz, carSurround;
    private Virtualizer virt;
    /** false — прошивка не даёт включить объёмный звук для всего звука. */
    public volatile boolean surroundSupported = true;
    public String scnLabel = "";

    /**
     * Игровой звук (вкладка «Игры»): пока идёт игра, вместо кривой пользователя звучит профиль игры
     * («Шаги врагов», «Насыщенный»…). Кривая и пресеты не меняются — после игры всё как было.
     */
    private float[] gameSrc, gameSrcF;   // профиль в своих частотах
    private float[] gameBands;           // он же в наших полосах; null — игрового звука нет
    private float gamePunch;
    private boolean gameLevel;
    public String gameLabel = "";
    private boolean lowLatency;
    /** Эффекты, созданные с короткими кадрами (у них свои полосы обработки). */
    private final Set<Integer> fastSessions = new HashSet<>();

    /**
     * Обработка Android делит звук на полосы FFT шириной «частота / размер кадра» (при 20 мс — 47 Гц).
     * Раньше полосы эквалайзера ставились прямо по ползункам, и в 15/31 полосах часть ползунков баса
     * не попадала ни в одну полосу FFT (не работала). Теперь у обработки свои DP_BANDS полос:
     * внизу — по одной на полосу FFT, выше — по 1/6 октавы; их усиление — кривая ползунков,
     * усреднённая по ширине полосы. Работает каждый ползунок.
     */
    private static final int DP_BANDS = 48;
    /** Обычный кадр 20 мс: бас точнее (полосы FFT 47 Гц вместо 94), задержка всего +10 мс. */
    private static final float FRAME_MS = 20f, FAST_FRAME_MS = 5f;
    private final int sampleRate;
    private float[][] layoutNormal, layoutFast;

    /** AutoEQ: коррекция текущих наушников (null — нет) и она же в наших полосах. */
    private AutoEq.Correction corr;
    private float[] corrBands = new float[0];
    public boolean leveling;
    public boolean enabled;

    // ---- глобальные опции ----
    public boolean autoMode;   // включать только с Bluetooth-звуком
    public boolean perDevice;  // свои настройки для каждого устройства
    public String profileKey = "default";
    public String profileName = "";
    public String lastPreset = "";

    public volatile boolean globalOk;

    private final Runnable saveTask = new Runnable() {
        public void run() { saveNow(); }
    };

    private EqEngine(Context ctx) {
        prefs = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE);
        sampleRate = outputRate(ctx);
        enabled = prefs.getBoolean("on", true);
        autoMode = prefs.getBoolean("auto", false);
        perDevice = prefs.getBoolean("perDevice", false);
        bands = sanitizeBands(prefs.getInt("bands", 9));
        profileKey = prefs.getString("profile", "default");
        profileName = prefs.getString("profileName", "");
        gains = new float[bands];
        if (!loadProfile(profileKey)) migrateOld();
    }

    private static int outputRate(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int r = Integer.parseInt(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            if (r >= 8000 && r <= 192000) return r;
        } catch (Exception ignored) {
        }
        return 48000;
    }

    /**
     * Полосы обработки для кадра frameMs: {верхняя граница, низ и верх усреднения}.
     * Граница ставится на 0,4 полосы FFT выше её центра — так обработка относит к полосе ровно этот бин.
     */
    private float[][] layout(boolean fast) {
        float[][] l = fast ? layoutFast : layoutNormal;
        if (l != null) return l;
        l = layoutFor(sampleRate, fast ? FAST_FRAME_MS : FRAME_MS);
        if (fast) layoutFast = l;
        else layoutNormal = l;
        return l;
    }

    static float[][] layoutFor(int sampleRate, float frameMs) {
        int want = (int) (frameMs * sampleRate / 1000f);
        int n = Integer.highestOneBit(want);
        if (n < want) n <<= 1;
        float d = sampleRate / (float) n;
        int[] stops = new int[DP_BANDS];
        int c = 0;
        while (c < DP_BANDS / 2 && c * d < 300f) {
            stops[c] = c;
            c++;
        }
        int top = (int) (sampleRate / 2f / d) - 1;
        int rest = DP_BANDS - c;
        double lo = Math.log(stops[c - 1] + 1), hi = Math.log(top);
        for (int i = 1; i <= rest; i++) {
            int st = (int) Math.round(Math.exp(lo + (hi - lo) * i / rest));
            stops[c] = Math.max(st, stops[c - 1] + 1);
            c++;
        }
        float[][] l = new float[3][DP_BANDS];
        int prev = -1;
        for (int i = 0; i < DP_BANDS; i++) {
            int a = prev + 1, b = stops[i];
            l[0][i] = i == DP_BANDS - 1 ? sampleRate / 2f : (b + 0.4f) * d;
            l[1][i] = a > 0 ? Math.max(16f, (a - 0.5f) * d) : 16f;
            l[2][i] = Math.max(l[1][i] + 1f, (b + 0.5f) * d);
            prev = b;
        }
        return l;
    }

    private static int sanitizeBands(int n) {
        return n == 15 || n == 31 ? n : 9;
    }

    /** Перенос настроек из версий 1–3 (ключи g0..g8, pre). */
    private void migrateOld() {
        float[] g = new float[9];
        for (int i = 0; i < 9; i++) g[i] = prefs.getFloat("g" + i, 0f);
        preamp = prefs.getFloat("pre", 0f);
        gains = resample(g, F9, freqs(bands));
    }

    // =====================================================================
    // Частоты
    // =====================================================================

    public int bandCount() { return bands; }

    public static float[] freqs(int n) {
        return n == 15 ? F15 : n == 31 ? F31 : F9;
    }

    public float[] freqs() { return freqs(bands); }

    public static String label(float f) {
        if (f < 1000) {
            return f == Math.floor(f) ? String.valueOf((int) f) : String.format(Locale.US, "%.1f", f);
        }
        float k = f / 1000f;
        return k == Math.floor(k) ? (int) k + "k" : String.format(Locale.US, "%.1fk", k);
    }

    /** Пересчёт кривой на другие частоты (интерполяция по логарифму частоты). */
    public static float[] resample(float[] src, float[] srcF, float[] dstF) {
        if (src.length != srcF.length) {
            float[] fixed = new float[srcF.length];
            System.arraycopy(src, 0, fixed, 0, Math.min(src.length, fixed.length));
            src = fixed;
        }
        float[] out = new float[dstF.length];
        for (int i = 0; i < dstF.length; i++) {
            float f = dstF[i];
            float v;
            if (f <= srcF[0]) {
                v = src[0];
            } else if (f >= srcF[srcF.length - 1]) {
                v = src[src.length - 1];
            } else {
                int j = 0;
                while (srcF[j + 1] < f) j++;
                double t = (Math.log(f) - Math.log(srcF[j])) / (Math.log(srcF[j + 1]) - Math.log(srcF[j]));
                v = (float) (src[j] + (src[j + 1] - src[j]) * t);
            }
            out[i] = Math.round(v * 2f) / 2f;
        }
        return out;
    }

    // =====================================================================
    // Аудио-сессии
    // =====================================================================

    /** session 0 = весь звук телефона; >0 = конкретный плеер. */
    public synchronized boolean attach(int session) {
        if (effects.containsKey(session)) return true;
        try {
            DynamicsProcessing dp = create(session);
            effects.put(session, dp);
            apply(session, dp);
            if (session == 0) globalOk = true;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attach failed for session " + session, t);
            if (session == 0) globalOk = false;
            return false;
        }
    }

    public synchronized void detach(int session) {
        fastSessions.remove(session);
        DynamicsProcessing dp = effects.remove(session);
        if (dp != null) dp.release();
    }

    public synchronized void releaseAll() {
        for (DynamicsProcessing dp : effects.values()) dp.release();
        effects.clear();
        fastSessions.clear();
        globalOk = false;
        if (virt != null) {
            try {
                virt.release();
            } catch (Throwable ignored) {
            }
            virt = null;
        }
        carSurround = 0;   // EQ запустится снова — объёмный звук включит фокус машины
    }

    public synchronized int playerSessions() {
        int n = 0;
        for (int s : effects.keySet()) if (s != 0) n++;
        return n;
    }

    private DynamicsProcessing create(int session) {
        fastSessions.remove(session);
        if (lowLatency) {
            try {
                DynamicsProcessing dp = new DynamicsProcessing(1000, session, config(true));
                fastSessions.add(session);
                return dp;
            } catch (Throwable t) {
                Log.w(TAG, "low latency not supported, normal mode", t);
            }
        }
        return new DynamicsProcessing(1000, session, config(false));
    }

    /** fast — для игр: обработка во времени короткими кадрами (меньше задержка, чуть грубее низ). */
    private DynamicsProcessing.Config config(boolean fast) {
        DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                fast ? DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION
                        : DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,              // каналы
                true, DP_BANDS, // эквалайзер: свои полосы обработки (см. layout)
                true, 2,        // многополосный компрессор: бас + остальное
                false, 0,       // post-EQ
                true);          // лимитер
        b.setPreferredFrameDuration(fast ? FAST_FRAME_MS : FRAME_MS);
        return b.build();
    }

    /** Низкая задержка на время игры: эффекты пересоздаются с короткими кадрами. */
    public void setLowLatency(boolean on) {
        if (on == lowLatency) return;
        lowLatency = on;
        rebuildAll();
        applyAll();
    }

    /**
     * Выход звука сменился (провод, Bluetooth, USB): пересоздать обработку. На части телефонов
     * старый эффект перестаёт действовать на новом выходе — EQ «переподключается» сам.
     */
    public void reattachAll() {
        rebuildAll();
        applyAll();
        synchronized (this) {
            if (virt != null) {
                try {
                    virt.release();
                } catch (Throwable ignored) {
                }
                virt = null;
            }
        }
        updateSurround();
    }

    /** Пересоздать эффекты (после смены числа полос). */
    private synchronized void rebuildAll() {
        List<Integer> sessions = new ArrayList<>(effects.keySet());
        for (DynamicsProcessing dp : effects.values()) dp.release();
        effects.clear();
        globalOk = false;
        for (int s : sessions) attach(s);
    }

    /** Полоса ползунка: кривая пользователя (или игры) + коррекция AutoEQ + сценарий. */
    private float baseGain(int i) {
        float g = gameBands != null && i < gameBands.length ? gameBands[i] : gains[i];
        if (corr != null && corr.on && i < corrBands.length) g += corrBands[i];
        if (scnTreble != 0f) g += scnTreble * trebleWeight(freqs(bands)[i]);
        return g;
    }

    /** Слои поверх кривой на частоте f: звук машины и панч. */
    private float layers(float f) {
        return carLayer(f, carBassDb, carBassHz, carHpHz) + punchLayer(f, activePunch(), smallSpeaker);
    }

    /**
     * Звук идёт из динамика самого телефона. Он не играет бас ниже ~200–300 Гц, поэтому подъём 65 Гц
     * в нём не слышен, а срез гула — слышен: панч делал звук тише. Для динамика у панча своя форма.
     */
    private boolean smallSpeaker;

    public void setSmallSpeaker(boolean on) {
        if (on == smallSpeaker) return;
        smallSpeaker = on;
        applyAll();
        notifyChanged();
    }

    public boolean smallSpeaker() {
        return smallSpeaker;
    }

    /** Итоговое усиление на частоте ползунка i (что слышно). */
    private float processedGain(int i) {
        return Math.max(-24f, Math.min(24f, baseGain(i) + layers(freqs(bands)[i])));
    }

    /** Панч, который сейчас действует: игровой звук, сценарий «Спорт» или ползунок. */
    private float activePunch() {
        return Math.max(gameBands != null ? gamePunch : punch, scnPunch);
    }

    /**
     * Панч — форма удара. Это кривая, а не порог компрессора, поэтому панч слышно одинаково на любой
     * громкости (раньше на Bluetooth на полную он, наоборот, убирал бас до −11 дБ).
     * Наушники, колонки, машина: «бочка» около 65 Гц до +10 дБ и гул около 300 Гц до −2 дБ.
     * Динамик телефона (small): удар около 180 Гц до +10 дБ и щелчок 3 кГц до +3 дБ — то, что динамик
     * играет; ниже 100 Гц −10 дБ — он этого всё равно не играет, а запас громкости освобождается.
     */
    static float punchLayer(float f, float p, boolean small) {
        if (p <= 0.01f) return 0f;
        if (small) {
            double k = Math.log(f / 180.0) / Math.log(2) / 0.7;
            double c = Math.log(f / 3000.0) / Math.log(2) / 0.8;
            return (float) (p * (10 * Math.exp(-0.5 * k * k) + 3 * Math.exp(-0.5 * c * c) - 10 * lowShelf(f, 60)));
        }
        double k = Math.log(f / 65.0) / Math.log(2) / 0.8;
        double m = Math.log(f / 300.0) / Math.log(2) / 0.7;
        return (float) (p * (10 * Math.exp(-0.5 * k * k) - 2 * Math.exp(-0.5 * m * m)));
    }

    /** Кривая ползунков на любой частоте: по логарифму частоты между соседними ползунками. */
    private static float interpLog(float[] f, float[] v, float x) {
        if (x <= f[0]) return v[0];
        int n = f.length;
        if (x >= f[n - 1]) return v[n - 1];
        int j = 0;
        while (f[j + 1] < x) j++;
        double t = (Math.log(x) - Math.log(f[j])) / (Math.log(f[j + 1]) - Math.log(f[j]));
        return (float) (v[j] + (v[j + 1] - v[j]) * t);
    }

    /** Усиление полос обработки: кривая со слоями, усреднённая по ширине полосы (8 точек). */
    private float[] dpGains(float[][] l) {
        float[] out = new float[DP_BANDS];
        if (bypass != BYPASS_NONE) return out;
        float[] f = freqs(bands);
        float[] base = new float[bands];
        for (int i = 0; i < bands; i++) base[i] = baseGain(i);
        for (int j = 0; j < DP_BANDS; j++) {
            double a = Math.log(l[1][j]), b = Math.log(l[2][j]);
            float sum = 0f;
            for (int k = 0; k < 8; k++) {
                float x = (float) Math.exp(a + (b - a) * k / 7.0);
                sum += interpLog(f, base, x) + layers(x);
            }
            out[j] = Math.max(-24f, Math.min(24f, sum / 8f));
        }
        return out;
    }

    private void applyPreEq(int session, DynamicsProcessing dp) {
        float[][] l = layout(fastSessions.contains(session));
        float[] g = dpGains(l);
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, DP_BANDS);
        for (int j = 0; j < DP_BANDS; j++) eq.setBand(j, new DynamicsProcessing.EqBand(true, l[0][j], g[j]));
        dp.setPreEqAllChannelsTo(eq);
    }

    /** Добавка звука машины на частоте f, дБ: Bass Boost до bassHz и фильтр баса ниже hpHz (0 — выкл). */
    public static float carLayer(float f, float bassDb, int bassHz, int hpHz) {
        float g = 0f;
        if (bassDb > 0f) g += bassDb * lowShelf(f, bassHz);
        if (hpHz > 0) g -= highPassCut(f, hpHz);
        return g;
    }

    /** Кривая, которую сейчас слышно (без звука машины) — для мини-графика на экране машины. */
    public float[] curveWithoutCar() {
        float[] out = new float[bands];
        for (int i = 0; i < bands; i++) {
            out[i] = processedGain(i) - carLayer(freqs(bands)[i], carBassDb, carBassHz, carHpHz);
        }
        return out;
    }

    /** Запас под Bass Boost машины (половина подъёма, не больше 6 дБ), чтобы бас не упирался в лимитер. */
    private float carHeadroom() {
        return carBassDb > 0f ? -Math.min(6f, carBassDb * 0.5f) : 0f;
    }

    /** Bass Boost: полный подъём до частоты, за октаву выше плавно сходит на нет. */
    static float lowShelf(float f, int hz) {
        if (f <= hz) return 1f;
        if (f >= hz * 2f) return 0f;
        return (float) (1 - Math.log(f / hz) / Math.log(2));
    }

    /** Фильтр баса: ниже частоты — минус 12 дБ на октаву (не больше 24 дБ). */
    static float highPassCut(float f, int hz) {
        if (f >= hz) return 0f;
        return (float) Math.min(24, 12 * Math.log(hz / f) / Math.log(2));
    }

    /** Звук машины: Bass Boost (дБ и до какой частоты), фильтр баса (Гц, 0 — выкл), объёмный звук 0…100. */
    public void setCarDsp(float bassDb, int bassHz, int highPassHz, int surround) {
        bassDb = Math.max(0f, Math.min(12f, bassDb));
        surround = Math.max(0, Math.min(100, surround));
        boolean same = bassDb == carBassDb && bassHz == carBassHz && highPassHz == carHpHz;
        carBassDb = bassDb;
        carBassHz = bassHz > 0 ? bassHz : 80;
        carHpHz = Math.max(0, highPassHz);
        boolean surroundChanged = surround != carSurround;
        carSurround = surround;
        if (surroundChanged) updateSurround();
        if (same && !surroundChanged) return;
        if (!same) applyAll();
        notifyChanged();
    }

    /** Объёмный звук машины — системный эффект Virtualizer на весь звук (если прошивка его даёт). */
    private synchronized void updateSurround() {
        boolean want = carSurround > 0 && enabled;
        try {
            if (!want) {
                if (virt != null) {
                    virt.release();
                    virt = null;
                }
                return;
            }
            if (virt == null) virt = new Virtualizer(1000, 0);
            if (virt.getStrengthSupported()) virt.setStrength((short) (carSurround * 10));
            virt.setEnabled(true);
            surroundSupported = true;
        } catch (Throwable t) {
            Log.w(TAG, "surround not supported", t);
            surroundSupported = false;
            if (virt != null) {
                try {
                    virt.release();
                } catch (Throwable ignored) {
                }
                virt = null;
            }
        }
    }

    /** Доля «верха»: 0 ниже 2 кГц, 1 выше 8 кГц — для мягкого ночного звука. */
    private static float trebleWeight(float f) {
        if (f <= 2000) return 0f;
        if (f >= 8000) return 1f;
        return (float) ((Math.log(f) - Math.log(2000)) / (Math.log(8000) - Math.log(2000)));
    }

    /**
     * A/B: ровный звук должен быть так же громок, как обработанный, иначе «громче» кажется «лучше».
     * Берём среднее усиление полос в слышимой середине (60 Гц … 10 кГц).
     */
    private float abCompensation() {
        float[] f = freqs(bands);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < bands; i++) {
            if (f[i] < 60 || f[i] > 10000) continue;
            sum += processedGain(i);
            n++;
        }
        float mean = n > 0 ? (float) (sum / n) : 0f;
        return Math.max(-12f, Math.min(12f, mean + corrHeadroom() + scnGain));
    }

    /** Игровой профиль: свой запас вместо запаса кривой пользователя (подъёмы до +6 дБ). */
    private float levelBase() {
        if (gameBands == null) return preamp;
        float m = 0;
        for (float v : gameBands) m = Math.max(m, v);
        return -Math.min(6f, m * 0.6f);
    }

    /** Запас под подъёмы коррекции, чтобы не упираться в лимитер (не больше 6 дБ). */
    private float corrHeadroom() {
        if (corr == null || !corr.on) return 0f;
        float m = 0;
        for (float v : corrBands) m = Math.max(m, v);
        return -Math.min(6f, m);
    }

    private void apply(int session, DynamicsProcessing dp) {
        applyPreEq(session, dp);

        boolean game = gameBands != null;
        float p = bypass != BYPASS_NONE ? 0f : activePunch();
        boolean lvl = bypass == BYPASS_NONE && (game ? gameLevel : leveling);
        boolean mbcOn = p > 0.01f || lvl;
        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, mbcOn, 2);
        mbc.setBand(0, bassBand(p, lvl));
        mbc.setBand(1, lvl ? levelBand(22000f) : neutralBand(22000f));
        dp.setMbcAllChannelsTo(mbc);

        // attack 1 мс, release 60 мс, 10:1, порог -1 dB — защита от хрипа и перегруза
        dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f));

        float base = levelBase() + boost;
        // панч: −1,5 дБ запаса на 100% (в динамике телефона −1: глубокий бас там срезан), остальное держит бас-компрессор
        if (bypass == BYPASS_NONE) base += corrHeadroom() + carHeadroom() + scnGain - (smallSpeaker ? 1f : 1.5f) * p;
        else if (bypass == BYPASS_AB) base += abCompensation();
        // тест динамиков — без баланса и фокуса, иначе он сам себя исказит
        float bal = bypass == BYPASS_TEST ? 0f : Math.max(-1f, Math.min(1f, balance + carBalance));
        dp.setInputGainbyChannel(0, base + (bal > 0 ? atten(bal) : 0f));
        dp.setInputGainbyChannel(1, base + (bal < 0 ? atten(-bal) : 0f));
        dp.setEnabled(enabled);
    }

    private static float atten(float b) {
        return (float) Math.max(-40.0, 20.0 * Math.log10(Math.max(1e-3, 1.0 - b)));
    }

    /** Низкие частоты (до 150 Гц): «панч» или выравнивание. */
    private DynamicsProcessing.MbcBand bassBand(float punch, boolean leveling) {
        if (punch > 0.01f) {
            // бас до 120 Гц: на громком звуке держит поднятую «бочку» у −6 дБ (до 3:1), чтобы не было хрипа;
            // на тихом не включается. Без подъёма уровня: раньше он и сделал панч зависящим от громкости
            // в динамике телефона удар выше (180 Гц) — компрессор держит до 250 Гц, порог −10 дБ
            return smallSpeaker
                    ? new DynamicsProcessing.MbcBand(true, 250f, 3f, 120f, 1f + 2f * punch, -10f, 6f, -90f, 1f, 0f, 0f)
                    : new DynamicsProcessing.MbcBand(true, 120f, 3f, 120f, 1f + 2f * punch, -6f, 6f, -90f, 1f, 0f, 0f);
        }
        if (leveling) return levelBand(150f);
        return neutralBand(150f);
    }

    private static DynamicsProcessing.MbcBand levelBand(float cutoff) {
        // медленный компрессор 3:1 + подъём тихого — тихие и громкие треки сближаются
        return new DynamicsProcessing.MbcBand(true, cutoff, 50f, 800f, 3f, -30f, 10f, -90f, 1f, 0f, 9f);
    }

    private static DynamicsProcessing.MbcBand neutralBand(float cutoff) {
        return new DynamicsProcessing.MbcBand(true, cutoff, 3f, 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f);
    }

    private synchronized void applyAll() {
        for (Map.Entry<Integer, DynamicsProcessing> e : effects.entrySet()) {
            try {
                apply(e.getKey(), e.getValue());
            } catch (Throwable t) {
                Log.w(TAG, "apply failed", t);
            }
        }
    }

    // =====================================================================
    // Изменение настроек
    // =====================================================================

    public void setGain(int band, float db) {
        if (band < 0 || band >= bands) return;
        gains[band] = clamp(db);
        synchronized (this) {
            // ползунок меняет кривую вокруг себя — пересчитываем все полосы обработки
            for (Map.Entry<Integer, DynamicsProcessing> e : effects.entrySet()) {
                try {
                    applyPreEq(e.getKey(), e.getValue());
                } catch (Throwable t) {
                    Log.w(TAG, "band failed", t);
                }
            }
        }
        lastPreset = "";
        save();
    }

    /** Установить кривую (в любом числе полос — пересчитается). */
    public void setCurve(float[] values, float[] valuesFreqs) {
        float[] g = resample(values, valuesFreqs, freqs(bands));
        for (int i = 0; i < bands; i++) gains[i] = clamp(g[i]);
        applyAll();
        save();
    }

    public void applyBuiltIn(int index, String name) {
        preamp = PRESET_PRE[index];
        punch = PRESET_PUNCH[index];
        setCurve(PRESETS[index], F9);
        lastPreset = name;
        save();
        notifyChanged();
    }

    public void setBandCount(int n) {
        n = sanitizeBands(n);
        if (n == bands) return;
        gains = resample(gains, freqs(bands), freqs(n));
        bands = n;
        corrBands = AutoEq.toBands(corr, freqs(bands));
        if (gameBands != null) gameBands = resample(gameSrc, gameSrcF, freqs(bands));
        rebuildAll();
        save();
        notifyChanged();
    }

    public void setPreamp(float db) {
        preamp = Math.max(-12f, Math.min(0f, db));
        applyAll();
        save();
    }

    public void setPunch(float v) {
        punch = Math.max(0f, Math.min(1f, v));
        applyAll();
        save();
    }

    public void setBoost(float db) {
        boost = Math.max(0f, Math.min(MAX_BOOST, db));
        applyAll();
        save();
    }

    public void setBalance(float b) {
        balance = Math.max(-1f, Math.min(1f, b));
        applyAll();
        save();
    }

    // =====================================================================
    // AutoEQ
    // =====================================================================

    /** Коррекция текущих наушников (null — убрать). В профиль не пишется. */
    public void setCorrection(AutoEq.Correction c) {
        corr = c;
        corrBands = AutoEq.toBands(c, freqs(bands));
        applyAll();
        notifyChanged();
    }

    public AutoEq.Correction correction() {
        return corr;
    }

    /** Коррекция в текущих полосах (для графика); пустой массив — нет коррекции. */
    public float[] correctionBands() {
        return corr != null && corr.on ? corrBands.clone() : new float[0];
    }

    // =====================================================================
    // Обход (A/B, тест динамиков) и сценарии
    // =====================================================================

    public void setBypass(int mode) {
        if (mode == bypass) return;
        bypass = mode;
        applyAll();
    }

    public int bypass() {
        return bypass;
    }

    /** Сценарий поверх звука: gain — общий уровень, treble — «верх», punchMin — минимум панча. */
    public void setScenario(float gain, float treble, float punchMin, String label) {
        String l = label == null ? "" : label;
        if (gain == scnGain && treble == scnTreble && punchMin == scnPunch && l.equals(scnLabel)) return;
        scnGain = gain;
        scnTreble = treble;
        scnPunch = punchMin;
        scnLabel = l;
        applyAll();
        notifyChanged();
    }

    /** Включить игровой звук: кривая values (дБ) на частотах valuesFreqs, панч 0…1, выравнивание. */
    public void setGameSound(float[] values, float[] valuesFreqs, float punchLevel, boolean level, String label) {
        gameSrc = values.clone();
        gameSrcF = valuesFreqs.clone();
        gameBands = resample(gameSrc, gameSrcF, freqs(bands));
        gamePunch = Math.max(0f, Math.min(1f, punchLevel));
        gameLevel = level;
        gameLabel = label == null ? "" : label;
        applyAll();
        notifyChanged();
    }

    /** Игра закончилась — звук пользователя возвращается. */
    public void clearGameSound() {
        if (gameBands == null) return;
        gameBands = null;
        gameSrc = gameSrcF = null;
        gameLabel = "";
        applyAll();
        notifyChanged();
    }

    public boolean gameSoundOn() {
        return gameBands != null;
    }

    /** Фокус машины: применяется сразу, в профиль не пишется. */
    public void setCarBalance(float b) {
        b = Math.max(-0.9f, Math.min(0.9f, b));
        if (Math.abs(b - carBalance) < 0.001f) return;
        carBalance = b;
        applyAll();
        notifyChanged();
    }

    public void setLeveling(boolean on) {
        leveling = on;
        applyAll();
        save();
    }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        applyAll();
        updateSurround();
        save();
        notifyChanged();
    }

    public void setAutoMode(boolean on) {
        autoMode = on;
        save();
    }

    public void setPerDevice(boolean on) {
        perDevice = on;
        if (!on) switchProfile("default", "");
        save();
    }

    private static float clamp(float v) {
        return Math.max(MIN_DB, Math.min(MAX_DB, v));
    }

    // =====================================================================
    // Профили устройств
    // =====================================================================

    /** Переключиться на настройки устройства (или телефона). */
    public void switchProfile(String key, String name) {
        if (!perDevice) key = "default";
        profileName = name == null ? "" : name;
        if (key.equals(profileKey)) {
            save();
            return;
        }
        saveNow();
        profileKey = key;
        loadProfile(key); // если профиля ещё нет — текущие настройки станут его началом
        applyAll();
        saveNow();
        notifyChanged();
    }

    private boolean loadProfile(String key) {
        String json = prefs.getString("profile:" + key, null);
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            readSound(o, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void readSound(JSONObject o, boolean withPersonal) throws Exception {
        int b = sanitizeBands(o.optInt("b", 9));
        JSONArray g = o.getJSONArray("g");
        float[] src = new float[g.length()];
        for (int i = 0; i < src.length; i++) src[i] = (float) g.getDouble(i);
        gains = resample(src, freqs(b), freqs(bands));
        preamp = (float) o.optDouble("pre", 0);
        punch = (float) o.optDouble("punch", 0);
        leveling = o.optBoolean("lvl", false);
        if (withPersonal) {
            boost = (float) o.optDouble("boost", 0);
            balance = (float) o.optDouble("bal", 0);
            lastPreset = o.optString("preset", "");
        }
    }

    private JSONObject writeSound(boolean withPersonal) throws Exception {
        JSONObject o = new JSONObject();
        o.put("b", bands);
        JSONArray g = new JSONArray();
        for (float v : gains) g.put((double) v);
        o.put("g", g);
        o.put("pre", (double) preamp);
        o.put("punch", (double) punch);
        o.put("lvl", leveling);
        if (withPersonal) {
            o.put("boost", (double) boost);
            o.put("bal", (double) balance);
            o.put("preset", lastPreset);
        }
        return o;
    }

    private void save() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 300);
    }

    private synchronized void saveNow() {
        main.removeCallbacks(saveTask);
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("on", enabled).putBoolean("auto", autoMode).putBoolean("perDevice", perDevice)
                .putInt("bands", bands).putString("profile", profileKey).putString("profileName", profileName);
        try {
            e.putString("profile:" + profileKey, writeSound(true).toString());
        } catch (Exception ex) {
            Log.w(TAG, "save failed", ex);
        }
        e.apply();
    }

    // =====================================================================
    // Свои пресеты + экспорт / импорт
    // =====================================================================

    private JSONObject presets() {
        try {
            return new JSONObject(prefs.getString("presets", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public List<String> presetNames() {
        List<String> out = new ArrayList<>();
        Iterator<String> it = presets().keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    public void savePreset(String name) {
        try {
            JSONObject all = presets();
            all.put(name, writeSound(false));
            prefs.edit().putString("presets", all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save preset failed", e);
        }
    }

    public void loadPreset(String name) {
        try {
            Object v = presets().get(name);
            if (v instanceof JSONArray) { // формат версий 1–3: 9 полос + preamp
                JSONArray arr = (JSONArray) v;
                float[] g = new float[9];
                for (int i = 0; i < 9; i++) g[i] = (float) arr.getDouble(i);
                if (arr.length() > 9) preamp = (float) arr.getDouble(9);
                setCurve(g, F9);
            } else {
                readSound((JSONObject) v, false);
                applyAll();
            }
            lastPreset = name;
            save();
            notifyChanged();
        } catch (Exception e) {
            Log.w(TAG, "load preset failed", e);
        }
    }

    /** Снимок звука (кривая, запас, панч, выравнивание) — для автопресета по приложению. */
    public String soundJson() {
        try {
            return writeSound(false).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Вернуть звук из снимка. */
    public void loadSoundJson(String json, String preset) {
        try {
            readSound(new JSONObject(json), false);
            lastPreset = preset == null ? "" : preset;
            applyAll();
            save();
            notifyChanged();
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
        }
    }

    public void deletePreset(String name) {
        JSONObject all = presets();
        all.remove(name);
        prefs.edit().putString("presets", all.toString()).apply();
    }

    /** Текст для отправки другу: JSON с кривой. */
    public String exportText(String name) {
        try {
            JSONObject o;
            if (name == null) {
                o = writeSound(false);
                name = "EQ";
            } else {
                Object v = presets().get(name);
                if (v instanceof JSONObject) {
                    o = (JSONObject) v;
                } else { // старый формат: массив 9 полос + preamp
                    JSONArray arr = (JSONArray) v;
                    JSONArray g = new JSONArray();
                    for (int i = 0; i < 9; i++) g.put(arr.getDouble(i));
                    o = new JSONObject();
                    o.put("b", 9);
                    o.put("g", g);
                    o.put("pre", arr.length() > 9 ? arr.getDouble(9) : 0);
                }
            }
            JSONObject out = new JSONObject();
            out.put("app", "EQ");
            out.put("v", 1);
            out.put("name", name);
            out.put("b", o.optInt("b", 9));
            out.put("g", o.getJSONArray("g"));
            out.put("pre", o.optDouble("pre", 0));
            out.put("punch", o.optDouble("punch", 0));
            out.put("lvl", o.optBoolean("lvl", false));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Импорт из текста (можно вставить сообщение целиком). Возвращает имя или null. */
    public String importText(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JSONObject o = new JSONObject(text.substring(a, b + 1));
            JSONArray g = o.getJSONArray("g");
            int n = g.length();
            if (n != 9 && n != 15 && n != 31) return null;
            JSONObject preset = new JSONObject();
            preset.put("b", n);
            JSONArray clean = new JSONArray();
            for (int i = 0; i < n; i++) clean.put(Math.max(MIN_DB, Math.min(MAX_DB, g.getDouble(i))));
            preset.put("g", clean);
            preset.put("pre", Math.max(-12, Math.min(0, o.optDouble("pre", 0))));
            preset.put("punch", Math.max(0, Math.min(1, o.optDouble("punch", 0))));
            preset.put("lvl", o.optBoolean("lvl", false));
            String name = o.optString("name", "").trim();
            if (name.isEmpty()) name = "Import";
            if (name.length() > 40) name = name.substring(0, 40);
            JSONObject all = presets();
            String unique = name;
            for (int k = 2; all.has(unique); k++) unique = name + " (" + k + ")";
            all.put(unique, preset);
            prefs.edit().putString("presets", all.toString()).apply();
            return unique;
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Слушатели (интерфейс обновляется, если настройки сменила служба/плитка)
    // =====================================================================

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    void notifyChanged() {
        main.post(new Runnable() {
            public void run() {
                for (Runnable r : listeners) r.run();
            }
        });
    }
}
