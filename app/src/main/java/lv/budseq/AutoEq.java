package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AutoEQ: коррекция АЧХ наушников из открытой базы AutoEq (github.com/jaakkopasanen/AutoEq).
 * Индекс моделей скачивается один раз и хранится в памяти телефона; для выбранной модели берётся
 * GraphicEQ.txt (или ParametricEQ.txt) и пересчитывается в наши полосы.
 * Коррекция хранится отдельно для каждых наушников и складывается с кривой пользователя.
 */
public final class AutoEq {
    private static final String TAG = "AutoEq";
    static final String BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/";
    private static final long INDEX_MAX_AGE = 14L * 24 * 60 * 60 * 1000;

    /** Модель в базе. path — относительный путь из INDEX.md (уже в URL-кодировке). */
    public static final class Entry {
        public final String name, path, source;

        Entry(String name, String path, String source) {
            this.name = name;
            this.path = path;
            this.source = source;
        }
    }

    /** Кривая коррекции: частоты (Гц) и усиление (дБ), нормализована к 0 дБ в середине диапазона. */
    public static final class Correction {
        public String name = "", source = "";
        public float[] f = new float[0], g = new float[0];
        public boolean on = true;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("src", source);
            o.put("on", on);
            JSONArray fa = new JSONArray(), ga = new JSONArray();
            for (int i = 0; i < f.length; i++) {
                fa.put((double) f[i]);
                ga.put(Math.round(g[i] * 10) / 10.0);
            }
            o.put("f", fa);
            o.put("g", ga);
            return o;
        }

        static Correction fromJson(JSONObject o) throws Exception {
            Correction c = new Correction();
            c.name = o.optString("name", "");
            c.source = o.optString("src", "");
            c.on = o.optBoolean("on", true);
            JSONArray fa = o.getJSONArray("f"), ga = o.getJSONArray("g");
            int n = Math.min(fa.length(), ga.length());
            c.f = new float[n];
            c.g = new float[n];
            for (int i = 0; i < n; i++) {
                c.f[i] = (float) fa.getDouble(i);
                c.g[i] = (float) ga.getDouble(i);
            }
            return c;
        }

        /** Самый большой подъём — под него нужен запас громкости. */
        public float maxBoost() {
            float m = 0;
            for (float v : g) m = Math.max(m, v);
            return m;
        }
    }

    public interface IndexCallback {
        void onIndex(List<Entry> all, String error);
    }

    public interface CorrectionCallback {
        void onCorrection(Correction c, String error);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile List<Entry> index;
    private static final Pattern LINE = Pattern.compile("^- \\[(.*?)\\]\\(\\./(.*)\\) by (.*)$");

    private AutoEq() { }

    // =====================================================================
    // Индекс моделей
    // =====================================================================

    private static File indexFile(Context c) {
        return new File(c.getFilesDir(), "autoeq_index.md");
    }

    public static boolean hasIndex(Context c) {
        return index != null || indexFile(c).exists();
    }

    /** Индекс из памяти, из файла или из сети (в фоне). Ответ — в главном потоке. */
    public static void loadIndex(Context ctx, final IndexCallback cb) {
        final List<Entry> ready = index;
        if (ready != null) {
            cb.onIndex(ready, null);
            return;
        }
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                List<Entry> list = null;
                String err = null;
                File f = indexFile(c);
                try {
                    boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < INDEX_MAX_AGE;
                    if (!fresh) {
                        try {
                            byte[] data = download(BASE + "INDEX.md", 8 * 1024 * 1024);
                            FileOutputStream os = new FileOutputStream(f);
                            os.write(data);
                            os.close();
                        } catch (Exception e) {
                            if (!f.exists()) throw e; // без сети берём старый индекс
                            Log.w(TAG, "index refresh failed, using cached", e);
                        }
                    }
                    list = parseIndex(new String(readFile(f), "UTF-8"));
                    index = list;
                } catch (Exception e) {
                    Log.w(TAG, "index failed", e);
                    err = message(e);
                }
                final List<Entry> l = list;
                final String e = err;
                main.post(new Runnable() {
                    public void run() { cb.onIndex(l, e); }
                });
            }
        }, "autoeq-index").start();
    }

    static List<Entry> parseIndex(String md) {
        List<Entry> out = new ArrayList<>();
        for (String line : md.split("\n")) {
            Matcher m = LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String src = m.group(3).trim();
            int on = src.indexOf(" on ");
            if (on > 0) src = src.substring(0, on);
            out.add(new Entry(m.group(1).trim(), m.group(2).trim(), src));
        }
        return out;
    }

    // =====================================================================
    // Поиск модели
    // =====================================================================

    /** «Galaxy Buds2 Pro (A1B2)» → [galaxy, buds, 2, pro]: буквы и цифры разделены, мусор убран. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.toLowerCase(Locale.ROOT);
        // «AirPods Pro — Markus» не бывает, а «Markus's AirPods Pro» — часто: имя владельца отбрасываем
        int own = Math.max(t.indexOf("'s "), t.indexOf("’s "));
        if (own > 0) t = t.substring(own + 3);
        t = t.replaceAll("\\(([0-9a-f]{2,6}|le|ble)\\)", " ")   // (A1B2), (LE)
                .replace("+", " plus ")                              // Buds+ = Buds Plus
                .replaceAll("([a-z])([0-9])", "$1 $2")
                .replaceAll("([0-9])([a-z])", "$1 $2")
                .replaceAll("[^a-z0-9]+", " ");
        for (String w : t.trim().split(" ")) {
            if (w.isEmpty() || w.equals("the") || w.equals("my") || w.equals("le")) continue;
            out.add(w);
        }
        return out;
    }

    private static int sourceRank(String src) {
        String s = src.toLowerCase(Locale.ROOT);
        if (s.startsWith("oratory1990")) return 0;
        if (s.startsWith("crinacle")) return 1;
        if (s.startsWith("rtings")) return 2;
        return 3;
    }

    /** Совпадение: доля слов запроса, найденных в названии модели (0..1). */
    static float score(List<String> query, List<String> name) {
        if (query.isEmpty()) return 0;
        int hit = 0;
        for (String q : query) if (name.contains(q)) hit++;
        float s = hit / (float) query.size();
        // лишние слова в названии («ANC on», «(transparency mode)») чуть снижают место в списке
        int extra = Math.max(0, name.size() - hit);
        return s - extra * 0.01f;
    }

    public static List<Entry> search(List<Entry> all, String query, int limit) {
        final List<String> q = tokens(query);
        List<Object[]> scored = new ArrayList<>();
        for (Entry e : all) {
            float s = score(q, tokens(e.name));
            if (s >= 0.5f) scored.add(new Object[]{e, s});
        }
        Collections.sort(scored, new Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                int c = Float.compare((Float) b[1], (Float) a[1]);
                if (c != 0) return c;
                return sourceRank(((Entry) a[0]).source) - sourceRank(((Entry) b[0]).source);
            }
        });
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && out.size() < limit; i++) out.add((Entry) scored.get(i)[0]);
        return out;
    }

    /** Лучшая модель для имени Bluetooth-устройства или null, если уверенности нет. */
    public static Entry bestMatch(List<Entry> all, String deviceName) {
        List<String> q = tokens(deviceName);
        if (q.size() < 2) return null;
        List<Entry> found = search(all, deviceName, 1);
        if (found.isEmpty()) return null;
        return score(q, tokens(found.get(0).name)) >= 0.99f ? found.get(0) : null;
    }

    // =====================================================================
    // Кривая коррекции
    // =====================================================================

    public static void fetch(Context ctx, final Entry e, final CorrectionCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                Correction c = null;
                String err = null;
                String last = e.path.substring(e.path.lastIndexOf('/') + 1);
                String base = BASE + e.path + "/" + last;
                try {
                    try {
                        c = parseGraphic(new String(download(base + "%20GraphicEQ.txt", 256 * 1024), "UTF-8"));
                    } catch (Exception ge) {
                        Log.w(TAG, "GraphicEQ failed, trying ParametricEQ", ge);
                        c = parseParametric(new String(download(base + "%20ParametricEQ.txt", 64 * 1024), "UTF-8"));
                    }
                    c.name = e.name;
                    c.source = e.source;
                } catch (Exception ex) {
                    Log.w(TAG, "fetch failed", ex);
                    err = message(ex);
                    c = null;
                }
                final Correction r = c;
                final String er = err;
                main.post(new Runnable() {
                    public void run() { cb.onCorrection(r, er); }
                });
            }
        }, "autoeq-fetch").start();
    }

    /** «GraphicEQ: 20 -7.7; 21 -7.8; …» */
    static Correction parseGraphic(String txt) throws Exception {
        int p = txt.indexOf("GraphicEQ:");
        if (p < 0) throw new Exception("no GraphicEQ");
        String[] pairs = txt.substring(p + 10).split(";");
        List<float[]> pts = new ArrayList<>();
        for (String pair : pairs) {
            String[] fg = pair.trim().split("\\s+");
            if (fg.length < 2) continue;
            try {
                pts.add(new float[]{Float.parseFloat(fg[0]), Float.parseFloat(fg[1])});
            } catch (NumberFormatException ignored) {
            }
        }
        if (pts.size() < 8) throw new Exception("GraphicEQ too short");
        float[] f = new float[pts.size()], g = new float[pts.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = pts.get(i)[0];
            g[i] = pts.get(i)[1];
        }
        return fromCurve(f, g);
    }

    /** «Filter 1: ON PK Fc 146 Hz Gain -3.5 dB Q 0.72» — считаем АЧХ биквадов (RBJ cookbook). */
    static Correction parseParametric(String txt) throws Exception {
        Pattern fp = Pattern.compile("ON\\s+(PK|LSC|HSC|LS|HS)\\s+Fc\\s+([0-9.]+)\\s*Hz\\s+Gain\\s+(-?[0-9.]+)\\s*dB(?:\\s+Q\\s+([0-9.]+))?");
        Matcher m = fp.matcher(txt);
        List<double[]> filters = new ArrayList<>();
        while (m.find()) {
            String t = m.group(1);
            int type = t.equals("PK") ? 0 : t.startsWith("L") ? 1 : 2;
            double q = m.group(4) != null ? Double.parseDouble(m.group(4)) : 0.707;
            filters.add(new double[]{type, Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)), q});
        }
        if (filters.isEmpty()) throw new Exception("no filters");
        float[] grid = grid();
        float[] g = new float[grid.length];
        for (int i = 0; i < grid.length; i++) {
            double db = 0;
            for (double[] fl : filters) db += biquadDb((int) fl[0], fl[1], fl[2], fl[3], grid[i], 48000);
            g[i] = (float) db;
        }
        return fromCurve(grid, g);
    }

    /** Сетка 1/6 октавы от 20 Гц до 20 кГц. */
    static float[] grid() {
        List<Float> l = new ArrayList<>();
        for (double f = 20; f <= 20001; f *= Math.pow(2, 1 / 6.0)) l.add((float) f);
        float[] out = new float[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    static double biquadDb(int type, double fc, double gainDb, double q, double f, double fs) {
        double A = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2 * q);
        double cw = Math.cos(w0);
        double b0, b1, b2, a0, a1, a2;
        if (type == 0) {          // peaking
            b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A;
            a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
        } else {
            double sq = 2 * Math.sqrt(A) * alpha;
            if (type == 1) {      // low shelf
                b0 = A * ((A + 1) - (A - 1) * cw + sq);
                b1 = 2 * A * ((A - 1) - (A + 1) * cw);
                b2 = A * ((A + 1) - (A - 1) * cw - sq);
                a0 = (A + 1) + (A - 1) * cw + sq;
                a1 = -2 * ((A - 1) + (A + 1) * cw);
                a2 = (A + 1) + (A - 1) * cw - sq;
            } else {              // high shelf
                b0 = A * ((A + 1) + (A - 1) * cw + sq);
                b1 = -2 * A * ((A - 1) + (A + 1) * cw);
                b2 = A * ((A + 1) + (A - 1) * cw - sq);
                a0 = (A + 1) - (A - 1) * cw + sq;
                a1 = 2 * ((A - 1) - (A + 1) * cw);
                a2 = (A + 1) - (A - 1) * cw - sq;
            }
        }
        double w = 2 * Math.PI * f / fs;
        double cr = Math.cos(w), ci = -Math.sin(w), c2r = Math.cos(2 * w), c2i = -Math.sin(2 * w);
        double nr = b0 + b1 * cr + b2 * c2r, ni = b1 * ci + b2 * c2i;
        double dr = a0 + a1 * cr + a2 * c2r, di = a1 * ci + a2 * c2i;
        double mag2 = (nr * nr + ni * ni) / (dr * dr + di * di);
        return 10 * Math.log10(mag2);
    }

    /** Нормализация: средний уровень 200 Гц … 4 кГц = 0 дБ, иначе вся кривая уходит вниз. */
    static Correction fromCurve(float[] f, float[] g) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < f.length; i++) {
            if (f[i] >= 200 && f[i] <= 4000) {
                sum += g[i];
                n++;
            }
        }
        float off = n > 0 ? (float) (sum / n) : 0f;
        Correction c = new Correction();
        c.f = f.clone();
        c.g = new float[g.length];
        for (int i = 0; i < g.length; i++) c.g[i] = Math.max(-15f, Math.min(15f, g[i] - off));
        return c;
    }

    /** Значение кривой на частоте x (интерполяция по логарифму частоты). */
    static float sample(float[] f, float[] g, float x) {
        if (f.length == 0) return 0f;
        if (x <= f[0]) return g[0];
        if (x >= f[f.length - 1]) return g[g.length - 1];
        int j = 0;
        while (f[j + 1] < x) j++;
        double t = (Math.log(x) - Math.log(f[j])) / (Math.log(f[j + 1]) - Math.log(f[j]));
        return (float) (g[j] + (g[j + 1] - g[j]) * t);
    }

    /** Коррекция в наших полосах: среднее по ширине каждой полосы (важно для 9 полос по октаве). */
    public static float[] toBands(Correction c, float[] centers) {
        float[] out = new float[centers.length];
        if (c == null || c.f.length == 0) return out;
        for (int i = 0; i < centers.length; i++) {
            double lo = i == 0 ? centers[0] / Math.sqrt(centers[1] / centers[0])
                    : Math.sqrt(centers[i - 1] * centers[i]);
            double hi = i == centers.length - 1 ? centers[i] * Math.sqrt(centers[i] / centers[i - 1])
                    : Math.sqrt(centers[i] * centers[i + 1]);
            hi = Math.min(hi, 20000);
            double sum = 0;
            int k = 8;
            for (int s = 0; s < k; s++) {
                double x = Math.exp(Math.log(lo) + (Math.log(hi) - Math.log(lo)) * (s + 0.5) / k);
                sum += sample(c.f, c.g, (float) x);
            }
            out[i] = (float) (sum / k);
        }
        return out;
    }

    // =====================================================================
    // Хранение: своя коррекция для каждых наушников
    // =====================================================================

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("autoeq", Context.MODE_PRIVATE);
    }

    public static Correction load(Context c, String address) {
        if (address == null) return null;
        String json = prefs(c).getString(address, null);
        if (json == null) return null;
        try {
            return Correction.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context c, String address, Correction corr) {
        if (address == null) return;
        try {
            prefs(c).edit().putString(address, corr.toJson().toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    public static void remove(Context c, String address) {
        if (address != null) prefs(c).edit().remove(address).apply();
    }

    /** Предложение модели при подключении: чтобы не спрашивать одно и то же дважды. */
    public static String suggestion(Context c, String address) {
        return address == null ? null : prefs(c).getString("sug:" + address, null);
    }

    public static void setSuggestion(Context c, String address, Entry e) {
        if (address == null) return;
        SharedPreferences.Editor ed = prefs(c).edit();
        if (e == null) {
            ed.remove("sug:" + address);
        } else {
            ed.putString("sug:" + address, e.name + "\n" + e.path + "\n" + e.source);
        }
        ed.apply();
    }

    public static Entry suggestionEntry(Context c, String address) {
        String s = suggestion(c, address);
        if (s == null) return null;
        String[] p = s.split("\n");
        return p.length == 3 ? new Entry(p[0], p[1], p[2]) : null;
    }

    public static boolean dismissed(Context c, String address) {
        return address != null && prefs(c).getBoolean("no:" + address, false);
    }

    public static void setDismissed(Context c, String address) {
        if (address == null) return;
        prefs(c).edit().putBoolean("no:" + address, true).remove("sug:" + address).apply();
    }

    /** Применить коррекцию текущих наушников (или убрать, если их нет). */
    public static void applyFor(Context c, String address) {
        EqEngine.get(c).setCorrection(load(c, address));
    }

    // =====================================================================
    // Сеть
    // =====================================================================

    static byte[] download(String url, int maxBytes) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "EQ-Android");
        int code = con.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        InputStream in = con.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new Exception("too large");
        }
        in.close();
        con.disconnect();
        return out.toByteArray();
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
