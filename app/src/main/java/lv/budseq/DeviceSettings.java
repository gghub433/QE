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
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", typeOverride);
            o.put("vol", volume);
            o.put("popup", popup);
            o.put("app", app);
            o.put("low", lowBattery);
            prefs(c).edit().putString(address, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
