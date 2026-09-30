using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Forms = System.Windows.Forms;
using static EQ.Lang;

namespace EQ
{
    /// <summary>
    /// EQ для ПК: запуск, одно окно (второй запуск просто показывает первое), значок у часов,
    /// ссылки eq://preset/…, автозапуск (--tray), выдача прав APO (--grant), скриншоты (--shots).
    /// </summary>
    static class App
    {
        static Mutex mutex;
        static Application app;
        static Window window;
        static Forms.NotifyIcon tray;
        static FileSystemWatcher inboxWatcher;

        public static string ExePath
        {
            get { return Process.GetCurrentProcess().MainModule.FileName; }
        }

        static string InboxFile
        {
            get { return Path.Combine(Store.Dir, "inbox.txt"); }
        }

        [STAThread]
        static int Main(string[] args)
        {
            if (args.Length >= 2 && args[0] == "--grant")
            {
                return Apo.Grant(args[1]);
            }
            int shots = Array.IndexOf(args, "--shots");
            if (shots >= 0 && shots + 1 < args.Length)
            {
                return Shots.Run(args[shots + 1]);
            }

            var incoming = args.FirstOrDefault(a => PresetCode.Decode(a) != null);
            mutex = new Mutex(true, @"Local\lv.budseq.eq", out bool first);
            if (!first)
            {
                // EQ уже запущен — передаём ему пресет (или просьбу показаться) и выходим
                try
                {
                    Directory.CreateDirectory(Store.Dir);
                    File.WriteAllText(InboxFile, incoming ?? "show", Encoding.UTF8);
                }
                catch
                {
                    // не удалось — первый экземпляр просто останется как есть
                }
                return 0;
            }

            Store.Load();
            Lang.Load(Store.Get("lang"));
            Apo.Detect();
            Sound.Load();
            Updates.Cleanup();
            Shell.RegisterProtocol();

            app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            app.DispatcherUnhandledException += (s, e) =>
            {
                Log(e.Exception);
                e.Handled = true;
            };
            window = CreateWindow();
            CreateTray();
            Sound.WriteNow();   // звук как в прошлый раз — сразу при запуске
            Games.Refresh(app.Dispatcher);
            GameTracker.Start();
            WatchInbox();
            if (!args.Contains("--tray"))
            {
                window.Show();
            }
            if (incoming != null)
            {
                ShowIncoming(incoming);
            }
            if (Store.GetBool("autoUpdate", true))
            {
                Updates.Check(app.Dispatcher);
            }
            app.Run();
            return 0;
        }

        static Window CreateWindow()
        {
            var w = new Window
            {
                Title = "EQ",
                Width = 1240,
                Height = 820,
                MinWidth = 980,
                MinHeight = 640,
                WindowStartupLocation = WindowStartupLocation.CenterScreen,
                Background = Theme.B(Theme.Bg),
                Content = new MainView(),
                UseLayoutRounding = true,
            };
            w.SourceInitialized += (s, e) => Shell.DarkTitleBar(w);
            w.KeyDown += (s, e) =>
            {
                if (e.Key == Key.Escape && MainView.Current.OverlayOpen)
                {
                    MainView.Current.CloseOverlay();
                }
            };
            w.Closing += OnClosing;
            return w;
        }

        static void OnClosing(object sender, CancelEventArgs e)
        {
            if (Store.GetBool("background", true))
            {
                // EQ остаётся у часов: следит за играми и держит звук
                e.Cancel = true;
                window.Hide();
                if (!Store.GetBool("trayHintShown", false))
                {
                    Store.Set("trayHintShown", true);
                    tray?.ShowBalloonTip(4000, "EQ", L("tray.hint"), Forms.ToolTipIcon.None);
                }
                return;
            }
            Quit();
        }

        static void CreateTray()
        {
            try
            {
                tray = new Forms.NotifyIcon
                {
                    Icon = System.Drawing.Icon.ExtractAssociatedIcon(ExePath),
                    Text = "EQ",
                    Visible = true,
                };
                tray.MouseClick += (s, e) =>
                {
                    if (e.Button == Forms.MouseButtons.Left)
                    {
                        ShowWindow();
                    }
                };
                RefreshTray();
            }
            catch
            {
                tray = null;   // без значка у часов
            }
        }

        /// <summary>Меню значка у часов (после смены языка — заново).</summary>
        public static void RefreshTray()
        {
            if (tray == null)
            {
                return;
            }
            var menu = new Forms.ContextMenuStrip();
            menu.Items.Add(L("tray.open"), null, (s, e) => ShowWindow());
            var onOff = new Forms.ToolStripMenuItem(L("tray.enabled")) { Checked = Sound.Enabled };
            onOff.Click += (s, e) =>
            {
                Sound.Enabled = !Sound.Enabled;
                onOff.Checked = Sound.Enabled;
                Sound.Apply();
            };
            menu.Items.Add(onOff);
            menu.Opening += (s, e) => onOff.Checked = Sound.Enabled;
            menu.Items.Add(new Forms.ToolStripSeparator());
            menu.Items.Add(L("tray.quit"), null, (s, e) => Quit());
            tray.ContextMenuStrip = menu;
        }

        public static void ShowWindow()
        {
            if (window == null)
            {
                return;
            }
            window.Show();
            if (window.WindowState == WindowState.Minimized)
            {
                window.WindowState = WindowState.Normal;
            }
            window.Activate();
        }

        public static void Quit()
        {
            try
            {
                if (tray != null)
                {
                    tray.Visible = false;
                    tray.Dispose();
                }
            }
            catch
            {
                // значок уже убран
            }
            Store.SaveNow();
            app?.Shutdown();
        }

        /// <summary>Второй запуск EQ (или ссылка eq://) пишет в inbox.txt — показываем окно и пресет.</summary>
        static void WatchInbox()
        {
            try
            {
                Directory.CreateDirectory(Store.Dir);
                inboxWatcher = new FileSystemWatcher(Store.Dir, "inbox.txt")
                {
                    NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName | NotifyFilters.Size,
                    EnableRaisingEvents = true,
                };
                FileSystemEventHandler h = (s, e) => app.Dispatcher.BeginInvoke(new Action(ReadInbox), DispatcherPriority.Background);
                inboxWatcher.Changed += h;
                inboxWatcher.Created += h;
            }
            catch
            {
                // без передачи между запусками
            }
        }

        static void ReadInbox()
        {
            string text = null;
            for (int i = 0; i < 5 && text == null; i++)
            {
                try
                {
                    if (!File.Exists(InboxFile))
                    {
                        return;
                    }
                    text = File.ReadAllText(InboxFile);
                    File.Delete(InboxFile);
                }
                catch
                {
                    Thread.Sleep(50);   // файл ещё пишется
                }
            }
            ShowWindow();
            if (text != null && text != "show")
            {
                ShowIncoming(text);
            }
        }

        static void ShowIncoming(string text)
        {
            var p = PresetCode.Decode(text);
            if (p != null)
            {
                ShowWindow();
                Dialogs.Incoming(p);
            }
        }

        public static void Log(Exception e)
        {
            try
            {
                Directory.CreateDirectory(Store.Dir);
                File.AppendAllText(Path.Combine(Store.Dir, "error.log"), DateTime.Now + "  " + e + "\r\n\r\n");
            }
            catch
            {
                // негде записать
            }
        }
    }
}
