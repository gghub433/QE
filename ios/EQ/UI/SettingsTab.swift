import SwiftUI
import UIKit

/// Вкладка «Настройки»: цвет, язык, обновления, поддерживаемые iPhone, что умеет версия для iOS.
struct SettingsTab: View {
    @ObservedObject private var settings = AppSettings.shared
    @ObservedObject private var updates = Updates.shared
    @ObservedObject private var stats = ListenStats.shared
    @Environment(\.accent) var accent
    @State var confirmReset = false

    var body: some View {
        TabPage(title: L("tab.settings")) {
            looksCard
            languageCard
            updatesCard
            modelsCard
            aboutCard
        }
        .confirmationDialog(L("set.resetStatsQ"), isPresented: $confirmReset, titleVisibility: .visible) {
            Button(L("set.resetStats"), role: .destructive) {
                stats.reset()
            }
        }
    }

    private var looksCard: some View {
        Card {
            CardTitle(text: L("set.looks"), systemImage: "paintpalette")
            HStack(spacing: 0) {
                ForEach(0..<AppSettings.accents.count, id: \.self) { i in
                    let c = Color(hex: AppSettings.accents[i])
                    Button {
                        Haptic.tick()
                        settings.accentIndex = i
                    } label: {
                        Circle()
                            .fill(c)
                            .frame(width: 32, height: 32)
                            .overlay(
                                Circle().stroke(Color.white, lineWidth: settings.accentIndex == i ? 3 : 0)
                                    .padding(-4)
                            )
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                    }
                    .buttonStyle(.plain)
                }
            }
            Toggle(isOn: $settings.coverColor) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("set.coverColor")).font(.system(size: 15))
                    Note(text: L("set.coverColorHint"))
                }
            }
            .tint(accent)
        }
    }

    private var languageCard: some View {
        Card {
            CardTitle(text: L("set.language"), systemImage: "globe")
            HStack {
                Text(Locale.current.localizedString(forLanguageCode: Bundle.main.preferredLocalizations.first ?? "en")?
                    .capitalized ?? "English")
                    .font(.system(size: 15))
                Spacer()
                Button(L("set.change")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(accent)
            }
            Note(text: L("set.languageHint"))
        }
    }

    private var updatesCard: some View {
        Card {
            CardTitle(text: L("set.updates"), systemImage: "arrow.down.circle")
            HStack {
                Text(L("set.version", Updates.current)).font(.system(size: 15))
                Spacer()
                switch updates.state {
                case .checking:
                    ProgressView()
                case .upToDate:
                    Text(L("set.upToDate")).font(.system(size: 14)).foregroundStyle(Palette.green)
                case .failed:
                    Text(L("set.noNet")).font(.system(size: 14)).foregroundStyle(Palette.orange)
                case .available(let v, _):
                    Text(L("set.newVersion", v)).font(.system(size: 14, weight: .semibold)).foregroundStyle(accent)
                case .idle:
                    EmptyView()
                }
            }
            if case .available(_, let url) = updates.state {
                WideButton(text: L("set.download"), systemImage: "arrow.down") {
                    UIApplication.shared.open(url)
                }
            } else {
                WideButton(text: L("set.check"), systemImage: "arrow.clockwise", filled: false) {
                    updates.check()
                }
            }
            Note(text: L("set.updatesHint"))
        }
    }

    private var modelsCard: some View {
        Card {
            NavigationLink {
                ModelsList()
            } label: {
                HStack {
                    CardTitle(text: L("set.models"), systemImage: "iphone")
                    Spacer()
                    Text("\(DeviceModel.all.count)")
                        .foregroundStyle(Palette.grey)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.grey)
                }
            }
            .buttonStyle(.plain)
            Note(text: L("set.modelsHint", DeviceModel.current.name))
        }
    }

    private var aboutCard: some View {
        Card {
            CardTitle(text: L("set.about"), systemImage: "info.circle")
            Note(text: L("set.aboutText"))
            Button(L("set.resetStats")) {
                confirmReset = true
            }
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Color.red)
        }
    }
}

/// Все iPhone, для которых сделан интерфейс: от iPhone 11 до новейших.
struct ModelsList: View {
    @Environment(\.accent) var accent

    var body: some View {
        let cur = DeviceModel.current.identifier
        let years = Array(Set(DeviceModel.all.map { $0.year })).sorted(by: >)
        ScrollView {
            VStack(spacing: 16) {
                ForEach(years, id: \.self) { y in
                    Card {
                        Text(String(y)).font(.system(size: 17, weight: .semibold)).foregroundStyle(Palette.grey)
                        ForEach(Array(DeviceModel.all.filter { $0.year == y }.reversed())) { m in
                            HStack(spacing: 12) {
                                Image(systemName: m.screen == .homeButton ? "iphone.gen1" : m.screen == .notch ? "iphone.gen2" : "iphone.gen3")
                                    .font(.system(size: 20))
                                    .frame(width: 28)
                                    .foregroundStyle(m.identifier == cur ? accent : .white)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(m.name).font(.system(size: 16, weight: m.identifier == cur ? .semibold : .regular))
                                    Text(String(format: "%.1f″ · ", m.inches) + m.screenText)
                                        .font(.system(size: 13))
                                        .foregroundStyle(Palette.grey)
                                }
                                Spacer()
                                if m.identifier == cur {
                                    Text(L("set.yours"))
                                        .font(.system(size: 12, weight: .semibold))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 4)
                                        .foregroundStyle(textOn(accent))
                                        .background(accent, in: Capsule())
                                }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(Color.black.ignoresSafeArea())
        .navigationTitle(L("set.models"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
