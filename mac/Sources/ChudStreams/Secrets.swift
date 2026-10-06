import Foundation

// API keys and tokens. They are kept in a file inside this app's own folder, not the macOS
// login keychain, so opening the app never asks for the Mac login password.

enum SecretKey: String, CaseIterable, Identifiable {
    case claude
    case tmdb
    case trakt
    case openSubtitles
    case openSubtitlesUser
    case openSubtitlesPassword
    case github
    case telegramBot
    case coinMarketCap
    case gemini
    case youtube
    case realDebrid
    case torbox
    case apiSports
    case subSource

    var id: String { rawValue }

    var label: String {
        switch self {
        case .claude: return "Anthropic API key"
        case .tmdb: return "TMDB API key"
        case .trakt: return "Trakt client ID"
        case .openSubtitles: return "OpenSubtitles API key"
        case .openSubtitlesUser: return "OpenSubtitles username"
        case .openSubtitlesPassword: return "OpenSubtitles password"
        case .github: return "GitHub token"
        case .telegramBot: return "Telegram bot token"
        case .coinMarketCap: return "CoinMarketCap API key"
        case .gemini: return "Gemini API key"
        case .youtube: return "YouTube API key"
        case .realDebrid: return "Real-Debrid token"
        case .torbox: return "TorBox API key"
        case .apiSports: return "API-Sports key"
        case .subSource: return "SubSource API key"
        }
    }
}

enum Secrets {
    private static let service = "app.chudstreams.mac.keys"
    private static var cache: [SecretKey: String] = [:]
    private static let lock = NSLock()

    static func get(_ key: SecretKey) -> String? {
        if let test = ServiceURL.testKey(key) { return test }
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[key] { return cached.isEmpty ? nil : cached }
        let value = Keychain.read(service: service, account: key.rawValue) ?? ""
        cache[key] = value
        return value.isEmpty ? nil : value
    }

    static func has(_ key: SecretKey) -> Bool { get(key) != nil }

    /// Saves (or with nil / empty, removes) a key.
    static func set(_ key: SecretKey, _ value: String?) {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        lock.lock()
        defer { lock.unlock() }
        cache[key] = trimmed
        if trimmed.isEmpty {
            Keychain.remove(service: service, account: key.rawValue)
        } else {
            Keychain.write(trimmed, service: service, account: key.rawValue)
        }
    }

    /// Fills the built-in keys when this Mac doesn't have them yet. A key you type in Settings is left alone.
    static func seedBundled() {
        let bundled: [(SecretKey, String)] = [
            (.tmdb, "94cf789639ae0b2e06c65a9f2ccad10a"),
            (.coinMarketCap, "0b906811cf3e4ec39bef56e2e69683a7"),
            (.gemini, "AIzaSyARPATBAMSGUFQ-I65s2rDv28XTSwIEVZQ"),
            (.youtube, "AIzaSyBYe6YBEM29lRXUoOd2MdtkIEWhiU6cQ48"),
            (.realDebrid, "JH4W4WZ3FAKRMGDM7WHOKVDM4EFXIQZFTIUIZAOD326JEGQKZPHA"),
            (.torbox, "0cc19b5a-61d0-4d08-811c-32cae57ffbdc"),
            (.openSubtitles, "yvDf7waNYglVo56QfmOsW6BvNXEWm6am"),
            (.apiSports, "5b5223cffa017ab9e7da79cd28fc1a8b"),
        ]
        for (key, value) in bundled where !has(key) {
            set(key, value)
        }
    }

    /// Every stored secret, for scrubbing them out of crash and error reports.
    static func allValues() -> [String] {
        SecretKey.allCases.compactMap { get($0) }.filter { $0.count >= 6 }
    }
}

/// Logins and API keys. They are kept in a file inside this app's own folder, not the macOS
/// login keychain, so opening the app never asks for the Mac login password.
enum Keychain {
    static let appService = "app.chudstreams.mac"
    private static var memory: [String: String] = [:]
    private static var loaded = false
    private static let lock = NSLock()

    private static func key(_ service: String, _ account: String) -> String { service + "\n" + account }

    private static var fileURL: URL {
        AppSupport.directory.appendingPathComponent("logins.plist")
    }

    private static func ensureLoaded() {
        guard !loaded else { return }
        loaded = true
        guard let data = try? Data(contentsOf: fileURL),
              let saved = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: String] else { return }
        memory = saved
    }

    private static func persist() {
        guard let data = try? PropertyListSerialization.data(fromPropertyList: memory, format: .binary, options: 0) else { return }
        try? data.write(to: fileURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }

    static func write(_ value: String, service: String = appService, account: String) {
        lock.lock()
        ensureLoaded()
        memory[key(service, account)] = value
        persist()
        lock.unlock()
    }

    static func read(service: String = appService, account: String) -> String? {
        lock.lock()
        ensureLoaded()
        let value = memory[key(service, account)]
        lock.unlock()
        guard let value, !value.isEmpty else { return nil }
        return value
    }

    static func remove(service: String = appService, account: String) {
        lock.lock()
        ensureLoaded()
        memory.removeValue(forKey: key(service, account))
        persist()
        lock.unlock()
    }

    // The original single-account names, kept so an existing sign-in carries over.
    static func save(_ password: String, account: String) { write(password, account: account) }
    static func load(account: String) -> String? { read(account: account) }
    static func delete(account: String) { remove(account: account) }
}

/// Where each outside service lives. The GitHub build points these at a local test server
/// (environment variables), so every screen can be checked without real accounts.
enum ServiceURL {
    private static func override(_ name: String) -> String? {
        ProcessInfo.processInfo.environment[name].flatMap { $0.isEmpty ? nil : $0 }
    }

    static var tmdb: String { override("CHUD_TMDB_API") ?? "https://api.themoviedb.org/3" }
    static var tmdbImages: String { override("CHUD_TMDB_IMAGES") ?? "https://image.tmdb.org/t/p" }
    static var trakt: String { override("CHUD_TRAKT_API") ?? "https://api.trakt.tv" }
    static var openSubtitles: String { override("CHUD_OPENSUBS_API") ?? "https://api.opensubtitles.com/api/v1" }
    static var claude: String { override("CHUD_CLAUDE_API") ?? "https://api.anthropic.com" }
    static var github: String { override("CHUD_GITHUB_API") ?? "https://api.github.com" }
    static var telegram: String { override("CHUD_TELEGRAM_API") ?? "https://api.telegram.org" }

    /// Test builds may pass keys this way; a real install never sets these.
    static func testKey(_ key: SecretKey) -> String? {
        override("CHUD_TEST_KEY_" + key.rawValue.uppercased())
    }
}
