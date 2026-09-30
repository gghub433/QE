import SwiftUI

/// Живая волна под обложкой: бежит, пока играет музыка, и затихает на паузе.
struct WaveView: View {
    let playing: Bool
    var color: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: !playing)) { tl in
            Canvas { ctx, size in
                let t = tl.date.timeIntervalSinceReferenceDate
                let mid = size.height / 2
                for layer in 0..<3 {
                    let k = CGFloat(layer)
                    var p = Path()
                    let amp = (size.height * 0.36) * (1 - k * 0.28) * (playing ? 1 : 0.08)
                    let freq = 2.2 + Double(layer) * 0.9
                    let speed = 1.6 + Double(layer) * 0.7
                    var x: CGFloat = 0
                    while x <= size.width {
                        let u = Double(x / size.width)
                        // края затухают, чтобы волна не обрывалась
                        let edge = sin(u * .pi)
                        let yv = sin(u * freq * 2 * .pi + t * speed + Double(layer)) * edge
                        let pt = CGPoint(x: x, y: mid + CGFloat(yv) * amp)
                        if x == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
                        x += 3
                    }
                    ctx.stroke(p, with: .color(color.opacity(layer == 0 ? 1 : 0.45 - Double(layer) * 0.12)),
                               style: StrokeStyle(lineWidth: layer == 0 ? 3 : 2, lineCap: .round))
                }
            }
        }
    }
}
