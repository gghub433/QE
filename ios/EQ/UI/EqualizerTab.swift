import SwiftUI
import UIKit

/// Вкладка «Эквалайзер»: кривая 9 / 15 / 31 полоса, A/B, обработка, пресеты, коды и QR.
struct EqualizerTab: View {
    @ObservedObject private var engine = AudioEngine.shared
    @ObservedObject private var route = AudioRoute.shared
    @Environment(\.accent) var accent

    @State var userPresets = Presets.user()
    @State var saving = false
    @State var newName = ""
    @State var sharing: EQPreset? = nil
    @State var entering = false
    @State var holding = false

    var body: some View {
        TabPage(title: L("tab.eq")) {
            curveCard
            soundCard
            presetsCard
            Card {
                CardTitle(text: L("eq.whereTitle"), systemImage: "info.circle")
                Note(text: L("eq.whereText"))
            }
        }
        .alert(L("eq.saveTitle"), isPresented: $saving) {
            TextField(L("eq.name"), text: $newName)
            Button(L("save")) { save() }
            Button(L("cancel"), role: .cancel) {}
        }
        .sheet(item: $sharing) { p in
            ShareSheet(preset: p)
                .environment(\.accent, accent)
        }
        .sheet(isPresented: $entering) {
            EnterCodeSheet()
                .environment(\.accent, accent)
        }
    }

    // MARK: кривая

    private var curveCard: some View {
        Card {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(engine.presetName.isEmpty ? L("eq.custom") : engine.presetName)
                        .font(.system(size: 20, weight: .bold))
                        .lineLimit(1)
                    Text(L("eq.for", route.name))
                        .font(.system(size: 14))
                        .foregroundStyle(Palette.grey)
                        .lineLimit(1)
                }
                Spacer()
                Toggle("", isOn: $engine.enabled).labelsHidden().tint(accent)
            }
            EqGraph(gains: engine.gains, bands: engine.bandCount, onChange: { i, g in
                var gs = engine.gains
                if i < gs.count {
                    gs[i] = g
                    engine.gains = gs
                    engine.presetName = ""
                }
            })
            .frame(height: 230)
            .opacity(engine.enabled && !holding ? 1 : 0.35)
            HStack(spacing: 8) {
                ForEach([9, 15, 31], id: \.self) { n in
                    Chip(text: "\(n)", selected: engine.bandCount == n) {
                        engine.setBandCount(n)
                    }
                }
                Spacer()
                abButton
            }
        }
    }

    /// A/B: пока держите — звучит оригинал той же громкости.
    private var abButton: some View {
        Text(holding ? L("eq.abOriginal") : L("eq.ab"))
            .font(.system(size: 15, weight: .semibold))
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .foregroundStyle(holding ? textOn(accent) : .white)
            .background(holding ? accent : Palette.chip, in: Capsule())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !holding {
                        holding = true
                        engine.abBypass = true
                        Haptic.tap()
                    }
                }
                .onEnded { _ in
                    holding = false
                    engine.abBypass = false
                })
            .accessibilityLabel(L("eq.abHint"))
    }

    // MARK: обработка

    private var soundCard: some View {
        Card {
            CardTitle(text: L("eq.processing"), systemImage: "waveform")
            SliderRow(title: L("eq.punch"), value: "\(Int((engine.punch * 100).rounded()))%",
                      v: $engine.punch, range: 0...1, step: 0.05)
            SliderRow(title: L("eq.boost"), value: String(format: "+%.1f dB", engine.boost),
                      v: $engine.boost, range: 0...12)
            SliderRow(title: L("eq.preamp"), value: String(format: "%.1f dB", engine.preamp),
                      v: $engine.preamp, range: -12...0)
            SliderRow(title: L("eq.balance"), value: balanceText, v: $engine.balance, range: -1...1, step: 0.05)
            Toggle(isOn: $engine.leveling) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("eq.leveling")).font(.system(size: 15))
                    Note(text: L("eq.levelingHint"))
                }
            }
            .tint(accent)
        }
    }

    private var balanceText: String {
        let b = Int((engine.balance * 100).rounded())
        if b == 0 { return L("eq.center") }
        return b < 0 ? L("eq.left", -b) : L("eq.right", b)
    }

    // MARK: пресеты

    private var presetsCard: some View {
        Card {
            CardTitle(text: L("eq.presets"), systemImage: "square.stack.3d.up")
            FlowLayout(spacing: 8) {
                ForEach(0..<Presets.builtIn.count, id: \.self) { i in
                    let p = Presets.builtIn[i]
                    Chip(text: L(p.key), selected: engine.presetName == L(p.key)) {
                        engine.applyPreset(EQPreset(name: L(p.key), bands: 9, gains: p.gains, punch: p.punch,
                                                    preamp: p.preamp))
                    }
                }
                ForEach(userPresets) { p in
                    Chip(text: p.name, systemImage: "person.fill", selected: engine.presetName == p.name) {
                        engine.applyPreset(p)
                    }
                    .contextMenu {
                        Button {
                            sharing = p
                        } label: {
                            Label(L("eq.share"), systemImage: "qrcode")
                        }
                        Button(role: .destructive) {
                            userPresets.removeAll { $0.id == p.id }
                            Presets.saveUser(userPresets)
                        } label: {
                            Label(L("delete"), systemImage: "trash")
                        }
                    }
                }
            }
            if !userPresets.isEmpty {
                Note(text: L("eq.holdPreset"))
            }
            HStack(spacing: 10) {
                WideButton(text: L("eq.save"), systemImage: "plus") {
                    newName = engine.presetName.isEmpty ? L("eq.myPreset") : engine.presetName
                    saving = true
                }
                WideButton(text: L("eq.share"), systemImage: "qrcode", filled: false) {
                    sharing = engine.currentPreset(name: engine.presetName.isEmpty ? L("eq.custom") : engine.presetName)
                }
            }
            HStack(spacing: 10) {
                WideButton(text: L("eq.enterCode"), systemImage: "keyboard", filled: false) {
                    entering = true
                }
                WideButton(text: L("eq.reset"), systemImage: "arrow.counterclockwise", filled: false) {
                    engine.applyPreset(EQPreset(name: L("p.flat"), bands: engine.bandCount,
                                                gains: Array(repeating: 0, count: engine.bandCount)))
                }
            }
        }
    }

    private func save() {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        userPresets.removeAll { $0.name == name }
        userPresets.append(engine.currentPreset(name: name))
        Presets.saveUser(userPresets)
        engine.presetName = name
        Haptic.success()
    }
}

// MARK: поделиться: код, QR, ссылка

struct ShareSheet: View {
    let preset: EQPreset
    @Environment(\.dismiss) var dismiss
    @Environment(\.accent) var accent
    @State var copied = false

    var body: some View {
        let code = PresetCode.encode(preset)
        let link = PresetCode.url(code)
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Card {
                        Text(preset.name).font(.system(size: 20, weight: .bold))
                        EqGraph(gains: preset.gains, bands: preset.bands, compact: true)
                            .frame(height: 90)
                    }
                    if let img = QR.image(link) {
                        Image(uiImage: img)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .padding(16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                            .frame(maxWidth: 260)
                    }
                    Note(text: L("share.qrHint"))
                        .multilineTextAlignment(.center)
                    Text(code)
                        .font(.system(size: 16, weight: .semibold, design: .monospaced))
                        .textSelection(.enabled)
                        .multilineTextAlignment(.center)
                        .padding(14)
                        .frame(maxWidth: .infinity)
                        .background(Palette.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    WideButton(text: copied ? L("share.copied") : L("share.copy"), systemImage: "doc.on.doc") {
                        UIPasteboard.general.string = code
                        copied = true
                        Haptic.success()
                    }
                    ShareLink(item: URL(string: link)!, message: Text(L("share.message", preset.name, code))) {
                        Label(L("share.send"), systemImage: "square.and.arrow.up")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .foregroundStyle(.white)
                            .background(Palette.chip, in: Capsule())
                    }
                }
                .padding(16)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(L("share.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }
}

// MARK: ввести код или отсканировать QR

struct EnterCodeSheet: View {
    @Environment(\.dismiss) var dismiss
    @State var text = ""
    @State var scanning = false

    var body: some View {
        let found = PresetCode.decode(text)
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Card {
                        TextField(L("code.placeholder"), text: $text, axis: .vertical)
                            .font(.system(size: 17, design: .monospaced))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                        HStack(spacing: 10) {
                            WideButton(text: L("code.paste"), systemImage: "doc.on.clipboard", filled: false) {
                                text = UIPasteboard.general.string ?? ""
                            }
                            WideButton(text: L("code.scan"), systemImage: "qrcode.viewfinder", filled: false) {
                                scanning = true
                            }
                        }
                    }
                    if let p = found {
                        PresetPreview(preset: p) {
                            dismiss()
                        }
                    } else if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Note(text: L("code.bad"))
                    } else {
                        Note(text: L("code.hint"))
                    }
                }
                .padding(16)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(L("code.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { dismiss() }
                }
            }
            .fullScreenCover(isPresented: $scanning) {
                ScannerScreen { value in
                    text = value
                    scanning = false
                }
            }
        }
    }
}

/// Предпросмотр чужого пресета: кривая, параметры и «Применить».
struct PresetPreview: View {
    let preset: EQPreset
    var done: () -> Void

    var body: some View {
        Card {
            CardTitle(text: preset.name.isEmpty ? L("code.found") : preset.name, systemImage: "waveform.path")
            EqGraph(gains: preset.gains, bands: preset.bands)
                .frame(height: 180)
            Note(text: details)
            WideButton(text: L("apply"), systemImage: "checkmark") {
                var p = preset
                if p.name.isEmpty { p.name = L("code.found") }
                AudioEngine.shared.applyPreset(p)
                Haptic.success()
                done()
            }
        }
    }

    private var details: String {
        var parts = [L("code.bands", preset.bands)]
        if preset.punch > 0.01 { parts.append(L("eq.punch") + " \(Int((preset.punch * 100).rounded()))%") }
        if preset.boost > 0.01 { parts.append(L("eq.boost") + String(format: " +%.1f dB", preset.boost)) }
        if preset.preamp < -0.01 { parts.append(L("eq.preamp") + String(format: " %.1f dB", preset.preamp)) }
        if abs(preset.balance) > 0.01 { parts.append(L("eq.balance") + " \(Int((preset.balance * 100).rounded()))") }
        if preset.leveling { parts.append(L("eq.leveling")) }
        return parts.joined(separator: " · ")
    }
}

/// Пресет пришёл по ссылке eq://preset/… или из QR с камеры iPhone.
final class PresetInbox: ObservableObject {
    static let shared = PresetInbox()
    @Published var incoming: EQPreset?

    func receive(_ text: String) {
        if let p = PresetCode.decode(text) {
            incoming = p
        }
    }
}

struct IncomingSheet: View {
    let preset: EQPreset
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                PresetPreview(preset: preset) {
                    dismiss()
                }
                .padding(16)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(L("code.incoming"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
