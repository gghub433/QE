package lv.budseq;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Проверка каналов в машине: три коротких сигнала только в ЛЕВОМ канале, пауза, три в ПРАВОМ.
 * Человек говорит, откуда пришёл первый — так EQ узнаёт, не перепутаны ли каналы и не моно ли магнитола.
 * На время теста эквалайзер обходится (без баланса и фокуса), иначе тест исказит сам себя.
 */
final class SpeakerTest {
    interface Done {
        void onDone();
    }

    private static final int RATE = 48000;
    private static volatile boolean playing;

    private SpeakerTest() { }

    static boolean isPlaying() {
        return playing;
    }

    static void play(Context ctx, final Done done) {
        if (playing) return;
        playing = true;
        final EqEngine eq = EqEngine.get(ctx);
        final Handler main = new Handler(Looper.getMainLooper());
        eq.setBypass(EqEngine.BYPASS_TEST);
        new Thread(new Runnable() {
            public void run() {
                AudioTrack t = null;
                try {
                    short[] pcm = build();
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
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.length * 2)
                            .build();
                    t.write(pcm, 0, pcm.length);
                    t.play();
                    Thread.sleep(pcm.length / 2 * 1000L / RATE + 300);
                } catch (Exception e) {
                    Log.w("EQ", "speaker test failed", e);
                } finally {
                    if (t != null) {
                        try {
                            t.release();
                        } catch (Exception ignored) {
                        }
                    }
                }
                main.post(new Runnable() {
                    public void run() {
                        eq.setBypass(EqEngine.BYPASS_NONE);
                        playing = false;
                        done.onDone();
                    }
                });
            }
        }, "eq-speaker-test").start();
    }

    /** Стерео PCM: 3 «динь» слева, 0,6 с тишины, 3 «динь» справа. */
    static short[] build() {
        float beep = 0.16f, gap = 0.09f, pause = 0.6f;
        int beepN = (int) (RATE * beep), gapN = (int) (RATE * gap), pauseN = (int) (RATE * pause);
        int side = 3 * beepN + 2 * gapN;
        int frames = side * 2 + pauseN;
        short[] pcm = new short[frames * 2];
        for (int ch = 0; ch < 2; ch++) {
            int start = ch == 0 ? 0 : side + pauseN;
            for (int b = 0; b < 3; b++) {
                int off = start + b * (beepN + gapN);
                for (int i = 0; i < beepN; i++) {
                    double t = i / (double) RATE;
                    // мягкий «динь»: до + соль, быстрая атака, плавное затухание
                    double env = Math.min(1, i / (RATE * 0.008)) * Math.exp(-t * 9);
                    double v = (Math.sin(2 * Math.PI * 1047 * t) + 0.6 * Math.sin(2 * Math.PI * 1568 * t)) / 1.6;
                    pcm[(off + i) * 2 + ch] = (short) (v * env * 0.5 * Short.MAX_VALUE);
                }
            }
        }
        return pcm;
    }
}
