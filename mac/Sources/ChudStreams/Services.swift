import AppKit
import Foundation

// Outside services, each using the viewer's own key from Settings > Services:
//   TMDB: trending titles, synopses, cast, actor pages (themoviedb.org)
//   Trakt: ratings and comments (trakt.tv)
//   OpenSubtitles: subtitle search and download (opensubtitles.com)

struct ServiceError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

private enum HTTP {
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        configuration.urlCache = URLCache(memoryCapacity: 8 << 20, diskCapacity: 64 << 20)
        configuration.requestCachePolicy = .useProtocolCachePolicy
        return URLSession(configuration: configuration)
    }()

    static func json(_ request: URLRequest, service: String) async throws -> Any {
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw ServiceError(message: "Couldn't reach \(service). Check your internet connection.")
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch code {
        case 200...299: break
        case 401, 403: throw ServiceError(message: "\(service) didn't accept your key. Check it in Settings > Services.")
        case 404: throw ServiceError(message: "\(service) doesn't have this title.")
        case 429: throw ServiceError(message: "\(service) is limiting requests right now. Try again in a minute.")
        default: throw ServiceError(message: "\(service) answered with error \(code).")
        }
        do {
            return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw ServiceError(message: "\(service) sent something unexpected.")
        }
    }
}

// MARK: - TMDB

struct TMDBTitle: Identifiable, Hashable {
    let id: Int
    let mediaType: String
    let title: String
    let overview: String
    let backdrop: String?
    let poster: String?
    let rating: Double?
    let year: Int?

    var isShow: Bool { mediaType == "tv" }
    var key: String { "\(mediaType)-\(id)" }
}

struct TMDBCastMember: Identifiable, Hashable {
    let id: Int
    let name: String
    let role: String
    let photo: String?
}

struct TMDBDetails {
    let id: Int
    let mediaType: String
    let title: String
    let tagline: String?
    let overview: String
    let runtime: Int?
    let genres: [String]
    let rating: Double?
    let votes: Int?
    let year: Int?
    let backdrop: String?
    let poster: String?
    let imdbId: String?
    let cast: [TMDBCastMember]
    let directors: [String]
    let trailerKey: String?
    let certification: String?
}

struct TMDBCredit: Identifiable, Hashable {
    let id: String
    let tmdbId: Int
    let mediaType: String
    let title: String
    let role: String
    let year: Int?
    let poster: String?
    let rating: Double?
}

struct TMDBPerson {
    let id: Int
    let name: String
    let biography: String
    let born: String?
    let died: String?
    let birthplace: String?
    let photo: String?
    let imdbId: String?
    let knownFor: String?
    let credits: [TMDBCredit]
}

enum TMDB {
    static var isConfigured: Bool { Secrets.has(.tmdb) }

    private static var cache: [String: Any] = [:]
    private static let lock = NSLock()

    static func image(_ path: String?, size: String = "w500") -> String? {
        guard let path, !path.isEmpty else { return nil }
        if path.hasPrefix("http") { return path }
        return "\(ServiceURL.tmdbImages)/\(size)\(path)"
    }

    private static func get(_ path: String, _ query: [String: String] = [:]) async throws -> [String: Any] {
        guard let key = Secrets.get(.tmdb) else {
            throw ServiceError(message: "Add a TMDB API key in Settings > Services to see this.")
        }
        var parts = URLComponents(string: ServiceURL.tmdb + path)
        var items = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        items.append(URLQueryItem(name: "language", value: Locale.preferredLanguages.first ?? "en-GB"))
        let bearer = key.hasPrefix("eyJ")
        if !bearer { items.append(URLQueryItem(name: "api_key", value: key)) }
        parts?.queryItems = items.sorted { $0.name < $1.name }
        guard let url = parts?.url else { throw ServiceError(message: "Bad TMDB address.") }
        let cacheKey = url.absoluteString
        lock.lock()
        let cached = cache[cacheKey] as? [String: Any]
        lock.unlock()
        if let cached { return cached }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if bearer { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        let result = try await HTTP.json(request, service: "TMDB") as? [String: Any] ?? [:]
        lock.lock()
        cache[cacheKey] = result
        lock.unlock()
        return result
    }

    private static func year(_ text: String?) -> Int? {
        guard let text, text.count >= 4 else { return nil }
        return Int(text.prefix(4))
    }

    private static func title(_ item: [String: Any], defaultType: String? = nil) -> TMDBTitle? {
        guard let id = int(item, "id") else { return nil }
        let type = str(item, "media_type") ?? defaultType ?? "movie"
        guard type == "movie" || type == "tv" else { return nil }
        return TMDBTitle(
            id: id,
            mediaType: type,
            title: str(item, "title") ?? str(item, "name") ?? "Untitled",
            overview: str(item, "overview") ?? "",
            backdrop: str(item, "backdrop_path"),
            poster: str(item, "poster_path"),
            rating: num(item, "vote_average"),
            year: year(str(item, "release_date") ?? str(item, "first_air_date"))
        )
    }

    /// This week's trending films and shows.
    static func trending() async throws -> [TMDBTitle] {
        let root = try await get("/trending/all/week")
        return (root["results"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { title($0) } }
    }

    /// Looks a provider title up on TMDB.
    static func find(title name: String, year: Int?, show: Bool) async throws -> TMDBTitle? {
        var query = ["query": name, "include_adult": "false"]
        if let year { query[show ? "first_air_date_year" : "year"] = String(year) }
        let root = try await get(show ? "/search/tv" : "/search/movie", query)
        let results = (root["results"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { title($0, defaultType: show ? "tv" : "movie") } }
        if let year, results.isEmpty {
            return try await find(title: name, year: nil, show: show)
        }
        return results.first
    }

    static func details(id: Int, show: Bool) async throws -> TMDBDetails {
        let append = show ? "aggregate_credits,external_ids,videos,content_ratings" : "credits,external_ids,videos,release_dates"
        let root = try await get(show ? "/tv/\(id)" : "/movie/\(id)", ["append_to_response": append])
        let credits = (root[show ? "aggregate_credits" : "credits"] as? [String: Any]) ?? [:]
        let castList = credits["cast"] as? [Any] ?? []
        let cast: [TMDBCastMember] = castList.prefix(30).compactMap { raw in
            guard let person = raw as? [String: Any], let personId = int(person, "id") else { return nil }
            var role = str(person, "character") ?? ""
            if role.isEmpty, let roles = person["roles"] as? [Any], let first = roles.first as? [String: Any] {
                role = str(first, "character") ?? ""
            }
            return TMDBCastMember(id: personId, name: str(person, "name") ?? "", role: role, photo: str(person, "profile_path"))
        }
        let crew = credits["crew"] as? [Any] ?? []
        var directors: [String] = crew.compactMap { raw in
            guard let person = raw as? [String: Any], str(person, "job") == "Director" else { return nil }
            return str(person, "name")
        }
        if show, let creators = root["created_by"] as? [Any] {
            directors = creators.compactMap { ($0 as? [String: Any]).flatMap { str($0, "name") } }
        }
        let videos = ((root["videos"] as? [String: Any])?["results"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
        let trailer = videos.first { str($0, "site") == "YouTube" && str($0, "type") == "Trailer" } ?? videos.first { str($0, "site") == "YouTube" }
        let external = root["external_ids"] as? [String: Any]
        let runtime = int(root, "runtime") ?? ((root["episode_run_time"] as? [Any])?.first as? NSNumber)?.intValue
        return TMDBDetails(
            id: id,
            mediaType: show ? "tv" : "movie",
            title: str(root, "title") ?? str(root, "name") ?? "",
            tagline: str(root, "tagline"),
            overview: str(root, "overview") ?? "",
            runtime: runtime,
            genres: (root["genres"] as? [Any] ?? []).compactMap { ($0 as? [String: Any]).flatMap { str($0, "name") } },
            rating: num(root, "vote_average"),
            votes: int(root, "vote_count"),
            year: year(str(root, "release_date") ?? str(root, "first_air_date")),
            backdrop: str(root, "backdrop_path"),
            poster: str(root, "poster_path"),
            imdbId: str(root, "imdb_id") ?? str(external, "imdb_id"),
            cast: cast,
            directors: Array(directors.prefix(3)),
            trailerKey: trailer.flatMap { str($0, "key") },
            certification: certification(root, show: show)
        )
    }

    private static func certification(_ root: [String: Any], show: Bool) -> String? {
        let region = Locale.current.region?.identifier ?? "GB"
        if show {
            let ratings = ((root["content_ratings"] as? [String: Any])?["results"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
            let match = ratings.first { str($0, "iso_3166_1") == region } ?? ratings.first { str($0, "iso_3166_1") == "US" }
            return match.flatMap { str($0, "rating") }
        }
        let releases = ((root["release_dates"] as? [String: Any])?["results"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
        let match = releases.first { str($0, "iso_3166_1") == region } ?? releases.first { str($0, "iso_3166_1") == "US" }
        let dates = (match?["release_dates"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }
        return dates.compactMap { str($0, "certification") }.first { !$0.isEmpty }
    }

    static func person(id: Int) async throws -> TMDBPerson {
        let root = try await get("/person/\(id)", ["append_to_response": "combined_credits,external_ids"])
        let combined = root["combined_credits"] as? [String: Any]
        var seen = Set<String>()
        let rawCredits = (combined?["cast"] as? [Any] ?? []) + (combined?["crew"] as? [Any] ?? [])
        let credits: [TMDBCredit] = rawCredits.compactMap { raw in
            guard let credit = raw as? [String: Any], let tmdbId = int(credit, "id") else { return nil }
            let type = str(credit, "media_type") ?? "movie"
            guard seen.insert("\(type)-\(tmdbId)").inserted else { return nil }
            return TMDBCredit(
                id: str(credit, "credit_id") ?? "\(type)-\(tmdbId)",
                tmdbId: tmdbId,
                mediaType: type,
                title: str(credit, "title") ?? str(credit, "name") ?? "Untitled",
                role: str(credit, "character") ?? str(credit, "job") ?? "",
                year: year(str(credit, "release_date") ?? str(credit, "first_air_date")),
                poster: str(credit, "poster_path"),
                rating: num(credit, "vote_average")
            )
        }
        .sorted { ($0.year ?? 0) > ($1.year ?? 0) }
        let external = root["external_ids"] as? [String: Any]
        return TMDBPerson(
            id: id,
            name: str(root, "name") ?? "",
            biography: str(root, "biography") ?? "",
            born: str(root, "birthday"),
            died: str(root, "deathday"),
            birthplace: str(root, "place_of_birth"),
            photo: str(root, "profile_path"),
            imdbId: str(root, "imdb_id") ?? str(external, "imdb_id"),
            knownFor: str(root, "known_for_department"),
            credits: credits
        )
    }
}

// MARK: - Trakt

struct TraktComment: Identifiable, Hashable {
    let id: Int
    let user: String
    let text: String
    let spoiler: Bool
    let review: Bool
    let likes: Int
    let replies: Int
    let date: Date?
    let rating: Int?
}

struct TraktSummary {
    let rating: Double?
    let votes: Int?
    let comments: [TraktComment]
    let url: URL?
}

enum Trakt {
    static var isConfigured: Bool { Secrets.has(.trakt) }

    private static func get(_ path: String) async throws -> Any {
        guard let key = Secrets.get(.trakt) else {
            throw ServiceError(message: "Add a Trakt client ID in Settings > Services to see ratings and comments.")
        }
        guard let url = URL(string: ServiceURL.trakt + path) else { throw ServiceError(message: "Bad Trakt address.") }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("2", forHTTPHeaderField: "trakt-api-version")
        request.setValue(key, forHTTPHeaderField: "trakt-api-key")
        return try await HTTP.json(request, service: "Trakt")
    }

    /// Rating and the most-liked comments for a film, show, or one episode of a show.
    static func summary(tmdbId: Int, show: Bool, season: Int? = nil, episode: Int? = nil) async throws -> TraktSummary {
        let found = try await get("/search/tmdb/\(tmdbId)?type=\(show ? "show" : "movie")") as? [Any] ?? []
        let entry = found.first as? [String: Any]
        let media = entry?[show ? "show" : "movie"] as? [String: Any]
        let ids = media?["ids"] as? [String: Any]
        guard let slug = str(ids, "slug") ?? str(ids, "trakt") else {
            throw ServiceError(message: "Trakt doesn't list this title.")
        }
        var base = show ? "/shows/\(slug)" : "/movies/\(slug)"
        var page = "https://trakt.tv\(base)"
        if show, let season, let episode {
            base += "/seasons/\(season)/episodes/\(episode)"
            page += "/seasons/\(season)/episodes/\(episode)"
        }
        let ratings = try? await get(base + "/ratings") as? [String: Any]
        let raw = (try? await get(base + "/comments/likes?limit=25") as? [Any]) ?? []
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let comments: [TraktComment] = raw.compactMap { value in
            guard let comment = value as? [String: Any], let id = int(comment, "id") else { return nil }
            let user = comment["user"] as? [String: Any]
            let stats = comment["user_stats"] as? [String: Any]
            return TraktComment(
                id: id,
                user: str(user, "name") ?? str(user, "username") ?? "Trakt user",
                text: str(comment, "comment") ?? "",
                spoiler: (comment["spoiler"] as? Bool) ?? false,
                review: (comment["review"] as? Bool) ?? false,
                likes: int(comment, "likes") ?? 0,
                replies: int(comment, "replies") ?? 0,
                date: str(comment, "created_at").flatMap { formatter.date(from: $0) },
                rating: int(stats, "rating")
            )
        }
        return TraktSummary(rating: num(ratings, "rating"), votes: int(ratings, "votes"), comments: comments, url: URL(string: page))
    }
}

// MARK: - OpenSubtitles

struct SubtitleResult: Identifiable, Hashable {
    let id: String
    let fileId: Int
    let language: String
    let release: String
    let downloads: Int
    let hearingImpaired: Bool
    let forced: Bool

    var detail: String {
        var parts = [language.uppercased(), "\(downloads) downloads"]
        if forced { parts.append("forced") }
        if hearingImpaired { parts.append("SDH") }
        return parts.joined(separator: " · ")
    }
}

enum OpenSubtitles {
    private static var token: String? = nil

    private static func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) throws -> URLRequest {
        guard let key = Secrets.get(.openSubtitles) else {
            throw ServiceError(message: "Add your OpenSubtitles API key in Settings > Services. It's free at opensubtitles.com.")
        }
        guard let url = URL(string: ServiceURL.openSubtitles + path) else { throw ServiceError(message: "Bad OpenSubtitles address.") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(key, forHTTPHeaderField: "Api-Key")
        request.setValue("CHUDSTREAMS v2.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        return request
    }

    static func search(query: String, languages: String, episode: Episode?) async throws -> [SubtitleResult] {
        var parts = URLComponents()
        var items = [
            URLQueryItem(name: "languages", value: languages.lowercased().replacingOccurrences(of: " ", with: "")),
            URLQueryItem(name: "query", value: query.lowercased()),
        ]
        if episode != nil { items.append(URLQueryItem(name: "type", value: "episode")) }
        parts.queryItems = items.sorted { $0.name < $1.name }
        let root = try await HTTP.json(try request("/subtitles?" + (parts.percentEncodedQuery ?? "")), service: "OpenSubtitles") as? [String: Any]
        let data = root?["data"] as? [Any] ?? []
        return data.compactMap { raw in
            guard let entry = raw as? [String: Any], let attributes = entry["attributes"] as? [String: Any],
                  let file = (attributes["files"] as? [Any])?.first as? [String: Any], let fileId = int(file, "file_id") else { return nil }
            return SubtitleResult(
                id: str(entry, "id") ?? String(fileId),
                fileId: fileId,
                language: str(attributes, "language") ?? "",
                release: str(attributes, "release") ?? str(file, "file_name") ?? "Subtitles",
                downloads: int(attributes, "download_count") ?? 0,
                hearingImpaired: (attributes["hearing_impaired"] as? Bool) ?? false,
                forced: (attributes["foreign_parts_only"] as? Bool) ?? false
            )
        }
        .sorted { $0.downloads > $1.downloads }
    }

    /// Signs in with the saved OpenSubtitles account, which raises the daily download limit.
    private static func login() async {
        guard token == nil, let user = Secrets.get(.openSubtitlesUser), let password = Secrets.get(.openSubtitlesPassword),
              let request = try? request("/login", method: "POST", body: ["username": user, "password": password]),
              let root = try? await HTTP.json(request, service: "OpenSubtitles") as? [String: Any] else { return }
        token = str(root, "token")
    }

    static func download(_ result: SubtitleResult) async throws -> URL {
        await login()
        let root = try await HTTP.json(try request("/download", method: "POST", body: ["file_id": result.fileId]), service: "OpenSubtitles") as? [String: Any]
        guard let link = str(root, "link"), let url = URL(string: link) else {
            let message = str(root, "message") ?? "OpenSubtitles didn't give a download link. You may have reached today's download limit."
            throw ServiceError(message: message)
        }
        let (data, _) = try await URLSession.shared.data(from: url)
        let folder = AppSupport.caches("Subtitles")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = (str(root, "file_name") ?? "\(result.fileId).srt").replacingOccurrences(of: "/", with: "_")
        let file = folder.appendingPathComponent(name)
        try data.write(to: file, options: .atomic)
        return file
    }
}
