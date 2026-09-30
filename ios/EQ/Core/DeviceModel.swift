import UIKit

/// Модель iPhone по системному коду (iPhone15,3 → «iPhone 14 Pro Max»).
/// Все модели от iPhone 11 (2019) до новейших; неизвестный новый iPhone тоже работает —
/// интерфейс подстраивается под экран сам (безопасные зоны, чёлка, Dynamic Island).
struct DeviceModel: Identifiable {
    enum Screen {
        case homeButton, notch, dynamicIsland
    }

    let identifier: String
    let name: String
    let screen: Screen
    let inches: Double
    let year: Int

    var id: String { identifier }

    /// Код → название, экран, диагональ, год выхода. По порядку выхода.
    static let all: [DeviceModel] = [
        DeviceModel(identifier: "iPhone12,1", name: "iPhone 11", screen: .notch, inches: 6.1, year: 2019),
        DeviceModel(identifier: "iPhone12,3", name: "iPhone 11 Pro", screen: .notch, inches: 5.8, year: 2019),
        DeviceModel(identifier: "iPhone12,5", name: "iPhone 11 Pro Max", screen: .notch, inches: 6.5, year: 2019),
        DeviceModel(identifier: "iPhone12,8", name: "iPhone SE (2020)", screen: .homeButton, inches: 4.7, year: 2020),
        DeviceModel(identifier: "iPhone13,1", name: "iPhone 12 mini", screen: .notch, inches: 5.4, year: 2020),
        DeviceModel(identifier: "iPhone13,2", name: "iPhone 12", screen: .notch, inches: 6.1, year: 2020),
        DeviceModel(identifier: "iPhone13,3", name: "iPhone 12 Pro", screen: .notch, inches: 6.1, year: 2020),
        DeviceModel(identifier: "iPhone13,4", name: "iPhone 12 Pro Max", screen: .notch, inches: 6.7, year: 2020),
        DeviceModel(identifier: "iPhone14,4", name: "iPhone 13 mini", screen: .notch, inches: 5.4, year: 2021),
        DeviceModel(identifier: "iPhone14,5", name: "iPhone 13", screen: .notch, inches: 6.1, year: 2021),
        DeviceModel(identifier: "iPhone14,2", name: "iPhone 13 Pro", screen: .notch, inches: 6.1, year: 2021),
        DeviceModel(identifier: "iPhone14,3", name: "iPhone 13 Pro Max", screen: .notch, inches: 6.7, year: 2021),
        DeviceModel(identifier: "iPhone14,6", name: "iPhone SE (2022)", screen: .homeButton, inches: 4.7, year: 2022),
        DeviceModel(identifier: "iPhone14,7", name: "iPhone 14", screen: .notch, inches: 6.1, year: 2022),
        DeviceModel(identifier: "iPhone14,8", name: "iPhone 14 Plus", screen: .notch, inches: 6.7, year: 2022),
        DeviceModel(identifier: "iPhone15,2", name: "iPhone 14 Pro", screen: .dynamicIsland, inches: 6.1, year: 2022),
        DeviceModel(identifier: "iPhone15,3", name: "iPhone 14 Pro Max", screen: .dynamicIsland, inches: 6.7, year: 2022),
        DeviceModel(identifier: "iPhone15,4", name: "iPhone 15", screen: .dynamicIsland, inches: 6.1, year: 2023),
        DeviceModel(identifier: "iPhone15,5", name: "iPhone 15 Plus", screen: .dynamicIsland, inches: 6.7, year: 2023),
        DeviceModel(identifier: "iPhone16,1", name: "iPhone 15 Pro", screen: .dynamicIsland, inches: 6.1, year: 2023),
        DeviceModel(identifier: "iPhone16,2", name: "iPhone 15 Pro Max", screen: .dynamicIsland, inches: 6.7, year: 2023),
        DeviceModel(identifier: "iPhone17,3", name: "iPhone 16", screen: .dynamicIsland, inches: 6.1, year: 2024),
        DeviceModel(identifier: "iPhone17,4", name: "iPhone 16 Plus", screen: .dynamicIsland, inches: 6.7, year: 2024),
        DeviceModel(identifier: "iPhone17,1", name: "iPhone 16 Pro", screen: .dynamicIsland, inches: 6.3, year: 2024),
        DeviceModel(identifier: "iPhone17,2", name: "iPhone 16 Pro Max", screen: .dynamicIsland, inches: 6.9, year: 2024),
        DeviceModel(identifier: "iPhone17,5", name: "iPhone 16e", screen: .notch, inches: 6.1, year: 2025),
        DeviceModel(identifier: "iPhone18,3", name: "iPhone 17", screen: .dynamicIsland, inches: 6.3, year: 2025),
        DeviceModel(identifier: "iPhone18,4", name: "iPhone Air", screen: .dynamicIsland, inches: 6.5, year: 2025),
        DeviceModel(identifier: "iPhone18,1", name: "iPhone 17 Pro", screen: .dynamicIsland, inches: 6.3, year: 2025),
        DeviceModel(identifier: "iPhone18,2", name: "iPhone 17 Pro Max", screen: .dynamicIsland, inches: 6.9, year: 2025),
        DeviceModel(identifier: "iPhone18,5", name: "iPhone 17e", screen: .notch, inches: 6.1, year: 2026),
        DeviceModel(identifier: "iPhone19,2", name: "iPhone 18 Pro", screen: .dynamicIsland, inches: 6.3, year: 2026),
        DeviceModel(identifier: "iPhone19,3", name: "iPhone 18 Pro Max", screen: .dynamicIsland, inches: 6.9, year: 2026),
    ]

    /// Одна модель — несколько кодов (разные страны).
    static let aliases = ["iPhone19,7": "iPhone19,3"]

    static let current: DeviceModel = detect()

    /// Код устройства; в симуляторе — код модели, которую он изображает.
    static func identifierString() -> String {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return sim
        }
        var info = utsname()
        uname(&info)
        let bytes = withUnsafeBytes(of: &info.machine) { raw in
            Array(raw.prefix { $0 != 0 })
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    static func detect() -> DeviceModel {
        let raw = identifierString()
        let id = aliases[raw] ?? raw
        if let known = all.first(where: { $0.identifier == id }) {
            return known
        }
        // модель новее этой версии EQ: название придёт с обновлением, а интерфейс
        // и так подстроится — SwiftUI сам обходит чёлку, Dynamic Island и скругления экрана
        let name = raw.hasPrefix("iPhone") ? L("device.newIphone") : UIDevice.current.model
        return DeviceModel(identifier: raw, name: name, screen: .dynamicIsland, inches: 0, year: 0)
    }

    var screenText: String {
        switch screen {
        case .homeButton: return L("device.screen.home")
        case .notch: return L("device.screen.notch")
        case .dynamicIsland: return L("device.screen.island")
        }
    }
}
