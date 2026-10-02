package lv.budseq;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Живой бас для волны-всплесков: Visualizer на весь звук телефона (сессия 0), один на всё приложение —
 * карточка «Сейчас играет», виджет, шторка и спектр на вкладке «Эквалайзер». Из формы волны считаем свой
 * спектр (float FFT: точнее, чем 8-битный спектр Visualizer) и отдаём уровни баса 30–110 Гц от центра
 * волны к краю, 0…1. Голос и середина волну не двигают. Автоусиление: бас в полную высоту и на тихой громкости.
 * Нужен доступ к микрофону — так Android называет доступ к звуку для визуализаторов; сам микрофон не используется.
 *
 * Почему один и со «сторожем»: в Android визуализатор на весь звук — общий для всех приложений, звук получает
 * только последний подключившийся, и любой может его выключить (раньше так делал и наш спектр: открыли и закрыли
 * вкладку — волна навсегда получала тишину). Ещё при смене выхода (Bluetooth, провод, машина) он может остаться
 * на старом выходе и отдавать тишину. Сторож раз в секунду проверяет и включает или переподключает его.
 */
public final class AudioPulse {
    /** Полос баса от центра к краю (вторая половина волны — зеркальная). */
    public static final int BARS = 40;
    private static final int N = 1024;
    /** Только бас: от центра волны (30 Гц) к краям (110 Гц) — бочка и бас-гитара, ниже мужского голоса. */
    private static final float F_LO = 30f, F_HI = 110f;
    /** Диапазон автоусиления, дБ: всё, что тише пика больше чем на RANGE, — ноль. */
    private static final float RANGE = 30f;

    /** Состояние для подсказки «что с волной». */
    public static final int ST_NO_ACCESS = 0, ST_UNAVAILABLE = 1, ST_SILENT = 2, ST_OK = 3;

    /** Спектр всего звука (вкладка «Эквалайзер»): тот же кадр FFT, что и у волны. */
    public interface SpectrumListener {
        /** re/im — FFT кадра (n точек), binHz — ширина бина. Вызывается на главном потоке, массивы не хранить. */
        void onSpectrum(float[] re, float[] im, int n, float binHz);
    }

    private static AudioPulse instance;

    public static synchronized AudioPulse get() {
        if (instance == null) instance = new AudioPulse();
        return instance;
    }

    private Visualizer vis;
    private final List<Object> users = new ArrayList<>();
    private final CopyOnWriteArrayList<SpectrumListener> spectrum = new CopyOnWriteArrayList<>();
    private final float[] levels = new float[BARS];
    private final float[] slow = new float[BARS];
    private volatile float loud;
    private volatile long lastData;
    private float agc = -100f;
    private long agcTime;

    // сторож
    private Context app;
    private Handler main;
    private boolean watching;
    /** elapsedRealtime: последний кадр от визуализатора, последний не тихий кадр, последнее переподключение. */
    private long lastCallback, lastSound, lastRecreate;
    /** С какого момента играет музыка (0 — не играет): тишину считаем поломкой, только если играет уже 2,5 с. */
    private long musicSince;
    /** Переподключений подряд без звука: каждое следующее — реже (3, 6, 12, 24, 30 с). */
    private int failStreak;
    private int reconnects;

    private final float[] re = new float[N], im = new float[N], win = new float[N];
    private final float[] edges = new float[BARS + 1];

    AudioPulse() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        for (int b = 0; b <= BARS; b++) edges[b] = (float) (F_LO * Math.pow(F_HI / F_LO, b / (double) BARS));
    }

    public static boolean allowed(Context c) {
        return c.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Включить для who (View, служба, спектр — кто угодно). false — визуализатора пока нет (нет доступа
     * или Android не дал); сторож будет пробовать снова, пока есть пользователи и доступ.
     * Визуализатор живёт, пока есть хоть один пользователь.
     */
    public synchronized boolean acquire(Context c, Object who) {
        if (!users.contains(who)) users.add(who);
        if (app == null) app = c.getApplicationContext();
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (!watching) {
            watching = true;
            main.postDelayed(watchdog, 1000);
        }
        if (vis != null) return true;
        if (!allowed(c)) return false;
        return startVis();
    }

    public synchronized void release(Object who) {
        users.remove(who);
        if (users.isEmpty()) {
            stopVis();
            watching = false;
            if (main != null) main.removeCallbacks(watchdog);
        }
    }

    public void addSpectrum(SpectrumListener l) {
        if (!spectrum.contains(l)) spectrum.add(l);
    }

    public void removeSpectrum(SpectrumListener l) {
        spectrum.remove(l);
    }

    /** Проверить сейчас (вернулись в EQ, выдали доступ) — не ждать секунду. */
    public void wake() {
        if (main == null || !watching) return;
        main.removeCallbacks(watchdog);
        main.post(watchdog);
    }

    private boolean startVis() {
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.max(range[0], Math.min(N, range[1])));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rateMilliHz) {
                    long t = SystemClock.elapsedRealtime();
                    lastCallback = t;
                    if (!flat(wave)) {
                        lastSound = t;
                        failStreak = 0;
                    }
                    onWave(wave, rateMilliHz / 1000, System.currentTimeMillis());
                }

                public void onFftDataCapture(Visualizer v, byte[] fft, int rate) { }
            }, Visualizer.getMaxCaptureRate(), true, false);
            int r = vis.setEnabled(true);
            long t = SystemClock.elapsedRealtime();
            lastCallback = t;
            lastSound = t;
            if (r != Visualizer.SUCCESS) Log.w("EQ", "pulse: enable " + r);
            return true;
        } catch (Throwable t) {
            Log.w("EQ", "pulse: visualizer unavailable", t);
            stopVis();
            return false;
        }
    }

    /** Отключиться. Не выключаем общий визуализатор — у других приложений он продолжит работать. */
    private void stopVis() {
        if (vis == null) return;
        try {
            vis.release();
        } catch (Throwable ignored) {
        }
        vis = null;
    }

    private void reconnect(String why) {
        Log.i("EQ", "pulse: reconnect (" + why + ")");
        stopVis();
        lastRecreate = SystemClock.elapsedRealtime();
        reconnects++;
        failStreak++;
        if (app != null && allowed(app)) startVis();
    }

    /** Можно ли переподключаться сейчас: не чаще раза в 3 с, при неудачах подряд — всё реже (до 30 с). */
    private boolean mayReconnect(long now) {
        long gap = Math.min(30000L, 3000L << Math.min(4, failStreak));
        return now - lastRecreate >= gap;
    }

    /** Кадр из одних 0x80: так визуализатор отвечает, когда выключен или висит на выходе без звука. */
    private static boolean flat(byte[] wave) {
        for (byte b : wave) if (b != (byte) 0x80) return false;
        return true;
    }

    private boolean musicActive() {
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Сторож: раз в секунду, пока есть пользователи. */
    private final Runnable watchdog = new Runnable() {
        public void run() {
            synchronized (AudioPulse.this) {
                if (!watching || users.isEmpty()) return;
                long now = SystemClock.elapsedRealtime();
                boolean music = musicActive();
                if (!music) musicSince = 0;
                else if (musicSince == 0) musicSince = now;
                if (vis == null) {
                    // не создался (фон, занят) — пробуем снова
                    if (app != null && allowed(app) && mayReconnect(now)) reconnect("missing");
                } else {
                    boolean on;
                    try {
                        on = vis.getEnabled();
                    } catch (Throwable t) {
                        on = false;
                    }
                    if (!on) {
                        // общий визуализатор выключил кто-то другой — включаем; не вышло — переподключаемся
                        int r;
                        try {
                            r = vis.setEnabled(true);
                        } catch (Throwable t) {
                            r = Visualizer.ERROR;
                        }
                        if (r != Visualizer.SUCCESS && mayReconnect(now)) reconnect("disabled " + r);
                    } else if (now - lastCallback > 2500 && mayReconnect(now)) {
                        // кадры не идут: звук забрало другое приложение или визуализатор умер
                        reconnect("no data");
                    } else if (music && now - musicSince > 2500 && now - lastSound > 3000 && mayReconnect(now)) {
                        // музыка играет, а приходит тишина: визуализатор остался на старом выходе
                        // (подключили Bluetooth, провод, машину) или его выключили
                        reconnect("silent while playing");
                    }
                }
                main.postDelayed(this, 1000);
            }
        }
    };

    /** Что с волной сейчас (для подсказки по долгому нажатию). */
    public synchronized int status(Context c) {
        if (!allowed(c)) return ST_NO_ACCESS;
        if (vis == null) return ST_UNAVAILABLE;
        if (SystemClock.elapsedRealtime() - lastSound > 1500 && musicActive()) return ST_SILENT;
        return ST_OK;
    }

    /** Сколько раз сторож переподключал визуализатор. */
    public int reconnects() {
        return reconnects;
    }

    /** Данные идут (визуализатор работает и присылал звук за последние 0,6 с). */
    public boolean live() {
        return vis != null && System.currentTimeMillis() - lastData < 600;
    }

    /** Скопировать уровни (длина BARS: от центра к краю). */
    public void levels(float[] out) {
        synchronized (levels) {
            System.arraycopy(levels, 0, out, 0, Math.min(out.length, BARS));
        }
    }

    /** Сила баса 0…1 — яркость центральной линии. */
    public float loudness() {
        return loud;
    }

    /**
     * Один кадр формы волны (8 бит без знака, 128 — тишина). Отдельно от Visualizer — для проверки вне Android.
     * Волна реагирует только на бас (30–110 Гц: бочка и бас-линия); голос и середина её не двигают.
     */
    void onWave(byte[] wave, int rateHz, long now) {
        int n = Math.min(N, wave.length);
        if (n < 64 || rateHz <= 0) return;
        double sq = 0;
        for (int i = 0; i < N; i++) {
            float v = i < n ? ((wave[i] & 0xFF) - 128) / 128f : 0f;
            sq += v * v;
            re[i] = v * win[i];
            im[i] = 0f;
        }
        float rms = (float) Math.sqrt(sq / n);
        fft(re, im);
        float binHz = rateHz / (float) N;
        for (SpectrumListener l : spectrum) l.onSpectrum(re, im, N, binHz);
        double voice = 1e-12, bass = 1e-12;
        for (int k = 1; k < N / 2; k++) {
            double pk = re[k] * re[k] + im[k] * im[k];
            float f = k * binHz;
            if (f <= F_HI + binHz / 2) bass += pk;
            else if (f >= 150f && f <= 2000f) voice += pk;
        }
        // полосы баса от центра (30 Гц) к краю (110 Гц); FFT в басу грубый — берём мощность между бинами
        float[] db = new float[BARS];
        float max = -200f;
        for (int b = 0; b < BARS; b++) {
            float c = edges[b] / binHz;
            int k = Math.max(1, Math.min(N / 2 - 2, (int) c));
            float t = Math.max(0f, Math.min(1f, c - k));
            double p0 = re[k] * re[k] + im[k] * im[k], p1 = re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1];
            db[b] = (float) (10 * Math.log10(p0 + (p1 - p0) * t + 1e-12));
            max = Math.max(max, db[b]);
        }
        // бас против полосы голоса (150–2000 Гц): поёт голос без баса — волна лежит
        // (бас на 12 дБ тише голоса — ноль, на 2 дБ тише и громче — полная высота)
        float share = (float) (10 * Math.log10(bass / voice));
        float gate = Math.max(0f, Math.min(1f, (share + 12f) / 10f));
        // автоусиление по басу: пик держится, потом опускается (8 дБ/с) — тихий бас тоже на всю высоту
        float dt = agcTime == 0 ? 0.05f : Math.min(0.5f, (now - agcTime) / 1000f);
        agcTime = now;
        agc = Math.max(max, agc - 8f * dt);
        // цифровая тишина (меньше половины шага 8-битного звука) — волна ложится
        boolean silent = rms < 0.4f / 128f;
        float sum = 0f;
        synchronized (levels) {
            for (int b = 0; b < BARS; b++) {
                float v = silent ? 0f : Math.max(0f, Math.min(1f, (db[b] - (agc - RANGE)) / RANGE));
                v = (float) Math.pow(v, 1.35) * gate;
                // удар бочки — выше обычного: сравниваем с медленным средним полосы
                slow[b] += (v - slow[b]) * 0.15f;
                float hit = Math.max(0f, v - slow[b]);
                levels[b] = Math.min(1f, v * 0.85f + hit * 1.1f);
                sum += levels[b];
            }
        }
        loud = sum / BARS;
        lastData = now;
    }

    /** Быстрое преобразование Фурье на месте (N — степень двойки). */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }
}
