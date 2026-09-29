package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Автопресет по приложению: Spotify — один звук, YouTube — другой, игры — третий.
 * Играющее приложение берём из активной MediaSession (NowPlaying), игры — по типу звука
 * USAGE_GAME (так Android помечает звук игр; разрешений не нужно).
 * Когда приложение перестаёт играть, возвращаем прежний звук — если его не меняли руками.
 */
public final class AppPresets {
    /** Особое «приложение»: любая игра. */
    public static final String GAME = "@game";
    /** Пресеты: «b:3» — встроенный №3, «u:Имя» — свой. */
    public static final String BUILTIN = "b:", USER = "u:";
    private static final long RESTORE_DELAY_MS = 10000;

    public static final class Rule {
        public final String app, preset;

        Rule(String app, String preset) {
            this.app = app;
            this.preset = preset;
        }
    }

    private AppPresets() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean("app_presets_on", true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("app_presets_on", on).apply();
    }

    public static List<Rule> rules(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("app_presets", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Rule(o.getString("app"), o.getString("preset")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Rule> rules) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : rules) {
                JSONObject o = new JSONObject();
                o.put("app", r.app);
                o.put("preset", r.preset);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("app_presets", a.toString()).apply();
    }

    /** Добавить или заменить правило для приложения. */
    public static void put(Context c, String app, String preset) {
        List<Rule> rules = rules(c);
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).app.equals(app)) rules.remove(i);
        }
        rules.add(new Rule(app, preset));
        save(c, rules);
    }

    public static void remove(Context c, String app) {
        List<Rule> rules = rules(c);
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).app.equals(app)) rules.remove(i);
        }
        save(c, rules);
    }

    private static String find(List<Rule> rules, String app) {
        if (app == null) return null;
        for (Rule r : rules) if (r.app.equals(app)) return r.preset;
        return null;
    }

    /** Название пресета для экрана. */
    public static String presetLabel(Context c, String preset) {
        if (preset.startsWith(BUILTIN)) {
            try {
                int i = Integer.parseInt(preset.substring(2));
                if (i >= 0 && i < EqEngine.PRESET_NAMES.length) return c.getString(EqEngine.PRESET_NAMES[i]);
            } catch (NumberFormatException ignored) {
            }
            return preset;
        }
        return preset.startsWith(USER) ? preset.substring(2) : preset;
    }

    /** Применить пресет правила. false — такого пресета больше нет. */
    public static boolean apply(Context c, String preset) {
        EqEngine eq = EqEngine.get(c);
        if (preset.startsWith(BUILTIN)) {
            try {
                int i = Integer.parseInt(preset.substring(2));
                if (i < 0 || i >= EqEngine.PRESETS.length) return false;
                eq.applyBuiltIn(i, c.getString(EqEngine.PRESET_NAMES[i]));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        String name = preset.startsWith(USER) ? preset.substring(2) : preset;
        if (!eq.presetNames().contains(name)) return false;
        eq.loadPreset(name);
        return true;
    }

    /** Играет ли сейчас игра (звук с пометкой USAGE_GAME). */
    public static boolean gameActive(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) return false;
        for (AudioPlaybackConfiguration cfg : configs) {
            AudioAttributes a = cfg.getAudioAttributes();
            if (a != null && a.getUsage() == AudioAttributes.USAGE_GAME) return true;
        }
        return false;
    }

    public static boolean gameActive(AudioManager am) {
        try {
            return gameActive(am.getActivePlaybackConfigurations());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Следит за играющим приложением (живёт в службе).
     * Переключает пресет и возвращает прежний звук через 10 с после того, как приложение замолчало.
     */
    public static final class Tracker {
        private String applied;        // правило, которое сейчас действует
        private String appliedJson;    // звук сразу после применения — чтобы понять, трогал ли его человек
        private String snapshot, snapshotPreset;
        private long lastMatch;

        public void update(Context c, String playingPkg, boolean game) {
            EqEngine eq = EqEngine.get(c);
            List<Rule> rules = enabled(c) ? rules(c) : new ArrayList<Rule>();
            String target = game ? find(rules, GAME) : null;
            if (target == null) target = find(rules, playingPkg);
            long now = SystemClock.elapsedRealtime();

            if (target == null) {
                if (applied == null || now - lastMatch < RESTORE_DELAY_MS) return;
                // вернуть звук, если после автопресета его не меняли руками
                if (snapshot != null && eq.soundJson().equals(appliedJson)) {
                    eq.loadSoundJson(snapshot, snapshotPreset);
                }
                applied = null;
                snapshot = null;
                return;
            }
            lastMatch = now;
            if (target.equals(applied)) return;
            if (applied == null) {
                snapshot = eq.soundJson();
                snapshotPreset = eq.lastPreset;
            }
            if (apply(c, target)) {
                applied = target;
                appliedJson = eq.soundJson();
            }
        }
    }
}
