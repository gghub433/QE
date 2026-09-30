using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;

namespace EQ
{
    /// <summary>
    /// Настройки EQ: %APPDATA%\EQ\settings.txt, строки «ключ=значение».
    /// В режиме скриншотов (Demo) ничего не читается и не пишется.
    /// </summary>
    static class Store
    {
        public static bool Demo;

        static readonly Dictionary<string, string> data = new Dictionary<string, string>();
        static readonly object gate = new object();
        static Timer saveTimer;

        public static string Dir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EQ"); }
        }

        static string FilePath
        {
            get { return Path.Combine(Dir, "settings.txt"); }
        }

        public static void Load()
        {
            if (Demo)
            {
                return;
            }
            try
            {
                if (!File.Exists(FilePath))
                {
                    return;
                }
                foreach (var line in File.ReadAllLines(FilePath, Encoding.UTF8))
                {
                    int i = line.IndexOf('=');
                    if (i > 0)
                    {
                        data[line.Substring(0, i)] = Unescape(line.Substring(i + 1));
                    }
                }
            }
            catch
            {
                // испорченный файл — начинаем с чистых настроек
            }
        }

        public static string Get(string key, string def = "")
        {
            lock (gate)
            {
                return data.TryGetValue(key, out var v) ? v : def;
            }
        }

        public static int GetInt(string key, int def)
        {
            return int.TryParse(Get(key, null), NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : def;
        }

        public static float GetFloat(string key, float def)
        {
            return float.TryParse(Get(key, null), NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : def;
        }

        public static bool GetBool(string key, bool def)
        {
            var s = Get(key, null);
            return s == null ? def : s == "1";
        }

        public static void Set(string key, string value)
        {
            lock (gate)
            {
                if (value == null)
                {
                    data.Remove(key);
                }
                else
                {
                    data[key] = value;
                }
            }
            ScheduleSave();
        }

        public static void Set(string key, int value)
        {
            Set(key, value.ToString(CultureInfo.InvariantCulture));
        }

        public static void Set(string key, float value)
        {
            Set(key, value.ToString("R", CultureInfo.InvariantCulture));
        }

        public static void Set(string key, bool value)
        {
            Set(key, value ? "1" : "0");
        }

        /// <summary>Все ключи с приставкой (например, «gp.» — звук для каждой игры).</summary>
        public static List<KeyValuePair<string, string>> WithPrefix(string prefix)
        {
            var list = new List<KeyValuePair<string, string>>();
            lock (gate)
            {
                foreach (var kv in data)
                {
                    if (kv.Key.StartsWith(prefix, StringComparison.Ordinal))
                    {
                        list.Add(kv);
                    }
                }
            }
            return list;
        }

        static void ScheduleSave()
        {
            if (Demo)
            {
                return;
            }
            lock (gate)
            {
                if (saveTimer == null)
                {
                    saveTimer = new Timer(_ => SaveNow());
                }
                saveTimer.Change(400, Timeout.Infinite);
            }
        }

        public static void SaveNow()
        {
            if (Demo)
            {
                return;
            }
            try
            {
                var sb = new StringBuilder();
                lock (gate)
                {
                    foreach (var kv in data)
                    {
                        sb.Append(kv.Key).Append('=').Append(Escape(kv.Value)).Append('\n');
                    }
                }
                Directory.CreateDirectory(Dir);
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, sb.ToString(), new UTF8Encoding(false));
                if (File.Exists(FilePath))
                {
                    File.Replace(tmp, FilePath, null);
                }
                else
                {
                    File.Move(tmp, FilePath);
                }
            }
            catch
            {
                // диск занят — сохраним при следующем изменении
            }
        }

        static string Escape(string s)
        {
            return s.Replace("\\", "\\\\").Replace("\n", "\\n").Replace("\r", "");
        }

        static string Unescape(string s)
        {
            var sb = new StringBuilder(s.Length);
            for (int i = 0; i < s.Length; i++)
            {
                if (s[i] == '\\' && i + 1 < s.Length)
                {
                    i++;
                    sb.Append(s[i] == 'n' ? '\n' : s[i]);
                }
                else
                {
                    sb.Append(s[i]);
                }
            }
            return sb.ToString();
        }
    }
}
