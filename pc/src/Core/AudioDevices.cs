using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace EQ
{
    /// <summary>Устройство вывода звука Windows.</summary>
    sealed class AudioDevice
    {
        public string Id = "";         // {guid} из MMDevices
        public string Name = "";       // «Динамики», «Наушники»
        public string Interface = "";  // «Realtek(R) Audio», «AirPods Pro»
        public bool Apo;               // на нём стоит Equalizer APO
        public bool IsDefault;

        public string Title
        {
            get { return string.IsNullOrEmpty(Interface) ? Name : Name + " (" + Interface + ")"; }
        }

        /// <summary>Значок Segoe по типу: наушники, колонки, ТВ, машина.</summary>
        public string Glyph
        {
            get
            {
                var s = (Name + " " + Interface).ToLowerInvariant();
                if (s.Contains("headphone") || s.Contains("наушник") || s.Contains("headset") || s.Contains("гарнитур")
                    || s.Contains("airpods") || s.Contains("buds") || s.Contains("austiņ"))
                {
                    return Icons.Headphones;
                }
                if (s.Contains("hdmi") || s.Contains("tv") || s.Contains("display") || s.Contains("monitor"))
                {
                    return Icons.Tv;
                }
                return Icons.Speakers;
            }
        }
    }

    /// <summary>
    /// Выходы звука: список из реестра (MMDevices), какой по умолчанию — через Core Audio,
    /// и стоит ли на каждом Equalizer APO (его CLSID в FxProperties).
    /// </summary>
    static class AudioDevices
    {
        const string RenderKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render";
        const string PkeyName = "{a45c254e-df1c-4efd-8020-67d146a850e0},2";
        const string PkeyInterface = "{b3f8fa53-0004-438e-9003-51a46e139bfc},6";

        public static List<AudioDevice> Demo;

        public static List<AudioDevice> List()
        {
            if (Demo != null)
            {
                return Demo;
            }
            var list = new List<AudioDevice>();
            var def = DefaultId();
            try
            {
                using (var hk = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
                using (var render = hk.OpenSubKey(RenderKey))
                {
                    if (render == null)
                    {
                        return list;
                    }
                    foreach (var id in render.GetSubKeyNames())
                    {
                        using (var dev = render.OpenSubKey(id))
                        {
                            if (dev == null || !(dev.GetValue("DeviceState") is int state) || (state & 1) == 0)
                            {
                                continue;   // выключено или не подключено
                            }
                            var d = new AudioDevice { Id = id, IsDefault = def != null && def.EndsWith(id, StringComparison.OrdinalIgnoreCase) };
                            using (var props = dev.OpenSubKey("Properties"))
                            {
                                d.Name = props?.GetValue(PkeyName) as string ?? "";
                                d.Interface = props?.GetValue(PkeyInterface) as string ?? "";
                            }
                            using (var fx = dev.OpenSubKey("FxProperties"))
                            {
                                if (fx != null)
                                {
                                    d.Apo = fx.GetValueNames().Any(n => (fx.GetValue(n) as string ?? "")
                                        .IndexOf(Apo.Clsid, StringComparison.OrdinalIgnoreCase) >= 0);
                                }
                            }
                            list.Add(d);
                        }
                    }
                }
            }
            catch
            {
                // реестр недоступен
            }
            return list.OrderByDescending(d => d.IsDefault).ThenBy(d => d.Name).ToList();
        }

        // Core Audio: IMMDeviceEnumerator.GetDefaultAudioEndpoint(eRender, eMultimedia)

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        class MMDeviceEnumerator
        {
        }

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);

            int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);

            int OpenPropertyStore(int access, out IntPtr properties);

            int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        }

        static string DefaultId()
        {
            try
            {
                var e = (IMMDeviceEnumerator)new MMDeviceEnumerator();
                if (e.GetDefaultAudioEndpoint(0, 1, out var dev) == 0 && dev != null && dev.GetId(out var id) == 0)
                {
                    return id;
                }
            }
            catch
            {
                // нет звука в системе
            }
            return null;
        }
    }
}
