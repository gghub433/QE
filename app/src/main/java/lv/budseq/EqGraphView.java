package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Ползунки эквалайзера (9 / 15 / 31 полоса). Двигать можно по одному
 * или вести пальцем поперёк — «рисовать» кривую. Позади — спектр музыки.
 */
public class EqGraphView extends View {

    public interface Listener {
        void onBandChanged(int band, float db);
    }

    private static final int ACCENT = Color.rgb(0x3E, 0x7B, 0xFA);
    private static final float MIN = -12f, MAX = 12f;

    private float[] freqs = new float[0];
    private float[] values = new float[0];
    private float[] spectrum;      // 0..1 для каждой полосы (или null)
    private float[] correction = new float[0];   // AutoEQ по полосам (дБ), пусто — нет
    private final Paint corrLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corrPath = new Path();
    private final float[] shown = new float[64];

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    private Listener listener;
    private int activeBand = -1;

    public EqGraphView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        track.setColor(Color.rgb(0x4A, 0x4C, 0x52));
        track.setStrokeCap(Paint.Cap.ROUND);
        fill.setColor(ACCENT);
        fill.setStrokeCap(Paint.Cap.ROUND);
        thumb.setColor(Color.WHITE);
        grid.setStrokeWidth(1 * d);
        label.setColor(Color.rgb(0xA0, 0xA3, 0xAA));
        label.setTextSize(12 * d);
        label.setTextAlign(Paint.Align.CENTER);
        valueText.setColor(Color.WHITE);
        valueText.setTextSize(11 * d);
        valueText.setTextAlign(Paint.Align.CENTER);
        spec.setColor(ACCENT);
        corrLine.setStyle(Paint.Style.STROKE);
        corrLine.setStrokeWidth(2 * d);
        corrLine.setStrokeJoin(Paint.Join.ROUND);
        corrLine.setColor(Color.argb(170, 0xFF, 0xB3, 0x40));
    }

    /** Кривая AutoEQ поверх ползунков (складывается с ними в звуке). Пустой массив — скрыть. */
    public void setCorrection(float[] c) {
        correction = c == null ? new float[0] : c;
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setBands(float[] frequencies, float[] v) {
        freqs = frequencies;
        values = new float[v.length];
        System.arraycopy(v, 0, values, 0, v.length);
        invalidate();
    }

    /** Уровни спектра 0..1 по полосам (null — скрыть). */
    public void setSpectrum(float[] levels) {
        spectrum = levels;
        invalidate();
    }

    private int n() { return values.length; }

    // Геометрия
    private float top() { return 30 * d; }
    private float bottom() { return getHeight() - 34 * d; }
    private float colW() { return (getWidth() - 24 * d) / Math.max(1, n()); }
    private float colX(int i) { return 12 * d + colW() * (i + 0.5f); }

    private float yFor(float db) {
        float t = (db - MIN) / (MAX - MIN);
        return bottom() - t * (bottom() - top());
    }

    private float dbFor(float y) {
        float t = (bottom() - y) / (bottom() - top());
        float db = MIN + t * (MAX - MIN);
        db = Math.round(db * 2f) / 2f; // шаг 0.5 dB
        return Math.max(MIN, Math.min(MAX, db));
    }

    private boolean showLabel(int i) {
        int n = n();
        if (n <= 9) return true;
        if (n <= 15) return i % 2 == 0;
        return i % 3 == 2;
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = n();
        if (n == 0) return;
        float cw = colW();

        // спектр музыки
        if (spectrum != null) {
            for (int i = 0; i < n && i < spectrum.length && i < shown.length; i++) {
                float target = Math.max(0f, Math.min(1f, spectrum[i]));
                shown[i] += (target - shown[i]) * (target > shown[i] ? 0.6f : 0.15f);
                float h = (bottom() - top()) * shown[i];
                spec.setAlpha(55 + (int) (60 * shown[i]));
                r.set(colX(i) - cw * 0.38f, bottom() - h, colX(i) + cw * 0.38f, bottom());
                c.drawRoundRect(r, 3 * d, 3 * d, spec);
            }
            postInvalidateOnAnimation();
        }

        // сетка: +12, +6, 0, -6, -12
        for (int db = -12; db <= 12; db += 6) {
            float y = yFor(db);
            grid.setColor(db == 0 ? Color.rgb(0x55, 0x58, 0x5E) : Color.rgb(0x2C, 0x2E, 0x33));
            c.drawLine(8 * d, y, getWidth() - 8 * d, y, grid);
        }

        float stroke = n <= 9 ? 4 * d : n <= 15 ? 3.5f * d : 2.5f * d;
        float thumbR = n <= 9 ? 8 * d : n <= 15 ? 6.5f * d : 4.5f * d;
        track.setStrokeWidth(stroke);
        fill.setStrokeWidth(stroke);
        float zero = yFor(0);
        for (int i = 0; i < n; i++) {
            float x = colX(i);
            float y = yFor(values[i]);
            c.drawLine(x, top(), x, bottom(), track);
            c.drawLine(x, zero, x, y, fill);
            boolean active = i == activeBand;
            thumb.setColor(active ? ACCENT : Color.WHITE);
            c.drawCircle(x, y, active ? thumbR * 1.4f : thumbR, thumb);
            if (n <= 9) {
                c.drawText(fmt(values[i]), x, top() - 12 * d, valueText);
            }
            if (showLabel(i)) {
                c.drawText(EqEngine.label(freqs[i]), x, getHeight() - 12 * d, label);
            }
        }

        // коррекция AutoEQ — тонкая оранжевая линия (итоговый звук = ползунки + линия)
        if (correction.length == n) {
            corrPath.reset();
            for (int i = 0; i < n; i++) {
                float y = yFor(Math.max(MIN, Math.min(MAX, correction[i])));
                if (i == 0) corrPath.moveTo(colX(i), y);
                else corrPath.lineTo(colX(i), y);
            }
            c.drawPath(corrPath, corrLine);
            label.setColor(Color.rgb(0xFF, 0xB3, 0x40));
            label.setTextAlign(Paint.Align.LEFT);
            c.drawText("AutoEQ", 12 * d, 18 * d, label);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(Color.rgb(0xA0, 0xA3, 0xAA));
        }

        // «пузырь» со значением для 15/31 полос
        if (n > 9 && activeBand >= 0) {
            String t = EqEngine.label(freqs[activeBand]) + " Hz  " + fmt(values[activeBand]) + " dB";
            float tw = valueText.measureText(t) + 20 * d;
            float bx = Math.max(tw / 2 + 4 * d, Math.min(getWidth() - tw / 2 - 4 * d, colX(activeBand)));
            r.set(bx - tw / 2, 2 * d, bx + tw / 2, 24 * d);
            fill.setStyle(Paint.Style.FILL);
            c.drawRoundRect(r, 11 * d, 11 * d, fill);
            c.drawText(t, bx, 17 * d, valueText);
        }
    }

    private static String fmt(float v) {
        return String.format(Locale.US, v > 0 ? "+%.1f" : "%.1f", v);
    }

    private int bandAt(float x) {
        int b = (int) ((x - 12 * d) / colW());
        return Math.max(0, Math.min(n() - 1, b));
    }

    private void set(int band, float db) {
        if (db != values[band]) {
            values[band] = db;
            if (listener != null) listener.onBandChanged(band, db);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (n() == 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                activeBand = bandAt(e.getX());
                getParent().requestDisallowInterceptTouchEvent(true);
                set(activeBand, dbFor(e.getY()));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeBand < 0) return true;
                int band = bandAt(e.getX());
                float db = dbFor(e.getY());
                if (band != activeBand) {
                    // ведём пальцем поперёк: плавно заполняем промежуточные полосы
                    float from = values[activeBand];
                    int step = band > activeBand ? 1 : -1;
                    int count = Math.abs(band - activeBand);
                    for (int k = 1; k <= count; k++) {
                        float v = from + (db - from) * k / count;
                        set(activeBand + k * step, Math.round(v * 2f) / 2f);
                    }
                    activeBand = band;
                } else {
                    set(band, db);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeBand = -1;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
