using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;

namespace EQ
{
    /// <summary>Звук целиком: кривая и обработка. Такой же, как на Android и iPhone.</summary>
    sealed class EqPreset
    {
        public string Name = "";
        public int Bands = 9;
        public float[] Gains = new float[9];
        public float Punch, Boost, Balance, Preamp;
        public bool Leveling;

        public EqPreset Clone()
        {
            var p = (EqPreset)MemberwiseClone();
            p.Gains = (float[])Gains.Clone();
            return p;
        }

        static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

        /// <summary>Одна строка для настроек: имя|полосы|усиления|панч|усиление|баланс|запас|выравнивание.</summary>
        public string Serialize()
        {
            return string.Join("|", Name.Replace("|", "/"), Bands.ToString(Inv),
                string.Join(",", Gains.Select(g => g.ToString("0.##", Inv))),
                Punch.ToString("0.##", Inv), Boost.ToString("0.##", Inv), Balance.ToString("0.##", Inv),
                Preamp.ToString("0.##", Inv), Leveling ? "1" : "0");
        }

        public static EqPreset Parse(string s)
        {
            try
            {
                var f = s.Split('|');
                if (f.Length < 8)
                {
                    return null;
                }
                var p = new EqPreset { Name = f[0], Bands = int.Parse(f[1], Inv) };
                p.Gains = f[2].Split(',').Select(x => float.Parse(x, Inv)).ToArray();
                if (p.Bands != 9 && p.Bands != 15 && p.Bands != 31 || p.Gains.Length != p.Bands)
                {
                    return null;
                }
                p.Punch = float.Parse(f[3], Inv);
                p.Boost = float.Parse(f[4], Inv);
                p.Balance = float.Parse(f[5], Inv);
                p.Preamp = float.Parse(f[6], Inv);
                p.Leveling = f[7] == "1";
                return p;
            }
            catch
            {
                return null;
            }
        }
    }

    static class Presets
    {
        public static readonly float[] F9 = { 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
        public static readonly float[] F15 = { 25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000 };
        public static readonly float[] F31 =
        {
            20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
            800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000,
        };

        public static float[] Freqs(int bands)
        {
            return bands == 15 ? F15 : bands == 31 ? F31 : F9;
        }

        /// <summary>Встроенные пресеты (как на Android): ключ названия, 9 полос, запас, панч.</summary>
        public static readonly (string Key, float[] Gains, float Preamp, float Punch)[] BuiltIn =
        {
            ("p.flat", new float[] { 0, 0, 0, 0, 0, 0, 0, 0, 0 }, 0, 0),
            ("p.hardBass", new float[] { 9, 7, 3, 0, -1, 0, 1, 2, 1 }, -6, 0.5f),
            ("p.bassMax", new float[] { 12, 9, 4, -1, -2, -1, 1, 2, 0 }, -9, 0.8f),
            ("p.bass", new float[] { 6, 5, 3, 1, 0, 0, 0, 0, 0 }, -3, 0.3f),
            ("p.v", new float[] { 5, 4, 1, -1, -2, -1, 1, 3, 4 }, -3, 0.2f),
            ("p.vocal", new float[] { -2, -1, 0, 2, 3, 3, 2, 0, -1 }, -2, 0),
            ("p.clarity", new float[] { 0, 0, -1, 0, 1, 2, 3, 3, 2 }, -2, 0),
            ("p.soft", new float[] { 1, 1, 0, 0, -1, -2, -3, -3, -4 }, 0, 0),
        };

        public static EqPreset FromBuiltIn(int i)
        {
            var b = BuiltIn[i];
            return new EqPreset { Name = Lang.L(b.Key), Bands = 9, Gains = (float[])b.Gains.Clone(), Preamp = b.Preamp, Punch = b.Punch };
        }

        /// <summary>Пересчёт кривой в другие полосы: линейно по логарифму частоты, шаг 0,5 дБ.</summary>
        public static float[] Resample(float[] src, float[] sf, float[] df)
        {
            var r = new float[df.Length];
            if (src == null || src.Length == 0 || src.Length != sf.Length)
            {
                return r;
            }
            for (int k = 0; k < df.Length; k++)
            {
                r[k] = Round05(ValueAt(src, sf, df[k]));
            }
            return r;
        }

        /// <summary>Значение кривой на любой частоте.</summary>
        public static float ValueAt(float[] src, float[] sf, float f)
        {
            if (f <= sf[0])
            {
                return src[0];
            }
            if (f >= sf[sf.Length - 1])
            {
                return src[src.Length - 1];
            }
            int i = 0;
            while (i < sf.Length - 2 && sf[i + 1] < f)
            {
                i++;
            }
            double a = Math.Log(sf[i], 2), b = Math.Log(sf[i + 1], 2), t = (Math.Log(f, 2) - a) / (b - a);
            return (float)(src[i] + (src[i + 1] - src[i]) * t);
        }

        public static float Round05(double v)
        {
            return (float)(Math.Round(v * 2, MidpointRounding.AwayFromZero) / 2);
        }

        // свои пресеты — строки в настройках

        public static List<EqPreset> User()
        {
            var list = new List<EqPreset>();
            foreach (var line in Store.Get("userPresets").Split('\n'))
            {
                var p = EqPreset.Parse(line);
                if (p != null)
                {
                    list.Add(p);
                }
            }
            return list;
        }

        public static void SaveUser(List<EqPreset> list)
        {
            Store.Set("userPresets", string.Join("\n", list.Select(p => p.Serialize())));
        }
    }

    /// <summary>
    /// Код пресета EQ-XXXX-XXXX-… и ссылка для QR — байт в байт как на Android и iPhone:
    /// код с телефона открывается на ПК и наоборот.
    /// Байты: [1][полос][полосы ×0,5 дБ со знаком][панч 0…100][усиление ×0,5][баланс -100…100][запас ×-0,5][флаги][CRC-8].
    /// </summary>
    static class PresetCode
    {
        public const string Link = "https://gghub433.github.io/QE/p/";
        const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

        static int Clamp(int v, int lo, int hi)
        {
            return Math.Max(lo, Math.Min(hi, v));
        }

        static int R(float x)
        {
            return (int)Math.Round(x, MidpointRounding.AwayFromZero);
        }

        public static byte[] ToBytes(EqPreset p)
        {
            var b = new List<byte> { 1, (byte)p.Bands };
            for (int i = 0; i < p.Bands; i++)
            {
                float g = i < p.Gains.Length ? p.Gains[i] : 0;
                b.Add(unchecked((byte)(sbyte)Clamp(R(g * 2), -48, 48)));
            }
            b.Add((byte)Clamp(R(p.Punch * 100), 0, 100));
            b.Add((byte)Clamp(R(p.Boost * 2), 0, 24));
            b.Add(unchecked((byte)(sbyte)Clamp(R(p.Balance * 100), -100, 100)));
            b.Add((byte)Clamp(R(-p.Preamp * 2), 0, 24));
            b.Add((byte)(p.Leveling ? 1 : 0));
            var arr = b.ToArray();
            b.Add(Crc8(arr, arr.Length));
            return b.ToArray();
        }

        public static EqPreset FromBytes(byte[] b)
        {
            if (b == null || b.Length < 4 || b[0] != 1)
            {
                return null;
            }
            int bands = b[1];
            if (bands != 9 && bands != 15 && bands != 31)
            {
                return null;
            }
            int len = 2 + bands + 5 + 1;
            if (b.Length != len || Crc8(b, len - 1) != b[len - 1])
            {
                return null;
            }
            var p = new EqPreset { Bands = bands, Gains = new float[bands] };
            for (int i = 0; i < bands; i++)
            {
                p.Gains[i] = (sbyte)b[2 + i] / 2f;
            }
            int k = 2 + bands;
            p.Punch = Math.Min(100, (int)b[k]) / 100f;
            p.Boost = Math.Min(24, (int)b[k + 1]) / 2f;
            p.Balance = Clamp((sbyte)b[k + 2], -100, 100) / 100f;
            p.Preamp = -Math.Min(24, (int)b[k + 3]) / 2f;
            p.Leveling = (b[k + 4] & 1) != 0;
            return p;
        }

        /// <summary>CRC-8, полином 0x07.</summary>
        public static byte Crc8(byte[] b, int len)
        {
            int crc = 0;
            for (int i = 0; i < len; i++)
            {
                crc ^= b[i];
                for (int j = 0; j < 8; j++)
                {
                    crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
                }
            }
            return (byte)crc;
        }

        public static string Base32(byte[] b)
        {
            var sb = new StringBuilder();
            int buf = 0, bits = 0;
            foreach (var x in b)
            {
                buf = (buf << 8) | x;
                bits += 8;
                while (bits >= 5)
                {
                    sb.Append(Alphabet[(buf >> (bits - 5)) & 31]);
                    bits -= 5;
                }
                buf &= (1 << bits) - 1;
            }
            if (bits > 0)
            {
                sb.Append(Alphabet[(buf << (5 - bits)) & 31]);
            }
            return sb.ToString();
        }

        public static byte[] FromBase32(string s)
        {
            var o = new List<byte>();
            int buf = 0, bits = 0;
            foreach (var ch in s)
            {
                int v = Alphabet.IndexOf(ch);
                if (v < 0)
                {
                    return null;
                }
                buf = (buf << 5) | v;
                bits += 5;
                if (bits >= 8)
                {
                    o.Add((byte)((buf >> (bits - 8)) & 0xFF));
                    bits -= 8;
                    buf &= (1 << bits) - 1;
                }
            }
            return o.ToArray();
        }

        /// <summary>EQ-ABCD-EFGH-…</summary>
        public static string Encode(EqPreset p)
        {
            var raw = Base32(ToBytes(p));
            var sb = new StringBuilder("EQ");
            for (int i = 0; i < raw.Length; i += 4)
            {
                sb.Append('-').Append(raw.Substring(i, Math.Min(4, raw.Length - i)));
            }
            return sb.ToString();
        }

        public static string Url(string code)
        {
            return Link + code;
        }

        /// <summary>Пресет из кода, ссылки https://…/p/&lt;код&gt;, eq://preset/&lt;код&gt; или сообщения с кодом внутри.</summary>
        public static EqPreset Decode(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return null;
            }
            var up = text.ToUpperInvariant();
            for (int i = 0; i + 1 < up.Length; i++)
            {
                if (up[i] == 'E' && up[i + 1] == 'Q' && (i == 0 || !char.IsLetterOrDigit(up[i - 1])))
                {
                    var p = Parse(up, i + 2);
                    if (p != null)
                    {
                        return p;
                    }
                }
            }
            return Parse(up, 0);
        }

        static EqPreset Parse(string up, int from)
        {
            var s = new StringBuilder();
            for (int i = from; i < up.Length; i++)
            {
                char ch = up[i];
                if (ch == '-' || ch == ' ')
                {
                    continue;
                }
                if (Alphabet.IndexOf(ch) < 0)
                {
                    break;
                }
                s.Append(ch);
            }
            if (s.Length < 4)
            {
                return null;
            }
            var head = FromBase32(s.ToString(0, 4));
            if (head == null || head.Length < 2)
            {
                return null;
            }
            int bytes = 2 + head[1] + 6;
            int chars = (bytes * 8 + 4) / 5;
            if (s.Length < chars)
            {
                return null;
            }
            return FromBytes(FromBase32(s.ToString(0, chars)));
        }
    }
}
