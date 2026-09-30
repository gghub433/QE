package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Библиотека Steam для вкладки «Игры → ПК»: Steam Web API (ключ человека + его профиль),
 * часы в играх и обложки из CDN Steam. Ключ хранится только в памяти этого телефона.
 * Нужен публичный доступ к «Сведениям об играх» в настройках приватности Steam.
 */
public final class Steam {
    public static final String KEY_PREFIX = "steam:";
    public static final String KEY_PAGE = "https://steamcommunity.com/dev/apikey";
    private static final String API = "https://api.steampowered.com/";
    private static final String[] COVERS = {
            "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/%d/library_600x900.jpg",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/%d/library_600x900.jpg",
            "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/%d/header.jpg",
            "https://cdn.cloudflare.steamstatic.com/steam/apps/%d/header.jpg",
    };
    private static final long MISS_RETRY_MS = 24L * 3600 * 1000;

    public static final class Game {
        public int appid;
        public String name;
        public int minutes, minutes2w;
        public long lastPlayed;   // секунды Unix

        public String key() {
            return KEY_PREFIX + appid;
        }
    }

    public static final class Library {
        public String steamId, name = "";
        public List<Game> games = new ArrayList<>();
        public long fetched;

        public int totalMinutes() {
            int m = 0;
            for (Game g : games) m += g.minutes;
            return m;
        }
    }

    public interface Callback {
        void onResult(Library lib, String error);
    }

    public interface CoverCallback {
        void onCover(Bitmap b);
    }

    public interface FindCallback {
        void onFound(int appid);
    }

    /** Понятная ошибка для экрана. */
    static final class SteamError extends Exception {
        final int res;

        SteamError(int res) {
            super("steam");
            this.res = res;
        }
    }

    private static final ExecutorService NET = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<Integer, Bitmap> MEM = new LruCache<Integer, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(Integer k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private Steam() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("steam", Context.MODE_PRIVATE);
    }

    public static boolean connected(Context c) {
        return !prefs(c).getString("steamid", "").isEmpty() && !prefs(c).getString("key", "").isEmpty();
    }

    public static String profileInput(Context c) {
        return prefs(c).getString("input", "");
    }

    public static void disconnect(Context c) {
        prefs(c).edit().clear().apply();
        libFile(c).delete();
    }

    private static File libFile(Context c) {
        return new File(c.getFilesDir(), "steam_lib.json");
    }

    /** Библиотека из памяти телефона (без сети). */
    public static Library cached(Context c) {
        File f = libFile(c);
        if (!f.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            Library lib = new Library();
            lib.steamId = o.optString("id");
            lib.name = o.optString("name");
            lib.fetched = o.optLong("at");
            lib.games = parseOwned(o.getJSONObject("owned").toString());
            return lib;
        } catch (Exception e) {
            return null;
        }
    }

    /** Подключить: профиль (ссылка, SteamID64 или имя из ссылки) + ключ Web API. */
    public static void connect(final Context ctx, final String input, final String key, final Callback cb) {
        final Context c = ctx.getApplicationContext();
        NET.execute(new Runnable() {
            public void run() {
                try {
                    String k = key.trim();
                    if (!k.matches("[0-9A-Fa-f]{32}")) throw new SteamError(R.string.steam_err_key);
                    String id = steamId64(input);
                    if (id == null) {
                        String vanity = vanity(input);
                        if (vanity == null) throw new SteamError(R.string.steam_err_profile);
                        id = parseVanity(get(API + "ISteamUser/ResolveVanityURL/v1/?key=" + k
                                + "&vanityurl=" + URLEncoder.encode(vanity, "UTF-8")));
                        if (id == null) throw new SteamError(R.string.steam_err_profile);
                    }
                    prefs(c).edit().putString("key", k).putString("steamid", id).putString("input", input.trim()).apply();
                    final Library lib = fetch(c);
                    post(cb, lib, null);
                } catch (SteamError e) {
                    post(cb, null, c.getString(e.res));
                } catch (Exception e) {
                    post(cb, null, c.getString(R.string.steam_err_net));
                }
            }
        });
    }

    /** Обновить часы и список игр. */
    public static void refresh(final Context ctx, final Callback cb) {
        final Context c = ctx.getApplicationContext();
        NET.execute(new Runnable() {
            public void run() {
                try {
                    post(cb, fetch(c), null);
                } catch (SteamError e) {
                    post(cb, null, c.getString(e.res));
                } catch (Exception e) {
                    post(cb, null, c.getString(R.string.steam_err_net));
                }
            }
        });
    }

    private static void post(final Callback cb, final Library lib, final String err) {
        MAIN.post(new Runnable() {
            public void run() { cb.onResult(lib, err); }
        });
    }

    private static Library fetch(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String key = p.getString("key", ""), id = p.getString("steamid", "");
        if (key.isEmpty() || id.isEmpty()) throw new SteamError(R.string.steam_err_profile);
        String owned = get(API + "IPlayerService/GetOwnedGames/v1/?key=" + key + "&steamid=" + id
                + "&include_appinfo=1&include_played_free_games=1&format=json");
        List<Game> games = parseOwned(owned);
        JSONObject resp = new JSONObject(owned).optJSONObject("response");
        if (games.isEmpty() && (resp == null || !resp.has("games"))) {
            throw new SteamError(R.string.steam_err_private);   // профиль или «Сведения об играх» скрыты
        }
        String name = "";
        try {
            name = parsePlayerName(get(API + "ISteamUser/GetPlayerSummaries/v2/?key=" + key + "&steamids=" + id));
        } catch (Exception ignored) {
            // без имени тоже работаем
        }
        Library lib = new Library();
        lib.steamId = id;
        lib.name = name;
        lib.games = games;
        lib.fetched = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("at", lib.fetched);
        o.put("owned", new JSONObject(owned));
        // библиотека бывает большой — в файл, не в настройки
        FileOutputStream os = new FileOutputStream(libFile(c));
        os.write(o.toString().getBytes("UTF-8"));
        os.close();
        return lib;
    }

    // =====================================================================
    // Разбор ответов (отдельно от сети — проверяется тестами)
    // =====================================================================

    /** 17 цифр, начинающихся с 7656 — это SteamID64 (в том числе внутри ссылки /profiles/…). */
    static String steamId64(String input) {
        if (input == null) return null;
        Matcher m = Pattern.compile("(?:^|/profiles/|\\s)(7656\\d{13})(?:\\D|$)").matcher(input.trim());
        return m.find() ? m.group(1) : null;
    }

    /** Имя из ссылки steamcommunity.com/id/<имя> или само имя. */
    static String vanity(String input) {
        if (input == null) return null;
        String s = input.trim();
        Matcher m = Pattern.compile("/id/([A-Za-z0-9_-]{2,32})").matcher(s);
        if (m.find()) return m.group(1);
        return s.matches("[A-Za-z0-9_-]{2,32}") ? s : null;
    }

    static String parseVanity(String json) throws Exception {
        JSONObject r = new JSONObject(json).getJSONObject("response");
        return r.optInt("success") == 1 ? r.optString("steamid", null) : null;
    }

    static String parsePlayerName(String json) throws Exception {
        JSONArray a = new JSONObject(json).getJSONObject("response").getJSONArray("players");
        return a.length() > 0 ? a.getJSONObject(0).optString("personaname", "") : "";
    }

    /** Игры: сначала те, во что играли последние 2 недели, потом по общему времени. */
    static List<Game> parseOwned(String json) throws Exception {
        JSONObject r = new JSONObject(json).optJSONObject("response");
        List<Game> out = new ArrayList<>();
        JSONArray a = r != null ? r.optJSONArray("games") : null;
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Game g = new Game();
            g.appid = o.getInt("appid");
            g.name = o.optString("name", "App " + g.appid);
            g.minutes = o.optInt("playtime_forever", 0);
            g.minutes2w = o.optInt("playtime_2weeks", 0);
            g.lastPlayed = o.optLong("rtime_last_played", 0);
            out.add(g);
        }
        Collections.sort(out, new Comparator<Game>() {
            public int compare(Game x, Game y) {
                if (x.minutes2w != y.minutes2w) return y.minutes2w - x.minutes2w;
                if (x.minutes != y.minutes) return y.minutes - x.minutes;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return out;
    }

    /** Поиск в магазине Steam: appid первой подходящей игры (для обложек игр из Epic, GOG…). */
    static int parseSearch(String json, String name) throws Exception {
        JSONArray items = new JSONObject(json).optJSONArray("items");
        if (items == null || items.length() == 0) return 0;
        String want = norm(name);
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            if (norm(o.optString("name")).equals(want)) return o.optInt("id");
        }
        return items.getJSONObject(0).optInt("id");
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    // =====================================================================
    // Обложки
    // =====================================================================

    public static Bitmap coverNow(int appid) {
        return MEM.get(appid);
    }

    /** Обложка 600×900 (или шапка магазина); кэш в памяти и в cache/steam. null — нет картинки. */
    public static void loadCover(Context ctx, final int appid, final CoverCallback cb) {
        Bitmap m = MEM.get(appid);
        if (m != null) {
            cb.onCover(m);
            return;
        }
        final File dir = new File(ctx.getCacheDir(), "steam");
        NET.execute(new Runnable() {
            public void run() {
                Bitmap b = null;
                try {
                    dir.mkdirs();
                    File f = new File(dir, appid + ".jpg");
                    File miss = new File(dir, appid + ".miss");
                    if (!f.isFile() && !(miss.isFile() && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS)) {
                        byte[] data = null;
                        for (String u : COVERS) {
                            try {
                                data = getBytes(String.format(Locale.US, u, appid));
                                if (data != null && data.length > 1000) break;
                            } catch (Exception ignored) {
                                data = null;
                            }
                        }
                        if (data != null) {
                            FileOutputStream os = new FileOutputStream(f);
                            os.write(data);
                            os.close();
                            miss.delete();
                        } else {
                            new FileOutputStream(miss).close();
                        }
                    }
                    if (f.isFile()) b = decode(f, 360);
                } catch (Exception ignored) {
                }
                final Bitmap res = b;
                if (res != null) MEM.put(appid, res);
                MAIN.post(new Runnable() {
                    public void run() { cb.onCover(res); }
                });
            }
        });
    }

    /** Игра не из Steam: найти её в магазине Steam по названию, чтобы взять обложку. */
    public static void findApp(final String name, final FindCallback cb) {
        NET.execute(new Runnable() {
            public void run() {
                int id = 0;
                try {
                    id = parseSearch(get("https://store.steampowered.com/api/storesearch/?l=english&cc=US&term="
                            + URLEncoder.encode(name, "UTF-8")), name);
                } catch (Exception ignored) {
                }
                final int res = id;
                MAIN.post(new Runnable() {
                    public void run() { cb.onFound(res); }
                });
            }
        });
    }

    private static Bitmap decode(File f, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }

    // =====================================================================
    // Сеть
    // =====================================================================

    private static String get(String url) throws Exception {
        byte[] b = getBytes(url);
        if (b == null) throw new Exception("empty");
        return new String(b, "UTF-8");
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "EQ-Android");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new SteamError(R.string.steam_err_key);
            if (code == 404) return null;
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new Exception("too big");
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
