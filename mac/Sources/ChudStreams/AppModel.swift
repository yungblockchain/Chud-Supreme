import AppKit
import Foundation
import SwiftUI

/// What the player should show. Live requests carry the channel list so up/down can flip channels.
struct PlaybackRequest: Identifiable, Equatable {
    enum Kind { case live, movie, episode, catchUp }

    let id = UUID()
    var kind: Kind
    var title: String
    var subtitle: String?
    var url: URL
    var artwork: String? = nil
    /// The channel, film or series this is.
    var item: MediaItem? = nil
    var episode: Episode? = nil
    /// Where to save the position (films and episodes); nil for live TV.
    var resumeKey: String? = nil
    var startAt: Double = 0
    var channelList: [MediaItem] = []
    var channelIndex: Int? = nil
    /// Following episodes, for "Up next".
    var upNext: [Episode] = []
    /// The history entry this playback updates.
    var historyId: String? = nil
    /// Extra HTTP headers some addon streams require (Referer, User-Agent).
    var headers: [String: String] = [:]
    /// An optional subtitle file to load with the stream.
    var subtitleURL: URL? = nil

    var live: Bool { kind == .live }
    var streamId: Int? { kind == .live ? item?.streamId : nil }

    static func == (a: PlaybackRequest, b: PlaybackRequest) -> Bool { a.id == b.id }
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var sources: [Source] = []
    @Published var activeSourceId: String? {
        didSet { defaults.set(activeSourceId, forKey: Keys.activeSource) }
    }
    @Published private(set) var accounts: [String: AccountInfo] = [:]
    /// Now and next, by item id.
    @Published private(set) var epg: [String: [Programme]] = [:]
    /// Full listings for the timeline guide, by item id. Absent means not loaded yet.
    @Published private(set) var listings: [String: [Programme]] = [:]
    @Published var notice: String? = nil
    @Published var section: AppSection = .home
    /// The film or series whose page is open.
    @Published var details: MediaItem? = nil
    /// The actor or crew member whose page is open (TMDB person id).
    @Published var person: Int? = nil
    /// Set when the app should jump to Search with this text.
    @Published var pendingSearch: String? = nil

    let userData = UserData()
    let playback = PlaybackCenter()
    private var catalogs: [String: CatalogStore] = [:]
    private let defaults = UserDefaults.standard
    private var epgFetchedAt: [String: Date] = [:]
    private var epgInFlight = Set<String>()
    private var listingFetchedAt: [String: Date] = [:]
    private var listingInFlight = Set<String>()
    private var listingOrder: [String] = []

    /// Formats Apple's player handles; anything else needs the built-in player or VLC.
    static let nativeFormats: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    init() {
        sources = AppModel.loadSources()
        activeSourceId = defaults.string(forKey: Keys.activeSource) ?? sources.first?.id
        if let active = activeSourceId, !sources.contains(where: { $0.id == active }) {
            activeSourceId = sources.first?.id
        }
        playback.model = self
        for source in sources { catalog(for: source.id)?.start() }
    }

    // MARK: Sources

    var activeSource: Source? { sources.first { $0.id == activeSourceId } ?? sources.first }
    var catalog: CatalogStore? { activeSource.flatMap { catalog(for: $0.id) } }
    var client: XtreamClient? { activeSource?.client }
    var credentials: XtreamCredentials? { activeSource?.credentials }
    var account: AccountInfo? { activeSource.flatMap { accounts[$0.id] } }
    var hasSources: Bool { !sources.isEmpty }

    func catalog(for sourceId: String) -> CatalogStore? {
        if let existing = catalogs[sourceId] { return existing }
        guard let source = sources.first(where: { $0.id == sourceId }) else { return nil }
        let store = CatalogStore(source: source)
        catalogs[sourceId] = store
        return store
    }

    func source(for item: MediaItem) -> Source? {
        sources.first { $0.id == item.source } ?? (item.sourceId == nil ? sources.first { $0.kind == .xtream } : nil)
    }

    func client(for item: MediaItem) -> XtreamClient? { source(for: item)?.client }

    private static func loadSources() -> [Source] {
        let store = UserDefaults.standard
        var list: [Source] = []
        if let data = store.data(forKey: Keys.sources), let saved = try? JSONDecoder().decode([Source].self, from: data) {
            list = saved
        }
        // The single Xtream login from earlier versions becomes the "main" source.
        if !list.contains(where: { $0.id == Source.mainId }),
           let server = store.string(forKey: "server"),
           let username = store.string(forKey: "username"),
           let password = Keychain.load(account: "\(username)@\(server)") {
            let source = Source(id: Source.mainId, kind: .xtream, name: URL(string: server)?.host ?? "My provider", server: server, username: username)
            Keychain.write(password, account: source.keychainAccount)
            list.insert(source, at: 0)
        }
        // A fresh install opens already signed in, and a host saved without www is corrected.
        // The GitHub screenshot tour leaves this off so it can still walk through first run.
        // A login you change in Settings is left as you saved it.
        if ProcessInfo.processInfo.environment["CHUD_TOUR"] == nil {
            BundledLogin.apply(to: &list)
        }
        if let data = try? JSONEncoder().encode(list) { store.set(data, forKey: Keys.sources) }
        return list
    }

    private func saveSources() {
        if let data = try? JSONEncoder().encode(sources) { defaults.set(data, forKey: Keys.sources) }
    }

    /// Checks an Xtream login and adds it.
    func addXtream(server rawServer: String, username rawUser: String, password rawPassword: String, name: String = "") async throws {
        let fromLink = XtreamClient.credentials(fromLink: rawServer)
        guard let server = fromLink?.server ?? XtreamClient.normalizeServer(rawServer) else { throw XtreamError.badServer }
        let username = fromLink?.username ?? rawUser.trimmingCharacters(in: .whitespacesAndNewlines)
        let password = fromLink?.password ?? rawPassword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !username.isEmpty, !password.isEmpty else { throw XtreamError.missingLogin }
        let candidate = XtreamCredentials(server: server, username: username, password: password)
        let info = try await XtreamClient(credentials: candidate).accountInfo()
        guard info.isActive else { throw XtreamError.inactive(info.status ?? "inactive") }
        let existing = sources.first { $0.kind == .xtream && $0.server == server && $0.username == username }
        let id = existing?.id ?? (sources.contains { $0.id == Source.mainId } ? "xt-\(M3UParser.stableId(server + username))" : Source.mainId)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = Source(id: id, kind: .xtream, name: trimmedName.isEmpty ? (URL(string: server)?.host ?? "Xtream") : trimmedName,
                            server: server, username: username)
        Keychain.write(password, account: source.keychainAccount)
        install(source)
        accounts[id] = info
    }

    /// Replaces the server, username or password of a source you already have. An empty password
    /// keeps the one that is saved. Favourites stay, and the channel list is loaded again.
    func updateXtream(id: String, server rawServer: String, username rawUser: String, password rawPassword: String) async throws {
        guard let index = sources.firstIndex(where: { $0.id == id && $0.kind == .xtream }) else { return }
        let existing = sources[index]
        let fromLink = XtreamClient.credentials(fromLink: rawServer)
        guard let server = fromLink?.server ?? XtreamClient.normalizeServer(rawServer) else { throw XtreamError.badServer }
        let username = fromLink?.username ?? rawUser.trimmingCharacters(in: .whitespacesAndNewlines)
        let typed = (fromLink?.password ?? rawPassword).trimmingCharacters(in: .whitespacesAndNewlines)
        let password = typed.isEmpty ? (existing.password ?? "") : typed
        guard !username.isEmpty, !password.isEmpty else { throw XtreamError.missingLogin }
        let candidate = XtreamCredentials(server: server, username: username, password: password)
        let info = try await XtreamClient(credentials: candidate).accountInfo()
        guard info.isActive else { throw XtreamError.inactive(info.status ?? "inactive") }
        var source = existing
        source.server = server
        source.username = username
        if source.name == URL(string: existing.server ?? "")?.host {
            source.name = URL(string: server)?.host ?? source.name
        }
        Keychain.write(password, account: source.keychainAccount)
        sources[index] = source
        catalogs[id] = nil
        CatalogCache.remove(source: id)
        saveSources()
        accounts[id] = info
        catalog(for: id)?.start()
    }

    /// Adds an M3U playlist (and optional XMLTV guide). The playlist is downloaded to check it.
    func addM3U(url rawURL: String, guide rawGuide: String, name: String = "") async throws {
        let link = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        // A provider's get.php link is really an Xtream login, which also brings films, series and catch-up.
        if XtreamClient.credentials(fromLink: link) != nil {
            try await addXtream(server: link, username: "", password: "", name: name)
            return
        }
        guard let url = URL(string: link), let scheme = url.scheme?.lowercased(), ["http", "https", "file"].contains(scheme) else {
            throw XtreamError.other("the playlist", "enter the full address, starting with http:// or https://.")
        }
        let guideLink = rawGuide.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = Source(id: "m3u-\(M3UParser.stableId(link))", kind: .m3u,
                            name: trimmedName.isEmpty ? (url.host ?? "Playlist") : trimmedName,
                            playlistURL: link, epgURL: guideLink.isEmpty ? nil : guideLink)
        install(source)
        if let store = catalog(for: source.id) {
            await store.refresh(.live)
            if case .failed(let message) = store.phases[.live] {
                removeSource(source.id)
                throw XtreamError.other(url.host ?? "the playlist", message)
            }
            await store.loadGuide()
        }
    }

    private func install(_ source: Source) {
        if let index = sources.firstIndex(where: { $0.id == source.id }) {
            sources[index] = source
            catalogs[source.id] = nil
        } else {
            sources.append(source)
        }
        saveSources()
        activeSourceId = source.id
        catalog(for: source.id)?.start()
    }

    func removeSource(_ id: String) {
        guard let source = sources.first(where: { $0.id == id }) else { return }
        Keychain.remove(account: source.keychainAccount)
        if id == Source.mainId, let server = source.server, let username = source.username {
            Keychain.delete(account: "\(username)@\(server)")
            defaults.removeObject(forKey: "server")
            defaults.removeObject(forKey: "username")
        }
        CatalogCache.remove(source: id)
        sources.removeAll { $0.id == id }
        catalogs[id] = nil
        accounts[id] = nil
        saveSources()
        if activeSourceId == id { activeSourceId = sources.first?.id }
        if playback.current?.item?.source == id { playback.stop() }
    }

    func renameSource(_ id: String, to name: String) {
        guard let index = sources.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        sources[index].name = trimmed
        saveSources()
    }

    func setGuide(_ id: String, url: String) {
        guard let index = sources.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        sources[index].epgURL = trimmed.isEmpty ? nil : trimmed
        saveSources()
        catalogs[id] = nil
        catalog(for: id)?.start()
    }

    func refreshAccount() async {
        for source in sources where source.kind == .xtream {
            if let info = try? await source.client?.accountInfo() { accounts[source.id] = info }
        }
    }

    // MARK: Favourites (compatibility with the original screens)

    func isFavorite(_ item: MediaItem) -> Bool { userData.isFavourite(item) }
    func toggleFavorite(_ item: MediaItem) {
        userData.toggleFavourite(item)
        objectWillChange.send()
    }
    var favorites: [MediaItem] { userData.favourites.items }

    // MARK: Resume

    func resumeKey(for item: MediaItem) -> String {
        let base = "movie-\(item.streamId)"
        return item.source == Source.mainId ? base : "\(item.source)|\(base)"
    }

    func resumeKey(for episode: Episode, of series: MediaItem) -> String {
        let base = "episode-\(episode.id)"
        return series.source == Source.mainId ? base : "\(series.source)|\(base)"
    }

    /// Saved position in seconds, or 0 if there's nothing worth resuming.
    func resumePosition(for key: String) -> Double {
        defaults.double(forKey: Keys.resumePrefix + key)
    }

    func saveResume(key: String, seconds: Double, duration: Double) {
        // Forget the position near the start or once it's effectively finished.
        if seconds < 30 || seconds > duration - 60 {
            defaults.removeObject(forKey: Keys.resumePrefix + key)
        } else {
            defaults.set(seconds, forKey: Keys.resumePrefix + key)
        }
    }

    func lastEpisode(forSeries series: MediaItem) -> Episode? {
        defaults.data(forKey: lastEpisodeKey(series))
            .flatMap { try? JSONDecoder().decode(Episode.self, from: $0) }
    }

    private func lastEpisodeKey(_ series: MediaItem) -> String {
        let base = Keys.lastEpisodePrefix + String(series.streamId)
        return series.source == Source.mainId ? base : "\(series.source)|\(base)"
    }

    // MARK: Programme guide

    func nowNext(for item: MediaItem) -> [Programme] {
        if let source = source(for: item), source.kind == .m3u || source.epgURL != nil,
           let store = catalog(for: source.id) {
            let list = store.xmltvListing(for: item)
            if !list.isEmpty {
                let now = Date()
                return Array(list.filter { $0.end > now }.prefix(4))
            }
        }
        return epg[item.id] ?? []
    }

    func loadEPG(for item: MediaItem) async {
        guard item.kind == .live, let client = client(for: item) else { return }
        let key = item.id
        if let fetched = epgFetchedAt[key], Date().timeIntervalSince(fetched) < 600,
           epg[key]?.first.map({ $0.end > Date() }) ?? true {
            return
        }
        guard !epgInFlight.contains(key) else { return }
        epgInFlight.insert(key)
        defer { epgInFlight.remove(key) }
        let listings = (try? await client.shortEPG(streamId: item.streamId)) ?? []
        epgFetchedAt[key] = Date()
        epg[key] = listings.filter { $0.end > Date() }
    }

    /// One channel's full listing for the guide. Xtream: fetched as its row scrolls into view and
    /// cached for 30 minutes (the 300 most recent channels). M3U: from the XMLTV guide.
    func listing(for item: MediaItem) -> [Programme]? {
        if let source = source(for: item), source.kind == .m3u || source.epgURL != nil,
           let store = catalog(for: source.id) {
            let list = store.xmltvListing(for: item)
            if !list.isEmpty || source.kind == .m3u { return list }
        }
        return listings[item.id]
    }

    func loadListing(for item: MediaItem) async {
        guard let source = source(for: item), source.kind == .xtream, let client = source.client else { return }
        let key = item.id
        if let fetched = listingFetchedAt[key], Date().timeIntervalSince(fetched) < 1800 { return }
        guard !listingInFlight.contains(key) else { return }
        listingInFlight.insert(key)
        defer { listingInFlight.remove(key) }
        guard let all = try? await client.fullEPG(streamId: item.streamId) else { return }
        let now = Date()
        listings[key] = all.filter {
            $0.end > now.addingTimeInterval(-26 * 3600) && $0.start < now.addingTimeInterval(26 * 3600)
        }
        listingFetchedAt[key] = now
        listingOrder.removeAll { $0 == key }
        listingOrder.append(key)
        while listingOrder.count > 300 {
            let oldest = listingOrder.removeFirst()
            listingFetchedAt[oldest] = nil
            listings[oldest] = nil
        }
    }

    // MARK: Stream addresses

    func liveURL(for item: MediaItem, hls: Bool) -> URL? {
        if let direct = item.url { return URL(string: direct) }
        return client(for: item)?.liveURL(item, hls: hls)
    }

    // MARK: Playback

    /// Films and series open their page; channels start playing.
    func open(_ item: MediaItem, in list: [MediaItem] = []) {
        switch item.kind {
        case .live: playLive(item, in: list.isEmpty ? [item] : list)
        case .movie, .series:
            withAnimation(Motion.page) { details = item }
        }
    }

    /// Replays an archived programme.
    func playCatchUp(_ programme: Programme, on channel: MediaItem) {
        guard let client = client(for: channel), let url = client.catchUpURL(streamId: channel.streamId, programme: programme) else {
            notice = "This programme can't be replayed: the provider didn't send its start time."
            return
        }
        let request = PlaybackRequest(kind: .catchUp, title: programme.title,
                                      subtitle: "\(channel.name)  ·  \(programme.start.formatted(date: .abbreviated, time: .shortened))",
                                      url: url, artwork: channel.icon, item: channel)
        playback.play(request)
    }

    func playLive(_ item: MediaItem, in list: [MediaItem]) {
        let settings = PlaybackSettings.current
        // The built-in player takes the provider's native MPEG-TS; Apple's player needs HLS.
        let formats = accounts[item.source]?.allowedFormats ?? []
        let wantsHLS = settings.engine == .apple && (formats.isEmpty || formats.contains("m3u8"))
        guard let url = liveURL(for: item, hls: wantsHLS) else { return }
        let request = PlaybackRequest(
            kind: .live,
            title: item.name,
            subtitle: nil,
            url: url,
            artwork: item.icon,
            item: item,
            channelList: list,
            channelIndex: list.firstIndex(of: item),
            historyId: item.id
        )
        userData.record(HistoryEntry(id: item.id, item: item, title: item.name, subtitle: "Live TV", image: item.icon))
        playback.play(request)
        Task { await loadEPG(for: item) }
    }

    /// Up/down in the player: step through the list the channel was opened from.
    func zap(_ step: Int) {
        guard let current = playback.current, current.live, let index = current.channelIndex else { return }
        let count = current.channelList.count
        guard count > 1 else { return }
        let next = ((index + step) % count + count) % count
        playLive(current.channelList[next], in: current.channelList)
    }

    func playMovie(_ item: MediaItem, containerExtension: String?, fromStart: Bool, artwork: String? = nil) {
        let url: URL?
        if let direct = item.url {
            url = URL(string: direct)
        } else {
            url = client(for: item)?.movieURL(item, containerExtension: containerExtension)
        }
        guard let url else { return }
        let key = resumeKey(for: item)
        let settings = PlaybackSettings.current
        let start = fromStart || !settings.resume ? 0 : resumePosition(for: key)
        let image = artwork ?? item.icon
        userData.record(HistoryEntry(id: item.id, item: item, title: item.cleanName, subtitle: "Film", image: image,
                                     position: start, duration: userData.history.first { $0.id == item.id }?.duration ?? 0))
        playback.play(PlaybackRequest(kind: .movie, title: item.cleanName, subtitle: nil, url: url, artwork: image,
                                      item: item, resumeKey: key, startAt: start, historyId: item.id))
    }

    func playEpisode(_ episode: Episode, of series: MediaItem, fromStart: Bool, upNext: [Episode] = [], artwork: String? = nil) {
        guard let client = client(for: series), let url = client.episodeURL(episode) else { return }
        if let data = try? JSONEncoder().encode(episode) {
            defaults.set(data, forKey: lastEpisodeKey(series))
        }
        let label = "S\(episode.season) E\(episode.number ?? "?")"
        let key = resumeKey(for: episode, of: series)
        let settings = PlaybackSettings.current
        let start = fromStart || !settings.resume ? 0 : resumePosition(for: key)
        let image = artwork ?? episode.image ?? series.icon
        let historyId = "series|\(series.id)"
        userData.record(HistoryEntry(id: historyId, item: series, episode: episode, title: series.cleanName,
                                     subtitle: "\(label) · \(episode.title)", image: series.icon ?? image, position: start))
        playback.play(PlaybackRequest(kind: .episode, title: episode.title, subtitle: "\(series.cleanName)  \(label)", url: url,
                                      artwork: image, item: series, episode: episode, resumeKey: key, startAt: start,
                                      upNext: upNext, historyId: historyId))
    }

    /// Continue watching: pick up where it stopped.
    func resume(_ entry: HistoryEntry) {
        switch entry.item.kind {
        case .live:
            let list = catalog(for: entry.item.source)?.all(.live) ?? []
            let category = list.filter { $0.categoryId == entry.item.categoryId }
            playLive(entry.item, in: category.isEmpty ? [entry.item] : category)
        case .movie:
            playMovie(entry.item, containerExtension: entry.item.containerExtension, fromStart: false, artwork: entry.image)
        case .series:
            if let episode = entry.episode {
                Task {
                    let following = await upcomingEpisodes(after: episode, of: entry.item)
                    playEpisode(episode, of: entry.item, fromStart: false, upNext: following)
                }
            } else {
                withAnimation(Motion.page) { details = entry.item }
            }
        }
    }

    /// Episodes after this one, in order, for Up next.
    func upcomingEpisodes(after episode: Episode, of series: MediaItem) async -> [Episode] {
        guard let client = client(for: series), let info = try? await client.seriesInfo(series.streamId) else { return [] }
        let all = info.seasons.flatMap { $0.episodes }
        guard let index = all.firstIndex(where: { $0.id == episode.id }) else { return [] }
        return Array(all.dropFirst(index + 1).prefix(20))
    }

    func stop() {
        playback.stop()
    }

    var nowPlaying: PlaybackRequest? { playback.current }

    /// Hands a stream to VLC if it's installed.
    func openExternally(_ url: URL) {
        let workspace = NSWorkspace.shared
        if let vlc = workspace.urlForApplication(withBundleIdentifier: "org.videolan.vlc") {
            workspace.open([url], withApplicationAt: vlc, configuration: NSWorkspace.OpenConfiguration()) { _, _ in }
            notice = "Opened in VLC."
        } else {
            notice = "VLC isn't installed. Get it free from videolan.org, or switch to the built-in player in Settings."
            if let site = URL(string: "https://www.videolan.org/vlc/") { workspace.open(site) }
        }
    }

    private enum Keys {
        static let sources = "sources.v1"
        static let activeSource = "sources.active"
        static let resumePrefix = "resume."
        static let lastEpisodePrefix = "lastEpisode."
    }
}

/// Shared animation timings, so the whole app moves the same way.
enum Motion {
    /// The screenshot tour runs on a Mac with no real display, where animations never finish,
    /// so it runs without them.
    private static let still = ProcessInfo.processInfo.environment["CHUD_TOUR"] != nil

    static let page: Animation? = still ? nil : .spring(response: 0.42, dampingFraction: 0.86)
    static let quick: Animation? = still ? nil : .spring(response: 0.26, dampingFraction: 0.9)
    static let hover: Animation? = still ? nil : .easeOut(duration: 0.14)
    static let fade: Animation? = still ? nil : .easeInOut(duration: 0.22)
}
