import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// Цвет от обложки: самый яркий насыщенный цвет картинки (как на Android).
enum CoverColor {
    private static var cache: [String: Color] = [:]

    static func of(_ track: Track?) -> Color? {
        guard let t = track, let data = t.artwork else { return nil }
        if let c = cache[t.id] { return c }
        guard let img = UIImage(data: data), let c = vivid(img) else { return nil }
        cache[t.id] = c
        return c
    }

    static func vivid(_ img: UIImage) -> Color? {
        let side = 24
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let small = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: fmt).image { _ in
            img.draw(in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        guard let cg = small.cgImage else { return nil }
        var px = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = px.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: side, height: side, bitsPerComponent: 8,
                                      bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return nil }
        // 12 корзин оттенка, вес = насыщенность × яркость
        var weight = [Double](repeating: 0, count: 12)
        var sumR = [Double](repeating: 0, count: 12), sumG = sumR, sumB = sumR
        for i in stride(from: 0, to: px.count, by: 4) {
            let r = Double(px[i]) / 255, g = Double(px[i + 1]) / 255, b = Double(px[i + 2]) / 255
            let mx = max(r, g, b), mn = min(r, g, b)
            let s = mx > 0 ? (mx - mn) / mx : 0
            if s < 0.25 || mx < 0.2 { continue }
            var h: Double
            if mx == r { h = (g - b) / (mx - mn) } else if mx == g { h = 2 + (b - r) / (mx - mn) } else { h = 4 + (r - g) / (mx - mn) }
            h = (h < 0 ? h + 6 : h) / 6
            let k = min(11, Int(h * 12))
            let w = s * mx
            weight[k] += w
            sumR[k] += r * w
            sumG[k] += g * w
            sumB[k] += b * w
        }
        guard let best = weight.indices.max(by: { weight[$0] < weight[$1] }), weight[best] > 0.5 else { return nil }
        var r = sumR[best] / weight[best], g = sumG[best] / weight[best], b = sumB[best] / weight[best]
        // поднять яркость, чтобы цвет читался на тёмном фоне
        let mx = max(r, g, b)
        if mx < 0.85 {
            let k = 0.85 / mx
            r = min(1, r * k)
            g = min(1, g * k)
            b = min(1, b * k)
        }
        return Color(.sRGB, red: r, green: g, blue: b, opacity: 1)
    }
}

/// QR-код ссылки на пресет (CoreImage, без сторонних библиотек).
enum QR {
    static func image(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
