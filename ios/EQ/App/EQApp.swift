import SwiftUI
import UIKit

@main
struct EQApp: App {
    @Environment(\.scenePhase) private var phase

    init() {
        let tab = UITabBarAppearance()
        tab.configureWithDefaultBackground()
        tab.backgroundEffect = UIBlurEffect(style: .systemChromeMaterialDark)
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.backgroundColor = .clear
        nav.largeTitleTextAttributes = [.foregroundColor: UIColor.white]
        nav.titleTextAttributes = [.foregroundColor: UIColor.white]
        let navBlur = UINavigationBarAppearance()
        navBlur.configureWithDefaultBackground()
        navBlur.backgroundEffect = UIBlurEffect(style: .systemChromeMaterialDark)
        navBlur.titleTextAttributes = [.foregroundColor: UIColor.white]
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().standardAppearance = navBlur
        UINavigationBar.appearance().compactAppearance = navBlur
        _ = AudioEngine.shared
        _ = AudioRoute.shared
        Demo.applyIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    PresetInbox.shared.receive(url.absoluteString)
                }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { act in
                    if let url = act.webpageURL {
                        PresetInbox.shared.receive(url.absoluteString)
                    }
                }
        }
        .onChange(of: phase) { p in
            if p == .active {
                Library.shared.reload()
                AudioRoute.shared.update()
            } else if p == .background {
                ListenStats.shared.save()
            }
        }
    }
}

/// Четыре вкладки, как на Android: Устройство, Эквалайзер, Музыка, Настройки.
struct RootView: View {
    @ObservedObject private var app = AppState.shared
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var engine = AudioEngine.shared
    @ObservedObject private var inbox = PresetInbox.shared

    private var accent: Color {
        if settings.coverColor, let c = CoverColor.of(engine.current) {
            return c
        }
        return settings.accent
    }

    var body: some View {
        TabView(selection: $app.tab) {
            DeviceTab()
                .tabItem { Label(L("tab.device"), systemImage: "headphones") }
                .tag(0)
            EqualizerTab()
                .tabItem { Label(L("tab.eq"), systemImage: "slider.vertical.3") }
                .tag(1)
            MusicTab()
                .tabItem { Label(L("tab.music"), systemImage: "music.note") }
                .tag(2)
            SettingsTab()
                .tabItem { Label(L("tab.settings"), systemImage: "gearshape") }
                .tag(3)
        }
        .tint(accent)
        .environment(\.accent, accent)
        .sheet(item: $inbox.incoming) { p in
            IncomingSheet(preset: p)
                .environment(\.accent, accent)
                .preferredColorScheme(.dark)
        }
        .onChange(of: app.tab) { _ in
            Haptic.tick()
        }
    }
}
