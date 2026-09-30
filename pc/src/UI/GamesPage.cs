using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using static EQ.Lang;

namespace EQ
{
    /// <summary>
    /// «Игры»: библиотека Steam / Epic / свои игры с обложками и игровым временем,
    /// звук под игру и запуск через EQ.
    /// </summary>
    static class GamesPage
    {
        static string filter = "all";

        public static FrameworkElement Build(MainView main)
        {
            // игровой звук
            var nowText = Ui.Text("", 15, null, FontWeights.SemiBold);
            var nowNote = Ui.Note("", 13).Mg(0, 3, 0, 0);
            Action paintNow = () =>
            {
                var g = GameTracker.Running;
                nowText.Text = g == null ? L("games.noneRunning") : F("games.running", g.Name);
                nowNote.Text = g == null ? L("games.noneNote") : L(Games.ProfileKeys[Games.ProfileFor(g)]) + " · " + L("games.soundOn");
            };
            paintNow();
            var nowBox = new StackPanel();
            nowBox.Children.Add(nowText);
            nowBox.Children.Add(nowNote);
            var nowRow = Ui.ListRow(Ui.Badge(Icons.Game, 46, Theme.Accent).Mg(0, 0, 14, 0), nowBox, null);
            var auto = new SwitchRow(L("games.auto"), L("games.autoNote"), Games.AutoOn, on =>
            {
                Games.AutoOn = on;
                if (!on)
                {
                    Sound.ClearGame();
                }
                else if (GameTracker.Running != null)
                {
                    Games.ApplySoundFor(GameTracker.Running, false);
                }
            });
            var strength = new SliderRow(L("games.strength"), 0, 1, 0.05, Games.Strength, Fmt.Percent, v =>
            {
                Games.Strength = (float)v;
                if (GameTracker.Running != null)
                {
                    Games.ApplySoundFor(GameTracker.Running, true);
                }
            });
            var soundCard = Ui.Card(Ui.CardTitle(Icons.Walk, L("games.sound")), nowRow, auto, strength,
                Ui.Note(L("games.soundNote"), 12.5).Mg(0, 4, 0, 0));

            // библиотека
            var grid = new WrapPanel();
            var tabs = new List<Pill>();
            var toolbar = new Grid { Margin = new Thickness(0, 0, 0, 14) };
            toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var tabRow = new StackPanel { Orientation = Orientation.Horizontal };
            toolbar.Children.Add(tabRow);
            var add = new Pill(L("games.add"), Icons.Add);
            add.Click += () =>
            {
                var dlg = new Microsoft.Win32.OpenFileDialog { Filter = L("games.exeFilter") + "|*.exe", Title = L("games.add") };
                if (dlg.ShowDialog() == true)
                {
                    Games.AddOwn(dlg.FileName);
                    Games.Refresh(main.Dispatcher);
                    main.Toast(L("games.added"));
                }
            };
            var refresh = new Pill(null, Icons.Refresh);
            refresh.ToolTip = L("games.refresh");
            refresh.Click += () =>
            {
                Games.Refresh(main.Dispatcher);
                main.Toast(L("games.refreshing"));
            };
            var right = Ui.Row(8, add, refresh);
            Grid.SetColumn(right, 1);
            toolbar.Children.Add(right);
            var empty = Ui.Note(L("games.empty"), 14);

            Action paint = null;
            paint = () =>
            {
                var all = Games.All;
                tabRow.Children.Clear();
                tabs.Clear();
                foreach (var f in new[] { "all", "steam", "epic", "own" })
                {
                    int count = all.Count(g => Match(g, f));
                    if (f != "all" && count == 0)
                    {
                        continue;
                    }
                    var key = f;
                    var p = new Pill(L("games.f." + f) + "  " + count, null, false, filter == f).Mg(0, 0, 8, 0);
                    p.Click += () =>
                    {
                        filter = key;
                        paint();
                    };
                    tabs.Add(p);
                    tabRow.Children.Add(p);
                }
                grid.Children.Clear();
                foreach (var g in all.Where(x => Match(x, filter)))
                {
                    grid.Children.Add(Tile(g, main));
                }
                empty.Visibility = grid.Children.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            };
            paint();
            var libCard = Ui.Card(Ui.CardTitle(Icons.Game, L("games.library")), toolbar, grid, empty);

            main.OnGames = () =>
            {
                paintNow();
                paint();
            };
            main.OnSound = paintNow;
            return MainView.PageFrame(L("tab.games"), L("games.subtitle"), ApoBanner.Build(main), soundCard, libCard);
        }

        static bool Match(Game g, string f)
        {
            switch (f)
            {
                case "steam":
                    return g.Source == GameSource.Steam;
                case "epic":
                    return g.Source == GameSource.Epic;
                case "own":
                    return g.Source == GameSource.Own;
                default:
                    return true;
            }
        }

        static string SourceName(Game g)
        {
            return g.Source == GameSource.Steam ? "Steam" : g.Source == GameSource.Epic ? "Epic Games" : L("games.own");
        }

        static string Meta(Game g)
        {
            return g.PlaytimeMin >= 1 ? Duration(g.PlaytimeMin) : L("games.notPlayed");
        }

        /// <summary>Плитка игры: обложка 2:3, название, игровое время.</summary>
        static FrameworkElement Tile(Game g, MainView main)
        {
            const double w = 160, h = 240;
            var cover = Cover(g, w, h, 16);
            var running = GameTracker.Running != null && GameTracker.Running.Key == g.Key;
            var coverBox = new Grid { Width = w, Height = h };
            coverBox.Children.Add(cover);
            if (running)
            {
                coverBox.Children.Add(new Border
                {
                    CornerRadius = new CornerRadius(16),
                    BorderBrush = Theme.B(Theme.Accent),
                    BorderThickness = new Thickness(3),
                });
                var tag = Ui.Tag(L("games.now"), Theme.OnColor(Theme.Accent));
                tag.Background = Theme.B(Theme.Accent);
                tag.HorizontalAlignment = HorizontalAlignment.Left;
                tag.VerticalAlignment = VerticalAlignment.Top;
                tag.Margin = new Thickness(10);
                coverBox.Children.Add(tag);
            }
            var sp = new StackPanel { Width = w, Margin = new Thickness(0, 0, 18, 20), Cursor = Cursors.Hand };
            sp.Children.Add(coverBox);
            var name = Ui.Text(g.Name, 14, null, FontWeights.SemiBold).Mg(2, 10, 0, 0);
            name.MaxHeight = 40;
            sp.Children.Add(name);
            sp.Children.Add(Ui.Note(Meta(g) + " · " + SourceName(g), 12.5).Mg(2, 3, 0, 0));
            sp.MouseEnter += (s, e) => coverBox.Opacity = 0.85;
            sp.MouseLeave += (s, e) => coverBox.Opacity = 1;
            sp.MouseLeftButtonUp += (s, e) => Details(g, main);
            return sp;
        }

        /// <summary>Окно игры: обложка, время, звук под игру, «Играть».</summary>
        public static void Details(Game g, MainView main)
        {
            var info = new StackPanel { Margin = new Thickness(22, 0, 0, 0) };
            var title = Ui.Title(g.Name, 22);
            title.TextWrapping = TextWrapping.Wrap;
            title.Margin = new Thickness(0, 0, 36, 0);
            info.Children.Add(title);
            info.Children.Add(Ui.Row(8, Ui.Tag(SourceName(g), Theme.Accent), GameTracker.Running?.Key == g.Key ? Ui.Tag(L("games.now"), Theme.Green) : null).Mg(0, 8, 0, 14));
            info.Children.Add(Stat(Icons.Clock, L("games.playtime"), g.PlaytimeMin >= 1 ? Duration(g.PlaytimeMin) : L("games.notPlayed")));
            if (g.LastPlayed > new DateTime(2005, 1, 1))
            {
                info.Children.Add(Stat(Icons.Game, L("games.lastPlayed"), g.LastPlayed.ToLocalTime().ToString("D", Lang.Culture)));
            }
            info.Children.Add(Ui.Text(L("games.soundFor"), 14, null, FontWeights.SemiBold).Mg(0, 16, 0, 8));
            var profiles = new WrapPanel();
            var pills = new List<Pill>();
            string[] glyphs = { Icons.Mute, Icons.Walk, Icons.Audio, Icons.Mic };
            int cur = Games.ProfileFor(g);
            for (int i = 0; i < Games.ProfileKeys.Length; i++)
            {
                int idx = i;
                var p = new Pill(L(Games.ProfileKeys[i]), glyphs[i], false, cur == i, 13).Mg(0, 0, 8, 8);
                p.Click += () =>
                {
                    Games.SetProfile(g, idx);
                    foreach (var x in pills)
                    {
                        x.Selected = x == p;
                    }
                };
                pills.Add(p);
                profiles.Children.Add(p);
            }
            info.Children.Add(profiles);
            info.Children.Add(Ui.Note(L("games.profileNote"), 12.5).Mg(0, 2, 0, 16));
            var play = new Pill(L("games.play"), Icons.Play, true).Wide();
            play.Click += () =>
            {
                Games.Play(g);
                main.CloseOverlay();
                main.Toast(F("games.launching", g.Name));
            };
            info.Children.Add(play);
            var extra = new List<UIElement>();
            if (g.InstallDir != null)
            {
                var folder = new Pill(L("games.folder"), Icons.Folder, false, false, 13);
                folder.Click += () => Games.OpenFolder(g);
                extra.Add(folder);
            }
            if (g.Source == GameSource.Steam)
            {
                var st = new Pill(L("games.inSteam"), Icons.Open, false, false, 13);
                st.Click += () => Games.OpenStore(g);
                extra.Add(st);
            }
            if (g.Source == GameSource.Own)
            {
                var rm = new Pill(L("games.remove"), Icons.Delete, false, false, 13);
                rm.Click += () =>
                {
                    Games.RemoveOwn(g);
                    Games.Refresh(main.Dispatcher);
                    main.CloseOverlay();
                };
                extra.Add(rm);
            }
            info.Children.Add(Ui.Wrap(8, extra.ToArray()).Mg(0, 10, 0, 0));

            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var cover = Cover(g, 200, 300, 18);
            cover.VerticalAlignment = VerticalAlignment.Top;
            row.Children.Add(cover);
            Grid.SetColumn(info, 1);
            row.Children.Add(info);
            main.ShowOverlay(row, 680);
        }

        static FrameworkElement Stat(string glyph, string label, string value)
        {
            var g = Ui.ListRow(Ui.Icon(glyph, 15, Theme.Grey).Mg(0, 0, 10, 0), Ui.Note(label, 13.5), Ui.Text(value, 13.5, null, FontWeights.SemiBold));
            g.Margin = new Thickness(0, 3, 0, 3);
            return g;
        }

        // =====================================================================
        // Обложки
        // =====================================================================

        static readonly Dictionary<string, BitmapSource> cache = new Dictionary<string, BitmapSource>();

        /// <summary>Обложка со скруглением; пока грузится или её нет — цветная заглушка с названием.</summary>
        public static Border Cover(Game g, double w, double h, double radius)
        {
            var b = new Border { Width = w, Height = h, CornerRadius = new CornerRadius(radius), ClipToBounds = true };
            b.Background = Placeholder(g);
            b.Child = PlaceholderText(g, w);
            var path = g.CoverPath;
            if (path == null)
            {
                return b;
            }
            if (cache.TryGetValue(path, out var bmp))
            {
                SetImage(b, bmp);
                return b;
            }
            var ui = b.Dispatcher;
            Task.Run(() =>
            {
                try
                {
                    var img = new BitmapImage();
                    img.BeginInit();
                    img.CacheOption = BitmapCacheOption.OnLoad;
                    img.DecodePixelWidth = 400;
                    img.UriSource = new Uri(path);
                    img.EndInit();
                    img.Freeze();
                    ui.Invoke(() =>
                    {
                        cache[path] = img;
                        SetImage(b, img);
                    });
                }
                catch
                {
                    // битая картинка — остаётся заглушка
                }
            });
            return b;
        }

        static void SetImage(Border b, BitmapSource img)
        {
            b.Background = new ImageBrush(img) { Stretch = Stretch.UniformToFill, AlignmentX = AlignmentX.Center, AlignmentY = AlignmentY.Top };
            b.Child = null;
        }

        static Brush Placeholder(Game g)
        {
            int k = g.DemoColor >= 0 ? g.DemoColor : Math.Abs((g.Name ?? "").GetHashCode());
            var a = Theme.C(Theme.Accents[k % Theme.Accents.Length]);
            var c = Theme.C(Theme.Accents[(k / 3 + 2) % Theme.Accents.Length]);
            var brush = new LinearGradientBrush
            {
                StartPoint = new Point(0, 0),
                EndPoint = new Point(1, 1),
            };
            brush.GradientStops.Add(new GradientStop(a, 0));
            brush.GradientStops.Add(new GradientStop(Theme.WithAlpha(c, 0.9), 0.55));
            brush.GradientStops.Add(new GradientStop(Theme.C(0x101116), 1));
            brush.Freeze();
            return brush;
        }

        static FrameworkElement PlaceholderText(Game g, double w)
        {
            double size = w > 180 ? 24 : 19;
            int longest = (g.Name ?? "").Split(' ').Select(s => s.Length).DefaultIfEmpty(1).Max();
            size = Math.Max(11, Math.Min(size, (w - 30) / (Math.Max(1, longest) * 0.6)));
            var t = Ui.Title(g.Name, size);
            t.TextWrapping = TextWrapping.Wrap;
            t.TextTrimming = TextTrimming.CharacterEllipsis;
            t.VerticalAlignment = VerticalAlignment.Bottom;
            t.Margin = new Thickness(14, 0, 14, 16);
            t.MaxHeight = 130;
            return t;
        }
    }
}
