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
                org.json.JSONArray spk = o.optJSONArray("spk");
                if (spk != null && spk.length() >= 2 && spk.length() % 2 == 0) {
                    s.carSpk = new float[spk.length()];
                    for (int i = 0; i < spk.length(); i++) s.carSpk[i] = (float) spk.getDouble(i);
                }
            } catch (Exception ignored) {
            }
        }
        return s;
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
            if (carSpk != null) {
                org.json.JSONArray spk = new org.json.JSONArray();
                for (float v : carSpk) spk.put(Math.round(v * 1000) / 1000.0);
                o.put("spk", spk);
            }
            prefs(c).edit().putString(address, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
