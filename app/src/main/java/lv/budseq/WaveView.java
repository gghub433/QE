package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Живая волна под треком: три слоя синусоид, к краям затухают.
 * Когда музыка играет — волна большая и быстрая, на паузе почти ровная.
 */
public class WaveView extends View {

    private static final int ACCENT = Color.rgb(0x3E, 0x7B, 0xFA);

    private int color = ACCENT;
    private float level = 0.12f, target = 0.12f, time;
    private boolean running;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;

    public WaveView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setPlaying(boolean playing) {
        target = playing ? 1f : 0.12f;
        startLoop();
    }

    /** Цвет волны (например, из обложки); 0 — акцент приложения. */
    public void setColor(int c) {
        color = c == 0 ? ACCENT : c;
        invalidate();
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
            if (!isShown()) {
                postDelayed(this, 400);
                return;
            }
            step(0.016f);
            invalidate();
            postOnAnimation(this);
        }
    };

    void step(float dt) {
        level += (target - level) * 0.05f;
        time += dt * (0.6f + 1.4f * level);
    }

    @Override
    protected void onDraw(Canvas c) {
        draw(c, getWidth(), getHeight());
    }

    void draw(Canvas c, float w, float h) {
        paint(c, 0, 0, w, h, level, time, color, d, path, line);
    }

    /**
     * Фирменная волна EQ (одна на всё приложение: экран, виджет, окно, карточка итогов).
     * level 0..1 — размах, time — фаза анимации.
     */
    static void paint(Canvas c, float left, float top, float w, float h, float level, float time, int color,
                      float d, Path path, Paint line) {
        float mid = top + h / 2f;
        float maxAmp = h * 0.42f;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        // дальний слой светлее и тоньше, ближний — ярче
        for (int layer = 2; layer >= 0; layer--) {
            float amp = maxAmp * level * (1f - layer * 0.28f);
            float freq = 1.6f + layer * 0.7f;             // волн на всю ширину
            float speed = 1.8f + layer * 0.6f;
            float phase = time * speed + layer * 1.7f;
            path.reset();
            int steps = Math.max(24, (int) (w / (3 * d)));
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                // огибающая: к краям волна затухает
                float env = (float) Math.pow(Math.sin(Math.PI * t), 1.6);
                float wobble = 0.75f + 0.25f * (float) Math.sin(time * 0.9f + t * 5f + layer);
                float y = mid + (float) Math.sin(t * freq * Math.PI * 2 + phase) * amp * env * wobble;
                float x = left + t * w;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            line.setColor(layer == 0 ? color : mix(color, Color.WHITE, 0.25f * layer));
            line.setAlpha(layer == 0 ? 255 : layer == 1 ? 140 : 80);
            line.setStrokeWidth((layer == 0 ? 2.6f : 1.8f) * d);
            c.drawPath(path, line);
        }
    }

    static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
