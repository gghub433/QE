package lv.budseq;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Наушник «с ножкой», нарисованный объёмно (градиенты, тень, амбушюр):
 *  - AIRPODS — длинная круглая ножка, решётка динамика на корпусе, серебристый микрофон на кончике;
 *  - AIRPODS_PRO — силиконовый амбушюр, короткая плоская ножка с сенсором;
 *  - BUDS3 — Galaxy Buds3/Buds4: ножка-«лезвие» из двух граней, вдоль ребра светится Blade Light.
 * Рисуется левый наушник в своих координатах (голова в 0,0, ножка вниз); правый — зеркально.
 * Общий для BudsView (кейс) и DeviceView (список устройств).
 */
final class StemBud {
    static final int AIRPODS = 0, AIRPODS_PRO = 1, BUDS3 = 2;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stem = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint face2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sensor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint metal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stemPath = new Path(), facePath = new Path(), bladePath = new Path();
    private final RectF r = new RectF();
    private float cachedU = -1;
    private int cachedKind = -1;

    StemBud() {
        edge.setStyle(Paint.Style.STROKE);
        light.setStyle(Paint.Style.STROKE);
        light.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Какая модель по названию устройства. */
    static int kindFor(String name, boolean galaxy) {
        if (galaxy) return BUDS3;
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("pro") ? AIRPODS_PRO : AIRPODS;
    }

    /** Длина ножки в долях u — чтобы вызывающий знал, сколько места нужно снизу. */
    static float stemLength(int kind) {
        return kind == AIRPODS ? 1.08f : kind == AIRPODS_PRO ? 0.78f : 0.92f;
    }

    /**
     * x, y — центр корпуса; u — размер (ширина корпуса ≈ 0.62u); tilt — наклон ножки наружу, °;
     * alpha 0…255; lightLevel 0…1 — яркость Blade Light (только BUDS3).
     */
    void draw(Canvas c, float x, float y, float u, boolean right, int kind, int alpha, float tilt, float lightLevel) {
        if (u != cachedU || kind != cachedKind) build(u, kind);
        setAlpha(alpha);
        c.save();
        c.translate(x, y);
        c.rotate(right ? -tilt : tilt);
        if (right) c.scale(-1f, 1f);
        float len = stemLength(kind) * u;

        // амбушюр позади корпуса (смотрит внутрь, к уху)
        if (kind != AIRPODS) {
            c.save();
            c.rotate(-18f, 0.27f * u, -0.06f * u);
            r.set(0.12f * u, -0.25f * u, 0.43f * u, 0.14f * u);
            c.drawOval(r, tip);
            c.drawOval(r, edge);
            r.set(0.28f * u, -0.15f * u, 0.40f * u, 0.03f * u);
            c.drawOval(r, dark);
            c.restore();
        }

        // ножка
        c.drawPath(stemPath, stem);
        if (kind == BUDS3) {
            c.drawPath(facePath, face2);                    // тёмная грань «лезвия»
        }
        c.drawPath(stemPath, edge);
        // мягкая тень от корпуса на ножке
        r.set(-0.16f * u, 0.12f * u, 0.12f * u, 0.36f * u);
        c.drawOval(r, shadow);

        if (kind == BUDS3) {
            // Blade Light вдоль ребра: свечение + яркая нить
            if (lightLevel > 0.01f) {
                int la = (int) (alpha * lightLevel);
                glow.setAlpha(la / 3);
                c.drawPath(bladePath, glow);
                light.setAlpha(la);
                c.drawPath(bladePath, light);
            }
            // микрофон — щель на торце
            r.set(-0.09f * u, len - 0.07f * u, 0.03f * u, len - 0.04f * u);
            c.drawRoundRect(r, 0.015f * u, 0.015f * u, dark);
        } else {
            if (kind == AIRPODS_PRO) {
                // сенсор нажатия — светлая плоская полоска
                r.set(-0.05f * u, 0.30f * u, 0.00f * u, 0.58f * u);
                c.drawRoundRect(r, 0.025f * u, 0.025f * u, metal);
            }
            // серебристый микрофон на кончике
            float cw = kind == AIRPODS ? 0.085f * u : 0.09f * u;
            float bx = -0.035f * u - (kind == AIRPODS ? 0.012f * u : 0.01f * u);
            r.set(bx - cw, len - 0.07f * u, bx + cw, len + 0.02f * u);
            c.save();
            c.clipPath(stemPath);
            c.drawRect(r, metal);
            c.restore();
            r.set(bx - 0.02f * u, len - 0.16f * u, bx + 0.02f * u, len - 0.12f * u);
            c.drawOval(r, dark);                            // отверстие микрофона
        }

        // корпус
        r.set(-0.31f * u, -0.25f * u, 0.31f * u, 0.25f * u);
        c.drawOval(r, body);
        c.drawOval(r, edge);
        if (kind == AIRPODS) {
            // решётка динамика на корпусе, смотрит к уху
            c.save();
            c.rotate(-22f, 0.15f * u, -0.07f * u);
            r.set(0.05f * u, -0.12f * u, 0.25f * u, -0.02f * u);
            c.drawOval(r, dark);
            c.restore();
        } else {
            // датчик приближения — маленькое тёмное окошко
            r.set(-0.115f * u, -0.125f * u, -0.075f * u, -0.085f * u);
            c.drawOval(r, sensor);
        }
        c.restore();
    }

    private void build(float u, int kind) {
        cachedU = u;
        cachedKind = kind;
        float len = stemLength(kind) * u;
        boolean blade = kind == BUDS3;
        int base = blade ? Color.rgb(0xDD, 0xE0, 0xE6) : Color.rgb(0xF6, 0xF6, 0xF8);
        int hi = Color.WHITE;
        int lo = blade ? Color.rgb(0x9C, 0xA1, 0xAB) : Color.rgb(0xC9, 0xCB, 0xD2);

        body.setShader(new RadialGradient(-0.10f * u, -0.12f * u, 0.42f * u,
                new int[]{hi, base, lo}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        // контур ножки: сверху шире, книзу чуть уже; у лезвия низ срезан, у AirPods — скруглён
        float topL = -0.15f * u, topR = 0.09f * u;
        float botL = blade ? -0.13f * u : -0.135f * u, botR = blade ? 0.05f * u : 0.055f * u;
        stemPath.reset();
        stemPath.moveTo(topL, 0f);
        stemPath.lineTo(botL, len - (blade ? 0.03f * u : 0.08f * u));
        if (blade) {
            stemPath.quadTo(botL, len, botL + 0.04f * u, len);
            stemPath.lineTo(botR - 0.03f * u, len);
            stemPath.quadTo(botR, len, botR, len - 0.03f * u);
        } else {
            float rad = (botR - botL) / 2f;
            stemPath.quadTo(botL, len + rad * 0.2f, (botL + botR) / 2f, len + rad * 0.2f);
            stemPath.quadTo(botR, len + rad * 0.2f, botR, len - 0.08f * u);
        }
        stemPath.lineTo(topR, 0f);
        stemPath.close();
        stem.setShader(new LinearGradient(topL, 0, topR, 0,
                new int[]{hi, base, lo}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        if (blade) {
            // правая (теневая) грань лезвия и ребро между гранями
            float ridgeTop = -0.03f * u, ridgeBot = -0.045f * u;
            facePath.reset();
            facePath.moveTo(ridgeTop, 0f);
            facePath.lineTo(ridgeBot, len);
            facePath.lineTo(botR - 0.03f * u, len);
            facePath.quadTo(botR, len, botR, len - 0.03f * u);
            facePath.lineTo(topR, 0f);
            facePath.close();
            face2.setShader(new LinearGradient(ridgeTop, 0, topR, 0,
                    Color.rgb(0xB4, 0xB9, 0xC2), Color.rgb(0x7E, 0x84, 0x8F), Shader.TileMode.CLAMP));
            bladePath.reset();
            bladePath.moveTo(ridgeTop - 0.002f * u, 0.30f * u);
            bladePath.lineTo(ridgeBot + 0.002f * u, len - 0.10f * u);
            light.setColor(Color.WHITE);
            light.setStrokeWidth(0.035f * u);
            glow.setColor(Color.rgb(0xCF, 0xE6, 0xFF));
            glow.setStrokeWidth(0.13f * u);
            tip.setColor(Color.rgb(0xB8, 0xBC, 0xC5));
        } else {
            tip.setColor(Color.rgb(0xEA, 0xEB, 0xEF));
        }
        dark.setColor(Color.rgb(0x2E, 0x30, 0x36));
        sensor.setColor(Color.rgb(0x6B, 0x70, 0x7A));
        metal.setColor(blade ? Color.rgb(0x6E, 0x73, 0x7D) : Color.rgb(0xB9, 0xBD, 0xC6));
        edge.setColor(Color.argb(90, 0x55, 0x59, 0x62));
        edge.setStrokeWidth(Math.max(1f, 0.012f * u));
        shadow.setShader(new RadialGradient(-0.02f * u, 0.22f * u, 0.16f * u,
                Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP));
    }

    private void setAlpha(int a) {
        body.setAlpha(a);
        stem.setAlpha(a);
        face2.setAlpha(a);
        tip.setAlpha(a);
        dark.setAlpha(a);
        sensor.setAlpha(a);
        metal.setAlpha(a);
        edge.setAlpha(a * 90 / 255);
        shadow.setAlpha(a);
    }
}
