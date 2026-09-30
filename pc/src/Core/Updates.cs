using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace EQ
{
    /// <summary>
    /// Обновление EQ для ПК: релизы GitHub с тегом pc-vX.Y и файлом EQ-PC.exe.
    /// Новая версия скачивается рядом, старая переименовывается в EQ.old.exe (Windows разрешает это даже
    /// для запущенной программы), и EQ перезапускается уже новым.
    /// </summary>
    static class Updates
    {
        public const string Releases = "https://github.com/gghub433/QE/releases";
        const string Api = "https://api.github.com/repos/gghub433/QE/releases?per_page=40";

        public static string Current
        {
            get
            {
                var v = typeof(Updates).Assembly.GetName().Version;
                return v.Major + "." + v.Minor;
            }
        }

        public enum State
        {
            Idle,
            Checking,
            UpToDate,
            Available,
            Downloading,
            Failed,
        }

        public static State Now = State.Idle;
        public static string NewVersion;
        static string assetUrl;
        public static event Action Changed;

        static void Set(State s)
        {
            Now = s;
            Changed?.Invoke();
        }

        public static void Check(System.Windows.Threading.Dispatcher ui)
        {
            if (Now == State.Checking || Now == State.Downloading || Store.Demo)
            {
                return;
            }
            Set(State.Checking);
            Task.Run(() =>
            {
                var result = State.Failed;
                try
                {
                    Shell.EnableTls();
                    using (var wc = new WebClient())
                    {
                        wc.Headers[HttpRequestHeader.UserAgent] = "EQ-PC";
                        wc.Headers[HttpRequestHeader.Accept] = "application/vnd.github+json";
                        var json = wc.DownloadString(Api);
                        // релизы идут от новых к старым: первый pc-v* — самый свежий
                        var m = Regex.Match(json, "\"tag_name\"\\s*:\\s*\"pc-v([0-9.]+)\"");
                        result = State.UpToDate;
                        if (m.Success && Newer(m.Groups[1].Value, Current))
                        {
                            NewVersion = m.Groups[1].Value;
                            var rest = json.Substring(m.Index);
                            var a = Regex.Match(rest, "\"browser_download_url\"\\s*:\\s*\"([^\"]*EQ-PC\\.exe)\"");
                            assetUrl = a.Success ? a.Groups[1].Value : null;
                            result = State.Available;
                        }
                    }
                }
                catch
                {
                    result = State.Failed;
                }
                ui.Invoke(() => Set(result));
            });
        }

        /// <summary>Скачать и заменить EQ.exe. Не вышло (папка только для чтения) — открыть страницу релизов.</summary>
        public static void Install(System.Windows.Threading.Dispatcher ui)
        {
            if (assetUrl == null)
            {
                Shell.Start(Releases);
                return;
            }
            Set(State.Downloading);
            Task.Run(() =>
            {
                try
                {
                    var exe = App.ExePath;
                    var dir = Path.GetDirectoryName(exe);
                    var tmp = Path.Combine(dir, "EQ.new.exe");
                    using (var wc = new WebClient())
                    {
                        wc.Headers[HttpRequestHeader.UserAgent] = "EQ-PC";
                        wc.DownloadFile(assetUrl, tmp);
                    }
                    var old = Path.Combine(dir, "EQ.old.exe");
                    if (File.Exists(old))
                    {
                        File.Delete(old);
                    }
                    File.Move(exe, old);
                    File.Move(tmp, exe);
                    ui.Invoke(() =>
                    {
                        Store.SaveNow();
                        Process.Start(exe, "--updated");
                        App.Quit();
                    });
                }
                catch
                {
                    ui.Invoke(() =>
                    {
                        Set(State.Available);
                        Shell.Start(Releases);
                    });
                }
            });
        }

        /// <summary>После обновления удалить старый файл.</summary>
        public static void Cleanup()
        {
            try
            {
                var old = Path.Combine(Path.GetDirectoryName(App.ExePath), "EQ.old.exe");
                if (File.Exists(old))
                {
                    File.Delete(old);
                }
            }
            catch
            {
                // старый ещё закрывается — удалим в следующий раз
            }
        }

        /// <summary>1.10 новее 1.9.</summary>
        public static bool Newer(string a, string b)
        {
            var x = a.Split('.').Select(s => int.TryParse(s, out var n) ? n : 0).ToArray();
            var y = b.Split('.').Select(s => int.TryParse(s, out var n) ? n : 0).ToArray();
            for (int i = 0; i < Math.Max(x.Length, y.Length); i++)
            {
                int p = i < x.Length ? x[i] : 0, q = i < y.Length ? y[i] : 0;
                if (p != q)
                {
                    return p > q;
                }
            }
            return false;
        }
    }
}
