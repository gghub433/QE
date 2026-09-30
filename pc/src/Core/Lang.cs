using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

namespace EQ
{
    /// <summary>
    /// Тексты на 4 языках: Lang/en.txt, ru.txt, lv.txt, uk.txt («ключ = значение», ключи во всех файлах одинаковые).
    /// </summary>
    static class Lang
    {
        public static readonly string[] Codes = { "en", "ru", "lv", "uk" };
        public static readonly string[] Names = { "English", "Русский", "Latviešu", "Українська" };

        static Dictionary<string, string> map = new Dictionary<string, string>();
        static Dictionary<string, string> fallback;

        public static string Code { get; private set; } = "en";

        /// <summary>code = "" — язык Windows (если его нет среди четырёх — английский).</summary>
        public static void Load(string code)
        {
            if (string.IsNullOrEmpty(code))
            {
                code = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName;
            }
            if (Array.IndexOf(Codes, code) < 0)
            {
                code = "en";
            }
            Code = code;
            if (fallback == null)
            {
                fallback = Read("en");
            }
            map = Read(code);
        }

        static Dictionary<string, string> Read(string code)
        {
            var d = new Dictionary<string, string>();
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("EQ.Lang." + code + ".txt"))
            {
                if (s == null)
                {
                    return d;
                }
                using (var r = new StreamReader(s, Encoding.UTF8))
                {
                    string line;
                    while ((line = r.ReadLine()) != null)
                    {
                        if (line.Length == 0 || line[0] == '#')
                        {
                            continue;
                        }
                        int i = line.IndexOf(" = ", StringComparison.Ordinal);
                        if (i > 0)
                        {
                            d[line.Substring(0, i)] = line.Substring(i + 3).Replace("\\n", "\n");
                        }
                    }
                }
            }
            return d;
        }

        public static string L(string key)
        {
            if (map.TryGetValue(key, out var v))
            {
                return v;
            }
            return fallback != null && fallback.TryGetValue(key, out v) ? v : key;
        }

        public static string F(string key, params object[] args)
        {
            return string.Format(CultureInfo.CurrentCulture, L(key), args);
        }

        /// <summary>«1 ч 20 мин» / «35 мин».</summary>
        public static string Duration(double minutes)
        {
            int m = (int)Math.Round(minutes);
            return m >= 60 ? F("time.hm", m / 60, m % 60) : F("time.m", m);
        }
    }
}
