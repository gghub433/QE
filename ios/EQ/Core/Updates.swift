import Foundation

/// Новая версия для iPhone: релизы GitHub с тегом ios-vX.Y.
/// iOS не даёт приложению поставить себя само — EQ показывает версию и открывает страницу с IPA.
final class Updates: ObservableObject {
    enum State {
        case idle, checking, upToDate, failed
        case available(String, URL)
    }

    static let shared = Updates()
    static let releases = URL(string: "https://github.com/gghub433/QE/releases")!
    static let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"

    @Published private(set) var state: State = .idle

    private init() {}

    func check() {
        if case .checking = state { return }
        state = .checking
        var req = URLRequest(url: URL(string: "https://api.github.com/repos/gghub433/QE/releases?per_page=40")!)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.timeoutInterval = 15
        URLSession.shared.dataTask(with: req) { data, _, _ in
            var result = State.failed
            if let data = data, let list = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] {
                result = .upToDate
                // релизы идут от новых к старым: первый ios-v* — самый свежий
                if let r = list.first(where: { ($0["tag_name"] as? String)?.hasPrefix("ios-v") == true }),
                   let tag = r["tag_name"] as? String {
                    let v = String(tag.dropFirst(5))
                    if Updates.newer(v, than: Updates.current) {
                        let page = URL(string: r["html_url"] as? String ?? "") ?? Updates.releases
                        result = .available(v, page)
                    }
                }
            }
            DispatchQueue.main.async {
                self.state = result
            }
        }.resume()
    }

    /// 1.10 новее 1.9.
    static func newer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }
}
