package lv.budseq;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Настройки машины коротким кодом EQC-XXXX-… (base32, как у пресетов) и ссылкой для QR —
 * передать другу с такой же машиной: Bass Boost, фильтр баса, объёмный звук, место и сила фокуса,
 * руль, где стоят динамики, задержки «вручную» и громкость при подключении.
 * Байты: [версия=1][флаги: 1 руль справа, 2 каналы перепутаны, 4 моно, 8 Bass Boost по 0,5 дБ][режим фокуса]
 * [место+1][Bass Boost дБ (с флагом 8 — ×2)][до частоты /5][фильтр баса /5][объёмный 0..100][громкость+1]
 * [число динамиков n][по динамику x, y ×250][маска мест с задержками]
 * [по каждому такому месту n задержек ×10 мс, 255 — рассчитать][CRC-8].
 */
public final class CarCode {
    private static final int VERSION = 1;
    private static final int MAX_SPEAKERS = 12;

    /** Что внутри кода (то же, что в DeviceSettings машины). */
    public static final class Car {
        public boolean rhd, swap, mono;
        public int mode, focus, bassHz, hp, surround, volume;
        public float bass;
        public float[] speakers;
        public float[][] delays = new float[CarFocusView.POINTS][];
    }

    private CarCode() { }

    public static Car from(DeviceSettings ds) {
        Car c = new Car();
        c.rhd = ds.carRhd;
        c.swap = ds.carSwap;
        c.mono = ds.carMono;
        c.mode = ds.carMode;
        c.focus = ds.carFocus;
        c.bass = ds.carBass;
        c.bassHz = ds.carBassHz;
        c.hp = ds.carHp;
        c.surround = ds.carSurround;
        c.volume = ds.volume;
        c.speakers = ds.speakers();
        for (int p = 0; p < c.delays.length; p++) c.delays[p] = ds.manualDelays(p);
        return c;
    }

    /** Записать в настройки машины (адрес и имя машины остаются свои). */
    public static void apply(Car c, DeviceSettings ds) {
        ds.carRhd = c.rhd;
        ds.carSwap = c.swap;
        ds.carMono = c.mono;
        ds.carMode = c.mode;
        ds.carFocus = c.focus;
        ds.carBass = c.bass;
        ds.carBassHz = c.bassHz;
        ds.carHp = c.hp;
        ds.carSurround = c.surround;
        ds.volume = c.volume;
        ds.carSpk = c.speakers != null ? c.speakers.clone() : null;
        for (int p = 0; p < ds.carDelay.length; p++) ds.carDelay[p] = c.delays[p] != null ? c.delays[p].clone() : null;
    }

    static byte[] toBytes(Car c) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(VERSION);
        // целые дБ — как раньше (код поймут и старые версии EQ), с половинкой — флаг 8 и ×2
        int half = Math.round(c.bass * 2);
        boolean fine = half % 2 != 0;
        o.write((c.rhd ? 1 : 0) | (c.swap ? 2 : 0) | (c.mono ? 4 : 0) | (fine ? 8 : 0));
        o.write(clamp(c.mode, 0, 2));
        o.write(clamp(c.focus + 1, 0, CarFocusView.POINTS));
        o.write(fine ? clamp(half, 0, 24) : clamp(half / 2, 0, 12));
        o.write(clamp(Math.round(c.bassHz / 5f), 0, 255));
        o.write(clamp(Math.round(c.hp / 5f), 0, 255));
        o.write(clamp(c.surround, 0, 100));
        o.write(clamp(c.volume + 1, 0, 101));
        int n = c.speakers != null ? Math.min(MAX_SPEAKERS, c.speakers.length / 2) : 0;
        o.write(n);
        for (int i = 0; i < n * 2; i++) o.write(clamp(Math.round(c.speakers[i] * 250), 0, 250));
        int mask = 0;
        for (int p = 0; p < c.delays.length; p++) if (c.delays[p] != null && c.delays[p].length == n) mask |= 1 << p;
        o.write(mask);
        for (int p = 0; p < c.delays.length; p++) {
            if ((mask & (1 << p)) == 0) continue;
            for (int i = 0; i < n; i++) {
                float v = c.delays[p][i];
                o.write(v < 0 ? 255 : clamp(Math.round(v * 10), 0, 200));
            }
        }
        byte[] b = o.toByteArray();
        byte[] out = new byte[b.length + 1];
        System.arraycopy(b, 0, out, 0, b.length);
        out[b.length] = (byte) PresetCode.crc8(b, b.length);
        return out;
    }

    /** Сколько байт в коде (по заголовку); -1 — не код машины. */
    private static int length(byte[] b) {
        if (b == null || b.length < 11 || b[0] != VERSION) return -1;
        int n = b[9] & 0xFF;
        if (n < 1 || n > MAX_SPEAKERS) return -1;
        int maskAt = 10 + n * 2;
        if (b.length <= maskAt) return -1;
        int mask = b[maskAt] & 0xFF;
        if (mask >= 1 << CarFocusView.POINTS) return -1;
        return maskAt + 1 + Integer.bitCount(mask) * n + 1;
    }

    static Car fromBytes(byte[] b) {
        int len = length(b);
        if (len < 0 || b.length < len || (PresetCode.crc8(b, len - 1) & 0xFF) != (b[len - 1] & 0xFF)) return null;
        Car c = new Car();
        int f = b[1] & 0xFF;
        c.rhd = (f & 1) != 0;
        c.swap = (f & 2) != 0;
        c.mono = (f & 4) != 0;
        c.mode = clamp(b[2] & 0xFF, 0, 2);
        c.focus = clamp(b[3] & 0xFF, 0, CarFocusView.POINTS) - 1;
        c.bass = (f & 8) != 0 ? clamp(b[4] & 0xFF, 0, 24) / 2f : clamp(b[4] & 0xFF, 0, 12);
        c.bassHz = (b[5] & 0xFF) * 5;
        if (c.bassHz <= 0) c.bassHz = 80;
        c.hp = (b[6] & 0xFF) * 5;
        c.surround = clamp(b[7] & 0xFF, 0, 100);
        c.volume = clamp(b[8] & 0xFF, 0, 101) - 1;
        int n = b[9] & 0xFF;
        c.speakers = new float[n * 2];
        for (int i = 0; i < n * 2; i++) c.speakers[i] = (b[10 + i] & 0xFF) / 250f;
        int k = 10 + n * 2;
        int mask = b[k++] & 0xFF;
        for (int p = 0; p < CarFocusView.POINTS; p++) {
            if ((mask & (1 << p)) == 0) continue;
            c.delays[p] = new float[n];
            for (int i = 0; i < n; i++) {
                int v = b[k++] & 0xFF;
                c.delays[p][i] = v == 255 ? -1f : v / 10f;
            }
        }
        return c;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** EQC-ABCD-EFGH-… */
    public static String encode(Car c) {
        String raw = PresetCode.base32(toBytes(c));
        StringBuilder sb = new StringBuilder("EQC");
        for (int i = 0; i < raw.length(); i += 4) sb.append('-').append(raw, i, Math.min(raw.length(), i + 4));
        return sb.toString();
    }

    /** Код машины в тексте (код, ссылка или целое сообщение); null — нет или опечатка. */
    public static Car decode(String text) {
        if (text == null) return null;
        String up = text.toUpperCase(Locale.ROOT);
        for (int i = up.indexOf("EQC"); i >= 0; i = up.indexOf("EQC", i + 1)) {
            if (i > 0 && Character.isLetterOrDigit(up.charAt(i - 1))) continue;
            StringBuilder sb = new StringBuilder();
            for (int j = i + 3; j < up.length(); j++) {
                char ch = up.charAt(j);
                if (ch == '-' || ch == ' ') continue;
                if (ch < 'A' || ch > 'Z') {
                    if (ch < '2' || ch > '7') break;
                }
                sb.append(ch);
            }
            byte[] b = PresetCode.fromBase32(sb.toString());
            int len = length(b);
            if (len < 0 || b.length < len) continue;
            byte[] exact = new byte[len];
            System.arraycopy(b, 0, exact, 0, len);
            Car c = fromBytes(exact);
            if (c != null) return c;
        }
        return null;
    }
}
