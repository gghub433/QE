package lv.budseq;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
    private Drawable icon;
    private int iconFor;
    /** Подробные картинки: объём, блики, мелкие детали. */
    private final DeviceArt art;

    public DeviceView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        art = new DeviceArt(d);
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
                art.headphones(c, cx, cy, s, alpha, time, music, ACCENT);
                break;
            case DeviceInfo.T_SPEAKER:
                art.speaker(c, cx, cy, s, w, alpha, time, music, ACCENT);
                break;
            case DeviceInfo.T_CAR:
                art.car(c, cx, cy, s, w, alpha, time, music, ACCENT);
                break;
            case DeviceInfo.T_HEADSET:
                art.headset(c, cx, cy, s, alpha, time, ACCENT);
                break;
            case DeviceInfo.T_TV:
                art.tv(c, cx, cy, s, w, alpha, time, music, ACCENT);
                break;
            case DeviceInfo.T_WATCH: {
                // стрелки — текущее время
                Calendar now = Calendar.getInstance();
                float sec = now.get(Calendar.SECOND) + (System.currentTimeMillis() % 1000) / 1000f;
                float min = now.get(Calendar.MINUTE) + sec / 60f;
                float hour = now.get(Calendar.HOUR) + min / 60f;
                art.watch(c, cx, cy, s, alpha, dev.battery, hour, min, sec, ACCENT, GREEN, ORANGE, RED);
                break;
            }
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
        art.budsCase(c, cx, cy, s, samsung, a, time);

        // наушники парят над кейсом
        float bob1 = (float) Math.sin(time * 2.2f) * 4 * d;
        float bob2 = (float) Math.sin(time * 2.2f + 1.4f) * 4 * d;
        float bx = s * 0.21f, by = cy - s * 0.18f;
        if (samsung && !BudsView.isStemGalaxy(dev.name)) {
            art.bean(c, cx - bx, by + bob1, s, false, a);
            art.bean(c, cx + bx, by + bob2, s, true, a);
        } else {
            // AirPods / AirPods Pro / Buds3–Buds4; у прочих TWS — ножка с амбушюром, как у Pro
            int kind = samsung ? StemBud.BUDS3
                    : dev.isAirPods() ? StemBud.kindFor(dev.name, false) : StemBud.AIRPODS_PRO;
            float glow = 0.55f + 0.45f * (float) Math.sin(time * 2.4f);
            stemBud.draw(c, cx - bx, by + bob1, s * 0.29f, false, kind, a, 12f, glow);
            stemBud.draw(c, cx + bx, by + bob2, s * 0.29f, true, kind, a, 12f, glow);
        }
        drawWaves(c, cx - bx - s * 0.16f, by, s * 0.10f, true, a);
        drawWaves(c, cx + bx + s * 0.16f, by, s * 0.10f, false, a);
    }

    private final StemBud stemBud = new StemBud();

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
