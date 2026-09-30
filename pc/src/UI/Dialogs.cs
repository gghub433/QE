using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using static EQ.Lang;

namespace EQ
{
    /// <summary>Окна-карточки: сохранить пресет, код и QR, ввести код, пресет по ссылке.</summary>
    static class Dialogs
    {
        static MainView Main
        {
            get { return MainView.Current; }
        }

        static void CopyText(string text)
        {
            try
            {
                Clipboard.SetText(text);
                Main.Toast(L("share.copied"));
            }
            catch
            {
                // буфер обмена занят другой программой
            }
        }

        public static void SavePreset(Action done)
        {
            var box = Ui.Input(string.IsNullOrEmpty(Sound.PresetName) ? L("eq.myPreset") : Sound.PresetName);
            var save = new Pill(L("save"), Icons.Save, true);
            Action doSave = () =>
            {
                var name = box.Text.Trim();
                if (name.Length == 0)
                {
                    return;
                }
                var list = Presets.User().Where(p => p.Name != name).ToList();
                list.Add(Sound.Current(name));
                Presets.SaveUser(list);
                Sound.PresetName = name;
                Sound.Apply();
                Main.CloseOverlay();
                Main.Toast(L("eq.saved"));
                done?.Invoke();
            };
            save.Click += doSave;
            box.KeyDown += (s, e) =>
            {
                if (e.Key == Key.Enter)
                {
                    doSave();
                }
            };
            var cancel = new Pill(L("cancel"));
            cancel.Click += Main.CloseOverlay;
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(L("eq.saveTitle"), 20).Mg(0, 0, 40, 14));
            sp.Children.Add(box);
            sp.Children.Add(Ui.Row(8, save, cancel).Mg(0, 16, 0, 0));
            Main.ShowOverlay(sp, 440);
            box.SelectAll();
            box.Focus();
        }

        /// <summary>Код и QR: телефон наводит камеру — пресет открывается в EQ на Android и iPhone.</summary>
        public static void Share(EqPreset p)
        {
            var code = PresetCode.Encode(p);
            var url = PresetCode.Url(code);
            var left = new StackPanel { Width = 240 };
            left.Children.Add(new QrView(url) { Width = 240, Height = 240 });
            left.Children.Add(Ui.Note(L("share.qrHint"), 12.5).Mg(0, 10, 0, 0));
            var right = new StackPanel { Margin = new Thickness(22, 0, 0, 0) };
            right.Children.Add(Ui.Title(p.Name, 20).Mg(0, 0, 40, 4));
            right.Children.Add(Ui.Note(F("code.bands", p.Bands), 13));
            right.Children.Add(new EqGraphView(p.Gains, p.Bands, null, true) { Height = 90, Margin = new Thickness(0, 12, 0, 14) });
            var codeBox = new TextBox
            {
                Text = code,
                IsReadOnly = true,
                TextWrapping = TextWrapping.Wrap,
                FontFamily = Theme.Mono,
                FontSize = 15,
                Foreground = Theme.B(Theme.White),
                Background = Theme.B(Theme.Bg),
                BorderThickness = new Thickness(0),
                Padding = new Thickness(14, 12, 14, 12),
            };
            right.Children.Add(new Border { CornerRadius = new CornerRadius(14), Background = Theme.B(Theme.Bg), Child = codeBox, ClipToBounds = true });
            var copy = new Pill(L("share.copy"), Icons.Copy, true);
            copy.Click += () => CopyText(code);
            var link = new Pill(L("share.link"), Icons.Link);
            link.Click += () => CopyText(F("share.message", p.Name, url));
            right.Children.Add(Ui.Wrap(8, copy, link).Mg(0, 14, 0, 0));
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.Children.Add(left);
            Grid.SetColumn(right, 1);
            g.Children.Add(right);
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(L("share.title"), 22).Mg(0, 0, 40, 16));
            sp.Children.Add(g);
            Main.ShowOverlay(sp, 720);
        }

        /// <summary>Ввести код или вставить ссылку/сообщение с кодом: сразу видно кривую и «Применить».</summary>
        public static void EnterCode(Action done)
        {
            var box = Ui.Input("", "mono");
            box.AcceptsReturn = true;
            box.TextWrapping = TextWrapping.Wrap;
            box.MinHeight = 70;
            var preview = new ContentControl { Margin = new Thickness(0, 14, 0, 0) };
            Action update = () =>
            {
                var p = PresetCode.Decode(box.Text);
                if (p != null)
                {
                    preview.Content = Preview(p, done);
                }
                else
                {
                    preview.Content = Ui.Note(box.Text.Trim().Length == 0 ? L("code.hint") : L("code.bad"), 13);
                }
            };
            box.TextChanged += (s, e) => update();
            var paste = new Pill(L("code.paste"), Icons.Paste);
            paste.Click += () =>
            {
                try
                {
                    box.Text = Clipboard.GetText();
                }
                catch
                {
                    // буфер обмена занят
                }
            };
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(L("code.title"), 20).Mg(0, 0, 40, 14));
            sp.Children.Add(box);
            sp.Children.Add(paste.Mg(0, 10, 0, 0));
            sp.Children.Add(preview);
            update();
            Main.ShowOverlay(sp, 560);
            box.Focus();
        }

        /// <summary>Пресет пришёл по ссылке eq://preset/… (кнопка на странице QR).</summary>
        public static void Incoming(EqPreset p)
        {
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(L("code.incoming"), 20).Mg(0, 0, 40, 10));
            sp.Children.Add(Preview(p, null));
            Main.ShowOverlay(sp, 560);
        }

        static FrameworkElement Preview(EqPreset p, Action done)
        {
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(string.IsNullOrEmpty(p.Name) ? L("code.found") : p.Name, 17));
            sp.Children.Add(new EqGraphView(p.Gains, p.Bands) { Height = 170, Margin = new Thickness(0, 10, 0, 8) });
            var parts = new System.Collections.Generic.List<string> { F("code.bands", p.Bands) };
            if (p.Punch > 0.01f)
            {
                parts.Add(L("eq.punch") + " " + Fmt.Percent(p.Punch));
            }
            if (p.Boost > 0.01f)
            {
                parts.Add(L("eq.boost") + " " + Fmt.Db(p.Boost, true));
            }
            if (p.Preamp < -0.01f)
            {
                parts.Add(L("eq.preamp") + " " + Fmt.Db(p.Preamp));
            }
            if (Math.Abs(p.Balance) > 0.01f)
            {
                parts.Add(L("eq.balance") + " " + Math.Round(p.Balance * 100));
            }
            sp.Children.Add(Ui.Note(string.Join(" · ", parts), 13));
            var apply = new Pill(L("apply"), Icons.Check, true);
            apply.Click += () =>
            {
                var x = p.Clone();
                if (string.IsNullOrEmpty(x.Name))
                {
                    x.Name = L("code.found");
                }
                Sound.ApplyPreset(x);
                Main.CloseOverlay();
                Main.Toast(L("code.applied"));
                if (done != null)
                {
                    done();
                }
                else if (Main.Page == MainView.PEq)
                {
                    Main.ShowPage(MainView.PEq);
                }
            };
            sp.Children.Add(apply.Mg(0, 14, 0, 0));
            return sp;
        }

        /// <summary>Свой пресет (правый клик): применить, код и QR, удалить.</summary>
        public static void UserPreset(EqPreset p, Action done)
        {
            var apply = new Pill(L("apply"), Icons.Check, true);
            apply.Click += () =>
            {
                Sound.ApplyPreset(p);
                Main.CloseOverlay();
                done?.Invoke();
            };
            var share = new Pill(L("eq.share"), Icons.Qr);
            share.Click += () => Share(p);
            var del = new Pill(L("delete"), Icons.Delete);
            del.Click += () =>
            {
                Presets.SaveUser(Presets.User().Where(x => x.Name != p.Name).ToList());
                Main.CloseOverlay();
                Main.Toast(L("eq.deleted"));
                done?.Invoke();
            };
            var sp = new StackPanel();
            sp.Children.Add(Ui.Title(p.Name, 20).Mg(0, 0, 40, 10));
            sp.Children.Add(new EqGraphView(p.Gains, p.Bands) { Height = 150, Margin = new Thickness(0, 0, 0, 14) });
            sp.Children.Add(Ui.Wrap(8, apply, share, del));
            Main.ShowOverlay(sp, 520);
        }
    }
}
