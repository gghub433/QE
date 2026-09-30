package lv.budseq;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Итоги Music Time — карточка для сторис 1080×1920: часы за неделю или месяц,
 * любимое устройство, пресет, топ-исполнитель, достижения, фирменная волна и название внизу.
 * Рисуется кодом на Canvas, сохраняется в кэш и отдаётся через ShareProvider.
 */
public final class ShareCard {
    public static final int W = 1080, H = 1920;
    static final String FILE = "musictime.png";

    /** Всё, что попадёт на карточку (строки уже на языке интерфейса). */
    public static final class Data {
        public String title = "Music Time", period = "", hours = "", hoursLabel = "";
        public String[] labels = new String[3], values = new String[3];
        public String achTitle = "", achEmpty = "";
        public String[] achNames = new String[ListenStats.ACH_COUNT];
        public boolean[] ach = new boolean[ListenStats.ACH_COUNT];
        public String appName = "EQ", tagline = "";
        public int accent = Theme.DEFAULT_ACCENT, wave = Theme.DEFAULT_ACCENT;
    }

    private ShareCard() { }

    /** Собрать данные за неделю (month = false) или текущий месяц. */
    public static Data collect(Context c, boolean month) {
        ListenStats st = ListenStats.get(c);
        ListenStats.Summary s = st.summary(month ? 0 : 7);
        Locale loc = c.getResources().getConfiguration().getLocales().get(0);
        Data d = new Data();
        d.title = c.getString(R.string.music_time);
        Calendar now = Calendar.getInstance();
        if (month) {
            String m = new SimpleDateFormat("LLLL yyyy", loc).format(now.getTime());
            d.period = m.isEmpty() ? m : m.substring(0, 1).toUpperCase(loc) + m.substring(1);
        } else {
            Calendar from = (Calendar) now.clone();
            from.add(Calendar.DAY_OF_YEAR, -6);
            SimpleDateFormat f = new SimpleDateFormat("d MMM", loc);
            d.period = c.getString(R.string.sc_week, f.format(from.getTime()) + " – " + f.format(now.getTime()));
        }
        d.hours = ListenStats.format(c, s.total);
        d.hoursLabel = c.getString(month ? R.string.sc_month_label : R.string.sc_week_label);
        d.labels[0] = c.getString(R.string.sc_device);
        d.values[0] = s.top(s.devices);
        d.labels[1] = c.getString(R.string.sc_preset);
        d.values[1] = s.top(s.presets);
        d.labels[2] = c.getString(R.string.sc_artist);
        d.values[2] = s.top(s.artists);
        d.achTitle = c.getString(R.string.ach_title);
        d.achEmpty = c.getString(R.string.sc_first_ach);
        int[] names = {R.string.ach_10h, R.string.ach_100h, R.string.ach_500h, R.string.ach_night, R.string.ach_loyal};
        for (int i = 0; i < names.length; i++) d.achNames[i] = c.getString(names[i]);
        d.ach = st.achievements();
        d.appName = c.getString(R.string.app_name);
        d.tagline = c.getString(R.string.tagline);
        d.accent = Theme.liveAccent();
        d.wave = Theme.liveWave();
        return d;
    }

    // =====================================================================
    // Рисование (чистый Canvas — можно отрисовать и вне Android)
    // =====================================================================

    static void draw(Canvas c, Data d) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF();

        // фон: акцент сверху уходит в почти чёрный, в углу — свечение цветом волны
        p.setShader(new LinearGradient(0, 0, 0, H, WaveView.mix(d.accent, Color.BLACK, 0.55f),
                Color.rgb(0x0B, 0x0C, 0x0F), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W * 0.85f, H * 0.12f, W * 0.7f,
                (d.wave & 0x00FFFFFF) | 0x66000000, d.wave & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // шапка: знак EQ + заголовок + период
        float x0 = 90, y0 = 150;
        r.set(x0, y0, x0 + 120, y0 + 120);
        p.setColor(d.accent);
        c.drawRoundRect(r, 34, 34, p);
        p.setColor(Color.WHITE);
        float[] bars = {0.45f, 0.8f, 0.55f, 1f, 0.65f};
        for (int i = 0; i < bars.length; i++) {
            float bx = x0 + 22 + i * 16, bh = 70 * bars[i];
            r.set(bx, y0 + 95 - bh, bx + 10, y0 + 95);
            c.drawRoundRect(r, 5, 5, p);
        }
        t.setColor(Color.WHITE);
        t.setFakeBoldText(true);
        t.setTextSize(70);
        c.drawText(d.title, x0 + 150, y0 + 72, t);
        t.setFakeBoldText(false);
        t.setTextSize(40);
        t.setColor(Color.argb(200, 255, 255, 255));
        c.drawText(fit(d.period, t, W - x0 - 150 - 60), x0 + 150, y0 + 122, t);

        // главное число
        t.setColor(Color.WHITE);
        t.setFakeBoldText(true);
        // крупно, но целиком: уменьшаем шрифт, пока не влезет
        float big = 150;
        t.setTextSize(big);
        while (big > 70 && t.measureText(d.hours) > W - x0 * 2) {
            big -= 6;
            t.setTextSize(big);
        }
        c.drawText(d.hours, x0, 540, t);
        t.setFakeBoldText(false);
        t.setTextSize(46);
        t.setColor(Color.argb(210, 255, 255, 255));
        c.drawText(d.hoursLabel, x0, 610, t);

        // карточки: устройство, пресет, исполнитель
        float y = 690;
        for (int i = 0; i < 3; i++) {
            if (d.values[i] == null) continue;
            r.set(x0, y, W - x0, y + 170);
            p.setColor(Color.argb(28, 255, 255, 255));
            c.drawRoundRect(r, 44, 44, p);
            t.setTextSize(36);
            t.setColor(Color.argb(170, 255, 255, 255));
            c.drawText(d.labels[i], x0 + 44, y + 62, t);
            t.setTextSize(58);
            t.setFakeBoldText(true);
            t.setColor(Color.WHITE);
            c.drawText(fit(d.values[i], t, W - x0 * 2 - 88), x0 + 44, y + 132, t);
            t.setFakeBoldText(false);
            y += 196;
        }

        // достижения — только полученные
        y += 36;
        t.setTextSize(40);
        t.setColor(Color.argb(200, 255, 255, 255));
        c.drawText(d.achTitle, x0, y, t);
        y += 40;
        int earned = 0;
        for (boolean b : d.ach) if (b) earned++;
        if (earned == 0) {
            t.setTextSize(38);
            t.setColor(Color.argb(150, 255, 255, 255));
            c.drawText(fit(d.achEmpty, t, W - x0 * 2), x0, y + 50, t);
        } else {
            float size = 150, gap = (W - x0 * 2 - size * Math.min(earned, 5)) / Math.max(1, Math.min(earned, 5) - 1);
            gap = Math.min(gap, 70);
            float bx = x0;
            for (int i = 0; i < d.ach.length; i++) {
                if (!d.ach[i]) continue;
                badge(c, p, t, i, bx, y, size, d);
                bx += size + gap;
            }
        }

        // волна и название внизу
        WaveView.paint(c, 60, 1615, W - 120, 120, 0.9f, 2.3f, d.wave, 3f, new Path(), new Paint(Paint.ANTI_ALIAS_FLAG));
        t.setTextAlign(Paint.Align.CENTER);
        t.setColor(Color.WHITE);
        t.setFakeBoldText(true);
        t.setTextSize(84);
        c.drawText(d.appName, W / 2f, 1820, t);
        t.setFakeBoldText(false);
        t.setTextSize(34);
        t.setColor(Color.argb(170, 255, 255, 255));
        c.drawText(fit(d.tagline, t, W - 160), W / 2f, 1872, t);
        t.setTextAlign(Paint.Align.LEFT);
    }

    /** Значок достижения: круг цвета акцента и простой рисунок (без эмодзи). */
    private static void badge(Canvas c, Paint p, Paint t, int kind, float x, float y, float size, Data d) {
        float cx = x + size / 2, cy = y + size / 2, rad = size / 2;
        p.setColor(d.accent);
        c.drawCircle(cx, cy, rad, p);
        p.setColor(Color.WHITE);
        if (kind <= ListenStats.ACH_500H) {
            String n = kind == ListenStats.ACH_10H ? "10" : kind == ListenStats.ACH_100H ? "100" : "500";
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(Color.WHITE);
            t.setFakeBoldText(true);
            t.setTextSize(n.length() > 2 ? 50 : 60);
            c.drawText(n, cx, cy + 20, t);
            t.setFakeBoldText(false);
            t.setTextAlign(Paint.Align.LEFT);
        } else if (kind == ListenStats.ACH_NIGHT) {
            // месяц: белый круг, сверху круг цвета значка со сдвигом
            c.drawCircle(cx - 6, cy, rad * 0.46f, p);
            p.setColor(d.accent);
            c.drawCircle(cx + rad * 0.2f, cy - rad * 0.16f, rad * 0.4f, p);
        } else {
            // наушники: дуга оголовья и две чашки
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(12);
            s.setStrokeCap(Paint.Cap.ROUND);
            s.setColor(Color.WHITE);
            RectF arc = new RectF(cx - rad * 0.48f, cy - rad * 0.5f, cx + rad * 0.48f, cy + rad * 0.46f);
            c.drawArc(arc, 180, 180, false, s);
            RectF cup = new RectF(cx - rad * 0.56f, cy - rad * 0.02f, cx - rad * 0.26f, cy + rad * 0.44f);
            c.drawRoundRect(cup, 12, 12, p);
            cup.set(cx + rad * 0.26f, cy - rad * 0.02f, cx + rad * 0.56f, cy + rad * 0.44f);
            c.drawRoundRect(cup, 12, 12, p);
        }
        // подпись: длинная — в две строки
        t.setTextAlign(Paint.Align.CENTER);
        t.setTextSize(28);
        t.setColor(Color.argb(210, 255, 255, 255));
        String name = d.achNames[kind];
        float maxW = size + 56;
        int sp = name.lastIndexOf(' ');
        if (t.measureText(name) > maxW && sp > 0) {
            c.drawText(fit(name.substring(0, sp), t, maxW), cx, y + size + 40, t);
            c.drawText(fit(name.substring(sp + 1), t, maxW), cx, y + size + 74, t);
        } else {
            c.drawText(fit(name, t, maxW), cx, y + size + 40, t);
        }
        t.setTextAlign(Paint.Align.LEFT);
    }

    static String fit(String s, Paint p, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    // =====================================================================
    // Файл и «Поделиться»
    // =====================================================================

    /** Нарисовать и сохранить в кэш (вызывать не в главном потоке). */
    public static File render(Context c, Data d) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        draw(new Canvas(bmp), d);
        File dir = new File(c.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("no cache dir");
        File f = new File(dir, FILE);
        FileOutputStream os = new FileOutputStream(f);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        bmp.recycle();
        return f;
    }

    public static void share(Activity a) {
        Uri uri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + FILE);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(send, a.getString(R.string.sc_share)));
    }
}
