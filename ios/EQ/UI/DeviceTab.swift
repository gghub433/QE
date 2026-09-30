import AVKit
import SwiftUI

/// Вкладка «Устройство»: куда идёт звук, машина с фокусом звука, этот iPhone.
struct DeviceTab: View {
    @ObservedObject private var route = AudioRoute.shared
    @ObservedObject private var engine = AudioEngine.shared
    @ObservedObject private var car = CarFocus.shared
    @ObservedObject private var app = AppState.shared
    @Environment(\.accent) var accent

    var body: some View {
        TabPage(title: L("tab.device")) {
            routeCard
            if route.kind == .car {
                carCard
            }
            soundCard
            phoneCard
            if route.kind != .car {
                Card {
                    CardTitle(text: L("car.title"), systemImage: "car.fill")
                    Note(text: L("car.hint"))
                }
            }
        }
    }

    // MARK: куда идёт звук

    private var routeCard: some View {
        Card {
            HStack(spacing: 16) {
                Image(systemName: route.symbol)
                    .font(.system(size: 40, weight: .regular))
                    .foregroundStyle(accent)
                    .frame(width: 76, height: 76)
                    .background(accent.opacity(0.14), in: Circle())
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("route.now"))
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.grey)
                    Text(route.name)
                        .font(.system(size: 22, weight: .bold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                    Text(route.kindText)
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.grey)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 10) {
                RoutePicker(tint: accent)
                    .frame(width: 44, height: 44)
                    .background(Palette.chip, in: Circle())
                Text(L("route.pick"))
                    .font(.system(size: 15, weight: .medium))
                Spacer()
            }
            if route.isApple {
                Note(text: L("route.appleBattery"))
            }
        }
    }

    // MARK: машина

    private var carCard: some View {
        Card {
            HStack {
                CardTitle(text: L("car.focus"), systemImage: "car.fill")
                Spacer()
                if car.point >= 0 {
                    Text(String(format: "%.1f dB", CarFocus.db(car.balance)))
                        .font(.system(size: 14, weight: .medium).monospacedDigit())
                        .foregroundStyle(Palette.grey)
                }
            }
            CarFocusView(focus: car)
                .frame(height: 330)
                .frame(maxWidth: .infinity)
            Text(car.point >= 0 ? car.label(car.point) : L("car.tapSeat"))
                .font(.system(size: 17, weight: .semibold))
                .frame(maxWidth: .infinity)
            HStack(spacing: 8) {
                Chip(text: L("car.soft"), selected: car.mode == 0) { car.mode = 0 }
                Chip(text: L("car.normal"), selected: car.mode == 1) { car.mode = 1 }
                Chip(text: L("car.strong"), selected: car.mode == 2) { car.mode = 2 }
            }
            .frame(maxWidth: .infinity)
            Toggle(L("car.rhd"), isOn: $car.rhd).tint(accent)
            Toggle(L("car.swap"), isOn: $car.swap).tint(accent)
            Note(text: L("car.note"))
        }
    }

    // MARK: звук сейчас

    private var soundCard: some View {
        Card {
            HStack {
                CardTitle(text: L("eq.title"), systemImage: "slider.vertical.3")
                Spacer()
                Toggle("", isOn: $engine.enabled).labelsHidden().tint(accent)
            }
            Button {
                app.tab = 1
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    EqGraph(gains: engine.gains, bands: engine.bandCount, compact: true)
                        .frame(height: 70)
                        .opacity(engine.enabled ? 1 : 0.35)
                    HStack {
                        Text(engine.presetName.isEmpty ? L("eq.custom") : engine.presetName)
                            .font(.system(size: 15, weight: .medium))
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Palette.grey)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: этот iPhone

    private var phoneCard: some View {
        let m = DeviceModel.current
        return Card {
            HStack(spacing: 16) {
                Image(systemName: m.screen == .homeButton ? "iphone.gen1" : m.screen == .notch ? "iphone.gen2" : "iphone.gen3")
                    .font(.system(size: 34))
                    .foregroundStyle(.white)
                    .frame(width: 60, height: 60)
                    .background(Palette.chip, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(L("phone.this"))
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.grey)
                    Text(m.name)
                        .font(.system(size: 20, weight: .bold))
                    Text(phoneLine(m))
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.grey)
                }
                Spacer(minLength: 0)
            }
            if route.battery >= 0 {
                HStack(spacing: 10) {
                    Image(systemName: route.charging ? "battery.100.bolt" : "battery.75")
                        .foregroundStyle(route.battery < 0.2 ? Color.red : Palette.green)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Palette.chip)
                            Capsule()
                                .fill(route.battery < 0.2 ? Color.red : Palette.green)
                                .frame(width: g.size.width * CGFloat(route.battery))
                        }
                    }
                    .frame(height: 8)
                    Text("\(Int((route.battery * 100).rounded()))%")
                        .font(.system(size: 15, weight: .semibold).monospacedDigit())
                }
            }
        }
    }

    private func phoneLine(_ m: DeviceModel) -> String {
        var parts: [String] = []
        if m.inches > 0 {
            parts.append(String(format: "%.1f″", m.inches))
        }
        parts.append(m.screenText)
        parts.append("iOS " + UIDevice.current.systemVersion)
        return parts.joined(separator: " · ")
    }
}

/// Системная кнопка выбора, куда играть звук (AirPods, колонка, AirPlay).
struct RoutePicker: UIViewRepresentable {
    var tint: Color

    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.prioritizesVideoDevices = false
        v.tintColor = .white
        v.activeTintColor = UIColor(tint)
        return v
    }

    func updateUIView(_ v: AVRoutePickerView, context: Context) {
        v.activeTintColor = UIColor(tint)
    }
}
