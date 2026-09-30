import SwiftUI
import UIKit

// MARK: живой акцент (цвет темы или цвет обложки) — во все экраны через окружение

private struct AccentKey: EnvironmentKey {
    static let defaultValue = Color(hex: 0x3E7BFA)
}

extension EnvironmentValues {
    var accent: Color {
        get { self[AccentKey.self] }
        set { self[AccentKey.self] = newValue }
    }
}

/// Текст на цветной кнопке: на светлом цвете — чёрный.
func textOn(_ c: Color) -> Color {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    UIColor(c).getRed(&r, green: &g, blue: &b, alpha: &a)
    return 0.299 * r + 0.587 * g + 0.114 * b > 0.72 ? .black : .white
}

/// Общее состояние экрана: какая вкладка открыта.
final class AppState: ObservableObject {
    static let shared = AppState()
    @Published var tab = LaunchArgs.tab
}

enum Haptic {
    static func tap() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func tick() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}

// MARK: карточка #1C1D21, скругление 24

struct Card<Content: View>: View {
    var padding: CGFloat = 18
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            content
        }
        .padding(padding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

struct CardTitle: View {
    let text: String
    var systemImage: String?

    var body: some View {
        HStack(spacing: 8) {
            if let s = systemImage {
                Image(systemName: s)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.grey)
            }
            Text(text)
                .font(.system(size: 17, weight: .semibold))
        }
    }
}

/// Кнопка-чип: выбранный — цвета акцента.
struct Chip: View {
    let text: String
    var systemImage: String?
    var selected = false
    let action: () -> Void
    @Environment(\.accent) var accent

    var body: some View {
        Button {
            Haptic.tick()
            action()
        } label: {
            HStack(spacing: 6) {
                if let s = systemImage {
                    Image(systemName: s).font(.system(size: 13, weight: .semibold))
                }
                Text(text).font(.system(size: 15, weight: .medium)).lineLimit(1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .foregroundStyle(selected ? textOn(accent) : .white)
            .background(selected ? accent : Palette.chip, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Большая кнопка на всю ширину.
struct WideButton: View {
    let text: String
    var systemImage: String?
    var filled = true
    let action: () -> Void
    @Environment(\.accent) var accent

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let s = systemImage {
                    Image(systemName: s)
                }
                Text(text)
            }
            .font(.system(size: 16, weight: .semibold))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(filled ? textOn(accent) : .white)
            .background(filled ? accent : Palette.chip, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Строка со слайдером: название, значение, слайдер.
struct SliderRow: View {
    let title: String
    let value: String
    @Binding var v: Float
    let range: ClosedRange<Float>
    var step: Float = 0.5
    @Environment(\.accent) var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title).font(.system(size: 15))
                Spacer()
                Text(value)
                    .font(.system(size: 15, weight: .medium).monospacedDigit())
                    .foregroundStyle(Palette.grey)
            }
            Slider(value: $v, in: range, step: step)
                .tint(accent)
        }
    }
}

/// Строки по ширине с переносом (чипы пресетов).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 320
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            row = max(row, size.height)
        }
        return CGSize(width: min(width, widest), height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += row + spacing
                row = 0
            }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}

/// Значок в цветном круге.
struct IconBadge: View {
    let systemImage: String
    var size: CGFloat = 44
    var color: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .background(color.opacity(0.16), in: Circle())
    }
}

/// Экран вкладки: чёрный фон, крупный заголовок iOS, карточки в прокрутке.
struct TabPage<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    content
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(title)
        }
    }
}

/// Маленькая серая подпись.
struct Note: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(Palette.grey)
            .fixedSize(horizontal: false, vertical: true)
    }
}
