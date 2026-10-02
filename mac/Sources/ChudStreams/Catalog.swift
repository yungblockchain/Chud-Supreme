import Foundation
import SwiftUI

// Sources and their catalogues.
//
// A source is an Xtream login or an M3U playlist (with an optional XMLTV guide). Each source's
// full lists of channels, films and series are downloaded once, cached compressed on disk and
// refreshed in the background, so browsing and searching 200,000 titles is instant. Before a
// full list has arrived, browsing falls back to one category at a time from the Xtream API.

enum SourceKind: String, Codable {
    case xtream, m3u
}

struct Source: Codable, Identifiable, Hashable {
    /// The id used for the first Xtream account, which existing favourites and resume points use.
    static let mainId = "main"

    var id: String
    var kind: SourceKind
    var name: String
    var server: String? = nil
    var username: String? = nil
    var playlistURL: String? = nil
    var epgURL: String? = nil
    var addedAt: Date = Date()

    var keychainAccount: String { "source.\(id)" }

    var password: String? {
        Keychain.read(account: keychainAccount)
    }

    var credentials: XtreamCredentials? {
        guard kind == .xtream, let server, let username, let password else { return nil }
        return XtreamCredentials(server: server, username: username, password: password)
    }

    var client: XtreamClient? {
        credentials.map { XtreamClient(credentials: $0, sourceId: id) }
    }

    var detail: String {
        switch kind {
        case .xtream: return "Xtream · \(username ?? "") on \(URL(string: server ?? "")?.host ?? server ?? "")"
        case .m3u: return "M3U · \(URL(string: playlistURL ?? "")?.host ?? "playlist")" + (epgURL == nil ? "" : " + guide")
        }
    }
}

enum CatalogPhase: Equatable {
    case idle
    case loading(bytes: Int64)
    case decoding
    case ready(count: Int, updated: Date)
    case failed(String)
}

/// On-disk cache of a source's lists, compressed.
enum CatalogCache {
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("CHUD STREAMS", isDirectory: true)
    }

    private static func file(_ sourceId: String, _ name: String) -> URL {
        let folder = directory.appendingPathComponent("Catalog", isDirectory: true)
            .appendingPathComponent(sourceId.replacingOccurrences(of: "/", with: "_"), isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(name)
    }

    struct Entry<T: Codable>: Codable {
        let saved: Date
        let value: T
    }

    static func save<T: Codable>(_ value: T, source: String, name: String) {
        guard let data = try? JSONEncoder().encode(Entry(saved: Date(), value: value)),
              let packed = try? (data as NSData).compressed(using: .lzfse) as Data else { return }
        try? packed.write(to: file(source, name + ".lzfse"), options: .atomic)
    }

    static func load<T: Codable>(_ type: T.Type, source: String, name: String) -> Entry<T>? {
        guard let packed = try? Data(contentsOf: file(source, name + ".lzfse")),
              let data = try? (packed as NSData).decompressed(using: .lzfse) as Data else { return nil }
        return try? JSONDecoder().decode(Entry<T>.self, from: data)
    }

    static func remove(source: String) {
        let folder = directory.appendingPathComponent("Catalog", isDirectory: true).appendingPathComponent(source)
        try? FileManager.default.removeItem(at: folder)
    }
}

/// Search keys prepared once per list: folded (no case, no accents) names.
private struct SearchIndex {
    var keys: [String] = []

    init() {}

    init(_ items: [MediaItem]) {
        keys = items.map { SearchIndex.fold($0.name) }
    }

    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }
}

/// Everything for one source: categories, full lists, search and the M3U guide.
@MainActor
final class CatalogStore: ObservableObject {
    let source: Source
    @Published private(set) var phases: [ContentKind: CatalogPhase] = [:]
    @Published private(set) var categories: [ContentKind: [ContentCategory]] = [:]
    /// Bumped whenever a full list arrives, so views showing it refresh.
    @Published private(set) var version = 0
    @Published private(set) var guideVersion = 0

    private var lists: [ContentKind: [MediaItem]] = [:]
    // Positions in `lists`, so big catalogues aren't held twice.
    private var byCategory: [ContentKind: [String: [Int32]]] = [:]
    private var byStreamId: [ContentKind: [Int: Int32]] = [:]
    private var indexes: [ContentKind: SearchIndex] = [:]
    private var categoryCache: [String: [MediaItem]] = [:]
    private var loading = Set<ContentKind>()
    private(set) var guide = XMLTVGuide()
    private var guideLoaded = false

    /// Lists older than this are refreshed in the background.
    static let maxAge: TimeInterval = 12 * 3600

    init(source: Source) {
        self.source = source
    }

    var kinds: [ContentKind] {
        source.kind == .xtream ? [.live, .movie, .series] : [.live, .movie]
    }

    func hasFullList(_ kind: ContentKind) -> Bool { lists[kind] != nil }
    func count(_ kind: ContentKind) -> Int { lists[kind]?.count ?? 0 }
    func all(_ kind: ContentKind) -> [MediaItem] { lists[kind] ?? [] }

    func item(_ kind: ContentKind, streamId: Int) -> MediaItem? {
        guard let position = byStreamId[kind]?[streamId], let list = lists[kind], Int(position) < list.count else { return nil }
        return list[Int(position)]
    }

    /// Cached lists first (instant), then a refresh from the provider if they're old or missing.
    func start() {
        for kind in kinds {
            if let entry = CatalogCache.load([MediaItem].self, source: source.id, name: kind.rawValue) {
                install(entry.value, kind: kind, updated: entry.saved)
                if let cats = CatalogCache.load([CachedCategory].self, source: source.id, name: "categories-\(kind.rawValue)") {
                    categories[kind] = cats.value.map { ContentCategory(id: $0.id, name: $0.name) }
                }
            }
        }
        Task { await refreshIfStale() }
    }

    func refreshIfStale() async {
        for kind in kinds {
            if case .ready(_, let updated) = phases[kind], Date().timeIntervalSince(updated) < CatalogStore.maxAge { continue }
            await refresh(kind)
        }
        if source.kind == .m3u || source.epgURL != nil { await loadGuide() }
    }

    func refreshAll() async {
        categoryCache = [:]
        for kind in kinds { await refresh(kind) }
        guideLoaded = false
        if source.kind == .m3u || source.epgURL != nil { await loadGuide() }
    }

    /// Downloads the whole list for one kind. For M3U, one download fills live and films together.
    func refresh(_ kind: ContentKind) async {
        guard !loading.contains(kind) else { return }
        if source.kind == .m3u {
            if kind != .live { return }  // one playlist download covers both
            await refreshPlaylist()
            return
        }
        guard let client = source.client else { return }
        loading.insert(kind)
        defer { loading.remove(kind) }
        let previous = phases[kind]
        phases[kind] = .loading(bytes: 0)
        do {
            // Categories first: they're small and let browsing start right away.
            let cats = try await client.categories(kind)
            categories[kind] = cats
            CatalogCache.save(cats.map { CachedCategory(id: $0.id, name: $0.name) }, source: source.id, name: "categories-\(kind.rawValue)")
            let items = try await client.allItems(kind) { [weak self] bytes in
                Task { @MainActor in
                    guard let self else { return }
                    if case .loading = self.phases[kind] { self.phases[kind] = .loading(bytes: bytes) }
                }
            }
            phases[kind] = .decoding
            install(items, kind: kind, updated: Date())
            let sourceId = source.id
            Task.detached(priority: .utility) {
                CatalogCache.save(items, source: sourceId, name: kind.rawValue)
            }
            ErrorLog.note("Loaded \(items.count) \(kind.rawValue) entries for \(source.name)")
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            ErrorLog.record("Catalogue refresh failed (\(kind.rawValue))", detail: message)
            // Keep the cached list if there is one.
            if lists[kind] != nil, case .ready = previous {
                phases[kind] = previous
            } else {
                phases[kind] = .failed(message)
            }
        }
    }

    private func refreshPlaylist() async {
        guard let link = source.playlistURL, let url = URL(string: link) else {
            phases[.live] = .failed("This playlist has no address.")
            return
        }
        loading.insert(.live)
        defer { loading.remove(.live) }
        phases[.live] = .loading(bytes: 0)
        phases[.movie] = .loading(bytes: 0)
        do {
            let headers = ["User-Agent": PlaybackSettings.current.userAgentString]
            let data = try await LargeDownload.fetch(url, headers: headers) { [weak self] bytes in
                Task { @MainActor in
                    guard let self else { return }
                    self.phases[.live] = .loading(bytes: bytes)
                    self.phases[.movie] = .loading(bytes: bytes)
                }
            }
            phases[.live] = .decoding
            let sourceId = source.id
            let playlist = await Task.detached(priority: .userInitiated) { M3UParser.parse(data, sourceId: sourceId) }.value
            guard !playlist.live.isEmpty || !playlist.movies.isEmpty else {
                throw XtreamError.other(url.host ?? "the server", "that address didn't return an M3U playlist.")
            }
            categories[.live] = playlist.liveGroups
            categories[.movie] = playlist.movieGroups
            install(playlist.live, kind: .live, updated: Date())
            install(playlist.movies, kind: .movie, updated: Date())
            if source.epgURL == nil, let guideURL = playlist.guideURL {
                detectedGuideURL = guideURL
            }
            let live = playlist.live, movies = playlist.movies
            let liveCats = playlist.liveGroups.map { CachedCategory(id: $0.id, name: $0.name) }
            let movieCats = playlist.movieGroups.map { CachedCategory(id: $0.id, name: $0.name) }
            Task.detached(priority: .utility) {
                CatalogCache.save(live, source: sourceId, name: ContentKind.live.rawValue)
                CatalogCache.save(movies, source: sourceId, name: ContentKind.movie.rawValue)
                CatalogCache.save(liveCats, source: sourceId, name: "categories-live")
                CatalogCache.save(movieCats, source: sourceId, name: "categories-movie")
            }
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            ErrorLog.record("Playlist download failed", detail: message)
            if lists[.live] == nil { phases[.live] = .failed(message) } else { phases[.live] = .ready(count: count(.live), updated: Date()) }
            if lists[.movie] == nil { phases[.movie] = .failed(message) } else { phases[.movie] = .ready(count: count(.movie), updated: Date()) }
        }
    }

    /// A guide address the playlist itself named (used when none was entered).
    private(set) var detectedGuideURL: String? = nil

    private func install(_ items: [MediaItem], kind: ContentKind, updated: Date) {
        lists[kind] = items
        var grouped: [String: [Int32]] = [:]
        var byId: [Int: Int32] = [:]
        byId.reserveCapacity(items.count)
        for (position, item) in items.enumerated() {
            grouped[item.categoryId ?? "", default: []].append(Int32(position))
            byId[item.streamId] = Int32(position)
        }
        byCategory[kind] = grouped
        byStreamId[kind] = byId
        indexes[kind] = SearchIndex(items)
        if categories[kind] == nil || categories[kind]?.isEmpty == true {
            var seen = Set<String>()
            categories[kind] = items.compactMap { item in
                guard let id = item.categoryId, seen.insert(id).inserted else { return nil }
                return ContentCategory(id: id, name: id)
            }
        }
        phases[kind] = .ready(count: items.count, updated: updated)
        version += 1
    }

    /// One category's entries: from the full list when it's here, otherwise from the provider.
    func items(_ kind: ContentKind, category: String) async throws -> [MediaItem] {
        if let grouped = byCategory[kind], let list = lists[kind] {
            return (grouped[category] ?? []).map { list[Int($0)] }
        }
        let key = "\(kind.rawValue)|\(category)"
        if let cached = categoryCache[key] { return cached }
        guard let client = source.client else { return [] }
        let loaded = try await client.items(kind, category: category)
        categoryCache[key] = loaded
        return loaded
    }

    /// Categories, loading them from the provider if the full list hasn't arrived yet.
    func loadCategories(_ kind: ContentKind) async -> [ContentCategory] {
        if let known = categories[kind], !known.isEmpty { return known }
        guard let client = source.client else { return [] }
        if let loaded = try? await client.categories(kind) {
            categories[kind] = loaded
            return loaded
        }
        return []
    }

    /// Titles containing every word of `query` (accents and case ignored), best matches first.
    func search(_ query: String, kinds wanted: [ContentKind], limit: Int = 120) async -> [MediaItem] {
        let words = SearchIndex.fold(query).split(separator: " ").map(String.init).filter { !$0.isEmpty }
        guard !words.isEmpty else { return [] }
        var jobs: [([MediaItem], SearchIndex)] = []
        for kind in wanted {
            if let list = lists[kind], let index = indexes[kind] { jobs.append((list, index)) }
        }
        let phrase = words.joined(separator: " ")
        return await Task.detached(priority: .userInitiated) {
            var scored: [(Int, MediaItem)] = []
            for (list, index) in jobs {
                for position in 0..<min(list.count, index.keys.count) {
                    let key = index.keys[position]
                    var matches = true
                    for word in words where !key.contains(word) {
                        matches = false
                        break
                    }
                    guard matches else { continue }
                    var score = key.count
                    if key.hasPrefix(phrase) { score -= 1000 } else if key.contains(phrase) { score -= 500 }
                    scored.append((score, list[position]))
                }
            }
            scored.sort { $0.0 < $1.0 }
            return scored.prefix(limit).map { $0.1 }
        }.value
    }

    /// Newest films or series by the provider's "added" date.
    func recentlyAdded(_ kind: ContentKind, limit: Int = 30) -> [MediaItem] {
        guard let list = lists[kind] else { return [] }
        let dated = list.filter { ($0.added ?? 0) > 0 }
        if dated.count < limit { return Array(list.suffix(limit).reversed()) }
        return Array(dated.sorted { ($0.added ?? 0) > ($1.added ?? 0) }.prefix(limit))
    }

    /// Finds a film or series by title (and year when known), for matching TMDB's trending list
    /// to what the provider actually has.
    func match(title: String, year: Int?, kind: ContentKind) -> MediaItem? {
        guard let list = lists[kind], let index = indexes[kind] else { return nil }
        let wanted = SearchIndex.fold(MediaItem.clean(title))
        guard wanted.count >= 2 else { return nil }
        var best: MediaItem? = nil
        var bestScore = Int.max
        for position in 0..<min(list.count, index.keys.count) where index.keys[position].contains(wanted) {
            let item = list[position]
            let clean = SearchIndex.fold(item.cleanName)
            var score = abs(clean.count - wanted.count)
            if clean == wanted { score -= 100 }
            if let year, let itemYear = item.titleYear { score += itemYear == year ? -50 : 200 }
            if score < bestScore {
                bestScore = score
                best = item
            }
        }
        return bestScore < 60 ? best : nil
    }

    // MARK: Guide (M3U playlists, or a guide address entered for any source)

    var guideURL: String? { source.epgURL ?? detectedGuideURL }

    func loadGuide(force: Bool = false) async {
        guard force || !guideLoaded, let link = guideURL, let url = URL(string: link) else { return }
        guideLoaded = true
        if !force, let cached = CatalogCache.load(CachedGuide.self, source: source.id, name: "guide"),
           Date().timeIntervalSince(cached.saved) < 6 * 3600 {
            guide = cached.value.guide
            guideVersion += 1
            return
        }
        do {
            let data = try await LargeDownload.fetch(url, headers: ["User-Agent": PlaybackSettings.current.userAgentString])
            let live = lists[.live] ?? []
            let ids = Set(live.compactMap { $0.epgId })
            let wanted: Set<String>? = ids.count * 2 >= max(1, live.count) ? ids : nil
            let parsed = await Task.detached(priority: .utility) { XMLTVParser.parse(data, channels: wanted) }.value
            guide = parsed
            guideVersion += 1
            let sourceId = source.id
            Task.detached(priority: .background) {
                CatalogCache.save(CachedGuide(parsed), source: sourceId, name: "guide")
            }
        } catch {
            ErrorLog.record("Guide download failed", detail: error.localizedDescription)
        }
    }

    func xmltvListing(for item: MediaItem) -> [Programme] {
        guide.listing(for: item)
    }
}

struct CachedCategory: Codable {
    let id: String
    let name: String
}

/// The guide in a form that can be cached.
struct CachedGuide: Codable {
    struct Show: Codable {
        let t: String
        let d: String
        let s: Double
        let e: Double
    }

    var programmes: [String: [Show]]
    var names: [String: String]

    init(_ guide: XMLTVGuide) {
        programmes = guide.programmes.mapValues { list in
            list.map { Show(t: $0.title, d: String($0.description.prefix(400)), s: $0.start.timeIntervalSince1970, e: $0.end.timeIntervalSince1970) }
        }
        names = guide.channelNames
    }

    var guide: XMLTVGuide {
        var result = XMLTVGuide()
        result.channelNames = names
        result.programmes = programmes.mapValues { list in
            list.map { Programme(title: $0.t, description: $0.d, start: Date(timeIntervalSince1970: $0.s), end: Date(timeIntervalSince1970: $0.e)) }
        }
        return result
    }
}
