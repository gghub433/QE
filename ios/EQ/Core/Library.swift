import AVFoundation
import Foundation

/// Трек из папки EQ («Файлы» → «На iPhone» → EQ) или добавленный кнопкой «Добавить».
struct Track: Identifiable, Equatable {
    let id: String
    let url: URL
    var title: String
    var artist: String
    var artwork: Data?
    var length: Double = 0
}

/// Своя музыка EQ. Эквалайзер iOS разрешает только для звука своего приложения,
/// поэтому EQ играет файлы сам: mp3, m4a, aac, wav, aiff, flac, alac.
/// Меняется только на главном потоке (теги читаются в фоне, результат — через main).
final class Library: ObservableObject, @unchecked Sendable {
    static let shared = Library()
    static let extensions: Set<String> = ["mp3", "m4a", "aac", "wav", "aif", "aiff", "aifc", "caf", "flac", "mp4", "m4b"]

    @Published private(set) var tracks: [Track] = []

    private var meta: [String: Track] = [:]
    private var loading: Set<String> = []

    var folder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private init() {
        if !LaunchArgs.demo {
            reload()
        }
    }

    /// Перечитать папку: файлы могли добавить через «Файлы» или Finder.
    func reload() {
        if LaunchArgs.demo { return }
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil,
                                                                   options: [.skipsHiddenFiles])) ?? []
        let audio = files
            .filter { Library.extensions.contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
        tracks = audio.map { url in
            let id = url.lastPathComponent
            return meta[id] ?? Track(id: id, url: url, title: url.deletingPathExtension().lastPathComponent, artist: "")
        }
        for t in tracks where meta[t.id] == nil && !loading.contains(t.id) {
            loadMeta(t)
        }
    }

    /// Название, исполнитель и обложка из тегов файла.
    private func loadMeta(_ t: Track) {
        loading.insert(t.id)
        let asset = AVURLAsset(url: t.url)
        Task {
            var title = t.title, artist = t.artist
            var art: Data?
            var length = 0.0
            if let items = try? await asset.load(.commonMetadata) {
                for item in items {
                    guard let key = item.commonKey else { continue }
                    if key == .commonKeyTitle, let v = try? await item.load(.stringValue), !v.isEmpty {
                        title = v
                    } else if key == .commonKeyArtist, let v = try? await item.load(.stringValue), !v.isEmpty {
                        artist = v
                    } else if key == .commonKeyArtwork, art == nil {
                        art = try? await item.load(.dataValue)
                    }
                }
            }
            if let d = try? await asset.load(.duration), d.seconds.isFinite {
                length = d.seconds
            }
            let done = Track(id: t.id, url: t.url, title: title, artist: artist, artwork: art, length: length)
            DispatchQueue.main.async {
                self.meta[done.id] = done
                self.loading.remove(done.id)
                if let i = self.tracks.firstIndex(where: { $0.id == done.id }) {
                    self.tracks[i] = done
                }
            }
        }
    }

    /// Скопировать выбранные файлы в папку EQ.
    func importFiles(_ urls: [URL]) {
        let fm = FileManager.default
        for src in urls {
            let access = src.startAccessingSecurityScopedResource()
            defer {
                if access { src.stopAccessingSecurityScopedResource() }
            }
            let base = src.deletingPathExtension().lastPathComponent
            var dst = folder.appendingPathComponent(src.lastPathComponent)
            var n = 2
            while fm.fileExists(atPath: dst.path) {
                dst = folder.appendingPathComponent("\(base) \(n).\(src.pathExtension)")
                n += 1
            }
            try? fm.copyItem(at: src, to: dst)
        }
        reload()
    }

    func delete(_ t: Track) {
        if AudioEngine.shared.current?.id == t.id {
            AudioEngine.shared.pause()
        }
        try? FileManager.default.removeItem(at: t.url)
        meta[t.id] = nil
        reload()
    }

    /// Демо для скриншотов: треки без файлов.
    func showDemo(_ list: [Track]) {
        tracks = list
    }
}
