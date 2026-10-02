import Foundation
import Security

// API keys and tokens the viewer enters in Settings. They live in the macOS Keychain, never in
// the app's code or preferences, and are only sent to the service they belong to.

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

    /// Fills TMDB and CoinMarketCap when the Keychain doesn't have them yet, so those
    /// screens work on a new install. A key you type in Settings is left alone.
    static func seedBundled() {
        let bundled: [(SecretKey, String)] = [
            (.tmdb, "94cf789639ae0b2e06c65a9f2ccad10a"),
            (.coinMarketCap, "0b906811cf3e4ec39bef56e2e69683a7"),
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

/// Generic-password Keychain helpers.
enum Keychain {
    static let appService = "app.chudstreams.mac"
    /// This session's values, so the app keeps working if the Keychain refuses a write
    /// (for example on a build machine with a locked keychain).
    private static var session: [String: String] = [:]
    private static let lock = NSLock()

    private static func sessionKey(_ service: String, _ account: String) -> String { service + "|" + account }

    static func write(_ value: String, service: String = appService, account: String) {
        lock.lock()
        session[sessionKey(service, account)] = value
        lock.unlock()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }

    static func read(service: String = appService, account: String) -> String? {
        lock.lock()
        let remembered = session[sessionKey(service, account)]
        lock.unlock()
        if let remembered { return remembered }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func remove(service: String = appService, account: String) {
        lock.lock()
        session[sessionKey(service, account)] = nil
        lock.unlock()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
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
