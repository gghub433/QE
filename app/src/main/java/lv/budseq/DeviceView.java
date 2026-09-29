package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.view.View;

import java.util.Calendar;

/**
 * Анимированная картинка подключённого устройства по его типу:
 * наушники-вкладыши, полноразмерные наушники, колонка, машина, гарнитура,
 * телевизор, часы и т.д. + название и заряд.
 */
public class DeviceView extends View {

    private final int ACCENT = Theme.accent();
    private static final int GREEN = Color.rgb(0x4C, 0xD9, 0x64);
    private static final int ORANGE = Color.rgb(0xFF, 0xB3, 0x40);
    private static final int RED = Color.rgb(0xFF, 0x5A, 0x5A);
    private static final int BODY = Color.rgb(0x2B, 0x2D, 0x33);
    private static final int BODY_LIGHT = Color.rgb(0x3D, 0x40, 0x48);
    private static final int STROKE = Color.rgb(0x5E, 0x62, 0x6C);
    private static final int DARK = Color.rgb(0x15, 0x16, 0x1A);
    private static final int WHITE_PART = Color.rgb(0xEE, 0xEE, 0xF1);
    private static final int SHADE = Color.rgb(0xC4, 0xC6, 0xCC);
    private static final int GREY_TEXT = Color.rgb(0xA0, 0xA3, 0xAA);

    private DeviceInfo dev;
    private String shownAddr;
    private float appear = 1f, time, music;
    private boolean running, musicActive;
    private long lastMusicCheck;
    private final AudioManager am;
    private final float d;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private Drawable icon;
    private int iconFor;

    public DeviceView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        title.setColor(Color.WHITE);
        title.setTextSize(15 * d);
        title.setTextAlign(Paint.Align.CENTER);
        title.setFakeBoldText(true);
        big.setTextSize(22 * d);
        big.setTextAlign(Paint.Align.CENTER);
        big.setFakeBoldText(true);
        small.setTextSize(12 * d);
        small.setTextAlign(Paint.Align.CENTER);
    }

    public void setDevice(DeviceInfo info) {
        String addr = info == null ? null : info.address;
        boolean changed = addr == null ? shownAddr != null : !addr.equals(shownAddr);
        if (changed) appear = 0f;
        shownAddr = addr;
        dev = info;
        startLoop();
        invalidate();
    }

    public DeviceInfo device() { return dev; }

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
            time += 0.016f;
            appear += (1f - appear) * 0.10f;
            long now = System.currentTimeMillis();
            if (now - lastMusicCheck > 500) {
                lastMusicCheck = now;
                try {
                    musicActive = am != null && am.isMusicActive();
                } catch (Exception e) {
                    musicActive = false;
                }
            }
            music += ((musicActive ? 1f : 0f) - music) * 0.08f;
            invalidate();
            postOnAnimation(this);
        }
    };

    // =====================================================================

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float top = 30 * d, bottom = h - 78 * d;
        float cx = w / 2f, cy = (top + bottom) / 2f;
        float s = Math.min(bottom - top, w * 0.62f);

        if (dev == null) {
            drawEmpty(c, w, h, cx, cy, s);
            return;
        }

        title.setAlpha(255);
        c.drawText(ellipsize(dev.name, title, w - 32 * d), cx, 22 * d, title);

        c.save();
        float k = 0.85f + 0.15f * appear;
        c.scale(k, k, cx, cy);
        int alpha = Math.max(0, Math.min(255, (int) (255 * appear)));
        fill.setAlpha(255);
        switch (dev.type) {
            case DeviceInfo.T_GALAXY_BUDS:
                drawEarbuds(c, cx, cy, s, true, alpha);
                break;
            case DeviceInfo.T_EARBUDS:
                drawEarbuds(c, cx, cy, s, false, alpha);
                break;
            case DeviceInfo.T_HEADPHONES:
                drawHeadphones(c, cx, cy, s, alpha);
                break;
            case DeviceInfo.T_SPEAKER:
                drawSpeaker(c, cx, cy, s, w, alpha);
                break;
            case DeviceInfo.T_CAR:
                drawCar(c, cx, cy, s, w, alpha);
                break;
            case DeviceInfo.T_HEADSET:
                drawHeadset(c, cx, cy, s, alpha);
                break;
            case DeviceInfo.T_TV:
                drawTv(c, cx, cy, s, w, alpha);
                break;
            case DeviceInfo.T_WATCH:
                drawWatch(c, cx, cy, s, alpha);
                break;
            default:
                drawIconType(c, cx, cy, s, DeviceInfo.iconRes(dev.type), alpha);
                break;
        }
        c.restore();
        drawInfo(c, w, h, cx);
    }

    // ---------- низ: заряд и тип ----------

    private void drawInfo(Canvas c, float w, float h, float cx) {
        String type = getContext().getString(DeviceInfo.labelRes(dev.type));
        int bat = dev.battery;
        if (bat >= 0 && bat <= 100) {
            big.setColor(bat <= 15 ? RED : Color.WHITE);
            c.drawText(bat + "%", cx, h - 44 * d, big);
            float bw = 120 * d, bh = 6 * d, by = h - 36 * d;
            r.set(cx - bw / 2, by, cx + bw / 2, by + bh);
            fill.setColor(Color.rgb(0x3A, 0x3B, 0x40));
            c.drawRoundRect(r, bh, bh, fill);
            r.set(cx - bw / 2, by, cx - bw / 2 + bw * bat / 100f, by + bh);
            fill.setColor(bat <= 15 ? RED : bat <= 30 ? ORANGE : GREEN);
            c.drawRoundRect(r, bh, bh, fill);
            small.setColor(GREY_TEXT);
            c.drawText(type, cx, h - 12 * d, small);
        } else {
            small.setColor(Color.WHITE);
            small.setTextSize(14 * d);
            c.drawText(type, cx, h - 38 * d, small);
            small.setTextSize(12 * d);
            small.setColor(GREY_TEXT);
            c.drawText(getContext().getString(R.string.battery_unknown), cx, h - 16 * d, small);
        }
    }

    private void drawEmpty(Canvas c, float w, float h, float cx, float cy, float s) {
        float pulse = 1f + 0.05f * (float) Math.sin(time * 2.5f);
        fill.setColor(Color.rgb(0x22, 0x24, 0x2A));
        c.drawCircle(cx, cy, s * 0.36f * pulse, fill);
        stroke.setColor(Color.rgb(0x33, 0x36, 0x3E));
        stroke.setStrokeWidth(2 * d);
        c.drawCircle(cx, cy, s * 0.36f * pulse + 6 * d, stroke);
        drawIcon(c, R.drawable.ic_bluetooth, cx, cy, s * 0.36f, Color.rgb(0x6A, 0x6E, 0x78));
        small.setTextSize(15 * d);
        small.setColor(Color.WHITE);
        c.drawText(getContext().getString(R.string.no_devices), cx, h - 58 * d, small);
        small.setTextSize(12 * d);
        small.setColor(GREY_TEXT);
        drawWrapped(c, getContext().getString(R.string.no_devices_hint), cx, h - 26 * d, w - 40 * d, small);
    }

    // ---------- наушники-вкладыши в кейсе ----------

    private void drawEarbuds(Canvas c, float cx, float cy, float s, boolean samsung, int a) {
        int caseColor = samsung ? BODY : WHITE_PART;
        int caseShade = samsung ? Color.rgb(0x24, 0x26, 0x2C) : SHADE;
        float cw = s * 0.80f, ch = s * 0.36f;
        float caseTop = cy + s * 0.10f, caseBottom = caseTop + ch;

        // тень
        fill.setColor(Color.BLACK);
        fill.setAlpha(a / 3);
        r.set(cx - cw * 0.45f, caseBottom - 4 * d, cx + cw * 0.45f, caseBottom + 8 * d);
        c.drawOval(r, fill);

        // открытая крышка (внутренняя сторона, позади)
        fill.setColor(caseShade);
        fill.setAlpha(a);
        r.set(cx - cw / 2 + 3 * d, caseTop - ch * 0.62f, cx + cw / 2 - 3 * d, caseTop + ch * 0.25f);
        c.drawRoundRect(r, ch * 0.45f, ch * 0.45f, fill);
        stroke.setColor(samsung ? STROKE : SHADE);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(1.2f * d);
        c.drawRoundRect(r, ch * 0.45f, ch * 0.45f, stroke);

        // корпус
        fill.setColor(caseColor);
        fill.setAlpha(a);
        r.set(cx - cw / 2, caseTop, cx + cw / 2, caseBottom);
        c.drawRoundRect(r, ch * 0.5f, ch * 0.5f, fill);
        stroke.setColor(samsung ? STROKE : SHADE);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(1.2f * d);
        c.drawRoundRect(r, ch * 0.5f, ch * 0.5f, stroke);
        // гнёзда
        fill.setColor(samsung ? DARK : Color.rgb(0xB5, 0xB8, 0xBF));
        fill.setAlpha(a);
        r.set(cx - cw * 0.32f, caseTop + ch * 0.12f, cx + cw * 0.32f, caseTop + ch * 0.30f);
        c.drawRoundRect(r, ch * 0.1f, ch * 0.1f, fill);
        // светодиод
        fill.setColor(GREEN);
        fill.setAlpha((int) (a * (0.55f + 0.45f * (float) Math.sin(time * 3))));
        c.drawCircle(cx, caseTop + ch * 0.62f, 3.2f * d, fill);

        // наушники парят над кейсом
        float bob1 = (float) Math.sin(time * 2.2f) * 4 * d;
        float bob2 = (float) Math.sin(time * 2.2f + 1.4f) * 4 * d;
        float bx = s * 0.21f, by = cy - s * 0.18f;
        if (samsung && !BudsView.isStemGalaxy(dev.name)) {
            drawBean(c, cx - bx, by + bob1, s, false, a);
            drawBean(c, cx + bx, by + bob2, s, true, a);
        } else {
            drawStemBud(c, cx - bx, by + bob1, s, false, a);
            drawStemBud(c, cx + bx, by + bob2, s, true, a);
        }
        drawWaves(c, cx - bx - s * 0.16f, by, s * 0.10f, true, a);
        drawWaves(c, cx + bx + s * 0.16f, by, s * 0.10f, false, a);
    }

    /** Наушник «с ножкой» (как у большинства TWS). */
    private void drawStemBud(Canvas c, float x, float y, float s, boolean right, int a) {
        c.save();
        c.rotate(right ? -12 : 12, x, y);
        float sw = s * 0.06f, sl = s * 0.25f;
        fill.setColor(WHITE_PART);
        fill.setAlpha(a);
        r.set(x - sw / 2, y, x + sw / 2, y + sl);
        c.drawRoundRect(r, sw / 2, sw / 2, fill);
        fill.setColor(SHADE);
        fill.setAlpha(a);
        r.set(x - sw / 2, y + sl - sw * 0.8f, x + sw / 2, y + sl);
        c.drawRoundRect(r, sw / 2, sw / 2, fill);
        fill.setColor(WHITE_PART);
        fill.setAlpha(a);
        c.drawCircle(x, y, s * 0.09f, fill);
        fill.setColor(Color.rgb(0x55, 0x58, 0x60));
        fill.setAlpha(a);
        float gx = x + (right ? -s * 0.035f : s * 0.035f);
        r.set(gx - s * 0.035f, y - s * 0.05f, gx + s * 0.035f, y + s * 0.02f);
        c.drawOval(r, fill);
        c.restore();
    }

    /** Наушник-«фасолина» (Galaxy Buds). */
    private void drawBean(Canvas c, float x, float y, float s, boolean right, int a) {
        float bw = s * 0.21f, bh = bw * 0.82f;
        fill.setColor(WHITE_PART);
        fill.setAlpha(a);
        r.set(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2);
        c.drawOval(r, fill);
        fill.setColor(SHADE);
        fill.setAlpha(a);
        r.set(x - bw / 2 + bw * 0.08f, y, x + bw / 2 - bw * 0.08f, y + bh / 2);
        c.drawArc(r, 0, 180, false, fill);
        fill.setColor(Color.rgb(0x6A, 0x6C, 0x72));
        fill.setAlpha(a);
        c.drawCircle(x + (right ? bw * 0.12f : -bw * 0.12f), y - bh * 0.05f, bw * 0.17f, fill);
    }

    // ---------- полноразмерные наушники ----------

    private void drawHeadphones(Canvas c, float cx, float cy, float s, int a) {
        c.save();
        c.rotate((float) Math.sin(time * 1.3f) * 3f, cx, cy + s * 0.3f);
        float rx = s * 0.34f;
        float arcCy = cy - s * 0.08f;

        // оголовье
        r.set(cx - rx, arcCy - rx, cx + rx, arcCy + rx);
        stroke.setColor(BODY_LIGHT);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(s * 0.075f);
        c.drawArc(r, 192, 156, false, stroke);
        r.set(cx - rx + s * 0.035f, arcCy - rx + s * 0.035f, cx + rx - s * 0.035f, arcCy + rx - s * 0.035f);
        stroke.setColor(DARK);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(s * 0.03f);
        c.drawArc(r, 200, 140, false, stroke);

        // дужки к чашкам
        float endY = arcCy - rx * (float) Math.sin(Math.toRadians(12));
        float cupW = s * 0.19f, cupH = s * 0.36f, cupCy = cy + s * 0.16f;
        stroke.setColor(STROKE);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(s * 0.03f);
        c.drawLine(cx - rx * 0.98f, endY, cx - rx, cupCy - cupH / 2, stroke);
        c.drawLine(cx + rx * 0.98f, endY, cx + rx, cupCy - cupH / 2, stroke);

        for (int side = -1; side <= 1; side += 2) {
            float x = cx + side * rx;
            // амбушюра (внутрь)
            fill.setColor(DARK);
            fill.setAlpha(a);
            r.set(x - cupW / 2 - side * s * 0.04f, cupCy - cupH / 2 + s * 0.02f,
                    x + cupW / 2 - side * s * 0.04f, cupCy + cupH / 2 - s * 0.02f);
            if (r.left > r.right) {
                float t = r.left; r.left = r.right; r.right = t;
            }
            c.drawRoundRect(r, s * 0.08f, s * 0.08f, fill);
            // чашка
            fill.setColor(BODY);
            fill.setAlpha(a);
            r.set(x - cupW / 2 + side * s * 0.02f, cupCy - cupH / 2, x + cupW / 2 + side * s * 0.02f, cupCy + cupH / 2);
            c.drawRoundRect(r, s * 0.08f, s * 0.08f, fill);
            stroke.setColor(STROKE);
            stroke.setAlpha(a);
            stroke.setStrokeWidth(1.2f * d);
            c.drawRoundRect(r, s * 0.08f, s * 0.08f, stroke);
            // накладка
            stroke.setColor(BODY_LIGHT);
            stroke.setStrokeWidth(2 * d);
            c.drawCircle(r.centerX(), cupCy, cupW * 0.28f, stroke);
            drawWaves(c, r.centerX() + side * cupW * 0.9f, cupCy, s * 0.12f, side < 0, a);
        }
        // индикатор
        fill.setColor(ACCENT);
        fill.setAlpha((int) (a * (0.5f + 0.5f * (float) Math.sin(time * 3))));
        c.drawCircle(cx + rx + s * 0.02f, cupCy + cupH * 0.32f, 2.6f * d, fill);
        c.restore();
    }

    // ---------- колонка ----------

    private void drawSpeaker(Canvas c, float cx, float cy, float s, float w, int a) {
        float len = Math.min(s * 1.35f, w * 0.66f);
        float hh = s * 0.46f;
        float left = cx - len / 2, right = cx + len / 2;
        float top = cy - hh / 2 + s * 0.02f, bottom = top + hh;
        float beat = music * Math.abs((float) Math.sin(time * 8.5f));

        // звуковые волны по бокам
        for (int i = 0; i < 3; i++) {
            float ph = (time * 0.8f + i / 3f) % 1f;
            int wa = (int) (a * music * (1f - ph) * 0.8f);
            if (wa <= 2) continue;
            stroke.setColor(ACCENT);
            stroke.setAlpha(wa);
            stroke.setStrokeWidth(2.2f * d);
            float rad = hh * (0.35f + 0.55f * ph);
            r.set(left - rad, (top + bottom) / 2 - rad, left + rad * 0.2f, (top + bottom) / 2 + rad);
            c.drawArc(r, 120, 120, false, stroke);
            r.set(right - rad * 0.2f, (top + bottom) / 2 - rad, right + rad, (top + bottom) / 2 + rad);
            c.drawArc(r, -60, 120, false, stroke);
        }

        // тень
        fill.setColor(Color.BLACK);
        fill.setAlpha(a / 3);
        r.set(left + len * 0.08f, bottom - 2 * d, right - len * 0.08f, bottom + 10 * d);
        c.drawOval(r, fill);

        // корпус-«капсула» с тканью
        int fabric = Color.rgb(0x2A, 0x3C, 0x62);
        fill.setColor(fabric);
        fill.setAlpha(a);
        r.set(left, top, right, bottom);
        c.drawRoundRect(r, hh / 2, hh / 2, fill);
        c.save();
        path.reset();
        path.addRoundRect(r, hh / 2, hh / 2, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(Color.rgb(0x39, 0x4E, 0x7A));
        fill.setAlpha(a);
        float step = hh * 0.11f;
        for (float yy = top + step / 2; yy < bottom; yy += step) {
            for (float xx = left + step / 2; xx < right; xx += step) {
                c.drawCircle(xx, yy, hh * 0.017f, fill);
            }
        }
        c.restore();
        stroke.setColor(Color.rgb(0x4A, 0x60, 0x90));
        stroke.setAlpha(a);
        stroke.setStrokeWidth(1.2f * d);
        c.drawRoundRect(r, hh / 2, hh / 2, stroke);

        // пассивные излучатели на торцах (качаются в такт)
        for (int side = -1; side <= 1; side += 2) {
            float ex = side < 0 ? left + hh * 0.17f : right - hh * 0.17f;
            float scale = 1f + 0.09f * beat;
            float ew = hh * 0.26f * scale, eh = hh * 0.78f * scale;
            fill.setColor(DARK);
            fill.setAlpha(a);
            r.set(ex - ew / 2, (top + bottom) / 2 - eh / 2, ex + ew / 2, (top + bottom) / 2 + eh / 2);
            c.drawOval(r, fill);
            stroke.setColor(STROKE);
            stroke.setAlpha(a);
            stroke.setStrokeWidth(1.5f * d);
            r.set(ex - ew * 0.3f, (top + bottom) / 2 - eh * 0.3f, ex + ew * 0.3f, (top + bottom) / 2 + eh * 0.3f);
            c.drawOval(r, stroke);
        }

        // логотип-шильдик
        fill.setColor(ORANGE);
        fill.setAlpha(a);
        float bw = hh * 0.55f, bh = hh * 0.2f;
        r.set(cx - bw / 2, (top + bottom) / 2 - bh / 2, cx + bw / 2, (top + bottom) / 2 + bh / 2);
        c.drawRoundRect(r, bh / 2, bh / 2, fill);
        fill.setColor(Color.WHITE);
        fill.setAlpha(a);
        c.drawCircle(cx, (top + bottom) / 2, bh * 0.22f, fill);
    }

    // ---------- машина ----------

    private void drawCar(Canvas c, float cx, float cy, float s, float w, int a) {
        float len = Math.min(s * 1.5f, w * 0.78f);
        float base = cy + s * 0.24f;
        float bodyTop = base - s * 0.25f;
        int paint = Color.rgb(0x2F, 0x63, 0xD8);

        // дорога
        stroke.setColor(Color.rgb(0x33, 0x36, 0x3E));
        stroke.setAlpha(a);
        stroke.setStrokeWidth(2 * d);
        c.drawLine(cx - len * 0.62f, base + s * 0.11f, cx + len * 0.62f, base + s * 0.11f, stroke);
        float dash = (time * (40 + 160 * music) * d) % (s * 0.3f);
        stroke.setColor(Color.rgb(0x55, 0x58, 0x60));
        for (float x = cx - len * 0.62f - dash; x < cx + len * 0.62f; x += s * 0.3f) {
            float x0 = Math.max(x, cx - len * 0.62f);
            float x1 = Math.min(x + s * 0.12f, cx + len * 0.62f);
            if (x1 > x0) c.drawLine(x0, base + s * 0.17f, x1, base + s * 0.17f, stroke);
        }

        // кабина
        fill.setColor(paint);
        fill.setAlpha(a);
        path.reset();
        path.moveTo(cx - len * 0.34f, bodyTop + s * 0.02f);
        path.lineTo(cx - len * 0.20f, bodyTop - s * 0.19f);
        path.lineTo(cx + len * 0.12f, bodyTop - s * 0.19f);
        path.lineTo(cx + len * 0.30f, bodyTop + s * 0.02f);
        path.close();
        c.drawPath(path, fill);
        // стёкла
        fill.setColor(Color.rgb(0x1B, 0x23, 0x33));
        fill.setAlpha(a);
        path.reset();
        path.moveTo(cx - len * 0.29f, bodyTop + s * 0.005f);
        path.lineTo(cx - len * 0.185f, bodyTop - s * 0.155f);
        path.lineTo(cx + len * 0.105f, bodyTop - s * 0.155f);
        path.lineTo(cx + len * 0.245f, bodyTop + s * 0.005f);
        path.close();
        c.drawPath(path, fill);
        fill.setColor(paint);
        fill.setAlpha(a);
        r.set(cx - len * 0.035f, bodyTop - s * 0.17f, cx - len * 0.01f, bodyTop + s * 0.02f);
        c.drawRect(r, fill);

        // кузов
        r.set(cx - len / 2, bodyTop, cx + len / 2, base);
        c.drawRoundRect(r, s * 0.08f, s * 0.08f, fill);
        stroke.setColor(Color.rgb(0x5A, 0x8A, 0xF0));
        stroke.setAlpha(a);
        stroke.setStrokeWidth(1.2f * d);
        c.drawLine(cx - len * 0.40f, bodyTop + s * 0.1f, cx + len * 0.42f, bodyTop + s * 0.1f, stroke);
        // фары
        fill.setColor(Color.rgb(0xFF, 0xE0, 0x8A));
        fill.setAlpha(a);
        r.set(cx + len / 2 - s * 0.07f, bodyTop + s * 0.05f, cx + len / 2 - s * 0.01f, bodyTop + s * 0.1f);
        c.drawRoundRect(r, 3 * d, 3 * d, fill);
        fill.setColor(RED);
        fill.setAlpha(a);
        r.set(cx - len / 2 + s * 0.01f, bodyTop + s * 0.05f, cx - len / 2 + s * 0.05f, bodyTop + s * 0.1f);
        c.drawRoundRect(r, 3 * d, 3 * d, fill);

        // колёса
        float wr = s * 0.11f;
        float ang = time * (1.2f + 6f * music);
        for (int side = -1; side <= 1; side += 2) {
            float wx = cx + side * len * 0.30f;
            fill.setColor(Color.BLACK);
            fill.setAlpha(a);
            c.drawCircle(wx, base, wr + 2 * d, fill);
            fill.setColor(DARK);
            fill.setAlpha(a);
            c.drawCircle(wx, base, wr, fill);
            fill.setColor(Color.rgb(0x9A, 0x9E, 0xA8));
            fill.setAlpha(a);
            c.drawCircle(wx, base, wr * 0.55f, fill);
            stroke.setColor(Color.rgb(0x5A, 0x5E, 0x68));
            stroke.setAlpha(a);
            stroke.setStrokeWidth(1.6f * d);
            for (int i = 0; i < 5; i++) {
                double t = ang + i * Math.PI * 2 / 5;
                c.drawLine(wx, base, wx + (float) Math.cos(t) * wr * 0.5f, base + (float) Math.sin(t) * wr * 0.5f, stroke);
            }
        }
    }

    // ---------- моно-гарнитура ----------

    private void drawHeadset(Canvas c, float cx, float cy, float s, int a) {
        c.save();
        c.rotate((float) Math.sin(time * 1.5f) * 4f, cx, cy);
        // заушина
        stroke.setColor(BODY_LIGHT);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(s * 0.04f);
        r.set(cx - s * 0.05f, cy - s * 0.42f, cx + s * 0.35f, cy + s * 0.05f);
        c.drawArc(r, 160, 220, false, stroke);
        // корпус
        fill.setColor(BODY);
        fill.setAlpha(a);
        r.set(cx - s * 0.09f, cy - s * 0.22f, cx + s * 0.09f, cy + s * 0.26f);
        c.drawRoundRect(r, s * 0.09f, s * 0.09f, fill);
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1.2f * d);
        c.drawRoundRect(r, s * 0.09f, s * 0.09f, stroke);
        // динамик
        fill.setColor(DARK);
        fill.setAlpha(a);
        c.drawCircle(cx, cy - s * 0.11f, s * 0.06f, fill);
        // микрофон
        stroke.setColor(BODY_LIGHT);
        stroke.setStrokeWidth(s * 0.035f);
        c.drawLine(cx - s * 0.04f, cy + s * 0.22f, cx - s * 0.30f, cy + s * 0.40f, stroke);
        fill.setColor(BODY);
        fill.setAlpha(a);
        c.drawCircle(cx - s * 0.31f, cy + s * 0.41f, s * 0.035f, fill);
        // индикатор
        fill.setColor(ACCENT);
        fill.setAlpha((int) (a * (0.5f + 0.5f * (float) Math.sin(time * 4))));
        c.drawCircle(cx, cy + s * 0.12f, 2.8f * d, fill);
        c.restore();
    }

    // ---------- телевизор ----------

    private void drawTv(Canvas c, float cx, float cy, float s, float w, int a) {
        float tw = Math.min(s * 1.45f, w * 0.74f);
        float th = Math.min(tw * 0.56f, s * 0.8f);
        float left = cx - tw / 2, top = cy - th / 2 - s * 0.06f;
        // подставка
        fill.setColor(BODY_LIGHT);
        fill.setAlpha(a);
        path.reset();
        path.moveTo(cx - tw * 0.05f, top + th);
        path.lineTo(cx + tw * 0.05f, top + th);
        path.lineTo(cx + tw * 0.15f, top + th + s * 0.12f);
        path.lineTo(cx - tw * 0.15f, top + th + s * 0.12f);
        path.close();
        c.drawPath(path, fill);
        // рамка
        fill.setColor(DARK);
        fill.setAlpha(a);
        r.set(left, top, left + tw, top + th);
        c.drawRoundRect(r, 6 * d, 6 * d, fill);
        stroke.setColor(STROKE);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(1.2f * d);
        c.drawRoundRect(r, 6 * d, 6 * d, stroke);
        // экран
        float in = 5 * d;
        r.set(left + in, top + in, left + tw - in, top + th - in);
        fill.setColor(Color.rgb(0x1C, 0x2C, 0x52));
        fill.setAlpha(a);
        c.drawRoundRect(r, 3 * d, 3 * d, fill);
        c.save();
        c.clipRect(r);
        float band = ((time * 0.25f) % 1.6f - 0.3f) * tw;
        fill.setColor(Color.WHITE);
        fill.setAlpha(a / 10);
        path.reset();
        path.moveTo(left + band, top);
        path.lineTo(left + band + tw * 0.18f, top);
        path.lineTo(left + band - tw * 0.05f, top + th);
        path.lineTo(left + band - tw * 0.23f, top + th);
        path.close();
        c.drawPath(path, fill);
        // «эквалайзер» на экране, когда играет музыка
        int bars = 7;
        float bw = (r.width() * 0.5f) / bars;
        for (int i = 0; i < bars; i++) {
            float k = 0.25f + 0.75f * music * (0.5f + 0.5f * (float) Math.sin(time * 6 + i * 0.9f));
            float bh = r.height() * 0.55f * k;
            float x = cx - r.width() * 0.25f + i * bw;
            fill.setColor(ACCENT);
            fill.setAlpha(a);
            c.drawRect(x + bw * 0.15f, r.bottom - 8 * d - bh, x + bw * 0.85f, r.bottom - 8 * d, fill);
        }
        c.restore();
    }

    // ---------- часы ----------

    private void drawWatch(Canvas c, float cx, float cy, float s, int a) {
        // ремешки
        fill.setColor(BODY);
        fill.setAlpha(a);
        r.set(cx - s * 0.15f, cy - s * 0.47f, cx + s * 0.15f, cy - s * 0.18f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, fill);
        r.set(cx - s * 0.15f, cy + s * 0.18f, cx + s * 0.15f, cy + s * 0.47f);
        c.drawRoundRect(r, s * 0.06f, s * 0.06f, fill);
        // корпус
        fill.setColor(BODY_LIGHT);
        fill.setAlpha(a);
        c.drawCircle(cx, cy, s * 0.30f, fill);
        // заводная головка
        r.set(cx + s * 0.28f, cy - s * 0.05f, cx + s * 0.35f, cy + s * 0.05f);
        c.drawRoundRect(r, 3 * d, 3 * d, fill);
        // циферблат
        fill.setColor(DARK);
        fill.setAlpha(a);
        c.drawCircle(cx, cy, s * 0.255f, fill);
        // кольцо заряда
        float rr = s * 0.215f;
        r.set(cx - rr, cy - rr, cx + rr, cy + rr);
        stroke.setColor(Color.rgb(0x2A, 0x2C, 0x32));
        stroke.setAlpha(a);
        stroke.setStrokeWidth(s * 0.03f);
        c.drawArc(r, 0, 360, false, stroke);
        int bat = dev.battery;
        if (bat >= 0) {
            stroke.setColor(bat <= 15 ? RED : bat <= 30 ? ORANGE : GREEN);
            stroke.setAlpha(a);
            c.drawArc(r, -90, 360f * bat / 100f, false, stroke);
        }
        // стрелки — текущее время
        Calendar now = Calendar.getInstance();
        float sec = now.get(Calendar.SECOND) + (System.currentTimeMillis() % 1000) / 1000f;
        float min = now.get(Calendar.MINUTE) + sec / 60f;
        float hour = (now.get(Calendar.HOUR) + min / 60f);
        hand(c, cx, cy, hour / 12f, s * 0.10f, s * 0.022f, Color.WHITE, a);
        hand(c, cx, cy, min / 60f, s * 0.15f, s * 0.016f, Color.WHITE, a);
        hand(c, cx, cy, sec / 60f, s * 0.17f, s * 0.008f, ACCENT, a);
        fill.setColor(ACCENT);
        fill.setAlpha(a);
        c.drawCircle(cx, cy, s * 0.018f, fill);
    }

    private void hand(Canvas c, float cx, float cy, float turn, float len, float width, int color, int a) {
        double t = turn * Math.PI * 2 - Math.PI / 2;
        stroke.setColor(color);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(width);
        c.drawLine(cx, cy, cx + (float) Math.cos(t) * len, cy + (float) Math.sin(t) * len, stroke);
    }

    // ---------- прочие устройства: крупная иконка ----------

    private void drawIconType(Canvas c, float cx, float cy, float s, int res, int a) {
        float bob = (float) Math.sin(time * 2f) * 3 * d;
        fill.setColor(Color.rgb(0x22, 0x24, 0x2A));
        fill.setAlpha(a);
        c.drawCircle(cx, cy + bob, s * 0.42f, fill);
        stroke.setColor(Color.rgb(0x35, 0x38, 0x40));
        stroke.setAlpha(a);
        stroke.setStrokeWidth(2 * d);
        c.drawCircle(cx, cy + bob, s * 0.42f, stroke);
        drawIcon(c, res, cx, cy + bob, s * 0.48f, Color.rgb(0xD6, 0xD8, 0xDE));
    }

    // ---------- помощники ----------

    /** Дуги «звука» рядом с устройством, когда играет музыка. */
    private void drawWaves(Canvas c, float x, float y, float size, boolean leftSide, int a) {
        if (music < 0.05f) return;
        for (int i = 0; i < 2; i++) {
            float ph = (time * 0.9f + i * 0.5f) % 1f;
            int wa = (int) (a * music * (1f - ph));
            if (wa <= 2) continue;
            stroke.setColor(ACCENT);
            stroke.setAlpha(wa);
            stroke.setStrokeWidth(2 * d);
            float rad = size * (0.4f + 0.8f * ph);
            r.set(x - rad, y - rad, x + rad, y + rad);
            c.drawArc(r, leftSide ? 140 : -40, 80, false, stroke);
        }
    }

    private void drawIcon(Canvas c, int res, float cx, float cy, float size, int color) {
        if (icon == null || iconFor != res) {
            Drawable dr = getContext().getDrawable(res);
            if (dr == null) return;
            icon = dr.mutate();
            iconFor = res;
        }
        icon.setTint(color);
        icon.setBounds((int) (cx - size / 2), (int) (cy - size / 2), (int) (cx + size / 2), (int) (cy + size / 2));
        icon.draw(c);
    }

    private static String ellipsize(String s, Paint p, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        String e = "…";
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + e) > maxW) n--;
        return s.substring(0, n) + e;
    }

    /** Текст по центру, перенос максимум на 2 строки. */
    private void drawWrapped(Canvas c, String text, float cx, float y, float maxW, Paint p) {
        if (p.measureText(text) <= maxW) {
            c.drawText(text, cx, y, p);
            return;
        }
        String[] words = text.split(" ");
        StringBuilder l1 = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String next = l1.length() == 0 ? words[i] : l1 + " " + words[i];
            if (p.measureText(next) > maxW && l1.length() > 0) break;
            l1.setLength(0);
            l1.append(next);
            i++;
        }
        StringBuilder l2 = new StringBuilder();
        for (; i < words.length; i++) {
            if (l2.length() > 0) l2.append(' ');
            l2.append(words[i]);
        }
        float lh = p.getTextSize() * 1.3f;
        c.drawText(l1.toString(), cx, y - lh / 2, p);
        c.drawText(ellipsize(l2.toString(), p, maxW), cx, y + lh / 2, p);
    }
}
