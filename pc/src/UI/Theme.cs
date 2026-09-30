using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace EQ
{
    /// <summary>Цвета и шрифты как на Android: тёмная тема, карточки #1C1D21, акцент #3E7BFA, скругление 24.</summary>
    static class Theme
    {
        public static readonly uint[] Accents = { 0x3E7BFA, 0x7C5CFF, 0xFF2D95, 0xFF5A5A, 0xFF9F0A, 0x30D158, 0x00C7BE, 0xE5E5EA };

        public static readonly Color Bg = C(0x0B0C0F);
        public static readonly Color Side = C(0x121317);
        public static readonly Color Card = C(0x1C1D21);
        public static readonly Color Chip = C(0x2E2F35);
        public static readonly Color ChipHover = C(0x3A3B40);
        public static readonly Color Line = C(0x2A2B30);
        public static readonly Color Grey = C(0x8E9199);
        public static readonly Color Green = C(0x4CD964);
        public static readonly Color Orange = C(0xFFB340);
        public static readonly Color Red = C(0xFF5A5A);
        public static readonly Color White = Colors.White;

        public static readonly FontFamily Font = new FontFamily("Segoe UI Variable Text, Segoe UI");
        public static readonly FontFamily Display = new FontFamily("Segoe UI Variable Display, Segoe UI Semibold, Segoe UI");
        public static readonly FontFamily IconFont = new FontFamily("Segoe Fluent Icons, Segoe MDL2 Assets");
        public static readonly FontFamily Mono = new FontFamily("Cascadia Mono, Consolas");

        public static int AccentIndex
        {
            get { return Math.Max(0, Math.Min(Accents.Length - 1, Store.GetInt("accent", 0))); }
            set { Store.Set("accent", value); }
        }

        public static Color Accent
        {
            get { return C(Accents[AccentIndex]); }
        }

        /// <summary>Текст на цветной кнопке: на светлом цвете — чёрный.</summary>
        public static Color OnColor(Color c)
        {
            return 0.299 * c.R + 0.587 * c.G + 0.114 * c.B > 0.72 * 255 ? Colors.Black : Colors.White;
        }

        public static Color C(uint rgb, byte alpha = 255)
        {
            return Color.FromArgb(alpha, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
        }

        public static Color WithAlpha(Color c, double a)
        {
            return Color.FromArgb((byte)Math.Round(255 * Math.Max(0, Math.Min(1, a))), c.R, c.G, c.B);
        }

        public static SolidColorBrush B(Color c)
        {
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }
    }

    /// <summary>Значки шрифта Segoe Fluent Icons / Segoe MDL2 Assets (векторные, без эмодзи).</summary>
    static class Icons
    {
        public const string Equalizer = "";
        public const string Game = "";
        public const string Headphones = "";
        public const string Speakers = "";
        public const string Tv = "";
        public const string Settings = "";
        public const string Pc = "";
        public const string Play = "";
        public const string Add = "";
        public const string Delete = "";
        public const string Save = "";
        public const string Share = "";
        public const string Qr = "";
        public const string Keyboard = "";
        public const string Refresh = "";
        public const string Download = "";
        public const string Info = "";
        public const string Globe = "";
        public const string Color = "";
        public const string Folder = "";
        public const string Check = "";
        public const string Close = "";
        public const string Chevron = "";
        public const string Clock = "";
        public const string Warning = "";
        public const string Shield = "";
        public const string Power = "";
        public const string Walk = "";
        public const string Audio = "";
        public const string Mic = "";
        public const string Mute = "";
        public const string Link = "";
        public const string Copy = "";
        public const string Paste = "";
        public const string Phone = "";
        public const string Undo = "";
        public const string Open = "";
    }

    /// <summary>Готовые кусочки интерфейса (всё кодом, без XAML).</summary>
    static class Ui
    {
        public static TextBlock Text(string text, double size = 14, Color? color = null, FontWeight? weight = null)
        {
            return new TextBlock
            {
                Text = text,
                FontSize = size,
                FontFamily = Theme.Font,
                Foreground = Theme.B(color ?? Theme.White),
                FontWeight = weight ?? FontWeights.Normal,
                TextWrapping = TextWrapping.Wrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
            };
        }

        public static TextBlock Title(string text, double size = 17)
        {
            var t = Text(text, size, null, FontWeights.SemiBold);
            t.FontFamily = Theme.Display;
            t.TextWrapping = TextWrapping.NoWrap;
            return t;
        }

        public static TextBlock Note(string text, double size = 13)
        {
            return Text(text, size, Theme.Grey);
        }

        public static TextBlock Icon(string glyph, double size = 16, Color? color = null)
        {
            return new TextBlock
            {
                Text = glyph,
                FontFamily = Theme.IconFont,
                FontSize = size,
                Foreground = Theme.B(color ?? Theme.White),
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center,
            };
        }

        /// <summary>Карточка #1C1D21 со скруглением 24.</summary>
        public static Border Card(params UIElement[] children)
        {
            var sp = new StackPanel();
            foreach (var c in children)
            {
                if (c != null)
                {
                    sp.Children.Add(c);
                }
            }
            return new Border
            {
                Background = Theme.B(Theme.Card),
                CornerRadius = new CornerRadius(24),
                Padding = new Thickness(22, 20, 22, 20),
                Child = sp,
            };
        }

        /// <summary>Заголовок карточки со значком.</summary>
        public static FrameworkElement CardTitle(string glyph, string text, UIElement right = null)
        {
            var g = new Grid { Margin = new Thickness(0, 0, 0, 12) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var i = Icon(glyph, 16, Theme.Grey);
            i.Margin = new Thickness(0, 0, 10, 0);
            var t = Title(text);
            t.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(t, 1);
            g.Children.Add(i);
            g.Children.Add(t);
            if (right != null)
            {
                Grid.SetColumn(right, 2);
                g.Children.Add(right);
            }
            return g;
        }

        public static StackPanel Row(double gap, params UIElement[] children)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            bool first = true;
            foreach (var c in children)
            {
                if (c == null)
                {
                    continue;
                }
                if (!first && c is FrameworkElement fe)
                {
                    var m = fe.Margin;
                    fe.Margin = new Thickness(m.Left + gap, m.Top, m.Right, m.Bottom);
                }
                first = false;
                sp.Children.Add(c);
            }
            return sp;
        }

        public static WrapPanel Wrap(double gap, params UIElement[] children)
        {
            var wp = new WrapPanel();
            foreach (var c in children)
            {
                if (c is FrameworkElement fe)
                {
                    fe.Margin = new Thickness(0, 0, gap, gap);
                }
                if (c != null)
                {
                    wp.Children.Add(c);
                }
            }
            return wp;
        }

        public static T Mg<T>(this T e, double l, double t, double r, double b) where T : FrameworkElement
        {
            e.Margin = new Thickness(l, t, r, b);
            return e;
        }

        /// <summary>Две колонки одинаковой ширины с промежутком.</summary>
        public static Grid Columns(double gap, params UIElement[] cols)
        {
            var g = new Grid();
            for (int i = 0; i < cols.Length; i++)
            {
                if (i > 0)
                {
                    g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(gap) });
                }
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                Grid.SetColumn(cols[i], i * 2);
                g.Children.Add(cols[i]);
            }
            return g;
        }

        /// <summary>Строка «значок — текст — справа» (для списков).</summary>
        public static Grid ListRow(UIElement left, UIElement middle, UIElement right)
        {
            var g = new Grid { Margin = new Thickness(0, 6, 0, 6) };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            if (left != null)
            {
                g.Children.Add(left);
            }
            if (middle != null)
            {
                Grid.SetColumn(middle, 1);
                g.Children.Add(middle);
            }
            if (right != null)
            {
                Grid.SetColumn(right, 2);
                g.Children.Add(right);
            }
            return g;
        }

        /// <summary>Значок в круге цвета акцента.</summary>
        public static Border Badge(string glyph, double size, Color color)
        {
            return new Border
            {
                Width = size,
                Height = size,
                CornerRadius = new CornerRadius(size / 2),
                Background = Theme.B(Theme.WithAlpha(color, 0.16)),
                Child = Icon(glyph, size * 0.45, color),
            };
        }

        public static Border Tag(string text, Color color)
        {
            return new Border
            {
                CornerRadius = new CornerRadius(10),
                Background = Theme.B(Theme.WithAlpha(color, 0.18)),
                Padding = new Thickness(10, 3, 10, 4),
                VerticalAlignment = VerticalAlignment.Center,
                Child = Text(text, 12, color, FontWeights.SemiBold),
            };
        }

        public static TextBox Input(string text, string mono = null)
        {
            return new TextBox
            {
                Text = text,
                FontSize = 15,
                FontFamily = mono != null ? Theme.Mono : Theme.Font,
                Foreground = Theme.B(Theme.White),
                Background = Theme.B(Theme.Bg),
                BorderBrush = Theme.B(Theme.ChipHover),
                BorderThickness = new Thickness(1),
                CaretBrush = Theme.B(Theme.White),
                SelectionBrush = Theme.B(Theme.Accent),
                Padding = new Thickness(12, 10, 12, 10),
            };
        }
    }
}
