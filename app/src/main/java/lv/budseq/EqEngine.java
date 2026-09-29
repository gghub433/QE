package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Звуковой движок на DynamicsProcessing (Android 9+):
 *  - эквалайзер на 9 / 15 / 31 полосу;
 *  - «панч»: компрессор только для низких частот;
 *  - выравнивание громкости (мягкий компрессор на всём диапазоне);
 *  - усиление громкости с лимитером, баланс Л/П;
 *  - профили: свои настройки для каждого устройства;
 *  - AutoEQ: коррекция наушников складывается с кривой пользователя (хранится отдельно).
 */
public final class EqEngine {
    private static final String TAG = "EQ";

    public static final float MIN_DB = -12f, MAX_DB = 12f;
    public static final float MAX_BOOST = 12f;
    public static final int[] BAND_COUNTS = {9, 15, 31};

    private static final float[] F9 = {63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    private static final float[] F15 = {25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000};
    private static final float[] F31 = {20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000};

    /** Встроенные пресеты (в 9 полосах; для 15/31 пересчитываются). */
    public static final int[] PRESET_NAMES = {
            R.string.p_flat, R.string.p_hard_bass, R.string.p_bass_max, R.string.p_bass,
            R.string.p_v, R.string.p_vocal, R.string.p_clarity, R.string.p_soft};
    public static final float[] PRESET_PRE = {0, -6, -9, -3, -3, -2, -2, 0};
    public static final float[] PRESET_PUNCH = {0, 0.5f, 0.8f, 0.3f, 0.2f, 0, 0, 0};
    public static final float[][] PRESETS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0},
            {9, 7, 3, 0, -1, 0, 1, 2, 1},
            {12, 9, 4, -1, -2, -1, 1, 2, 0},
            {6, 5, 3, 1, 0, 0, 0, 0, 0},
            {5, 4, 1, -1, -2, -1, 1, 3, 4},
            {-2, -1, 0, 2, 3, 3, 2, 0, -1},
            {0, 0, -1, 0, 1, 2, 3, 3, 2},
            {1, 1, 0, 0, -1, -2, -3, -3, -4},
    };

    private static EqEngine instance;

    public static synchronized EqEngine get(Context ctx) {
        if (instance == null) instance = new EqEngine(ctx.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Map<Integer, DynamicsProcessing> effects = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ---- текущие настройки ----
    private int bands = 9;
    public float[] gains = new float[9];
    public float preamp;       // -12 … 0 dB
    public float punch;        // 0 … 1
    public float boost;        // 0 … 12 dB
    public float balance;      // -1 (лево) … 1 (право)
    /** Добавка к балансу от фокуса машины (CarFocusView). В профиле не сохраняется. */
    public float carBalance;

    /** AutoEQ: коррекция текущих наушников (null — нет) и она же в наших полосах. */
    private AutoEq.Correction corr;
    private float[] corrBands = new float[0];
    public boolean leveling;
    public boolean enabled;

    // ---- глобальные опции ----
    public boolean autoMode;   // включать только с Bluetooth-звуком
    public boolean perDevice;  // свои настройки для каждого устройства
    public String profileKey = "default";
    public String profileName = "";
    public String lastPreset = "";

    public volatile boolean globalOk;

    private final Runnable saveTask = new Runnable() {
        public void run() { saveNow(); }
    };

    private EqEngine(Context ctx) {
        prefs = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE);
        enabled = prefs.getBoolean("on", true);
        autoMode = prefs.getBoolean("auto", false);
        perDevice = prefs.getBoolean("perDevice", false);
        bands = sanitizeBands(prefs.getInt("bands", 9));
        profileKey = prefs.getString("profile", "default");
        profileName = prefs.getString("profileName", "");
        gains = new float[bands];
        if (!loadProfile(profileKey)) migrateOld();
    }

    private static int sanitizeBands(int n) {
        return n == 15 || n == 31 ? n : 9;
    }

    /** Перенос настроек из версий 1–3 (ключи g0..g8, pre). */
    private void migrateOld() {
        float[] g = new float[9];
        for (int i = 0; i < 9; i++) g[i] = prefs.getFloat("g" + i, 0f);
        preamp = prefs.getFloat("pre", 0f);
        gains = resample(g, F9, freqs(bands));
    }

    // =====================================================================
    // Частоты
    // =====================================================================

    public int bandCount() { return bands; }

    public static float[] freqs(int n) {
        return n == 15 ? F15 : n == 31 ? F31 : F9;
    }

    public float[] freqs() { return freqs(bands); }

    /** Верхняя граница каждой полосы — среднее геометрическое соседних центров. */
    static float[] cutoffs(float[] f) {
        float[] c = new float[f.length];
        for (int i = 0; i < f.length - 1; i++) c[i] = (float) Math.sqrt(f[i] * f[i + 1]);
        c[f.length - 1] = 22000f;
        return c;
    }

    public static String label(float f) {
        if (f < 1000) {
            return f == Math.floor(f) ? String.valueOf((int) f) : String.format(Locale.US, "%.1f", f);
        }
        float k = f / 1000f;
        return k == Math.floor(k) ? (int) k + "k" : String.format(Locale.US, "%.1fk", k);
    }

    /** Пересчёт кривой на другие частоты (интерполяция по логарифму частоты). */
    public static float[] resample(float[] src, float[] srcF, float[] dstF) {
        if (src.length != srcF.length) {
            float[] fixed = new float[srcF.length];
            System.arraycopy(src, 0, fixed, 0, Math.min(src.length, fixed.length));
            src = fixed;
        }
        float[] out = new float[dstF.length];
        for (int i = 0; i < dstF.length; i++) {
            float f = dstF[i];
            float v;
            if (f <= srcF[0]) {
                v = src[0];
            } else if (f >= srcF[srcF.length - 1]) {
                v = src[src.length - 1];
            } else {
                int j = 0;
                while (srcF[j + 1] < f) j++;
                double t = (Math.log(f) - Math.log(srcF[j])) / (Math.log(srcF[j + 1]) - Math.log(srcF[j]));
                v = (float) (src[j] + (src[j + 1] - src[j]) * t);
            }
            out[i] = Math.round(v * 2f) / 2f;
        }
        return out;
    }

    // =====================================================================
    // Аудио-сессии
    // =====================================================================

    /** session 0 = весь звук телефона; >0 = конкретный плеер. */
    public synchronized boolean attach(int session) {
        if (effects.containsKey(session)) return true;
        try {
            DynamicsProcessing dp = create(session);
            effects.put(session, dp);
            apply(dp);
            if (session == 0) globalOk = true;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "attach failed for session " + session, t);
            if (session == 0) globalOk = false;
            return false;
        }
    }

    public synchronized void detach(int session) {
        DynamicsProcessing dp = effects.remove(session);
        if (dp != null) dp.release();
    }

    public synchronized void releaseAll() {
        for (DynamicsProcessing dp : effects.values()) dp.release();
        effects.clear();
        globalOk = false;
    }

    public synchronized int playerSessions() {
        int n = 0;
        for (int s : effects.keySet()) if (s != 0) n++;
        return n;
    }

    private DynamicsProcessing create(int session) {
        DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,              // каналы
                true, bands,    // эквалайзер
                true, 2,        // многополосный компрессор: бас + остальное
                false, 0,       // post-EQ
                true)           // лимитер
                .build();
        return new DynamicsProcessing(1000, session, cfg);
    }

    /** Пересоздать эффекты (после смены числа полос). */
    private synchronized void rebuildAll() {
        List<Integer> sessions = new ArrayList<>(effects.keySet());
        for (DynamicsProcessing dp : effects.values()) dp.release();
        effects.clear();
        globalOk = false;
        for (int s : sessions) attach(s);
    }

    /** Итоговое усиление полосы: кривая пользователя + коррекция AutoEQ. */
    private float bandGain(int i) {
        float g = gains[i];
        if (corr != null && corr.on && i < corrBands.length) g += corrBands[i];
        return Math.max(-24f, Math.min(24f, g));
    }

    /** Запас под подъёмы коррекции, чтобы не упираться в лимитер (не больше 6 дБ). */
    private float corrHeadroom() {
        if (corr == null || !corr.on) return 0f;
        float m = 0;
        for (float v : corrBands) m = Math.max(m, v);
        return -Math.min(6f, m);
    }

    private void apply(DynamicsProcessing dp) {
        float[] cut = cutoffs(freqs(bands));
        DynamicsProcessing.Eq eq = new DynamicsProcessing.Eq(true, true, bands);
        for (int i = 0; i < bands; i++) {
            eq.setBand(i, new DynamicsProcessing.EqBand(true, cut[i], bandGain(i)));
        }
        dp.setPreEqAllChannelsTo(eq);

        boolean mbcOn = punch > 0.01f || leveling;
        DynamicsProcessing.Mbc mbc = new DynamicsProcessing.Mbc(true, mbcOn, 2);
        mbc.setBand(0, bassBand());
        mbc.setBand(1, restBand());
        dp.setMbcAllChannelsTo(mbc);

        // attack 1 мс, release 60 мс, 10:1, порог -1 dB — защита от хрипа и перегруза
        dp.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f));

        float base = preamp + boost + corrHeadroom();
        float bal = Math.max(-1f, Math.min(1f, balance + carBalance));
        dp.setInputGainbyChannel(0, base + (bal > 0 ? atten(bal) : 0f));
        dp.setInputGainbyChannel(1, base + (bal < 0 ? atten(-bal) : 0f));
        dp.setEnabled(enabled);
    }

    private static float atten(float b) {
        return (float) Math.max(-40.0, 20.0 * Math.log10(Math.max(1e-3, 1.0 - b)));
    }

    /** Низкие частоты (до 150 Гц): «панч» или выравнивание. */
    private DynamicsProcessing.MbcBand bassBand() {
        if (punch > 0.01f) {
            float ratio = 1f + 3f * punch;      // до 4:1
            float threshold = -12f - 18f * punch; // -12 … -30 dB
            float makeup = 2f + 6f * punch;       // +2 … +8 dB
            // атака 15 мс пропускает удар, потом бас «уплотняется»
            return new DynamicsProcessing.MbcBand(true, 150f, 15f, 120f, ratio, threshold, 6f, -90f, 1f, 0f, makeup);
        }
        if (leveling) return levelBand(150f);
        return neutralBand(150f);
    }

    private DynamicsProcessing.MbcBand restBand() {
        return leveling ? levelBand(22000f) : neutralBand(22000f);
    }

    private static DynamicsProcessing.MbcBand levelBand(float cutoff) {
        // медленный компрессор 3:1 + подъём тихого — тихие и громкие треки сближаются
        return new DynamicsProcessing.MbcBand(true, cutoff, 50f, 800f, 3f, -30f, 10f, -90f, 1f, 0f, 9f);
    }

    private static DynamicsProcessing.MbcBand neutralBand(float cutoff) {
        return new DynamicsProcessing.MbcBand(true, cutoff, 3f, 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f);
    }

    private synchronized void applyAll() {
        for (DynamicsProcessing dp : effects.values()) {
            try {
                apply(dp);
            } catch (Throwable t) {
                Log.w(TAG, "apply failed", t);
            }
        }
    }

    // =====================================================================
    // Изменение настроек
    // =====================================================================

    public void setGain(int band, float db) {
        if (band < 0 || band >= bands) return;
        gains[band] = clamp(db);
        float cut = cutoffs(freqs(bands))[band];
        synchronized (this) {
            for (DynamicsProcessing dp : effects.values()) {
                try {
                    dp.setPreEqBandAllChannelsTo(band, new DynamicsProcessing.EqBand(true, cut, bandGain(band)));
                } catch (Throwable t) {
                    Log.w(TAG, "band failed", t);
                }
            }
        }
        lastPreset = "";
        save();
    }

    /** Установить кривую (в любом числе полос — пересчитается). */
    public void setCurve(float[] values, float[] valuesFreqs) {
        float[] g = resample(values, valuesFreqs, freqs(bands));
        for (int i = 0; i < bands; i++) gains[i] = clamp(g[i]);
        applyAll();
        save();
    }

    public void applyBuiltIn(int index, String name) {
        preamp = PRESET_PRE[index];
        punch = PRESET_PUNCH[index];
        setCurve(PRESETS[index], F9);
        lastPreset = name;
        save();
        notifyChanged();
    }

    public void setBandCount(int n) {
        n = sanitizeBands(n);
        if (n == bands) return;
        gains = resample(gains, freqs(bands), freqs(n));
        bands = n;
        corrBands = AutoEq.toBands(corr, freqs(bands));
        rebuildAll();
        save();
        notifyChanged();
    }

    public void setPreamp(float db) {
        preamp = Math.max(-12f, Math.min(0f, db));
        applyAll();
        save();
    }

    public void setPunch(float v) {
        punch = Math.max(0f, Math.min(1f, v));
        applyAll();
        save();
    }

    public void setBoost(float db) {
        boost = Math.max(0f, Math.min(MAX_BOOST, db));
        applyAll();
        save();
    }

    public void setBalance(float b) {
        balance = Math.max(-1f, Math.min(1f, b));
        applyAll();
        save();
    }

    // =====================================================================
    // AutoEQ
    // =====================================================================

    /** Коррекция текущих наушников (null — убрать). В профиль не пишется. */
    public void setCorrection(AutoEq.Correction c) {
        corr = c;
        corrBands = AutoEq.toBands(c, freqs(bands));
        applyAll();
        notifyChanged();
    }

    public AutoEq.Correction correction() {
        return corr;
    }

    /** Коррекция в текущих полосах (для графика); пустой массив — нет коррекции. */
    public float[] correctionBands() {
        return corr != null && corr.on ? corrBands.clone() : new float[0];
    }

    /** Фокус машины: применяется сразу, в профиль не пишется. */
    public void setCarBalance(float b) {
        b = Math.max(-0.9f, Math.min(0.9f, b));
        if (Math.abs(b - carBalance) < 0.001f) return;
        carBalance = b;
        applyAll();
        notifyChanged();
    }

    public void setLeveling(boolean on) {
        leveling = on;
        applyAll();
        save();
    }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        applyAll();
        save();
        notifyChanged();
    }

    public void setAutoMode(boolean on) {
        autoMode = on;
        save();
    }

    public void setPerDevice(boolean on) {
        perDevice = on;
        if (!on) switchProfile("default", "");
        save();
    }

    private static float clamp(float v) {
        return Math.max(MIN_DB, Math.min(MAX_DB, v));
    }

    // =====================================================================
    // Профили устройств
    // =====================================================================

    /** Переключиться на настройки устройства (или телефона). */
    public void switchProfile(String key, String name) {
        if (!perDevice) key = "default";
        profileName = name == null ? "" : name;
        if (key.equals(profileKey)) {
            save();
            return;
        }
        saveNow();
        profileKey = key;
        loadProfile(key); // если профиля ещё нет — текущие настройки станут его началом
        applyAll();
        saveNow();
        notifyChanged();
    }

    private boolean loadProfile(String key) {
        String json = prefs.getString("profile:" + key, null);
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            readSound(o, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void readSound(JSONObject o, boolean withPersonal) throws Exception {
        int b = sanitizeBands(o.optInt("b", 9));
        JSONArray g = o.getJSONArray("g");
        float[] src = new float[g.length()];
        for (int i = 0; i < src.length; i++) src[i] = (float) g.getDouble(i);
        gains = resample(src, freqs(b), freqs(bands));
        preamp = (float) o.optDouble("pre", 0);
        punch = (float) o.optDouble("punch", 0);
        leveling = o.optBoolean("lvl", false);
        if (withPersonal) {
            boost = (float) o.optDouble("boost", 0);
            balance = (float) o.optDouble("bal", 0);
            lastPreset = o.optString("preset", "");
        }
    }

    private JSONObject writeSound(boolean withPersonal) throws Exception {
        JSONObject o = new JSONObject();
        o.put("b", bands);
        JSONArray g = new JSONArray();
        for (float v : gains) g.put((double) v);
        o.put("g", g);
        o.put("pre", (double) preamp);
        o.put("punch", (double) punch);
        o.put("lvl", leveling);
        if (withPersonal) {
            o.put("boost", (double) boost);
            o.put("bal", (double) balance);
            o.put("preset", lastPreset);
        }
        return o;
    }

    private void save() {
        main.removeCallbacks(saveTask);
        main.postDelayed(saveTask, 300);
    }

    private synchronized void saveNow() {
        main.removeCallbacks(saveTask);
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("on", enabled).putBoolean("auto", autoMode).putBoolean("perDevice", perDevice)
                .putInt("bands", bands).putString("profile", profileKey).putString("profileName", profileName);
        try {
            e.putString("profile:" + profileKey, writeSound(true).toString());
        } catch (Exception ex) {
            Log.w(TAG, "save failed", ex);
        }
        e.apply();
    }

    // =====================================================================
    // Свои пресеты + экспорт / импорт
    // =====================================================================

    private JSONObject presets() {
        try {
            return new JSONObject(prefs.getString("presets", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public List<String> presetNames() {
        List<String> out = new ArrayList<>();
        Iterator<String> it = presets().keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    public void savePreset(String name) {
        try {
            JSONObject all = presets();
            all.put(name, writeSound(false));
            prefs.edit().putString("presets", all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save preset failed", e);
        }
    }

    public void loadPreset(String name) {
        try {
            Object v = presets().get(name);
            if (v instanceof JSONArray) { // формат версий 1–3: 9 полос + preamp
                JSONArray arr = (JSONArray) v;
                float[] g = new float[9];
                for (int i = 0; i < 9; i++) g[i] = (float) arr.getDouble(i);
                if (arr.length() > 9) preamp = (float) arr.getDouble(9);
                setCurve(g, F9);
            } else {
                readSound((JSONObject) v, false);
                applyAll();
            }
            lastPreset = name;
            save();
            notifyChanged();
        } catch (Exception e) {
            Log.w(TAG, "load preset failed", e);
        }
    }

    /** Снимок звука (кривая, запас, панч, выравнивание) — для автопресета по приложению. */
    public String soundJson() {
        try {
            return writeSound(false).toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Вернуть звук из снимка. */
    public void loadSoundJson(String json, String preset) {
        try {
            readSound(new JSONObject(json), false);
            lastPreset = preset == null ? "" : preset;
            applyAll();
            save();
            notifyChanged();
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
        }
    }

    public void deletePreset(String name) {
        JSONObject all = presets();
        all.remove(name);
        prefs.edit().putString("presets", all.toString()).apply();
    }

    /** Текст для отправки другу: JSON с кривой. */
    public String exportText(String name) {
        try {
            JSONObject o;
            if (name == null) {
                o = writeSound(false);
                name = "EQ";
            } else {
                Object v = presets().get(name);
                if (v instanceof JSONObject) {
                    o = (JSONObject) v;
                } else { // старый формат: массив 9 полос + preamp
                    JSONArray arr = (JSONArray) v;
                    JSONArray g = new JSONArray();
                    for (int i = 0; i < 9; i++) g.put(arr.getDouble(i));
                    o = new JSONObject();
                    o.put("b", 9);
                    o.put("g", g);
                    o.put("pre", arr.length() > 9 ? arr.getDouble(9) : 0);
                }
            }
            JSONObject out = new JSONObject();
            out.put("app", "EQ");
            out.put("v", 1);
            out.put("name", name);
            out.put("b", o.optInt("b", 9));
            out.put("g", o.getJSONArray("g"));
            out.put("pre", o.optDouble("pre", 0));
            out.put("punch", o.optDouble("punch", 0));
            out.put("lvl", o.optBoolean("lvl", false));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Импорт из текста (можно вставить сообщение целиком). Возвращает имя или null. */
    public String importText(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JSONObject o = new JSONObject(text.substring(a, b + 1));
            JSONArray g = o.getJSONArray("g");
            int n = g.length();
            if (n != 9 && n != 15 && n != 31) return null;
            JSONObject preset = new JSONObject();
            preset.put("b", n);
            JSONArray clean = new JSONArray();
            for (int i = 0; i < n; i++) clean.put(Math.max(MIN_DB, Math.min(MAX_DB, g.getDouble(i))));
            preset.put("g", clean);
            preset.put("pre", Math.max(-12, Math.min(0, o.optDouble("pre", 0))));
            preset.put("punch", Math.max(0, Math.min(1, o.optDouble("punch", 0))));
            preset.put("lvl", o.optBoolean("lvl", false));
            String name = o.optString("name", "").trim();
            if (name.isEmpty()) name = "Import";
            if (name.length() > 40) name = name.substring(0, 40);
            JSONObject all = presets();
            String unique = name;
            for (int k = 2; all.has(unique); k++) unique = name + " (" + k + ")";
            all.put(unique, preset);
            prefs.edit().putString("presets", all.toString()).apply();
            return unique;
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Слушатели (интерфейс обновляется, если настройки сменила служба/плитка)
    // =====================================================================

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    void notifyChanged() {
        main.post(new Runnable() {
            public void run() {
                for (Runnable r : listeners) r.run();
            }
        });
    }
}
