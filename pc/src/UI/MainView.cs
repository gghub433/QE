using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using static EQ.Lang;

namespace EQ
{
    /// <summary>
    /// Всё окно EQ: слева меню (Устройство, Эквалайзер, Игры, Настройки) и состояние,
    /// справа страница; поверх — окно-карточка (коды, игры) и короткие уведомления.
    /// </summary>
    sealed class MainView : Grid
    {
        public static MainView Current;

        public const int PDevice = 0, PEq = 1, PGames = 2, PSettings = 3;
        static readonly string[] NavKeys = { "tab.device", "tab.eq", "tab.games", "tab.settings" };
        static readonly string[] NavIcons = { Icons.Headphones, Icons.Equalizer, Icons.Game, Icons.Settings };

        readonly Border pageHost = new Border();
        readonly Grid overlay = new Grid { Visibility = Visibility.Collapsed };
        readonly Border toast;
        readonly TextBlock toastText;
        readonly DispatcherTimer toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.2) };
        Border sidebar;
        Border[] navButtons;
        StackPanel status;
        int page = -1;

        /// <summary>Обновления для открытой страницы (сбрасываются при смене страницы).</summary>
        public Action OnSound, OnGames, OnUpdates;

        public int Page
        {
            get { return page; }
        }

        public MainView()
        {
            Current = this;
            Background = Theme.B(Theme.Bg);
            TextOptions.SetTextFormattingMode(this, TextFormattingMode.Ideal);
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(250) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var content = new Grid();
            SetColumn(content, 1);
            content.Children.Add(pageHost);

            overlay.Background = Theme.B(Theme.WithAlpha(Colors.Black, 0.62));
            overlay.MouseLeftButtonDown += (s, e) =>
            {
                if (e.OriginalSource == overlay)
                {
                    CloseOverlay();
                }
            };
            content.Children.Add(overlay);

            toastText = Ui.Text("", 14, null, FontWeights.SemiBold);
            toast = new Border
            {
                Background = Theme.B(Theme.ChipHover),
                CornerRadius = new CornerRadius(20),
                Padding = new Thickness(20, 10, 20, 11),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(0, 0, 0, 28),
                Child = toastText,
                Visibility = Visibility.Collapsed,
                IsHitTestVisible = false,
            };
            content.Children.Add(toast);
            toastTimer.Tick += (s, e) =>
            {
                toastTimer.Stop();
                toast.Visibility = Visibility.Collapsed;
            };
            Children.Add(content);

            BuildSidebar();
            Sound.Changed += () =>
            {
                UpdateStatus();
                OnSound?.Invoke();
            };
            GameTracker.Changed += () =>
            {
                UpdateStatus();
                OnSound?.Invoke();
                OnGames?.Invoke();
            };
            Games.LibraryChanged += () => OnGames?.Invoke();
            Updates.Changed += () => OnUpdates?.Invoke();
            ShowPage(Store.GetInt("page", PEq));
        }

        void BuildSidebar()
        {
            if (sidebar != null)
            {
                Children.Remove(sidebar);
            }
            var sp = new DockPanel { LastChildFill = false };
            // логотип
            var logo = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(22, 26, 0, 26) };
            DockPanel.SetDock(logo, Dock.Top);
            var mark = new Border
            {
                Width = 40,
                Height = 40,
                CornerRadius = new CornerRadius(12),
                Background = Theme.B(Theme.C(0x111317)),
                BorderBrush = Theme.B(Theme.Line),
                BorderThickness = new Thickness(1),
                Child = Bars(),
            };
            logo.Children.Add(mark);
            var name = new StackPanel { Margin = new Thickness(12, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            name.Children.Add(Ui.Title("EQ", 20));
            name.Children.Add(Ui.Note(L("app.forPc"), 12));
            logo.Children.Add(name);
            sp.Children.Add(logo);

            navButtons = new Border[NavKeys.Length];
            for (int i = 0; i < NavKeys.Length; i++)
            {
                int idx = i;
                var row = new StackPanel { Orientation = Orientation.Horizontal };
                row.Children.Add(Ui.Icon(NavIcons[i], 17));
                row.Children.Add(Ui.Text(L(NavKeys[i]), 15, null, FontWeights.SemiBold).Mg(14, 0, 0, 1));
                var b = new Border
                {
                    CornerRadius = new CornerRadius(14),
                    Padding = new Thickness(16, 11, 16, 12),
                    Margin = new Thickness(12, 2, 12, 2),
                    Child = row,
                    Cursor = Cursors.Hand,
                    Background = Brushes.Transparent,
                };
                b.MouseLeftButtonUp += (s, e) => ShowPage(idx);
                b.MouseEnter += (s, e) =>
                {
                    if (page != idx)
                    {
                        b.Background = Theme.B(Theme.WithAlpha(Colors.White, 0.05));
                    }
                };
                b.MouseLeave += (s, e) => PaintNav();
                DockPanel.SetDock(b, Dock.Top);
                navButtons[i] = b;
                sp.Children.Add(b);
            }

            status = new StackPanel();
            var statusCard = new Border
            {
                Background = Theme.B(Theme.Card),
                CornerRadius = new CornerRadius(18),
                Padding = new Thickness(16, 14, 16, 14),
                Margin = new Thickness(12, 0, 12, 18),
                Child = status,
            };
            DockPanel.SetDock(statusCard, Dock.Bottom);
            sp.Children.Add(statusCard);

            sidebar = new Border
            {
                Background = Theme.B(Theme.Side),
                BorderBrush = Theme.B(Theme.Line),
                BorderThickness = new Thickness(0, 0, 1, 0),
                Child = sp,
            };
            Children.Add(sidebar);
            PaintNav();
            UpdateStatus();
        }

        /// <summary>Пять полос логотипа (как значок приложения).</summary>
        static FrameworkElement Bars()
        {
            var c = new Canvas { Width = 26, Height = 22 };
            double[] heights = { 18.5, 22, 13.9, 10.4, 16.2 };   // как на значке Android
            for (int i = 0; i < heights.Length; i++)
            {
                var r = new Border
                {
                    Width = 3.4,
                    Height = heights[i],
                    CornerRadius = new CornerRadius(1.7),
                    Background = Theme.B(Theme.Accent),
                };
                Canvas.SetLeft(r, i * 5.4);
                Canvas.SetTop(r, 22 - heights[i]);
                c.Children.Add(r);
            }
            return c;
        }

        void PaintNav()
        {
            for (int i = 0; i < navButtons.Length; i++)
            {
                bool sel = i == page;
                navButtons[i].Background = sel ? Theme.B(Theme.WithAlpha(Theme.Accent, 0.16)) : Brushes.Transparent;
                var row = (StackPanel)navButtons[i].Child;
                ((TextBlock)row.Children[0]).Foreground = Theme.B(sel ? Theme.Accent : Theme.Grey);
                ((TextBlock)row.Children[1]).Foreground = Theme.B(sel ? Theme.White : Theme.C(0xC8CAD0));
            }
        }

        /// <summary>Внизу меню: работает ли EQ на весь звук, какая игра идёт, выключатель.</summary>
        void UpdateStatus()
        {
            if (status == null)
            {
                return;
            }
            status.Children.Clear();
            Color dot;
            string text;
            if (!Apo.Installed)
            {
                dot = Theme.Orange;
                text = L("status.noApo");
            }
            else if (Apo.NeedsAccess)
            {
                dot = Theme.Orange;
                text = L("status.access");
            }
            else if (!Sound.Enabled)
            {
                dot = Theme.Grey;
                text = L("status.off");
            }
            else
            {
                dot = Theme.Green;
                text = L("status.on");
            }
            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            left.Children.Add(new Border { Width = 9, Height = 9, CornerRadius = new CornerRadius(4.5), Background = Theme.B(dot), VerticalAlignment = VerticalAlignment.Center });
            left.Children.Add(Ui.Text("EQ", 14, null, FontWeights.SemiBold).Mg(8, 0, 0, 1));
            head.Children.Add(left);
            var sw = new Switch(Sound.Enabled);
            sw.Toggled += on =>
            {
                Sound.Enabled = on;
                Sound.Apply();
            };
            Grid.SetColumn(sw, 1);
            head.Children.Add(sw);
            status.Children.Add(head);
            status.Children.Add(Ui.Note(text, 12.5).Mg(0, 8, 0, 0));
            var g = GameTracker.Running;
            if (g != null)
            {
                var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
                row.Children.Add(Ui.Icon(Icons.Game, 14, Theme.Accent));
                var t = Ui.Text(g.Name, 13, null, FontWeights.SemiBold).Mg(8, 0, 0, 0);
                t.TextWrapping = TextWrapping.NoWrap;
                t.MaxWidth = 170;
                row.Children.Add(t);
                status.Children.Add(row);
                status.Children.Add(Ui.Note(L(Games.ProfileKeys[Games.ProfileFor(g)]), 12).Mg(22, 2, 0, 0));
            }
        }

        public void ShowPage(int i)
        {
            i = Math.Max(0, Math.Min(NavKeys.Length - 1, i));
            OnSound = OnGames = OnUpdates = null;
            page = i;
            Store.Set("page", i);
            CloseOverlay();
            UIElement body;
            switch (i)
            {
                case PDevice:
                    body = DevicePage.Build(this);
                    break;
                case PGames:
                    body = GamesPage.Build(this);
                    break;
                case PSettings:
                    body = SettingsPage.Build(this);
                    break;
                default:
                    body = EqPage.Build(this);
                    break;
            }
            pageHost.Child = body;
            PaintNav();
            if (body is FrameworkElement fe && !Store.Demo)
            {
                fe.Opacity = 0;
                fe.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
            }
        }

        /// <summary>Язык или цвет поменялись — перерисовать всё.</summary>
        public void Rebuild()
        {
            BuildSidebar();
            ShowPage(page);
        }

        /// <summary>Страница: крупный заголовок, подзаголовок и карточки с прокруткой.</summary>
        public static FrameworkElement PageFrame(string title, string subtitle, params UIElement[] cards)
        {
            var sp = new StackPanel { Margin = new Thickness(36, 30, 36, 36), MaxWidth = 1100 };
            var t = Ui.Title(title, 32);
            sp.Children.Add(t);
            if (subtitle != null)
            {
                sp.Children.Add(Ui.Note(subtitle, 14).Mg(0, 4, 0, 0));
            }
            var body = new StackPanel { Margin = new Thickness(0, 22, 0, 0) };
            foreach (var c in cards)
            {
                if (c == null)
                {
                    continue;
                }
                if (c is FrameworkElement fe)
                {
                    fe.Margin = new Thickness(0, 0, 0, 16);
                }
                body.Children.Add(c);
            }
            sp.Children.Add(body);
            return new ScrollHost(sp);
        }

        // =====================================================================
        // Окно-карточка поверх страницы
        // =====================================================================

        public void ShowOverlay(UIElement content, double width = 540)
        {
            overlay.Children.Clear();
            var card = new Border
            {
                Background = Theme.B(Theme.Card),
                CornerRadius = new CornerRadius(26),
                Padding = new Thickness(26, 24, 26, 24),
                Width = width,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                BorderBrush = Theme.B(Theme.Line),
                BorderThickness = new Thickness(1),
            };
            var grid = new Grid();
            grid.Children.Add(content);
            var close = new Pill(null, Icons.Close, false, false, 12)
            {
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, -6, -8, 0),
            };
            close.Click += CloseOverlay;
            grid.Children.Add(close);
            card.Child = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
                Content = grid,
                MaxHeight = 700,
            };
            overlay.Children.Add(card);
            overlay.Visibility = Visibility.Visible;
        }

        public void CloseOverlay()
        {
            overlay.Visibility = Visibility.Collapsed;
            overlay.Children.Clear();
        }

        public bool OverlayOpen
        {
            get { return overlay.Visibility == Visibility.Visible; }
        }

        public void Toast(string text)
        {
            toastText.Text = text;
            toast.Visibility = Visibility.Visible;
            toastTimer.Stop();
            toastTimer.Start();
        }
    }
}
