package lv.budseq;

import android.content.Context;

/**
 * Спектр на вкладке «Эквалайзер»: то, что играет на телефоне, разложенное по полосам эквалайзера.
 * Звук берёт у AudioPulse — того же визуализатора, что и волна от баса. Раньше у спектра был свой
 * визуализатор: в Android он общий на всех, поэтому спектр забирал звук у волны, а закрываясь — выключал
 * его совсем, и волна до перезапуска получала тишину.
 * Нужен доступ к микрофону — так Android называет доступ к звуку для визуализаторов; сам микрофон не используется.
 */
public final class Spectrum implements AudioPulse.SpectrumListener {

    public interface Listener {
        void onLevels(float[] levels);
    }

    private float[] edges = new float[0];
    private volatile Listener listener;

    /** Границы полос: [low0, high0=low1, ..., highN]. */
    public void setBands(float[] freqs) {
        float[] e = new float[freqs.length + 1];
        e[0] = freqs[0] / 1.4f;
        for (int i = 0; i < freqs.length - 1; i++) e[i + 1] = (float) Math.sqrt(freqs[i] * freqs[i + 1]);
        e[freqs.length] = Math.min(22000f, freqs[freqs.length - 1] * 1.4f);
        edges = e;
    }

    /** Уровни приходят на главном потоке, ~20 раз в секунду. false — визуализатор недоступен. */
    public boolean start(Context c, Listener l) {
        stop();
        listener = l;
        AudioPulse p = AudioPulse.get();
        p.addSpectrum(this);
        if (p.acquire(c, this)) return true;
        stop();
        return false;
    }

    public void stop() {
        AudioPulse p = AudioPulse.get();
        p.removeSpectrum(this);
        p.release(this);
        listener = null;
    }

    public void onSpectrum(float[] re, float[] im, int n, float binHz) {
        Listener l = listener;
        if (l != null) l.onLevels(toLevels(re, im, n, binHz));
    }

    /** Мощность полос FFT кадра (float, окно Ханна, 1024 точки) → 0…1 по каждой полосе эквалайзера. */
    float[] toLevels(float[] re, float[] im, int n, float binHz) {
        float[] e = edges;
        int bands = e.length - 1;
        float[] out = new float[Math.max(0, bands)];
        if (bands <= 0 || binHz <= 0) return out;
        for (int b = 0; b < bands; b++) {
            int k0 = Math.max(1, (int) Math.floor(e[b] / binHz));
            int k1 = Math.min(n / 2 - 1, Math.max(k0, (int) Math.ceil(e[b + 1] / binHz)));
            // энергия полосы = сумма мощностей бинов (для музыки это даёт ровную картину по октавам)
            double power = 0;
            for (int k = k0; k <= k1; k++) power += re[k] * re[k] + im[k] * im[k];
            double db = 10 * Math.log10(power + 1e-9);   // громкая музыка ~20 … 50 дБ
            out[b] = (float) Math.max(0, Math.min(1, (db - 10) / 40));
        }
        return out;
    }
}
