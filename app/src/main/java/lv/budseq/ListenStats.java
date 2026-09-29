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
 * Music Time: сколько слушаешь музыку — по дням, исполнителям и приложениям.
 * Считает служба (раз в 15 секунд смотрит, играет ли плеер). Хранится 60 дней.
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

    private static final int KEEP_DAYS = 60;
    private static final int KEEP_NAMES = 100;
    private static ListenStats instance;

    public static synchronized ListenStats get(Context c) {
        if (instance == null) instance = new ListenStats(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject days, artists, apps;

    private final Runnable saveTask = new Runnable() {
        public void run() { saveNow(); }
    };

    private ListenStats(Context c) {
        prefs = c.getSharedPreferences("stats", Context.MODE_PRIVATE);
        days = read("days");
        artists = read("artists");
        apps = read("apps");
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

    /** Добавить прослушанное время. pkg и artist могут быть null (без доступа к уведомлениям). */
    public synchronized void add(long seconds, String pkg, String artist) {
        if (seconds <= 0) return;
        try {
            String today = dayKey(Calendar.getInstance());
            days.put(today, days.optLong(today, 0) + seconds);
            if (pkg != null && !pkg.isEmpty()) apps.put(pkg, apps.optLong(pkg, 0) + seconds);
            if (artist != null) {
                String a = artist.trim();
                if (a.length() > 60) a = a.substring(0, 60);
                if (!a.isEmpty()) artists.put(a, artists.optLong(a, 0) + seconds);
            }
        } catch (Exception ignored) {
        }
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 60000);
    }

    public synchronized void saveNow() {
        main.removeCallbacks(saveTask);
        prune();
        prefs.edit()
                .putString("days", days.toString())
                .putString("artists", artists.toString())
                .putString("apps", apps.toString())
                .apply();
    }

    public synchronized void reset() {
        main.removeCallbacks(saveTask);
        days = new JSONObject();
        artists = new JSONObject();
        apps = new JSONObject();
        prefs.edit().clear().apply();
    }

    /** Старые дни и редкие исполнители не копятся бесконечно. */
    private void prune() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -KEEP_DAYS);
        String oldest = dayKey(cal);
        List<String> old = new ArrayList<>();
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.compareTo(oldest) < 0) old.add(k);
        }
        for (String k : old) days.remove(k);
        trim(artists);
        trim(apps);
    }

    private static void trim(JSONObject o) {
        if (o.length() <= KEEP_NAMES) return;
        List<Entry> all = sorted(o);
        for (int i = KEEP_NAMES; i < all.size(); i++) o.remove(all.get(i).name);
    }

    private static List<Entry> sorted(JSONObject o) {
        List<Entry> out = new ArrayList<>();
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
        return days.optLong(dayKey(Calendar.getInstance()), 0);
    }

    /** Последние n дней: [0] — самый старый, [n-1] — сегодня. */
    public synchronized long[] lastDays(int n) {
        long[] out = new long[n];
        Calendar cal = Calendar.getInstance();
        for (int i = n - 1; i >= 0; i--) {
            out[i] = days.optLong(dayKey(cal), 0);
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return out;
    }

    public synchronized List<Entry> topArtists(int n) {
        List<Entry> all = sorted(artists);
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    public synchronized List<Entry> topApps(int n) {
        List<Entry> all = sorted(apps);
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    public synchronized boolean isEmpty() {
        return days.length() == 0;
    }
}
