package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Резервная копия всех настроек EQ в один файл JSON: эквалайзер и свои пресеты, устройства и машины
 * (задержки, Bass Boost…), игры, Music Time, AutoEQ, оформление и настройки.
 * Ключ Steam API в копию не попадает (это пароль) — после восстановления его вводят заново.
 */
final class Backup {
    /** Все файлы настроек приложения. */
    static final String[] PREFS = {"eq", "settings", "devices", "games", "stats", "autoeq", "steam"};

    private Backup() { }

    /** Пароли и ключи — не копируем. */
    private static boolean secret(String file, String key) {
        return "steam".equals(file) && "key".equals(key);
    }

    static String export(Context c, String version) throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "EQ");
        root.put("version", version);
        root.put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()));
        JSONObject all = new JSONObject();
        for (String name : PREFS) {
            SharedPreferences p = c.getSharedPreferences(name, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                if (secret(name, e.getKey())) continue;
                Object v = e.getValue();
                JSONObject item = new JSONObject();
                if (v instanceof Boolean) item.put("t", "b");
                else if (v instanceof Integer) item.put("t", "i");
                else if (v instanceof Long) item.put("t", "l");
                else if (v instanceof Float) item.put("t", "f");
                else if (v instanceof String) item.put("t", "s");
                else if (v instanceof Set) {
                    item.put("t", "set");
                    JSONArray a = new JSONArray();
                    for (Object x : (Set<?>) v) a.put(String.valueOf(x));
                    v = a;
                } else {
                    continue;
                }
                item.put("v", v instanceof Float ? Double.valueOf(((Float) v).doubleValue()) : v);
                o.put(e.getKey(), item);
            }
            all.put(name, o);
        }
        root.put("prefs", all);
        return root.toString(1);
    }

    /** Что в копии — подробно, для окна перед восстановлением. */
    static final class Info {
        String version = "", created = "";
        int devices, presets, games, keys;
    }

    static Info info(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!"EQ".equals(root.optString("app"))) return null;
            JSONObject all = root.getJSONObject("prefs");
            Info i = new Info();
            i.version = root.optString("version");
            i.created = root.optString("created");
            Iterator<String> it = all.keys();
            while (it.hasNext()) i.keys += all.getJSONObject(it.next()).length();
            JSONObject dev = all.optJSONObject("devices");
            if (dev != null) i.devices = dev.length();
            JSONObject eq = all.optJSONObject("eq");
            JSONObject pr = eq != null ? eq.optJSONObject("presets") : null;
            if (pr != null) {
                try {
                    i.presets = new JSONObject(pr.optString("v", "{}")).length();
                } catch (Exception ignored) {
                }
            }
            JSONObject games = all.optJSONObject("games");
            if (games != null) i.games = games.length();
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    /** Восстановить: каждый файл настроек заменяется целиком (ключ Steam остаётся свой). */
    static boolean restore(Context c, String json) {
        try {
            JSONObject all = new JSONObject(json).getJSONObject("prefs");
            for (String name : PREFS) {
                JSONObject o = all.optJSONObject(name);
                if (o == null) continue;
                SharedPreferences p = c.getSharedPreferences(name, Context.MODE_PRIVATE);
                SharedPreferences.Editor ed = p.edit();
                for (String k : p.getAll().keySet()) if (!secret(name, k)) ed.remove(k);
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (secret(name, k)) continue;
                    JSONObject item = o.getJSONObject(k);
                    String t = item.optString("t");
                    if ("b".equals(t)) ed.putBoolean(k, item.getBoolean("v"));
                    else if ("i".equals(t)) ed.putInt(k, item.getInt("v"));
                    else if ("l".equals(t)) ed.putLong(k, item.getLong("v"));
                    else if ("f".equals(t)) ed.putFloat(k, (float) item.getDouble("v"));
                    else if ("s".equals(t)) ed.putString(k, item.getString("v"));
                    else if ("set".equals(t)) {
                        JSONArray a = item.getJSONArray("v");
                        Set<String> set = new HashSet<>();
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                    }
                }
                ed.commit();   // сразу на диск: дальше приложение перезапускается
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
