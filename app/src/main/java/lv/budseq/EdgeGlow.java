package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * Подсветка краёв экрана от баса: тонкая светящаяся рамка по краю экрана поверх любых приложений,
 * вспыхивает на ударе бочки и плавно гаснет. Звук — AudioPulse (только бас 30–110 Гц).
 * Окно не принимает касаний и полупрозрачно (Android 12+ иначе блокирует нажатия под ним).
 * Показывает EqService, пока играет музыка и включён экран.
 */
final class EdgeGlow {

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("edge_glow", false);
    }

    /** Яркость 10…100 %. */
    static int brightness(Context c) {
        return prefs(c).getInt("edge_bright", 80);
    }

    /** Толщина 2…24 dp. */
    static int widthDp(Context c) {
        return prefs(c).getInt("edge_width", 8);
    }

    /** Можно ли показывать: разрешено «поверх других приложений» и есть доступ к звуку. */
    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c) && AudioPulse.allowed(c);
    }

    private final Context ctx;
    private final WindowManager wm;
    private GlowView view;
    private final Object key = new Object();

    EdgeGlow(Context c) {
        ctx = c.getApplicationContext();
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShown() {
        return view != null;
    }

    void show() {
        if (view != null || wm == null) return;
        GlowView v = new GlowView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        // Android 12+: нажатия проходят сквозь окно другого приложения, только если оно не плотнее 0,8
        lp.alpha = 0.8f;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) Api30.fullScreen(lp);
        lp.setTitle("EQ edge glow");
        try {
            wm.addView(v, lp);
            view = v;
            AudioPulse.get().acquire(ctx, key);
        } catch (Exception e) {
            view = null;
        }
    }

    void hide() {
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
        }
        view.running = false;
        view = null;
        AudioPulse.get().release(key);
    }

    /** Поменяли яркость или толщину — сразу видно. */
    void reload() {
        if (view != null) view.reload();
    }

    /** Показать вспышки 2,5 с без музыки — чтобы увидеть, как выглядит. */
    void demo() {
        if (view != null) view.demoUntil = SystemClock.uptimeMillis() + 2500;
    }

    private static final class Api30 {
        static void fullScreen(WindowManager.LayoutParams lp) {
            lp.setFitInsetsTypes(0);   // на весь экран, под строкой состояния и кнопками тоже
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
    }

    private static final class Api31 {
        /** Скругление углов экрана (если телефон его сообщает), px. */
        static float corner(WindowInsets in) {
            android.view.RoundedCorner rc = in.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
            return rc != null ? rc.getRadius() : -1f;
        }
    }

    /** Рамка: три обводки (мягкое свечение, ореол, яркая линия), цвет волны по кругу, медленно вращается. */
    private static final class GlowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Matrix m = new Matrix();
        private final float[] bars = new float[AudioPulse.BARS];
        private final float d;
        private float level, time, bright, width, corner;
        volatile boolean running = true;
        long demoUntil;
        private SweepGradient sweep;
        private int sweepColor, sweepW, sweepH;

        GlowView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            reload();
            postOnAnimation(tick);
        }

        void reload() {
            bright = Math.max(10, Math.min(100, brightness(getContext()))) / 100f;
            width = Math.max(2, Math.min(24, widthDp(getContext()))) * d;
            invalidate();
        }

        private final Runnable tick = new Runnable() {
            public void run() {
                if (!running) return;
                AudioPulse pulse = AudioPulse.get();
                float target;
                long now = SystemClock.uptimeMillis();
                if (now < demoUntil) {
                    // показ: удары два раза в секунду
                    double ph = (now % 500) / 500.0;
                    target = (float) Math.exp(-ph * 5);
                } else {
                    pulse.levels(bars);
                    // центр волны — самый низ (бочка): сильнее всего зависим от него
                    float max = 0f;
                    for (int i = 0; i < 14; i++) max = Math.max(max, bars[i]);
                    target = Math.min(1f, 0.65f * max + 0.35f * pulse.loudness());
                }
                // вспыхивает сразу, гаснет плавно (~0,15 с)
                level = Math.max(target, level * 0.88f);
                time += 0.016f;
                invalidate();
                if (level < 0.02f && now >= demoUntil) postDelayed(this, 120);   // тихо — реже, бережём батарею
                else postOnAnimation(this);
            }
        };

        @Override
        protected void onDraw(Canvas c) {
            if (level < 0.02f) return;
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (corner <= 0) corner = cornerRadius();
            int color = Theme.liveWave();
            if (color == 0) color = Theme.accent();
            if (sweep == null || color != sweepColor || w != sweepW || h != sweepH) {
                int light = mix(color, Color.WHITE, 0.55f);
                int accent = Theme.liveAccent();
                sweep = new SweepGradient(w / 2f, h / 2f,
                        new int[]{color, light, accent, color, light, color}, null);
                sweepColor = color;
                sweepW = w;
                sweepH = h;
            }
            m.setRotate(time * 40f, w / 2f, h / 2f);
            sweep.setLocalMatrix(m);
            p.setShader(sweep);
            float a = Math.min(1f, level) * bright;
            // мягкое свечение, ореол, яркая линия у самого края
            float[] widths = {width * 3.2f, width * 1.8f, width};
            float[] alphas = {0.16f, 0.34f, 0.95f};
            for (int i = 0; i < 3; i++) {
                float sw = widths[i] * (0.6f + 0.4f * level);
                p.setStrokeWidth(sw);
                p.setAlpha((int) (255 * alphas[i] * a));
                float in = sw / 2f;
                r.set(in, in, w - in, h - in);
                float rad = Math.max(0f, corner - in);
                c.drawRoundRect(r, rad, rad, p);
            }
            p.setShader(null);
        }

        private float cornerRadius() {
            if (Build.VERSION.SDK_INT >= 31) {
                WindowInsets in = getRootWindowInsets();
                if (in != null) {
                    float v = Api31.corner(in);
                    if (v > 0) return v;
                }
            }
            return 36 * d;   // обычное скругление экрана
        }

        private static int mix(int a, int b, float t) {
            int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
            int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
            int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
            return Color.rgb(r, g, bl);
        }
    }
}
