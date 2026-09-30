import SwiftUI

/// Кривая эквалайзера: тянете пальцем вверх-вниз — полоса под пальцем меняется (шаг 0,5 дБ).
/// Без onChange — только показ (предпросмотр пресета).
struct EqGraph: View {
    let gains: [Float]
    let bands: Int
    var range: Float = 15
    var onChange: ((Int, Float) -> Void)?
    var compact = false
    @Environment(\.accent) var accent
    @State var active: Int? = nil

    private static let minF: Float = 20, maxF: Float = 20000

    private func x(_ f: Float, _ w: CGFloat) -> CGFloat {
        let t = (log2(f) - log2(EqGraph.minF)) / (log2(EqGraph.maxF) - log2(EqGraph.minF))
        return CGFloat(t) * w
    }

    private func y(_ g: Float, _ h: CGFloat) -> CGFloat {
        let t = (range - max(-range, min(range, g))) / (2 * range)
        return 10 + CGFloat(t) * (h - 20)
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height - (compact ? 0 : 18)
            let freqs = Presets.freqs(bands)
            let pts: [CGPoint] = freqs.indices.map { i in
                CGPoint(x: x(freqs[i], w), y: y(i < gains.count ? gains[i] : 0, h))
            }
            ZStack(alignment: .topLeading) {
                Canvas { ctx, size in
                    // сетка: 0 дБ и ±половина диапазона
                    for g in [range / 2, 0, -range / 2] {
                        var line = Path()
                        line.move(to: CGPoint(x: 0, y: y(g, h)))
                        line.addLine(to: CGPoint(x: size.width, y: y(g, h)))
                        ctx.stroke(line, with: .color(.white.opacity(g == 0 ? 0.18 : 0.07)),
                                   style: StrokeStyle(lineWidth: 1, dash: g == 0 ? [] : [4, 5]))
                    }
                    for f: Float in [100, 1000, 10000] {
                        var line = Path()
                        line.move(to: CGPoint(x: x(f, w), y: 0))
                        line.addLine(to: CGPoint(x: x(f, w), y: h))
                        ctx.stroke(line, with: .color(.white.opacity(0.06)), lineWidth: 1)
                    }
                    let curve = EqGraph.smooth(pts, w)
                    // заливка до нуля
                    var fill = curve
                    fill.addLine(to: CGPoint(x: w, y: y(0, h)))
                    fill.addLine(to: CGPoint(x: 0, y: y(0, h)))
                    fill.closeSubpath()
                    ctx.fill(fill, with: .linearGradient(Gradient(colors: [accent.opacity(0.35), accent.opacity(0.02)]),
                                                         startPoint: CGPoint(x: 0, y: 0), endPoint: CGPoint(x: 0, y: h)))
                    ctx.stroke(curve, with: .color(accent), style: StrokeStyle(lineWidth: compact ? 2.5 : 3.5, lineCap: .round, lineJoin: .round))
                    if !compact {
                        let r: CGFloat = bands > 15 ? 3 : 5
                        for (i, p) in pts.enumerated() {
                            let big = i == active
                            let rr = big ? r * 2 : r
                            let dot = Path(ellipseIn: CGRect(x: p.x - rr, y: p.y - rr, width: rr * 2, height: rr * 2))
                            ctx.fill(dot, with: .color(big ? accent : .white))
                        }
                    }
                }
                if let i = active, i < pts.count, i < gains.count {
                    Text(String(format: "%+.1f dB", gains[i]))
                        .font(.system(size: 13, weight: .semibold).monospacedDigit())
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Palette.chip, in: Capsule())
                        .position(x: min(max(pts[i].x, 40), w - 40), y: max(14, pts[i].y - 24))
                }
                if !compact {
                    ForEach(0..<EqGraph.labels.count, id: \.self) { k in
                        let f = EqGraph.labels[k].0
                        Text(EqGraph.labels[k].1)
                            .font(.system(size: 11))
                            .foregroundStyle(Palette.grey)
                            .position(x: min(max(x(f, w), 12), w - 14), y: h + 10)
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(drag(pts, h), including: onChange == nil ? .none : .all)
        }
    }

    private func drag(_ pts: [CGPoint], _ h: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                if active == nil {
                    // полоса, ближайшая к пальцу по горизонтали
                    active = pts.indices.min(by: { abs(pts[$0].x - v.startLocation.x) < abs(pts[$1].x - v.startLocation.x) })
                    Haptic.tick()
                }
                guard let i = active, i < gains.count else { return }
                let t = Float((v.location.y - 10) / max(1, h - 20))
                var g = range - t * 2 * range
                g = (max(-range, min(range, g)) * 2).rounded() / 2
                if g != gains[i] {
                    if (g == 0) != (gains[i] == 0) { Haptic.tick() }
                    onChange?(i, g)
                }
            }
            .onEnded { _ in
                active = nil
            }
    }

    private static let labels: [(Float, String)] = [(31.5, "30"), (100, "100"), (300, "300"), (1000, "1k"),
                                                     (3000, "3k"), (10000, "10k"), (18000, "20k")]

    /// Плавная кривая через точки (Катмулл-Ром), с полками до краёв.
    static func smooth(_ pts: [CGPoint], _ w: CGFloat) -> Path {
        var p = Path()
        guard let first = pts.first, let last = pts.last else { return p }
        var all = [CGPoint(x: 0, y: first.y)] + pts + [CGPoint(x: w, y: last.y)]
        if all[1].x <= 0 { all.removeFirst() }
        p.move(to: all[0])
        for i in 0..<(all.count - 1) {
            let p0 = all[max(0, i - 1)], p1 = all[i], p2 = all[i + 1], p3 = all[min(all.count - 1, i + 2)]
            let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6)
            let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
            p.addCurve(to: p2, control1: c1, control2: c2)
        }
        return p
    }
}
