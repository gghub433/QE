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
 * Подробные картинки устройств: объём (градиенты), блики, мелкие детали — оголовье со строчкой и слайдерами,
 * чашки с амбушюрами и кнопками; колонка с тканью, резиновыми торцами, излучателями и кнопками; машина
 * с дверями, стёклами, фарами и колёсами с дисками; кейс и наушники Galaxy; часы, телевизор, гарнитура;
 * динамик машины (вид сверху).
 * Рисуем в «единицах»: канва переносится в центр картинки и масштабируется на её размер, поэтому градиенты
 * создаются один раз, а не на каждом кадре. Свет везде сверху слева.
 */
final class DeviceArt {
    private static final Shader.TileMode CLAMP = Shader.TileMode.CLAMP;

    private final float d;
    /** 1 dp в единицах текущей картинки. */
    private float u;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Path path2 = new Path();

    // наушники
    private final Shader hpBand, hpSlider, hpShell, hpCushion, hpPlate;
    // колонка
    private final Shader spFabric, spGloss, spCap, spRadiator, spBadge;
    // машина
    private final Shader carPaint, carGlass, carTyre, carRim, carHead, carTail, carBeam;
    // телевизор, часы, гарнитура
    private final Shader tvFrame, tvScreen, wCase, wStrap, hsBody, hsTip;
    // кейс и наушники Galaxy
    private final Shader caseWhite, caseDark, lidWhite, lidDark, beanBody, beanTouch, tipGel;
    // динамик машины
    private final Shader coneSurround, cone, coneCap;
    // тень под устройством
    private final Shader shadow;

    DeviceArt(float density) {
        d = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        hpBand = new LinearGradient(0, -0.40f, 0, -0.12f,
                new int[]{0xFF70757F, 0xFF3C3F46, 0xFF22242A}, new float[]{0f, 0.45f, 1f}, CLAMP);
        hpSlider = new LinearGradient(-0.013f, 0, 0.013f, 0,
                new int[]{0xFF6E737C, 0xFFE4E7EC, 0xFF9AA0A9, 0xFF5A5F68}, new float[]{0f, 0.35f, 0.6f, 1f}, CLAMP);
        hpShell = new RadialGradient(-0.045f, -0.085f, 0.24f,
                new int[]{0xFF60656F, 0xFF31343A, 0xFF17181B}, new float[]{0f, 0.5f, 1f}, CLAMP);
        hpCushion = new RadialGradient(0f, -0.02f, 0.17f,
                new int[]{0xFF2E3035, 0xFF1A1B1E, 0xFF0C0D0F}, new float[]{0f, 0.7f, 1f}, CLAMP);
        hpPlate = new LinearGradient(-0.06f, -0.11f, 0.06f, 0.11f, 0xFF4A4E57, 0xFF202227, CLAMP);

        spFabric = new LinearGradient(0, -0.23f, 0, 0.23f,
                new int[]{0xFF4C67A6, 0xFF2D4274, 0xFF1A2748}, new float[]{0f, 0.45f, 1f}, CLAMP);
        spGloss = new LinearGradient(0, -0.23f, 0, -0.02f, 0x38FFFFFF, 0x00FFFFFF, CLAMP);
        spCap = new LinearGradient(0, -0.23f, 0, 0.23f,
                new int[]{0xFF3A3C43, 0xFF1C1D21, 0xFF0E0F11}, new float[]{0f, 0.5f, 1f}, CLAMP);
        spRadiator = new RadialGradient(-0.12f, -0.16f, 0.62f,
                new int[]{0xFF5A5E67, 0xFF2A2C31, 0xFF141518}, new float[]{0f, 0.55f, 1f}, CLAMP);
        spBadge = new LinearGradient(0, -0.035f, 0, 0.035f, 0xFFFFC866, 0xFFFF8F1A, CLAMP);

        carPaint = new LinearGradient(0, -0.21f, 0, 0.21f,
                new int[]{0xFF79A3FF, 0xFF3E72E2, 0xFF2D58C4, 0xFF183274}, new float[]{0f, 0.3f, 0.62f, 1f}, CLAMP);
        carGlass = new LinearGradient(0, -0.2f, 0, -0.035f,
                new int[]{0xFF47587E, 0xFF1C2537, 0xFF0D121D}, new float[]{0f, 0.45f, 1f}, CLAMP);
        carTyre = new RadialGradient(0, 0, 0.11f,
                new int[]{0xFF0B0C0E, 0xFF1A1B1F, 0xFF34363C, 0xFF0D0E10}, new float[]{0f, 0.72f, 0.88f, 1f}, CLAMP);
        carRim = new RadialGradient(-0.025f, -0.03f, 0.09f,
                new int[]{0xFFF4F6F9, 0xFFB8BDC5, 0xFF757B85}, new float[]{0f, 0.5f, 1f}, CLAMP);
        carHead = new LinearGradient(0, -0.012f, 0, 0.035f, 0xFFFFFFFF, 0xFFFFD77A, CLAMP);
        carTail = new LinearGradient(0, -0.01f, 0, 0.04f, 0xFFFF6B6B, 0xFFB01E24, CLAMP);
        carBeam = new RadialGradient(0, 0, 0.5f, new int[]{0x80FFE9A8, 0x00FFE9A8}, null, CLAMP);

        tvFrame = new LinearGradient(0, -0.3f, 0, 0.3f, 0xFF3C3F46, 0xFF141518, CLAMP);
        tvScreen = new LinearGradient(0, -0.3f, 0, 0.3f,
                new int[]{0xFF2A4278, 0xFF15224C, 0xFF0A1230}, new float[]{0f, 0.5f, 1f}, CLAMP);
        wCase = new RadialGradient(-0.1f, -0.12f, 0.42f,
                new int[]{0xFFB4B9C2, 0xFF5B606A, 0xFF25272C}, new float[]{0f, 0.5f, 1f}, CLAMP);
        wStrap = new LinearGradient(-0.15f, 0, 0.15f, 0,
                new int[]{0xFF1E1F23, 0xFF3A3D44, 0xFF2A2C31, 0xFF17181B}, new float[]{0f, 0.3f, 0.7f, 1f}, CLAMP);
        hsBody = new LinearGradient(-0.09f, 0, 0.09f, 0,
                new int[]{0xFF54585F, 0xFF2E3036, 0xFF16171A}, new float[]{0f, 0.45f, 1f}, CLAMP);
        hsTip = new RadialGradient(-0.015f, -0.02f, 0.06f, 0xFFB7BBC3, 0xFF5E626B, CLAMP);

        caseWhite = new RadialGradient(-0.16f, 0.13f, 0.62f,
                new int[]{0xFFFFFFFF, 0xFFEDEEF1, 0xFFC6C8CE}, new float[]{0f, 0.45f, 1f}, CLAMP);
        caseDark = new RadialGradient(-0.16f, 0.13f, 0.62f,
                new int[]{0xFF50545C, 0xFF2A2C31, 0xFF141518}, new float[]{0f, 0.45f, 1f}, CLAMP);
        lidWhite = new LinearGradient(0, -0.13f, 0, 0.19f, 0xFFDADCE0, 0xFFB3B6BC, CLAMP);
        lidDark = new LinearGradient(0, -0.13f, 0, 0.19f, 0xFF2C2E33, 0xFF1A1B1E, CLAMP);
        beanBody = new RadialGradient(-0.035f, -0.04f, 0.14f,
                new int[]{0xFFFFFFFF, 0xFFEEEFF1, 0xFFBEC1C7}, new float[]{0f, 0.45f, 1f}, CLAMP);
        beanTouch = new LinearGradient(-0.07f, -0.06f, 0.07f, 0.06f, 0xFFFAFBFC, 0xFFD6D8DC, CLAMP);
        tipGel = new RadialGradient(-0.012f, -0.012f, 0.05f, 0xFFC9CCD2, 0xFF7E828B, CLAMP);

        coneSurround = new RadialGradient(0, 0, 0.95f,
                new int[]{0xFF1E1F23, 0xFF45484F, 0xFF1A1B1E}, new float[]{0.78f, 0.86f, 0.95f}, CLAMP);
        cone = new RadialGradient(0, 0, 0.8f,
                new int[]{0xFF141518, 0xFF2B2D33, 0xFF3A3D44}, new float[]{0.3f, 0.7f, 1f}, CLAMP);
        coneCap = new RadialGradient(-0.1f, -0.12f, 0.36f, 0xFF80858F, 0xFF25272C, CLAMP);

        shadow = new RadialGradient(0, 0, 0.5f, new int[]{0x8C000000, 0x00000000}, null, CLAMP);
    }

    // =====================================================================
    // Полноразмерные наушники
    // =====================================================================

    void headphones(Canvas c, float cx, float cy, float s, int a, float time, float music, int accent) {
        begin(c, cx, cy, s);
        c.rotate((float) Math.sin(time * 1.3f) * 3f, 0, 0.3f);
        shadow(c, 0, 0.42f, 0.86f, 0.07f, a);
        float R = 0.33f, acy = -0.05f, cupCy = 0.16f;
        float sx = R * (float) Math.cos(Math.toRadians(20));   // где кончается оголовье

        // слайдеры (металл с делениями) — под оголовьем
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.translate(side * sx, 0);
            sh(fill, hpSlider, a);
            r.set(-0.012f, -0.17f, 0.012f, -0.035f);
            c.drawRoundRect(r, 0.006f, 0.006f, fill);
            col(stroke, 0xFF4E535C, a);
            stroke.setStrokeWidth(0.003f);
            for (int i = 0; i < 4; i++) c.drawLine(-0.007f, -0.14f + i * 0.02f, 0.007f, -0.14f + i * 0.02f, stroke);
            c.restore();
        }

        // оголовье: внешняя дуга, блик сверху, мягкая подушка со строчкой
        r.set(-R, acy - R, R, acy + R);
        sh(stroke, hpBand, a);
        stroke.setStrokeWidth(0.07f);
        c.drawArc(r, 200, 140, false, stroke);
        col(stroke, 0x40FFFFFF, a);
        stroke.setStrokeWidth(0.008f);
        r.set(-R - 0.024f, acy - R - 0.024f, R + 0.024f, acy + R + 0.024f);
        c.drawArc(r, 222, 96, false, stroke);
        float ri = R - 0.042f;
        r.set(-ri, acy - ri, ri, acy + ri);
        col(stroke, 0xFF151619, a);
        stroke.setStrokeWidth(0.03f);
        c.drawArc(r, 207, 126, false, stroke);
        col(fill, 0xFF3E4148, a);
        for (int k = 212; k <= 328; k += 6) {
            double t = Math.toRadians(k);
            c.drawCircle((float) Math.cos(t) * ri, acy + (float) Math.sin(t) * ri, 0.0035f, fill);
        }
        // заглушки на концах оголовья, куда входят слайдеры
        for (int side = -1; side <= 1; side += 2) {
            col(fill, 0xFF2A2C31, a);
            r.set(side * sx - 0.024f, -0.205f, side * sx + 0.024f, -0.16f);
            c.drawRoundRect(r, 0.012f, 0.012f, fill);
            col(stroke, 0x30FFFFFF, a);
            stroke.setStrokeWidth(0.004f);
            c.drawLine(side * sx - 0.015f, -0.2f, side * sx + 0.015f, -0.2f, stroke);
        }

        for (int side = -1; side <= 1; side += 2) {
            float x = side * sx;
            // вилка (держатель чашки) с шарнирами
            r.set(x - 0.125f, cupCy - 0.205f, x + 0.125f, cupCy + 0.205f);
            col(stroke, 0xFF34373D, a);
            stroke.setStrokeWidth(0.016f);
            c.drawArc(r, 196, 148, false, stroke);
            col(stroke, 0x2EFFFFFF, a);
            stroke.setStrokeWidth(0.004f);
            c.drawArc(r, 220, 100, false, stroke);
            for (int e = 0; e < 2; e++) {
                double t = Math.toRadians(e == 0 ? 196 : 344);
                float px = x + 0.125f * (float) Math.cos(t), py = cupCy + 0.205f * (float) Math.sin(t);
                col(fill, 0xFF4B4F57, a);
                c.drawCircle(px, py, 0.013f, fill);
                col(fill, 0xFF1E1F23, a);
                c.drawCircle(px, py, 0.005f, fill);
            }

            c.save();
            c.translate(x, cupCy);
            // амбушюра (видна изнутри) со складками кожи
            c.save();
            c.translate(-side * 0.055f, 0);
            sh(fill, hpCushion, a);
            r.set(-0.08f, -0.156f, 0.08f, 0.156f);
            c.drawOval(r, fill);
            col(stroke, 0xFF3C3F46, a);
            stroke.setStrokeWidth(0.005f);
            c.drawArc(r, side < 0 ? -70 : 110, 140, false, stroke);
            col(stroke, 0xFF34363C, a);
            stroke.setStrokeWidth(0.004f);
            for (int i = -2; i <= 2; i++) {
                float yy = i * 0.05f;
                c.drawLine(-side * 0.072f, yy, -side * 0.056f, yy + 0.012f, stroke);
            }
            c.restore();
            // корпус чашки
            sh(fill, hpShell, a);
            r.set(-0.1f, -0.17f, 0.1f, 0.17f);
            c.drawOval(r, fill);
            col(stroke, 0x5A8A8F99, a);
            stroke.setStrokeWidth(0.005f);
            r.set(-0.094f, -0.164f, 0.094f, 0.164f);
            c.drawOval(r, stroke);
            // накладка с кольцом цвета темы и логотипом
            sh(fill, hpPlate, a);
            r.set(-0.062f, -0.115f, 0.062f, 0.115f);
            c.drawOval(r, fill);
            col(stroke, 0xFF4F535B, a);
            stroke.setStrokeWidth(0.003f);
            c.drawOval(r, stroke);
            col(stroke, accent, (int) (a * (0.45f + 0.45f * music)));
            stroke.setStrokeWidth(0.006f);
            r.set(-0.036f, -0.068f, 0.036f, 0.068f);
            c.drawOval(r, stroke);
            col(fill, 0xFF9297A1, a);
            c.drawCircle(0, 0, 0.012f, fill);
            // микрофоны внизу
            col(fill, 0xFF0B0C0E, a);
            c.drawCircle(-0.012f, 0.14f, 0.0045f, fill);
            c.drawCircle(0.012f, 0.14f, 0.0045f, fill);
            // блик
            col(fill, 0x30FFFFFF, a);
            r.set(-0.075f, -0.135f, -0.025f, -0.07f);
            c.drawOval(r, fill);
            if (side > 0) {
                // кнопки на правой чашке и индикатор
                for (int b = 0; b < 2; b++) {
                    col(fill, 0xFF25272C, a);
                    r.set(0.093f, 0.025f + b * 0.045f, 0.109f, 0.058f + b * 0.045f);
                    c.drawRoundRect(r, 0.008f, 0.008f, fill);
                    col(stroke, 0xFF555961, a);
                    stroke.setStrokeWidth(0.003f);
                    c.drawRoundRect(r, 0.008f, 0.008f, stroke);
                }
                col(fill, accent, (int) (a * (0.5f + 0.5f * (float) Math.sin(time * 3))));
                c.drawCircle(0.055f, 0.122f, 0.008f, fill);
            } else {
                // разъём USB-C внизу левой чашки
                col(fill, 0xFF08090A, a);
                r.set(-0.02f, 0.148f, 0.02f, 0.161f);
                c.drawRoundRect(r, 0.006f, 0.006f, fill);
                col(stroke, 0xFF5A5E66, a);
                stroke.setStrokeWidth(0.0025f);
                c.drawRoundRect(r, 0.006f, 0.006f, stroke);
            }
            c.restore();
            waves(c, x + side * 0.17f, cupCy, 0.12f, side < 0, a, time, music, accent);
        }
        c.restore();
    }

    // =====================================================================
    // Колонка
    // =====================================================================

    void speaker(Canvas c, float cx, float cy, float s, float w, int a, float time, float music, int accent) {
        begin(c, cx, cy, s);
        float len = Math.min(1.35f, w * 0.66f / s);
        float hh = 0.46f, mid = 0.02f;
        float beat = music * Math.abs((float) Math.sin(time * 8.5f));

        // звук из торцов
        for (int i = 0; i < 3; i++) {
            float ph = (time * 0.8f + i / 3f) % 1f;
            int wa = (int) (a * music * (1f - ph) * 0.8f);
            if (wa <= 2) continue;
            col(stroke, accent, wa);
            stroke.setStrokeWidth(2.2f * u);
            float rad = hh * (0.35f + 0.55f * ph);
            r.set(-len / 2 - rad, mid - rad, -len / 2 + rad * 0.2f, mid + rad);
            c.drawArc(r, 120, 120, false, stroke);
            r.set(len / 2 - rad * 0.2f, mid - rad, len / 2 + rad, mid + rad);
            c.drawArc(r, -60, 120, false, stroke);
        }
        shadow(c, 0, mid + hh / 2 + 0.02f, len * 0.9f, 0.07f, a);

        c.save();
        c.translate(0, mid);
        // кнопки сверху: «−», ▶, «+», Bluetooth, питание
        float top = -hh / 2;
        for (int i = 0; i < 5; i++) {
            float bx = -0.16f + i * 0.08f;
            col(fill, 0xFF17181B, a);
            r.set(bx - 0.028f, top - 0.014f, bx + 0.028f, top + 0.01f);
            c.drawRoundRect(r, 0.01f, 0.01f, fill);
        }

        // корпус-капсула: ткань с плетением, резиновые торцы, полоса-подставка
        path.reset();
        r.set(-len / 2, -hh / 2, len / 2, hh / 2);
        path.addRoundRect(r, hh / 2, hh / 2, Path.Direction.CW);
        sh(fill, spFabric, a);
        c.drawPath(path, fill);
        c.save();
        c.clipPath(path);
        stroke.setStrokeWidth(0.003f);
        col(stroke, 0x14FFFFFF, a);
        for (float k = -len / 2 - hh; k < len / 2; k += 0.02f) c.drawLine(k, -hh / 2, k + hh, hh / 2, stroke);
        col(stroke, 0x2A000000, a);
        for (float k = -len / 2 - hh; k < len / 2; k += 0.02f) c.drawLine(k, hh / 2, k + hh, -hh / 2, stroke);
        sh(fill, spGloss, a);
        c.drawRect(-len / 2, -hh / 2, len / 2, 0, fill);
        sh(fill, spCap, a);
        c.drawRect(-len / 2, -hh / 2, -len / 2 + 0.13f, hh / 2, fill);
        c.drawRect(len / 2 - 0.13f, -hh / 2, len / 2, hh / 2, fill);
        col(stroke, 0xFF08090A, a);
        stroke.setStrokeWidth(0.006f);
        c.drawLine(-len / 2 + 0.13f, -hh / 2, -len / 2 + 0.13f, hh / 2, stroke);
        c.drawLine(len / 2 - 0.13f, -hh / 2, len / 2 - 0.13f, hh / 2, stroke);
        col(fill, 0xFF111215, a);
        r.set(-len * 0.24f, hh / 2 - 0.024f, len * 0.24f, hh / 2 + 0.01f);
        c.drawRoundRect(r, 0.012f, 0.012f, fill);
        c.restore();
        col(stroke, 0x785B6E9A, a);
        stroke.setStrokeWidth(0.004f);
        c.drawPath(path, stroke);
        // блик вдоль верха
        col(stroke, 0x40FFFFFF, a);
        stroke.setStrokeWidth(0.006f);
        c.drawLine(-len / 2 + 0.17f, -hh / 2 + 0.03f, len / 2 - 0.17f, -hh / 2 + 0.03f, stroke);

        // значки на кнопках
        col(stroke, 0xD8FFFFFF, a);
        stroke.setStrokeWidth(0.0045f);
        float gy = top - 0.002f, g = 0.009f;
        c.drawLine(-0.16f - g, gy, -0.16f + g, gy, stroke);                       // −
        path2.reset();                                                             // ▶
        path2.moveTo(-0.086f, gy - g);
        path2.lineTo(-0.072f, gy);
        path2.lineTo(-0.086f, gy + g);
        path2.close();
        col(fill, 0xD8FFFFFF, a);
        c.drawPath(path2, fill);
        c.drawLine(-g, gy, g, gy, stroke);                                         // +
        c.drawLine(0, gy - g, 0, gy + g, stroke);
        c.drawLine(0.076f, gy - g, 0.084f, gy - 0.003f, stroke);                   // Bluetooth (руна)
        c.drawLine(0.084f, gy - 0.003f, 0.076f, gy + 0.004f, stroke);
        c.drawLine(0.08f, gy - g, 0.08f, gy + g, stroke);
        r.set(0.16f - g, gy - g + 0.002f, 0.16f + g, gy + g + 0.002f);           // питание
        c.drawArc(r, -50, 280, false, stroke);
        c.drawLine(0.16f, gy - g - 0.002f, 0.16f, gy, stroke);

        // пассивные излучатели на торцах — качаются в такт
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.translate(side * (len / 2 - 0.065f), 0);
            float k = 1f + 0.09f * beat;
            c.scale(k, k);
            col(fill, 0xFF0A0B0C, a);
            r.set(-0.056f, -0.172f, 0.056f, 0.172f);
            c.drawOval(r, fill);
            c.save();
            c.scale(0.1f, 0.31f);
            sh(fill, spRadiator, a);
            c.drawCircle(0, 0, 0.5f, fill);
            c.restore();
            col(stroke, 0xFF4A4D55, a);
            stroke.setStrokeWidth(0.003f);
            r.set(-0.033f, -0.1f, 0.033f, 0.1f);
            c.drawOval(r, stroke);
            col(fill, 0xFF62666F, a);
            r.set(-0.014f, -0.042f, 0.014f, 0.042f);
            c.drawOval(r, fill);
            c.restore();
        }

        // шильдик спереди
        sh(fill, spBadge, a);
        r.set(-0.11f, -0.035f, 0.11f, 0.035f);
        c.drawRoundRect(r, 0.035f, 0.035f, fill);
        col(fill, 0x50FFFFFF, a);
        r.set(-0.1f, -0.031f, 0.1f, -0.004f);
        c.drawRoundRect(r, 0.02f, 0.02f, fill);
        col(fill, Color.WHITE, a);
        c.drawCircle(-0.055f, 0, 0.013f, fill);
        r.set(-0.03f, -0.0065f, 0.075f, 0.0065f);
        c.drawRoundRect(r, 0.0065f, 0.0065f, fill);
        c.restore();
        c.restore();
    }

    // =====================================================================
    // Машина (вид сбоку)
    // =====================================================================

    void car(Canvas c, float cx, float cy, float s, float w, int a, float time, float music, int accent) {
        begin(c, cx, cy, s);
        float L = Math.min(1.5f, w * 0.78f / s);
        float base = 0.24f, road = 0.35f;

        // дорога с разметкой
        col(stroke, 0xFF33363E, a);
        stroke.setStrokeWidth(2 * u);
        c.drawLine(-L * 0.62f, road, L * 0.62f, road, stroke);
        float gap = 0.3f, dash = (time * (0.28f + 1.1f * music)) % gap;
        col(stroke, 0xFF55585F, a);
        for (float x = -L * 0.62f - dash; x < L * 0.62f; x += gap) {
            float x0 = Math.max(x, -L * 0.62f), x1 = Math.min(x + 0.12f, L * 0.62f);
            if (x1 > x0) c.drawLine(x0, road + 0.06f, x1, road + 0.06f, stroke);
        }
        shadow(c, 0, road - 0.005f, L * 0.98f, 0.05f, a);

        // свет фар, когда играет музыка
        if (music > 0.05f) {
            c.save();
            c.translate(L * 0.6f, 0.03f);
            c.scale(0.5f, 0.16f);
            sh(fill, carBeam, (int) (a * music));
            c.drawCircle(0, 0, 0.5f, fill);
            c.restore();
        }

        // кузов
        path.reset();
        path.moveTo(-0.5f * L, 0.19f);
        path.lineTo(-0.5f * L, 0.07f);
        path.cubicTo(-0.5f * L, 0.01f, -0.485f * L, -0.018f, -0.43f * L, -0.026f);
        path.lineTo(-0.34f * L, -0.036f);
        path.cubicTo(-0.28f * L, -0.06f, -0.22f * L, -0.18f, -0.15f * L, -0.198f);
        path.lineTo(0.06f * L, -0.204f);
        path.cubicTo(0.12f * L, -0.2f, 0.2f * L, -0.08f, 0.26f * L, -0.047f);
        path.cubicTo(0.34f * L, -0.032f, 0.44f * L, -0.026f, 0.478f * L, -0.006f);
        path.cubicTo(0.503f * L, 0.008f, 0.505f * L, 0.07f, 0.5f * L, 0.12f);
        path.lineTo(0.492f * L, 0.19f);
        path.cubicTo(0.47f * L, 0.205f, -0.47f * L, 0.205f, -0.5f * L, 0.19f);
        path.close();
        sh(fill, carPaint, a);
        c.drawPath(path, fill);

        c.save();
        c.clipPath(path);
        // нижняя накладка (пороги, бамперы)
        col(fill, 0xFF15171C, a);
        c.drawRect(-0.52f * L, 0.152f, 0.52f * L, 0.21f, fill);
        // линии кузова: плечо (блик) и нижняя грань
        col(stroke, 0x55FFFFFF, a);
        stroke.setStrokeWidth(0.006f);
        c.drawLine(-0.44f * L, 0.008f, 0.45f * L, -0.002f, stroke);
        col(stroke, 0x40000000, a);
        stroke.setStrokeWidth(0.005f);
        c.drawLine(-0.42f * L, 0.105f, 0.44f * L, 0.1f, stroke);
        // двери: швы, ручки, лючок бака
        col(stroke, 0xB00E1730, a);
        stroke.setStrokeWidth(0.004f);
        c.drawLine(-0.065f * L, -0.04f, -0.07f * L, 0.152f, stroke);
        c.drawLine(0.215f * L, -0.045f, 0.225f * L, 0.152f, stroke);
        c.drawLine(-0.3f * L, -0.04f, -0.315f * L, 0.152f, stroke);
        for (int i = -1; i <= 1; i += 2) {
            float hx = i < 0 ? -0.16f * L : 0.12f * L;
            col(fill, 0xFFC9CED6, a);
            r.set(hx - 0.028f, 0.018f, hx + 0.028f, 0.03f);
            c.drawRoundRect(r, 0.006f, 0.006f, fill);
            col(fill, 0x50000000, a);
            r.set(hx - 0.026f, 0.03f, hx + 0.026f, 0.034f);
            c.drawRoundRect(r, 0.002f, 0.002f, fill);
        }
        col(stroke, 0x900E1730, a);
        stroke.setStrokeWidth(0.004f);
        c.drawCircle(-0.4f * L, 0.03f, 0.016f, stroke);
        // ниши колёс
        col(fill, 0xFF0A0B0D, a);
        c.drawCircle(-0.31f * L, base, 0.136f, fill);
        c.drawCircle(0.31f * L, base, 0.136f, fill);
        c.restore();
        col(stroke, 0x50000000, a);
        stroke.setStrokeWidth(0.004f);
        c.drawPath(path, stroke);

        // стёкла с хромом, стойкой и отражением
        path2.reset();
        path2.moveTo(-0.315f * L, -0.04f);
        path2.cubicTo(-0.26f * L, -0.07f, -0.21f * L, -0.166f, -0.145f * L, -0.183f);
        path2.lineTo(0.05f * L, -0.188f);
        path2.cubicTo(0.1f * L, -0.182f, 0.165f * L, -0.09f, 0.222f * L, -0.05f);
        path2.close();
        sh(fill, carGlass, a);
        c.drawPath(path2, fill);
        c.save();
        c.clipPath(path2);
        col(fill, 0x22FFFFFF, a);
        path.reset();
        path.moveTo(-0.01f * L, -0.2f);
        path.lineTo(0.06f * L, -0.2f);
        path.lineTo(0.0f * L, -0.03f);
        path.lineTo(-0.07f * L, -0.03f);
        path.close();
        c.drawPath(path, fill);
        col(fill, 0xFF101216, a);
        c.drawRect(-0.07f * L, -0.2f, -0.045f * L, -0.03f, fill);
        c.restore();
        col(stroke, 0x88C9CED6, a);
        stroke.setStrokeWidth(0.004f);
        c.drawPath(path2, stroke);
        // акулий плавник антенны
        path.reset();
        path.moveTo(-0.135f * L, -0.199f);
        path.cubicTo(-0.12f * L, -0.215f, -0.105f * L, -0.228f, -0.09f * L, -0.229f);
        path.lineTo(-0.08f * L, -0.202f);
        path.close();
        col(fill, 0xFF15171C, a);
        c.drawPath(path, fill);
        // зеркало
        path.reset();
        path.moveTo(0.205f * L, -0.045f);
        path.cubicTo(0.205f * L, -0.075f, 0.25f * L, -0.078f, 0.258f * L, -0.06f);
        path.lineTo(0.25f * L, -0.04f);
        path.close();
        col(fill, 0xFF14161B, a);
        c.drawPath(path, fill);
        col(stroke, 0x30FFFFFF, a);
        stroke.setStrokeWidth(0.003f);
        c.drawLine(0.215f * L, -0.066f, 0.245f * L, -0.068f, stroke);
        // фара, решётка, фонарь
        path.reset();
        path.moveTo(0.452f * L, -0.012f);
        path.cubicTo(0.48f * L, -0.008f, 0.498f * L, 0.0f, 0.502f * L, 0.012f);
        path.lineTo(0.5f * L, 0.035f);
        path.cubicTo(0.48f * L, 0.034f, 0.465f * L, 0.02f, 0.452f * L, -0.012f);
        path.close();
        sh(fill, carHead, a);
        c.drawPath(path, fill);
        col(stroke, accent, (int) (a * (0.6f + 0.4f * music)));
        stroke.setStrokeWidth(0.004f);
        c.drawLine(0.458f * L, -0.004f, 0.496f * L, 0.008f, stroke);   // ходовые огни
        col(fill, 0xFF0C0D10, a);
        for (int i = 0; i < 3; i++) {
            r.set(0.47f * L, 0.112f + i * 0.016f, 0.501f * L, 0.12f + i * 0.016f);
            c.drawRoundRect(r, 0.004f, 0.004f, fill);
        }
        sh(fill, carTail, a);
        r.set(-0.497f * L, -0.01f, -0.468f * L, 0.04f);
        c.drawRoundRect(r, 0.01f, 0.01f, fill);
        col(fill, 0x40FFFFFF, a);
        r.set(-0.492f * L, -0.004f, -0.474f * L, 0.005f);
        c.drawRoundRect(r, 0.004f, 0.004f, fill);

        // колёса: шина, тормозной диск с суппортом, вращающиеся спицы, гайки
        float ang = time * (1.2f + 6f * music) * 57.3f;
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.translate(side * 0.31f * L, base);
            sh(fill, carTyre, a);
            c.drawCircle(0, 0, 0.11f, fill);
            col(stroke, 0xFF2C2E33, a);
            stroke.setStrokeWidth(0.003f);
            c.drawCircle(0, 0, 0.097f, stroke);
            col(fill, 0xFF4E5258, a);
            c.drawCircle(0, 0, 0.07f, fill);
            col(stroke, 0xFF3A3D42, a);
            stroke.setStrokeWidth(0.003f);
            c.drawCircle(0, 0, 0.05f, stroke);
            c.save();
            c.rotate(-40);
            col(fill, 0xFFD43B3B, a);
            r.set(-0.016f, -0.073f, 0.016f, -0.045f);
            c.drawRoundRect(r, 0.008f, 0.008f, fill);
            c.restore();
            sh(stroke, carRim, a);
            stroke.setStrokeWidth(0.012f);
            c.drawCircle(0, 0, 0.073f, stroke);
            c.save();
            c.rotate(ang);
            col(fill, 0xFFC4C9D1, a);
            for (int i = 0; i < 5; i++) {
                c.save();
                c.rotate(i * 72);
                path.reset();
                path.moveTo(-0.008f, -0.016f);
                path.lineTo(-0.017f, -0.07f);
                path.lineTo(0.017f, -0.07f);
                path.lineTo(0.008f, -0.016f);
                path.close();
                c.drawPath(path, fill);
                c.restore();
            }
            col(fill, 0xFF6A6F78, a);
            for (int i = 0; i < 5; i++) {
                double t = Math.toRadians(i * 72 + 36);
                c.drawCircle((float) Math.cos(t) * 0.028f, (float) Math.sin(t) * 0.028f, 0.0045f, fill);
            }
            c.restore();
            col(fill, 0xFF8E949D, a);
            c.drawCircle(0, 0, 0.018f, fill);
            col(fill, accent, a);
            c.drawCircle(0, 0, 0.007f, fill);
            c.restore();
        }
        c.restore();
    }

    // =====================================================================
    // Гарнитура
    // =====================================================================

    void headset(Canvas c, float cx, float cy, float s, int a, float time, int accent) {
        begin(c, cx, cy, s);
        c.rotate((float) Math.sin(time * 1.5f) * 4f, 0, 0);
        shadow(c, 0.0f, 0.46f, 0.5f, 0.05f, a);
        // заушина (полупрозрачный пластик)
        r.set(-0.05f, -0.42f, 0.35f, 0.05f);
        col(stroke, 0xA08C9099, a);
        stroke.setStrokeWidth(0.035f);
        c.drawArc(r, 160, 220, false, stroke);
        col(stroke, 0x60FFFFFF, a);
        stroke.setStrokeWidth(0.007f);
        c.drawArc(r, 190, 120, false, stroke);
        // микрофонная штанга с сеткой
        col(stroke, 0xFF34373D, a);
        stroke.setStrokeWidth(0.035f);
        c.drawLine(-0.04f, 0.22f, -0.29f, 0.39f, stroke);
        col(stroke, 0x40FFFFFF, a);
        stroke.setStrokeWidth(0.007f);
        c.drawLine(-0.05f, 0.205f, -0.28f, 0.37f, stroke);
        col(fill, 0xFF202227, a);
        c.drawCircle(-0.31f, 0.405f, 0.042f, fill);
        col(fill, 0xFF4A4D55, a);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) c.drawCircle(-0.31f + i * 0.014f, 0.405f + j * 0.014f, 0.004f, fill);
        }
        // корпус
        c.save();
        sh(fill, hsBody, a);
        r.set(-0.09f, -0.22f, 0.09f, 0.26f);
        c.drawRoundRect(r, 0.09f, 0.09f, fill);
        col(stroke, 0xFF5E626C, a);
        stroke.setStrokeWidth(0.004f);
        c.drawRoundRect(r, 0.09f, 0.09f, stroke);
        c.restore();
        // динамик с хромовым кольцом и мягкой насадкой
        c.save();
        c.translate(0, -0.11f);
        col(fill, 0xFF0E0F11, a);
        c.drawCircle(0, 0, 0.066f, fill);
        col(stroke, 0xFFB0B5BE, a);
        stroke.setStrokeWidth(0.008f);
        c.drawCircle(0, 0, 0.066f, stroke);
        sh(fill, hsTip, a);
        c.drawCircle(0, 0, 0.05f, fill);
        col(fill, 0x50FFFFFF, a);
        c.drawCircle(-0.017f, -0.018f, 0.012f, fill);
        c.restore();
        // кнопка, индикатор, разъём
        col(fill, 0xFF23252A, a);
        r.set(-0.042f, 0.0f, 0.042f, 0.09f);
        c.drawOval(r, fill);
        col(stroke, 0xFF50545C, a);
        stroke.setStrokeWidth(0.004f);
        c.drawOval(r, stroke);
        col(fill, accent, (int) (a * (0.5f + 0.5f * (float) Math.sin(time * 4))));
        c.drawCircle(0, 0.16f, 0.011f, fill);
        col(fill, 0xFF08090A, a);
        r.set(-0.022f, 0.243f, 0.022f, 0.255f);
        c.drawRoundRect(r, 0.005f, 0.005f, fill);
        col(fill, 0x28FFFFFF, a);
        r.set(-0.07f, -0.2f, -0.03f, 0.18f);
        c.drawRoundRect(r, 0.02f, 0.02f, fill);
        c.restore();
    }

    // =====================================================================
    // Телевизор
    // =====================================================================

    void tv(Canvas c, float cx, float cy, float s, float w, int a, float time, float music, int accent) {
        begin(c, cx, cy, s);
        float tw = Math.min(1.45f, w * 0.74f / s);
        float th = Math.min(tw * 0.56f, 0.8f);
        float left = -tw / 2, top = -th / 2 - 0.06f, bottom = top + th;
        shadow(c, 0, bottom + 0.12f, tw * 0.85f, 0.05f, a);
        // ножки
        for (int side = -1; side <= 1; side += 2) {
            col(stroke, 0xFF3A3D44, a);
            stroke.setStrokeWidth(0.016f);
            c.drawLine(side * tw * 0.33f, bottom - 0.01f, side * tw * 0.37f, bottom + 0.115f, stroke);
            col(fill, 0xFF23252A, a);
            r.set(side * tw * 0.37f - 0.04f, bottom + 0.108f, side * tw * 0.37f + 0.04f, bottom + 0.122f);
            c.drawRoundRect(r, 0.007f, 0.007f, fill);
        }
        // рамка
        c.save();
        c.translate(0, top + th / 2);
        sh(fill, tvFrame, a);
        r.set(left, -th / 2, -left, th / 2);
        c.drawRoundRect(r, 0.02f, 0.02f, fill);
        col(stroke, 0x785E626C, a);
        stroke.setStrokeWidth(0.004f);
        c.drawRoundRect(r, 0.02f, 0.02f, stroke);
        // экран
        float in = 0.016f;
        r.set(left + in, -th / 2 + in, -left - in, th / 2 - in * 1.8f);
        sh(fill, tvScreen, a);
        c.drawRoundRect(r, 0.006f, 0.006f, fill);
        c.save();
        c.clipRect(r);
        float band = ((time * 0.25f) % 1.6f - 0.3f) * tw;
        col(fill, 0x18FFFFFF, a);
        path.reset();
        path.moveTo(left + band, -th / 2);
        path.lineTo(left + band + tw * 0.18f, -th / 2);
        path.lineTo(left + band - tw * 0.05f, th / 2);
        path.lineTo(left + band - tw * 0.23f, th / 2);
        path.close();
        c.drawPath(path, fill);
        // «эквалайзер» на экране
        int bars = 9;
        float bw = (r.width() * 0.56f) / bars;
        for (int i = 0; i < bars; i++) {
            float k = 0.22f + 0.78f * music * (0.5f + 0.5f * (float) Math.sin(time * 6 + i * 0.9f));
            float bh = r.height() * 0.6f * k;
            float x = -r.width() * 0.28f + i * bw;
            col(fill, accent, a);
            r.set(x + bw * 0.16f, th / 2 - in * 1.8f - 0.035f - bh, x + bw * 0.84f, th / 2 - in * 1.8f - 0.035f);
            c.drawRoundRect(r, bw * 0.2f, bw * 0.2f, fill);
            col(fill, 0x90FFFFFF, a);
            r.set(r.left, r.top, r.right, r.top + 0.008f);
            c.drawRoundRect(r, 0.004f, 0.004f, fill);
            r.set(left + in, -th / 2 + in, -left - in, th / 2 - in * 1.8f);
        }
        c.restore();
        // логотип и индикатор на нижней рамке
        col(fill, 0xFF8C919B, a);
        r.set(-0.025f, th / 2 - in * 1.25f, 0.025f, th / 2 - in * 0.65f);
        c.drawRoundRect(r, 0.004f, 0.004f, fill);
        col(fill, 0xFFFF4D4D, (int) (a * 0.8f));
        c.drawCircle(tw * 0.42f, th / 2 - in * 0.9f, 0.004f, fill);
        c.restore();
        c.restore();
    }

    // =====================================================================
    // Часы
    // =====================================================================

    /** battery -1 — неизвестно; hour 0..12, minute 0..60, second 0..60 (с долями). */
    void watch(Canvas c, float cx, float cy, float s, int a, int battery, float hour, float minute, float second,
               int accent, int green, int orange, int red) {
        begin(c, cx, cy, s);
        shadow(c, 0, 0.5f, 0.42f, 0.05f, a);
        // ремешки со строчкой и дырочками
        for (int k = -1; k <= 1; k += 2) {
            sh(fill, wStrap, a);
            r.set(-0.15f, k < 0 ? -0.47f : 0.18f, 0.15f, k < 0 ? -0.18f : 0.47f);
            c.drawRoundRect(r, 0.06f, 0.06f, fill);
            col(stroke, 0xFF4A4D55, a);
            stroke.setStrokeWidth(0.003f);
            for (float yy = r.top + 0.03f; yy < r.bottom - 0.03f; yy += 0.026f) {
                c.drawLine(-0.125f, yy, -0.125f, yy + 0.013f, stroke);
                c.drawLine(0.125f, yy, 0.125f, yy + 0.013f, stroke);
            }
            if (k > 0) {
                col(fill, 0xFF0B0C0E, a);
                for (int i = 0; i < 3; i++) c.drawCircle(0, 0.32f + i * 0.045f, 0.011f, fill);
            }
        }
        // корпус, заводная головка с насечками, кнопка
        sh(fill, wCase, a);
        c.drawCircle(0, 0, 0.30f, fill);
        r.set(0.285f, -0.05f, 0.345f, 0.05f);
        c.drawRoundRect(r, 0.012f, 0.012f, fill);
        col(stroke, 0xFF3A3D44, a);
        stroke.setStrokeWidth(0.003f);
        for (float yy = -0.035f; yy <= 0.036f; yy += 0.0175f) c.drawLine(0.295f, yy, 0.338f, yy, stroke);
        col(fill, 0xFF5B606A, a);
        r.set(0.27f, 0.1f, 0.305f, 0.16f);
        c.drawRoundRect(r, 0.01f, 0.01f, fill);
        // безель с делениями
        col(fill, 0xFF15161A, a);
        c.drawCircle(0, 0, 0.268f, fill);
        for (int i = 0; i < 60; i++) {
            double t = Math.toRadians(i * 6);
            boolean major = i % 5 == 0;
            col(stroke, major ? 0xFFE6E8EC : 0xFF5E626C, a);
            stroke.setStrokeWidth(major ? 0.006f : 0.0025f);
            float r0 = major ? 0.232f : 0.245f, r1 = 0.258f;
            c.drawLine((float) Math.cos(t) * r0, (float) Math.sin(t) * r0, (float) Math.cos(t) * r1, (float) Math.sin(t) * r1, stroke);
        }
        // кольцо заряда
        float rr = 0.205f;
        r.set(-rr, -rr, rr, rr);
        col(stroke, 0xFF2A2C32, a);
        stroke.setStrokeWidth(0.026f);
        c.drawArc(r, 0, 360, false, stroke);
        if (battery >= 0) {
            col(stroke, battery <= 15 ? red : battery <= 30 ? orange : green, a);
            c.drawArc(r, -90, 360f * battery / 100f, false, stroke);
        }
        // стрелки
        hand(c, hour / 12f, 0.1f, 0.022f, Color.WHITE, a);
        hand(c, minute / 60f, 0.155f, 0.016f, Color.WHITE, a);
        hand(c, second / 60f, 0.175f, 0.008f, accent, a);
        col(fill, accent, a);
        c.drawCircle(0, 0, 0.018f, fill);
        // блик на стекле
        col(fill, 0x0CFFFFFF, a);
        r.set(-0.2f, -0.23f, 0.04f, -0.1f);
        c.drawOval(r, fill);
        c.restore();
    }

    private void hand(Canvas c, float turn, float len, float width, int color, int a) {
        double t = turn * Math.PI * 2 - Math.PI / 2;
        col(stroke, color, a);
        stroke.setStrokeWidth(width);
        c.drawLine(0, 0, (float) Math.cos(t) * len, (float) Math.sin(t) * len, stroke);
    }

    // =====================================================================
    // Кейс беспроводных наушников и наушник-«фасолина» (Galaxy Buds)
    // =====================================================================

    /** Кейс с открытой крышкой: geometry как раньше (ширина 0,8, высота 0,36, верх на +0,1). */
    void budsCase(Canvas c, float cx, float cy, float s, boolean dark, int a, float time) {
        begin(c, cx, cy, s);
        float cw = 0.8f, ch = 0.36f, caseTop = 0.10f, caseBottom = caseTop + ch;
        shadow(c, 0, caseBottom + 0.01f, cw * 0.95f, 0.06f, a);
        // открытая крышка (внутренняя сторона)
        sh(fill, dark ? lidDark : lidWhite, a);
        r.set(-cw / 2 + 3 * u, caseTop - ch * 0.62f, cw / 2 - 3 * u, caseTop + ch * 0.25f);
        c.drawRoundRect(r, ch * 0.45f, ch * 0.45f, fill);
        col(stroke, dark ? 0xFF4A4D55 : 0xFFA6A9B0, a);
        stroke.setStrokeWidth(0.004f);
        c.drawRoundRect(r, ch * 0.45f, ch * 0.45f, stroke);
        col(fill, dark ? 0x30FFFFFF : 0x60FFFFFF, a);
        r.set(-cw * 0.3f, caseTop - ch * 0.56f, cw * 0.3f, caseTop - ch * 0.5f);
        c.drawRoundRect(r, 0.01f, 0.01f, fill);
        // шарнир
        col(fill, dark ? 0xFF3A3D44 : 0xFF9DA1A9, a);
        r.set(-0.12f, caseTop - 0.016f, 0.12f, caseTop + 0.006f);
        c.drawRoundRect(r, 0.008f, 0.008f, fill);
        // корпус
        sh(fill, dark ? caseDark : caseWhite, a);
        r.set(-cw / 2, caseTop, cw / 2, caseBottom);
        c.drawRoundRect(r, ch * 0.5f, ch * 0.5f, fill);
        col(stroke, dark ? 0xFF4E525A : 0xFFB9BCC2, a);
        stroke.setStrokeWidth(0.004f);
        c.drawRoundRect(r, ch * 0.5f, ch * 0.5f, stroke);
        // шов под крышкой
        col(stroke, dark ? 0xFF0E0F11 : 0xFFA9ACB3, a);
        stroke.setStrokeWidth(0.004f);
        c.drawLine(-cw * 0.42f, caseTop + 0.022f, cw * 0.42f, caseTop + 0.022f, stroke);
        // гнёзда для наушников
        for (int side = -1; side <= 1; side += 2) {
            col(fill, dark ? 0xFF0B0C0E : 0xFF9EA2AA, a);
            r.set(side * 0.13f - 0.095f, caseTop + 0.035f, side * 0.13f + 0.095f, caseTop + 0.1f);
            c.drawOval(r, fill);
            col(fill, dark ? 0xFF2A2C31 : 0xFF7E828B, a);
            c.drawCircle(side * 0.13f, caseTop + 0.068f, 0.01f, fill);
        }
        // блик и индикатор
        col(fill, dark ? 0x22FFFFFF : 0x80FFFFFF, a);
        r.set(-cw * 0.38f, caseTop + 0.13f, -cw * 0.08f, caseTop + 0.2f);
        c.drawOval(r, fill);
        float led = 0.55f + 0.45f * (float) Math.sin(time * 3);
        col(fill, 0xFF4CD964, (int) (a * led * 0.35f));
        c.drawCircle(0, caseTop + ch * 0.62f, 0.022f, fill);
        col(fill, 0xFF4CD964, (int) (a * led));
        c.drawCircle(0, caseTop + ch * 0.62f, 0.009f, fill);
        c.restore();
    }

    /** Наушник-«фасолина» (Galaxy Buds Pro / Buds2 / Live): амбушюр, сенсорная панель, микрофон, блик. */
    void bean(Canvas c, float x, float y, float s, boolean right, int a) {
        begin(c, x, y, s);
        float bw = 0.21f, bh = bw * 0.82f;
        float side = right ? -1f : 1f;   // амбушюр смотрит к центру
        // амбушюр
        c.save();
        c.translate(side * 0.068f, 0.045f);
        sh(fill, tipGel, a);
        r.set(-0.044f, -0.038f, 0.044f, 0.038f);
        c.drawOval(r, fill);
        col(fill, 0xFF3E4148, a);
        c.drawCircle(side * 0.01f, 0.004f, 0.014f, fill);
        c.restore();
        // корпус
        sh(fill, beanBody, a);
        r.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        c.drawOval(r, fill);
        col(stroke, 0xFFB5B8BF, a);
        stroke.setStrokeWidth(0.003f);
        c.drawOval(r, stroke);
        // сенсорная панель
        sh(fill, beanTouch, a);
        r.set(-bw * 0.34f - side * 0.012f, -bh * 0.34f, bw * 0.34f - side * 0.012f, bh * 0.3f);
        c.drawOval(r, fill);
        col(stroke, 0xFFC8CBD1, a);
        stroke.setStrokeWidth(0.0025f);
        c.drawOval(r, stroke);
        // микрофон и датчик
        col(fill, 0xFF4A4D55, a);
        c.drawCircle(-side * bw * 0.34f, -bh * 0.3f, 0.0045f, fill);
        col(fill, 0xFF2A2C31, a);
        c.drawCircle(side * bw * 0.3f, bh * 0.28f, 0.006f, fill);
        // блик
        col(fill, 0xC8FFFFFF, a);
        r.set(-bw * 0.33f, -bh * 0.4f, -bw * 0.08f, -bh * 0.22f);
        c.drawOval(r, fill);
        c.restore();
    }

    // =====================================================================
    // Динамик машины (вид сверху, для схемы салона)
    // =====================================================================

    /** Динамик в двери: решётка, резиновый подвес, диффузор, колпачок с бликом. level 0..1 — яркость. */
    void carSpeaker(Canvas c, float x, float y, float rad, float level) {
        begin(c, x, y, rad);
        int a = 255;
        col(fill, 0xFF101114, a);
        c.drawCircle(0, 0, 1f, fill);
        sh(fill, coneSurround, a);
        c.drawCircle(0, 0, 0.95f, fill);
        sh(fill, cone, a);
        c.drawCircle(0, 0, 0.78f, fill);
        col(stroke, 0x30FFFFFF, a);
        stroke.setStrokeWidth(0.03f);
        c.drawCircle(0, 0, 0.55f, stroke);
        sh(fill, coneCap, a);
        c.drawCircle(0, 0, 0.34f, fill);
        col(fill, 0x60FFFFFF, a);
        c.drawCircle(-0.1f, -0.12f, 0.08f, fill);
        col(stroke, 0xFF55585F, a);
        stroke.setStrokeWidth(1.2f * u);
        c.drawCircle(0, 0, 1f, stroke);
        if (rad * 1f > 9 * d) {
            col(fill, 0xFF6A6E77, a);
            for (int i = 0; i < 4; i++) {
                double t = Math.toRadians(45 + i * 90);
                c.drawCircle((float) Math.cos(t) * 0.88f, (float) Math.sin(t) * 0.88f, 0.045f, fill);
            }
        }
        if (level < 1f) {
            col(fill, 0xFF000000, (int) (255 * (1f - level) * 0.6f));
            c.drawCircle(0, 0, 1f, fill);
        }
        c.restore();
    }

    // =====================================================================
    // Помощники
    // =====================================================================

    /** Перейти в единицы картинки: центр (cx, cy), 1 = s пикселей. Закрыть — c.restore(). */
    private void begin(Canvas c, float cx, float cy, float s) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        u = d / s;
    }

    private void shadow(Canvas c, float x, float y, float w, float h, int a) {
        c.save();
        c.translate(x, y);
        c.scale(w, h);
        sh(fill, shadow, a);
        c.drawCircle(0, 0, 0.5f, fill);
        c.restore();
    }

    /** Дуги «звука» рядом с устройством, когда играет музыка. */
    private void waves(Canvas c, float x, float y, float size, boolean leftSide, int a, float time, float music, int accent) {
        if (music < 0.05f) return;
        for (int i = 0; i < 2; i++) {
            float ph = (time * 0.9f + i * 0.5f) % 1f;
            int wa = (int) (a * music * (1f - ph));
            if (wa <= 2) continue;
            col(stroke, accent, wa);
            stroke.setStrokeWidth(2 * u);
            float rad = size * (0.4f + 0.8f * ph);
            r.set(x - rad, y - rad, x + rad, y + rad);
            c.drawArc(r, leftSide ? 140 : -40, 80, false, stroke);
        }
    }

    /** Сплошной цвет с учётом прозрачности появления. */
    private static void col(Paint p, int color, int a) {
        p.setShader(null);
        p.setColor(color);
        p.setAlpha(Color.alpha(color) * Math.max(0, Math.min(255, a)) / 255);
    }

    private static void sh(Paint p, Shader s, int a) {
        p.setColor(Color.BLACK);
        p.setShader(s);
        p.setAlpha(Math.max(0, Math.min(255, a)));
    }
}
