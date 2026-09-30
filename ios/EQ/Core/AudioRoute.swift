import AVFoundation
import UIKit

/// Куда сейчас идёт звук: AirPods, другие наушники, машина (CarPlay или Bluetooth магнитолы),
/// динамик iPhone, AirPlay. Плюс заряд самого iPhone.
final class AudioRoute: ObservableObject {
    enum Kind {
        case speaker, receiver, wired, airpods, airpodsPro, airpodsMax, beats, earbuds, headphones,
             bluetooth, car, airplay, usb, hdmi
    }

    static let shared = AudioRoute()

    @Published private(set) var name = ""
    @Published private(set) var kind: Kind = .speaker
    @Published private(set) var battery: Float = -1
    @Published private(set) var charging = false

    private init() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        update()
        updateBattery()
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.update()
        }
        nc.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.updateBattery()
        }
        nc.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.updateBattery()
        }
    }

    func update() {
        if LaunchArgs.demo { return }
        guard let out = AVAudioSession.sharedInstance().currentRoute.outputs.first else {
            set(name: DeviceModel.current.name, kind: .speaker)
            return
        }
        let n = out.portName
        switch out.portType {
        case .carAudio:
            set(name: n, kind: .car)
        case .bluetoothA2DP, .bluetoothLE, .bluetoothHFP:
            set(name: n, kind: AudioRoute.byName(n))
        case .headphones, .headsetMic, .lineOut:
            set(name: n, kind: .wired)
        case .builtInReceiver:
            set(name: DeviceModel.current.name, kind: .receiver)
        case .airPlay:
            set(name: n, kind: .airplay)
        case .usbAudio:
            set(name: n, kind: .usb)
        case .HDMI:
            set(name: n, kind: .hdmi)
        default:
            set(name: DeviceModel.current.name, kind: .speaker)
        }
    }

    private func set(name n: String, kind k: Kind) {
        name = n
        kind = k
        CarFocus.shared.attach(car: k == .car ? n : nil)
    }

    private func updateBattery() {
        let d = UIDevice.current
        battery = d.batteryLevel
        charging = d.batteryState == .charging || d.batteryState == .full
    }

    /// Тип Bluetooth-устройства по названию (как на Android).
    static func byName(_ raw: String) -> Kind {
        let n = raw.lowercased()
        func has(_ words: String...) -> Bool {
            words.contains { n.contains($0) }
        }
        if has("airpods max") { return .airpodsMax }
        if has("airpods pro") { return .airpodsPro }
        if has("airpods") { return .airpods }
        if has("beats", "powerbeats", "studio buds") { return .beats }
        if has("car", "auto", "toyota", "volkswagen", "vw ", "bmw", "audi", "ford", "honda", "kia", "hyundai",
               "mazda", "skoda", "škoda", "mercedes", "renault", "peugeot", "opel", "nissan", "volvo", "tesla",
               "lexus", "uconnect", "mylink", "sensus", "sync") {
            return .car
        }
        if has("buds", "pods", "tws", "earbud", "wf-", "liberty", "elite", "earfun", "soundpeats", "qcy",
               "enco", "nothing ear", "ear (", "motif", "tozo") {
            return .earbuds
        }
        if has("wh-", "headphone", "xm4", "xm5", "qc35", "qc45", "quietcomfort", "momentum", "life q",
               "space one", "px7", "studio", "solo", "major") {
            return .headphones
        }
        return .bluetooth
    }

    var symbol: String {
        AudioRoute.symbol(kind)
    }

    static func symbol(_ k: Kind) -> String {
        switch k {
        case .speaker, .receiver: return "iphone"
        case .wired, .headphones: return "headphones"
        case .airpods: return "airpods"
        case .airpodsPro: return "airpodspro"
        case .airpodsMax: return "airpodsmax"
        case .beats, .earbuds: return "earbuds"
        case .bluetooth: return "hifispeaker.fill"
        case .car: return "car.fill"
        case .airplay: return "airplayaudio"
        case .usb: return "cable.connector"
        case .hdmi: return "tv"
        }
    }

    var kindText: String {
        switch kind {
        case .speaker: return L("route.speaker")
        case .receiver: return L("route.receiver")
        case .wired: return L("route.wired")
        case .airpods, .airpodsPro, .airpodsMax, .beats: return L("route.apple")
        case .earbuds: return L("route.earbuds")
        case .headphones: return L("route.headphones")
        case .bluetooth: return L("route.bluetooth")
        case .car: return L("route.car")
        case .airplay: return L("route.airplay")
        case .usb: return L("route.usb")
        case .hdmi: return L("route.hdmi")
        }
    }

    var isHeadphones: Bool {
        switch kind {
        case .wired, .airpods, .airpodsPro, .airpodsMax, .beats, .earbuds, .headphones: return true
        default: return false
        }
    }

    var isApple: Bool {
        kind == .airpods || kind == .airpodsPro || kind == .airpodsMax || kind == .beats
    }

    func showDemo(name n: String, kind k: Kind, battery b: Float) {
        set(name: n, kind: k)
        battery = b
        charging = false
    }
}
