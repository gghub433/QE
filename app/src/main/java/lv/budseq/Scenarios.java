package lv.budseq;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Автосценарии «когда → что» (Настройки → Сценарии). Живут в службе, работают и при закрытом экране:
 *  - машина подключилась → экран машины крупно + пресет машины;
 *  - наушники и ночь (23:00–07:00) → на 3 дБ тише и мягче верх;
 *  - наушники и играет выбранное приложение для тренировки → больше панча.
 * Ночь и тренировка — слой поверх звука (EqEngine.setScenario), пресеты не трогают.
 */
public final class Scenarios {
    static final float NIGHT_GAIN = -3f, NIGHT_TREBLE = -3f, SPORT_PUNCH = 0.7f;

    public static final class Config {
        public boolean carOn, carOpen = true;
        public String carPreset = "";            // «b:3» / «u:Имя», пусто — не менять
        public boolean nightOn;
        public int nightFrom = 23 * 60, nightTo = 7 * 60;   // минуты от полуночи
        public boolean sportOn;
        public List<String> sportApps = new ArrayList<>();
    }

    private Scenarios() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static Config load(Context c) {
        Config k = new Config();
        try {
            JSONObject o = new JSONObject(prefs(c).getString("scenarios", "{}"));
            JSONObject car = o.optJSONObject("car");
            if (car != null) {
                k.carOn = car.optBoolean("on");
                k.carOpen = car.optBoolean("open", true);
                k.carPreset = car.optString("preset", "");
            }
            JSONObject night = o.optJSONObject("night");
            if (night != null) {
                k.nightOn = night.optBoolean("on");
                k.nightFrom = night.optInt("from", k.nightFrom);
                k.nightTo = night.optInt("to", k.nightTo);
            }
            JSONObject sport = o.optJSONObject("sport");
            if (sport != null) {
                k.sportOn = sport.optBoolean("on");
                JSONArray a = sport.optJSONArray("apps");
                if (a != null) for (int i = 0; i < a.length(); i++) k.sportApps.add(a.getString(i));
            }
        } catch (Exception ignored) {
        }
        return k;
    }

    public static void save(Context c, Config k) {
        try {
            JSONObject o = new JSONObject();
            o.put("car", new JSONObject().put("on", k.carOn).put("open", k.carOpen).put("preset", k.carPreset));
            o.put("night", new JSONObject().put("on", k.nightOn).put("from", k.nightFrom).put("to", k.nightTo));
            JSONArray a = new JSONArray();
            for (String s : k.sportApps) a.put(s);
            o.put("sport", new JSONObject().put("on", k.sportOn).put("apps", a));
            prefs(c).edit().putString("scenarios", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Сколько правил включено — для строки в настройках. */
    public static int enabledCount(Config k) {
        return (k.carOn ? 1 : 0) + (k.nightOn ? 1 : 0) + (k.sportOn ? 1 : 0);
    }

    /** Попадает ли минута суток в окно from…to (окно может переходить через полночь). */
    static boolean inWindow(int minute, int from, int to) {
        if (from == to) return true;
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    // =====================================================================
    // Служба
    // =====================================================================

    /** Ночь и тренировка: пересчитать слой звука. playingPkg — играющий плеер, fgPkg — приложение на экране. */
    public static void evaluate(Context c, DeviceInfo out, String playingPkg, String fgPkg) {
        Config k = load(c);
        boolean phones = out != null && out.isHeadphones();
        Calendar cal = Calendar.getInstance();
        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        boolean night = k.nightOn && phones && inWindow(minute, k.nightFrom, k.nightTo);
        boolean sport = k.sportOn && phones && (playingPkg != null && k.sportApps.contains(playingPkg)
                || fgPkg != null && k.sportApps.contains(fgPkg));
        String label = "";
        if (night) label = c.getString(R.string.scn_night);
        if (sport) label = label.isEmpty() ? c.getString(R.string.scn_sport) : label + " + " + c.getString(R.string.scn_sport);
        EqEngine.get(c).setScenario(night ? NIGHT_GAIN : 0f, night ? NIGHT_TREBLE : 0f, sport ? SPORT_PUNCH : 0f, label);
    }

    /** Машина подключилась: пресет машины и экран машины крупно. */
    public static void onConnected(Context c, DeviceInfo info, NotificationManager nm, String channel, int notifId) {
        if (info == null || info.type != DeviceInfo.T_CAR) return;
        Config k = load(c);
        if (!k.carOn) return;
        if (!k.carPreset.isEmpty()) AppPresets.apply(c, k.carPreset);
        if (!k.carOpen || MainActivity.visible) return;
        Intent open = new Intent(c, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DEVICE);
        open.putExtra(MainActivity.EXTRA_CAR, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // из фона Android открывает экран, только если есть «Поверх других приложений»; иначе — уведомление
        if (Settings.canDrawOverlays(c)) {
            try {
                c.startActivity(open);
                return;
            } catch (Exception ignored) {
            }
        }
        PendingIntent pi = PendingIntent.getActivity(c, 21, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(notifId, new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(c.getString(R.string.scn_car_notif, info.name))
                .setContentText(c.getString(R.string.scn_car_notif_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60 * 1000)
                .build());
    }
}
