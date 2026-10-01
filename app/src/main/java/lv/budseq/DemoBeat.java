package lv.budseq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Random;

/**
 * «Послушать»: короткий бит (бочка, бас, малый барабан, тарелки) по кругу через EQ —
 * двигаешь панч или ползунки и сразу слышишь разницу, даже без музыки.
 * Если EQ не стоит на весь звук телефона, он подключается к звуку самого бита.
 */
final class DemoBeat {
    private static final int RATE = 48000;
    /** Сам выключается через минуту, если забыли. */
    private static final long MAX_MS = 60000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static AudioTrack track;
    private static int session;
    private static boolean starting;
    private static Runnable onStopped;
    private static EqEngine eqRef;

    private DemoBeat() { }

    static boolean isPlaying() {
        return track != null || starting;
    }

    /** stopped — на главном потоке, когда бит остановился (сам или кнопкой). */
    static void start(Context ctx, Runnable stopped) {
        if (isPlaying()) return;
        starting = true;
        onStopped = stopped;
        final EqEngine eq = EqEngine.get(ctx);
        eqRef = eq;
        new Thread(new Runnable() {
            public void run() {
                AudioTrack t = null;
                try {
                    short[] pcm = build();
                    t = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                    .build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                    t.write(pcm, 0, pcm.length);
                    t.setLoopPoints(0, pcm.length / 2, -1);
                } catch (Exception e) {
                    Log.w("EQ", "demo beat failed", e);
                    if (t != null) t.release();
                    t = null;
                }
                final AudioTrack ready = t;
                main.post(new Runnable() {
                    public void run() {
                        if (!starting || ready == null) {
                            // остановили, пока готовился, или не вышло
                            if (ready != null) ready.release();
                            finish();
                            return;
                        }
                        starting = false;
                        track = ready;
                        // EQ не на весь звук — подключаемся к звуку бита, иначе разницы не слышно
                        if (!eq.globalOk && eq.attach(ready.getAudioSessionId())) session = ready.getAudioSessionId();
                        ready.play();
                        main.postDelayed(autoStop, MAX_MS);
                    }
                });
            }
        }, "eq-demo-beat").start();
    }

    private static final Runnable autoStop = new Runnable() {
        public void run() { stop(); }
    };

    static void stop() {
        main.removeCallbacks(autoStop);
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
        finish();
    }

    private static void finish() {
        starting = false;
        if (session != 0 && eqRef != null) eqRef.detach(session);
        session = 0;
        Runnable r = onStopped;
        onStopped = null;
        if (r != null) r.run();
    }

    /** 2 такта по 120 BPM (4 с) — стерео PCM, конец бесшовно переходит в начало. */
    static short[] build() {
        int beat = RATE / 2;                       // 120 BPM
        int frames = beat * 8;
        float[] l = new float[frames], r = new float[frames];
        Random rnd = new Random(1);
        // бочка: тон 140→48 Гц и короткий щелчок
        int kickN = (int) (0.45 * RATE);
        float[] kick = new float[kickN];
        for (int i = 0; i < kickN; i++) {
            double t = i / (double) RATE;
            double ph = 2 * Math.PI * (48 * t + (140 - 48) * 0.035 * (1 - Math.exp(-t / 0.035)));
            kick[i] = (float) ((Math.sin(ph) * Math.exp(-t / 0.16) + 0.25 * rnd.nextGaussian() * Math.exp(-t / 0.004))
                    * fade(i, kickN));
        }
        // малый: шум + 190 Гц
        int snareN = (int) (0.25 * RATE);
        float[] snare = new float[snareN];
        for (int i = 0; i < snareN; i++) {
            double t = i / (double) RATE;
            snare[i] = (float) ((0.6 * rnd.nextGaussian() * Math.exp(-t / 0.06)
                    + 0.5 * Math.sin(2 * Math.PI * 190 * t) * Math.exp(-t / 0.05)) * fade(i, snareN));
        }
        // тарелки: высокий шум
        int hatN = (int) (0.05 * RATE);
        float[] hat = new float[hatN];
        double prev = 0;
        for (int i = 0; i < hatN; i++) {
            double t = i / (double) RATE, n = rnd.nextGaussian();
            hat[i] = (float) ((n - prev) * Math.exp(-t / 0.012) * 0.25 * fade(i, hatN));
            prev = n;
        }
        // бас восьмыми: E1 E1 G1 E1 A1 A1 D2 G1
        double[] notes = {41.2, 41.2, 49.0, 41.2, 55.0, 55.0, 73.4, 49.0};
        int half = beat / 2;
        for (int b = 0; b < 8; b++) {
            int at = b * beat;
            mix(l, r, kick, at, 0.9f, 0f);
            if (b % 2 == 1) mix(l, r, snare, at, 0.5f, 0f);
            for (int h = 0; h < 2; h++) {
                int j = at + h * half;
                double f = notes[(b * 2 + h) % notes.length];
                for (int i = 0; i < half && j + i < frames; i++) {
                    double t = i / (double) RATE;
                    double env = Math.min(1, t / 0.01) * Math.exp(-t / 0.35) * fade(i, half);
                    float v = (float) ((Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t)) * env * 0.55);
                    l[j + i] += v;
                    r[j + i] += v;
                }
                mix(l, r, hat, j, 1f, h == 0 ? -0.3f : 0.3f);
            }
        }
        // тихий аккорд в середине (целое число периодов за 4 с — шва нет)
        for (int i = 0; i < frames; i++) {
            double t = i / (double) RATE;
            float pad = (float) (0.05 * (Math.sin(2 * Math.PI * 220 * t) + Math.sin(2 * Math.PI * 277 * t)
                    + Math.sin(2 * Math.PI * 330 * t)));
            l[i] += pad;
            r[i] += pad;
        }
        float peak = 1e-6f;
        for (int i = 0; i < frames; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        float k = 0.8f / peak * Short.MAX_VALUE;   // пик −2 дБ
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            pcm[i * 2] = (short) (l[i] * k);
            pcm[i * 2 + 1] = (short) (r[i] * k);
        }
        return pcm;
    }

    /** Последние 8 мс звука плавно гаснут — без щелчков между нотами и на стыке петли. */
    private static double fade(int i, int n) {
        return Math.min(1.0, (n - i) / (0.008 * RATE));
    }

    /** pan: −1 лево … 1 право. */
    private static void mix(float[] l, float[] r, float[] s, int at, float gain, float pan) {
        float gl = gain * Math.min(1f, 1f - pan), gr = gain * Math.min(1f, 1f + pan);
        for (int i = 0; i < s.length && at + i < l.length; i++) {
            l[at + i] += s[i] * gl;
            r[at + i] += s[i] * gr;
        }
    }
}
