using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace EQ
{
    /// <summary>
    /// Скриншоты для проверки интерфейса (GitHub Actions, без экрана): EQ.exe --shots папка.
    /// Демо-данные: игры, устройства, пресет; настройки не читаются и не пишутся.
    /// </summary>
    static class Shots
    {
        public static int Run(string dir)
        {
            Directory.CreateDirectory(dir);
            try
            {
                Store.Demo = true;
                var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
                foreach (var lang in new[] { "ru", "en" })
                {
                    Lang.Load(lang);
                    Demo();
                    var view = new MainView();
                    var pages = lang == "ru" ? new[] { 0, 1, 2, 3 } : new[] { 1, 2 };
                    foreach (var p in pages)
                    {
                        view.ShowPage(p);
                        Render(view, Path.Combine(dir, "pc-" + lang + "-" + p + ".png"));
                    }
                    if (lang == "ru")
                    {
                        view.ShowPage(MainView.PGames);
                        GamesPage.Details(Games.All[0], view);
                        Render(view, Path.Combine(dir, "pc-ru-2-game.png"));
                        view.ShowPage(MainView.PEq);
                        Dialogs.Share(Sound.Current(Sound.PresetName));
                        Render(view, Path.Combine(dir, "pc-ru-1-share.png"));
                        // без Equalizer APO: плашка «Установить»
                        Apo.DemoInstalled = false;
                        view.Rebuild();
                        view.ShowPage(MainView.PEq);
                        Render(view, Path.Combine(dir, "pc-ru-1-noapo.png"));
                        Apo.DemoInstalled = true;
                    }
                }
                return 0;
            }
            catch (Exception e)
            {
                File.WriteAllText(Path.Combine(dir, "error.txt"), e.ToString());
                return 1;
            }
        }

        static void Demo()
        {
            Apo.DemoInstalled = true;
            AudioDevices.Demo = new List<AudioDevice>
            {
                new AudioDevice { Id = "1", Name = "Наушники", Interface = "AirPods Pro", Apo = true, IsDefault = true },
                new AudioDevice { Id = "2", Name = "Динамики", Interface = "Realtek(R) Audio", Apo = true },
                new AudioDevice { Id = "3", Name = "LG TV", Interface = "NVIDIA High Definition Audio", Apo = false },
            };
            var names = new[]
            {
                ("Counter-Strike 2", "730", 18420.0, 0), ("Cyberpunk 2077", "1091500", 5230.0, 2), ("Apex Legends", "1172470", 3110.0, 3),
                ("Baldur's Gate 3", "1086940", 7420.0, 4), ("PUBG: BATTLEGROUNDS", "578080", 2890.0, 5), ("Forza Horizon 5", "1551360", 1260.0, 6),
                ("Rocket League", "252950", 940.0, 1), ("Hollow Knight", "367520", 610.0, 7), ("Fortnite", null, 1840.0, 1), ("Minecraft", null, 3300.0, 5),
            };
            Games.All = names.Select((x, i) => new Game
            {
                Key = x.Item2 != null ? "steam:" + x.Item2 : "epic:" + x.Item1,
                Name = x.Item1,
                Source = x.Item2 != null ? GameSource.Steam : i == 8 ? GameSource.Epic : GameSource.Own,
                AppId = x.Item2,
                InstallDir = @"C:\Games\" + x.Item1,
                PlaytimeMin = x.Item3,
                LastPlayed = DateTime.UtcNow.AddDays(-i * 2 - 1),
                DemoColor = x.Item4,
            }).ToList();
            var hard = Presets.FromBuiltIn(1);
            Sound.ApplyPreset(hard);
            Sound.Enabled = true;
            GameTracker.ShowDemo(Games.All[0]);
            Games.ApplySoundFor(Games.All[0], true);
        }

        static void Render(FrameworkElement v, string path)
        {
            const double w = 1280, h = 820, scale = 1.5;
            v.Measure(new Size(w, h));
            v.Arrange(new Rect(0, 0, w, h));
            v.UpdateLayout();
            // дать WPF дорисовать отложенное (обложки, размеры текста)
            Dispatcher.CurrentDispatcher.Invoke(() => { }, DispatcherPriority.ApplicationIdle);
            v.UpdateLayout();
            var rtb = new RenderTargetBitmap((int)(w * scale), (int)(h * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            rtb.Render(v);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(rtb));
            using (var f = File.Create(path))
            {
                enc.Save(f);
            }
        }
    }
}
