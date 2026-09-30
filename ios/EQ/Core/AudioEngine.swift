import AVFoundation
import AudioToolbox
import MediaPlayer
import UIKit

/// Плеер EQ со звуком: AVAudioEngine → эквалайзер (9/15/31 полоса) → компрессор (панч, выравнивание)
/// → лимитер (усиление без хрипа) → баланс. iOS разрешает обрабатывать только звук своего приложения,
/// поэтому эквалайзер работает для музыки, которую играет EQ.
final class AudioEngine: ObservableObject {
    static let shared = AudioEngine()

    @Published private(set) var bandCount = 9
    @Published var gains: [Float] = Array(repeating: 0, count: 9) { didSet { changed() } }
    @Published var preamp: Float = 0 { didSet { changed() } }        // -12 … 0 дБ
    @Published var punch: Float = 0 { didSet { changed() } }         // 0 … 1
    @Published var boost: Float = 0 { didSet { changed() } }         // 0 … 12 дБ
    @Published var balance: Float = 0 { didSet { changed() } }       // -1 … 1
    @Published var leveling = false { didSet { changed() } }
    @Published var enabled = true { didSet { changed() } }
    @Published var abBypass = false { didSet { apply() } }          // A/B: пока держат кнопку
    @Published var carBalance: Float = 0 { didSet { apply() } }     // фокус машины, не сохраняется
    @Published var presetName = ""

    @Published private(set) var current: Track?
    @Published private(set) var isPlaying = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var sleepEnd: Date?

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var eq = AVAudioUnitEQ(numberOfBands: 9)
    private let dynamics = AVAudioUnitEffect(audioComponentDescription: AudioEngine.desc(kAudioUnitSubType_DynamicsProcessor))
    private let limiter = AVAudioUnitEffect(audioComponentDescription: AudioEngine.desc(kAudioUnitSubType_PeakLimiter))
    private let out = AVAudioMixerNode()
    private var file: AVAudioFile?
    private var seekFrame: AVAudioFramePosition = 0
    private var token = 0
    private var queue: [Track] = []
    private var timer: Timer?
    private var loading = false
    private var saveWork: DispatchWorkItem?

    private static func desc(_ sub: OSType) -> AudioComponentDescription {
        AudioComponentDescription(componentType: kAudioUnitType_Effect, componentSubType: sub,
                                  componentManufacturer: kAudioUnitManufacturer_Apple,
                                  componentFlags: 0, componentFlagsMask: 0)
    }

    private init() {
        loading = true
        load()
        loading = false
        engine.attach(player)
        engine.attach(eq)
        engine.attach(dynamics)
        engine.attach(limiter)
        engine.attach(out)
        connect(format: nil)
        setupSession()
        setupRemote()
        apply()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    // MARK: цепочка

    private func connect(format: AVAudioFormat?) {
        engine.connect(player, to: eq, format: format)
        engine.connect(eq, to: dynamics, format: format)
        engine.connect(dynamics, to: limiter, format: format)
        engine.connect(limiter, to: out, format: format)
        engine.connect(out, to: engine.mainMixerNode, format: format)
    }

    private func changed() {
        if loading { return }
        apply()
        scheduleSave()
    }

    /// Все настройки → в узлы звука.
    func apply() {
        let freqs = Presets.freqs(bandCount)
        let width: Float = bandCount == 9 ? 1.0 : bandCount == 15 ? 0.66 : 0.33
        let bypass = !enabled || abBypass
        for (i, f) in freqs.enumerated() where i < eq.bands.count {
            let b = eq.bands[i]
            b.filterType = .parametric
            b.frequency = f
            b.bandwidth = width
            var g: Float = i < gains.count ? gains[i] : 0
            if f <= 125 { g += punch * 4 }      // панч: плотнее низ
            b.gain = max(-24, min(24, g))
            b.bypass = false
        }
        eq.bypass = bypass
        // A/B: оригинал той же громкости — честно сравниваем звук, а не громкость
        eq.globalGain = bypass ? 0 : preamp
        let compress = !bypass && (leveling || punch > 0.01)
        dynamics.bypass = !compress
        if compress {
            set(dynamics, kDynamicsProcessorParam_Threshold, leveling ? -30 : -14 - 10 * punch)
            set(dynamics, kDynamicsProcessorParam_HeadRoom, leveling ? 6 : 10 - 5 * punch)
            set(dynamics, kDynamicsProcessorParam_AttackTime, 0.004)
            set(dynamics, kDynamicsProcessorParam_ReleaseTime, 0.12)
            set(dynamics, 6, leveling ? 6 : 3 * punch)   // общее усиление (OverallGain)
        }
        set(limiter, kLimiterParam_PreGain, bypass ? abCompensation() : boost)
        out.pan = max(-1, min(1, balance + carBalance))
    }

    /// Номер параметра в разных SDK приходит то как Int, то как UInt32 — принимаем любой.
    private func set<P: BinaryInteger>(_ unit: AVAudioUnitEffect, _ param: P, _ value: Float) {
        AudioUnitSetParameter(unit.audioUnit, AudioUnitParameterID(param), kAudioUnitScope_Global, 0,
                              AudioUnitParameterValue(value), 0)
    }

    /// Средний подъём кривой в слышимой середине (60 Гц … 10 кГц).
    private func abCompensation() -> Float {
        let freqs = Presets.freqs(bandCount)
        var sum: Float = 0, n: Float = 0
        for (i, f) in freqs.enumerated() where f >= 60 && f <= 10000 && i < gains.count {
            sum += gains[i]
            n += 1
        }
        let mean = n > 0 ? sum / n : 0
        return max(0, min(12, mean + preamp + boost))
    }

    func setBandCount(_ n: Int) {
        guard n != bandCount, n == 9 || n == 15 || n == 31 else { return }
        let newGains = Presets.resample(gains, from: Presets.freqs(bandCount), to: Presets.freqs(n))
        let wasPlaying = isPlaying, pos = position
        token += 1
        player.stop()
        engine.stop()
        engine.detach(eq)
        eq = AVAudioUnitEQ(numberOfBands: n)
        engine.attach(eq)
        loading = true
        bandCount = n
        gains = newGains
        loading = false
        connect(format: file?.processingFormat)
        apply()
        scheduleSave()
        if wasPlaying { startPlayback(from: pos) }
    }

    func applyPreset(_ p: EQPreset) {
        loading = true
        if p.bands != bandCount {
            gains = Presets.resample(p.gains, from: Presets.freqs(p.bands), to: Presets.freqs(bandCount))
        } else {
            gains = p.gains
        }
        preamp = p.preamp
        punch = p.punch
        boost = p.boost
        balance = p.balance
        leveling = p.leveling
        loading = false
        presetName = p.name
        apply()
        scheduleSave()
    }

    func currentPreset(name: String) -> EQPreset {
        EQPreset(name: name, bands: bandCount, gains: gains, punch: punch, boost: boost,
                 balance: balance, preamp: preamp, leveling: leveling)
    }

    // MARK: воспроизведение

    func play(_ track: Track, in list: [Track]) {
        queue = list
        guard let f = try? AVAudioFile(forReading: track.url) else { return }
        file = f
        current = track
        duration = Double(f.length) / f.processingFormat.sampleRate
        startPlayback(from: 0)
    }

    private func startPlayback(from seconds: Double) {
        guard let f = file else { return }
        token += 1
        let t = token
        player.stop()
        engine.stop()
        connect(format: f.processingFormat)
        apply()
        try? AVAudioSession.sharedInstance().setActive(true)
        do {
            try engine.start()
        } catch {
            return
        }
        let sr = f.processingFormat.sampleRate
        let start = AVAudioFramePosition(max(0, min(seconds, duration)) * sr)
        seekFrame = start
        let left = f.length - start
        if left <= 0 {
            next()
            return
        }
        player.scheduleSegment(f, startingFrame: start, frameCount: AVAudioFrameCount(left), at: nil,
                               completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                if self?.token == t { self?.next() }
            }
        }
        player.play()
        position = seconds
        isPlaying = true
        updateNowPlaying()
    }

    func togglePlay() {
        isPlaying ? pause() : resume()
    }

    func pause() {
        guard isPlaying else { return }
        player.pause()
        engine.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func resume() {
        guard !isPlaying else { return }
        if file == nil {
            if let first = queue.first ?? Library.shared.tracks.first {
                play(first, in: queue.isEmpty ? Library.shared.tracks : queue)
            }
            return
        }
        try? AVAudioSession.sharedInstance().setActive(true)
        try? engine.start()
        player.play()
        isPlaying = true
        updateNowPlaying()
    }

    func seek(to seconds: Double) {
        guard file != nil else { return }
        let wasPlaying = isPlaying
        startPlayback(from: seconds)
        if !wasPlaying { pause() }
    }

    func next() {
        guard let cur = current, let i = queue.firstIndex(where: { $0.id == cur.id }), i + 1 < queue.count else {
            token += 1
            player.stop()
            isPlaying = false
            position = 0
            updateNowPlaying()
            return
        }
        play(queue[i + 1], in: queue)
    }

    func previous() {
        if position > 3 {
            seek(to: 0)
            return
        }
        guard let cur = current, let i = queue.firstIndex(where: { $0.id == cur.id }), i > 0 else {
            seek(to: 0)
            return
        }
        play(queue[i - 1], in: queue)
    }

    private func tick() {
        sleepTick()
        guard isPlaying, let f = file, let nt = player.lastRenderTime, let pt = player.playerTime(forNodeTime: nt) else { return }
        position = min(duration, Double(seekFrame + pt.sampleTime) / f.processingFormat.sampleRate)
        ListenStats.shared.add(seconds: 0.5, artist: current?.artist ?? "")
    }

    // MARK: таймер сна: за минуту до конца музыка плавно затихает и встаёт на паузу

    func setSleep(minutes: Int) {
        engine.mainMixerNode.outputVolume = 1
        sleepEnd = minutes > 0 ? Date().addingTimeInterval(Double(minutes) * 60) : nil
    }

    private func sleepTick() {
        guard let end = sleepEnd else { return }
        let left = end.timeIntervalSinceNow
        if left <= 0 {
            pause()
            sleepEnd = nil
            engine.mainMixerNode.outputVolume = 1
        } else if left < 60 {
            let k = Float(left / 60)
            engine.mainMixerNode.outputVolume = k * k
        }
    }

    // MARK: система: фоновый звук, звонки, экран блокировки

    private func setupSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                               queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            if type == .began { self?.pause() }
        }
    }

    private func setupRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in
            self?.resume()
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlay()
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in
            self?.next()
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            self?.previous()
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent {
                self?.seek(to: e.positionTime)
            }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let t = current else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if let data = t.artwork, let img = UIImage(data: data) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: сохранение настроек звука

    private struct Saved: Codable {
        var bands: Int
        var gains: [Float]
        var preamp: Float
        var punch: Float
        var boost: Float
        var balance: Float
        var leveling: Bool
        var enabled: Bool
        var presetName: String
    }

    private func scheduleSave() {
        saveWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: w)
    }

    private func saveNow() {
        let s = Saved(bands: bandCount, gains: gains, preamp: preamp, punch: punch, boost: boost,
                      balance: balance, leveling: leveling, enabled: enabled, presetName: presetName)
        if let data = try? JSONEncoder().encode(s) {
            UserDefaults.standard.set(data, forKey: "eq.state")
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: "eq.state"),
              let s = try? JSONDecoder().decode(Saved.self, from: data) else { return }
        let n = (s.bands == 15 || s.bands == 31) ? s.bands : 9
        bandCount = n
        eq = AVAudioUnitEQ(numberOfBands: n)
        gains = s.gains.count == n ? s.gains : Array(repeating: 0, count: n)
        preamp = s.preamp
        punch = s.punch
        boost = s.boost
        balance = s.balance
        leveling = s.leveling
        enabled = s.enabled
        presetName = s.presetName
    }

    // MARK: демо для скриншотов

    func showDemo(_ track: Track, position pos: Double, duration dur: Double) {
        current = track
        position = pos
        duration = dur
        isPlaying = true
    }
}
