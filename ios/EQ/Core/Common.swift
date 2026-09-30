import SwiftUI
import UIKit

/// Текст из Localizable.strings (en / ru / lv / uk).
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

/// Текст с подстановкой: L("mt.today", "1 ч").
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), arguments: args)
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: opacity)
    }
}

/// Цвета как в Android-версии: тёмная тема, карточки #1C1D21.
enum Palette {
    static let card = Color(hex: 0x1C1D21)
    static let chip = Color(hex: 0x3A3B40)
    static let grey = Color(hex: 0x8E9199)
    static let green = Color(hex: 0x4CD964)
    static let orange = Color(hex: 0xFFB340)
}

/// Параметры запуска: -demo (картинка для скриншотов), -tab N (открыть вкладку).
enum LaunchArgs {
    static let args = ProcessInfo.processInfo.arguments

    static var demo: Bool {
        args.contains("-demo")
    }

    static var tab: Int {
        if let i = args.firstIndex(of: "-tab"), i + 1 < args.count, let n = Int(args[i + 1]) {
            return n
        }
        return 0
    }
}

/// Оформление: цвет акцента (8 цветов, как на Android) и «цвет от обложки».
final class AppSettings: ObservableObject {
    static let shared = AppSettings()
    static let accents: [UInt32] = [0x3E7BFA, 0x7C5CFF, 0xFF2D95, 0xFF5A5A, 0xFF9F0A, 0x30D158, 0x00C7BE, 0xE5E5EA]

    @Published var accentIndex: Int {
        didSet { UserDefaults.standard.set(accentIndex, forKey: "accent") }
    }
    @Published var coverColor: Bool {
        didSet { UserDefaults.standard.set(coverColor, forKey: "coverColor") }
    }

    var accent: Color {
        Color(hex: AppSettings.accents[max(0, min(AppSettings.accents.count - 1, accentIndex))])
    }

    /// Текст на акцентной кнопке: на светлом акценте — тёмный.
    var onAccent: Color {
        accentIndex == AppSettings.accents.count - 1 ? .black : .white
    }

    private init() {
        accentIndex = UserDefaults.standard.integer(forKey: "accent")
        coverColor = UserDefaults.standard.object(forKey: "coverColor") as? Bool ?? true
    }
}

/// Длительность «1 ч 20 мин» / «35 мин».
func durationText(_ seconds: Double) -> String {
    let s = Int(seconds)
    let h = s / 3600, m = (s % 3600) / 60
    return h > 0 ? L("time.hm", h, m) : L("time.m", m)
}

/// Время трека «3:07».
func clockText(_ seconds: Double) -> String {
    let s = max(0, Int(seconds))
    return String(format: "%d:%02d", s / 60, s % 60)
}
