import Foundation

/// Звук целиком: кривая и обработка. Такой же, как в Android-версии EQ.
struct EQPreset: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var bands: Int
    var gains: [Float]
    var punch: Float = 0
    var boost: Float = 0
    var balance: Float = 0
    var preamp: Float = 0
    var leveling = false
}

enum Presets {
    static let f9: [Float] = [63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    static let f15: [Float] = [25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000]
    static let f31: [Float] = [20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
                               800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000]

    static func freqs(_ bands: Int) -> [Float] {
        bands == 15 ? f15 : bands == 31 ? f31 : f9
    }

    /// Встроенные пресеты (как на Android): ключ названия, 9 полос, запас, панч.
    static let builtIn: [(key: String, gains: [Float], preamp: Float, punch: Float)] = [
        ("p.flat", [0, 0, 0, 0, 0, 0, 0, 0, 0], 0, 0),
        ("p.hardBass", [9, 7, 3, 0, -1, 0, 1, 2, 1], -6, 0.5),
        ("p.bassMax", [12, 9, 4, -1, -2, -1, 1, 2, 0], -9, 0.8),
        ("p.bass", [6, 5, 3, 1, 0, 0, 0, 0, 0], -3, 0.3),
        ("p.v", [5, 4, 1, -1, -2, -1, 1, 3, 4], -3, 0.2),
        ("p.vocal", [-2, -1, 0, 2, 3, 3, 2, 0, -1], -2, 0),
        ("p.clarity", [0, 0, -1, 0, 1, 2, 3, 3, 2], -2, 0),
        ("p.soft", [1, 1, 0, 0, -1, -2, -3, -3, -4], 0, 0),
    ]

    /// Пересчёт кривой в другие полосы: линейно по логарифму частоты.
    static func resample(_ src: [Float], from sf: [Float], to df: [Float]) -> [Float] {
        guard !src.isEmpty, src.count == sf.count else { return Array(repeating: 0, count: df.count) }
        return df.map { f -> Float in
            if f <= sf[0] { return src[0] }
            if f >= sf[sf.count - 1] { return src[src.count - 1] }
            var i = 0
            while i < sf.count - 2 && sf[i + 1] < f { i += 1 }
            let a = log2(sf[i]), b = log2(sf[i + 1]), t = (log2(f) - a) / (b - a)
            let v = src[i] + (src[i + 1] - src[i]) * t
            return (v * 2).rounded() / 2
        }
    }

    // MARK: свои пресеты

    static func user() -> [EQPreset] {
        guard let data = UserDefaults.standard.data(forKey: "userPresets"),
              let list = try? JSONDecoder().decode([EQPreset].self, from: data) else { return [] }
        return list
    }

    static func saveUser(_ list: [EQPreset]) {
        if let data = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(data, forKey: "userPresets")
        }
    }
}

/// Код пресета EQ-XXXX-XXXX-… и ссылка для QR — байт в байт как в Android-версии,
/// поэтому код с Android открывается на iPhone и наоборот.
/// Байты: [1][полос][полосы ×0,5 дБ со знаком][панч 0…100][усиление ×0,5][баланс -100…100][запас ×-0,5][флаги][CRC-8].
enum PresetCode {
    static let link = "https://gghub433.github.io/QE/p/"
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    static func toBytes(_ p: EQPreset) -> [UInt8] {
        func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { max(lo, min(hi, v)) }
        func r(_ x: Float) -> Int { Int((x).rounded(.toNearestOrAwayFromZero)) }
        var b: [UInt8] = [1, UInt8(p.bands)]
        for i in 0..<p.bands {
            let g = i < p.gains.count ? p.gains[i] : 0
            b.append(UInt8(bitPattern: Int8(clamp(r(g * 2), -48, 48))))
        }
        b.append(UInt8(clamp(r(p.punch * 100), 0, 100)))
        b.append(UInt8(clamp(r(p.boost * 2), 0, 24)))
        b.append(UInt8(bitPattern: Int8(clamp(r(p.balance * 100), -100, 100))))
        b.append(UInt8(clamp(r(-p.preamp * 2), 0, 24)))
        b.append(p.leveling ? 1 : 0)
        b.append(crc8(b, b.count))
        return b
    }

    static func fromBytes(_ b: [UInt8]) -> EQPreset? {
        // версия 1 — полосы по 0,5 дБ, версия 2 — по 0,1 дБ (Android 7.7+)
        guard b.count >= 4, b[0] == 1 || b[0] == 2 else { return nil }
        let unit: Float = b[0] == 2 ? 10 : 2
        let bands = Int(b[1])
        guard bands == 9 || bands == 15 || bands == 31 else { return nil }
        let len = 2 + bands + 5 + 1
        guard b.count == len, crc8(b, len - 1) == b[len - 1] else { return nil }
        var p = EQPreset(name: "", bands: bands, gains: [])
        for i in 0..<bands {
            p.gains.append(Float(Int8(bitPattern: b[2 + i])) / unit)
        }
        let k = 2 + bands
        p.punch = Float(min(100, Int(b[k]))) / 100
        p.boost = Float(min(24, Int(b[k + 1]))) / 2
        p.balance = Float(max(-100, min(100, Int(Int8(bitPattern: b[k + 2]))))) / 100
        p.preamp = -Float(min(24, Int(b[k + 3]))) / 2
        p.leveling = (b[k + 4] & 1) != 0
        return p
    }

    /// CRC-8, полином 0x07.
    static func crc8(_ b: [UInt8], _ len: Int) -> UInt8 {
        var crc: UInt8 = 0
        for i in 0..<len {
            crc ^= b[i]
            for _ in 0..<8 {
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x07 : crc << 1
            }
        }
        return crc
    }

    static func base32(_ b: [UInt8]) -> String {
        var out = ""
        var buf = 0, bits = 0
        for x in b {
            buf = (buf << 8) | Int(x)
            bits += 8
            while bits >= 5 {
                out.append(alphabet[(buf >> (bits - 5)) & 31])
                bits -= 5
            }
            buf &= (1 << bits) - 1
        }
        if bits > 0 {
            out.append(alphabet[(buf << (5 - bits)) & 31])
        }
        return out
    }

    static func fromBase32(_ s: String) -> [UInt8]? {
        var out: [UInt8] = []
        var buf = 0, bits = 0
        for ch in s {
            guard let v = alphabet.firstIndex(of: ch) else { return nil }
            buf = (buf << 5) | v
            bits += 5
            if bits >= 8 {
                out.append(UInt8((buf >> (bits - 8)) & 0xFF))
                bits -= 8
                buf &= (1 << bits) - 1
            }
        }
        return out
    }

    /// EQ-ABCD-EFGH-…
    static func encode(_ p: EQPreset) -> String {
        let raw = Array(base32(toBytes(p)))
        var s = "EQ"
        var i = 0
        while i < raw.count {
            s += "-" + String(raw[i..<min(raw.count, i + 4)])
            i += 4
        }
        return s
    }

    static func url(_ code: String) -> String {
        link + code
    }

    /// Пресет из кода, ссылки https://…/p/<код>, eq://preset/<код> или сообщения с кодом внутри.
    static func decode(_ text: String) -> EQPreset? {
        let up = Array(text.uppercased())
        var i = 0
        while i + 1 < up.count {
            if up[i] == "E" && up[i + 1] == "Q" && (i == 0 || !(up[i - 1].isLetter || up[i - 1].isNumber)) {
                if let p = parse(up, from: i + 2) { return p }
            }
            i += 1
        }
        return parse(up, from: 0)
    }

    private static func parse(_ up: [Character], from: Int) -> EQPreset? {
        var s: [Character] = []
        var i = from
        while i < up.count {
            let ch = up[i]
            i += 1
            if ch == "-" || ch == " " { continue }
            if !alphabet.contains(ch) { break }
            s.append(ch)
        }
        guard s.count >= 4, let head = fromBase32(String(s[0..<4])), head.count >= 2 else { return nil }
        let bytes = 2 + Int(head[1]) + 6
        let chars = (bytes * 8 + 4) / 5
        guard s.count >= chars, let all = fromBase32(String(s[0..<chars])) else { return nil }
        return fromBytes(all)
    }
}
