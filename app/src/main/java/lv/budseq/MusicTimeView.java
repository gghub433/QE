package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Music Time: столбики «сколько слушал» за последние 7 дней.
 * Сегодня — акцентным цветом, над самым высоким столбиком — время.
 */
public class MusicTimeView extends View {

    private static final int ACCENT = Color.rgb(0x3E, 0x7B, 0xFA);
    private static final int BAR = Color.rgb(0x3A, 0x3B, 0x40);
    private static final int GREY_TEXT = Color.rgb(0xA0, 0xA3, 0xAA);

    private long[] secs = new long[7];
    private String[] labels = new String[7];
    private float grow = 0f;
    private boolean running;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;

    public MusicTimeView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        grid.setColor(Color.rgb(0x2C, 0x2E, 0x33));
        grid.setStrokeWidth(1 * d);
    }

    /** seconds[0] — самый старый день, последний — сегодня; labels — подписи дней. */
    public void setData(long[] seconds, String[] dayLabels) {
        boolean changed = seconds.length != secs.length;
        for (int i = 0; !changed && i < seconds.length; i++) changed = seconds[i] != secs[i];
        secs = seconds;
        labels = dayLabels;
        if (changed) grow = Math.min(grow, 0.3f);
        startLoop();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    private void startLoop() {
        if (running) return;
        running = true;
        postOnAnimation(tick);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running || !isAttachedToWindow()) {
                running = false;
                return;
            }
            grow += (1f - grow) * 0.12f;
            invalidate();
            if (grow < 0.999f) {
                postOnAnimation(this);
            } else {
                grow = 1f;
                running = false;
            }
        }
    };

    /** Для отрисовки вне Android: сразу финальное состояние. */
    void finish() {
        grow = 1f;
    }

    /** Подпись над столбиком без слов (не зависит от языка): 1:25 = 1 ч 25 мин, 0:42 = 42 мин. */
    static String shortTime(long s) {
        long m = s / 60;
        return (m / 60) + ":" + (m % 60 < 10 ? "0" : "") + (m % 60);
    }

    @Override
    protected void onDraw(Canvas c) {
        draw(c, getWidth(), getHeight());
    }

    void draw(Canvas c, float w, float h) {
        int n = secs.length;
        if (n == 0) return;
        long max = 1;
        int maxIdx = -1;
        for (int i = 0; i < n; i++) {
            if (secs[i] > max) {
                max = secs[i];
                maxIdx = i;
            }
        }
        // шкала: минимум 30 минут, чтобы пара минут не выглядела как рекорд
        long scale = Math.max(max, 30 * 60);

        float top = 26 * d, bottom = h - 26 * d;
        float colW = w / n;
        float bw = Math.min(colW * 0.56f, 34 * d);

        // линии сетки
        for (int k = 0; k <= 2; k++) {
            float y = bottom - (bottom - top) * k / 2f;
            c.drawLine(4 * d, y, w - 4 * d, y, grid);
        }

        text.setTextSize(12 * d);
        for (int i = 0; i < n; i++) {
            float x = colW * (i + 0.5f);
            float t = secs[i] / (float) scale;
            float bh = Math.max(secs[i] > 0 ? 4 * d : 2 * d, (bottom - top) * t * grow);
            boolean today = i == n - 1;
            fill.setColor(today ? ACCENT : BAR);
            if (!today && secs[i] > 0) fill.setColor(Color.rgb(0x4A, 0x5E, 0x8C));
            r.set(x - bw / 2, bottom - bh, x + bw / 2, bottom);
            c.drawRoundRect(r, Math.min(bw / 2, 8 * d), Math.min(bw / 2, 8 * d), fill);

            text.setColor(today ? Color.WHITE : GREY_TEXT);
            text.setFakeBoldText(today);
            String lbl = labels != null && i < labels.length && labels[i] != null ? labels[i] : "";
            c.drawText(lbl, x, h - 6 * d, text);

            if ((i == maxIdx || today) && secs[i] > 0) {
                text.setColor(Color.WHITE);
                text.setFakeBoldText(true);
                c.drawText(shortTime(secs[i]), x, bottom - bh - 6 * d, text);
            }
        }
        text.setFakeBoldText(false);
    }
}
