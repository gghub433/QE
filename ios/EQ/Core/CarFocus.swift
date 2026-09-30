import Foundation

/// Фокус звука в машине, как на Android: 6 точек салона (водитель, центр, пассажир, задний ряд).
/// iPhone отдаёт в машину стерео, поэтому фокус — это баланс Л/П, посчитанный по физике:
/// в выбранной точке ближние динамики громче (1/r²), и EQ приглушает этот канал.
/// Настройки свои для каждой машины (по её названию).
final class CarFocus: ObservableObject {
    static let shared = CarFocus()

    /// Точки фокуса (x, y по кузову: 0…1 слева направо и от носа к багажнику).
    static let points: [(x: Double, y: Double)] = [
        (0.285, 0.475), (0.5, 0.465), (0.715, 0.475),
        (0.235, 0.695), (0.5, 0.695), (0.765, 0.695),
    ]
    /// Динамики: двери спереди и сзади, твитеры у стёкол.
    static let speakers: [(x: Double, y: Double)] = [
        (0.035, 0.47), (0.965, 0.47), (0.035, 0.66), (0.965, 0.66), (0.12, 0.302), (0.88, 0.302),
    ]
    static let strength: [Double] = [0.4, 0.7, 1.0]
    static let carW = 1.8, carL = 4.5

    @Published private(set) var car: String?
    @Published var point = -1 { didSet { changed() } }
    @Published var mode = 1 { didSet { changed() } }
    @Published var rhd = false { didSet { changed() } }
    @Published var swap = false { didSet { changed() } }

    private var loading = false

    private init() {}

    /// Звук пошёл в машину (name) или ушёл из неё (nil).
    func attach(car name: String?) {
        if name == car { return }
        car = name
        loading = true
        if let name = name, let v = UserDefaults.standard.array(forKey: "car." + name) as? [Int], v.count >= 4 {
            point = v[0]
            mode = v[1]
            rhd = v[2] != 0
            swap = v[3] != 0
        } else {
            point = -1
            mode = 1
            rhd = false
            swap = false
        }
        loading = false
        apply()
    }

    private func changed() {
        if loading { return }
        if let name = car, !LaunchArgs.demo {
            UserDefaults.standard.set([point, mode, rhd ? 1 : 0, swap ? 1 : 0], forKey: "car." + name)
        }
        apply()
    }

    func apply() {
        AudioEngine.shared.carBalance = car == nil ? 0 : balance
    }

    var balance: Float {
        CarFocus.balance(point: point, mode: mode, swap: swap)
    }

    static func balance(point: Int, mode: Int, swap: Bool) -> Float {
        guard point >= 0, point < points.count else { return 0 }
        let p = points[point]
        var eL = 0.0, eR = 0.0
        for s in speakers {
            let dx = (s.x - p.x) * carW, dy = (s.y - p.y) * carL
            let e = 1 / max(0.09, dx * dx + dy * dy)   // не ближе 30 см
            if s.x < 0.5 { eL += e } else { eR += e }
        }
        guard eL > 0, eR > 0 else { return 0 }
        let db = 10 * log10(eL / eR) * strength[max(0, min(strength.count - 1, mode))]
        let b = Float(min(0.9, 1 - pow(10, -abs(db) / 20)))
        let bal = db > 0 ? b : -b
        return swap ? -bal : bal
    }

    /// Разница громкости каналов, дБ (для подписи).
    static func db(_ bal: Float) -> Float {
        20 * log10(max(0.1, 1 - abs(bal)))
    }

    func label(_ p: Int) -> String {
        switch p {
        case 0: return L(rhd ? "car.passenger" : "car.driver")
        case 1: return L("car.front")
        case 2: return L(rhd ? "car.driver" : "car.passenger")
        case 3: return L("car.rearLeft")
        case 4: return L("car.all")
        case 5: return L("car.rearRight")
        default: return L("car.off")
        }
    }
}
