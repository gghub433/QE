using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using static EQ.Lang;

namespace EQ
{
    /// <summary>«Устройство»: куда идёт звук (наушники, колонки, ТВ), где работает EQ, Equalizer APO, этот ПК.</summary>
    static class DevicePage
    {
        public static FrameworkElement Build(MainView main)
        {
            var devices = AudioDevices.List();
            var def = devices.FirstOrDefault(d => d.IsDefault) ?? devices.FirstOrDefault();

            // куда идёт звук
            FrameworkElement routeCard;
            if (def == null)
            {
                routeCard = Ui.Card(Ui.CardTitle(Icons.Speakers, L("dev.output")), Ui.Note(L("dev.none"), 14));
            }
            else
            {
                var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                info.Children.Add(Ui.Note(L("dev.now"), 13));
                var n = Ui.Title(def.Name, 24);
                n.TextWrapping = TextWrapping.Wrap;
                info.Children.Add(n);
                if (!string.IsNullOrEmpty(def.Interface))
                {
                    info.Children.Add(Ui.Note(def.Interface, 14).Mg(0, 2, 0, 0));
                }
                info.Children.Add(ApoTag(def).Mg(0, 10, 0, 0));
                var top = Ui.ListRow(Ui.Badge(def.Glyph, 84, Theme.Accent).Mg(0, 0, 20, 0), info, null);
                var list = new StackPanel { Margin = new Thickness(0, 16, 0, 0) };
                var others = devices.Where(d => d != def).ToList();
                if (others.Count > 0)
                {
                    list.Children.Add(Ui.Text(L("dev.others"), 14, null, FontWeights.SemiBold).Mg(0, 0, 0, 6));
                    foreach (var d in others)
                    {
                        var t = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                        t.Children.Add(Ui.Text(d.Name, 14.5));
                        if (!string.IsNullOrEmpty(d.Interface))
                        {
                            t.Children.Add(Ui.Note(d.Interface, 12.5));
                        }
                        list.Children.Add(Ui.ListRow(Ui.Icon(d.Glyph, 20, Theme.Grey).Mg(4, 0, 16, 0), t, ApoTag(d)));
                    }
                }
                var actions = new WrapPanel { Margin = new Thickness(0, 14, 0, 0) };
                if (Apo.Installed)
                {
                    var pick = new Pill(L("dev.pick"), Icons.Settings);
                    pick.Click += Apo.OpenConfigurator;
                    actions.Children.Add(pick.Mg(0, 0, 8, 8));
                }
                var sys = new Pill(L("dev.system"), Icons.Speakers);
                sys.Click += () => Shell.Start("ms-settings:sound");
                actions.Children.Add(sys.Mg(0, 0, 8, 8));
                var refresh = new Pill(null, Icons.Refresh);
                refresh.Click += () => main.ShowPage(main.Page);
                actions.Children.Add(refresh.Mg(0, 0, 8, 8));
                routeCard = Ui.Card(top, list, actions,
                    devices.Any(d => !d.Apo) && Apo.Installed ? Ui.Note(L("dev.pickNote"), 12.5).Mg(0, 4, 0, 0) : null);
            }

            // Equalizer APO
            var apoBox = new StackPanel();
            if (!Apo.Installed)
            {
                apoBox.Children.Add(Ui.Text(L("apo.missingTitle"), 15, Theme.Orange, FontWeights.SemiBold));
                apoBox.Children.Add(Ui.Note(L("apo.missingText"), 13).Mg(0, 4, 0, 12));
                var inst = new Pill(L("apo.install"), Icons.Download, true);
                inst.Click += () => Shell.Start(Apo.DownloadUrl);
                var again = new Pill(L("apo.recheck"), Icons.Refresh);
                again.Click += () =>
                {
                    Apo.Detect();
                    Sound.WriteNow();
                    main.Rebuild();
                };
                apoBox.Children.Add(Ui.Row(8, inst, again));
                apoBox.Children.Add(Ui.Note(L("apo.steps"), 12.5).Mg(0, 12, 0, 0));
            }
            else
            {
                bool ok = !Apo.NeedsAccess;
                apoBox.Children.Add(Ui.Row(8, Ui.Icon(ok ? Icons.Check : Icons.Warning, 16, ok ? Theme.Green : Theme.Orange),
                    Ui.Text(ok ? L("apo.ready") : L("apo.accessTitle"), 15, ok ? Theme.Green : Theme.Orange, FontWeights.SemiBold)));
                apoBox.Children.Add(Ui.Note(ok ? L("apo.readyText") : L("apo.accessText"), 13).Mg(0, 4, 0, 12));
                var buttons = new WrapPanel();
                if (!ok)
                {
                    var allow = new Pill(L("apo.allow"), Icons.Shield, true);
                    allow.Click += () =>
                    {
                        if (Apo.RequestAccess())
                        {
                            Sound.WriteNow();
                            main.Toast(Sound.Written ? L("apo.done") : L("apo.failed"));
                            main.Rebuild();
                        }
                    };
                    buttons.Children.Add(allow.Mg(0, 0, 8, 8));
                }
                if (Apo.HasOtherFilters())
                {
                    apoBox.Children.Add(Ui.Text(L("apo.others"), 13, Theme.Orange).Mg(0, 0, 0, 10));
                    var only = new Pill(L("apo.makeOnly"), Icons.Check);
                    only.Click += () =>
                    {
                        if (Apo.MakeEqOnly())
                        {
                            main.Toast(L("apo.onlyDone"));
                            main.ShowPage(main.Page);
                        }
                        else if (Apo.NeedsAccess)
                        {
                            main.Rebuild();
                        }
                    };
                    buttons.Children.Add(only.Mg(0, 0, 8, 8));
                }
                apoBox.Children.Add(buttons);
            }
            var apoCard = Ui.Card(Ui.CardTitle(Icons.Equalizer, "Equalizer APO"), apoBox);

            // этот ПК
            var pc = new StackPanel();
            pc.Children.Add(Row(L("pc.name"), Shell.PcName()));
            pc.Children.Add(Row("Windows", Shell.WindowsName()));
            var cpu = Shell.CpuName();
            if (cpu.Length > 0)
            {
                pc.Children.Add(Row(L("pc.cpu"), cpu));
            }
            var ram = Shell.RamGb();
            if (ram > 0)
            {
                pc.Children.Add(Row(L("pc.ram"), F("pc.gb", ram)));
            }
            var pcCard = Ui.Card(Ui.CardTitle(Icons.Pc, L("pc.title")), pc);

            // телефон и ПК
            var phoneCard = Ui.Card(Ui.CardTitle(Icons.Phone, L("pc.phoneTitle")), Ui.Note(L("pc.phoneText"), 13.5));

            return MainView.PageFrame(L("tab.device"), L("dev.subtitle"), routeCard,
                Ui.Columns(16, apoCard, pcCard), phoneCard);
        }

        static Border ApoTag(AudioDevice d)
        {
            if (!Apo.Installed)
            {
                return Ui.Tag(L("dev.noApo"), Theme.Grey);
            }
            return d.Apo ? Ui.Tag(L("dev.eqOn"), Theme.Green) : Ui.Tag(L("dev.eqOff"), Theme.Orange);
        }

        static FrameworkElement Row(string label, string value)
        {
            var v = Ui.Text(value, 14, null, FontWeights.SemiBold);
            v.TextAlignment = TextAlignment.Right;
            v.TextWrapping = TextWrapping.Wrap;
            v.MaxWidth = 300;
            return Ui.ListRow(Ui.Note(label, 14).Mg(0, 0, 16, 0), null, v);
        }
    }
}
