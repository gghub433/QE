using System;
using System.Diagnostics;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32;

namespace EQ
{
    /// <summary>
    /// Equalizer APO — бесплатный системный эквалайзер Windows (драйвер-фильтр звука).
    /// Windows не даёт обычной программе менять звук других программ и игр, поэтому EQ пишет
    /// свои настройки в config\EQ.txt, а в config.txt добавляет одну строку «Include: EQ.txt».
    /// APO сам перечитывает файлы — звук меняется сразу, на весь ПК.
    /// </summary>
    static class Apo
    {
        public const string Clsid = "{EACD2258-FCAC-4FF4-B36D-419E924A6D79}";
        public const string DownloadUrl = "https://sourceforge.net/projects/equalizerapo/files/latest/download";
        public const string IncludeLine = "Include: EQ.txt";

        public static string InstallDir { get; private set; }
        public static string ConfigDir { get; private set; }
        public static bool DemoInstalled;

        /// <summary>null — всё хорошо, "access" — нет прав на папку config, иначе текст ошибки.</summary>
        public static string LastError { get; private set; }

        public static bool Installed
        {
            get { return DemoInstalled || ConfigDir != null && Directory.Exists(ConfigDir); }
        }

        public static bool NeedsAccess
        {
            get { return LastError == "access"; }
        }

        public static void Detect()
        {
            InstallDir = null;
            ConfigDir = null;
            if (Store.Demo)
            {
                return;
            }
            try
            {
                using (var hk = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (var k = hk.OpenSubKey(@"SOFTWARE\EqualizerAPO"))
                {
                    if (k != null)
                    {
                        InstallDir = k.GetValue("InstallPath") as string;
                        ConfigDir = k.GetValue("ConfigPath") as string;
                    }
                }
            }
            catch
            {
                // нет доступа к реестру — ищем в обычной папке
            }
            if (string.IsNullOrEmpty(ConfigDir))
            {
                var c = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "EqualizerAPO", "config");
                if (Directory.Exists(c))
                {
                    ConfigDir = c;
                    InstallDir = Path.GetDirectoryName(c);
                }
                else
                {
                    ConfigDir = null;
                }
            }
        }

        static string OurFile
        {
            get { return Path.Combine(ConfigDir, "EQ.txt"); }
        }

        static string ConfigFile
        {
            get { return Path.Combine(ConfigDir, "config.txt"); }
        }

        /// <summary>Записать наш файл и подключить его. false — не вышло (см. LastError).</summary>
        public static bool Write(string text)
        {
            if (Store.Demo || ConfigDir == null || !Directory.Exists(ConfigDir))
            {
                return false;
            }
            try
            {
                File.WriteAllText(OurFile, text, new UTF8Encoding(false));
                EnsureInclude();
                LastError = null;
                return true;
            }
            catch (UnauthorizedAccessException)
            {
                LastError = "access";
            }
            catch (Exception e)
            {
                LastError = e.Message;
            }
            return false;
        }

        static void EnsureInclude()
        {
            string all = File.Exists(ConfigFile) ? File.ReadAllText(ConfigFile) : "";
            if (all.IndexOf(IncludeLine, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return;
            }
            var add = (all.Length > 0 && !all.EndsWith("\n") ? "\r\n" : "") + "# EQ\r\n" + IncludeLine + "\r\n";
            File.AppendAllText(ConfigFile, add, new UTF8Encoding(false));
        }

        /// <summary>В config.txt есть свои фильтры, кроме EQ (например, пример из установки APO).</summary>
        public static bool HasOtherFilters()
        {
            if (Store.Demo || ConfigDir == null)
            {
                return false;
            }
            try
            {
                foreach (var raw in File.ReadAllLines(ConfigFile))
                {
                    var line = raw.Trim();
                    if (line.Length == 0 || line.StartsWith("#") || line.Equals(IncludeLine, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    return true;
                }
            }
            catch
            {
                // нет файла — нет и чужих фильтров
            }
            return false;
        }

        /// <summary>Оставить в config.txt только EQ; прежний файл сохраняется рядом.</summary>
        public static bool MakeEqOnly()
        {
            try
            {
                var backup = Path.Combine(ConfigDir, "config.before-EQ.txt");
                if (File.Exists(ConfigFile) && !File.Exists(backup))
                {
                    File.Copy(ConfigFile, backup);
                }
                File.WriteAllText(ConfigFile, "# EQ: прежние настройки — в config.before-EQ.txt\r\n" + IncludeLine + "\r\n",
                    new UTF8Encoding(false));
                LastError = null;
                return true;
            }
            catch (UnauthorizedAccessException)
            {
                LastError = "access";
            }
            catch (Exception e)
            {
                LastError = e.Message;
            }
            return false;
        }

        /// <summary>
        /// Один раз с правами администратора (окно UAC): разрешить пользователям менять папку config.
        /// Потом EQ пишет туда без вопросов.
        /// </summary>
        public static bool RequestAccess()
        {
            if (ConfigDir == null)
            {
                return false;
            }
            try
            {
                var psi = new ProcessStartInfo(App.ExePath, "--grant \"" + ConfigDir + "\"")
                {
                    Verb = "runas",
                    UseShellExecute = true,
                };
                var p = Process.Start(psi);
                p?.WaitForExit(20000);
                return p != null && p.ExitCode == 0;
            }
            catch
            {
                return false;   // нажали «Нет» в окне UAC
            }
        }

        /// <summary>Выполняется во втором, повышенном процессе EQ (--grant).</summary>
        public static int Grant(string dir)
        {
            try
            {
                var ds = Directory.GetAccessControl(dir);
                var users = new SecurityIdentifier(WellKnownSidType.BuiltinUsersSid, null);
                ds.AddAccessRule(new FileSystemAccessRule(users, FileSystemRights.Modify,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None,
                    AccessControlType.Allow));
                Directory.SetAccessControl(dir, ds);
                return 0;
            }
            catch
            {
                return 1;
            }
        }

        /// <summary>Выбор устройств, на которых работает APO (его Configurator).</summary>
        public static void OpenConfigurator()
        {
            if (InstallDir == null)
            {
                return;
            }
            foreach (var name in new[] { "DeviceSelector.exe", "Configurator.exe" })
            {
                var path = Path.Combine(InstallDir, name);
                if (File.Exists(path))
                {
                    Shell.Start(path);
                    return;
                }
            }
        }
    }
}
