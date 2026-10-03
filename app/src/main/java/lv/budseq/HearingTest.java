package lv.budseq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/**
 * Тест слуха для кривой «Мой слух»: в наушниках звучат тихие гудки (10 частот, каждая дважды, вразнобой),
 * гудок с каждым разом громче на 2,5 дБ. Человек жмёт «Слышу» — запоминаем порог.
 * На время теста эквалайзер обходится. Итог — коррекция по правилу «половины потери» относительно
 * нормальной кривой порога слышимости (ISO 226:2003), без изменения общей громкости.
 */
final class HearingTest {

    interface Ui {
        /** Начался гудок: номер (с 1), всего, частота, Гц. */
        void onTrial(int index, int total, int freq);

        /** Громкость гудка сейчас, дБ (от полной). */
        void onLevel(float db);

        /** Готово: пороги по частотам FREQS, дБ. */
        void onDone(float[] thresholds);

        void onStopped();
    }

    static final int[] FREQS = {125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000, 12000};
    /** Нормальный порог слышимости относительно 1 кГц (ISO 226:2003), дБ. */
    private static final float[] REF = {19.7f, 9.0f, 2.0f, 0f, -3.7f, -8.4f, -7.8f, 3.6f, 10.2f, 9.9f};
    private static final int RATE = 48000;
    private static final float START_DB = -78f, MAX_DB = -8f, STEP_DB = 2.5f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running, heard, toneOn;
    private Thread thread;

    void start(final Context ctx, final Ui ui) {
        if (running) return;
        running = true;
        final EqEngine eq = EqEngine.get(ctx);
        eq.setBypass(EqEngine.BYPASS_TEST);
        thread = new Thread(new Runnable() {
            public void run() {
                final float[] sum = new float[FREQS.length];
                final int[] count = new int[FREQS.length];
                AudioTrack t = null;
                boolean done = false;
                try {
                    int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    t = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(Math.max(min, RATE / 5 * 4))
                            .build();
                    t.play();
                    // каждая частота дважды, порядок вразнобой — не угадать следующий гудок
                    int[] order = new int[FREQS.length * 2];
                    for (int i = 0; i < order.length; i++) order[i] = i % FREQS.length;
                    Random rnd = new Random();
                    for (int i = order.length - 1; i > 0; i--) {
                        int j = rnd.nextInt(i + 1);
                        int x = order[i];
                        order[i] = order[j];
                        order[j] = x;
                    }
                    for (int n = 0; n < order.length && running; n++) {
                        final int fi = order[n];
                        final int num = n + 1, total = order.length;
                        main.post(new Runnable() {
                            public void run() { ui.onTrial(num, total, FREQS[fi]); }
                        });
                        silence(t, 0.6f + rnd.nextFloat() * 0.8f);   // пауза перед гудком разной длины
                        heard = false;
                        float db = START_DB;
                        float got = MAX_DB;
                        while (running && db <= MAX_DB) {
                            final float shown = db;
                            main.post(new Runnable() {
                                public void run() { ui.onLevel(shown); }
                            });
                            toneOn = true;
                            tone(t, FREQS[fi], db);
                            toneOn = false;
                            silence(t, 0.25f);
                            if (heard) {
                                got = db;
                                break;
                            }
                            db += STEP_DB;
                        }
                        sum[fi] += got;
                        count[fi]++;
                    }
                    done = running;
                } catch (Exception ignored) {
                } finally {
                    if (t != null) {
                        try {
                            t.stop();
                        } catch (Exception ignored) {
                        }
                        t.release();
                    }
                    final boolean finished = done;
                    main.post(new Runnable() {
                        public void run() {
                            eq.setBypass(EqEngine.BYPASS_NONE);
                            running = false;
                            if (finished) {
                                float[] th = new float[FREQS.length];
                                for (int i = 0; i < th.length; i++) th[i] = count[i] > 0 ? sum[i] / count[i] : MAX_DB;
                                ui.onDone(th);
                            } else {
                                ui.onStopped();
                            }
                        }
                    });
                }
            }
        }, "eq-hearing-test");
        thread.start();
    }

    /** «Слышу»: засчитывается, только если гудок звучит или только что звучал. */
    void heard() {
        heard = true;
    }

    boolean toneNow() {
        return toneOn;
    }

    void stop() {
        running = false;
    }

    boolean isRunning() {
        return running;
    }

    /** Гудок 0,3 с с мягкими краями (без щелчка — щелчок слышно раньше самого тона). */
    private static void tone(AudioTrack t, int freq, float db) {
        int n = (int) (RATE * 0.3f), ramp = (int) (RATE * 0.03f);
        double amp = Math.pow(10, db / 20.0) * Short.MAX_VALUE;
        short[] pcm = new short[n * 2];
        for (int i = 0; i < n; i++) {
            double env = 1;
            if (i < ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * i / ramp);
            else if (i > n - ramp) env = 0.5 - 0.5 * Math.cos(Math.PI * (n - i) / ramp);
            short v = (short) (Math.sin(2 * Math.PI * freq * i / (double) RATE) * amp * env);
            pcm[i * 2] = v;
            pcm[i * 2 + 1] = v;
        }
        t.write(pcm, 0, pcm.length);
    }

    private static void silence(AudioTrack t, float sec) {
        short[] pcm = new short[(int) (RATE * sec) * 2];
        t.write(pcm, 0, pcm.length);
    }

    /**
     * Коррекция, дБ, по частотам FREQS: отклонение порога от нормы (относительно 1 кГц), половина —
     * подъём (не больше +8 дБ, не меньше −4); среднее 500–4000 Гц — к нулю, чтобы общая громкость не менялась.
     */
    static float[] correction(float[] th) {
        int k1 = 3;   // 1000 Гц
        float[] g = new float[th.length];
        for (int i = 0; i < th.length; i++) {
            float dev = (th[i] - th[k1]) - REF[i];
            g[i] = Math.max(-4f, Math.min(8f, 0.5f * dev));
        }
        float mean = 0f;
        int n = 0;
        for (int i = 2; i <= 6; i++) {
            mean += g[i];
            n++;
        }
        mean /= n;
        for (int i = 0; i < g.length; i++) g[i] = Math.round(Math.max(-6f, Math.min(8f, g[i] - mean)) * 2f) / 2f;
        return g;
    }

    static float[] freqs() {
        float[] f = new float[FREQS.length];
        for (int i = 0; i < f.length; i++) f[i] = FREQS[i];
        return f;
    }
}
