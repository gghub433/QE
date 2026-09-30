using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using Microsoft.Win32;

namespace EQ
{
    /// <summary>Мелочи Windows: открыть ссылку, автозапуск, ссылки eq://, тёмный заголовок окна, сведения о ПК.</summary>
    static class Shell
    {
        public static void Start(string target, string workDir = null)
        {
            if (Store.Demo)
            {
                return;
            }
            try
            {
                var psi = new ProcessStartInfo(target) { UseShellExecute = true };
                if (workDir != null)
                {
                    psi.WorkingDirectory = workDir;
                }
                Process.Start(psi);
            }
            catch
            {
                // нет программы для такой ссылки
            }
        }

        public static void EnableTls()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
        }

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public static bool Autostart
        {
            get
            {
                try
                {
                    using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                    {
                        return k?.GetValue("EQ") != null;
                    }
                }
                catch
                {
                    return false;
                }
            }
            set
            {
                if (Store.Demo)
                {
                    return;
                }
                try
                {
                    using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
                    {
                        if (value)
                        {
                            k.SetValue("EQ", "\"" + App.ExePath + "\" --tray");
                        }
                        else
                        {
                            k.DeleteValue("EQ", false);
                        }
                    }
                }
                catch
                {
                    // политика запрещает автозапуск
                }
            }
        }

        /// <summary>Ссылки eq://preset/… (кнопка «Открыть в EQ» на странице QR) открывают EQ для ПК.</summary>
        public static void RegisterProtocol()
        {
            if (Store.Demo)
            {
                return;
            }
            try
            {
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\eq"))
                {
                    k.SetValue("", "URL:EQ");
                    k.SetValue("URL Protocol", "");
                    using (var cmd = k.CreateSubKey(@"shell\open\command"))
                    {
                        cmd.SetValue("", "\"" + App.ExePath + "\" \"%1\"");
                    }
                }
            }
            catch
            {
                // без ссылок eq:// — код можно ввести вручную
            }
        }

        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        /// <summary>Тёмный заголовок окна (Windows 10 20H1+ и 11).</summary>
        public static void DarkTitleBar(Window w)
        {
            try
            {
                var h = new WindowInteropHelper(w).Handle;
                int on = 1;
                if (DwmSetWindowAttribute(h, 20, ref on, 4) != 0)
                {
                    DwmSetWindowAttribute(h, 19, ref on, 4);
                }
                int color = 0x0F0E0B;   // цвет заголовка на Windows 11 (BGR)
                DwmSetWindowAttribute(h, 35, ref color, 4);
            }
            catch
            {
                // старая Windows — обычный заголовок
            }
        }

        // сведения об этом ПК

        public static string WindowsName()
        {
            if (Store.Demo)
            {
                return "Windows 11 Pro 24H2";
            }
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion"))
                {
                    var name = k?.GetValue("ProductName") as string ?? "Windows";
                    var disp = k?.GetValue("DisplayVersion") as string ?? "";
                    int.TryParse(k?.GetValue("CurrentBuild") as string, out var build);
                    if (build >= 22000)
                    {
                        name = name.Replace("Windows 10", "Windows 11");   // в реестре Windows 11 до сих пор пишет «10»
                    }
                    return (name + " " + disp).Trim();
                }
            }
            catch
            {
                return "Windows";
            }
        }

        public static string CpuName()
        {
            if (Store.Demo)
            {
                return "AMD Ryzen 7 7800X3D";
            }
            try
            {
                using (var k = Registry.LocalMachine.OpenSubKey(@"HARDWARE\DESCRIPTION\System\CentralProcessor\0"))
                {
                    return (k?.GetValue("ProcessorNameString") as string ?? "").Trim();
                }
            }
            catch
            {
                return "";
            }
        }

        [StructLayout(LayoutKind.Sequential)]
        struct MemoryStatus
        {
            public uint Length;
            public uint MemoryLoad;
            public ulong TotalPhys;
            public ulong AvailPhys;
            public ulong TotalPageFile;
            public ulong AvailPageFile;
            public ulong TotalVirtual;
            public ulong AvailVirtual;
            public ulong AvailExtendedVirtual;
        }

        [DllImport("kernel32.dll")]
        static extern bool GlobalMemoryStatusEx(ref MemoryStatus status);

        public static double RamGb()
        {
            if (Store.Demo)
            {
                return 32;
            }
            var m = new MemoryStatus { Length = (uint)Marshal.SizeOf(typeof(MemoryStatus)) };
            return GlobalMemoryStatusEx(ref m) ? Math.Round(m.TotalPhys / 1073741824.0) : 0;
        }

        public static string PcName()
        {
            return Store.Demo ? "GAMING-PC" : Environment.MachineName;
        }

        public static bool FileExists(string p)
        {
            return !string.IsNullOrEmpty(p) && File.Exists(p);
        }
    }
}
