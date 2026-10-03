package lv.budseq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Настройки конкретного устройства (по его адресу). */
public final class DeviceSettings {
    /** -1 = определять автоматически. */
    public int typeOverride = -1;
    /** Громкость при подключении, 0..100; -1 = не менять. */
    public int volume = -1;
    public boolean popup = true;
    /** Пакет приложения, которое открыть при подключении ("" = ничего). */
    public String app = "";
    public int lowBattery = 15;
    /** Машина: точка фокуса звука 0..5 (-1 = выкл), сила фокуса (CarFocusView.MODE_*), руль справа. */
    public int carFocus = -1;
    public int carMode = CarFocusView.MODE_NORMAL;
    public boolean carRhd;
    /** Машина: где стоят динамики (x, y парами, 0..1 по кузову; null — схема по умолчанию). */
    public float[] carSpk;
    /** Машина: проверка каналов — левый и правый перепутаны / магнитола играет моно. */
    public boolean carSwap, carMono;
    /**
     * Звук при подключении: «b:3» (встроенный пресет) / «u:Имя» (свой), "" — не менять,
     * null — сам: машине и FM-трансмиттеру «Басы», остальным не менять.
     */
    public String sound;
    /** Машина «как в магнитоле»: Bass Boost (дБ и до какой частоты), фильтр баса (Гц, 0 — выкл), объёмный звук 0…100. */
    public int carBass, carBassHz = 80, carHp, carSurround;
    /**
     * Задержки «вручную» («+» и «−», как в магнитоле): по каждому месту (0..5) — мс по динамикам,
     * −1 — считать само; null — всё рассчитано.
     */
    public float[][] carDelay = new float[CarFocusView.POINTS][];

    /** Встроенный пресет «Басы» (EqEngine.PRESET_NAMES[3]) — машинам по умолчанию. */
    public static final String CAR_DEFAULT_SOUND = AppPresets.BUILTIN + 3;

    private final String address;

    private DeviceSettings(String address) {
        this.address = address;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("devices", Context.MODE_PRIVATE);
    }

    public static DeviceSettings get(Context c, String address) {
        DeviceSettings s = new DeviceSettings(address);
        String json = prefs(c).getString(address, null);
        if (json != null) {
            try {
                JSONObject o = new JSONObject(json);
                s.typeOverride = o.optInt("type", -1);
                s.volume = o.optInt("vol", -1);
                s.popup = o.optBoolean("popup", true);
                s.app = o.optString("app", "");
                s.lowBattery = o.optInt("low", 15);
                s.carFocus = o.optInt("car", -1);
                s.carMode = o.optInt("carMode", CarFocusView.MODE_NORMAL);
                s.carRhd = o.optBoolean("rhd", false);
                s.carSwap = o.optBoolean("swap", false);
                s.carMono = o.optBoolean("mono", false);
                if (o.has("sound")) s.sound = o.optString("sound", "");
                s.carBass = o.optInt("bass", 0);
                s.carBassHz = o.optInt("bassHz", 80);
                s.carHp = o.optInt("hp", 0);
                s.carSurround = o.optInt("sur", 0);
                org.json.JSONArray spk = o.optJSONArray("spk");
                if (spk != null && spk.length() >= 2 && spk.length() % 2 == 0) {
                    s.carSpk = new float[spk.length()];
                    for (int i = 0; i < spk.length(); i++) s.carSpk[i] = (float) spk.getDouble(i);
                }
                org.json.JSONArray dly = o.optJSONArray("dly");
                if (dly != null) {
                    for (int p = 0; p < Math.min(dly.length(), s.carDelay.length); p++) {
                        org.json.JSONArray a = dly.optJSONArray(p);
                        if (a == null) continue;
                        s.carDelay[p] = new float[a.length()];
                        for (int i = 0; i < a.length(); i++) s.carDelay[p][i] = (float) a.optDouble(i, -1);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    /** Какой звук включить при подключении устройства такого типа ("" — не менять). */
    public String soundFor(int type) {
        if (sound != null) return sound;
        return type == DeviceInfo.T_CAR ? CAR_DEFAULT_SOUND : "";
    }

    /** Задержки «вручную» для места, если они подходят к нынешним динамикам; иначе null. */
    public float[] manualDelays(int point) {
        if (point < 0 || point >= carDelay.length) return null;
        float[] m = carDelay[point];
        return m != null && m.length == speakers().length / 2 ? m : null;
    }

    /** Выставить задержку динамика для места, мс (0…20). */
    public void setManualDelay(int point, int speaker, float ms) {
        if (point < 0 || point >= carDelay.length) return;
        int n = speakers().length / 2;
        if (speaker < 0 || speaker >= n) return;
        float[] m = manualDelays(point);
        if (m == null) {
            m = new float[n];
            java.util.Arrays.fill(m, -1f);
        }
        m[speaker] = Math.max(0f, Math.min(20f, ms));
        carDelay[point] = m;
    }

    /** «Сбросить»: для места снова всё рассчитано. */
    public void resetDelays(int point) {
        if (point >= 0 && point < carDelay.length) carDelay[point] = null;
    }

    /** Динамики машины: свои или схема по умолчанию (4 в дверях + твитеры). */
    public float[] speakers() {
        return carSpk != null ? carSpk.clone() : CarFocusView.LAYOUTS[CarFocusView.DEFAULT_LAYOUT].clone();
    }

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", typeOverride);
            o.put("vol", volume);
            o.put("popup", popup);
            o.put("app", app);
            o.put("low", lowBattery);
            o.put("car", carFocus);
            o.put("carMode", carMode);
            o.put("rhd", carRhd);
            o.put("swap", carSwap);
            o.put("mono", carMono);
            if (sound != null) o.put("sound", sound);
            o.put("bass", carBass);
            o.put("bassHz", carBassHz);
            o.put("hp", carHp);
            o.put("sur", carSurround);
            if (carSpk != null) {
                org.json.JSONArray spk = new org.json.JSONArray();
                for (float v : carSpk) spk.put(Math.round(v * 1000) / 1000.0);
                o.put("spk", spk);
            }
            boolean anyDelay = false;
            org.json.JSONArray dly = new org.json.JSONArray();
            for (float[] m : carDelay) {
                if (m == null) {
                    dly.put(JSONObject.NULL);
                    continue;
                }
                anyDelay = true;
                org.json.JSONArray a = new org.json.JSONArray();
                for (float v : m) a.put(Math.round(v * 100) / 100.0);
                dly.put(a);
            }
            if (anyDelay) o.put("dly", dly);
            prefs(c).edit().putString(address, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
