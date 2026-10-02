import Foundation

// Xtream Codes API client (player_api.php). Everything is decoded leniently because panels
// disagree on whether numbers arrive as numbers or strings, and some omit fields entirely.

struct XtreamCredentials: Codable, Hashable {
    var server: String
    var username: String
    var password: String
}

/// Why a request to the provider failed. Each case says what actually happened, so a sign-in
/// problem can be told apart from a wrong address, a blocked connection or an offline Mac.
enum XtreamError: LocalizedError {
    case badServer
    case missingLogin
    case rejected
    case inactive(String)
    case noInternet
    case hostNotFound(String)
    case refused(String)
    case timedOut(String)
    case secureConnection(String)
    case forbidden(String)
    case httpStatus(String, Int)
    case notXtream(String)
    case other(String, String)

    var errorDescription: String? {
        switch self {
        case .badServer:
            return "Enter the server address from your provider."
        case .missingLogin:
            return "Enter both your username and password."
        case .rejected:
            return "The server didn't accept that username and password."
        case .inactive(let status):
            return "This account is marked \"\(status)\". Contact your provider to renew it."
        case .noInternet:
            return "This Mac isn't connected to the internet. Check your Wi-Fi, then try again."
        case .hostNotFound(let host):
            return "Couldn't find a server called \(host). Check the spelling of the address."
        case .refused(let host):
            return "\(host) isn't accepting connections on that port. Check the port number (often 8080 or 80)."
        case .timedOut(let host):
            return "\(host) didn't answer in time. Check the port number, or try again in a moment."
        case .secureConnection(let host):
            return "Couldn't make a secure connection to \(host). Try the address with http:// instead of https://."
        case .forbidden(let host):
            return "\(host) refused the connection (error 403). Your provider may be blocking this app or your network; check with them."
        case .httpStatus(let host, let code):
            return "\(host) answered with error \(code). Check the address, or try again later."
        case .notXtream(let host):
            return "\(host) answered, but not with Xtream account data. Use the base address your provider gave you, like http://example.com:8080."
        case .other(let host, let detail):
            return "Couldn't connect to \(host): \(detail)"
        }
    }
}

struct AccountInfo {
    var status: String?
    var expiry: Date?
    var activeConnections: Int?
    var maxConnections: Int?
    var isTrial: Bool
    var allowedFormats: [String]

    var isActive: Bool { status == nil || status?.lowercased() == "active" }
}

enum ContentKind: String, Codable, Hashable {
    case live, movie, series
}

struct ContentCategory: Identifiable, Hashable {
    let id: String
    let name: String
}

/// A live channel, film or series from one of the viewer's sources (an Xtream account or an M3U
/// playlist). Stored in favourites, the library and history, so new fields must stay optional.
struct MediaItem: Identifiable, Hashable, Codable {
    let kind: ContentKind
    let streamId: Int
    let name: String
    let icon: String?
    let categoryId: String?
    let containerExtension: String?
    let rating: String?
    /// Which source this came from; nil means the first (original) Xtream account.
    var sourceId: String? = nil
    /// The stream address, for M3U entries (Xtream addresses are built from the login).
    var url: String? = nil
    /// XMLTV channel id (tvg-id / epg_channel_id), for guide listings.
    var epgId: String? = nil
    /// The provider's channel number, if it gives one.
    var number: Int? = nil
    /// When the provider added it (Unix time), for "Recently added".
    var added: Double? = nil
    /// The provider keeps catch-up recordings for this channel.
    var hasArchive: Bool? = nil

    var source: String { sourceId ?? Source.mainId }
    var id: String { "\(source)|\(kind.rawValue)-\(streamId)" }

    /// Title without the provider's decorations ("EN | ", "[4K]", "(2021)"), for matching and search.
    var cleanName: String { MediaItem.clean(name) }

    /// A year in the title, like "Film (2021)" or "Film 2021".
    var titleYear: Int? {
        guard let range = name.range(of: "(19|20)\\d{2}", options: .regularExpression) else { return nil }
        return Int(name[range])
    }

    static func clean(_ raw: String) -> String {
        var text = raw
        // "EN | Title", "UK: Title", "|NL| Title"
        if let range = text.range(of: "^\\s*[|\\[(]?[A-Za-z]{2,4}[|\\])]?\\s*[|:\\-]\\s*", options: .regularExpression) {
            text.removeSubrange(range)
        }
        for pattern in ["\\[[^\\]]*\\]", "\\((19|20)\\d{2}\\)", "\\b(4K|UHD|FHD|HD|SD|HEVC|H265|MULTI|VOSTFR)\\b"] {
            text = text.replacingOccurrences(of: pattern, with: "", options: [.regularExpression, .caseInsensitive])
        }
        return text.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: " -|:.").union(.whitespaces))
    }
}

struct VodInfo {
    var title: String?
    var plot: String?
    var year: String?
    var rating: String?
    var duration: String?
    var genre: String?
    var cast: String?
    var director: String?
    var poster: String?
    var backdrop: String?
    var containerExtension: String?
    var tmdbId: Int? = nil
}

struct Episode: Identifiable, Hashable, Codable {
    let id: String
    let season: String
    let number: String?
    let title: String
    let containerExtension: String?
    let plot: String?
    let duration: String?
    let image: String?
}

struct Season: Identifiable, Hashable {
    let key: String
    let episodes: [Episode]
    var id: String { key }
}

struct SeriesInfo {
    var title: String?
    var plot: String?
    var year: String?
    var rating: String?
    var genre: String?
    var cast: String?
    var poster: String?
    var backdrop: String?
    var seasons: [Season]
    var tmdbId: Int? = nil
}

struct Programme: Identifiable, Hashable {
    let title: String
    let description: String
    let start: Date
    let end: Date
    /// The provider keeps a recording of this programme (catch-up).
    var hasArchive: Bool = false
    /// Start time as the server writes it ("2026-09-28 20:00:00"), needed for catch-up addresses.
    var serverStart: String? = nil

    var id: TimeInterval { start.timeIntervalSince1970 }
    func isOn(at date: Date) -> Bool { date >= start && date < end }
    func hasEnded(by date: Date) -> Bool { end <= date }
    var canReplay: Bool { hasArchive && hasEnded(by: Date()) }
}

struct XtreamClient {
    let credentials: XtreamCredentials
    var sourceId: String = Source.mainId

    // MARK: Input helpers

    /// "example.com:8080/", "http://example.com:8080/c/" -> "http://example.com:8080"
    static func normalizeServer(_ input: String) -> String? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.lowercased().hasPrefix("http://") && !text.lowercased().hasPrefix("https://") {
            text = "http://" + text
        }
        guard let parts = URLComponents(string: text), let scheme = parts.scheme?.lowercased(),
              let host = parts.host, !host.isEmpty else { return nil }
        if let port = parts.port { return "\(scheme)://\(host):\(port)" }
        return "\(scheme)://\(host)"
    }

    /// Reads server, username and password out of a provider's M3U link (get.php?username=...&password=...).
    static func credentials(fromLink input: String) -> XtreamCredentials? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.contains("username="), text.contains("password=") else { return nil }
        if !text.contains("://") { text = "http://" + text }
        guard let parts = URLComponents(string: text),
              let user = parts.queryItems?.first(where: { $0.name == "username" })?.value, !user.isEmpty,
              let pass = parts.queryItems?.first(where: { $0.name == "password" })?.value, !pass.isEmpty,
              let server = normalizeServer(text) else { return nil }
        return XtreamCredentials(server: server, username: user, password: pass)
    }

    // MARK: Calls

    func accountInfo() async throws -> AccountInfo {
        let root = try await call(action: nil)
        guard let dict = root as? [String: Any] else { throw XtreamError.notXtream(host) }
        guard let user = dict["user_info"] as? [String: Any] else { throw XtreamError.rejected }
        if str(user, "auth") == "0" { throw XtreamError.rejected }
        let formats = (user["allowed_output_formats"] as? [Any])?.compactMap { $0 as? String } ?? []
        return AccountInfo(
            status: str(user, "status"),
            expiry: str(user, "exp_date").flatMap { Double($0) }.flatMap { $0 > 0 ? Date(timeIntervalSince1970: $0) : nil },
            activeConnections: int(user, "active_cons"),
            maxConnections: int(user, "max_connections"),
            isTrial: str(user, "is_trial") == "1",
            allowedFormats: formats.map { $0.lowercased() }
        )
    }

    func categories(_ kind: ContentKind) async throws -> [ContentCategory] {
        let action: String
        switch kind {
        case .live: action = "get_live_categories"
        case .movie: action = "get_vod_categories"
        case .series: action = "get_series_categories"
        }
        let list = try await call(action: action) as? [Any] ?? []
        return list.compactMap { raw in
            guard let item = raw as? [String: Any], let id = str(item, "category_id") else { return nil }
            return ContentCategory(id: id, name: str(item, "category_name") ?? "Untitled")
        }
    }

    func items(_ kind: ContentKind, category: String) async throws -> [MediaItem] {
        let action: String
        switch kind {
        case .live: action = "get_live_streams"
        case .movie: action = "get_vod_streams"
        case .series: action = "get_series"
        }
        let list = try await call(action: action, params: ["category_id": category]) as? [Any] ?? []
        return list.compactMap { raw in
            guard let item = raw as? [String: Any] else { return nil }
            let idKey = kind == .series ? "series_id" : "stream_id"
            guard let id = int(item, idKey) else { return nil }
            return MediaItem(
                kind: kind,
                streamId: id,
                name: str(item, "name") ?? "Untitled",
                icon: kind == .series ? str(item, "cover") : str(item, "stream_icon"),
                categoryId: str(item, "category_id"),
                containerExtension: str(item, "container_extension"),
                rating: str(item, "rating").flatMap { $0 == "0" ? nil : $0 },
                sourceId: sourceId,
                epgId: str(item, "epg_channel_id"),
                number: int(item, "num"),
                added: str(item, "added").flatMap { Double($0) } ?? str(item, "last_modified").flatMap { Double($0) },
                hasArchive: kind == .live ? (int(item, "tv_archive") ?? 0) > 0 : nil
            )
        }
    }

    /// Every channel, film or series the account has, in one request. Big providers send tens of
    /// megabytes and can take minutes to start, so this waits up to three minutes between bytes and
    /// reports how much has arrived. Malformed entries are skipped rather than failing the list.
    func allItems(_ kind: ContentKind, progress: @escaping @Sendable (Int64) -> Void) async throws -> [MediaItem] {
        let action: String
        switch kind {
        case .live: action = "get_live_streams"
        case .movie: action = "get_vod_streams"
        case .series: action = "get_series"
        }
        let url = try apiURL(action: action, params: [:])
        let data: Data
        do {
            data = try await LargeDownload.fetch(url, headers: requestHeaders, progress: progress)
        } catch let error as URLError {
            throw describe(error)
        } catch LargeDownload.Failure.status(let code) {
            if code == 401 { throw XtreamError.rejected }
            if code == 403 { throw XtreamError.forbidden(host) }
            throw XtreamError.httpStatus(host, code)
        }
        let source = sourceId
        return try await Task.detached(priority: .userInitiated) {
            try XtreamCatalogDecoder.decode(data, kind: kind, sourceId: source)
        }.value
    }

    func vodInfo(_ id: Int) async throws -> VodInfo {
        let root = try await call(action: "get_vod_info", params: ["vod_id": String(id)]) as? [String: Any]
        let info = root?["info"] as? [String: Any]
        let movie = root?["movie_data"] as? [String: Any]
        return VodInfo(
            title: str(movie, "name") ?? str(info, "name"),
            plot: str(info, "plot") ?? str(info, "description"),
            year: (str(info, "releasedate") ?? str(info, "year")).map { String($0.prefix(4)) },
            rating: str(info, "rating").flatMap { $0 == "0" ? nil : $0 },
            duration: str(info, "duration"),
            genre: str(info, "genre"),
            cast: str(info, "cast") ?? str(info, "actors"),
            director: str(info, "director"),
            poster: str(info, "movie_image") ?? str(info, "cover_big"),
            backdrop: str(info, "backdrop_path"),
            containerExtension: str(movie, "container_extension"),
            tmdbId: int(info, "tmdb_id") ?? int(info, "tmdb")
        )
    }

    func seriesInfo(_ id: Int) async throws -> SeriesInfo {
        let root = try await call(action: "get_series_info", params: ["series_id": String(id)]) as? [String: Any]
        let info = root?["info"] as? [String: Any]
        return SeriesInfo(
            title: str(info, "name"),
            plot: str(info, "plot"),
            year: (str(info, "releaseDate") ?? str(info, "release_date")).map { String($0.prefix(4)) },
            rating: str(info, "rating").flatMap { $0 == "0" ? nil : $0 },
            genre: str(info, "genre"),
            cast: str(info, "cast"),
            poster: str(info, "cover"),
            backdrop: str(info, "backdrop_path"),
            seasons: parseSeasons(root?["episodes"]),
            tmdbId: int(info, "tmdb_id") ?? int(info, "tmdb")
        )
    }

    func shortEPG(streamId: Int, limit: Int = 4) async throws -> [Programme] {
        let root = try await call(action: "get_short_epg", params: ["stream_id": String(streamId), "limit": String(limit)])
        return parseListings(root)
    }

    private func parseListings(_ root: Any) -> [Programme] {
        let listings = (root as? [String: Any])?["epg_listings"] as? [Any] ?? []
        return listings.compactMap { raw in
            guard let item = raw as? [String: Any],
                  let start = str(item, "start_timestamp").flatMap({ Double($0) }),
                  let end = str(item, "stop_timestamp").flatMap({ Double($0) }), end > start else { return nil }
            let title = decodeMaybeBase64(str(item, "title"))
            return Programme(
                title: title.isEmpty ? "Untitled" : title,
                description: decodeMaybeBase64(str(item, "description")),
                start: Date(timeIntervalSince1970: start),
                end: Date(timeIntervalSince1970: end),
                hasArchive: str(item, "has_archive") == "1",
                serverStart: str(item, "start")
            )
        }.sorted { $0.start < $1.start }
    }

    /// The whole listing for one channel, including which past programmes can be replayed.
    func fullEPG(streamId: Int) async throws -> [Programme] {
        let root = try await call(action: "get_simple_data_table", params: ["stream_id": String(streamId)])
        return parseListings(root)
    }

    // MARK: Stream addresses

    /// Standard Xtream catch-up address: /timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ts
    func catchUpURL(streamId: Int, programme: Programme) -> URL? {
        guard let serverStart = programme.serverStart,
              let match = serverStart.range(of: "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}", options: .regularExpression)
        else { return nil }
        let stamp = String(serverStart[match])          // "2026-09-28 20:00"
        let day = String(stamp.prefix(10))
        let hour = String(stamp.dropFirst(11).prefix(2))
        let minute = String(stamp.suffix(2))
        let minutes = max(1, Int(programme.end.timeIntervalSince(programme.start) / 60))
        return URL(string: "\(credentials.server)/timeshift/\(pathSafe(credentials.username))/\(pathSafe(credentials.password))/\(minutes)/\(day):\(hour)-\(minute)/\(streamId).ts")
    }

    func liveURL(_ item: MediaItem, hls: Bool) -> URL? {
        URL(string: "\(credentials.server)/live/\(pathSafe(credentials.username))/\(pathSafe(credentials.password))/\(item.streamId).\(hls ? "m3u8" : "ts")")
    }

    func movieURL(_ item: MediaItem, containerExtension: String?) -> URL? {
        let ext = containerExtension ?? item.containerExtension ?? "mp4"
        return URL(string: "\(credentials.server)/movie/\(pathSafe(credentials.username))/\(pathSafe(credentials.password))/\(item.streamId).\(ext)")
    }

    func episodeURL(_ episode: Episode) -> URL? {
        let ext = episode.containerExtension ?? "mp4"
        return URL(string: "\(credentials.server)/series/\(pathSafe(credentials.username))/\(pathSafe(credentials.password))/\(episode.id).\(ext)")
    }

    // MARK: Plumbing

    /// The server's host and port, for error messages ("example.com:8080").
    var host: String {
        guard let parts = URLComponents(string: credentials.server), let name = parts.host else {
            return credentials.server
        }
        if let port = parts.port { return "\(name):\(port)" }
        return name
    }

    private var requestHeaders: [String: String] {
        // Panels often refuse an unknown player name. IPTV Smarters is accepted by this provider.
        ["Accept": "application/json", "User-Agent": "IPTVSmartersPro"]
    }

    private func apiURL(action: String?, params: [String: String]) throws -> URL {
        guard var parts = URLComponents(string: credentials.server + "/player_api.php") else { throw XtreamError.badServer }
        var query: [(String, String)] = [("username", credentials.username), ("password", credentials.password)]
        if let action { query.append(("action", action)) }
        for (key, value) in params.sorted(by: { $0.key < $1.key }) {
            query.append((key, value))
        }
        parts.percentEncodedQuery = query
            .map { "\(queryEncode($0.0))=\(queryEncode($0.1))" }
            .joined(separator: "&")
        guard let url = parts.url else { throw XtreamError.badServer }
        return url
    }

    /// The provider's XMLTV guide for the whole account (xmltv.php).
    var xmltvURL: URL? {
        guard var parts = URLComponents(string: credentials.server + "/xmltv.php") else { return nil }
        parts.percentEncodedQuery = "username=\(queryEncode(credentials.username))&password=\(queryEncode(credentials.password))"
        return parts.url
    }

    private func call(action: String?, params: [String: String] = [:]) async throws -> Any {
        // Everything but letters, digits and -._~ is percent-encoded (apiURL): URLComponents leaves
        // "+" as is, which servers read as a space, breaking passwords that contain one.
        let url = try apiURL(action: action, params: params)
        // Busy panels can be slow to answer, so a single call gets a full minute.
        var request = URLRequest(url: url, timeoutInterval: 60)
        for (name, value) in requestHeaders { request.setValue(value, forHTTPHeaderField: name) }

        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: request)
        } catch let error as URLError {
            throw describe(error)
        }
        let (data, response) = result
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw XtreamError.rejected }
        if code == 403 { throw XtreamError.forbidden(host) }
        guard (200...299).contains(code) else { throw XtreamError.httpStatus(host, code) }
        do {
            return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            // Usually a web page: a login portal, a block page or a Cloudflare check.
            throw XtreamError.notXtream(host)
        }
    }

    func describe(_ error: URLError) -> XtreamError {
        switch error.code {
        case .notConnectedToInternet, .dataNotAllowed, .internationalRoamingOff:
            return .noInternet
        case .cannotFindHost, .dnsLookupFailed:
            return .hostNotFound(host)
        case .cannotConnectToHost, .networkConnectionLost:
            return .refused(host)
        case .timedOut:
            return .timedOut(host)
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
             .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot, .clientCertificateRejected,
             .clientCertificateRequired:
            return .secureConnection(host)
        case .appTransportSecurityRequiresSecureConnection:
            return .secureConnection(host)
        default:
            return .other(host, error.localizedDescription)
        }
    }

    /// Percent-encodes everything except unreserved ASCII characters.
    private func queryEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func parseSeasons(_ element: Any?) -> [Season] {
        var bySeason: [String: [Episode]] = [:]
        func add(_ raw: Any, seasonKey: String) {
            guard let item = raw as? [String: Any], let id = str(item, "id") else { return }
            let info = item["info"] as? [String: Any]
            let season = str(item, "season") ?? seasonKey
            bySeason[season, default: []].append(Episode(
                id: id,
                season: season,
                number: str(item, "episode_num"),
                title: str(item, "title") ?? "Episode",
                containerExtension: str(item, "container_extension"),
                plot: str(info, "plot"),
                duration: str(info, "duration"),
                image: str(info, "movie_image")
            ))
        }
        if let map = element as? [String: Any] {
            for (key, value) in map { (value as? [Any])?.forEach { add($0, seasonKey: key) } }
        } else if let list = element as? [Any] {
            for (index, value) in list.enumerated() { (value as? [Any])?.forEach { add($0, seasonKey: String(index + 1)) } }
        }
        return bySeason
            .map { key, episodes in
                Season(key: key, episodes: episodes.sorted { (Int($0.number ?? "") ?? .max) < (Int($1.number ?? "") ?? .max) })
            }
            .sorted { (Int($0.key) ?? .max, $0.key) < (Int($1.key) ?? .max, $1.key) }
    }

    private func pathSafe(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

// MARK: Lenient JSON helpers

func str(_ dict: [String: Any]?, _ key: String) -> String? {
    guard let value = dict?[key] else { return nil }
    if let text = value as? String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed == "null" ? nil : trimmed
    }
    if let number = value as? NSNumber { return number.stringValue }
    // backdrop_path and similar arrive as lists of images: take the first.
    if let list = value as? [Any], let first = list.first as? String, !first.isEmpty { return first }
    return nil
}

func int(_ dict: [String: Any]?, _ key: String) -> Int? {
    guard let text = str(dict, key) else { return nil }
    return Int(text) ?? Double(text).map { Int($0) }
}

/// Xtream sends EPG titles and descriptions base64-encoded, but not every panel does.
func decodeMaybeBase64(_ value: String?) -> String {
    guard let value, !value.isEmpty else { return "" }
    guard value.count % 4 == 0,
          value.range(of: "^[A-Za-z0-9+/=\\s]+$", options: .regularExpression) != nil,
          let data = Data(base64Encoded: value, options: .ignoreUnknownCharacters),
          let decoded = String(data: data, encoding: .utf8),
          !decoded.isEmpty,
          !decoded.unicodeScalars.contains(where: { $0.properties.generalCategory == .control && $0 != "\n" && $0 != "\r" && $0 != "\t" })
    else { return value }
    return decoded.trimmingCharacters(in: .whitespacesAndNewlines)
}
