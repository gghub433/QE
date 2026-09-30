package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Анимированный кейс с наушниками: крышка открывается,
 * наушники вылетают из кейса, когда их достают, и показывают заряд.
 * Стили: «фасолины» Galaxy Buds, белые AirPods «с ножками», Galaxy Buds3/Buds4 «с ножками»
 * и подсветкой Blade Light в кейсе с прозрачной крышкой.
 */
public class BudsView extends View {

    public static final int STYLE_BEAN = 0, STYLE_AIRPODS = 1, STYLE_BUDS3 = 2;

    private final int ACCENT = Theme.accent();
    private static final int GREEN = Color.rgb(0x4C, 0xD9, 0x64);
    private static final int ORANGE = Color.rgb(0xFF, 0xB3, 0x40);
    private static final int RED = Color.rgb(0xFF, 0x5A, 0x5A);

    private BudsLink.State state = new BudsLink.State();
    private int style = -1;

    // текущие (анимируемые) значения
    private float lid, outL, outR, alpha, wearL, wearR;
    private float time;
    private boolean running;

    private final Paint caseFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caseStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint budFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint budShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint led = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF lidRect = new RectF();
    private final RectF caseRect = new RectF();
    private final Paint lidInner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lidRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public BudsView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        caseStroke.setStyle(Paint.Style.STROKE);
        caseStroke.setStrokeWidth(1.5f * d);
        lidRim.setStyle(Paint.Style.STROKE);
        lidRim.setStrokeWidth(1.5f * d);
        setStyle(STYLE_BEAN);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        ring.setColor(GREEN);
        text.setColor(Color.WHITE);
        text.setTextSize(15 * d);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        small.setColor(Color.rgb(0xA0, 0xA3, 0xAA));
        small.setTextSize(11 * d);
        small.setTextAlign(Paint.Align.CENTER);
    }

    /** Внешний вид: STYLE_BEAN (Galaxy Buds) или STYLE_AIRPODS. */
    public void setStyle(int st) {
        if (st == style) return;
        style = st;
        if (st == STYLE_AIRPODS) {
            caseFill.setColor(Color.rgb(0xF2, 0xF2, 0xF4));
            caseStroke.setColor(Color.rgb(0xC4, 0xC6, 0xCC));
            inner.setColor(Color.rgb(0xCF, 0xD1, 0xD6));
            lidInner.setColor(Color.rgb(0xE3, 0xE4, 0xE8));
            lidRim.setColor(Color.rgb(0xD0, 0xD2, 0xD8));
            budFill.setColor(Color.rgb(0xFA, 0xFA, 0xFB));
            budShade.setColor(Color.rgb(0xB9, 0xBB, 0xC2));
            grill.setColor(Color.rgb(0x3A, 0x3C, 0x42));
        } else if (st == STYLE_BUDS3) {
            // Buds3/Buds4: тёмный кейс, серебристые наушники с ножками
            caseFill.setColor(Color.rgb(0x2B, 0x2D, 0x33));
            caseStroke.setColor(Color.rgb(0x5E, 0x62, 0x6C));
            inner.setColor(Color.rgb(0x14, 0x15, 0x18));
            lidInner.setColor(Color.rgb(0x3A, 0x3D, 0x46));
            lidRim.setColor(Color.rgb(0x55, 0x58, 0x62));
            budFill.setColor(Color.rgb(0xD9, 0xDC, 0xE2));
            budShade.setColor(Color.rgb(0xA4, 0xA8, 0xB2));
            grill.setColor(Color.rgb(0x3A, 0x3C, 0x42));
        } else {
            caseFill.setColor(Color.rgb(0x2B, 0x2D, 0x33));
            caseStroke.setColor(Color.rgb(0x55, 0x58, 0x60));
            inner.setColor(Color.rgb(0x14, 0x15, 0x18));
            lidInner.setColor(Color.rgb(0x20, 0x22, 0x27));
            lidRim.setColor(Color.rgb(0x33, 0x36, 0x3D));
            budFill.setColor(Color.rgb(0xEE, 0xEE, 0xF0));
            budShade.setColor(Color.rgb(0xC9, 0xCA, 0xCF));
            grill.setColor(Color.rgb(0x6A, 0x6C, 0x72));
        }
        invalidate();
    }

    /** Какой стиль рисовать для устройства. */
    public static int styleFor(DeviceInfo info) {
        if (info == null) return STYLE_BEAN;
        if (info.isAirPods()) return STYLE_AIRPODS;
        return isStemGalaxy(info.name) ? STYLE_BUDS3 : STYLE_BEAN;
    }

    /** Galaxy Buds3 / Buds3 Pro / Buds4 — «с ножками». */
    public static boolean isStemGalaxy(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        return n.contains("buds3") || n.contains("buds4");
    }

    public void setState(BudsLink.State s) {
        state = s;
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
            if (!running || !isAttachedToWindow()) { running = false; return; }
            step();
            invalidate();
            postOnAnimation(this);
        }
    };

    /** Шаг анимации (вынесен, чтобы картинку можно было отрисовать вне Android). */
    void step() {
        BudsLink.State s = state;
        float tLid = s.lidOpen() ? 1f : 0f;
        float tL = s.connected && !BudsLink.State.inCase(s.placeL) ? 1f : 0f;
        float tR = s.connected && !BudsLink.State.inCase(s.placeR) ? 1f : 0f;
        float tA = s.connected ? 1f : 0.45f;
        // крышка открывается первой, наушники вылетают после неё
        lid += (tLid - lid) * 0.12f;
        // из кейса наушник вылетает, когда крышка открылась; если оба снаружи
        // (крышку рисуем закрытой) или наушник уже снаружи — не держим его
        boolean free = lid > 0.6f || tLid == 0f;
        float gate = free || tL < outL || outL > 0.5f ? 1f : 0f;
        outL += (tL * gate - outL) * 0.10f;
        gate = free || tR < outR || outR > 0.5f ? 1f : 0f;
        outR += (tR * gate - outR) * 0.10f;
        alpha += (tA - alpha) * 0.1f;
        wearL += ((s.placeL == BudsLink.P_WEARING ? 1f : 0f) - wearL) * 0.15f;
        wearR += ((s.placeR == BudsLink.P_WEARING ? 1f : 0f) - wearR) * 0.15f;
        time += 0.016f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float caseW = Math.min(w * 0.32f, h * 0.46f);
        float caseH = caseW * 0.86f;
        float cy = h * 0.53f;
        float seam = cy - caseH * 0.06f;           // линия разъёма крышки
        float caseTop = cy - caseH / 2f, caseBottom = cy + caseH / 2f;
        float rad = caseW * 0.34f;
        float budW = caseW * 0.33f;
        float budH = budW * 0.82f;

        int a = (int) (255 * alpha);
        setAlphaAll(a);

        // Позиции наушников: в кейсе -> снаружи
        float inY = seam + budH * 0.08f;
        float outY = cy - caseH * 0.05f;
        float lx = lerp(cx - caseW * 0.21f, cx - caseW * 1.12f, outL);
        float rx = lerp(cx + caseW * 0.21f, cx + caseW * 1.12f, outR);
        float ly = lerp(inY, outY, outL) - (float) Math.sin(outL * Math.PI) * caseH * 0.55f
                + (float) Math.sin(time * 2.2f) * 3 * d * outL;
        float ry = lerp(inY, outY, outR) - (float) Math.sin(outR * Math.PI) * caseH * 0.55f
                + (float) Math.sin(time * 2.2f + 1.3f) * 3 * d * outR;

        // Крышка на петле СЗАДИ: при открытии поднимается и уходит назад.
        // Вид спереди: купол крышки сжимается и поднимается, за наушниками видна её внутренняя сторона.
        float theta = (float) Math.toRadians(lid * 105f);
        float cosT = (float) Math.cos(theta), sinT = (float) Math.sin(theta);
        float depth = caseH * 0.62f;          // глубина кейса — на столько поднимается край крышки
        float rise = depth * sinT;

        caseRect.set(cx - caseW / 2, caseTop, cx + caseW / 2, caseBottom);   // общий «камешек» кейса

        // 1) внутренняя сторона крышки — позади всего
        if (lid > 0.01f) {
            lidRect.set(cx - caseW / 2 + 1 * d, seam - rise, cx + caseW / 2 - 1 * d, seam + 2 * d);
            float lr = Math.min(rad, lidRect.height() / 2f);
            c.drawRoundRect(lidRect, lr, lr, lidInner);
            c.drawRoundRect(lidRect, lr, lr, caseStroke);
            lidRect.inset(5 * d, 5 * d);
            if (lidRect.height() > 4 * d) {
                float lr2 = Math.min(lr, lidRect.height() / 2f);
                c.drawRoundRect(lidRect, lr2, lr2, lidRim);
            }
            // углубления для наушников
            float pit = Math.min(1f, lid * 2f);
            inner.setAlpha((int) (a * pit));
            lidRect.set(cx - caseW / 2 + 8 * d, seam - 5 * d, cx + caseW / 2 - 8 * d, seam + 10 * d);
            c.drawRoundRect(lidRect, 8 * d, 8 * d, inner);
            inner.setAlpha(a);
        }

        // 2) наушники, которые ещё в кейсе (в закрытом кейсе их не видно — кроме прозрачной крышки Buds3)
        boolean glassLid = style == STYLE_BUDS3;
        if (outL < 0.5f && (lid > 0.02f || glassLid)) drawBud(c, lx, ly, budW, budH, false, wearL);
        if (outR < 0.5f && (lid > 0.02f || glassLid)) drawBud(c, rx, ry, budW, budH, true, wearR);

        // 3) нижняя часть корпуса (спереди)
        c.save();
        c.clipRect(cx - caseW, seam, cx + caseW, caseBottom + 4 * d);
        c.drawRoundRect(caseRect, rad, rad, caseFill);
        c.drawRoundRect(caseRect, rad, rad, caseStroke);
        c.restore();
        // светодиод
        led.setColor(!state.connected ? Color.rgb(0x55, 0x55, 0x55)
                : state.chgCase ? ORANGE : GREEN);
        led.setAlpha(state.connected ? (int) (a * (0.6f + 0.4f * (float) Math.sin(time * 3))) : a);
        c.drawCircle(cx, seam + (caseBottom - seam) * 0.40f, 3.5f * d, led);

        // 4) внешний купол крышки — пока крышка не перевалила за вертикаль
        if (cosT > 0.02f) {
            c.save();
            c.translate(0, -rise);
            c.scale(1f, cosT, 0, seam);
            c.clipRect(cx - caseW, caseTop - 4 * d, cx + caseW, seam);
            if (glassLid) {
                // затемнённое стекло: сквозь него видно наушники
                int fa = caseFill.getAlpha();
                caseFill.setAlpha(fa * 90 / 255);
                c.drawRoundRect(caseRect, rad, rad, caseFill);
                caseFill.setAlpha(fa);
            } else {
                c.drawRoundRect(caseRect, rad, rad, caseFill);
            }
            c.drawRoundRect(caseRect, rad, rad, caseStroke);
            c.restore();
        }
        if (lid < 0.02f) {
            c.drawLine(cx - caseW / 2 + rad * 0.3f, seam, cx + caseW / 2 - rad * 0.3f, seam, caseStroke);
        }

        // наушники снаружи — поверх
        if (outL >= 0.5f) drawBud(c, lx, ly, budW, budH, false, wearL);
        if (outR >= 0.5f) drawBud(c, rx, ry, budW, budH, true, wearR);

        // подписи заряда
        float labelY = h - 34 * d;
        drawBattery(c, cx - w * 0.32f, labelY, "L", state.batL, state.chgL, state.placeL);
        drawBattery(c, cx, labelY, getContext().getString(R.string.case_label), state.batCase, state.chgCase, -1);
        drawBattery(c, cx + w * 0.32f, labelY, "R", state.batR, state.chgR, state.placeR);

        if (!state.connected) {
            small.setAlpha(255);
            c.drawText(getContext().getString(R.string.buds_not_connected), cx, 16 * d, small);
        } else if (state.name != null && !state.name.isEmpty()) {
            small.setAlpha(255);
            c.drawText(state.name, cx, 16 * d, small);
        }
    }

    private void drawBud(Canvas c, float x, float y, float bw, float bh, boolean right, float wear) {
        if (style != STYLE_BEAN) {
            drawStemBud(c, x, y, bw, bh, right, wear);
            return;
        }
        // корпус "фасолина"
        r.set(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2);
        c.drawOval(r, budFill);
        r.set(x - bw / 2 + bw * 0.08f, y, x + bw / 2 - bw * 0.08f, y + bh / 2);
        c.drawArc(r, 0, 180, false, budShade);
        // сенсорная панель / сетка
        float gx = x + (right ? bw * 0.12f : -bw * 0.12f);
        c.drawCircle(gx, y - bh * 0.05f, bw * 0.17f, grill);
        if (wear > 0.02f) {
            ring.setAlpha((int) (255 * wear));
            c.drawCircle(x, y, bw * 0.62f + (1 - wear) * 10 * d, ring);
        }
    }

    /**
     * Наушник «с ножкой»: AirPods / AirPods Pro / Galaxy Buds3–Buds4 (объёмный, см. StemBud).
     * В кейсе ножку закрывает передняя стенка — видна только голова, как в жизни.
     */
    private void drawStemBud(Canvas c, float x, float y, float bw, float bh, boolean right, float wear) {
        int kind = style == STYLE_BUDS3 ? StemBud.BUDS3 : StemBud.kindFor(state.name, false);
        // Blade Light дышит, пока наушники на связи
        float light = state.connected ? 0.55f + 0.45f * (float) Math.sin(time * 2.4f + (right ? 1.3f : 0f)) : 0.25f;
        stemBud.draw(c, x, y, bw * 1.2f, right, kind, budFill.getAlpha(), 9f, light);
        if (wear > 0.02f) {
            ring.setAlpha((int) (255 * wear));
            c.drawCircle(x, y + bw * 0.35f, bw * 0.8f + (1 - wear) * 10 * d, ring);
        }
    }

    private final StemBud stemBud = new StemBud();

    private void drawBattery(Canvas c, float x, float y, String label, int bat, boolean chg, int place) {
        boolean known = state.connected && bat >= 0 && bat <= 100;
        String t = known ? bat + "%" : "—";
        text.setColor(!known ? Color.rgb(0x77, 0x77, 0x77) : bat <= 15 ? RED : Color.WHITE);
        if (known && chg) {
            // иконка-молния слева от процентов
            float tw = text.measureText(t);
            float size = 14 * d;
            float gap = 3 * d;
            float startX = x - (tw + size + gap) / 2f;
            drawBolt(c, startX, y - size * 0.85f, size);
            c.drawText(t, startX + size + gap + tw / 2f, y, text);
        } else {
            c.drawText(t, x, y, text);
        }

        // полоска заряда
        float bw = 44 * d, bh = 5 * d, by = y + 8 * d;
        r.set(x - bw / 2, by, x + bw / 2, by + bh);
        bar.setColor(Color.rgb(0x3A, 0x3B, 0x40));
        c.drawRoundRect(r, bh, bh, bar);
        if (known) {
            r.set(x - bw / 2, by, x - bw / 2 + bw * bat / 100f, by + bh);
            bar.setColor(chg ? ORANGE : bat <= 15 ? RED : bat <= 30 ? ORANGE : GREEN);
            c.drawRoundRect(r, bh, bh, bar);
        }

        String sub = label;
        if (state.connected && place >= 0) {
            if (place == BudsLink.P_WEARING) sub += " · " + getContext().getString(R.string.in_ear);
            else if (place == BudsLink.P_IDLE) sub += " · " + getContext().getString(R.string.taken_out);
            else if (BudsLink.State.inCase(place)) sub += " · " + getContext().getString(R.string.in_case);
        }
        small.setAlpha(255);
        c.drawText(sub, x, by + bh + 15 * d, small);
    }

    /** Молния (как иконка ic_bolt) в квадрате size×size. */
    private void drawBolt(Canvas c, float left, float top, float size) {
        float k = size / 24f;
        bolt.reset();
        bolt.moveTo(left + 7 * k, top + 2 * k);
        bolt.lineTo(left + 7 * k, top + 13 * k);
        bolt.lineTo(left + 10 * k, top + 13 * k);
        bolt.lineTo(left + 10 * k, top + 22 * k);
        bolt.lineTo(left + 17 * k, top + 10 * k);
        bolt.lineTo(left + 13 * k, top + 10 * k);
        bolt.lineTo(left + 17 * k, top + 2 * k);
        bolt.close();
        boltPaint.setColor(ORANGE);
        c.drawPath(bolt, boltPaint);
    }

    private void setAlphaAll(int a) {
        lidInner.setAlpha(a);
        lidRim.setAlpha(a);
        caseFill.setAlpha(a);
        caseStroke.setAlpha(a);
        inner.setAlpha(a);
        budFill.setAlpha(a);
        budShade.setAlpha(a);
        grill.setAlpha(a);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
