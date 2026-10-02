package lv.budseq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Фирменная волна EQ — «всплески»: тонкие вертикальные пики, зеркальные вверх и вниз
 * от светящейся линии; у центра белые, к концам — цвет волны. В центре — бас, к краям — верх.
 * В карточке «Сейчас играет» пики идут от настоящего звука (AudioPulse), в остальных местах
 * (загрузка, всплывающее окно) — нарисованная анимация того же вида.
 */
public class WaveView extends View {

    private int color = Theme.liveWave();
    private float level = 0.12f, target = 0.12f, time;
    /** Сколько прошло с начала «вдоха» (с), -1 — не дышим. */
    private float breath = -1f;
    private static final float BREATH_IN = 0.3f, BREATH_OUT = 0.4f;

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    private boolean running;
    /** Живой звук: пики от музыки (карточка «Сейчас играет»). */
    private boolean live, playing, acquired;
    private final float[] raw = new float[AudioPulse.BARS];
    private final float[] shown = new float[AudioPulse.BARS];
    private float glow, glowTarget;

    private final Paint spike = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public WaveView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
    }

    /** Постоянный размах (загрузочный экран, всплывающее окно). */
    public void setLevel(float l) {
        target = Math.max(0f, Math.min(1f, l));
        startLoop();
    }

    /** Фирменный «вдох»: размах 0 → 1 → 0,6 примерно за 700 мс, потом — к обычному уровню. */
    public void breathe() {
        breath = 0f;
        level = 0f;
        startLoop();
    }

    public void setPlaying(boolean p) {
        playing = p;
        target = p ? 1f : 0.12f;
        startLoop();
    }

    /** Пики от настоящего звука телефона (нужен доступ к звуку — см. AudioPulse). */
    public void setLive(boolean on) {
        live = on;
        updatePulse();
    }

    /** Доступ к звуку только что выдали — подключиться заново. */
    public void retryLive() {
        acquired = false;
        updatePulse();
    }

    /** Есть ли настоящий звук (иначе волна рисует анимацию). */
    public boolean isLive() {
        return live && AudioPulse.get().live();
    }

    private void updatePulse() {
        boolean want = live && isAttachedToWindow();
        if (want && !acquired) {
            AudioPulse.get().acquire(getContext(), this);
            acquired = true;
        } else if (!want && acquired) {
            AudioPulse.get().release(this);
            acquired = false;
        }
    }

    /** Цвет волны (например, из обложки); 0 — цвет волны из темы. */
    public void setColor(int c) {
        color = c == 0 ? Theme.wave() : c;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePulse();
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
        updatePulse();
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
        if (breath >= 0f) {
            breath += dt;
            if (breath < BREATH_IN) {
                level = ease(breath / BREATH_IN);
            } else if (breath < BREATH_IN + BREATH_OUT) {
                level = 1f - 0.4f * ease((breath - BREATH_IN) / BREATH_OUT);
            } else {
                level = 0.6f;
                breath = -1f;
            }
        } else {
            level += (target - level) * 0.05f;
        }
        time += dt;
        AudioPulse pulse = AudioPulse.get();
        if (live && pulse.live()) {
            // настоящий звук: на паузе пики ложатся сами (тишина)
            pulse.levels(raw);
            glowTarget = pulse.loudness();
        } else if (live) {
            // живого звука нет (нет доступа или тишина) — честно: спокойная линия, без выдуманных пиков
            synth(raw, playing ? 0.1f : 0f, time);
            glowTarget = playing ? 0.3f : 0f;
        } else {
            synth(raw, level, time);
            glowTarget = 0.35f + 0.5f * level;
        }
        // пик взлетает сразу, опадает плавно
        for (int i = 0; i < raw.length; i++) {
            float k = raw[i] > shown[i] ? 0.6f : 0.13f;
            shown[i] += (raw[i] - shown[i]) * k;
        }
        glow += (glowTarget - glow) * 0.15f;
    }

    @Override
    protected void onDraw(Canvas c) {
        draw(c, getWidth(), getHeight());
    }

    void draw(Canvas c, float w, float h) {
        paintSpikes(c, 0, 0, w, h, shown, glow, time, color, d, spike, core);
    }

    /** Нарисованная «музыка» для мест без звука: уровни полос от центра к краю. */
    static void synth(float[] out, float level, float time) {
        for (int b = 0; b < out.length; b++) {
            double u = b / (double) out.length;
            double wave = 0.5 + 0.5 * Math.sin(time * 2.3 + b * 0.55) * Math.sin(time * 1.1 + b * 0.21);
            out[b] = (float) Math.max(0, Math.min(1, level * (0.3 + 0.7 * wave) * (1 - 0.45 * u)));
        }
    }

    /**
     * Фирменная волна (виджет, карточка итогов): нарисованная, level 0..1 — размах, time — фаза.
     * path не нужен (оставлен для старых вызовов).
     */
    static void paint(Canvas c, float left, float top, float w, float h, float level, float time, int color,
                      float d, Path path, Paint line) {
        float[] half = new float[AudioPulse.BARS];
        synth(half, level, time);
        paintSpikes(c, left, top, w, h, half, 0.35f + 0.5f * level, time, color, d, line,
                new Paint(Paint.ANTI_ALIAS_FLAG));
    }

    /**
     * Картинка волны для виджета и шторки: на тёмной скруглённой подложке (в светлой шторке
     * белая середина иначе потеряется); card — цвет подложки, 0 — прозрачная.
     */
    static Bitmap bitmap(int wPx, int hPx, float[] half, float glow, float time, int color, float d, int card) {
        Bitmap b = Bitmap.createBitmap(Math.max(8, wPx), Math.max(8, hPx), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float pad = 0f;
        if (card != 0) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(card);
            c.drawRoundRect(new RectF(0, 0, wPx, hPx), 12 * d, 12 * d, p);
            pad = 8 * d;
        }
        paintSpikes(c, pad, 0, wPx - pad * 2, hPx, half, glow, time, color, d,
                new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG));
        return b;
    }

    /**
     * Всплески: half — уровни от центра (бас) к краю (верх), 0..1; glow — яркость центральной линии.
     * Соседние пики немного разные («мерцание» умножает настоящий уровень, тишина остаётся тишиной).
     */
    static void paintSpikes(Canvas c, float left, float top, float w, float h, float[] half, float glow,
                            float time, int color, float d, Paint spike, Paint core) {
        float mid = top + h / 2f;
        float maxAmp = h * 0.48f;
        float spacing = Math.max(3.2f * d, w / 140f);
        int n = (int) (w / spacing);
        if (n % 2 == 0) n--;
        n = Math.max(9, n);
        float x0 = left + (w - (n - 1) * spacing) / 2f;
        int c0 = n / 2;
        int light = mix(color, Color.WHITE, 0.55f);

        // светящаяся линия посередине: к краям гаснет
        int coreCol = mix(color, Color.WHITE, 0.75f);
        core.setStyle(Paint.Style.STROKE);
        core.setStrokeCap(Paint.Cap.ROUND);
        core.setShader(new LinearGradient(left, 0, left + w, 0,
                new int[]{coreCol & 0x00FFFFFF, coreCol, Color.WHITE, coreCol, coreCol & 0x00FFFFFF},
                new float[]{0f, 0.2f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        float g = Math.max(0f, Math.min(1f, glow));
        core.setStrokeWidth(7 * d);
        core.setAlpha((int) (35 + 80 * g));
        c.drawLine(left + 2 * d, mid, left + w - 2 * d, mid, core);
        core.setStrokeWidth(1.6f * d);
        core.setAlpha((int) (150 + 105 * g));
        c.drawLine(left + 2 * d, mid, left + w - 2 * d, mid, core);

        // пики: у середины белые, к концам — цвет волны (один градиент на все).
        // Три прохода: мягкое свечение, тонкая игла во всю высоту, толще у середины — пик «острый»
        spike.setStyle(Paint.Style.STROKE);
        spike.setStrokeCap(Paint.Cap.ROUND);
        spike.setShader(new LinearGradient(0, mid - maxAmp, 0, mid + maxAmp,
                new int[]{color, light, Color.WHITE, light, color},
                new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        for (int pass = 0; pass < 3; pass++) {
            spike.setStrokeWidth(pass == 0 ? 4.5f * d : pass == 1 ? 1.1f * d : 2.2f * d);
            for (int i = 0; i < n; i++) {
                float u = Math.abs(i - c0) / (float) c0;
                float v = sample(half, u);
                // соседние пики разные, как в настоящем звуке: «мерцание» умножает уровень (тишина — тишина)
                float sh = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(time * (3f + (i * 37 % 11) * 0.55f) + i * 1.7f));
                float env = (float) Math.pow(Math.max(0f, 1f - u * u), 0.9);
                float a = maxAmp * Math.min(1f, 1.6f * (float) Math.pow(v * sh, 1.25)) * env;
                if (a < 1.2f * d) continue;
                // короткие — только тонкой иглой (иначе свечение и толщина превращают их в точки)
                if (pass != 1 && a < 4f * d) continue;
                float down = a * (0.8f + 0.2f * (float) Math.sin(i * 2.3f + time * 3.1f));
                float x = x0 + i * spacing;
                if (pass == 2) {
                    a *= 0.42f;
                    down *= 0.42f;
                }
                spike.setAlpha(pass == 0 ? 32 : i % 3 == 1 ? 140 : 255);
                c.drawLine(x, mid - a, x, mid + down, spike);
            }
        }
        spike.setShader(null);
        core.setShader(null);
    }

    /** Уровень в точке u (0 — центр, 1 — край) между полосами. */
    private static float sample(float[] half, float u) {
        float p = u * (half.length - 1);
        int i = (int) p;
        if (i >= half.length - 1) return half[half.length - 1];
        float t = p - i;
        return half[i] + (half[i + 1] - half[i]) * t;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }
}
