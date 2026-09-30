using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using static EQ.Lang;

namespace EQ
{
    /// <summary>«Настройки»: цвет, язык, запуск с Windows и работа в фоне, обновления, о программе.</summary>
    static class SettingsPage
    {
        public static FrameworkElement Build(MainView main)
        {
            // цвет акцента
            var swatches = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 2, 0, 4) };
            for (int i = 0; i < Theme.Accents.Length; i++)
            {
                int idx = i;
                bool sel = Theme.AccentIndex == i;
                var dot = new Border
                {
                    Width = 34,
                    Height = 34,
                    CornerRadius = new CornerRadius(17),
                    Background = Theme.B(Theme.C(Theme.Accents[i])),
                    Margin = new Thickness(0, 0, 14, 0),
                    Cursor = Cursors.Hand,
                    BorderBrush = Brushes.White,
                    BorderThickness = new Thickness(sel ? 3 : 0),
                };
                dot.MouseLeftButtonUp += (s, e) =>
                {
                    Theme.AccentIndex = idx;
                    main.Rebuild();
                };
                swatches.Children.Add(dot);
            }
            var looks = Ui.Card(Ui.CardTitle(Icons.Color, L("set.looks")), swatches);

            // язык
            var langs = new WrapPanel();
            var current = Store.Get("lang");
            var auto = new Pill(L("set.langAuto"), null, false, current == "").Mg(0, 0, 8, 8);
            auto.Click += () => SetLang("", main);
            langs.Children.Add(auto);
            for (int i = 0; i < Lang.Codes.Length; i++)
            {
                var code = Lang.Codes[i];
                var p = new Pill(Lang.Names[i], null, false, current == code).Mg(0, 0, 8, 8);
                p.Click += () => SetLang(code, main);
                langs.Children.Add(p);
            }
            var lang = Ui.Card(Ui.CardTitle(Icons.Globe, L("set.language")), langs);

            // запуск
            var start = Ui.Card(Ui.CardTitle(Icons.Power, L("set.start")),
                new SwitchRow(L("set.autostart"), L("set.autostartNote"), Shell.Autostart, on => Shell.Autostart = on),
                new SwitchRow(L("set.background"), L("set.backgroundNote"), Store.GetBool("background", true), on => Store.Set("background", on)));

            // обновления
            var state = Ui.Note("", 13.5);
            var action = new ContentControl();
            Action paint = () =>
            {
                Pill b;
                switch (Updates.Now)
                {
                    case Updates.State.Checking:
                        state.Text = L("upd.checking");
                        action.Content = null;
                        return;
                    case Updates.State.Downloading:
                        state.Text = L("upd.downloading");
                        action.Content = null;
                        return;
                    case Updates.State.UpToDate:
                        state.Text = L("upd.latest");
                        state.Foreground = Theme.B(Theme.Green);
                        break;
                    case Updates.State.Failed:
                        state.Text = L("upd.failed");
                        state.Foreground = Theme.B(Theme.Orange);
                        break;
                    case Updates.State.Available:
                        state.Text = F("upd.available", Updates.NewVersion);
                        state.Foreground = Theme.B(Theme.Accent);
                        b = new Pill(L("upd.install"), Icons.Download, true);
                        b.Click += () => Updates.Install(main.Dispatcher);
                        action.Content = b;
                        return;
                    default:
                        state.Text = "";
                        break;
                }
                b = new Pill(L("upd.check"), Icons.Refresh);
                b.Click += () => Updates.Check(main.Dispatcher);
                action.Content = b;
            };
            paint();
            main.OnUpdates = paint;
            var ver = Ui.ListRow(Ui.Text(F("upd.version", Updates.Current), 15, null, FontWeights.SemiBold), null, state);
            var updates = Ui.Card(Ui.CardTitle(Icons.Download, L("upd.title")), ver, action.Mg(0, 8, 0, 0),
                Ui.Note(L("upd.note"), 12.5).Mg(0, 10, 0, 0));

            // о программе
            var github = new Pill("GitHub", Icons.Link);
            github.Click += () => Shell.Start("https://github.com/gghub433/QE");
            var about = Ui.Card(Ui.CardTitle(Icons.Info, L("set.about")), Ui.Note(L("set.aboutText"), 13.5), github.Mg(0, 12, 0, 0));

            return MainView.PageFrame(L("tab.settings"), null, Ui.Columns(16, looks, lang), Ui.Columns(16, start, updates), about);
        }

        static void SetLang(string code, MainView main)
        {
            Store.Set("lang", code);
            Lang.Load(code);
            main.Rebuild();
            App.RefreshTray();
        }
    }
}
