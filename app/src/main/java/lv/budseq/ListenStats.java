package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Music Time: сколько слушаешь музыку — по дням, с исполнителями, треками, приложениями,
 * устройствами и пресетами за каждый день (для недели, итогов месяца и карточки для сторис).
 * Считает служба раз в 15 секунд. Дни хранятся 62 дня, общие счётчики — всегда.
 */
public final class ListenStats {

    public static final class Entry {
        public final String name;
        public final long seconds;

        Entry(String name, long seconds) {
            this.name = name;
            this.seconds = seconds;
        }
    }

    /** Сводка за период (неделя / месяц). */
    public static final class Summary {
        public long total, night;
        public int daysWithMusic;
        public List<Entry> artists = new ArrayList<>(), tracks = new ArrayList<>(), apps = new ArrayList<>(),
                devices = new ArrayList<>(), presets = new ArrayList<>();

        public String top(List<Entry> l) {
            return l.isEmpty() ? null : l.get(0).name;
        }
    }

    /** Достижения. */
    public static final int ACH_10H = 0, ACH_100H = 1, ACH_500H = 2, ACH_NIGHT = 3, ACH_LOYAL = 4, ACH_COUNT = 5;

    private static final int KEEP_DAYS = 62;
    private static final int KEEP_PER_DAY = 40;
    private static final int KEEP_NAMES = 100;
    private static final int NIGHT_FROM = 23, NIGHT_TO = 5;
    private static ListenStats instance;

    public static synchronized ListenStats get(Context c) {
        if (instance == null) instance = new ListenStats(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** days2: {"20260929": {"t":сек, "n":ночь, "a":{исполнитель}, "k":{трек}, "p":{приложение}, "d":{устройство}, "e":{пресет}}} */
    private JSONObject days;
    /** За всё время: всего, по приложениям/исполнителям, по наушникам; лучшая ночь. */
    private JSONObject lifeApps, lifeArtists, lifeHeadphones;
    private long life, bestNight;

    private final Runnable saveTask = new Runnable() {
        public void run() { saveNow(); }
    };

    private ListenStats(Context c) {
        prefs = c.getSharedPreferences("stats", Context.MODE_PRIVATE);
        days = read("days2");
        lifeApps = read("apps");
        lifeArtists = read("artists");
        lifeHeadphones = read("hp");
        life = prefs.getLong("life", -1);
        bestNight = prefs.getLong("bestNight", 0);
        if (life < 0) migrate();
    }

    /** Данные 5.0: только итоги по дням — переносим в новый формат. */
    private void migrate() {
        life = 0;
        JSONObject old = read("days");
        Iterator<String> it = old.keys();
        while (it.hasNext()) {
            String k = it.next();
            long t = old.optLong(k, 0);
            life += t;
            try {
                day(k).put("t", day(k).optLong("t", 0) + t);
            } catch (Exception ignored) {
            }
        }
        saveNow();
    }

    private JSONObject read(String key) {
        try {
            return new JSONObject(prefs.getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Ключ дня: 20260929. */
    static String dayKey(Calendar cal) {
        return String.format(Locale.US, "%04d%02d%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private JSONObject day(String key) throws Exception {
        JSONObject d = days.optJSONObject(key);
        if (d == null) {
            d = new JSONObject();
            days.put(key, d);
        }
        return d;
    }

    private static void inc(JSONObject parent, String map, String name, long s) throws Exception {
        if (name == null) return;
        String n = name.trim();
        if (n.isEmpty()) return;
        if (n.length() > 80) n = n.substring(0, 80);
        JSONObject m = parent.optJSONObject(map);
        if (m == null) {
            m = new JSONObject();
            parent.put(map, m);
        }
        m.put(n, m.optLong(n, 0) + s);
    }

    /**
     * Добавить прослушанное время. Всё, кроме seconds, может быть null.
     * headphones — устройство, если это наушники (для «Верен наушникам»).
     */
    public synchronized void add(long seconds, String pkg, String artist, String title, String device,
                                 String preset, String headphones) {
        if (seconds <= 0) return;
        try {
            Calendar now = Calendar.getInstance();
            JSONObject d = day(dayKey(now));
            d.put("t", d.optLong("t", 0) + seconds);
            inc(d, "a", artist, seconds);
            if (title != null && !title.trim().isEmpty()) {
                inc(d, "k", artist != null && !artist.trim().isEmpty() ? artist.trim() + " — " + title.trim() : title, seconds);
            }
            inc(d, "p", pkg, seconds);
            inc(d, "d", device, seconds);
            inc(d, "e", preset, seconds);
            // ночь: с 23:00 до 5:00 засчитываем вечеру, с которого она началась
            int h = now.get(Calendar.HOUR_OF_DAY);
            if (h >= NIGHT_FROM || h < NIGHT_TO) {
                Calendar evening = (Calendar) now.clone();
                if (h < NIGHT_TO) evening.add(Calendar.DAY_OF_YEAR, -1);
                JSONObject e = day(dayKey(evening));
                long n = e.optLong("n", 0) + seconds;
                e.put("n", n);
                bestNight = Math.max(bestNight, n);
            }
            life += seconds;
            if (pkg != null && !pkg.isEmpty()) lifeApps.put(pkg, lifeApps.optLong(pkg, 0) + seconds);
            inc(lifeArtists, artist, seconds);
            if (headphones != null) inc(lifeHeadphones, headphones, seconds);
        } catch (Exception ignored) {
        }
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 60000);
    }

    private static void inc(JSONObject map, String name, long s) throws Exception {
        if (name == null || name.trim().isEmpty()) return;
        String n = name.trim();
        map.put(n, map.optLong(n, 0) + s);
    }

    public synchronized void saveNow() {
        main.removeCallbacks(saveTask);
        prune();
        prefs.edit()
                .putString("days2", days.toString())
                .putString("apps", lifeApps.toString())
                .putString("artists", lifeArtists.toString())
                .putString("hp", lifeHeadphones.toString())
                .putLong("life", life)
                .putLong("bestNight", bestNight)
                .remove("days")
                .apply();
    }

    public synchronized void reset() {
        main.removeCallbacks(saveTask);
        days = new JSONObject();
        lifeApps = new JSONObject();
        lifeArtists = new JSONObject();
        lifeHeadphones = new JSONObject();
        life = 0;
        bestNight = 0;
        prefs.edit().clear().putLong("life", 0).apply();
    }

    /** Старые дни и редкие имена не копятся бесконечно. */
    private void prune() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
        String oldest = dayKey(cal);
        List<String> old = new ArrayList<>();
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.compareTo(oldest) < 0) {
                old.add(k);
                continue;
            }
            JSONObject d = days.optJSONObject(k);
            if (d == null) continue;
            for (String m : new String[]{"a", "k", "p", "d", "e"}) {
                JSONObject map = d.optJSONObject(m);
                if (map != null) trim(map, KEEP_PER_DAY);
            }
        }
        for (String k : old) days.remove(k);
        trim(lifeArtists, KEEP_NAMES);
        trim(lifeApps, KEEP_NAMES);
        trim(lifeHeadphones, KEEP_NAMES);
    }

    private static void trim(JSONObject o, int keep) {
        if (o.length() <= keep) return;
        List<Entry> all = sorted(o);
        for (int i = keep; i < all.size(); i++) o.remove(all.get(i).name);
    }

    private static List<Entry> sorted(JSONObject o) {
        List<Entry> out = new ArrayList<>();
        if (o == null) return out;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.add(new Entry(k, o.optLong(k, 0)));
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) { return Long.compare(b.seconds, a.seconds); }
        });
        return out;
    }

    // ---------- чтение ----------

    public synchronized long today() {
        JSONObject d = days.optJSONObject(dayKey(Calendar.getInstance()));
        return d == null ? 0 : d.optLong("t", 0);
    }

    /** Последние n дней: [0] — самый старый, [n-1] — сегодня. */
    public synchronized long[] lastDays(int n) {
        long[] out = new long[n];
        Calendar cal = Calendar.getInstance();
        for (int i = n - 1; i >= 0; i--) {
            JSONObject d = days.optJSONObject(dayKey(cal));
            out[i] = d == null ? 0 : d.optLong("t", 0);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return out;
    }

    /** Дни текущего месяца: [0] — 1-е число, последний — сегодня. */
    public synchronized long[] monthDays() {
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_MONTH);
        return lastDays(today);
    }

    /** Сводка за последние n дней (неделя = 7) или за текущий месяц (n = 0). */
    public synchronized Summary summary(int n) {
        Calendar cal = Calendar.getInstance();
        int count = n > 0 ? n : cal.get(Calendar.DAY_OF_MONTH);
        JSONObject a = new JSONObject(), k = new JSONObject(), p = new JSONObject(), dv = new JSONObject(),
                e = new JSONObject();
        Summary s = new Summary();
        try {
            for (int i = 0; i < count; i++) {
                JSONObject d = days.optJSONObject(dayKey(cal));
                cal.add(Calendar.DAY_OF_YEAR, -1);
                if (d == null) continue;
                long t = d.optLong("t", 0);
                s.total += t;
                s.night += d.optLong("n", 0);
                if (t >= 60) s.daysWithMusic++;
                merge(a, d.optJSONObject("a"));
                merge(k, d.optJSONObject("k"));
                merge(p, d.optJSONObject("p"));
                merge(dv, d.optJSONObject("d"));
                merge(e, d.optJSONObject("e"));
            }
        } catch (Exception ignored) {
        }
        s.artists = sorted(a);
        s.tracks = sorted(k);
        s.apps = sorted(p);
        s.devices = sorted(dv);
        s.presets = sorted(e);
        return s;
    }

    private static void merge(JSONObject into, JSONObject from) throws Exception {
        if (from == null) return;
        Iterator<String> it = from.keys();
        while (it.hasNext()) {
            String key = it.next();
            into.put(key, into.optLong(key, 0) + from.optLong(key, 0));
        }
    }

    public synchronized List<Entry> topArtists(int n) {
        return head(sorted(lifeArtists), n);
    }

    public synchronized List<Entry> topApps(int n) {
        return head(sorted(lifeApps), n);
    }

    static List<Entry> head(List<Entry> all, int n) {
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    public synchronized long lifetime() {
        return life;
    }

    /** Какие достижения получены: 10/100/500 часов, ночной слушатель, верен одним наушникам. */
    public synchronized boolean[] achievements() {
        boolean[] a = new boolean[ACH_COUNT];
        a[ACH_10H] = life >= 10 * 3600;
        a[ACH_100H] = life >= 100 * 3600;
        a[ACH_500H] = life >= 500 * 3600;
        a[ACH_NIGHT] = bestNight >= 2 * 3600;
        List<Entry> hp = sorted(lifeHeadphones);
        a[ACH_LOYAL] = life >= 10 * 3600 && !hp.isEmpty() && hp.get(0).seconds >= life * 0.9;
        return a;
    }

    /** «1 ч 25 мин» / «42 мин» на языке интерфейса. */
    public static String format(Context c, long s) {
        long h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? c.getString(R.string.time_hm, h, m) : c.getString(R.string.time_m, m);
    }

    /** Только часы — для больших цифр итогов: «23 ч». */
    public static String hours(Context c, long s) {
        return s >= 3600 ? c.getString(R.string.time_h, s / 3600) : format(c, s);
    }

    public synchronized boolean isEmpty() {
        return days.length() == 0 && life == 0;
    }
}
