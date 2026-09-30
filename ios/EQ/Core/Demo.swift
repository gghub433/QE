import UIKit

/// Картинка для скриншотов (запуск с -demo): треки, обложки, статистика, AirPods Pro или машина (-car).
/// Файлов нет — звук не играет, только интерфейс.
enum Demo {
    static func applyIfNeeded() {
        guard LaunchArgs.demo else { return }
        let items: [(String, String, [UInt32])] = [
            ("Midnight Drive", "Nova Lane", [0x3E7BFA, 0x7C5CFF, 0x0B0C10]),
            ("Golden Hour", "Sunset Theory", [0xFF9F0A, 0xFF2D95, 0x1A0B14]),
            ("Deep Water", "Blue Harbor", [0x00C7BE, 0x3E7BFA, 0x07121A]),
            ("Neon Heart", "Pulse Avenue", [0xFF2D95, 0x7C5CFF, 0x12081A]),
            ("Low End Theory", "Bass Republic", [0x30D158, 0x00C7BE, 0x06140C]),
            ("Paper Planes", "Echo Park", [0xFF5A5A, 0xFF9F0A, 0x180A08]),
        ]
        let lengths: [Double] = [212, 187, 243, 199, 231, 176]
        let tracks = items.enumerated().map { pair -> Track in
            let (i, it) = pair
            return Track(id: "demo\(i)", url: URL(fileURLWithPath: "/demo/\(i).mp3"), title: it.0, artist: it.1,
                  artwork: cover(it.2, seed: i), length: lengths[i])
        }
        Library.shared.showDemo(tracks)
        let e = AudioEngine.shared
        if let hard = Presets.builtIn.first(where: { $0.key == "p.hardBass" }) {
            e.applyPreset(EQPreset(name: L(hard.key), bands: 9, gains: hard.gains, punch: hard.punch,
                                   preamp: hard.preamp))
        }
        e.showDemo(tracks[0], position: 83, duration: tracks[0].length)

        var days: [String: Double] = [:]
        let mins: [Double] = [48, 95, 32, 120, 64, 150, 87]
        for (i, m) in mins.enumerated() {
            days[ListenStats.key(Date().addingTimeInterval(Double(i - 6) * 86400))] = m * 60
        }
        ListenStats.shared.showDemo(days: days, artists: [
            "Nova Lane": 9800, "Bass Republic": 7300, "Sunset Theory": 5200, "Blue Harbor": 3100, "Echo Park": 1900,
        ])
        if LaunchArgs.args.contains("-car") {
            AudioRoute.shared.showDemo(name: "Toyota Camry", kind: .car, battery: 0.82)
            CarFocus.shared.point = 0
        } else {
            AudioRoute.shared.showDemo(name: "AirPods Pro", kind: .airpodsPro, battery: 0.82)
        }
    }

    /// Абстрактная обложка: градиент и мягкие круги.
    static func cover(_ colors: [UInt32], seed: Int) -> Data? {
        let size = CGSize(width: 600, height: 600)
        let img = UIGraphicsImageRenderer(size: size).image { ctx in
            let c = ctx.cgContext
            let cols = colors.map { UIColor(rgb: $0).cgColor } as CFArray
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: cols, locations: [0, 0.55, 1]) {
                c.drawLinearGradient(g, start: .zero, end: CGPoint(x: size.width, y: size.height), options: [])
            }
            var rnd = UInt32(seed * 7919 + 17)
            func next() -> CGFloat {
                rnd = rnd &* 1_103_515_245 &+ 12345
                return CGFloat((rnd >> 8) % 1000) / 1000
            }
            for _ in 0..<5 {
                let r = 60 + next() * 200
                let rect = CGRect(x: next() * size.width - r / 2, y: next() * size.height - r / 2, width: r, height: r)
                c.setFillColor(UIColor(white: 1, alpha: 0.06 + next() * 0.1).cgColor)
                c.fillEllipse(in: rect)
            }
            c.setStrokeColor(UIColor(white: 1, alpha: 0.35).cgColor)
            c.setLineWidth(10)
            c.setLineCap(.round)
            for k in 0..<9 {
                let x = 150 + CGFloat(k) * 37.5
                let h = 40 + next() * 160
                c.move(to: CGPoint(x: x, y: 430 - h / 2))
                c.addLine(to: CGPoint(x: x, y: 430 + h / 2))
            }
            c.strokePath()
        }
        return img.jpegData(compressionQuality: 0.85)
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
