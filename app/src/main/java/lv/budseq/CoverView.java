package lv.budseq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Плитка игры: обложка Steam (обрезка по центру), иконка игры телефона на карточке
 * или заглушка — градиент по названию и само название. Рамка акцентом — «играете сейчас».
 */
public class CoverView extends View {
    private Bitmap cover;
    private Drawable icon;
    private String title = "", source = "";
    private float aspect = 1.5f;   // высота / ширина: 600×900 у Steam
    private boolean playing;

    private final float d;
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect src = new Rect();

    public CoverView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        small.setColor(Color.argb(190, 255, 255, 255));
        small.setTextAlign(Paint.Align.CENTER);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
    }

    public void setCover(Bitmap b) {
        cover = b;
        invalidate();
    }

    public void setIcon(Drawable dr) {
        icon = dr;
        invalidate();
    }

    public void setTitle(String name, String src) {
        title = name == null ? "" : name;
        source = src == null ? "" : src;
        invalidate();
    }

    public void setAspect(float a) {
        aspect = a;
        requestLayout();
    }

    public void setPlaying(boolean on) {
        playing = on;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, Math.round(w * aspect));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float r = 16 * d;
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (cover != null) {
            drawCropped(c, w, h);
        } else {
            int base = hueColor(title);
            bg.setShader(new LinearGradient(0, 0, w, h, base, darker(base), Shader.TileMode.CLAMP));
            c.drawRect(box, bg);
            bg.setShader(null);
            if (icon != null) {
                int s = Math.round(Math.min(w, h) * 0.52f);
                int l = (w - s) / 2, t = (h - s) / 2;
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(c);
            } else {
                drawTitle(c, w, h);
            }
        }
        c.restore();
        if (playing) {
            ring.setColor(Theme.liveAccent());
            float in = ring.getStrokeWidth() / 2;
            box.set(in, in, w - in, h - in);
            c.drawRoundRect(box, r - in, r - in, ring);
        }
    }

    /** Обложка по центру без искажений (лишнее обрезается). */
    private void drawCropped(Canvas c, int w, int h) {
        int bw = cover.getWidth(), bh = cover.getHeight();
        float scale = Math.max(w / (float) bw, h / (float) bh);
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int sl = (bw - sw) / 2, st = (bh - sh) / 2;
        src.set(sl, st, sl + sw, st + sh);
        c.drawBitmap(cover, src, box, img);
    }

    /** Название крупно по центру (до 4 строк) и источник снизу. */
    private void drawTitle(Canvas c, int w, int h) {
        // шрифт поменьше, пока каждое слово не влезет целиком (длинные названия не рвём на «Showdo…»)
        float maxW = w - 20 * d;
        float size = Math.min(17 * d, w / 7f);
        List<String> lines;
        while (true) {
            text.setTextSize(size);
            lines = wrap(title, maxW, 4);
            if (size <= 10 * d || fits(lines, maxW)) break;
            size -= d;
        }
        float lh = text.getTextSize() * 1.2f;
        float y = h / 2f - (lines.size() - 1) * lh / 2f + text.getTextSize() * 0.35f;
        for (String s : lines) {
            c.drawText(s, w / 2f, y, text);
            y += lh;
        }
        if (!source.isEmpty()) {
            small.setTextSize(11 * d);
            c.drawText(source, w / 2f, h - 12 * d, small);
        }
    }

    private boolean fits(List<String> lines, float maxW) {
        for (String l : lines) if (l.endsWith("…")) return false;
        return true;
    }

    private List<String> wrap(String s, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : s.split("\\s+")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (text.measureText(next) <= maxW || line.isEmpty()) {
                line = next;
            } else {
                out.add(line);
                line = word;
            }
        }
        if (!line.isEmpty()) out.add(line);
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, cut.get(maxLines - 1) + "…");
            out = cut;
        }
        // слишком длинное слово — ужимаем до ширины
        for (int i = 0; i < out.size(); i++) {
            String l = out.get(i);
            while (l.length() > 2 && text.measureText(l) > maxW) l = l.substring(0, l.length() - 2) + "…";
            out.set(i, l);
        }
        return out;
    }

    /** Свой цвет для каждой игры: оттенок от названия, насыщенный и не слишком светлый. */
    static int hueColor(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        float hue = ((h & 0x7FFFFFFF) % 360);
        return Color.HSVToColor(new float[]{hue, 0.55f, 0.55f});
    }

    private static int darker(int c) {
        return Color.rgb(Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3);
    }
}
