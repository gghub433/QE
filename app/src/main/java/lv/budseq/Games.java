package lv.budseq;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Игры: звук под игру («Шаги врагов», «Насыщенный», «Голоса»), список игр телефона,
 * игровое время (статистика Android) и какая игра запущена прямо сейчас.
 * Звук накладывается слоем в EqEngine — кривая и пресеты пользователя не меняются.
 */
public final class Games {
    public static final int P_NONE = 0, P_STEPS = 1, P_RICH = 2, P_VOICE = 3;
    public static final int[] PROFILE_NAMES = {R.string.gp_none, R.string.gp_steps, R.string.gp_rich, R.string.gp_voice};
    /** Особые ключи: игра без названия (узнали по звуку USAGE_GAME) и проба звука. */
    public static final String ANY = "@game", TRY = "@try";

    // Профили в своих частотах (дБ). Шаги и перезарядка живут в 2–5 кГц, гул взрывов — ниже 150 Гц.
    static final float[] PF = {32, 64, 125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000, 16000};
    static final float[][] PROFILES = {
            null,
            {-6, -5, -3, -1, 0, 1, 3.5f, 5, 5, 3, 1.5f, -1},          // шаги врагов
            {4.5f, 4, 2.5f, 0.5f, -0.5f, 0, 1, 2, 2.5f, 2.5f, 2, 1.5f},   // насыщенный
            {-4, -3, -1.5f, 0, 1, 2.5f, 3, 2.5f, 1.5f, 0, -1, -2},        // голоса
    };
    static final float[] P_PUNCH = {0, 0, 0.6f, 0};
    /** Выравнивание (компрессор) поднимает тихие звуки — тихие шаги становятся слышнее. */
    static final boolean[] P_LEVEL = {false, true, false, true};

    /** Стрелялки на телефоне: для них по умолчанию «Шаги врагов». */
    private static final Set<String> PHONE_SHOOTERS = new HashSet<>(java.util.Arrays.asList(
            "com.tencent.ig", "com.pubg.krmobile", "com.vng.pubgmobile", "com.rekoo.pubgm", "com.pubg.imobile",
            "com.pubg.newstate", "com.activision.callofduty.shooter", "com.activision.callofduty.warzone",
            "com.dts.freefireth", "com.dts.freefiremax", "com.axlebolt.standoff2",
            "com.criticalforceentertainment.criticalops", "com.epicgames.fortnite", "com.miraclegames.farlight84",
            "com.netease.newspike", "com.proximabeta.mf.uamo"));
    /** Стрелялки в Steam (appid). */
    private static final Set<Integer> STEAM_SHOOTERS = new HashSet<>(java.util.Arrays.asList(
            730, 578080, 1172470, 359550, 1938090, 594650, 252490, 221100, 1517290, 1238810, 1238840, 2357570,
            440, 107410, 393380, 581320, 2073850, 2767030, 1240440, 1144200, 686810, 240, 550, 1422450,
            2507950, 2073620));
    private static final String[] SHOOTER_WORDS = {
            "pubg", "callofduty", "call of duty", "freefire", "free fire", "standoff", "shooter", "fps",
            "strike", "warzone", "battlegrounds", "fortnite", "apex", "rainbow six", "valorant", "tarkov",
            "battlefield", "sniper", "overwatch", "team fortress", "insurgency", "the finals", "halo",
            "ready or not", "hell let loose", "deadlock", "delta force", "arena breakout", "left 4 dead",
            "marvel rivals", "critical ops", "farlight", "blood strike"};

    private Games() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("games", Context.MODE_PRIVATE);
    }

    // =====================================================================
    // Настройки
    // =====================================================================

    public static boolean autoOn(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    public static void setAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
    }

    /** «Насыщенность»: сила игрового звука 0…1. */
    public static float strength(Context c) {
        return prefs(c).getFloat("strength", 1f);
    }

    public static void setStrength(Context c, float s) {
        prefs(c).edit().putFloat("strength", Math.max(0f, Math.min(1f, s))).apply();
    }

    /** Звук для игр, которых EQ не знает по имени (и для стрелялок, если не задано иначе). */
    public static int defaultProfile(Context c) {
        return prefs(c).getInt("default", P_STEPS);
    }

    public static void setDefaultProfile(Context c, int p) {
        prefs(c).edit().putInt("default", p).apply();
    }

    public static boolean isShooter(String key, String name) {
        if (key != null && PHONE_SHOOTERS.contains(key)) return true;
        if (key != null && key.startsWith(Steam.KEY_PREFIX)) {
            try {
                if (STEAM_SHOOTERS.contains(Integer.parseInt(key.substring(Steam.KEY_PREFIX.length())))) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        String s = ((key == null ? "" : key) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
        for (String w : SHOOTER_WORDS) if (s.contains(w)) return true;
        return false;
    }

    /** Звук для игры: выбранный вручную, иначе — стрелялкам шаги, остальным насыщенный. */
    public static int profileFor(Context c, String key, String name) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("profiles", "{}"));
            if (key != null && o.has(key)) return o.getInt(key);
        } catch (Exception ignored) {
        }
        if (ANY.equals(key) || TRY.equals(key)) return defaultProfile(c);
        return isShooter(key, name) ? P_STEPS : P_RICH;
    }

    public static boolean hasOwnProfile(Context c, String key) {
        try {
            return new JSONObject(prefs(c).getString("profiles", "{}")).has(key);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setProfile(Context c, String key, int p) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("profiles", "{}"));
            o.put(key, p);
            prefs(c).edit().putString("profiles", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // Звук
    // =====================================================================

    /** Кривая профиля с учётом «насыщенности». */
    static float[] curve(int profile, float strength) {
        float[] src = PROFILES[profile];
        float[] out = new float[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] * strength;
        return out;
    }

    /** Включить звук профиля (P_NONE — вернуть обычный). */
    public static void applySound(Context c, int profile, String label) {
        EqEngine eq = EqEngine.get(c);
        float s = strength(c);
        if (profile <= P_NONE || profile >= PROFILES.length || s < 0.01f) {
            eq.clearGameSound();
            return;
        }
        eq.setGameSound(curve(profile, s), PF, P_PUNCH[profile] * s, P_LEVEL[profile] && s > 0.3f, label);
    }

    /**
     * Тот же звук для ПК: конфиг Equalizer APO (бесплатный эквалайзер для Windows; его же читает Peace).
     * Вставить в C:\Program Files\EqualizerAPO\config\config.txt.
     */
    public static String apoConfig(int profile, float strength, String title) {
        if (profile <= P_NONE) profile = P_STEPS;
        float[] f = EqEngine.freqs(31);
        float[] v = EqEngine.resample(curve(profile, strength), PF, f);
        float max = 0;
        for (float x : v) max = Math.max(max, x);
        StringBuilder sb = new StringBuilder();
        sb.append("# EQ: ").append(title).append('\n');
        sb.append(String.format(Locale.US, "Preamp: %.1f dB%n", -max - 0.5f));
        sb.append("GraphicEQ: ");
        for (int i = 0; i < f.length; i++) {
            if (i > 0) sb.append("; ");
            sb.append(String.format(Locale.US, "%s %.1f", fmtHz(f[i]), v[i]));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String fmtHz(float f) {
        return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.format(Locale.US, "%.1f", f);
    }

    // =====================================================================
    // Игры телефона
    // =====================================================================

    public static final class PhoneGame {
        public String pkg, label;
        public long weekMs, todayMs;
    }

    /** Игры с ярлыком на рабочем столе: Android-категория «Игры», флаг игры или известная стрелялка + добавленные вручную. */
    public static List<PhoneGame> phoneGames(Context c) {
        return phoneGames(c, true);
    }

    /** withTime — ещё и игровое время (для экрана; службе хватает списка). */
    static List<PhoneGame> phoneGames(Context c, boolean withTime) {
        PackageManager pm = c.getPackageManager();
        Set<String> extra = extraGames(c);
        Map<String, PhoneGame> out = new HashMap<>();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(main, 0);
        } catch (Exception e) {
            list = new ArrayList<>();
        }
        for (ResolveInfo ri : list) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String pkg = ai.packageName;
            if (out.containsKey(pkg) || pkg.equals(c.getPackageName())) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            if (!isGameApp(ai) && !extra.contains(pkg)) continue;
            PhoneGame g = new PhoneGame();
            g.pkg = pkg;
            g.label = label;
            out.put(pkg, g);
        }
        List<PhoneGame> games = new ArrayList<>(out.values());
        if (withTime && hasUsageAccess(c)) {
            Map<String, Long> week = usage(c, 7), today = usage(c, 0);
            for (PhoneGame g : games) {
                Long w = week.get(g.pkg), t = today.get(g.pkg);
                g.weekMs = w != null ? w : 0;
                g.todayMs = t != null ? t : 0;
            }
        }
        Collections.sort(games, new Comparator<PhoneGame>() {
            public int compare(PhoneGame a, PhoneGame b) {
                if (a.weekMs != b.weekMs) return a.weekMs > b.weekMs ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return games;
    }

    @SuppressWarnings("deprecation")
    static boolean isGameApp(ApplicationInfo ai) {
        return ai.category == ApplicationInfo.CATEGORY_GAME
                || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                || PHONE_SHOOTERS.contains(ai.packageName);
    }

    /** Приложения, которые человек сам отметил как игру (Android не всегда знает). */
    public static Set<String> extraGames(Context c) {
        return new HashSet<>(prefs(c).getStringSet("extra", new HashSet<String>()));
    }

    public static void addExtraGame(Context c, String pkg) {
        Set<String> s = extraGames(c);
        s.add(pkg);
        prefs(c).edit().putStringSet("extra", s).apply();
    }

    // =====================================================================
    // Игровое время (статистика использования Android)
    // =====================================================================

    /** Выдан ли «Доступ к истории использования» (Настройки → Специальный доступ). */
    @SuppressWarnings("deprecation")
    public static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Время на экране по приложениям: days = 0 — сегодня, иначе за последние N дней. */
    static Map<String, Long> usage(Context c, int days) {
        Map<String, Long> out = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            long from;
            if (days == 0) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                cal.set(java.util.Calendar.MINUTE, 0);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                from = cal.getTimeInMillis();
            } else {
                from = now - days * 24L * 3600 * 1000;
            }
            Map<String, UsageStats> m = usm.queryAndAggregateUsageStats(from, now);
            for (Map.Entry<String, UsageStats> e : m.entrySet()) {
                out.put(e.getKey(), e.getValue().getTotalTimeInForeground());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // =====================================================================
    // Игра «с ПК» или проба: включается кнопкой «Играть», выключается «Не играть»
    // =====================================================================

    private static final long SESSION_MAX_MS = 6L * 3600 * 1000;   // забыли выключить — через 6 ч само

    public static String sessionKey(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("now_key", null);
        if (k != null && System.currentTimeMillis() - p.getLong("now_start", 0) > SESSION_MAX_MS) {
            stopSession(c);
            return null;
        }
        return k;
    }

    public static String sessionName(Context c) {
        return prefs(c).getString("now_name", "");
    }

    public static void startSession(Context c, String key, String name) {
        String old = sessionKey(c);
        if (old != null && !old.equals(key)) stopSession(c);
        if (key.equals(old)) return;
        prefs(c).edit().putString("now_key", key).putString("now_name", name)
                .putLong("now_start", System.currentTimeMillis()).apply();
    }

    /** Закончить игру; время записываем в «игровое время EQ». */
    public static void stopSession(Context c) {
        SharedPreferences p = prefs(c);
        String k = p.getString("now_key", null);
        if (k == null) return;
        long ms = Math.min(SESSION_MAX_MS, Math.max(0, System.currentTimeMillis() - p.getLong("now_start", 0)));
        SharedPreferences.Editor e = p.edit().remove("now_key").remove("now_name").remove("now_start");
        if (!TRY.equals(k)) {
            try {
                JSONObject o = new JSONObject(p.getString("played", "{}"));
                o.put(k, o.optLong(k, 0) + ms / 1000);
                e.putString("played", o.toString());
            } catch (Exception ignored) {
            }
        }
        e.apply();
    }

    /** Сколько секунд сыграно через кнопку «Играть» в EQ. */
    public static long playedSecs(Context c, String key) {
        try {
            return new JSONObject(prefs(c).getString("played", "{}")).optLong(key, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // =====================================================================
    // Игры ПК не из Steam (Epic, GOG…) — добавленные вручную
    // =====================================================================

    public static final String[] PC_SOURCES = {"Steam", "Epic Games", "GOG", "EA app", "Ubisoft Connect",
            "Battle.net", "Xbox", "Riot"};

    public static final class PcGame {
        public String key, name, source;
        public int coverAppId;   // обложка найдена в магазине Steam (0 — нет)
    }

    public static List<PcGame> pcGames(Context c) {
        List<PcGame> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("pc", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                PcGame g = new PcGame();
                g.name = o.getString("name");
                g.source = o.optString("source", "");
                g.coverAppId = o.optInt("cover", 0);
                g.key = "pc:" + g.name.toLowerCase(Locale.ROOT);
                out.add(g);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void putPcGame(Context c, String name, String source, int coverAppId) {
        name = name.trim();
        if (name.isEmpty()) return;
        List<PcGame> list = pcGames(c);
        JSONArray a = new JSONArray();
        try {
            boolean found = false;
            for (PcGame g : list) {
                JSONObject o = new JSONObject();
                boolean same = g.name.equalsIgnoreCase(name);
                o.put("name", same ? name : g.name);
                o.put("source", same ? source : g.source);
                o.put("cover", same && coverAppId != 0 ? coverAppId : g.coverAppId);
                a.put(o);
                found |= same;
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("source", source);
                o.put("cover", coverAppId);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("pc", a.toString()).apply();
    }

    public static void removePcGame(Context c, String name) {
        JSONArray a = new JSONArray();
        try {
            for (PcGame g : pcGames(c)) {
                if (g.name.equalsIgnoreCase(name)) continue;
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                o.put("source", g.source);
                o.put("cover", g.coverAppId);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("pc", a.toString()).apply();
    }

    /** Откуда запускать игру Steam (можно поменять в окне игры). */
    public static String sourceFor(Context c, String key, String fallback) {
        return prefs(c).getString("src_" + key, fallback);
    }

    public static void setSource(Context c, String key, String source) {
        prefs(c).edit().putString("src_" + key, source).apply();
    }

    // =====================================================================
    // Какая игра идёт сейчас (живёт в службе)
    // =====================================================================

    public static final class Tracker {
        private static final long LINGER_MS = 10000;   // звук игры держим ещё 10 с после паузы
        private static final long GAMES_REFRESH_MS = 10 * 60 * 1000;
        private String appliedKey;
        private int appliedProfile = -1;
        private float appliedStrength = -1;
        private long lastSeen;
        private String fg;
        private long lastQuery;
        private Set<String> gamePkgs = new HashSet<>();
        private final Map<String, String> labels = new HashMap<>();
        private long gamesAt;

        /** gameAudio — сейчас звучит игра (Android пометил звук как USAGE_GAME). */
        public void update(Context c, boolean gameAudio) {
            long now = SystemClock.elapsedRealtime();
            String key = sessionKey(c), name = key != null ? sessionName(c) : null;
            if (key == null && autoOn(c)) {
                String pkg = hasUsageAccess(c) ? foreground(c) : null;
                if (pkg != null && isGamePkg(c, pkg, now)) {
                    key = pkg;
                    name = labels.get(pkg);
                } else if (gameAudio) {
                    key = ANY;
                    name = c.getString(R.string.game_generic);
                }
            }
            if (key != null) {
                lastSeen = now;
                int profile = profileFor(c, key, name);
                float s = strength(c);
                if (key.equals(appliedKey) && profile == appliedProfile && s == appliedStrength) return;
                appliedKey = key;
                appliedProfile = profile;
                appliedStrength = s;
                applySound(c, profile, name);
                return;
            }
            if (appliedKey == null || now - lastSeen < LINGER_MS) return;
            appliedKey = null;
            appliedProfile = -1;
            EqEngine.get(c).clearGameSound();
        }

        /** Сбросить (сменили настройки на вкладке «Игры» — применить заново). */
        public void invalidate() {
            appliedProfile = -1;
            gamesAt = 0;
        }

        private boolean isGamePkg(Context c, String pkg, long now) {
            if (now - gamesAt > GAMES_REFRESH_MS) {
                Set<String> s = new HashSet<>();
                labels.clear();
                for (PhoneGame g : phoneGames(c, false)) {
                    s.add(g.pkg);
                    labels.put(g.pkg, g.label);
                }
                gamePkgs = s;
                gamesAt = now;
            }
            return gamePkgs.contains(pkg);
        }

        /** Приложение на экране: читаем события Android по кусочку с прошлого раза. */
        private String foreground(Context c) {
            try {
                UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                long from = lastQuery == 0 || now - lastQuery > 3600 * 1000 ? now - 3600 * 1000 : lastQuery - 2000;
                UsageEvents ev = usm.queryEvents(from, now);
                UsageEvents.Event e = new UsageEvents.Event();
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    int t = e.getEventType();
                    if (t == 1) {                                   // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                        fg = e.getPackageName();
                    } else if (t == 2 && e.getPackageName().equals(fg)) {   // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
                        fg = null;
                    } else if (t == 16) {                           // SCREEN_NON_INTERACTIVE: экран погас
                        fg = null;
                    }
                }
                lastQuery = now;
            } catch (Exception ignored) {
            }
            return fg;
        }
    }
}
