import SwiftUI

/// Салон машины сверху: 6 точек фокуса. Нажмите на место — звук соберётся вокруг него.
struct CarFocusView: View {
    @ObservedObject var focus: CarFocus
    @Environment(\.accent) var accent

    private static let shell = Color(hex: 0x2B2D33)
    private static let edge = Color(hex: 0x5E626C)
    private static let floor = Color(hex: 0x17181C)
    private static let glass = Color(hex: 0x1B2333)
    private static let seat = Color(hex: 0x3A3D45)
    private static let seatBack = Color(hex: 0x4A4E58)

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let w = h * 0.46
            let ox = (geo.size.width - w) / 2
            Canvas { ctx, _ in
                func p(_ x: Double, _ y: Double) -> CGPoint {
                    CGPoint(x: ox + CGFloat(x) * w, y: CGFloat(y) * h)
                }
                func r(_ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double) -> CGRect {
                    CGRect(origin: p(x0, y0), size: CGSize(width: CGFloat(x1 - x0) * w, height: CGFloat(y1 - y0) * h))
                }
                // кузов
                let shell = Path(roundedRect: r(0.02, 0.01, 0.98, 0.99), cornerSize: CGSize(width: w * 0.3, height: h * 0.1),
                                 style: .continuous)
                ctx.fill(shell, with: .color(CarFocusView.shell))
                ctx.stroke(shell, with: .color(CarFocusView.edge), lineWidth: 1.5)
                // лобовое и заднее стекло
                var front = Path()
                front.move(to: p(0.14, 0.33))
                front.addQuadCurve(to: p(0.86, 0.33), control: p(0.5, 0.29))
                front.addLine(to: p(0.94, 0.24))
                front.addQuadCurve(to: p(0.06, 0.24), control: p(0.5, 0.19))
                front.closeSubpath()
                ctx.fill(front, with: .color(CarFocusView.glass))
                var rear = Path()
                rear.move(to: p(0.16, 0.84))
                rear.addQuadCurve(to: p(0.84, 0.84), control: p(0.5, 0.86))
                rear.addLine(to: p(0.9, 0.9))
                rear.addQuadCurve(to: p(0.1, 0.9), control: p(0.5, 0.93))
                rear.closeSubpath()
                ctx.fill(rear, with: .color(CarFocusView.glass))
                // пол салона
                ctx.fill(Path(roundedRect: r(0.09, 0.345, 0.91, 0.83), cornerRadius: w * 0.08), with: .color(CarFocusView.floor))
                // сиденья
                for sx in [0.285, 0.715] {
                    ctx.fill(Path(roundedRect: r(sx - 0.14, 0.43, sx + 0.14, 0.55), cornerRadius: w * 0.05), with: .color(CarFocusView.seat))
                    ctx.fill(Path(roundedRect: r(sx - 0.14, 0.53, sx + 0.14, 0.565), cornerRadius: w * 0.03), with: .color(CarFocusView.seatBack))
                }
                ctx.fill(Path(roundedRect: r(0.12, 0.65, 0.88, 0.76), cornerRadius: w * 0.05), with: .color(CarFocusView.seat))
                ctx.fill(Path(roundedRect: r(0.12, 0.745, 0.88, 0.78), cornerRadius: w * 0.03), with: .color(CarFocusView.seatBack))
                // руль у водителя
                let wheelX = focus.rhd ? 0.715 : 0.285
                let wheel = Path(ellipseIn: CGRect(x: p(wheelX, 0.39).x - w * 0.1, y: p(wheelX, 0.39).y - w * 0.035,
                                                   width: w * 0.2, height: w * 0.07))
                ctx.stroke(wheel, with: .color(CarFocusView.edge), lineWidth: 3)

                let sel = focus.point
                // лучи от динамиков к точке фокуса
                if sel >= 0, sel < CarFocus.points.count {
                    let fp = CarFocus.points[sel]
                    for s in CarFocus.speakers {
                        var ray = Path()
                        ray.move(to: p(s.x, s.y))
                        ray.addLine(to: p(fp.x, fp.y))
                        ctx.stroke(ray, with: .color(accent.opacity(0.28)), style: StrokeStyle(lineWidth: 1.5, dash: [3, 4]))
                    }
                    let c = p(fp.x, fp.y), gr = w * 0.3
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - gr, y: c.y - gr, width: gr * 2, height: gr * 2)),
                             with: .radialGradient(Gradient(colors: [accent.opacity(0.55), accent.opacity(0)]),
                                                   center: c, startRadius: 0, endRadius: gr))
                }
                // динамики
                for s in CarFocus.speakers {
                    let c = p(s.x, s.y), sr = w * 0.035
                    ctx.fill(Path(ellipseIn: CGRect(x: c.x - sr, y: c.y - sr, width: sr * 2, height: sr * 2)),
                             with: .color(sel >= 0 ? accent : Color(hex: 0x8E9199)))
                }
                // точки фокуса
                for (i, fp) in CarFocus.points.enumerated() {
                    let c = p(fp.x, fp.y)
                    let pr = w * (i == sel ? 0.065 : 0.045)
                    let dot = Path(ellipseIn: CGRect(x: c.x - pr, y: c.y - pr, width: pr * 2, height: pr * 2))
                    if i == sel {
                        ctx.fill(dot, with: .color(accent))
                        ctx.stroke(dot, with: .color(.white), lineWidth: 2)
                    } else {
                        ctx.stroke(dot, with: .color(.white.opacity(0.7)), lineWidth: 2)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { loc in
                var best = -1
                var bestD = CGFloat.greatestFiniteMagnitude
                for (i, fp) in CarFocus.points.enumerated() {
                    let dx = loc.x - (ox + CGFloat(fp.x) * w), dy = loc.y - CGFloat(fp.y) * h
                    let d = dx * dx + dy * dy
                    if d < bestD {
                        bestD = d
                        best = i
                    }
                }
                guard best >= 0, bestD < (w * 0.2) * (w * 0.2) else { return }
                Haptic.tap()
                focus.point = focus.point == best ? -1 : best
            }
        }
    }
}
