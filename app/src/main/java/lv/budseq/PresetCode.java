package lv.budseq;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Пресет коротким кодом: EQ-XXXX-XXXX-… (base32) и ссылкой — для QR и «Ввести код».
 * Байты: [версия][число полос][полосы со знаком][панч 0..100][усиление 0..24 (×0,5 дБ)]
 * [баланс -100..100][запас 0..24 (×-0,5 дБ)][флаги: 1 = выравнивание][CRC-8] — опечатку код заметит.
 * Версия 1 — полосы по 0,5 дБ; версия 2 — по 0,1 дБ (только если кривая настроена точнее 0,5 дБ,
 * иначе код как раньше — его поймут и старые версии EQ).
 */
public final class PresetCode {
    public static final String LINK = "https://gghub433.github.io/QE/p/";
    public static final String SCHEME = "eq://preset/";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int VERSION = 1, VERSION_FINE = 2;

    public static final class Preset {
        public int bands;
        public float[] gains;
        public float punch, boost, balance, preamp;
        public boolean leveling;
    }

    private PresetCode() { }

    // =====================================================================
    // Пресет ↔ байты
    // =====================================================================

    static byte[] toBytes(Preset p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        boolean fine = fine(p.gains, p.bands);
        o.write(fine ? VERSION_FINE : VERSION);
        o.write(p.bands);
        for (int i = 0; i < p.bands; i++) {
            o.write((byte) (fine ? clamp(Math.round(p.gains[i] * 10), -120, 120) : clamp(Math.round(p.gains[i] * 2), -48, 48)));
        }
        o.write(clamp(Math.round(p.punch * 100), 0, 100));
        o.write(clamp(Math.round(p.boost * 2), 0, 24));
        o.write((byte) clamp(Math.round(p.balance * 100), -100, 100));
        o.write(clamp(Math.round(-p.preamp * 2), 0, 24));
        o.write(p.leveling ? 1 : 0);
        byte[] b = o.toByteArray();
        byte[] out = new byte[b.length + 1];
        System.arraycopy(b, 0, out, 0, b.length);
        out[b.length] = (byte) crc8(b, b.length);
        return out;
    }

    static Preset fromBytes(byte[] b) {
        if (b == null || b.length < 4 || (b[0] != VERSION && b[0] != VERSION_FINE)) return null;
        float unit = b[0] == VERSION_FINE ? 10f : 2f;
        int bands = b[1] & 0xFF;
        if (bands != 9 && bands != 15 && bands != 31) return null;
        int len = 2 + bands + 5 + 1;
        if (b.length != len || (crc8(b, len - 1) & 0xFF) != (b[len - 1] & 0xFF)) return null;
        Preset p = new Preset();
        p.bands = bands;
        p.gains = new float[bands];
        for (int i = 0; i < bands; i++) p.gains[i] = b[2 + i] / unit;   // байт со знаком
        int k = 2 + bands;
        p.punch = clamp(b[k] & 0xFF, 0, 100) / 100f;
        p.boost = clamp(b[k + 1] & 0xFF, 0, 24) / 2f;
        p.balance = clamp(b[k + 2], -100, 100) / 100f;
        p.preamp = -clamp(b[k + 3] & 0xFF, 0, 24) / 2f;
        p.leveling = (b[k + 4] & 1) != 0;
        return p;
    }

    /** Кривая настроена точнее 0,5 дБ (кнопками «−»/«+» по 0,1 дБ)? */
    private static boolean fine(float[] g, int n) {
        for (int i = 0; i < n; i++) {
            if (Math.abs(g[i] * 2 - Math.round(g[i] * 2)) > 0.01f) return true;
        }
        return false;
    }

    /** CRC-8 (полином 0x07). */
    static int crc8(byte[] b, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= b[i] & 0xFF;
            for (int k = 0; k < 8; k++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // =====================================================================
    // base32 и вид кода
    // =====================================================================

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] fromBase32(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = ALPHABET.indexOf(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                o.write((buf >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return o.toByteArray();
    }

    /** EQ-ABCD-EFGH-… */
    public static String encode(Preset p) {
        String raw = base32(toBytes(p));
        StringBuilder sb = new StringBuilder("EQ");
        for (int i = 0; i < raw.length(); i += 4) sb.append('-').append(raw, i, Math.min(raw.length(), i + 4));
        return sb.toString();
    }

    /** Ссылка для QR: камера телефона откроет её, а EQ перехватит. */
    public static String link(String code) {
        return LINK + code;
    }

    /**
     * Достать пресет из того, что вставили: код, ссылка https://…/p/<код>, eq://preset/<код>
     * или целое сообщение с кодом внутри. null — кода нет или опечатка.
     */
    public static Preset decode(String text) {
        if (text == null) return null;
        String up = text.toUpperCase(Locale.ROOT);
        // «EQ» в начале слова; внутри самого кода тоже бывает «EQ», поэтому пробуем все места
        for (int i = up.indexOf("EQ"); i >= 0; i = up.indexOf("EQ", i + 1)) {
            if (i > 0 && Character.isLetterOrDigit(up.charAt(i - 1))) continue;
            Preset p = parse(up, i + 2);
            if (p != null) return p;
        }
        return parse(up, 0);   // голый код без «EQ»
    }

    private static Preset parse(String up, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < up.length(); i++) {
            char ch = up.charAt(i);
            if (ch == '-' || ch == ' ') continue;
            if (ALPHABET.indexOf(ch) < 0) break;     // конец кода (точка, перевод строки, конец ссылки)
            sb.append(ch);
        }
        // длину знаем из второго байта (число полос) — лишние слова после кода не мешают
        String s = sb.toString();
        if (s.length() < 4) return null;
        byte[] head = fromBase32(s.substring(0, 4));
        int bytes = 2 + (head[1] & 0xFF) + 6;
        int chars = (bytes * 8 + 4) / 5;
        if (s.length() < chars) return null;
        return fromBytes(fromBase32(s.substring(0, chars)));
    }

    // =====================================================================
    // Пресет ↔ эквалайзер
    // =====================================================================

    public static Preset current(EqEngine eq) {
        Preset p = new Preset();
        p.bands = eq.bandCount();
        p.gains = eq.gains.clone();
        p.punch = eq.punch;
        p.boost = eq.boost;
        p.balance = eq.balance;
        p.preamp = eq.preamp;
        p.leveling = eq.leveling;
        return p;
    }

    /** Применить: кривая пересчитается в текущее число полос. */
    public static void apply(EqEngine eq, Preset p) {
        eq.setCurve(p.gains, EqEngine.freqs(p.bands));
        eq.setPunch(p.punch);
        eq.setBoost(p.boost);
        eq.setBalance(p.balance);
        eq.setPreamp(p.preamp);
        eq.setLeveling(p.leveling);
        eq.lastPreset = "";
        eq.notifyChanged();
    }
}
