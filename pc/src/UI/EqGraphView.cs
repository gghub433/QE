using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace EQ
{
    /// <summary>
    /// Кривая эквалайзера: тяните мышью вверх-вниз — меняется полоса под курсором (шаг 0,5 дБ),
    /// колесо мыши — точная подстройка. Без onChange — только показ (предпросмотр пресета).
    /// </summary>
    sealed class EqGraphView : FrameworkElement
    {
        float[] gains;
        int bands;
        readonly float range;
        readonly Action<int, float> onChange;
        readonly bool compact;
        int active = -1;
        int hover = -1;

        const double MinF = 20, MaxF = 20000;
        static readonly (float F, string T)[] Labels = { (31.5f, "30"), (100, "100"), (300, "300"), (1000, "1k"), (3000, "3k"), (10000, "10k"), (18000, "20k") };

        public EqGraphView(float[] gains, int bands, Action<int, float> onChange = null, bool compact = false, float range = 15)
        {
            this.gains = gains;
            this.bands = bands;
            this.onChange = onChange;
            this.compact = compact;
            this.range = range;
            if (onChange != null)
            {
                Cursor = Cursors.SizeNS;
            }
            MouseLeftButtonDown += Down;
            MouseMove += Moved;
            MouseLeftButtonUp += Up;
            MouseLeave += (s, e) =>
            {
                hover = -1;
                InvalidateVisual();
            };
            MouseWheel += Wheel;
        }

        public void Update(float[] g, int b)
        {
            gains = g;
            bands = b;
            InvalidateVisual();
        }

        double PlotH
        {
            get { return ActualHeight - (compact ? 0 : 22); }
        }

        double X(double f)
        {
            return (Math.Log(f, 2) - Math.Log(MinF, 2)) / (Math.Log(MaxF, 2) - Math.Log(MinF, 2)) * ActualWidth;
        }

        double Y(double g)
        {
            double t = (range - Math.Max(-range, Math.Min(range, g))) / (2 * range);
            return 10 + t * (PlotH - 20);
        }

        List<Point> Points()
        {
            var f = Presets.Freqs(bands);
            var pts = new List<Point>();
            for (int i = 0; i < f.Length; i++)
            {
                pts.Add(new Point(X(f[i]), Y(i < gains.Length ? gains[i] : 0)));
            }
            return pts;
        }

        int Nearest(double x)
        {
            var pts = Points();
            int best = 0;
            for (int i = 1; i < pts.Count; i++)
            {
                if (Math.Abs(pts[i].X - x) < Math.Abs(pts[best].X - x))
                {
                    best = i;
                }
            }
            return best;
        }

        void Down(object s, MouseButtonEventArgs e)
        {
            if (onChange == null)
            {
                return;
            }
            active = Nearest(e.GetPosition(this).X);
            CaptureMouse();
            SetFrom(e.GetPosition(this).Y);
            e.Handled = true;
        }

        void Moved(object s, MouseEventArgs e)
        {
            if (onChange == null)
            {
                return;
            }
            if (IsMouseCaptured && active >= 0)
            {
                SetFrom(e.GetPosition(this).Y);
            }
            else
            {
                int h = Nearest(e.GetPosition(this).X);
                if (h != hover)
                {
                    hover = h;
                    InvalidateVisual();
                }
            }
        }

        void Up(object s, MouseButtonEventArgs e)
        {
            if (IsMouseCaptured)
            {
                ReleaseMouseCapture();
            }
            active = -1;
            InvalidateVisual();
        }

        void Wheel(object s, MouseWheelEventArgs e)
        {
            if (onChange == null || hover < 0 || hover >= gains.Length)
            {
                return;
            }
            float g = Math.Max(-range, Math.Min(range, gains[hover] + Math.Sign(e.Delta) * 0.5f));
            onChange(hover, g);
            e.Handled = true;
        }

        void SetFrom(double y)
        {
            if (active < 0 || active >= gains.Length)
            {
                return;
            }
            double t = (y - 10) / Math.Max(1, PlotH - 20);
            float g = Presets.Round05(Math.Max(-range, Math.Min(range, range - t * 2 * range)));
            if (Math.Abs(g - gains[active]) > 0.01f)
            {
                onChange(active, g);
            }
            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            double w = ActualWidth, h = PlotH;
            if (w <= 0 || h <= 0)
            {
                return;
            }
            var accent = Theme.Accent;
            dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, ActualHeight));
            // сетка: 0 дБ и ±половина диапазона, вертикали 100 Гц / 1 кГц / 10 кГц
            var dash = new Pen(Theme.B(Theme.WithAlpha(Colors.White, 0.07)), 1) { DashStyle = new DashStyle(new double[] { 4, 5 }, 0) };
            var zero = new Pen(Theme.B(Theme.WithAlpha(Colors.White, 0.18)), 1);
            var vert = new Pen(Theme.B(Theme.WithAlpha(Colors.White, 0.06)), 1);
            foreach (var g in new[] { range / 2, 0, -range / 2 })
            {
                dc.DrawLine(g == 0 ? zero : dash, new Point(0, Y(g)), new Point(w, Y(g)));
            }
            foreach (var f in new[] { 100.0, 1000, 10000 })
            {
                dc.DrawLine(vert, new Point(X(f), 0), new Point(X(f), h));
            }
            var pts = Points();
            var curve = Smooth(pts, w);
            // заливка до нуля
            var fill = new StreamGeometry();
            using (var c = fill.Open())
            {
                c.BeginFigure(new Point(0, Y(0)), true, true);
                AppendCurve(c, pts, w, true);
                c.LineTo(new Point(w, Y(0)), false, false);
            }
            fill.Freeze();
            var grad = new LinearGradientBrush(Theme.WithAlpha(accent, 0.38), Theme.WithAlpha(accent, 0.02), 90);
            dc.DrawGeometry(grad, null, fill);
            dc.DrawGeometry(null, new Pen(Theme.B(accent), compact ? 2.5 : 3.5) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round }, curve);
            if (!compact)
            {
                double r = bands > 15 ? 3.5 : 5.5;
                for (int i = 0; i < pts.Count; i++)
                {
                    bool big = i == active || active < 0 && i == hover;
                    dc.DrawEllipse(big ? Theme.B(accent) : Brushes.White, big ? new Pen(Brushes.White, 2) : null, pts[i], big ? r * 1.8 : r, big ? r * 1.8 : r);
                }
                int show = active >= 0 ? active : hover;
                if (show >= 0 && show < pts.Count && show < gains.Length)
                {
                    var f = Presets.Freqs(bands)[show];
                    var txt = (f >= 1000 ? (f / 1000).ToString("0.#", CultureInfo.InvariantCulture) + "k" : f.ToString("0.#", CultureInfo.InvariantCulture))
                              + " Hz  " + (gains[show] > 0 ? "+" : "") + gains[show].ToString("0.0", CultureInfo.CurrentCulture) + " dB";
                    var ft = Text(txt, 12.5, Brushes.White, true);
                    double bw = ft.Width + 20, bh = ft.Height + 8;
                    double bx = Math.Max(0, Math.Min(w - bw, pts[show].X - bw / 2));
                    double by = Math.Max(0, pts[show].Y - bh - 16);
                    dc.DrawRoundedRectangle(Theme.B(Theme.ChipHover), null, new Rect(bx, by, bw, bh), bh / 2, bh / 2);
                    dc.DrawText(ft, new Point(bx + 10, by + 4));
                }
                foreach (var l in Labels)
                {
                    var ft = Text(l.T, 11.5, Theme.B(Theme.Grey), false);
                    double x = Math.Max(0, Math.Min(w - ft.Width, X(l.F) - ft.Width / 2));
                    dc.DrawText(ft, new Point(x, h + 5));
                }
            }
        }

        FormattedText Text(string s, double size, Brush b, bool bold)
        {
            return new FormattedText(s, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
                new Typeface(Theme.Font, FontStyles.Normal, bold ? FontWeights.SemiBold : FontWeights.Normal, FontStretches.Normal),
                size, b, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        }

        /// <summary>Плавная кривая через точки (Катмулл-Ром), с полками до краёв.</summary>
        static Geometry Smooth(List<Point> pts, double w)
        {
            var geo = new StreamGeometry();
            using (var c = geo.Open())
            {
                if (pts.Count > 0)
                {
                    c.BeginFigure(new Point(0, pts[0].Y), false, false);
                    AppendCurve(c, pts, w, false);
                }
            }
            geo.Freeze();
            return geo;
        }

        static void AppendCurve(StreamGeometryContext c, List<Point> pts, double w, bool fromZero)
        {
            if (pts.Count == 0)
            {
                return;
            }
            var all = new List<Point> { new Point(0, pts[0].Y) };
            all.AddRange(pts);
            all.Add(new Point(w, pts[pts.Count - 1].Y));
            if (fromZero)
            {
                c.LineTo(all[0], false, false);
            }
            for (int i = 0; i < all.Count - 1; i++)
            {
                Point p0 = all[Math.Max(0, i - 1)], p1 = all[i], p2 = all[i + 1], p3 = all[Math.Min(all.Count - 1, i + 2)];
                var c1 = new Point(p1.X + (p2.X - p0.X) / 6, p1.Y + (p2.Y - p0.Y) / 6);
                var c2 = new Point(p2.X - (p3.X - p1.X) / 6, p2.Y - (p3.Y - p1.Y) / 6);
                c.BezierTo(c1, c2, p2, true, true);
            }
        }
    }
}
