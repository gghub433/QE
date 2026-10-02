package lv.budseq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.RemoteViews;

/**
 * Виджет на главный экран: трек и обложка, волна-всплески от баса, кнопки плеера,
 * заряд наушников и Music Time за сегодня. Волна — отдельная полоса (w_wave): пока играет музыка,
 * служба обновляет только её несколько раз в секунду.
 * Всё рисуется кодом в Bitmap; XML-разметка (res/layout/widget.xml) — только потому,
 * что Android не умеет виджеты без неё: там картинка и три кнопки.
 * Данные присылает EqService (у него «Сейчас играет», устройства и статистика).
 */
public class EqWidget extends AppWidgetProvider {

    static final String ACT_PREV = "lv.budseq.widget.PREV";
    static final String ACT_PLAY = "lv.budseq.widget.PLAY";
    static final String ACT_NEXT = "lv.budseq.widget.NEXT";

    private static final int CARD = Color.rgb(0x1C, 0x1D, 0x21);
    private static final int GREY = Color.rgb(0xA0, 0xA3, 0xAA);
    private static final int GREEN = Color.rgb(0x4C, 0xD9, 0x64);

    /** Что показать. Собирает служба. */
    public static final class Data {
        public String title, artist, device, battery, eqStatus;
        public Bitmap art;
        public boolean playing, eqOn;
        /** Заряд L/R/кейса (Galaxy Buds, AirPods); -1 — нет данных. */
        public int batL = -1, batR = -1, batCase = -1;
        public long todaySecs;
        public int accent = Theme.accent();
        public int waveColor = Theme.wave();
    }

    private static volatile Data last;
    /** Последние уровни волны (живой бас от службы) — чтобы полная перерисовка их не сбрасывала. */
    private static volatile float[] lastWave;
    private static volatile float lastGlow;

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        EqTileService.ensureService(c); // служба пришлёт свежие данные
        push(c, last);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle options) {
        push(c, last);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String a = intent.getAction();
        int key = ACT_PREV.equals(a) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : ACT_PLAY.equals(a) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                : ACT_NEXT.equals(a) ? KeyEvent.KEYCODE_MEDIA_NEXT : 0;
        if (key == 0) return;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        } catch (Exception ignored) {
        }
    }

    private static int[] ids(Context c) {
        try {
            return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, EqWidget.class));
        } catch (Exception e) {
            return new int[0];
        }
    }

    /** Есть ли виджет на экране (иначе служба не тратит время на рисование). */
    public static boolean exists(Context c) {
        return ids(c).length > 0;
    }

    public static void push(Context ctx, Data data) {
        Context c = Lang.wrap(ctx.getApplicationContext());
        if (data == null) data = new Data();
        last = data;
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        float d = c.getResources().getDisplayMetrics().density;
        float scale = Math.min(d, 2.5f); // не раздуваем картинку: у виджетов есть лимит памяти
        for (int id : ids(c)) {
            int[] sz = sizeDp(mgr, id);
            int wDp = sz[0], hDp = sz[1];
            Bitmap bmp = Bitmap.createBitmap(Math.round(wDp * scale), Math.round(hDp * scale), Bitmap.Config.ARGB_8888);
            draw(new Canvas(bmp), bmp.getWidth(), bmp.getHeight(), scale, data, c);

            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
            rv.setImageViewBitmap(R.id.w_image, bmp);
            // полоса волны поверх картинки: место — отступами, картинка — отдельно
            float[] wr = waveRect(wDp, hDp);
            float dd = c.getResources().getDisplayMetrics().density;
            rv.setViewPadding(R.id.w_wave_box, Math.round(wr[0] * dd), Math.round(wr[1] * dd),
                    Math.round((wDp - wr[0] - wr[2]) * dd), Math.round((hDp - wr[1] - wr[3]) * dd));
            rv.setImageViewBitmap(R.id.w_wave, waveBitmap(wr, data, lastWave, lastGlow,
                    (System.currentTimeMillis() % 100000L) / 1000f));
            rv.setOnClickPendingIntent(R.id.w_wave, openApp(c));
            rv.setImageViewResource(R.id.w_play, data.playing ? R.drawable.ic_pause : R.drawable.ic_play);
            if (Build.VERSION.SDK_INT >= 31) {
                // кнопка «играть» — цветом акцента из темы (раньше Android 12 остаётся синей из XML)
                rv.setColorStateList(R.id.w_play, "setBackgroundTintList", ColorStateList.valueOf(data.accent));
            }
            rv.setOnClickPendingIntent(R.id.w_prev, action(c, ACT_PREV, 1));
            rv.setOnClickPendingIntent(R.id.w_play, action(c, ACT_PLAY, 2));
            rv.setOnClickPendingIntent(R.id.w_next, action(c, ACT_NEXT, 3));
            rv.setOnClickPendingIntent(R.id.w_image, openApp(c));
            mgr.updateAppWidget(id, rv);
        }
    }

    private static PendingIntent openApp(Context c) {
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_MUSIC);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 20, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Размер виджета в dp, как его рисуем (портретная ориентация: ширина min, высота max). */
    private static int[] sizeDp(AppWidgetManager mgr, int id) {
        Bundle o = mgr.getAppWidgetOptions(id);
        int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
        int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150);
        if (wDp <= 0) wDp = 320;
        if (hDp <= 0) hDp = 150;
        return new int[]{Math.max(180, Math.min(wDp, 600)), Math.max(100, Math.min(hDp, 300))};
    }

    /** Где волна, dp: {слева, сверху, ширина, высота} — между обложкой и нижними строками (как в draw). */
    static float[] waveRect(float wDp, float hDp) {
        float pad = 14, bottomArea = 42;
        float art = Math.max(28, Math.min(52, hDp - pad * 2 - bottomArea - 26));
        float top = pad + art + 2;
        float h = Math.max(14, hDp - pad - bottomArea - top);
        return new float[]{pad, top, wDp - pad * 2, h};
    }

    /** Картинка волны: живой бас (half) или, пока его нет, спокойная линия без выдуманных пиков. */
    private static Bitmap waveBitmap(float[] wr, Data data, float[] half, float glow, float time) {
        int color = data.waveColor != 0 ? data.waveColor : data.accent;
        float s = 2f;   // своя плотность: полоса маленькая, обновляется часто
        if (half == null) {
            half = new float[AudioPulse.BARS];
            WaveView.synth(half, data.playing ? 0.12f : 0.06f, time);
            glow = 0.3f;
        }
        return WaveView.bitmap(Math.round(wr[2] * s), Math.round(wr[3] * s), half, glow, time, color, s, 0);
    }

    /**
     * Только полоса волны (несколько раз в секунду, пока играет музыка): маленькая картинка
     * через partiallyUpdateAppWidget — остальной виджет не перерисовывается.
     * half == null — живого баса нет, вернуть спокойную волну.
     */
    public static void pushWave(Context ctx, float[] half, float glow, float time) {
        Context c = ctx.getApplicationContext();
        Data data = last != null ? last : new Data();
        lastWave = half != null ? half.clone() : null;
        lastGlow = glow;
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        for (int id : ids(c)) {
            try {
                int[] sz = sizeDp(mgr, id);
                RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
                rv.setImageViewBitmap(R.id.w_wave, waveBitmap(waveRect(sz[0], sz[1]), data, half, glow, time));
                mgr.partiallyUpdateAppWidget(id, rv);
            } catch (Exception ignored) {
                // виджет удалили между проверкой и обновлением
            }
        }
    }

    private static PendingIntent action(Context c, String act, int code) {
        Intent i = new Intent(c, EqWidget.class);
        i.setAction(act);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // =====================================================================
    // Рисование (чистый Canvas — можно отрисовать и вне Android)
    // =====================================================================

    static void draw(Canvas c, float w, float h, float d, Data data, Context ctx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF();
        float pad = 14 * d;
        float bottomArea = 42 * d;                       // две строки внизу слева, справа — кнопки
        int wave = data.waveColor != 0 ? data.waveColor : data.accent;

        // карточка
        p.setColor(CARD);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, 24 * d, 24 * d, p);

        // обложка: размер от высоты, чтобы волне осталось место
        float art = Math.max(28 * d, Math.min(52 * d, h - pad * 2 - bottomArea - 26 * d));
        r.set(pad, pad, pad + art, pad + art);
        if (data.art != null) {
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, 12 * d, 12 * d, Path.Direction.CW);
            c.clipPath(clip);
            c.drawBitmap(data.art, null, r, p);
            c.restore();
        } else {
            p.setColor(WaveView.mix(CARD, wave, 0.25f));
            c.drawRoundRect(r, 12 * d, 12 * d, p);
            p.setColor(Color.WHITE);
            p.setAlpha(200);
            float nx = r.centerX(), ny = r.centerY();
            c.drawCircle(nx - art * 0.08f, ny + art * 0.14f, art * 0.12f, p);
            c.drawRect(nx + art * 0.02f, ny - art * 0.24f, nx + art * 0.07f, ny + art * 0.14f, p);
            c.drawRect(nx + art * 0.02f, ny - art * 0.24f, nx + art * 0.2f, ny - art * 0.17f, p);
            p.setAlpha(255);
        }

        // трек, исполнитель, статус EQ
        float tx = pad + art + 12 * d, tw = w - tx - pad;
        float line = Math.min(18 * d, art / 2.6f);
        t.setColor(Color.WHITE);
        t.setTextSize(Math.min(15 * d, line * 0.95f));
        t.setFakeBoldText(true);
        String title = data.title != null && !data.title.isEmpty() ? data.title : ctx.getString(R.string.nothing_playing);
        float y = pad + line * 0.95f;
        c.drawText(fit(title, t, tw), tx, y, t);
        t.setFakeBoldText(false);
        t.setTextSize(Math.min(13 * d, line * 0.8f));
        t.setColor(Color.rgb(0xC8, 0xCA, 0xD0));
        if (data.artist != null && !data.artist.isEmpty()) {
            y += line;
            c.drawText(fit(data.artist, t, tw), tx, y, t);
        }
        if (art >= 44 * d) {
            y += line;
            t.setTextSize(11 * d);
            t.setColor(GREY);
            String eqs = "EQ" + (data.eqStatus != null && !data.eqStatus.isEmpty() ? " · " + data.eqStatus : "");
            p.setColor(data.eqOn ? GREEN : Color.rgb(0x6A, 0x6E, 0x78));
            c.drawCircle(tx + 3.5f * d, y - 4 * d, 3.5f * d, p);
            c.drawText(fit(eqs, t, tw - 12 * d), tx + 12 * d, y, t);
        }

        // волна между обложкой и нижними строками — отдельной полосой поверх (w_wave, см. waveRect)

        // низ слева (справа — кнопки из разметки): Music Time сегодня и заряд
        float leftW = w - pad * 2 - 150 * d;
        t.setTextSize(12 * d);
        t.setColor(GREY);
        c.drawText(fit(ctx.getString(R.string.w_today, ListenStats.format(ctx, data.todaySecs)), t, leftW),
                pad, h - pad - 22 * d, t);
        drawBattery(c, t, p, pad, h - pad - 4 * d, leftW, d, data);
    }

    /** «L 80%  R 90%  [кейс] 55%» — кейс нарисован значком, чтобы влезло в узкий виджет. */
    private static void drawBattery(Canvas c, Paint t, Paint p, float x, float y, float maxW, float d, Data data) {
        t.setTextSize(13 * d);
        t.setColor(Color.WHITE);
        if (data.batL < 0 && data.batR < 0) {
            String s = data.device != null ? data.device : "";
            if (data.battery != null && !data.battery.isEmpty()) s = s.isEmpty() ? data.battery : s + " · " + data.battery;
            c.drawText(fit(s, t, maxW), x, y, t);
            return;
        }
        String lr = "L " + pct(data.batL) + "  R " + pct(data.batR);
        boolean withCase = data.batCase >= 0 && t.measureText(lr + " " + pct(data.batCase)) + 30 * d <= maxW;
        if (!withCase && t.measureText(lr) > maxW) t.setTextSize(11 * d);   // совсем узкий виджет
        c.drawText(fit(lr, t, maxW), x, y, t);
        if (!withCase) return;
        float cx = x + t.measureText(lr) + 10 * d;
        // значок кейса: скруглённый прямоугольник с линией крышки
        RectF r = new RectF(cx, y - 11 * d, cx + 15 * d, y - 1 * d);
        p.setColor(Color.WHITE);
        p.setAlpha(210);
        c.drawRoundRect(r, 4 * d, 4 * d, p);
        p.setColor(CARD);
        c.drawRect(cx + 2 * d, y - 7.5f * d, cx + 13 * d, y - 6.5f * d, p);
        p.setAlpha(255);
        c.drawText(pct(data.batCase), cx + 19 * d, y, t);
    }

    private static String pct(int v) {
        return v >= 0 && v <= 100 ? v + "%" : "—";
    }

    /** Обрезать текст с «…», чтобы влез в ширину. */
    static String fit(String s, Paint p, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s.substring(0, n) + "…") > maxW) n--;
        return s.substring(0, n) + "…";
    }
}
