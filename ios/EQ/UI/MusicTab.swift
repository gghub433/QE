import Charts
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Вкладка «Музыка»: сейчас играет (обложка, волна цвета обложки), моя музыка, таймер сна, время с музыкой.
struct MusicTab: View {
    @ObservedObject private var engine = AudioEngine.shared
    @ObservedObject private var library = Library.shared
    @ObservedObject private var stats = ListenStats.shared
    @Environment(\.accent) var accent
    @State var importing = false
    @State var scrub: Double? = nil

    var body: some View {
        TabPage(title: L("tab.music")) {
            nowPlaying
            libraryCard
            timeCard
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result {
                library.importFiles(urls)
            }
        }
    }

    // MARK: сейчас играет

    private var nowPlaying: some View {
        Card(padding: 20) {
            HStack(spacing: 16) {
                ArtworkView(track: engine.current, size: 104, radius: 20)
                VStack(alignment: .leading, spacing: 4) {
                    Text(engine.current?.title ?? L("music.nothing"))
                        .font(.system(size: 20, weight: .bold))
                        .lineLimit(2)
                    Text(engine.current.map { $0.artist.isEmpty ? L("music.unknownArtist") : $0.artist } ?? L("music.addHint"))
                        .font(.system(size: 15))
                        .foregroundStyle(Palette.grey)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            WaveView(playing: engine.isPlaying, color: accent)
                .frame(height: 44)
            VStack(spacing: 2) {
                Slider(value: Binding(get: { scrub ?? engine.position },
                                      set: { scrub = $0 }),
                       in: 0...max(1, engine.duration)) { editing in
                    if !editing, let s = scrub {
                        engine.seek(to: s)
                        scrub = nil
                    }
                }
                .tint(accent)
                HStack {
                    Text(clockText(scrub ?? engine.position))
                    Spacer()
                    Text("-" + clockText(engine.duration - (scrub ?? engine.position)))
                }
                .font(.system(size: 12, weight: .medium).monospacedDigit())
                .foregroundStyle(Palette.grey)
            }
            HStack {
                sleepMenu
                Spacer()
                Button {
                    engine.previous()
                } label: {
                    Image(systemName: "backward.fill").font(.system(size: 26))
                }
                Spacer()
                Button {
                    Haptic.tap()
                    engine.togglePlay()
                } label: {
                    Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(textOn(accent))
                        .frame(width: 72, height: 72)
                        .background(accent, in: Circle())
                }
                Spacer()
                Button {
                    engine.next()
                } label: {
                    Image(systemName: "forward.fill").font(.system(size: 26))
                }
                Spacer()
                RoutePicker(tint: accent)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .foregroundStyle(.white)
            if let end = engine.sleepEnd {
                HStack(spacing: 6) {
                    Image(systemName: "moon.fill")
                    Text(L("sleep.until", end.formatted(date: .omitted, time: .shortened)))
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(accent)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var sleepMenu: some View {
        Menu {
            ForEach([15, 30, 45, 60, 90], id: \.self) { m in
                Button(L("sleep.minutes", m)) {
                    engine.setSleep(minutes: m)
                }
            }
            if engine.sleepEnd != nil {
                Button(L("sleep.off"), role: .destructive) {
                    engine.setSleep(minutes: 0)
                }
            }
        } label: {
            Image(systemName: engine.sleepEnd == nil ? "moon" : "moon.fill")
                .font(.system(size: 20))
                .foregroundStyle(engine.sleepEnd == nil ? Color.white : accent)
                .frame(width: 40, height: 40)
        }
    }

    // MARK: моя музыка

    private var libraryCard: some View {
        Card {
            HStack {
                CardTitle(text: L("music.library"), systemImage: "music.note.list")
                Spacer()
                Button {
                    importing = true
                } label: {
                    Label(L("music.add"), systemImage: "plus")
                        .font(.system(size: 15, weight: .semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(textOn(accent))
                        .background(accent, in: Capsule())
                }
                .buttonStyle(.plain)
            }
            if library.tracks.isEmpty {
                Note(text: L("music.empty"))
            } else {
                VStack(spacing: 0) {
                    ForEach(library.tracks) { t in
                        TrackRow(track: t, current: engine.current?.id == t.id, playing: engine.isPlaying)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                Haptic.tap()
                                engine.play(t, in: library.tracks)
                            }
                            .contextMenu {
                                Button(role: .destructive) {
                                    library.delete(t)
                                } label: {
                                    Label(L("delete"), systemImage: "trash")
                                }
                            }
                        if t.id != library.tracks.last?.id {
                            Divider().overlay(Color.white.opacity(0.06)).padding(.leading, 60)
                        }
                    }
                }
            }
        }
    }

    // MARK: время с музыкой

    private var timeCard: some View {
        Card {
            CardTitle(text: L("mt.title"), systemImage: "clock")
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(durationText(stats.today))
                        .font(.system(size: 28, weight: .bold))
                    Text(L("mt.today")).font(.system(size: 13)).foregroundStyle(Palette.grey)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(durationText(stats.weekTotal))
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(Palette.grey)
                    Text(L("mt.week")).font(.system(size: 13)).foregroundStyle(Palette.grey)
                }
            }
            Chart {
                ForEach(stats.week) { d in
                    BarMark(x: .value("day", d.date, unit: .day), y: .value("min", d.seconds / 60))
                        .foregroundStyle(Calendar.current.isDateInToday(d.date) ? accent : accent.opacity(0.45))
                        .cornerRadius(6)
                }
            }
            .chartXAxis {
                AxisMarks(values: .stride(by: .day)) { _ in
                    AxisValueLabel(format: .dateTime.weekday(.narrow))
                }
            }
            .chartYAxis(.hidden)
            .frame(height: 130)
            let top = stats.top(3)
            if !top.isEmpty {
                Text(L("mt.top")).font(.system(size: 15, weight: .semibold)).padding(.top, 4)
                ForEach(0..<top.count, id: \.self) { i in
                    HStack {
                        Text("\(i + 1)").font(.system(size: 15, weight: .bold)).foregroundStyle(accent).frame(width: 22)
                        Text(top[i].name).font(.system(size: 15)).lineLimit(1)
                        Spacer()
                        Text(durationText(top[i].seconds)).font(.system(size: 14).monospacedDigit()).foregroundStyle(Palette.grey)
                    }
                }
            }
        }
    }
}

/// Обложка трека или заглушка с нотой.
struct ArtworkView: View {
    let track: Track?
    var size: CGFloat
    var radius: CGFloat
    @Environment(\.accent) var accent

    var body: some View {
        Group {
            if let data = track?.artwork, let img = UIImage(data: data) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                ZStack {
                    LinearGradient(colors: [accent.opacity(0.55), Palette.chip], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Image(systemName: "music.note")
                        .font(.system(size: size * 0.38, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.85))
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

struct TrackRow: View {
    let track: Track
    let current: Bool
    let playing: Bool
    @Environment(\.accent) var accent

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(track: track, size: 48, radius: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(.system(size: 16, weight: current ? .semibold : .regular))
                    .foregroundStyle(current ? accent : .white)
                    .lineLimit(1)
                Text(track.artist.isEmpty ? L("music.unknownArtist") : track.artist)
                    .font(.system(size: 13))
                    .foregroundStyle(Palette.grey)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if current && playing {
                Image(systemName: "speaker.wave.2.fill")
                    .foregroundStyle(accent)
            } else if track.length > 0 {
                Text(clockText(track.length))
                    .font(.system(size: 13).monospacedDigit())
                    .foregroundStyle(Palette.grey)
            }
        }
        .padding(.vertical, 8)
    }
}
