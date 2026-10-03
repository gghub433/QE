package lv.budseq;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.ParcelUuid;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * «Подробно» об устройстве на вкладке «Устройство»: как узнали тип, чем подключено (профили Bluetooth
 * или провод), кодек и формат звука, задержка, громкость шагами и в дБ, заряд с расходом в час и временем
 * до разряда, у наушников — каждый наушник и кейс, у машины — каждый динамик (расстояние и задержка).
 * Чего система не сообщает, того в списке нет.
 */
final class DeviceDetails {

    static final class Row {
        final String label, value;

        Row(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private DeviceDetails() { }

    /** Профиль A2DP держим открытым: кодек спрашивают у него. */
    private static BluetoothA2dp a2dp;
    private static boolean asked;
    /** Система не даёт кодек (Android 13+) или задержку — больше не спрашиваем. */
    private static boolean codecDenied, latencyDenied;

    /** Попросить у системы профиль A2DP (один раз; без разрешения «Устройства поблизости» — позже). */
    static void init(Context c) {
        if (asked) return;
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return;
        try {
            asked = ad.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
                }

                public void onServiceDisconnected(int profile) {
                    if (profile != BluetoothProfile.A2DP) return;
                    a2dp = null;
                    asked = false;   // Bluetooth выключали — попросим снова
                }
            }, BluetoothProfile.A2DP);
        } catch (Exception ignored) {
            // нет разрешения — спросим при следующем показе
        }
    }

    /** Строки для устройства; dev == null — динамик телефона. */
    static List<Row> rows(Context c, DeviceInfo dev) {
        List<Row> out = new ArrayList<>();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        DeviceInfo active = DeviceMonitor.get().primaryAudio();
        boolean playing = dev == null ? active == null : dev == active;
        AudioDeviceInfo port = output(am, dev);

        if (dev == null) {
            add(out, c, R.string.dd_type, c.getString(R.string.phone_speaker));
        } else {
            String type = c.getString(DeviceInfo.labelRes(dev.type));
            int why = dev.type != dev.detectedType ? R.string.dd_by_hand : byRes(dev.detectedBy);
            add(out, c, R.string.dd_type, why != 0 ? type + " · " + c.getString(why) : type);
            add(out, c, R.string.dd_link, link(c, dev, port));
            if (!dev.isWired() && dev.isAudio()) {
                String codec = codec(c, dev.device);
                if (codec != null) add(out, c, R.string.dd_codec, codec);
                String caps = codecCaps(dev.device);
                if (caps != null) add(out, c, R.string.dd_codecs, caps);
            }
        }
        if (port != null && (dev == null || dev.isWired())) {
            String f = format(c, port);
            if (f != null) add(out, c, R.string.dd_format, f);
        }
        if (playing && (dev == null || dev.isAudio())) {
            int lat = latency(am);
            if (lat > 0) add(out, c, R.string.dd_latency, c.getString(R.string.dd_latency_v, lat));
            String vol = volume(c, am, dev, port);
            if (vol != null) add(out, c, R.string.dd_volume, vol);
        }
        if (dev == null) return out;

        // заряд: у Galaxy Buds и AirPods — каждый наушник и кейс
        BudsLink.State bs = budsState(dev);
        if (bs != null) {
            if (bs.batL >= 0 || bs.placeL == BudsLink.P_DISCONNECTED) add(out, c, R.string.dd_left, bud(c, bs.batL, bs.placeL, bs.chgL));
            if (bs.batR >= 0 || bs.placeR == BudsLink.P_DISCONNECTED) add(out, c, R.string.dd_right, bud(c, bs.batR, bs.placeR, bs.chgR));
            if (bs.batCase >= 0) {
                add(out, c, R.string.dd_case, bs.batCase + "%" + (bs.chgCase ? " · " + c.getString(R.string.dd_charging) : ""));
            }
            if (dev.isGalaxyBuds()) {
                if (BudsLink.hasNoiseControl(bs) && bs.noise >= 0) {
                    int nc = bs.noise == BudsLink.NC_ANC ? R.string.nc_anc
                            : bs.noise == BudsLink.NC_AMBIENT ? R.string.nc_ambient : R.string.nc_off;
                    add(out, c, R.string.dd_nc, c.getString(nc));
                }
                if (bs.touchLocked >= 0) {
                    add(out, c, R.string.dd_touch, c.getString(bs.touchLocked == 1 ? R.string.dd_off : R.string.dd_on));
                }
            }
        }
        if (dev.battery >= 0) {
            StringBuilder b = new StringBuilder(dev.battery + "%");
            float drain = dev.drainPerHour();
            int left = dev.minutesLeft();
            if (drain > 0) b.append(" · ").append(c.getString(R.string.dd_drain, drain));
            if (left >= 0) b.append(" · ").append(c.getString(R.string.dd_left_time, duration(c, left)));
            if (drain <= 0) b.append(" · ").append(c.getString(R.string.dd_drain_wait));
            add(out, c, R.string.dd_battery, b.toString());
        } else if (bs == null && dev.isAudio()) {
            add(out, c, R.string.dd_battery, c.getString(R.string.dd_battery_none));
        }

        // сколько подключено и сколько слушали
        long mins = (System.currentTimeMillis() - dev.connectedAt) / 60000L;
        add(out, c, R.string.dd_connected, c.getString(R.string.dd_since, duration(c, (int) mins),
                new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(dev.connectedAt))));
        if (dev.isAudio()) {
            long today = ListenStats.get(c).deviceToday(dev.name) / 60;
            add(out, c, R.string.dd_today, duration(c, (int) today));
            if (dev.isHeadphones()) {
                long all = ListenStats.get(c).headphonesTotal(dev.name) / 60;
                if (all > 0) add(out, c, R.string.dd_total, duration(c, (int) all));
            }
        }

        // наушники: поправка AutoEQ
        if (dev.isHeadphones()) {
            AutoEq.Correction corr = AutoEq.load(c, dev.address);
            if (corr != null && corr.name != null && !corr.name.isEmpty()) {
                add(out, c, R.string.dd_autoeq, corr.name + " · " + c.getString(corr.on ? R.string.dd_on : R.string.dd_off)
                        + " · " + c.getString(R.string.dd_autoeq_max, String.format(Locale.US, "%.1f", corr.maxBoost())));
            }
        }

        if (dev.type == DeviceInfo.T_CAR) carRows(c, out, DeviceSettings.get(c, dev.address));

        if (!dev.isWired()) {
            add(out, c, R.string.dd_address, dev.address);
            String cls = btClass(dev.device);
            if (cls != null) add(out, c, R.string.dd_class, cls);
        }
        return out;
    }

    // =====================================================================
    // Машина: место, баланс и каждый динамик
    // =====================================================================

    private static void carRows(Context c, List<Row> out, DeviceSettings ds) {
        float[] spk = ds.speakers();
        int n = spk.length / 2;
        String seat = ds.carFocus < 0 ? c.getString(R.string.off)
                : c.getString(CarFocusView.labelRes(ds.carFocus, ds.carRhd)) + " · " + c.getString(
                ds.carMode == CarFocusView.MODE_SOFT ? R.string.car_soft
                        : ds.carMode == CarFocusView.MODE_STRONG ? R.string.car_strong : R.string.car_normal);
        add(out, c, R.string.dd_car_focus, seat);
        float bal = CarFocusView.balanceFor(ds.carFocus, ds.carMode, spk, ds.carSwap, ds.carMono);
        if (Math.abs(bal) > 0.001f) {
            String db = String.format(Locale.US, "%.1f", -CarFocusView.balanceDb(bal));
            add(out, c, R.string.dd_car_balance, c.getString(bal > 0 ? R.string.dd_car_quiet_l : R.string.dd_car_quiet_r, db));
        }
        StringBuilder flags = new StringBuilder();
        if (ds.carRhd) flags.append(c.getString(R.string.car_rhd));
        if (ds.carSwap) flags.append(flags.length() > 0 ? " · " : "").append(c.getString(R.string.dd_car_swap));
        if (ds.carMono) flags.append(flags.length() > 0 ? " · " : "").append(c.getString(R.string.dd_car_mono));
        if (flags.length() > 0) add(out, c, R.string.dd_car_flags, flags.toString());

        StringBuilder snd = new StringBuilder();
        snd.append(ds.carBass > 0 ? String.format(Locale.US, "Bass Boost +%.1f dB · ", ds.carBass)
                + c.getString(R.string.car_hz, ds.carBassHz) : "Bass Boost " + c.getString(R.string.off));
        snd.append('\n').append(c.getString(R.string.car_tab_filter)).append(' ')
                .append(ds.carHp > 0 ? c.getString(R.string.car_hz, ds.carHp) : c.getString(R.string.off));
        snd.append('\n').append(c.getString(R.string.car_tab_surround)).append(' ')
                .append(ds.carSurround > 0 ? ds.carSurround + "%" : c.getString(R.string.off));
        add(out, c, R.string.dd_car_sound, snd.toString());
        if (ds.volume >= 0) add(out, c, R.string.ds_volume, ds.volume + "%");

        add(out, c, R.string.dd_car_speakers, String.valueOf(n));
        float[] dist = CarFocusView.distancesM(ds.carFocus, spk);
        float[] auto = CarFocusView.delaysMs(ds.carFocus, spk);
        float[] manual = ds.carFocus >= 0 ? ds.manualDelays(ds.carFocus) : null;
        for (int i = 0; i < n; i++) {
            String name = c.getString(CarFocusView.speakerName(spk[i * 2], spk[i * 2 + 1]));
            if (dist == null) {
                add(out, name, c.getString(R.string.dd_spk_nofocus));
                continue;
            }
            boolean hand = manual != null && manual.length == n && manual[i] >= 0;
            float ms = hand ? manual[i] : auto[i];
            String v = c.getString(R.string.dd_spk, dist[i],
                    c.getString(R.string.car_ms, ms), c.getString(R.string.car_cm, CarFocusView.delayCm(ms)));
            add(out, name, hand ? v + " · " + c.getString(R.string.dd_by_hand_short) : v);
        }
    }

    // =====================================================================
    // Подключение, кодек, формат
    // =====================================================================

    private static final String U_A2DP = "0000110b", U_AVRCP = "0000110e", U_AVRCP_T = "0000110c",
            U_HFP = "0000111e", U_HSP = "00001108", U_HID = "00001124", U_HOGP = "00001812",
            U_ASCS = "0000184e", U_PACS = "00001850", U_ASHA = "0000fdf0";

    private static String link(Context c, DeviceInfo dev, AudioDeviceInfo port) {
        if (dev.isWired()) {
            String s = c.getString(R.string.dd_wired) + ": " + dev.name;
            if (port != null && port.getProductName() != null) {
                String pn = port.getProductName().toString().trim();
                if (!pn.isEmpty() && !pn.equalsIgnoreCase(dev.name) && !pn.equalsIgnoreCase(android.os.Build.MODEL)) {
                    s += " (" + pn + ")";
                }
            }
            return s;
        }
        List<String> p = new ArrayList<>();
        try {
            ParcelUuid[] uu = dev.device != null ? dev.device.getUuids() : null;
            if (uu != null) {
                boolean music = false, calls = false, keys = false, le = false, input = false, aid = false;
                for (ParcelUuid u : uu) {
                    String s = u.toString().toLowerCase(Locale.ROOT);
                    if (s.startsWith(U_A2DP)) music = true;
                    else if (s.startsWith(U_HFP) || s.startsWith(U_HSP)) calls = true;
                    else if (s.startsWith(U_AVRCP) || s.startsWith(U_AVRCP_T)) keys = true;
                    else if (s.startsWith(U_ASCS) || s.startsWith(U_PACS)) le = true;
                    else if (s.startsWith(U_HID) || s.startsWith(U_HOGP)) input = true;
                    else if (s.startsWith(U_ASHA)) aid = true;
                }
                if (music) p.add(c.getString(R.string.dd_p_music));
                if (calls) p.add(c.getString(R.string.dd_p_calls));
                if (keys) p.add(c.getString(R.string.dd_p_keys));
                if (le) p.add("LE Audio");
                if (aid) p.add(c.getString(R.string.dd_p_aid));
                if (input) p.add(c.getString(R.string.dd_p_input));
            }
        } catch (SecurityException ignored) {
        }
        StringBuilder sb = new StringBuilder("Bluetooth");
        for (int i = 0; i < p.size(); i++) sb.append(i == 0 ? ": " : ", ").append(p.get(i));
        return sb.toString();
    }

    /** Кодек сейчас: «LDAC · 96 кГц / 24 бит · стерео · 990 кбит/с». null — система не сообщает. */
    private static String codec(Context c, BluetoothDevice d) {
        Object cfg = codecConfig(d);
        if (cfg == null) return null;
        try {
            int type = (Integer) call(cfg, "getCodecType");
            String name = codecName(type);
            if (name == null) return null;
            StringBuilder sb = new StringBuilder(name);
            int rate = rateHz((Integer) call(cfg, "getSampleRate"));
            int bits = bits((Integer) call(cfg, "getBitsPerSample"));
            if (rate > 0) {
                sb.append(" · ").append(khz(rate)).append(' ').append(c.getString(R.string.dd_khz));
                if (bits > 0) sb.append(" / ").append(c.getString(R.string.dd_bits, bits));
            }
            int ch = (Integer) call(cfg, "getChannelMode");
            if (ch == 1) sb.append(" · ").append(c.getString(R.string.dd_mono));
            else if (ch == 2) sb.append(" · ").append(c.getString(R.string.dd_stereo));
            if (type == 4) {   // LDAC: качество в codecSpecific1
                long q = (Long) call(cfg, "getCodecSpecific1");
                if (q == 1000) sb.append(" · ").append(c.getString(R.string.dd_kbps, 990));
                else if (q == 1001) sb.append(" · ").append(c.getString(R.string.dd_kbps, 660));
                else if (q == 1002) sb.append(" · ").append(c.getString(R.string.dd_kbps, 330));
                else if (q == 1003) sb.append(" · ").append(c.getString(R.string.dd_abr));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Какие кодеки умеют эти наушники вместе с телефоном: «SBC, AAC, LDAC». */
    private static String codecCaps(BluetoothDevice d) {
        Object st = codecStatus(d);
        if (st == null) return null;
        try {
            Object caps = call(st, "getCodecsSelectableCapabilities");
            List<Object> list = new ArrayList<>();
            if (caps instanceof Object[]) {
                for (Object o : (Object[]) caps) list.add(o);
            } else if (caps instanceof List) {
                list.addAll((List<?>) caps);
            }
            List<String> names = new ArrayList<>();
            for (Object o : list) {
                String n = codecName((Integer) call(o, "getCodecType"));
                if (n != null && !names.contains(n)) names.add(n);
            }
            StringBuilder sb = new StringBuilder();
            for (String n : names) sb.append(sb.length() > 0 ? ", " : "").append(n);
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object codecStatus(BluetoothDevice d) {
        if (a2dp == null || d == null || codecDenied) return null;
        try {
            Method m = BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class);
            return m.invoke(a2dp, d);
        } catch (Throwable t) {
            codecDenied = true;   // Android 13+ отдаёт кодек только системным приложениям
            return null;
        }
    }

    private static Object codecConfig(BluetoothDevice d) {
        Object st = codecStatus(d);
        if (st == null) return null;
        try {
            return call(st, "getCodecConfig");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object call(Object o, String method) throws Exception {
        return o.getClass().getMethod(method).invoke(o);
    }

    static String codecName(int type) {
        switch (type) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX HD";
            case 4: return "LDAC";
            case 5: return "LC3";
            case 6: return "Opus";
            default: return null;
        }
    }

    static int rateHz(int flag) {
        switch (flag) {
            case 0x1: return 44100;
            case 0x2: return 48000;
            case 0x4: return 88200;
            case 0x8: return 96000;
            case 0x10: return 176400;
            case 0x20: return 192000;
            default: return 0;
        }
    }

    static int bits(int flag) {
        switch (flag) {
            case 0x1: return 16;
            case 0x2: return 24;
            case 0x4: return 32;
            default: return 0;
        }
    }

    /** 44100 → «44.1», 48000 → «48». */
    static String khz(int hz) {
        return hz % 1000 == 0 ? String.valueOf(hz / 1000) : String.format(Locale.US, "%.1f", hz / 1000f);
    }

    /** Провод и динамик телефона: «до 192 кГц · 24 бит · 2 канала»; null — формат любой (не сообщает). */
    static String format(Context c, AudioDeviceInfo a) {
        List<String> parts = new ArrayList<>();
        int maxRate = 0;
        for (int r : a.getSampleRates()) maxRate = Math.max(maxRate, r);
        if (maxRate > 0) parts.add(c.getString(R.string.dd_upto, khz(maxRate) + " " + c.getString(R.string.dd_khz)));
        int bits = 0;
        boolean flt = false;
        for (int e : a.getEncodings()) {
            if (e == AudioFormat.ENCODING_PCM_16BIT) bits = Math.max(bits, 16);
            else if (e == 21) bits = Math.max(bits, 24);   // ENCODING_PCM_24BIT_PACKED (Android 12)
            else if (e == 22) bits = Math.max(bits, 32);   // ENCODING_PCM_32BIT (Android 12)
            else if (e == AudioFormat.ENCODING_PCM_FLOAT) flt = true;
        }
        if (bits > 0) parts.add(c.getString(R.string.dd_bits, bits));
        else if (flt) parts.add(c.getString(R.string.dd_bits, 32) + " float");
        int ch = 0;
        for (int n : a.getChannelCounts()) ch = Math.max(ch, n);
        if (ch == 1) parts.add(c.getString(R.string.dd_mono));
        else if (ch == 2) parts.add(c.getString(R.string.dd_stereo));
        else if (ch > 2) parts.add(c.getString(R.string.dd_channels, ch));
        if (parts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(sb.length() > 0 ? " · " : "").append(p);
        return sb.toString();
    }

    /** Выход системы для устройства: Bluetooth — по адресу, провод — по id, null — динамик телефона. */
    private static AudioDeviceInfo output(AudioManager am, DeviceInfo dev) {
        if (am == null) return null;
        try {
            for (AudioDeviceInfo a : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = a.getType();
                if (dev == null) {
                    if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return a;
                } else if (dev.isWired()) {
                    if (a.getId() == dev.outputId) return a;
                } else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && dev.address.equalsIgnoreCase(a.getAddress())) {
                    return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Задержка вывода музыки, мс (с Bluetooth — вместе с передачей); 0 — неизвестно. */
    private static int latency(AudioManager am) {
        if (am == null || latencyDenied) return 0;
        try {
            Object v = AudioManager.class.getMethod("getOutputLatency", int.class).invoke(am, AudioManager.STREAM_MUSIC);
            int ms = v instanceof Integer ? (Integer) v : 0;
            return ms > 0 && ms < 2000 ? ms : 0;
        } catch (Throwable t) {
            latencyDenied = true;
            return 0;
        }
    }

    /** «9 из 15 (60%) · −18.0 дБ». */
    private static String volume(Context c, AudioManager am, DeviceInfo dev, AudioDeviceInfo port) {
        if (am == null) return null;
        try {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return null;
            String s = c.getString(R.string.dd_volume_v, v, max, Math.round(100f * v / max));
            int type = port != null ? port.getType()
                    : dev == null ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
            if (v > 0) {
                float db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, v, type);
                if (!Float.isNaN(db) && !Float.isInfinite(db) && db < -0.05f) {
                    s += " · " + String.format(Locale.US, "%.1f", db).replace('-', '−') + " dB";
                }
            }
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Мелочи
    // =====================================================================

    private static BudsLink.State budsState(DeviceInfo dev) {
        BudsLink.State bs = BudsLink.get().state();
        if (dev.isGalaxyBuds() && bs.connected && dev.address.equals(bs.address)) return bs;
        if (dev.isAirPods()) {
            BudsLink.State as = AirPods.get().state();
            if (as.connected && as.hasBattery()) return as;
        }
        return null;
    }

    private static String bud(Context c, int bat, int place, boolean charging) {
        // «не на связи» и заряжающийся — в кейсе (как на картинке)
        int p = place == BudsLink.P_WEARING ? R.string.dd_in_ear
                : BudsLink.State.out(place, charging) ? R.string.dd_out
                : place == BudsLink.P_CASE ? R.string.dd_in_case_open : R.string.dd_in_case;
        // выключен в закрытом кейсе — заряд не сообщает
        if (place == BudsLink.P_DISCONNECTED) return c.getString(p);
        String s = bat + "%" + " · " + c.getString(p);
        if (charging) s += " · " + c.getString(R.string.dd_charging);
        return s;
    }

    private static int byRes(int by) {
        switch (by) {
            case DeviceInfo.BY_SAMSUNG: return R.string.dd_by_samsung;
            case DeviceInfo.BY_CLASS: return R.string.dd_by_class;
            case DeviceInfo.BY_NAME: return R.string.dd_by_name;
            case DeviceInfo.BY_PORT: return R.string.dd_by_port;
            default: return 0;
        }
    }

    private static String btClass(BluetoothDevice d) {
        if (d == null) return null;
        try {
            BluetoothClass bc = d.getBluetoothClass();
            return bc == null ? null : String.format(Locale.US, "0x%04X", bc.getDeviceClass());
        } catch (SecurityException e) {
            return null;
        }
    }

    static String duration(Context c, int minutes) {
        int m = Math.max(0, minutes);
        return m >= 60 ? c.getString(R.string.time_hm, m / 60, m % 60) : c.getString(R.string.time_m, m);
    }

    private static void add(List<Row> out, Context c, int label, String value) {
        out.add(new Row(c.getString(label), value));
    }

    private static void add(List<Row> out, String label, String value) {
        out.add(new Row(label, value));
    }
}
