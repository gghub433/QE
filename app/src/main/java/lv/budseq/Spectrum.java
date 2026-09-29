package lv.budseq;

import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Визуализатор: берёт спектр того, что играет на телефоне (Visualizer, сессия 0),
 * и раскладывает его по полосам эквалайзера. Нужен доступ к микрофону —
 * так Android называет доступ к звуку для визуализаторов; сам микрофон не используется.
 */
public final class Spectrum {

    public interface Listener {
        void onLevels(float[] levels);
    }

    private Visualizer vis;
    private float[] edges = new float[0];

    /** Границы полос: [low0, high0=low1, ..., highN]. */
    public void setBands(float[] freqs) {
        float[] e = new float[freqs.length + 1];
        e[0] = freqs[0] / 1.4f;
        for (int i = 0; i < freqs.length - 1; i++) e[i + 1] = (float) Math.sqrt(freqs[i] * freqs[i + 1]);
        e[freqs.length] = Math.min(22000f, freqs[freqs.length - 1] * 1.4f);
        edges = e;
    }

    public boolean start(final Listener l) {
        stop();
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(2048, range[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] wave, int rate) { }

                public void onFftDataCapture(Visualizer v, byte[] fft, int rateMilliHz) {
                    l.onLevels(toLevels(fft, rateMilliHz / 1000f));
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 30000), false, true);
            vis.setEnabled(true);
            return true;
        } catch (Throwable t) {
            Log.w("EQ", "visualizer unavailable", t);
            stop();
            return false;
        }
    }

    public void stop() {
        if (vis != null) {
            try {
                vis.setEnabled(false);
            } catch (Throwable ignored) {
            }
            vis.release();
            vis = null;
        }
    }

    private float[] toLevels(byte[] fft, float sampleRate) {
        int bands = edges.length - 1;
        float[] out = new float[Math.max(0, bands)];
        int n = fft.length;           // n байт = n/2 комплексных бинов
        if (n < 4 || bands <= 0) return out;
        float binHz = sampleRate / n;
        for (int b = 0; b < bands; b++) {
            int k0 = Math.max(1, (int) Math.floor(edges[b] / binHz));
            int k1 = Math.min(n / 2 - 1, Math.max(k0, (int) Math.ceil(edges[b + 1] / binHz)));
            // энергия полосы = сумма мощностей бинов (для музыки это даёт ровную картину по октавам)
            double power = 0;
            for (int k = k0; k <= k1; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                power += re * re + im * im;
            }
            double db = 10 * Math.log10(power + 1);   // ~0 … 70 dB
            out[b] = (float) Math.max(0, Math.min(1, (db - 10) / 40));
        }
        return out;
    }
}
