using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Threading;
using Microsoft.Win32;

namespace EQ
{
    enum GameSource
    {
        Steam,
        Epic,
        Own,
    }

    /// <summary>Игра на этом ПК: Steam, Epic Games или добавленная вручную (.exe).</summary>
    sealed class Game
    {
        public string Key = "";          // steam:730, epic:Fortnite, exe:C:\…\game.exe
        public string Name = "";
        public GameSource Source;
        public string AppId;             // Steam appid или AppName в Epic
        public string InstallDir;
        public string Exe;
        public string CoverPath;         // картинка 600×900 (или шапка), если есть
        public double PlaytimeMin;
        public DateTime LastPlayed;
        public int DemoColor = -1;       // только для скриншотов
    }

    /// <summary>
    /// Игры: библиотека Steam и Epic прямо с диска (ключ Steam не нужен), обложки, игровое время,
    /// запуск через EQ и звук под игру («Шаги врагов», «Насыщенный», «Голоса»), как на Android.
    /// </summary>
    static class Games
    {
        public const int PNone = 0, PSteps = 1, PRich = 2, PVoice = 3;
        public static readonly string[] ProfileKeys = { "gp.none", "gp.steps", "gp.rich", "gp.voice" };

        // Профили в своих частотах (дБ): шаги и перезарядка живут в 2–5 кГц, гул взрывов — ниже 150 Гц.
        public static readonly float[] PF = { 32, 64, 125, 250, 500, 1000, 2000, 3000, 4000, 6000, 8000, 16000 };
        static readonly float[][] Profiles =
        {
            null,
            new[] { -6f, -5, -3, -1, 0, 1, 3.5f, 5, 5, 3, 1.5f, -1 },          // шаги врагов
            new[] { 4.5f, 4, 2.5f, 0.5f, -0.5f, 0, 1, 2, 2.5f, 2.5f, 2, 1.5f },  // насыщенный
            new[] { -4f, -3, -1.5f, 0, 1, 2.5f, 3, 2.5f, 1.5f, 0, -1, -2 },     // голоса
        };

        static readonly HashSet<string> SteamShooters = new HashSet<string>
        {
            "730", "578080", "1172470", "359550", "1938090", "594650", "252490", "221100", "1517290", "1238810",
            "1238840", "2357570", "440", "107410", "393380", "581320", "2073850", "2767030", "1240440", "1144200",
            "686810", "240", "550", "1422450", "2507950", "2073620",
        };

        static readonly string[] ShooterWords =
        {
            "counter-strike", "pubg", "call of duty", "shooter", "strike", "warzone", "battlegrounds", "fortnite",
            "apex", "rainbow six", "valorant", "tarkov", "battlefield", "sniper", "overwatch", "team fortress",
            "insurgency", "the finals", "halo", "ready or not", "hell let loose", "deadlock", "delta force",
            "arena breakout", "left 4 dead", "marvel rivals", "rust", "dayz", "squad", "hunt: showdown",
        };

        /// <summary>Служебные «игры» Steam, которые не показываем.</summary>
        static readonly string[] SkipWords = { "redistributable", "proton", "steam linux runtime", "steamvr", "soundtrack", "dedicated server", "sdk" };

        public static List<Game> All = new List<Game>();
        public static event Action LibraryChanged;

        public static bool AutoOn
        {
            get { return Store.GetBool("games.auto", true); }
            set { Store.Set("games.auto", value); }
        }

        /// <summary>«Насыщенность»: сила игрового звука 0…1.</summary>
        public static float Strength
        {
            get { return Store.GetFloat("games.strength", 1f); }
            set { Store.Set("games.strength", Math.Max(0f, Math.Min(1f, value))); }
        }

        public static bool IsShooter(Game g)
        {
            if (g.Source == GameSource.Steam && g.AppId != null && SteamShooters.Contains(g.AppId))
            {
                return true;
            }
            var s = (g.Name ?? "").ToLowerInvariant();
            return ShooterWords.Any(w => s.Contains(w));
        }

        /// <summary>Звук для игры: выбранный вручную, иначе — стрелялкам шаги, остальным насыщенный.</summary>
        public static int ProfileFor(Game g)
        {
            int p = Store.GetInt("gp." + g.Key, -1);
            if (p >= 0 && p < Profiles.Length)
            {
                return p;
            }
            return IsShooter(g) ? PSteps : PRich;
        }

        public static void SetProfile(Game g, int p)
        {
            Store.Set("gp." + g.Key, p);
            if (GameTracker.Running != null && GameTracker.Running.Key == g.Key)
            {
                ApplySoundFor(g, true);
            }
        }

        public static float[] Curve(int profile, float strength)
        {
            var src = Profiles[profile];
            return src.Select(v => v * strength).ToArray();
        }

        /// <summary>Включить звук игры. manual — игру запустили кнопкой «Играть» (не зависит от «Автоматически»).</summary>
        public static void ApplySoundFor(Game g, bool manual)
        {
            if (!manual && !AutoOn)
            {
                return;
            }
            int p = ProfileFor(g);
            float s = Strength;
            if (p == PNone || s < 0.01f)
            {
                Sound.ClearGame();
                return;
            }
            Sound.SetGame(Curve(p, s), PF, g.Name);
        }

        // =====================================================================
        // Библиотека
        // =====================================================================

        /// <summary>Перечитать игры с диска (в фоне), потом подгрузить обложки.</summary>
        public static void Refresh(Dispatcher ui)
        {
            if (Store.Demo)
            {
                return;
            }
            Task.Run(() =>
            {
                var list = new List<Game>();
                try
                {
                    list.AddRange(ScanSteam());
                }
                catch
                {
                    // нет Steam или файлы заняты
                }
                try
                {
                    list.AddRange(ScanEpic());
                }
                catch
                {
                    // нет Epic
                }
                list.AddRange(OwnGames());
                foreach (var g in list)
                {
                    if (g.Source != GameSource.Steam)
                    {
                        g.PlaytimeMin = Store.GetFloat("pt." + g.Key, 0);
                    }
                    long lp;
                    if (long.TryParse(Store.Get("lp." + g.Key, null), out lp) && lp > 0)
                    {
                        var t = DateTimeOffset.FromUnixTimeSeconds(lp).UtcDateTime;
                        if (t > g.LastPlayed)
                        {
                            g.LastPlayed = t;
                        }
                    }
                }
                list = list.GroupBy(g => g.Key).Select(x => x.First())
                    .OrderByDescending(g => g.LastPlayed).ThenBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList();
                ui.Invoke(() =>
                {
                    All = list;
                    LibraryChanged?.Invoke();
                });
                DownloadMissingCovers(list, ui);
            });
        }

        public static string SteamRoot()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Valve\Steam"))
                {
                    var p = k?.GetValue("SteamPath") as string;
                    if (!string.IsNullOrEmpty(p) && Directory.Exists(p))
                    {
                        return p.Replace('/', '\\');
                    }
                }
            }
            catch
            {
                // нет ключа
            }
            var def = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Steam");
            return Directory.Exists(def) ? def : null;
        }

        static IEnumerable<Game> ScanSteam()
        {
            var root = SteamRoot();
            if (root == null)
            {
                yield break;
            }
            var libs = new List<string> { root };
            var lf = Path.Combine(root, "steamapps", "libraryfolders.vdf");
            if (File.Exists(lf))
            {
                var v = Vdf.Parse(File.ReadAllText(lf)).Child("libraryfolders");
                if (v != null)
                {
                    foreach (var c in v.Children.Values)
                    {
                        if (c["path"] != null)
                        {
                            libs.Add(c["path"]);
                        }
                    }
                    foreach (var kv in v.Values)
                    {
                        if (kv.Key.All(char.IsDigit) && kv.Value.Contains("\\"))
                        {
                            libs.Add(kv.Value);   // старый формат файла
                        }
                    }
                }
            }
            var playtime = SteamPlaytime(root);
            foreach (var lib in libs.Select(l => l.Replace("\\\\", "\\")).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                var apps = Path.Combine(lib, "steamapps");
                if (!Directory.Exists(apps))
                {
                    continue;
                }
                foreach (var acf in Directory.GetFiles(apps, "appmanifest_*.acf"))
                {
                    Vdf st;
                    try
                    {
                        st = Vdf.Parse(File.ReadAllText(acf)).Child("AppState");
                    }
                    catch
                    {
                        continue;
                    }
                    if (st == null || st["appid"] == null || st["name"] == null)
                    {
                        continue;
                    }
                    var name = st["name"];
                    var low = name.ToLowerInvariant();
                    if (SkipWords.Any(w => low.Contains(w)) || st["appid"] == "228980")
                    {
                        continue;
                    }
                    var g = new Game
                    {
                        Key = "steam:" + st["appid"],
                        Name = name,
                        Source = GameSource.Steam,
                        AppId = st["appid"],
                        InstallDir = st["installdir"] == null ? null : Path.Combine(apps, "common", st["installdir"]),
                        CoverPath = SteamCover(root, st["appid"]),
                    };
                    if (playtime.TryGetValue(g.AppId, out var pt))
                    {
                        g.PlaytimeMin = pt.Item1;
                        g.LastPlayed = pt.Item2;
                    }
                    long lu;
                    if (g.LastPlayed == default(DateTime) && long.TryParse(st["LastPlayed"], out lu) && lu > 0)
                    {
                        g.LastPlayed = DateTimeOffset.FromUnixTimeSeconds(lu).UtcDateTime;
                    }
                    yield return g;
                }
            }
        }

        /// <summary>Игровое время Steam (минуты) и когда играли — из localconfig.vdf последнего пользователя.</summary>
        static Dictionary<string, Tuple<double, DateTime>> SteamPlaytime(string root)
        {
            var r = new Dictionary<string, Tuple<double, DateTime>>();
            try
            {
                var ud = Path.Combine(root, "userdata");
                var file = Directory.Exists(ud)
                    ? Directory.GetDirectories(ud).Select(d => Path.Combine(d, "config", "localconfig.vdf"))
                        .Where(File.Exists).OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault()
                    : null;
                if (file == null)
                {
                    return r;
                }
                var apps = Vdf.Parse(File.ReadAllText(file)).Path("UserLocalConfigStore/Software/Valve/Steam/apps");
                if (apps == null)
                {
                    return r;
                }
                foreach (var kv in apps.Children)
                {
                    double.TryParse(kv.Value["Playtime"], out var min);
                    long.TryParse(kv.Value["LastPlayed"], out var lp);
                    r[kv.Key] = Tuple.Create(min, lp > 0 ? DateTimeOffset.FromUnixTimeSeconds(lp).UtcDateTime : default(DateTime));
                }
            }
            catch
            {
                // файл занят Steam — без времени
            }
            return r;
        }

        /// <summary>Обложка из кэша Steam (старое и новое расположение).</summary>
        static string SteamCover(string root, string appid)
        {
            try
            {
                var cache = Path.Combine(root, "appcache", "librarycache");
                var flat = Path.Combine(cache, appid + "_library_600x900.jpg");
                if (File.Exists(flat))
                {
                    return flat;
                }
                var dir = Path.Combine(cache, appid);
                if (Directory.Exists(dir))
                {
                    var files = Directory.GetFiles(dir, "*.jpg", SearchOption.AllDirectories);
                    var best = files.FirstOrDefault(f => Path.GetFileName(f).StartsWith("library_600x900", StringComparison.OrdinalIgnoreCase))
                               ?? files.FirstOrDefault(f => Path.GetFileName(f).IndexOf("capsule", StringComparison.OrdinalIgnoreCase) >= 0);
                    if (best != null)
                    {
                        return best;
                    }
                }
                var own = Path.Combine(CoverCache, appid + ".jpg");
                if (File.Exists(own))
                {
                    return own;
                }
            }
            catch
            {
                // нет кэша
            }
            return null;
        }

        static string CoverCache
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "EQ", "covers"); }
        }

        /// <summary>Обложек нет в кэше Steam — скачать с сервера картинок Steam (один раз).</summary>
        static void DownloadMissingCovers(List<Game> list, Dispatcher ui)
        {
            var need = list.Where(g => g.Source == GameSource.Steam && g.CoverPath == null).Take(60).ToList();
            if (need.Count == 0)
            {
                return;
            }
            Shell.EnableTls();
            Directory.CreateDirectory(CoverCache);
            foreach (var g in need)
            {
                var dst = Path.Combine(CoverCache, g.AppId + ".jpg");
                var urls = new[]
                {
                    "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/" + g.AppId + "/library_600x900.jpg",
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/" + g.AppId + "/library_600x900.jpg",
                    "https://cdn.cloudflare.steamstatic.com/steam/apps/" + g.AppId + "/header.jpg",
                };
                foreach (var u in urls)
                {
                    try
                    {
                        using (var wc = new WebClient())
                        {
                            wc.DownloadFile(u, dst + ".part");
                        }
                        File.Copy(dst + ".part", dst, true);
                        File.Delete(dst + ".part");
                        var game = g;
                        ui.Invoke(() =>
                        {
                            game.CoverPath = dst;
                            LibraryChanged?.Invoke();
                        });
                        break;
                    }
                    catch
                    {
                        // нет картинки по этой ссылке — пробуем следующую
                    }
                }
            }
        }

        static IEnumerable<Game> ScanEpic()
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "Epic", "EpicGamesLauncher", "Data", "Manifests");
            if (!Directory.Exists(dir))
            {
                yield break;
            }
            foreach (var f in Directory.GetFiles(dir, "*.item"))
            {
                string json;
                try
                {
                    json = File.ReadAllText(f);
                }
                catch
                {
                    continue;
                }
                var name = JsonString(json, "DisplayName");
                var app = JsonString(json, "AppName");
                var loc = JsonString(json, "InstallLocation");
                if (name == null || app == null || loc == null || json.Contains("\"addons\""))
                {
                    continue;
                }
                var exe = JsonString(json, "LaunchExecutable");
                yield return new Game
                {
                    Key = "epic:" + app,
                    Name = name,
                    Source = GameSource.Epic,
                    AppId = app,
                    InstallDir = loc,
                    Exe = exe == null ? null : Path.Combine(loc, exe),
                };
            }
        }

        static string JsonString(string json, string key)
        {
            var m = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            return m.Success ? Regex.Unescape(m.Groups[1].Value) : null;
        }

        // свои игры: «название|путь к exe» построчно

        static IEnumerable<Game> OwnGames()
        {
            foreach (var line in Store.Get("own.games").Split('\n'))
            {
                var i = line.IndexOf('|');
                if (i <= 0)
                {
                    continue;
                }
                var exe = line.Substring(i + 1);
                yield return new Game
                {
                    Key = "exe:" + exe.ToLowerInvariant(),
                    Name = line.Substring(0, i),
                    Source = GameSource.Own,
                    Exe = exe,
                    InstallDir = Path.GetDirectoryName(exe),
                };
            }
        }

        public static void AddOwn(string exe)
        {
            var name = FileVersionInfo.GetVersionInfo(exe).ProductName;
            if (string.IsNullOrWhiteSpace(name))
            {
                name = Path.GetFileNameWithoutExtension(exe);
            }
            var lines = Store.Get("own.games").Split('\n').Where(l => l.Length > 0 && !l.EndsWith("|" + exe)).ToList();
            lines.Add(name.Replace("|", "/").Trim() + "|" + exe);
            Store.Set("own.games", string.Join("\n", lines));
        }

        public static void RemoveOwn(Game g)
        {
            var lines = Store.Get("own.games").Split('\n').Where(l => l.Length > 0 && !l.EndsWith("|" + g.Exe)).ToList();
            Store.Set("own.games", string.Join("\n", lines));
        }

        // =====================================================================
        // Запуск
        // =====================================================================

        /// <summary>«Играть»: сначала звук под игру, потом запуск (через Steam / Epic или сам exe).</summary>
        public static void Play(Game g)
        {
            ApplySoundFor(g, true);
            GameTracker.Launched(g);
            Store.Set("lp." + g.Key, DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString());
            switch (g.Source)
            {
                case GameSource.Steam:
                    Shell.Start("steam://rungameid/" + g.AppId);
                    break;
                case GameSource.Epic:
                    Shell.Start("com.epicgames.launcher://apps/" + Uri.EscapeDataString(g.AppId) + "?action=launch&silent=true");
                    break;
                default:
                    Shell.Start(g.Exe, Path.GetDirectoryName(g.Exe));
                    break;
            }
        }

        public static void OpenFolder(Game g)
        {
            if (g.InstallDir != null && Directory.Exists(g.InstallDir))
            {
                Shell.Start(g.InstallDir);
            }
        }

        public static void OpenStore(Game g)
        {
            if (g.Source == GameSource.Steam)
            {
                Shell.Start("steam://nav/games/details/" + g.AppId);
            }
        }
    }

    /// <summary>
    /// Какая игра запущена прямо сейчас (по папке игры среди процессов). Игра началась — включаем её звук,
    /// закончилась — возвращаем обычный; для Epic и своих игр EQ сам считает игровое время.
    /// </summary>
    static class GameTracker
    {
        public static Game Running { get; private set; }
        public static event Action Changed;

        static DispatcherTimer timer;
        static bool busy;
        static DateTime lastTick = DateTime.UtcNow;
        static string launchedKey;
        static DateTime launchedUntil;

        public static void Start()
        {
            if (Store.Demo)
            {
                return;
            }
            timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
            timer.Tick += (s, e) => Tick();
            timer.Start();
        }

        /// <summary>Нажали «Играть»: пока игра грузится (до 3 минут), звук не сбрасываем.</summary>
        public static void Launched(Game g)
        {
            launchedKey = g.Key;
            launchedUntil = DateTime.UtcNow.AddMinutes(3);
            if (Running == null || Running.Key != g.Key)
            {
                Running = g;
                Changed?.Invoke();
            }
        }

        public static void ShowDemo(Game g)
        {
            Running = g;
        }

        static void Tick()
        {
            if (busy)
            {
                return;
            }
            busy = true;
            var games = Games.All.Where(g => !string.IsNullOrEmpty(g.InstallDir)).ToList();
            var ui = Dispatcher.CurrentDispatcher;
            Task.Run(() => Find(games)).ContinueWith(t =>
            {
                ui.Invoke(() =>
                {
                    busy = false;
                    OnResult(t.IsFaulted ? null : t.Result);
                });
            });
        }

        static void OnResult(Game g)
        {
            var now = DateTime.UtcNow;
            double dt = Math.Min(0.2, (now - lastTick).TotalMinutes);
            lastTick = now;
            if (g == null && launchedKey != null && now < launchedUntil)
            {
                return;   // игра ещё запускается
            }
            if (g != null)
            {
                launchedKey = null;
                if (g.Source != GameSource.Steam && Running != null && Running.Key == g.Key)
                {
                    g.PlaytimeMin += dt;
                    Store.Set("pt." + g.Key, (float)g.PlaytimeMin);
                    Store.Set("lp." + g.Key, DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString());
                }
            }
            if ((g == null) != (Running == null) || g != null && g.Key != Running.Key)
            {
                Running = g;
                launchedKey = null;
                if (g != null)
                {
                    Games.ApplySoundFor(g, false);
                }
                else
                {
                    Sound.ClearGame();
                }
                Changed?.Invoke();
            }
        }

        static Game Find(List<Game> games)
        {
            if (games.Count == 0)
            {
                return null;
            }
            var dirs = games.Select(g => Tuple.Create(g, g.InstallDir.TrimEnd('\\') + "\\")).ToList();
            var sb = new StringBuilder(1024);
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    var path = ImagePath(p.Id, sb);
                    if (path == null)
                    {
                        continue;
                    }
                    foreach (var d in dirs)
                    {
                        if (path.StartsWith(d.Item2, StringComparison.OrdinalIgnoreCase))
                        {
                            return d.Item1;
                        }
                    }
                }
                finally
                {
                    p.Dispose();
                }
            }
            return null;
        }

        const int QueryLimited = 0x1000;

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(int access, bool inherit, int pid);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool QueryFullProcessImageName(IntPtr process, int flags, StringBuilder name, ref int size);

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr handle);

        static string ImagePath(int pid, StringBuilder sb)
        {
            var h = OpenProcess(QueryLimited, false, pid);
            if (h == IntPtr.Zero)
            {
                return null;
            }
            try
            {
                sb.Clear();
                int size = sb.Capacity;
                return QueryFullProcessImageName(h, 0, sb, ref size) ? sb.ToString() : null;
            }
            finally
            {
                CloseHandle(h);
            }
        }
    }
}
