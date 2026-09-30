using System;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Windows.Threading;

namespace EQ
{
    /// <summary>
    /// Звук ПК: кривая 9/15/31 полоса, панч (полка низов), усиление, запас, баланс,
    /// игровой слой («Шаги врагов» и т.д.) → файл Equalizer APO. Как на Android, но на весь звук Windows.
    /// </summary>
    static class Sound
    {
        public static int Bands { get; private set; } = 9;
        public static float[] Gains = new float[9];
        public static float Preamp;   // -12 … 0 дБ
        public static float Punch;    // 0 … 1
        public static float Boost;    // 0 … 6 дБ (без лимитера — осторожно)
        public static float Balance;  // -1 … 1
        public static bool Enabled = true;
        public static bool AbBypass;
        public static string PresetName = "";

        /// <summary>Игровой слой поверх кривой: частоты, дБ и название игры.</summary>
        public static float[] GameCurve;
        public static float[] GameFreqs;
        public static string GameName;

        /// <summary>Что-то поменялось (для экранов).</summary>
        public static event Action Changed;

        /// <summary>Последняя запись в APO удалась.</summary>
        public static bool Written { get; private set; }

        static DispatcherTimer writeTimer;
        static readonly CultureInfo Inv = CultureInfo.InvariantCulture;

        public static void Load()
        {
            var p = EqPreset.Parse(Store.Get("sound"));
            if (p != null)
            {
                Bands = p.Bands;
                Gains = p.Gains;
                Preamp = p.Preamp;
                Punch = p.Punch;
                Boost = Math.Min(6, p.Boost);
                Balance = p.Balance;
                PresetName = p.Name;
            }
            Enabled = Store.GetBool("enabled", true);
        }

        static void Save()
        {
            Store.Set("sound", Current(PresetName).Serialize());
            Store.Set("enabled", Enabled);
        }

        /// <summary>Применить: записать в APO (с задержкой, чтобы не писать на каждое движение ползунка).</summary>
        public static void Apply(bool save = true)
        {
            if (save)
            {
                Save();
            }
            if (writeTimer == null)
            {
                writeTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(120) };
                writeTimer.Tick += (s, e) =>
                {
                    writeTimer.Stop();
                    WriteNow();
                };
            }
            writeTimer.Stop();
            writeTimer.Start();
            Changed?.Invoke();
        }

        public static void WriteNow()
        {
            Written = Apo.Write(BuildConfig());
        }

        public static void SetBands(int n)
        {
            if (n == Bands || n != 9 && n != 15 && n != 31)
            {
                return;
            }
            Gains = Presets.Resample(Gains, Presets.Freqs(Bands), Presets.Freqs(n));
            Bands = n;
            Apply();
        }

        public static void ApplyPreset(EqPreset p)
        {
            Gains = p.Bands == Bands ? (float[])p.Gains.Clone() : Presets.Resample(p.Gains, Presets.Freqs(p.Bands), Presets.Freqs(Bands));
            Preamp = p.Preamp;
            Punch = p.Punch;
            Boost = Math.Min(6, p.Boost);
            Balance = p.Balance;
            PresetName = p.Name;
            Apply();
        }

        public static EqPreset Current(string name)
        {
            return new EqPreset
            {
                Name = name ?? "",
                Bands = Bands,
                Gains = (float[])Gains.Clone(),
                Preamp = Preamp,
                Punch = Punch,
                Boost = Boost,
                Balance = Balance,
            };
        }

        public static void SetGame(float[] curve, float[] freqs, string name)
        {
            GameCurve = curve;
            GameFreqs = freqs;
            GameName = name;
            Apply(false);
        }

        public static void ClearGame()
        {
            if (GameCurve == null && GameName == null)
            {
                return;
            }
            GameCurve = null;
            GameFreqs = null;
            GameName = null;
            Apply(false);
        }

        /// <summary>Средний подъём кривой (60 Гц … 10 кГц): A/B сравнивает звук, а не громкость.</summary>
        public static float AbCompensation()
        {
            var f = Presets.Freqs(Bands);
            double sum = 0;
            int n = 0;
            for (int i = 0; i < f.Length && i < Gains.Length; i++)
            {
                if (f[i] >= 60 && f[i] <= 10000)
                {
                    sum += Gains[i];
                    n++;
                }
            }
            double mean = n > 0 ? sum / n : 0;
            return (float)Math.Max(-12, Math.Min(12, mean + Preamp + Boost));
        }

        static string Num(double v)
        {
            return v.ToString("0.0#", Inv);
        }

        static string GraphicEq(float[] freqs, float[] gains)
        {
            return "GraphicEQ: " + string.Join("; ", freqs.Select((f, i) => Num(f) + " " + Num(i < gains.Length ? gains[i] : 0)));
        }

        /// <summary>Текст для config\EQ.txt.</summary>
        public static string BuildConfig()
        {
            var sb = new StringBuilder();
            sb.Append("# EQ для ПК (lv.budseq). Этот файл пишет EQ — правки здесь сотрутся.\r\n");
            if (!Enabled)
            {
                sb.Append("# EQ выключен\r\n");
                return sb.ToString();
            }
            if (AbBypass)
            {
                sb.Append("# A/B: оригинал той же громкости\r\n");
                sb.Append("Preamp: ").Append(Num(AbCompensation())).Append(" dB\r\n");
                return sb.ToString();
            }
            sb.Append("# ").Append(string.IsNullOrEmpty(PresetName) ? "custom" : PresetName.Replace("\n", " ")).Append("\r\n");
            sb.Append("Preamp: ").Append(Num(Preamp + Boost)).Append(" dB\r\n");
            sb.Append(GraphicEq(Presets.Freqs(Bands), Gains)).Append("\r\n");
            if (Punch > 0.01f)
            {
                // панч: плотнее низ (на Android ещё и компрессор; в APO — полка низких частот)
                sb.Append("Filter: ON LS Fc 105 Hz Gain ").Append(Num(Punch * 6)).Append(" dB\r\n");
            }
            if (GameCurve != null && GameFreqs != null)
            {
                sb.Append("# game: ").Append((GameName ?? "").Replace("\n", " ")).Append("\r\n");
                sb.Append(GraphicEq(GameFreqs, GameCurve)).Append("\r\n");
            }
            if (Math.Abs(Balance) > 0.01f)
            {
                double db = 20 * Math.Log10(Math.Max(0.1, 1 - Math.Abs(Balance)));
                sb.Append("Channel: ").Append(Balance > 0 ? "L" : "R").Append("\r\n");
                sb.Append("Preamp: ").Append(Num(db)).Append(" dB\r\n");
                sb.Append("Channel: all\r\n");
            }
            return sb.ToString();
        }
    }
}
