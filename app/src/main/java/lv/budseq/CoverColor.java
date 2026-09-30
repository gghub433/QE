package lv.budseq;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Доминирующий яркий цвет обложки (свой простой алгоритм, без библиотек):
 * уменьшить до 24×24 → отбросить серые и тёмные пиксели → гистограмма по оттенку (36 корзин по 10°,
 * вес = насыщенность × яркость) → средний цвет самой тяжёлой корзины → сделать ярче.
 */
public final class CoverColor {
    private static final int SIZE = 24, BINS = 36;
    private static final float MIN_SAT = 0.25f, MIN_VAL = 0.25f;

    private CoverColor() { }

    /** 0 — ярких цветов на обложке нет (чёрно-белая, серая) → берём цвет темы. */
    public static int dominant(Bitmap b) {
        if (b == null) return 0;
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, SIZE, SIZE, true);
            int[] px = new int[SIZE * SIZE];
            small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            return dominant(px);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Сам алгоритм на массиве ARGB (можно проверить вне Android). */
    static int dominant(int[] px) {
        float[] weight = new float[BINS];
        float[] r = new float[BINS], g = new float[BINS], bl = new float[BINS];
        float[] hsv = new float[3];
        for (int c : px) {
            if (Color.alpha(c) < 128) continue;
            hsv(c, hsv);
            if (hsv[1] < MIN_SAT || hsv[2] < MIN_VAL) continue;   // серое и тёмное — не цвет
            int bin = Math.min(BINS - 1, (int) (hsv[0] / (360f / BINS)));
            float w = hsv[1] * hsv[2];
            weight[bin] += w;
            r[bin] += Color.red(c) * w;
            g[bin] += Color.green(c) * w;
            bl[bin] += Color.blue(c) * w;
        }
        // соседние корзины помогают: оттенок на границе не должен проигрывать
        int best = -1;
        float bestW = 0;
        for (int i = 0; i < BINS; i++) {
            float w = weight[i] + 0.5f * (weight[(i + 1) % BINS] + weight[(i + BINS - 1) % BINS]);
            if (w > bestW) {
                bestW = w;
                best = i;
            }
        }
        // меньше ~4% площади яркого цвета — это не «цвет обложки»
        if (best < 0 || weight[best] < px.length * 0.04f * 0.3f) return 0;
        int avg = Color.rgb(Math.round(r[best] / weight[best]), Math.round(g[best] / weight[best]),
                Math.round(bl[best] / weight[best]));
        hsv(avg, hsv);
        hsv[1] = Math.max(0.55f, Math.min(1f, hsv[1]));
        hsv[2] = Math.max(0.88f, hsv[2]);   // на тёмном фоне цвет должен светиться
        return fromHsv(hsv);
    }

    /** RGB → HSV (свой, чтобы алгоритм работал и вне Android). */
    static void hsv(int c, float[] out) {
        float r = Color.red(c) / 255f, g = Color.green(c) / 255f, b = Color.blue(c) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = 60 * (((g - b) / d) % 6);
        else if (max == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        out[0] = h;
        out[1] = max == 0 ? 0 : d / max;
        out[2] = max;
    }

    static int fromHsv(float[] hsv) {
        float h = hsv[0], s = hsv[1], v = hsv[2];
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
