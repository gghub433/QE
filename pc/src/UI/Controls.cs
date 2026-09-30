using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace EQ
{
    /// <summary>Кнопка-капсула: обычная, выбранная (цвет акцента) или главная (залитая акцентом).</summary>
    sealed class Pill : Border
    {
        public event Action Click;

        readonly TextBlock label;
        readonly TextBlock icon;
        bool selected;
        readonly bool filled;
        bool hover;

        public Pill(string text, string glyph = null, bool filled = false, bool selected = false, double size = 14)
        {
            this.filled = filled;
            this.selected = selected;
            CornerRadius = new CornerRadius(20);
            Padding = new Thickness(text == null ? 10 : 16, 8, text == null ? 10 : 16, 9);
            Cursor = Cursors.Hand;
            VerticalAlignment = VerticalAlignment.Center;
            HorizontalAlignment = HorizontalAlignment.Left;
            var sp = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
            if (glyph != null)
            {
                icon = Ui.Icon(glyph, size);
                if (text != null)
                {
                    icon.Margin = new Thickness(0, 1, 8, 0);
                }
                sp.Children.Add(icon);
            }
            if (text != null)
            {
                label = Ui.Text(text, size, null, FontWeights.SemiBold);
                label.TextWrapping = TextWrapping.NoWrap;
                sp.Children.Add(label);
            }
            Child = sp;
            MouseEnter += (s, e) =>
            {
                hover = true;
                Paint();
            };
            MouseLeave += (s, e) =>
            {
                hover = false;
                Paint();
            };
            MouseLeftButtonUp += (s, e) =>
            {
                e.Handled = true;
                Click?.Invoke();
            };
            Paint();
        }

        public bool Selected
        {
            get { return selected; }
            set
            {
                selected = value;
                Paint();
            }
        }

        public string Label
        {
            set
            {
                if (label != null)
                {
                    label.Text = value;
                }
            }
        }

        public Pill Wide()
        {
            HorizontalAlignment = HorizontalAlignment.Stretch;
            Padding = new Thickness(16, 11, 16, 12);
            CornerRadius = new CornerRadius(22);
            return this;
        }

        void Paint()
        {
            var accent = Theme.Accent;
            Color bg, fg;
            if (filled || selected)
            {
                bg = hover ? Blend(accent, Colors.White, 0.12) : accent;
                fg = Theme.OnColor(accent);
            }
            else
            {
                bg = hover ? Theme.ChipHover : Theme.Chip;
                fg = Theme.White;
            }
            Background = Theme.B(bg);
            if (label != null)
            {
                label.Foreground = Theme.B(fg);
            }
            if (icon != null)
            {
                icon.Foreground = Theme.B(fg);
            }
        }

        static Color Blend(Color a, Color b, double t)
        {
            return Color.FromRgb((byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));
        }
    }

    /// <summary>Переключатель в стиле телефона.</summary>
    sealed class Switch : FrameworkElement
    {
        public event Action<bool> Toggled;
        bool on;

        public Switch(bool on)
        {
            this.on = on;
            Width = 46;
            Height = 26;
            Cursor = Cursors.Hand;
            VerticalAlignment = VerticalAlignment.Center;
            MouseLeftButtonUp += (s, e) =>
            {
                e.Handled = true;
                IsOn = !IsOn;
                Toggled?.Invoke(IsOn);
            };
        }

        public bool IsOn
        {
            get { return on; }
            set
            {
                on = value;
                InvalidateVisual();
            }
        }

        protected override void OnRender(DrawingContext dc)
        {
            var r = new Rect(0, 0, ActualWidth, ActualHeight);
            dc.DrawRoundedRectangle(Theme.B(on ? Theme.Accent : Theme.ChipHover), null, r, r.Height / 2, r.Height / 2);
            double d = r.Height - 6;
            double x = on ? r.Width - d - 3 : 3;
            dc.DrawEllipse(Brushes.White, null, new Point(x + d / 2, r.Height / 2), d / 2, d / 2);
        }
    }

    /// <summary>Плоский ползунок: дорожка, заливка цвета акцента, белый кружок.</summary>
    sealed class FlatSlider : FrameworkElement
    {
        public double Min, Max, Step;
        double value;
        public event Action<double> ValueChanged;
        public event Action DragEnded;

        public FlatSlider(double min, double max, double step, double value)
        {
            Min = min;
            Max = max;
            Step = step;
            this.value = value;
            Height = 28;
            Cursor = Cursors.Hand;
            MouseLeftButtonDown += (s, e) =>
            {
                CaptureMouse();
                Move(e.GetPosition(this).X);
                e.Handled = true;
            };
            MouseMove += (s, e) =>
            {
                if (IsMouseCaptured)
                {
                    Move(e.GetPosition(this).X);
                }
            };
            MouseLeftButtonUp += (s, e) =>
            {
                if (IsMouseCaptured)
                {
                    ReleaseMouseCapture();
                    DragEnded?.Invoke();
                }
            };
            MouseWheel += (s, e) =>
            {
                Value = value + Math.Sign(e.Delta) * Step;
                ValueChanged?.Invoke(value);
                e.Handled = true;
            };
        }

        public double Value
        {
            get { return value; }
            set
            {
                this.value = Math.Max(Min, Math.Min(Max, value));
                InvalidateVisual();
            }
        }

        void Move(double x)
        {
            const double pad = 10;
            double t = (x - pad) / Math.Max(1, ActualWidth - pad * 2);
            double v = Min + Math.Max(0, Math.Min(1, t)) * (Max - Min);
            if (Step > 0)
            {
                v = Math.Round(v / Step) * Step;
            }
            if (Math.Abs(v - value) > 1e-6)
            {
                Value = v;
                ValueChanged?.Invoke(value);
            }
        }

        protected override void OnRender(DrawingContext dc)
        {
            const double pad = 10;
            double w = ActualWidth - pad * 2, cy = ActualHeight / 2;
            double t = (value - Min) / Math.Max(1e-9, Max - Min);
            dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, ActualWidth, ActualHeight));
            dc.DrawRoundedRectangle(Theme.B(Theme.ChipHover), null, new Rect(pad, cy - 3, w, 6), 3, 3);
            // для баланса (-1…1) заливка идёт от центра
            double from = Min < 0 && Max > 0 ? (0 - Min) / (Max - Min) : 0;
            double a = Math.Min(from, t), b = Math.Max(from, t);
            dc.DrawRoundedRectangle(Theme.B(Theme.Accent), null, new Rect(pad + a * w, cy - 3, Math.Max(0, (b - a) * w), 6), 3, 3);
            dc.DrawEllipse(Brushes.White, null, new Point(pad + t * w, cy), 10, 10);
        }
    }

    /// <summary>Строка: название, значение справа и ползунок под ними.</summary>
    sealed class SliderRow : StackPanel
    {
        public readonly FlatSlider Slider;
        readonly TextBlock valueText;
        readonly Func<double, string> format;

        public SliderRow(string title, double min, double max, double step, double value, Func<double, string> format, Action<double> changed)
        {
            this.format = format;
            Margin = new Thickness(0, 4, 0, 6);
            var head = new Grid();
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            head.Children.Add(Ui.Text(title, 14));
            valueText = Ui.Text(format(value), 14, Theme.Grey, FontWeights.SemiBold);
            Grid.SetColumn(valueText, 1);
            head.Children.Add(valueText);
            Children.Add(head);
            Slider = new FlatSlider(min, max, step, value);
            Slider.ValueChanged += v =>
            {
                valueText.Text = format(v);
                changed(v);
            };
            Children.Add(Slider);
        }

        public void Set(double v)
        {
            Slider.Value = v;
            valueText.Text = format(v);
        }
    }

    /// <summary>Строка с переключателем: название, пояснение, переключатель справа.</summary>
    sealed class SwitchRow : Grid
    {
        public readonly Switch Switch;

        public SwitchRow(string title, string note, bool on, Action<bool> toggled)
        {
            Margin = new Thickness(0, 6, 0, 6);
            ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 16, 0) };
            sp.Children.Add(Ui.Text(title, 14));
            if (note != null)
            {
                sp.Children.Add(Ui.Note(note, 12.5).Mg(0, 2, 0, 0));
            }
            Children.Add(sp);
            Switch = new Switch(on);
            Switch.Toggled += toggled;
            SetColumn(Switch, 1);
            Children.Add(Switch);
        }
    }

    /// <summary>Прокрутка без светлой полосы Windows: тонкий свой ползунок справа.</summary>
    sealed class ScrollHost : Grid
    {
        public readonly ScrollViewer Viewer;
        readonly Border thumb;

        public ScrollHost(UIElement content)
        {
            Viewer = new ScrollViewer
            {
                VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                PanningMode = PanningMode.VerticalOnly,
                Content = content,
                Focusable = false,
            };
            Children.Add(Viewer);
            thumb = new Border
            {
                Width = 4,
                CornerRadius = new CornerRadius(2),
                Background = Theme.B(Theme.WithAlpha(Colors.White, 0.22)),
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, 0, 4, 0),
                IsHitTestVisible = false,
                Visibility = Visibility.Collapsed,
            };
            Children.Add(thumb);
            Viewer.ScrollChanged += (s, e) => UpdateThumb();
            SizeChanged += (s, e) => UpdateThumb();
        }

        void UpdateThumb()
        {
            double view = Viewer.ViewportHeight, total = Viewer.ExtentHeight;
            if (total <= view + 1 || view <= 0)
            {
                thumb.Visibility = Visibility.Collapsed;
                return;
            }
            thumb.Visibility = Visibility.Visible;
            double h = Math.Max(40, ActualHeight * view / total);
            thumb.Height = h;
            double top = (ActualHeight - h) * Viewer.VerticalOffset / Math.Max(1, total - view);
            thumb.Margin = new Thickness(0, top, 4, 0);
        }
    }

    /// <summary>QR-код (белый фон, чёрные модули).</summary>
    sealed class QrView : FrameworkElement
    {
        readonly bool[,] m;

        public QrView(string text)
        {
            m = QrCode.Encode(text);
        }

        protected override void OnRender(DrawingContext dc)
        {
            double s = Math.Min(ActualWidth, ActualHeight);
            dc.DrawRoundedRectangle(Brushes.White, null, new Rect(0, 0, s, s), 20, 20);
            if (m == null)
            {
                return;
            }
            int n = m.GetLength(0);
            double cell = (s - 32) / n, o = 16;
            var geo = new StreamGeometry();
            using (var g = geo.Open())
            {
                for (int y = 0; y < n; y++)
                {
                    for (int x = 0; x < n; x++)
                    {
                        if (m[y, x])
                        {
                            double px = o + x * cell, py = o + y * cell;
                            g.BeginFigure(new Point(px, py), true, true);
                            g.LineTo(new Point(px + cell + 0.3, py), false, false);
                            g.LineTo(new Point(px + cell + 0.3, py + cell + 0.3), false, false);
                            g.LineTo(new Point(px, py + cell + 0.3), false, false);
                        }
                    }
                }
            }
            geo.Freeze();
            dc.DrawGeometry(Brushes.Black, null, geo);
        }
    }

    static class Fmt
    {
        public static string Db(double v, bool plus = false)
        {
            return (plus && v > 0 ? "+" : "") + v.ToString("0.0", CultureInfo.CurrentCulture) + " dB";
        }

        public static string Percent(double v)
        {
            return Math.Round(v * 100) + "%";
        }
    }
}
