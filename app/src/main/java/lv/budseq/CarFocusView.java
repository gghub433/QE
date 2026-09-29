package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Салон машины сверху: 6 точек фокуса звука (передний ряд и задний ряд, слева / по центру / справа)
 * и динамики там, где они реально стоят (их можно перетащить).
 * Телефон отдаёт в машину только стерео, поэтому фокус — это баланс Л/П, но посчитанный по физике:
 * в выбранной точке ближние динамики звучат громче (закон 1/r²), и EQ приглушает этот канал,
 * чтобы сцена выстроилась вокруг слушателя. Руль рисуется слева или справа.
 */
public class CarFocusView extends View {

    public interface Listener {
        /** point = 0..5 или -1 (фокус выключен). */
        void onFocusChanged(int point);

        /** В режиме расстановки перетащили динамик: новые координаты всех динамиков. */
        void onSpeakersChanged(float[] speakers);
    }

    /** 0 перед-лево, 1 перед-центр, 2 перед-право, 3 зад-лево, 4 зад-центр (весь салон), 5 зад-право. */
    public static final int POINTS = 6;
    public static final int MODE_SOFT = 0, MODE_NORMAL = 1, MODE_STRONG = 2;

    /** Сила поправки: доля от полной компенсации разницы громкости каналов. */
    private static final float[] STRENGTH = {0.4f, 0.7f, 1.0f};
    /** Точки фокуса (x, y относительно кузова: 0..1 слева направо и от носа к багажнику). */
    private static final float[] POINT_POS = {
            0.285f, 0.475f, 0.5f, 0.465f, 0.715f, 0.475f, 0.235f, 0.695f, 0.5f, 0.695f, 0.765f, 0.695f};
    /** Размер салона для расчёта расстояний, м. */
    private static final float CAR_W = 1.8f, CAR_L = 4.5f;

    /** Схемы динамиков: 2 спереди, 4 в дверях, 4 + твитеры, + сабвуфер, + центр на панели. */
    public static final float[][] LAYOUTS = {
            {0.035f, 0.47f, 0.965f, 0.47f},
            {0.035f, 0.47f, 0.965f, 0.47f, 0.035f, 0.66f, 0.965f, 0.66f},
            {0.035f, 0.47f, 0.965f, 0.47f, 0.035f, 0.66f, 0.965f, 0.66f, 0.12f, 0.302f, 0.88f, 0.302f},
            {0.035f, 0.47f, 0.965f, 0.47f, 0.035f, 0.66f, 0.965f, 0.66f, 0.12f, 0.302f, 0.88f, 0.302f, 0.5f, 0.9f},
            {0.035f, 0.47f, 0.965f, 0.47f, 0.035f, 0.66f, 0.965f, 0.66f, 0.12f, 0.302f, 0.88f, 0.302f, 0.5f, 0.315f},
    };
    public static final int DEFAULT_LAYOUT = 2;

    private final int ACCENT = Theme.accent();
    private static final int BODY = Color.rgb(0x2B, 0x2D, 0x33);
    private static final int BODY_EDGE = Color.rgb(0x5E, 0x62, 0x6C);
    private static final int FLOOR = Color.rgb(0x17, 0x18, 0x1C);
    private static final int GLASS = Color.rgb(0x1B, 0x23, 0x33);
    private static final int SEAT = Color.rgb(0x3A, 0x3D, 0x45);
    private static final int SEAT_BACK = Color.rgb(0x4A, 0x4E, 0x58);
    private static final int GREY_TEXT = Color.rgb(0xA0, 0xA3, 0xAA);

    /** Канал динамика: -1 левый, 1 правый, 0 оба (центр, сабвуфер — баланс на них не влияет). */
    static int side(float x) {
        return Math.abs(x - 0.5f) < 0.08f ? 0 : x < 0.5f ? -1 : 1;
    }

    /**
     * Баланс для точки фокуса (-1 … 1, &gt;0 — приглушить левый канал), посчитанный по динамикам:
     * уровень канала в точке = сумма 1/r² его динамиков; громкую сторону приглушаем на разницу в дБ
     * (с долей по режиму). swap — каналы в машине перепутаны, mono — магнитола в моно (баланс бесполезен).
     */
    public static float balanceFor(int point, int mode, float[] spk, boolean swap, boolean mono) {
        if (point < 0 || point >= POINTS || mono || spk == null) return 0f;
        float px = POINT_POS[point * 2], py = POINT_POS[point * 2 + 1];
        double eL = 0, eR = 0;
        for (int i = 0; i + 1 < spk.length; i += 2) {
            int s = side(spk[i]);
            if (s == 0) continue;
            double dx = (spk[i] - px) * CAR_W, dy = (spk[i + 1] - py) * CAR_L;
            double e = 1.0 / Math.max(0.09, dx * dx + dy * dy);   // не ближе 30 см
            if (s < 0) eL += e;
            else eR += e;
        }
        if (eL <= 0 || eR <= 0) return 0f;   // динамики только с одной стороны
        double db = 10 * Math.log10(eL / eR) * STRENGTH[Math.max(0, Math.min(STRENGTH.length - 1, mode))];
        float b = (float) Math.min(0.9, 1 - Math.pow(10, -Math.abs(db) / 20));
        float bal = db > 0 ? b : -b;
        return swap ? -bal : bal;
    }

    /** Разница громкости каналов в точке, дБ (для подписи). */
    public static float balanceDb(float bal) {
        return (float) (20 * Math.log10(Math.max(0.1, 1 - Math.abs(bal))));
    }

    /** Название точки с учётом того, с какой стороны руль. */
    public static int labelRes(int point, boolean rhd) {
        switch (point) {
            case 0: return rhd ? R.string.car_passenger : R.string.car_driver;
            case 1: return R.string.car_front;
            case 2: return rhd ? R.string.car_driver : R.string.car_passenger;
            case 3: return R.string.car_rear_left;
            case 4: return R.string.car_all;
            case 5: return R.string.car_rear_right;
            default: return R.string.off;
        }
    }

    /**
     * Применить фокус машины, если звук сейчас идёт в машину (основное звуковое устройство).
     * Фокус добавляется к балансу профиля и не сохраняется в нём —
     * после отключения машины баланс возвращается сам.
     */
    public static void applyFocus(Context c) {
        EqEngine eq = EqEngine.get(c);
        DeviceInfo p = DeviceMonitor.get().primaryAudio();
        float b = 0f;
        if (p != null && p.type == DeviceInfo.T_CAR) {
            DeviceSettings ds = DeviceSettings.get(c, p.address);
            b = balanceFor(ds.carFocus, ds.carMode, ds.speakers(), ds.carSwap, ds.carMono);
        }
        eq.setCarBalance(b);
    }

    private Listener listener;
    private int focus = -1;
    private boolean rhd;
    private int mode = MODE_NORMAL;
    private float time;
    private final float[] sel = new float[POINTS];
    private final float[] pts = new float[POINTS * 2];
    private float pointR;
    private int pressed = -1;
    private boolean running;
    /** Динамики (x, y парами, относительно кузова) и их расстановка пальцем. */
    private float[] spk = LAYOUTS[DEFAULT_LAYOUT].clone();
    private boolean editMode;
    private int dragging = -1;
    /** Громкость каналов после фокуса (для яркости «звука» от динамиков). */
    private float gainL = 1f, gainR = 1f;
    private float carLeft, carTop, carWpx, carHpx;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final float d;

    public CarFocusView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setState(int focusPoint, int focusMode, boolean rightHand) {
        focus = focusPoint;
        mode = focusMode;
        rhd = rightHand;
        startLoop();
        invalidate();
    }

    public void setSpeakers(float[] speakers) {
        if (dragging >= 0) return;
        spk = speakers.clone();
        invalidate();
    }

    public void setEditMode(boolean on) {
        editMode = on;
        invalidate();
    }

    /** Итоговый баланс машины: ближний канал рисуем тусклее. */
    public void setBalance(float bal) {
        gainL = bal > 0 ? 1f - bal : 1f;
        gainR = bal < 0 ? 1f + bal : 1f;
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
            if (!isShown()) {           // вкладка не видна — не тратим батарею
                postDelayed(this, 400);
                return;
            }
            step(0.016f);
            invalidate();
            postOnAnimation(this);
        }
    };

    /** Шаг анимации (вынесен отдельно, чтобы картинку можно было отрисовать вне Android). */
    void step(float dt) {
        time += dt;
        for (int i = 0; i < POINTS; i++) {
            float target = i == focus ? 1f : 0f;
            sel[i] += (target - sel[i]) * 0.12f;
        }
    }

    // =====================================================================
    // Рисование
    // =====================================================================

    @Override
    protected void onDraw(Canvas c) {
        draw(c, getWidth(), getHeight());
    }

    void draw(Canvas c, float w, float h) {
        float carH = h * 0.94f;
        float carW = Math.min(w * 0.58f, carH * 0.44f);
        float cx = w / 2f, top = (h - carH) / 2f, bottom = top + carH;
        float left = cx - carW / 2f, right = cx + carW / 2f;
        pointR = carW * 0.068f;
        carLeft = left;
        carTop = top;
        carWpx = carW;
        carHpx = carH;

        // точки фокуса: центры сидений
        float frontY = top + carH * 0.475f, rearY = top + carH * 0.695f;
        setPoint(0, cx - carW * 0.215f, frontY);
        setPoint(1, cx, frontY - carH * 0.01f);
        setPoint(2, cx + carW * 0.215f, frontY);
        setPoint(3, cx - carW * 0.265f, rearY);
        setPoint(4, cx, rearY);
        setPoint(5, cx + carW * 0.265f, rearY);

        // тень под машиной
        fill.setColor(Color.BLACK);
        fill.setAlpha(70);
        r.set(left - carW * 0.02f, top + carH * 0.015f, right + carW * 0.02f, bottom + carH * 0.01f);
        c.drawRoundRect(r, carW * 0.3f, carW * 0.3f, fill);

        // зеркала
        fill.setColor(BODY);
        fill.setAlpha(255);
        float mirY = top + carH * 0.285f;
        r.set(left - carW * 0.11f, mirY - carH * 0.012f, left + carW * 0.02f, mirY + carH * 0.022f);
        c.drawRoundRect(r, carW * 0.03f, carW * 0.03f, fill);
        r.set(right - carW * 0.02f, mirY - carH * 0.012f, right + carW * 0.11f, mirY + carH * 0.022f);
        c.drawRoundRect(r, carW * 0.03f, carW * 0.03f, fill);

        // кузов (нос сверху)
        path.reset();
        path.moveTo(cx, top);
        path.cubicTo(cx + carW * 0.40f, top, right, top + carH * 0.035f, right, top + carH * 0.15f);
        path.lineTo(right, bottom - carH * 0.12f);
        path.cubicTo(right, bottom - carH * 0.02f, cx + carW * 0.38f, bottom, cx, bottom);
        path.cubicTo(cx - carW * 0.38f, bottom, left, bottom - carH * 0.02f, left, bottom - carH * 0.12f);
        path.lineTo(left, top + carH * 0.15f);
        path.cubicTo(left, top + carH * 0.035f, cx - carW * 0.40f, top, cx, top);
        path.close();
        fill.setColor(BODY);
        c.drawPath(path, fill);
        stroke.setColor(BODY_EDGE);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(1.5f * d);
        c.drawPath(path, stroke);

        // линии капота
        stroke.setColor(Color.rgb(0x3A, 0x3D, 0x45));
        stroke.setStrokeWidth(1.2f * d);
        c.drawLine(cx - carW * 0.20f, top + carH * 0.05f, cx - carW * 0.25f, top + carH * 0.19f, stroke);
        c.drawLine(cx + carW * 0.20f, top + carH * 0.05f, cx + carW * 0.25f, top + carH * 0.19f, stroke);

        // фары
        fill.setColor(Color.rgb(0xFF, 0xE8, 0xA8));
        fill.setAlpha(200);
        r.set(left + carW * 0.07f, top + carH * 0.035f, left + carW * 0.25f, top + carH * 0.055f);
        c.drawRoundRect(r, carW * 0.02f, carW * 0.02f, fill);
        r.set(right - carW * 0.25f, top + carH * 0.035f, right - carW * 0.07f, top + carH * 0.055f);
        c.drawRoundRect(r, carW * 0.02f, carW * 0.02f, fill);
        // стоп-сигналы
        fill.setColor(Color.rgb(0xFF, 0x4A, 0x4A));
        fill.setAlpha(190);
        r.set(left + carW * 0.07f, bottom - carH * 0.035f, left + carW * 0.27f, bottom - carH * 0.02f);
        c.drawRoundRect(r, carW * 0.02f, carW * 0.02f, fill);
        r.set(right - carW * 0.27f, bottom - carH * 0.035f, right - carW * 0.07f, bottom - carH * 0.02f);
        c.drawRoundRect(r, carW * 0.02f, carW * 0.02f, fill);
        fill.setAlpha(255);

        // лобовое стекло
        fill.setColor(GLASS);
        path.reset();
        path.moveTo(left + carW * 0.13f, top + carH * 0.215f);
        path.quadTo(cx, top + carH * 0.185f, right - carW * 0.13f, top + carH * 0.215f);
        path.lineTo(right - carW * 0.07f, top + carH * 0.30f);
        path.lineTo(left + carW * 0.07f, top + carH * 0.30f);
        path.close();
        c.drawPath(path, fill);
        // заднее стекло
        path.reset();
        path.moveTo(left + carW * 0.08f, top + carH * 0.80f);
        path.lineTo(right - carW * 0.08f, top + carH * 0.80f);
        path.lineTo(right - carW * 0.15f, top + carH * 0.865f);
        path.quadTo(cx, top + carH * 0.885f, left + carW * 0.15f, top + carH * 0.865f);
        path.close();
        c.drawPath(path, fill);

        // пол салона
        fill.setColor(FLOOR);
        r.set(left + carW * 0.06f, top + carH * 0.30f, right - carW * 0.06f, top + carH * 0.80f);
        c.drawRoundRect(r, carW * 0.08f, carW * 0.08f, fill);

        // приборная панель
        fill.setColor(Color.rgb(0x24, 0x26, 0x2C));
        r.set(left + carW * 0.06f, top + carH * 0.30f, right - carW * 0.06f, top + carH * 0.36f);
        c.drawRoundRect(r, carW * 0.05f, carW * 0.05f, fill);
        // экран магнитолы — светится акцентом
        fill.setColor(ACCENT);
        fill.setAlpha(150 + (int) (60 * Math.sin(time * 2)));
        r.set(cx - carW * 0.08f, top + carH * 0.312f, cx + carW * 0.08f, top + carH * 0.338f);
        c.drawRoundRect(r, carW * 0.015f, carW * 0.015f, fill);
        fill.setAlpha(255);

        // центральный тоннель
        fill.setColor(Color.rgb(0x22, 0x24, 0x29));
        r.set(cx - carW * 0.06f, top + carH * 0.37f, cx + carW * 0.06f, top + carH * 0.59f);
        c.drawRoundRect(r, carW * 0.03f, carW * 0.03f, fill);
        // рычаг
        fill.setColor(Color.rgb(0x3A, 0x3D, 0x45));
        c.drawCircle(cx, top + carH * 0.43f, carW * 0.022f, fill);

        // передние кресла
        drawSeat(c, cx - carW * 0.215f, top + carH * 0.425f, carW * 0.26f, carH * 0.15f);
        drawSeat(c, cx + carW * 0.215f, top + carH * 0.425f, carW * 0.26f, carH * 0.15f);

        // руль перед водителем
        float wheelX = rhd ? cx + carW * 0.215f : cx - carW * 0.215f;
        float wheelY = top + carH * 0.382f;
        float wr = carW * 0.105f;
        stroke.setColor(Color.rgb(0x6A, 0x6E, 0x78));
        stroke.setStrokeWidth(carW * 0.028f);
        r.set(wheelX - wr, wheelY - wr * 0.42f, wheelX + wr, wheelY + wr * 0.42f);
        c.drawOval(r, stroke);
        stroke.setStrokeWidth(carW * 0.02f);
        c.drawLine(wheelX - wr * 0.9f, wheelY, wheelX + wr * 0.9f, wheelY, stroke);

        // задний диван
        float benchTop = top + carH * 0.635f;
        fill.setColor(SEAT);
        r.set(left + carW * 0.10f, benchTop, right - carW * 0.10f, benchTop + carH * 0.115f);
        c.drawRoundRect(r, carW * 0.06f, carW * 0.06f, fill);
        fill.setColor(SEAT_BACK);
        r.set(left + carW * 0.10f, benchTop + carH * 0.105f, right - carW * 0.10f, benchTop + carH * 0.15f);
        c.drawRoundRect(r, carW * 0.04f, carW * 0.04f, fill);
        stroke.setColor(FLOOR);
        stroke.setStrokeWidth(1.5f * d);
        float third = (carW * 0.80f) / 3f;
        c.drawLine(left + carW * 0.10f + third, benchTop + carH * 0.01f,
                left + carW * 0.10f + third, benchTop + carH * 0.14f, stroke);
        c.drawLine(left + carW * 0.10f + 2 * third, benchTop + carH * 0.01f,
                left + carW * 0.10f + 2 * third, benchTop + carH * 0.14f, stroke);

        // динамики — там, где они стоят в этой машине
        float[] abs = new float[spk.length];
        for (int i = 0; i + 1 < spk.length; i += 2) {
            abs[i] = left + spk[i] * carW;
            abs[i + 1] = top + spk[i + 1] * carH;
        }
        int fx = focusIndexForDraw();
        for (int i = 0; i + 1 < spk.length; i += 2) {
            int s = side(spk[i]);
            float size = s == 0 ? (spk[i + 1] > 0.8f ? 0.075f : 0.035f) : spk[i + 1] < 0.4f ? 0.028f : 0.045f;
            drawSpeaker(c, abs[i], abs[i + 1], carW * size);
            if (editMode) {
                stroke.setColor(ACCENT);
                stroke.setAlpha(i / 2 == dragging ? 255 : 170);
                stroke.setStrokeWidth(2 * d);
                c.drawCircle(abs[i], abs[i + 1], carW * size + 5 * d, stroke);
            }
        }

        // «звук» летит от динамиков к точке фокуса
        if (fx >= 0) {
            float px = pts[fx * 2], py = pts[fx * 2 + 1];
            float strength = sel[fx];
            stroke.setColor(ACCENT);
            stroke.setStrokeWidth(carW * 0.012f);
            for (int i = 0; i + 1 < abs.length; i += 2) {
                stroke.setAlpha((int) (45 * strength * level(spk[i])));
                c.drawLine(abs[i], abs[i + 1], px, py, stroke);
            }
            for (int i = 0; i + 1 < abs.length; i += 2) {
                float lv = level(spk[i]);
                for (int k = 0; k < 3; k++) {
                    float ph = (time * 0.7f + k / 3f + i * 0.07f) % 1f;
                    float x = abs[i] + (px - abs[i]) * ph;
                    float y = abs[i + 1] + (py - abs[i + 1]) * ph;
                    fill.setColor(ACCENT);
                    fill.setAlpha((int) (200 * strength * lv * (1f - ph) * Math.min(1f, ph * 4f)));
                    c.drawCircle(x, y, carW * 0.014f, fill);
                }
            }
            fill.setAlpha(255);
        }

        // точки фокуса
        for (int i = 0; i < POINTS; i++) {
            float x = pts[i * 2], y = pts[i * 2 + 1];
            float s = sel[i];
            // пульсирующие кольца у выбранной
            if (s > 0.02f) {
                for (int k = 0; k < 2; k++) {
                    float ph = (time * 0.8f + k * 0.5f) % 1f;
                    stroke.setColor(ACCENT);
                    stroke.setAlpha((int) (170 * s * (1f - ph)));
                    stroke.setStrokeWidth(2 * d);
                    c.drawCircle(x, y, pointR * (1.1f + 1.3f * ph), stroke);
                }
            }
            fill.setColor(i == pressed ? Color.rgb(0x44, 0x48, 0x52) : Color.rgb(0x26, 0x28, 0x2E));
            fill.setAlpha(230);
            c.drawCircle(x, y, pointR, fill);
            if (s > 0.01f) {
                fill.setColor(ACCENT);
                fill.setAlpha((int) (255 * s));
                c.drawCircle(x, y, pointR * (0.55f + 0.45f * s), fill);
            }
            stroke.setColor(s > 0.5f ? Color.WHITE : Color.rgb(0x8A, 0x8E, 0x98));
            stroke.setAlpha(255);
            stroke.setStrokeWidth(1.6f * d);
            c.drawCircle(x, y, pointR, stroke);
            fill.setColor(Color.WHITE);
            fill.setAlpha(s > 0.5f ? 255 : 150);
            c.drawCircle(x, y, pointR * 0.18f, fill);
        }
        fill.setAlpha(255);

        // подписи сторон
        text.setColor(GREY_TEXT);
        text.setTextSize(13 * d);
        float ly = top + carH * 0.56f + 5 * d;
        c.drawText("L", left - carW * 0.13f, ly, text);
        c.drawText("R", right + carW * 0.13f, ly, text);
    }

    private int focusIndexForDraw() {
        if (focus >= 0) return focus;
        // после выключения ещё немного показываем затухающий звук
        int best = -1;
        float max = 0.02f;
        for (int i = 0; i < POINTS; i++) {
            if (sel[i] > max) {
                max = sel[i];
                best = i;
            }
        }
        return best;
    }

    private void setPoint(int i, float x, float y) {
        pts[i * 2] = x;
        pts[i * 2 + 1] = y;
    }

    /** Яркость звука от динамика: его канал после баланса (центр и сабвуфер — всегда полный). */
    private float level(float x) {
        int s = side(x);
        return s < 0 ? 0.25f + 0.75f * gainL : s > 0 ? 0.25f + 0.75f * gainR : 1f;
    }

    private void drawSpeaker(Canvas c, float x, float y, float rad) {
        fill.setColor(Color.rgb(0x15, 0x16, 0x1A));
        c.drawCircle(x, y, rad, fill);
        stroke.setColor(Color.rgb(0x55, 0x58, 0x60));
        stroke.setAlpha(255);
        stroke.setStrokeWidth(1.2f * d);
        c.drawCircle(x, y, rad, stroke);
        fill.setColor(Color.rgb(0x44, 0x47, 0x50));
        c.drawCircle(x, y, rad * 0.4f, fill);
    }

    private void drawSeat(Canvas c, float x, float seatTop, float sw, float sh) {
        // подушка
        fill.setColor(SEAT);
        r.set(x - sw / 2, seatTop, x + sw / 2, seatTop + sh * 0.72f);
        c.drawRoundRect(r, sw * 0.22f, sw * 0.22f, fill);
        // спинка (машина смотрит вверх — спинка ниже подушки)
        fill.setColor(SEAT_BACK);
        r.set(x - sw * 0.52f, seatTop + sh * 0.66f, x + sw * 0.52f, seatTop + sh * 0.92f);
        c.drawRoundRect(r, sw * 0.14f, sw * 0.14f, fill);
        // подголовник
        r.set(x - sw * 0.24f, seatTop + sh * 0.9f, x + sw * 0.24f, seatTop + sh * 1.02f);
        c.drawRoundRect(r, sw * 0.08f, sw * 0.08f, fill);
    }

    // =====================================================================
    // Касания
    // =====================================================================

    private int pointAt(float x, float y) {
        int best = -1;
        float bestD = pointR * 2.2f;
        for (int i = 0; i < POINTS; i++) {
            float dx = x - pts[i * 2], dy = y - pts[i * 2 + 1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < bestD) {
                bestD = dist;
                best = i;
            }
        }
        return best;
    }

    private int speakerAt(float x, float y) {
        int best = -1;
        float bestD = carWpx * 0.14f + 12 * d;
        for (int i = 0; i + 1 < spk.length; i += 2) {
            float dx = x - (carLeft + spk[i] * carWpx), dy = y - (carTop + spk[i + 1] * carHpx);
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < bestD) {
                bestD = dist;
                best = i / 2;
            }
        }
        return best;
    }

    private boolean dragTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = speakerAt(e.getX(), e.getY());
                if (dragging >= 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return dragging >= 0;
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0 || carWpx <= 0) return false;
                spk[dragging * 2] = Math.max(0.02f, Math.min(0.98f, (e.getX() - carLeft) / carWpx));
                spk[dragging * 2 + 1] = Math.max(0.02f, Math.min(0.98f, (e.getY() - carTop) / carHpx));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging >= 0 && listener != null) listener.onSpeakersChanged(spk.clone());
                dragging = -1;
                invalidate();
                return true;
            default:
                return dragging >= 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editMode) return dragTouch(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = pointAt(e.getX(), e.getY());
                invalidate();
                return pressed >= 0;
            case MotionEvent.ACTION_UP: {
                int p = pointAt(e.getX(), e.getY());
                if (p >= 0 && p == pressed) {
                    focus = p == focus ? -1 : p;   // повторное нажатие — выключить фокус
                    if (listener != null) listener.onFocusChanged(focus);
                    performClick();
                }
                pressed = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
            default:
                return pressed >= 0;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
