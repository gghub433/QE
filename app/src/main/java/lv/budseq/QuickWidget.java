package lv.budseq;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Виджет «EQ: быстрые кнопки» (одна строка): EQ вкл/выкл, следующий пресет (встроенные, потом свои)
 * и панч по кругу 0 → 25 → 50 → 75 → 100 %. Точные значения подписаны.
 */
public class QuickWidget extends AppWidgetProvider {

    static final String ACT_POWER = "lv.budseq.quick.POWER";
    static final String ACT_PRESET = "lv.budseq.quick.PRESET";
    static final String ACT_PUNCH = "lv.budseq.quick.PUNCH";
    private static final int[] PUNCH_STEPS = {0, 25, 50, 75, 100};

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        update(c);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        String a = intent.getAction();
        if (a == null) return;
        EqEngine eq = EqEngine.get(c);
        if (ACT_POWER.equals(a)) {
            eq.setEnabled(!eq.enabled);
        } else if (ACT_PRESET.equals(a)) {
            nextPreset(c, eq);
        } else if (ACT_PUNCH.equals(a)) {
            int now = Math.round(eq.punch * 100), next = PUNCH_STEPS[0];
            for (int s : PUNCH_STEPS) {
                if (s > now) {
                    next = s;
                    break;
                }
            }
            eq.setPunch(next / 100f);
            eq.notifyChanged();
        } else {
            return;
        }
        EqTileService.ensureService(c);   // служба применит и обновит уведомление
        update(c);
    }

    /** Все пресеты по порядку: встроенные, потом свои. */
    private static List<String> allPresets(Context c, EqEngine eq) {
        List<String> out = new ArrayList<>();
        for (int r : EqEngine.PRESET_NAMES) out.add(c.getString(r));
        out.addAll(eq.presetNames());
        return out;
    }

    private static void nextPreset(Context ctx, EqEngine eq) {
        Context c = Lang.wrap(ctx.getApplicationContext());
        List<String> all = allPresets(c, eq);
        if (all.isEmpty()) return;
        int i = all.indexOf(eq.lastPreset);
        int next = (i + 1) % all.size();
        if (next < EqEngine.PRESET_NAMES.length) eq.applyBuiltIn(next, all.get(next));
        else eq.loadPreset(all.get(next));
    }

    private static int[] ids(Context c) {
        try {
            return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, QuickWidget.class));
        } catch (Exception e) {
            return new int[0];
        }
    }

    /** Перерисовать (EQ поменялся где угодно — служба зовёт это). */
    public static void update(Context ctx) {
        Context c = Lang.wrap(ctx.getApplicationContext());
        int[] ids = ids(c);
        if (ids.length == 0) return;
        EqEngine eq = EqEngine.get(c);
        int accent = Theme.liveAccent();
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        rv.setTextViewText(R.id.q_power_text, c.getString(eq.enabled ? R.string.qw_on : R.string.qw_off));
        rv.setInt(R.id.q_power, "setBackgroundResource", eq.enabled ? R.drawable.widget_btn_on : R.drawable.widget_btn_bg);
        if (eq.enabled && Build.VERSION.SDK_INT >= 31) {
            // кнопка «включено» — цветом темы (раньше Android 12 остаётся синей из XML)
            rv.setColorStateList(R.id.q_power, "setBackgroundTintList", ColorStateList.valueOf(accent));
        }
        rv.setInt(R.id.q_power_icon, "setColorFilter", Color.WHITE);
        rv.setTextViewText(R.id.q_preset_label, c.getString(R.string.qw_preset));
        rv.setTextViewText(R.id.q_preset_text, eq.lastPreset.isEmpty() ? c.getString(R.string.custom) : eq.lastPreset);
        rv.setTextViewText(R.id.q_punch_label, c.getString(R.string.qw_punch));
        rv.setTextViewText(R.id.q_punch_text, Math.round(eq.punch * 100) + "%");
        rv.setOnClickPendingIntent(R.id.q_power, action(c, ACT_POWER, 31));
        rv.setOnClickPendingIntent(R.id.q_preset, action(c, ACT_PRESET, 32));
        rv.setOnClickPendingIntent(R.id.q_punch, action(c, ACT_PUNCH, 33));
        try {
            AppWidgetManager.getInstance(c).updateAppWidget(ids, rv);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent action(Context c, String act, int code) {
        Intent i = new Intent(c, QuickWidget.class);
        i.setAction(act);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
