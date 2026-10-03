package lv.budseq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Автонастройка салона по микрофону: телефон на месте водителя, на уровне головы.
 * 2 с слушаем шум салона, 8 с играем розовый шум через динамики машины и пишем микрофоном
 * (EQ на это время обходится). Разбираем на 28 полос по трети октавы (31,5 Гц…16 кГц), сравниваем
 * с целевой кривой машины и строим поправку: провалы поднимаем (до +6 дБ), горбы срезаем (до −10 дБ),
 * общая громкость не меняется. Ниже 80 Гц микрофон телефона врёт — подъём там не больше +3 дБ.
 */
final class CabinTune {

    interface Ui {
        /** step 0 — слушаю тишину, 1 — играю шум и слушаю, 2 — считаю; progress 0…1. */
        void onStep(int step, float progress);

        void onDone(Result r);

        /** Не вышло: строка-причина (R.string…). */
        void onError(int msgRes);
    }

    static final class Result {
        float[] freqs;
        /** Как звучит салон (от средней громкости 300–3000 Гц), дБ. */
        float[] response;
        /** Поправка эквалайзера, дБ. */
        float[] correction;
        /** Насколько шум из динамиков громче шума салона, дБ. */
        float snr;
    }

    /** Полосы по трети октавы. */
    static final float[] THIRD = {31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000};
    private static final int RATE = 48000;
    private static final int N = 8192;
    private static final float NOISE_SEC = 2f, PLAY_SEC = 8f, SKIP_SEC = 1.5f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    boolean isRunning() {
        return running;
    }

    void stop() {
        running = false;
    }

    void start(final Context ctx, final Ui ui) {
        if (running) return;
        running = true;
        final EqEngine eq = EqEngine.get(ctx);
        eq.setBypass(EqEngine.BYPASS_TEST);
        new Thread(new Runnable() {
            public void run() {
                AudioRecord rec = null;
                AudioTrack play = null;
                Result res = null;
                int err = 0;
                try {
                    int src = MediaRecorder.AudioSource.VOICE_RECOGNITION;   // без шумодава и автоусиления
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))) {
                        src = MediaRecorder.AudioSource.UNPROCESSED;
                    }
                    int minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minRec, RATE));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("rec");
                    int minPlay = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    play = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(Math.max(minPlay, RATE / 5 * 4))
                            .build();
                    rec.startRecording();
                    // 1) тишина: шум салона
                    float[] noise = record(rec, (int) (RATE * NOISE_SEC), 0, ui);
                    if (!running) return;
                    // 2) розовый шум из динамиков, пишем то же время + запас на задержку Bluetooth
                    final AudioTrack p = play;
                    final int total = (int) (RATE * PLAY_SEC);
                    Thread player = new Thread(new Runnable() {
                        public void run() { playPink(p, total + RATE); }
                    }, "eq-cabin-pink");
                    play.play();
                    player.start();
                    float[] meas = record(rec, total, 1, ui);
                    player.join(3000);
                    if (!running) return;
                    post(ui, 2, 0f);
                    // пропускаем начало (задержка Bluetooth, разгон)
                    int skip = (int) (RATE * SKIP_SEC);
                    float[] body = new float[meas.length - skip];
                    System.arraycopy(meas, skip, body, 0, body.length);
                    res = analyse(noise, body, RATE);
                    if (res == null) err = R.string.cabin_err_quiet;
                } catch (Exception e) {
                    err = R.string.cabin_err_mic;
                } finally {
                    if (rec != null) {
                        try {
                            rec.stop();
                        } catch (Exception ignored) {
                        }
                        rec.release();
                    }
                    if (play != null) {
                        try {
                            play.stop();
                        } catch (Exception ignored) {
                        }
                        play.release();
                    }
                    final Result out = res;
                    final int error = err;
                    final boolean wasRunning = running;
                    main.post(new Runnable() {
                        public void run() {
                            eq.setBypass(EqEngine.BYPASS_NONE);
                            running = false;
                            if (!wasRunning) return;
                            if (out != null) ui.onDone(out);
                            else ui.onError(error != 0 ? error : R.string.cabin_err_mic);
                        }
                    });
                }
            }
        }, "eq-cabin-tune").start();
    }

    private void post(final Ui ui, final int step, final float p) {
        main.post(new Runnable() {
            public void run() { ui.onStep(step, p); }
        });
    }

    private float[] record(AudioRecord rec, int samples, int step, Ui ui) {
        float[] out = new float[samples];
        short[] buf = new short[RATE / 10];
        int got = 0, lastShown = -1;
        while (got < samples && running) {
            int n = rec.read(buf, 0, Math.min(buf.length, samples - got));
            if (n <= 0) break;
            for (int i = 0; i < n; i++) out[got + i] = buf[i] / 32768f;
            got += n;
            int pct = got * 20 / samples;
            if (pct != lastShown) {
                lastShown = pct;
                post(ui, step, got / (float) samples);
            }
        }
        return out;
    }

    /** Розовый шум (фильтр Пола Келлета), −14 дБ от полной: громко, но без перегруза. */
    private void playPink(AudioTrack t, int samples) {
        Random rnd = new Random(7);
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        short[] buf = new short[RATE / 10 * 2];
        int left = samples;
        double gain = 0.2 * 0.11 * Short.MAX_VALUE;
        while (left > 0 && running) {
            int n = Math.min(buf.length / 2, left);
            for (int i = 0; i < n; i++) {
                double w = rnd.nextGaussian();
                b0 = 0.99886 * b0 + w * 0.0555179;
                b1 = 0.99332 * b1 + w * 0.0750759;
                b2 = 0.96900 * b2 + w * 0.1538520;
                b3 = 0.86650 * b3 + w * 0.3104856;
                b4 = 0.55000 * b4 + w * 0.5329522;
                b5 = -0.7616 * b5 - w * 0.0168980;
                double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
                b6 = w * 0.115926;
                short v = (short) Math.max(-32767, Math.min(32767, pink * gain));
                buf[i * 2] = v;
                buf[i * 2 + 1] = v;
            }
            t.write(buf, 0, n * 2);
            left -= n;
        }
    }

    /** Мощность по полосам трети октавы, дБ (усреднение кадров FFT 8192 с перекрытием 50%). */
    static float[] bands(float[] x, int rate) {
        float[] win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        double[] pow = new double[N / 2];
        float[] re = new float[N], im = new float[N];
        int frames = 0;
        for (int s = 0; s + N <= x.length; s += N / 2) {
            for (int i = 0; i < N; i++) {
                re[i] = x[s + i] * win[i];
                im[i] = 0f;
            }
            AudioPulse.fft(re, im);
            for (int k = 1; k < N / 2; k++) pow[k] += re[k] * re[k] + im[k] * im[k];
            frames++;
        }
        float[] out = new float[THIRD.length];
        float binHz = rate / (float) N;
        double edge = Math.pow(2, 1.0 / 6);
        for (int b = 0; b < THIRD.length; b++) {
            double lo = THIRD[b] / edge, hi = THIRD[b] * edge, p = 0;
            int k0 = Math.max(1, (int) Math.ceil(lo / binHz)), k1 = Math.min(N / 2 - 1, (int) Math.floor(hi / binHz));
            for (int k = k0; k <= k1; k++) p += pow[k];
            if (k1 < k0) p = pow[Math.max(1, Math.min(N / 2 - 1, Math.round(THIRD[b] / binHz)))];
            out[b] = (float) (10 * Math.log10(p / Math.max(1, frames) + 1e-20));
        }
        return out;
    }

    /** Целевая кривая машины: +4 дБ в басу (до 60 Гц, к 300 Гц — ноль), ровно до 3 кГц, к 16 кГц −4 дБ. */
    static float target(float f) {
        if (f <= 60) return 4f;
        if (f < 300) return (float) (4 * (1 - Math.log(f / 60.0) / Math.log(5)));
        if (f <= 3000) return 0f;
        return (float) Math.max(-4, -4 * Math.log(f / 3000.0) / Math.log(16000 / 3000.0));
    }

    /** Разбор: null — шум из динамиков почти не громче тишины (сделать громче). */
    static Result analyse(float[] noise, float[] meas, int rate) {
        float[] nb = bands(noise, rate), mb = bands(meas, rate);
        int n = THIRD.length;
        // запас над шумом в середине (200–4000 Гц)
        double snr = 0;
        int sn = 0;
        for (int b = 0; b < n; b++) {
            if (THIRD[b] < 200 || THIRD[b] > 4000) continue;
            snr += mb[b] - nb[b];
            sn++;
        }
        snr /= Math.max(1, sn);
        if (snr < 10) return null;
        // вычесть шум салона (по мощности) и выровнять к средней громкости 300–3000 Гц
        float[] resp = new float[n];
        for (int b = 0; b < n; b++) {
            double sig = Math.pow(10, mb[b] / 10) - Math.pow(10, nb[b] / 10);
            resp[b] = (float) (10 * Math.log10(Math.max(sig, Math.pow(10, mb[b] / 10) * 0.05)));
        }
        double mid = 0;
        int mn = 0;
        for (int b = 0; b < n; b++) {
            if (THIRD[b] < 300 || THIRD[b] > 3000) continue;
            mid += resp[b];
            mn++;
        }
        mid /= mn;
        for (int b = 0; b < n; b++) resp[b] -= (float) mid;
        // поправка = цель − как звучит, сглаженная по соседним полосам
        float[] raw = new float[n];
        for (int b = 0; b < n; b++) raw[b] = target(THIRD[b]) - resp[b];
        float[] c = new float[n];
        for (int b = 0; b < n; b++) {
            float l = raw[Math.max(0, b - 1)], r = raw[Math.min(n - 1, b + 1)];
            c[b] = 0.25f * l + 0.5f * raw[b] + 0.25f * r;
            float maxUp = THIRD[b] < 80 ? 3f : THIRD[b] > 12500 ? 3f : 6f;   // микрофон телефона и края — осторожно
            c[b] = Math.max(-10f, Math.min(maxUp, c[b]));
        }
        // общая громкость как была: среднее 300–3000 Гц — к нулю
        double cm = 0;
        for (int b = 0; b < n; b++) if (THIRD[b] >= 300 && THIRD[b] <= 3000) cm += c[b];
        cm /= mn;
        for (int b = 0; b < n; b++) c[b] = Math.round((c[b] - (float) cm) * 2f) / 2f;
        Result r = new Result();
        r.freqs = THIRD.clone();
        r.response = resp;
        r.correction = c;
        r.snr = (float) snr;
        return r;
    }
}
