import Foundation
import SwiftUI

// Infinity is the addon library. It speaks the same addon protocol Nuvio and Stremio use:
// a manifest URL installs an addon, and that addon supplies catalogues, metadata, streams
// and subtitles. Xtream search is a different tab. Cinemeta ships installed so the shelves
// are not empty. Torrents are sent to Real-Debrid, then TorBox, when a stream has no file URL.

struct StremioCatalog: Codable, Hashable {
    var type: String
    var id: String
    var name: String
}

struct StremioAddon: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var version: String
    var description: String
    var transport: String
    var resources: [String]
    var types: [String]
    var catalogs: [StremioCatalog]
    var enabled: Bool

    func supports(_ resource: String) -> Bool {
        resources.contains { $0 == resource || $0.hasPrefix(resource) }
    }
}

struct InfinityTitle: Codable, Identifiable, Hashable {
    var id: String
    var type: String
    var name: String
    var poster: String?
    var overview: String?
    var year: String?
    var transport: String
    var addonName: String
}

struct InfinityEpisode: Identifiable, Hashable {
    var id: String
    var title: String
    var season: Int
    var episode: Int
}

struct InfinityStream: Identifiable {
    let id = UUID()
    var name: String
    var title: String
    var url: URL?
    var infoHash: String?
    var headers: [String: String]
    var subtitles: [URL]
}

@MainActor
final class InfinityStore: ObservableObject {
    static let shared = InfinityStore()

    @Published private(set) var addons: [StremioAddon] = []
    @Published private(set) var shelves: [InfinityShelf] = []
    @Published private(set) var continueWatching: [InfinityTitle] = []
    @Published var loading = false
    @Published var status = ""

    private var loaded = false
    private let continueKey = "infinity.continue"

    private var fileURL: URL { AppSupport.directory.appendingPathComponent("infinity-addons.json") }

    func prepare() {
        guard !loaded else { return }
        loaded = true
        if let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([StremioAddon].self, from: data) {
            addons = saved
        }
        if addons.isEmpty, ProcessInfo.processInfo.environment["CHUD_TOUR"] == nil {
            addons = [Self.cinemeta]
            persist()
        }
        if let data = UserDefaults.standard.data(forKey: continueKey),
           let saved = try? JSONDecoder().decode([InfinityTitle].self, from: data) {
            continueWatching = saved
        }
    }

    func refresh() async {
        prepare()
        loading = true
        status = ""
        defer { loading = false }
        var next: [InfinityShelf] = []
        if !continueWatching.isEmpty {
            next.append(InfinityShelf(id: "continue", title: "Continue watching", titles: continueWatching))
        }
        if let upcoming = await Self.upcoming() {
            next.append(upcoming)
        }
        for addon in addons where addon.enabled {
            for catalog in addon.catalogs.prefix(4) where catalog.type == "movie" || catalog.type == "series" {
                let titles = await Self.catalog(addon, catalog, search: nil)
                if !titles.isEmpty {
                    next.append(InfinityShelf(id: "\(addon.id)/\(catalog.id)", title: "\(addon.name) · \(catalog.name)", titles: titles))
                }
            }
        }
        shelves = next
        if next.isEmpty { status = "No catalogues yet. Install an addon from Settings." }
    }

    func search(_ query: String) async -> [InfinityTitle] {
        prepare()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return [] }
        var found: [InfinityTitle] = []
        for addon in addons where addon.enabled {
            for catalog in addon.catalogs.prefix(3) where catalog.type == "movie" || catalog.type == "series" {
                let titles = await Self.catalog(addon, catalog, search: trimmed)
                found.append(contentsOf: titles)
            }
        }
        var seen = Set<String>()
        return found.filter { seen.insert($0.id + $0.type).inserted }
    }

    func install(from raw: String) async throws {
        prepare()
        guard let url = Self.manifestURL(raw) else { throw InfinityError("That doesn't look like a manifest link.") }
        let (data, response) = try await URLSession.shared.data(from: url)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else { throw InfinityError("The manifest didn't answer (\(code)).") }
        guard let addon = Self.parseManifest(data, manifestURL: url) else { throw InfinityError("That file isn't an addon manifest.") }
        if let index = addons.firstIndex(where: { $0.id == addon.id }) {
            addons[index] = addon
        } else {
            addons.append(addon)
        }
        persist()
        await refresh()
    }

    func setEnabled(_ id: String, _ enabled: Bool) {
        guard let index = addons.firstIndex(where: { $0.id == id }) else { return }
        addons[index].enabled = enabled
        persist()
    }

    func remove(_ id: String) {
        addons.removeAll { $0.id == id }
        persist()
    }

    func remember(_ title: InfinityTitle) {
        continueWatching.removeAll { $0.id == title.id && $0.type == title.type }
        continueWatching.insert(title, at: 0)
        if continueWatching.count > 24 { continueWatching = Array(continueWatching.prefix(24)) }
        if let data = try? JSONEncoder().encode(continueWatching) {
            UserDefaults.standard.set(data, forKey: continueKey)
        }
    }

    static func meta(_ title: InfinityTitle) async -> (overview: String, episodes: [InfinityEpisode]) {
        guard let url = endpoint(title.transport, "meta", title.type, title.id) else { return (title.overview ?? "", []) }
        guard let root = await json(url), let meta = root["meta"] as? [String: Any] else { return (title.overview ?? "", []) }
        let overview = (meta["description"] as? String) ?? title.overview ?? ""
        let videos = meta["videos"] as? [[String: Any]] ?? []
        let episodes: [InfinityEpisode] = videos.compactMap { video in
            guard let id = video["id"] as? String else { return nil }
            let season = video["season"] as? Int ?? 0
            let episode = video["episode"] as? Int ?? 0
            let name = (video["title"] as? String) ?? (video["name"] as? String) ?? "Episode \(episode)"
            return InfinityEpisode(id: id, title: name, season: season, episode: episode)
        }
        return (overview, episodes)
    }

    static func streams(transport: String, type: String, id: String) async -> [InfinityStream] {
        guard let url = endpoint(transport, "stream", type, id) else { return [] }
        guard let root = await json(url), let rows = root["streams"] as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            let name = (row["name"] as? String) ?? "Stream"
            let title = ((row["title"] as? String) ?? "").replacingOccurrences(of: "\n", with: " · ")
            let headers = ((row["behaviorHints"] as? [String: Any])?["proxyHeaders"] as? [String: Any])?["request"] as? [String: String] ?? [:]
            let subs = (row["subtitles"] as? [[String: Any]] ?? []).compactMap { item -> URL? in
                guard let link = item["url"] as? String else { return nil }
                return URL(string: link)
            }
            if let link = row["url"] as? String, let file = URL(string: link), let scheme = file.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                return InfinityStream(name: name, title: title, url: file, infoHash: nil, headers: headers, subtitles: subs)
            }
            if let hash = (row["infoHash"] as? String)?.lowercased(), hash.count >= 32 {
                return InfinityStream(name: name, title: title.isEmpty ? "Torrent" : title, url: nil, infoHash: hash, headers: headers, subtitles: subs)
            }
            return nil
        }
    }

    static func resolveDebrid(hash: String) async -> URL? {
        if let token = Secrets.get(.realDebrid), let url = await realDebrid(hash: hash, token: token) { return url }
        if let token = Secrets.get(.torbox), let url = await torBox(hash: hash, token: token) { return url }
        return nil
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(addons) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }

    private static let cinemeta = StremioAddon(
        id: "com.linvo.cinemeta",
        name: "Cinemeta",
        version: "3.0.14",
        description: "The built-in catalogue of films and series.",
        transport: "https://v3-cinemeta.strem.io",
        resources: ["catalog", "meta"],
        types: ["movie", "series"],
        catalogs: [
            StremioCatalog(type: "movie", id: "top", name: "Popular films"),
            StremioCatalog(type: "series", id: "top", name: "Popular series"),
        ],
        enabled: true
    )

    static func manifestURL(_ raw: String) -> URL? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if text.lowercased().hasPrefix("stremio://") {
            text = "https://" + text.dropFirst("stremio://".count)
        }
        if !text.contains("://") { text = "https://" + text }
        guard text.lowercased().contains("manifest.json"), let url = URL(string: text) else { return nil }
        return url
    }

    private static func parseManifest(_ data: Data, manifestURL: URL) -> StremioAddon? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = root["id"] as? String, let name = root["name"] as? String else { return nil }
        let resources: [String] = (root["resources"] as? [Any] ?? []).compactMap { item in
            if let name = item as? String { return name }
            return (item as? [String: Any])?["name"] as? String
        }
        let catalogs: [StremioCatalog] = (root["catalogs"] as? [[String: Any]] ?? []).compactMap { row in
            guard let type = row["type"] as? String, let id = row["id"] as? String else { return nil }
            return StremioCatalog(type: type, id: id, name: (row["name"] as? String) ?? id)
        }
        var transport = manifestURL.absoluteString
        if let range = transport.range(of: "/manifest.json") { transport = String(transport[..<range.lowerBound]) }
        transport = transport.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return StremioAddon(
            id: id,
            name: name,
            version: (root["version"] as? String) ?? "",
            description: (root["description"] as? String) ?? "",
            transport: transport,
            resources: resources,
            types: root["types"] as? [String] ?? [],
            catalogs: catalogs,
            enabled: true
        )
    }

    private static func catalog(_ addon: StremioAddon, _ catalog: StremioCatalog, search: String?) async -> [InfinityTitle] {
        var path = "catalog/\(catalog.type)/\(catalog.id)"
        if let search, let encoded = search.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
            path += "/search=\(encoded)"
        }
        guard let url = URL(string: "\(addon.transport)/\(path).json"),
              let root = await json(url),
              let metas = root["metas"] as? [[String: Any]] else { return [] }
        return metas.prefix(30).compactMap { meta in
            guard let id = meta["id"] as? String, let name = meta["name"] as? String else { return nil }
            return InfinityTitle(
                id: id,
                type: (meta["type"] as? String) ?? catalog.type,
                name: name,
                poster: meta["poster"] as? String,
                overview: meta["description"] as? String,
                year: meta["releaseInfo"] as? String,
                transport: addon.transport,
                addonName: addon.name
            )
        }
    }

    private static func upcoming() async -> InfinityShelf? {
        guard let key = Secrets.get(.tmdb) else { return nil }
        async let films = tmdbList("/movie/upcoming", key: key, type: "movie")
        async let shows = tmdbList("/tv/on_the_air", key: key, type: "series")
        let titles = await films + shows
        guard !titles.isEmpty else { return nil }
        return InfinityShelf(id: "upcoming", title: "Upcoming", titles: Array(titles.prefix(24)))
    }

    private static func tmdbList(_ path: String, key: String, type: String) async -> [InfinityTitle] {
        guard let url = URL(string: "\(ServiceURL.tmdb)\(path)?api_key=\(key)") else { return [] }
        guard let root = await json(url), let rows = root["results"] as? [[String: Any]] else { return [] }
        var titles: [InfinityTitle] = []
        for row in rows.prefix(12) {
            guard let tmdbId = row["id"] as? Int else { continue }
            let name = (row["title"] as? String) ?? (row["name"] as? String) ?? "Untitled"
            let posterPath = row["poster_path"] as? String
            let poster = posterPath.map { ServiceURL.tmdbImages + "/w342" + $0 }
            let external = await json(URL(string: "\(ServiceURL.tmdb)/\(type == "movie" ? "movie" : "tv")/\(tmdbId)/external_ids?api_key=\(key)")!)
            let imdb = external?["imdb_id"] as? String
            guard let imdb, imdb.hasPrefix("tt") else { continue }
            titles.append(InfinityTitle(
                id: imdb,
                type: type,
                name: name,
                poster: poster,
                overview: row["overview"] as? String,
                year: String(((row["release_date"] as? String) ?? (row["first_air_date"] as? String) ?? "").prefix(4)),
                transport: "https://v3-cinemeta.strem.io",
                addonName: "Upcoming"
            ))
        }
        return titles
    }

    private static func endpoint(_ transport: String, _ resource: String, _ type: String, _ id: String) -> URL? {
        let encoded = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        return URL(string: "\(transport)/\(resource)/\(type)/\(encoded).json")
    }

    private static func json(_ url: URL?) async -> [String: Any]? {
        guard let url else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 25
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private static func realDebrid(hash: String, token: String) async -> URL? {
        let magnet = "magnet:?xt=urn:btih:\(hash)"
        guard let added = await form("https://api.real-debrid.com/rest/1.0/torrents/addMagnet", token: token, body: "magnet=\(magnet.percent)"),
              let id = added["id"] as? String else { return nil }
        _ = await form("https://api.real-debrid.com/rest/1.0/torrents/selectFiles/\(id)", token: token, body: "files=all")
        for _ in 0..<4 {
            if let info = await get("https://api.real-debrid.com/rest/1.0/torrents/info/\(id)", token: token),
               (info["status"] as? String) == "downloaded",
               let link = (info["links"] as? [String])?.first,
               let unlocked = await form("https://api.real-debrid.com/rest/1.0/unrestrict/link", token: token, body: "link=\(link.percent)"),
               let file = unlocked["download"] as? String,
               let url = URL(string: file) {
                return url
            }
            try? await Task.sleep(nanoseconds: 700_000_000)
        }
        return nil
    }

    private static func torBox(hash: String, token: String) async -> URL? {
        let magnet = "magnet:?xt=urn:btih:\(hash)"
        guard let created = await form("https://api.torbox.app/v1/api/torrents/createtorrent", token: token, body: "magnet=\(magnet.percent)&seed=1") else { return nil }
        let data = created["data"] as? [String: Any]
        let torrentId = (data?["torrent_id"] as? Int) ?? (data?["id"] as? Int) ?? (created["torrent_id"] as? Int)
        guard let torrentId else { return nil }
        let fileId = ((data?["files"] as? [[String: Any]])?.first?["id"] as? Int) ?? 0
        guard let url = URL(string: "https://api.torbox.app/v1/api/torrents/requestdl?token=\(token)&torrent_id=\(torrentId)&file_id=\(fileId)&redirect=false"),
              let root = await json(url) else { return nil }
        let link = (root["data"] as? String) ?? ((root["data"] as? [String: Any])?["link"] as? String) ?? (root["url"] as? String)
        return link.flatMap { URL(string: $0) }
    }

    private static func form(_ link: String, token: String, body: String) async -> [String: Any]? {
        guard let url = URL(string: link) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = body.data(using: .utf8)
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private static func get(_ link: String, token: String) async -> [String: Any]? {
        guard let url = URL(string: link) else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 25
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return await json(request)
    }

    private static func json(_ request: URLRequest) async -> [String: Any]? {
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

private extension String {
    var percent: String { addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? self }
}

struct InfinityShelf: Identifiable {
    var id: String
    var title: String
    var titles: [InfinityTitle]
}

struct InfinityError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

@MainActor
struct InfinityView: View {
    @EnvironmentObject private var playback: PlaybackCenter
    @ObservedObject private var store = InfinityStore.shared
    @State private var query = ""
    @State private var results: [InfinityTitle] = []
    @State private var searching = false
    @State private var selected: InfinityTitle? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                NeonTitle(text: "Infinity", size: 34)
                Spacer()
                Text("Addon catalogues. Separate from Live TV search.")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textMuted)
            }
            HStack(spacing: 10) {
                TextField("Search addons", text: $query)
                    .textFieldStyle(.plain)
                    .font(NeonFont.body(15))
                    .padding(10)
                    .neonPanel()
                    .onSubmit { Task { await runSearch() } }
                Button(searching ? "Searching…" : "Search") { Task { await runSearch() } }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .disabled(searching)
            }
            if let selected {
                InfinityDetail(title: selected, onClose: { self.selected = nil })
            } else if !results.isEmpty {
                posterRow(InfinityShelf(id: "search", title: "Results", titles: results))
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 18) {
                        if store.loading && store.shelves.isEmpty {
                            Text("Loading catalogues…")
                                .font(NeonFont.body(15))
                                .foregroundColor(Neon.textMuted)
                        }
                        if !store.status.isEmpty && store.shelves.isEmpty {
                            Text(store.status)
                                .font(NeonFont.body(15))
                                .foregroundColor(Neon.textSecondary)
                        }
                        ForEach(store.shelves) { shelf in
                            posterRow(shelf)
                        }
                    }
                }
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task { await store.refresh() }
    }

    private func posterRow(_ shelf: InfinityShelf) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(shelf.title)
                .font(NeonFont.body(16, bold: true))
                .foregroundColor(Neon.cyan)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(shelf.titles) { title in
                        Button { selected = title } label: { poster(title) }
                            .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func poster(_ title: InfinityTitle) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                Neon.surface
                if let poster = title.poster, let url = URL(string: poster) {
                    AsyncImage(url: url) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        Color.clear
                    }
                }
            }
            .frame(width: 120, height: 180)
            .clipped()
            .neonPanel()
            Text(title.name)
                .font(NeonFont.body(12, bold: true))
                .foregroundColor(Neon.text)
                .lineLimit(2)
                .frame(width: 120, alignment: .leading)
        }
    }

    private func runSearch() async {
        searching = true
        defer { searching = false }
        selected = nil
        results = await store.search(query)
    }
}

@MainActor
private struct InfinityDetail: View {
    @EnvironmentObject private var playback: PlaybackCenter
    @ObservedObject private var store = InfinityStore.shared
    let title: InfinityTitle
    let onClose: () -> Void
    @State private var overview = ""
    @State private var episodes: [InfinityEpisode] = []
    @State private var streams: [InfinityStream] = []
    @State private var streamFor = ""
    @State private var message = "Loading…"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Back") { onClose() }.buttonStyle(NeonButtonStyle())
                Text(title.name).font(NeonFont.display(22)).foregroundColor(Neon.text).lineLimit(1)
                Spacer()
            }
            Text(overview.isEmpty ? (title.overview ?? "") : overview)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .lineLimit(4)
            if !episodes.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(episodes.prefix(40)) { episode in
                            Button("S\(episode.season)E\(episode.episode)") { Task { await loadStreams(id: episode.id) } }
                                .buttonStyle(NeonButtonStyle(prominent: streamFor == episode.id))
                        }
                    }
                }
            }
            Text(message).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(streams) { stream in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(stream.name).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text).lineLimit(1)
                                Text(stream.title).font(NeonFont.body(12)).foregroundColor(Neon.textSecondary).lineLimit(2)
                            }
                            Spacer()
                            Button(stream.url == nil ? "Debrid" : "Play") { Task { await play(stream) } }
                                .buttonStyle(NeonButtonStyle(prominent: true))
                        }
                        .padding(10)
                        .neonPanel()
                    }
                }
            }
        }
        .task { await open() }
    }

    private func open() async {
        let meta = await InfinityStore.meta(title)
        overview = meta.overview
        episodes = meta.episodes
        await loadStreams(id: title.type == "series" ? (episodes.first?.id ?? title.id) : title.id)
    }

    private func loadStreams(id: String) async {
        streamFor = id
        message = "Looking for streams…"
        var found = await InfinityStore.streams(transport: title.transport, type: title.type, id: id)
        if found.isEmpty {
            for addon in store.addons where addon.enabled && addon.transport != title.transport && addon.supports("stream") {
                found.append(contentsOf: await InfinityStore.streams(transport: addon.transport, type: title.type, id: id))
            }
        }
        streams = found
        message = found.isEmpty ? "No streams from the installed addons. Add one in Settings → Addons." : "\(found.count) streams"
    }

    private func play(_ stream: InfinityStream) async {
        message = stream.url == nil ? "Asking Real-Debrid and TorBox…" : "Opening…"
        let url: URL?
        if let direct = stream.url {
            url = direct
        } else if let hash = stream.infoHash {
            url = await InfinityStore.resolveDebrid(hash: hash)
        } else {
            url = nil
        }
        guard let url else {
            message = "That torrent isn't cached on Real-Debrid or TorBox."
            return
        }
        store.remember(title)
        var request = PlaybackRequest(
            kind: title.type == "series" ? .episode : .movie,
            title: title.name,
            subtitle: stream.name,
            url: url
        )
        request.headers = stream.headers
        request.subtitleURL = stream.subtitles.first
        playback.play(request)
        message = "Playing"
    }
}

@MainActor
struct InfinityAddonsSettings: View {
    @ObservedObject private var store = InfinityStore.shared
    @State private var link = ""
    @State private var working = false
    @State private var failure: String? = nil
    @State private var success: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            NeonTitle(text: "Addons", size: 28)
            Text("Paste a manifest.json link, or a stremio:// link. Installed addons fill Infinity. This does not change your Xtream login.")
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
            TextField("https://example.com/manifest.json", text: $link)
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(10)
                .neonPanel()
                .onSubmit { install() }
            Button(working ? "Installing…" : "Install") { install() }
                .buttonStyle(NeonButtonStyle(prominent: true))
                .disabled(working || InfinityStore.manifestURL(link) == nil)
            if let failure {
                Text(failure).font(NeonFont.body(13)).foregroundColor(Neon.danger)
            }
            if let success {
                Text(success).font(NeonFont.body(13)).foregroundColor(Neon.positive)
            }
            if store.addons.isEmpty {
                Text("No addons yet.")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textMuted)
            }
            ForEach(store.addons) { addon in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(addon.name).font(NeonFont.body(16, bold: true)).foregroundColor(Neon.text)
                            Text(addon.description).font(NeonFont.body(12)).foregroundColor(Neon.textSecondary).lineLimit(2)
                            Text(addon.transport).font(NeonFont.body(11)).foregroundColor(Neon.textMuted).lineLimit(1)
                        }
                        Spacer()
                        Toggle("On", isOn: Binding(
                            get: { addon.enabled },
                            set: { store.setEnabled(addon.id, $0) }
                        ))
                        .toggleStyle(.switch)
                        .labelsHidden()
                        Button("Remove") { store.remove(addon.id) }
                            .buttonStyle(NeonButtonStyle())
                    }
                }
                .padding(14)
                .neonPanel(highlighted: addon.enabled)
            }
        }
        .onAppear { store.prepare() }
    }

    private func install() {
        guard !working else { return }
        working = true
        failure = nil
        success = nil
        let entered = link
        Task {
            do {
                try await store.install(from: entered)
                link = ""
                success = "Installed. Infinity will use it on the next visit."
            } catch {
                failure = error.localizedDescription
            }
            working = false
        }
    }
}
