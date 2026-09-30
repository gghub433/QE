using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using static EQ.Lang;

namespace EQ
{
    /// <summary>«Эквалайзер»: кривая 9/15/31 полоса на весь звук ПК, A/B, обработка, пресеты, коды и QR.</summary>
    static class EqPage
    {
        public static FrameworkElement Build(MainView main)
        {
            var name = Ui.Title(PresetTitle(), 22);
            var forText = Ui.Note(ForText(), 13.5).Mg(0, 3, 0, 0);
            var enabled = new Switch(Sound.Enabled);
            enabled.Toggled += on =>
            {
                Sound.Enabled = on;
                Sound.Apply();
            };
            var head = new Grid { Margin = new Thickness(0, 0, 0, 14) };
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var left = new StackPanel();
            left.Children.Add(name);
            left.Children.Add(forText);
            head.Children.Add(left);
            Grid.SetColumn(enabled, 1);
            head.Children.Add(enabled);

            EqGraphView graph = null;
            graph = new EqGraphView(Sound.Gains, Sound.Bands, (i, g) =>
            {
                if (i < Sound.Gains.Length)
                {
                    Sound.Gains[i] = g;
                    Sound.PresetName = "";
                    Sound.Apply();
                    graph.Update(Sound.Gains, Sound.Bands);
                }
            }) { Height = 290 };
            graph.Opacity = Sound.Enabled ? 1 : 0.4;

            var bandPills = new List<Pill>();
            foreach (var n in new[] { 9, 15, 31 })
            {
                var p = new Pill(n.ToString(), null, false, Sound.Bands == n);
                p.Click += () =>
                {
                    Sound.SetBands(n);
                    graph.Update(Sound.Gains, Sound.Bands);
                    foreach (var b in bandPills)
                    {
                        b.Selected = b == p;
                    }
                };
                bandPills.Add(p);
            }
            var ab = new Pill(L("eq.ab"), Icons.Undo);
            ab.ToolTip = L("eq.abHint");
            ab.MouseLeftButtonDown += (s, e) =>
            {
                Sound.AbBypass = true;
                Sound.WriteNow();
                ab.Selected = true;
                ab.Label = L("eq.abOriginal");
                graph.Opacity = 0.4;
                ab.CaptureMouse();
                e.Handled = true;
            };
            MouseButtonEventHandler release = (s, e) =>
            {
                if (!Sound.AbBypass)
                {
                    return;
                }
                Sound.AbBypass = false;
                Sound.WriteNow();
                ab.Selected = false;
                ab.Label = L("eq.ab");
                graph.Opacity = Sound.Enabled ? 1 : 0.4;
                ab.ReleaseMouseCapture();
            };
            ab.MouseLeftButtonUp += release;

            var bottom = new Grid { Margin = new Thickness(0, 14, 0, 0) };
            bottom.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            bottom.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var bands = Ui.Row(8, bandPills.Cast<UIElement>().ToArray());
            bands.Children.Insert(0, Ui.Note(L("eq.bands"), 13).Mg(0, 0, 10, 0));
            ((FrameworkElement)bands.Children[0]).VerticalAlignment = VerticalAlignment.Center;
            bottom.Children.Add(bands);
            Grid.SetColumn(ab, 1);
            bottom.Children.Add(ab);

            var gameTag = new ContentControl();
            Action paintGame = () =>
            {
                gameTag.Content = Sound.GameName == null ? null
                    : Ui.Row(8, Ui.Icon(Icons.Game, 14, Theme.Accent), Ui.Text(F("eq.gameLayer", Sound.GameName), 13.5, Theme.Accent, FontWeights.SemiBold))
                        .Mg(0, 12, 0, 0);
            };
            paintGame();

            var curveCard = Ui.Card(head, graph, bottom, gameTag);

            // обработка
            var punch = new SliderRow(L("eq.punch"), 0, 1, 0.05, Sound.Punch, Fmt.Percent, v =>
            {
                Sound.Punch = (float)v;
                Sound.PresetName = "";
                Sound.Apply();
            });
            var boost = new SliderRow(L("eq.boost"), 0, 6, 0.5, Sound.Boost, v => Fmt.Db(v, true), v =>
            {
                Sound.Boost = (float)v;
                Sound.Apply();
            });
            var preamp = new SliderRow(L("eq.preamp"), -12, 0, 0.5, Sound.Preamp, v => Fmt.Db(v), v =>
            {
                Sound.Preamp = (float)v;
                Sound.Apply();
            });
            var balance = new SliderRow(L("eq.balance"), -1, 1, 0.05, Sound.Balance, BalanceText, v =>
            {
                Sound.Balance = (float)v;
                Sound.Apply();
            });
            var soundCard = Ui.Card(Ui.CardTitle(Icons.Audio, L("eq.processing")), punch, boost, preamp, balance,
                Ui.Note(L("eq.processingNote"), 12.5).Mg(0, 6, 0, 0));

            // пресеты
            var chips = new WrapPanel();
            var presetsBox = new StackPanel();
            Action refreshAll = null;
            Action paintPresets = () =>
            {
                chips.Children.Clear();
                for (int i = 0; i < Presets.BuiltIn.Length; i++)
                {
                    int idx = i;
                    var label = L(Presets.BuiltIn[i].Key);
                    var p = new Pill(label, null, false, Sound.PresetName == label).Mg(0, 0, 8, 8);
                    p.Click += () =>
                    {
                        Sound.ApplyPreset(Presets.FromBuiltIn(idx));
                        refreshAll();
                    };
                    chips.Children.Add(p);
                }
                foreach (var up in Presets.User())
                {
                    var preset = up;
                    var p = new Pill(up.Name, Icons.Save, false, Sound.PresetName == up.Name).Mg(0, 0, 8, 8);
                    p.Click += () =>
                    {
                        Sound.ApplyPreset(preset);
                        refreshAll();
                    };
                    p.MouseRightButtonUp += (s, e) =>
                    {
                        Dialogs.UserPreset(preset, refreshAll);
                        e.Handled = true;
                    };
                    chips.Children.Add(p);
                }
            };
            paintPresets();
            var save = new Pill(L("eq.save"), Icons.Add, true);
            save.Click += () => Dialogs.SavePreset(refreshAll);
            var share = new Pill(L("eq.share"), Icons.Qr);
            share.Click += () => Dialogs.Share(Sound.Current(PresetTitle()));
            var enter = new Pill(L("eq.enterCode"), Icons.Keyboard);
            enter.Click += () => Dialogs.EnterCode(refreshAll);
            var reset = new Pill(L("eq.reset"), Icons.Refresh);
            reset.Click += () =>
            {
                Sound.ApplyPreset(new EqPreset { Name = L("p.flat"), Bands = Sound.Bands, Gains = new float[Sound.Bands] });
                refreshAll();
            };
            presetsBox.Children.Add(chips);
            presetsBox.Children.Add(Ui.Note(L("eq.rightClick"), 12.5).Mg(0, 2, 0, 12));
            presetsBox.Children.Add(Ui.Wrap(8, save, share, enter, reset));
            var presetsCard = Ui.Card(Ui.CardTitle(Icons.Equalizer, L("eq.presets")), presetsBox);

            refreshAll = () =>
            {
                name.Text = PresetTitle();
                graph.Update(Sound.Gains, Sound.Bands);
                foreach (var b in bandPills)
                {
                    b.Selected = b.Tag is int n && n == Sound.Bands;
                }
                punch.Set(Sound.Punch);
                boost.Set(Sound.Boost);
                preamp.Set(Sound.Preamp);
                balance.Set(Sound.Balance);
                paintPresets();
            };
            for (int i = 0; i < bandPills.Count; i++)
            {
                bandPills[i].Tag = new[] { 9, 15, 31 }[i];
            }

            main.OnSound = () =>
            {
                name.Text = PresetTitle();
                enabled.IsOn = Sound.Enabled;
                graph.Opacity = Sound.Enabled && !Sound.AbBypass ? 1 : 0.4;
                paintGame();
            };

            return MainView.PageFrame(L("tab.eq"), L("eq.subtitle"),
                ApoBanner.Build(main),
                curveCard,
                Ui.Columns(16, soundCard, presetsCard));
        }

        static string PresetTitle()
        {
            return string.IsNullOrEmpty(Sound.PresetName) ? L("eq.custom") : Sound.PresetName;
        }

        static string ForText()
        {
            var def = AudioDevices.List().FirstOrDefault(d => d.IsDefault);
            return def == null ? L("eq.allSound") : F("eq.for", def.Title);
        }

        static string BalanceText(double v)
        {
            int b = (int)Math.Round(v * 100);
            return b == 0 ? L("eq.center") : b < 0 ? F("eq.left", -b) : F("eq.right", b);
        }
    }

    /// <summary>Плашка сверху, если EQ пока не может менять звук: нет Equalizer APO или нет прав на его папку.</summary>
    static class ApoBanner
    {
        public static FrameworkElement Build(MainView main)
        {
            if (Apo.Installed && !Apo.NeedsAccess)
            {
                return null;
            }
            bool noApo = !Apo.Installed;
            var title = Ui.Title(noApo ? L("apo.missingTitle") : L("apo.accessTitle"), 16);
            var text = Ui.Note(noApo ? L("apo.missingText") : L("apo.accessText"), 13).Mg(0, 4, 0, 12);
            Pill action;
            if (noApo)
            {
                action = new Pill(L("apo.install"), Icons.Download, true);
                action.Click += () => Shell.Start(Apo.DownloadUrl);
            }
            else
            {
                action = new Pill(L("apo.allow"), Icons.Shield, true);
                action.Click += () =>
                {
                    if (Apo.RequestAccess())
                    {
                        Sound.WriteNow();
                        main.Toast(Sound.Written ? L("apo.done") : L("apo.failed"));
                        main.ShowPage(main.Page);
                    }
                };
            }
            var again = new Pill(L("apo.recheck"), Icons.Refresh);
            again.Click += () =>
            {
                Apo.Detect();
                Sound.WriteNow();
                main.Rebuild();
            };
            var sp = new StackPanel();
            sp.Children.Add(title);
            sp.Children.Add(text);
            sp.Children.Add(Ui.Row(8, action, again));
            var g = Ui.ListRow(Ui.Badge(Icons.Warning, 44, Theme.Orange).Mg(0, 0, 16, 0), sp, null);
            g.Margin = new Thickness(0);
            ((FrameworkElement)g.Children[0]).VerticalAlignment = VerticalAlignment.Top;
            return new Border
            {
                Background = Theme.B(Theme.WithAlpha(Theme.Orange, 0.10)),
                BorderBrush = Theme.B(Theme.WithAlpha(Theme.Orange, 0.35)),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(24),
                Padding = new Thickness(22, 18, 22, 18),
                Child = g,
            };
        }
    }
}
