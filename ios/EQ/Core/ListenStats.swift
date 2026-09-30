import Foundation

/// «Время с музыкой»: сколько слушали сегодня, за неделю и кого — как на Android.
final class ListenStats: ObservableObject {
    static let shared = ListenStats()

    @Published private(set) var days: [String: Double] = [:]
    @Published private(set) var artists: [String: Double] = [:]

    private var unsaved = 0.0
    private static let fmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private init() {
        let d = UserDefaults.standard
        days = d.dictionary(forKey: "stats.days") as? [String: Double] ?? [:]
        artists = d.dictionary(forKey: "stats.artists") as? [String: Double] ?? [:]
    }

    static func key(_ date: Date) -> String {
        fmt.string(from: date)
    }

    func add(seconds: Double, artist: String) {
        if LaunchArgs.demo { return }
        days[ListenStats.key(Date()), default: 0] += seconds
        let a = artist.trimmingCharacters(in: .whitespaces)
        if !a.isEmpty {
            artists[a, default: 0] += seconds
        }
        unsaved += seconds
        if unsaved >= 10 {
            save()
        }
    }

    func save() {
        unsaved = 0
        // храним 60 дней
        let cutoff = ListenStats.key(Date().addingTimeInterval(-60 * 86400))
        days = days.filter { $0.key >= cutoff }
        UserDefaults.standard.set(days, forKey: "stats.days")
        UserDefaults.standard.set(artists, forKey: "stats.artists")
    }

    var today: Double {
        days[ListenStats.key(Date())] ?? 0
    }

    struct Day: Identifiable {
        let date: Date
        let seconds: Double
        var id: Date { date }
    }

    /// Последние 7 дней, от старого к сегодняшнему.
    var week: [Day] {
        let cal = Calendar.current
        let start = cal.startOfDay(for: Date())
        return (0..<7).reversed().map { back -> Day in
            let d = cal.date(byAdding: .day, value: -back, to: start) ?? start
            return Day(date: d, seconds: days[ListenStats.key(d)] ?? 0)
        }
    }

    var weekTotal: Double {
        week.reduce(0) { $0 + $1.seconds }
    }

    func top(_ n: Int) -> [(name: String, seconds: Double)] {
        artists.sorted { $0.value > $1.value }.prefix(n).map { ($0.key, $0.value) }
    }

    func reset() {
        days = [:]
        artists = [:]
        save()
    }

    func showDemo(days d: [String: Double], artists a: [String: Double]) {
        days = d
        artists = a
    }
}
