import SwiftUI

// Global search across a source's full catalogue: every channel, film and series, matched as you
// type (capitals and accents ignored), optionally across all of the viewer's sources. Recent
// searches are kept on this Mac.

private enum SearchFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case live = "Live TV"
    case movie = "Films"
    case series = "Series"

    var id: String { rawValue }

    init(kind: ContentKind) {
        switch kind {
        case .live: self = .live
        case .movie: self = .movie
        case .series: self = .series
        }
    }

    var kinds: [ContentKind] {
        switch self {
        case .all: return [.live, .movie, .series]
        case .live: return [.live]
        case .movie: return [.movie]
        case .series: return [.series]
        }
    }

    var symbol: String {
        switch self {
        case .all: return "square.grid.2x2"
        case .live: return "tv"
        case .movie: return "film"
        case .series: return "square.stack.3d.up"
        }
    }
}

/// Everything a search depends on; a change restarts it.
private struct SearchRequest: Hashable {
    var text: String
    var filter: SearchFilter
    var allSources: Bool
    var sourceId: String
    var version: Int
}

private enum SearchRecents {
    static let key = "search.recent"
    static let limit = 10

    static func load() -> [String] {
        UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    static func save(_ list: [String]) {
        UserDefaults.standard.set(list, forKey: key)
    }
}

private enum SearchText {
    static func noun(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "channels"
        case .movie: return "films"
        case .series: return "series"
        }
    }

    static func count(_ value: Int, _ kind: ContentKind) -> String {
        switch kind {
        case .live: return value == 1 ? "1 channel" : "\(formatCount(value)) channels"
        case .movie: return value == 1 ? "1 film" : "\(formatCount(value)) films"
        case .series: return "\(formatCount(value)) series"
        }
    }

    /// "channels, films and series"
    static func list(_ kinds: [ContentKind]) -> String {
        let nouns = kinds.map { noun($0) }
        guard nouns.count > 1, let last = nouns.last else { return nouns.first ?? "" }
        return nouns.dropLast().joined(separator: ", ") + " and " + last
    }
}

// MARK: - Screen

@MainActor
struct SearchView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let catalog = model.catalog {
            SearchScreen(catalog: catalog)
        } else {
            EmptyState(
                symbol: "magnifyingglass",
                title: "Nothing to search yet",
                message: "Add an Xtream login or an M3U playlist in Settings, and every channel, film and series in it becomes searchable here.",
                actionTitle: "Open Settings",
                action: { model.go(.settings) }
            )
        }
    }
}

@MainActor
private struct SearchScreen: View {
    @EnvironmentObject private var model: AppModel
    let catalog: CatalogStore
    @AppStorage("search.allSources") private var allSources = false
    @State private var query = ""
    @State private var filter: SearchFilter = .all
    @State private var results: [MediaItem] = []
    @State private var searchedText = ""
    @State private var searching = false
    @State private var hitLimit = false
    @State private var catalogVersion = 0
    @State private var recent: [String] = SearchRecents.load()
    @FocusState private var fieldFocused: Bool

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var searchAll: Bool { allSources && model.sources.count > 1 }

    private var request: SearchRequest {
        SearchRequest(text: trimmed, filter: filter, allSources: searchAll, sourceId: catalog.source.id, version: catalogVersion)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            searchField
            filterRow
            SearchCatalogNotes(catalog: catalog, kinds: filter.kinds)
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .padding(.top, 28)
        .background(SearchCatalogWatcher(catalog: catalog, version: $catalogVersion))
        .task(id: request) { await run(request) }
        .task { await focusField() }
        .onAppear { consumePending() }
        .onChange(of: model.pendingSearch) { _ in consumePending() }
    }

    // MARK: Pieces

    private var header: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                NeonTitle(text: "Search")
                Text(scopeText)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            if model.sources.count > 1 {
                Toggle(isOn: $allSources) {
                    Text("Search all sources")
                        .font(NeonFont.body(13, bold: true))
                        .foregroundColor(Neon.text)
                }
                .toggleStyle(.switch)
                .tint(Neon.cyan)
                .help("Look through every account and playlist you've added, not just the one picked in the sidebar")
            }
        }
        .padding(.horizontal, 28)
    }

    private var scopeText: String {
        if searchAll { return "Across all \(model.sources.count) of your sources" }
        var parts: [String] = ["In \(catalog.source.name)"]
        for kind in catalog.kinds where catalog.hasFullList(kind) {
            parts.append(SearchText.count(catalog.count(kind), kind))
        }
        return parts.joined(separator: " · ")
    }

    private var searchField: some View {
        HStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(fieldFocused ? Neon.cyan : Neon.textMuted)
            TextField("Channels, films and series…", text: $query)
                .textFieldStyle(.plain)
                .font(NeonFont.body(22, bold: true))
                .foregroundColor(Neon.text)
                .focused($fieldFocused)
                .onSubmit { remember(trimmed) }
                .onExitCommand { query = "" }
            if searching {
                ProgressView()
                    .controlSize(.small)
            }
            if !query.isEmpty {
                Button { clearQuery() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 17))
                        .foregroundColor(Neon.textMuted)
                }
                .buttonStyle(.plain)
                .help("Clear the search")
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .neonPanel(highlighted: fieldFocused)
        .animation(Motion.hover, value: fieldFocused)
        .padding(.horizontal, 28)
    }

    private var filterRow: some View {
        HStack(spacing: 10) {
            ForEach(SearchFilter.allCases) { option in
                SearchChip(title: option.rawValue, symbol: option.symbol, count: chipCount(option), selected: filter == option) {
                    withAnimation(Motion.quick) { filter = option }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 28)
    }

    @ViewBuilder
    private var content: some View {
        if trimmed.isEmpty {
            SearchIdleView(
                recent: recent,
                onPick: { text in pick(text) },
                onRemove: { text in forget(text) },
                onClear: { clearRecent() }
            )
            .transition(.opacity)
        } else if results.isEmpty && !searching && searchedText == trimmed {
            EmptyState(symbol: "magnifyingglass", title: "No matches for “\(trimmed)”", message: noMatchMessage)
                .transition(.opacity)
        } else {
            SearchResultsView(
                results: results,
                query: searchedText,
                filter: filter,
                badges: badges,
                hitLimit: hitLimit,
                onOpen: { item, list in open(item, in: list) },
                onShowAll: { kind in showAll(kind) }
            )
            .opacity(searching ? 0.6 : 1)
            .animation(Motion.fade, value: searching)
        }
    }

    private var noMatchMessage: String {
        if !searchAll && filter == .series && !catalog.kinds.contains(.series) {
            return "Playlists don't have a Series section, so try All or Films instead."
        }
        var message = "Nothing matches every word. Try fewer words or a different spelling"
        if filter != .all { message += ", or search All rather than just \(filter.rawValue)" }
        if model.sources.count > 1 && !searchAll { message += ", or switch on Search all sources" }
        return message + "."
    }

    /// Source names for poster badges when several sources are searched.
    private var badges: [String: String] {
        guard searchAll else { return [:] }
        var names: [String: String] = [:]
        for source in model.sources { names[source.id] = String(source.name.prefix(16)) }
        return names
    }

    private func chipCount(_ option: SearchFilter) -> Int? {
        guard !searchedText.isEmpty, !searching else { return nil }
        if option == filter { return results.count }
        guard filter == .all else { return nil }
        return results.filter { option.kinds.contains($0.kind) }.count
    }

    // MARK: Searching

    private func run(_ request: SearchRequest) async {
        guard !request.text.isEmpty else {
            results = []
            searchedText = ""
            hitLimit = false
            searching = false
            return
        }
        searching = true
        // Debounce: a new keystroke cancels this task before the search starts.
        try? await Task.sleep(nanoseconds: 250_000_000)
        guard !Task.isCancelled else { return }
        let stores: [CatalogStore]
        if request.allSources {
            stores = model.sources.compactMap { model.catalog(for: $0.id) }
        } else {
            stores = [catalog]
        }
        var found: [MediaItem] = []
        var limited = false
        for store in stores {
            let kinds = request.filter.kinds.filter { store.kinds.contains($0) }
            if kinds.isEmpty { continue }
            let batch = await store.search(request.text, kinds: kinds, limit: 200)
            if Task.isCancelled { return }
            if batch.count >= 200 { limited = true }
            found.append(contentsOf: batch)
        }
        withAnimation(Motion.quick) {
            results = found
            searchedText = request.text
            hitLimit = limited
            searching = false
        }
    }

    private func focusField() async {
        try? await Task.sleep(nanoseconds: 150_000_000)
        fieldFocused = true
    }

    /// Another screen asked for a search ("Search for similar").
    private func consumePending() {
        guard let pending = model.pendingSearch else { return }
        model.pendingSearch = nil
        query = pending
        filter = .all
        fieldFocused = true
    }

    private func clearQuery() {
        query = ""
        fieldFocused = true
    }

    private func showAll(_ kind: ContentKind) {
        withAnimation(Motion.quick) { filter = SearchFilter(kind: kind) }
    }

    private func open(_ item: MediaItem, in list: [MediaItem]) {
        remember(searchedText)
        if item.kind == .live {
            model.playLive(item, in: list)
        } else {
            model.open(item)
        }
    }

    // MARK: Recent searches

    private func pick(_ text: String) {
        query = text
        remember(text)
    }

    private func remember(_ text: String) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.count >= 2 else { return }
        var list = recent.filter { $0.caseInsensitiveCompare(value) != .orderedSame }
        list.insert(value, at: 0)
        if list.count > SearchRecents.limit { list = Array(list.prefix(SearchRecents.limit)) }
        withAnimation(Motion.quick) { recent = list }
        SearchRecents.save(list)
    }

    private func forget(_ text: String) {
        let list = recent.filter { $0 != text }
        withAnimation(Motion.quick) { recent = list }
        SearchRecents.save(list)
    }

    private func clearRecent() {
        withAnimation(Motion.quick) { recent = [] }
        SearchRecents.save([])
    }
}

/// Watches the catalogue and reports when a full list arrives, so the screen itself doesn't
/// redraw on every download progress tick.
@MainActor
private struct SearchCatalogWatcher: View {
    @ObservedObject var catalog: CatalogStore
    @Binding var version: Int

    var body: some View {
        Color.clear
            .onAppear { version = catalog.version }
            .onChange(of: catalog.version) { value in version = value }
    }
}

/// A note while a full list is still downloading (search only covers lists that have arrived).
@MainActor
private struct SearchCatalogNotes: View {
    @ObservedObject var catalog: CatalogStore
    let kinds: [ContentKind]

    private var waiting: [ContentKind] {
        kinds.filter { catalog.kinds.contains($0) && !catalog.hasFullList($0) }
    }

    var body: some View {
        let pending = waiting
        if !pending.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(pending, id: \.self) { kind in
                    CatalogStatusLine(phase: catalog.phases[kind], noun: SearchText.noun(kind))
                }
                Text("Your \(SearchText.list(pending)) will show up in search once the full list has arrived.")
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.textMuted)
            }
            .padding(.horizontal, 28)
            .transition(.opacity)
        }
    }
}

// MARK: - Before typing

@MainActor
private struct SearchIdleView: View {
    let recent: [String]
    let onPick: (String) -> Void
    let onRemove: (String) -> Void
    let onClear: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                if !recent.isEmpty { recentSection }
                tips
            }
            .padding(.horizontal, 28)
            .padding(.top, 6)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var recentSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Text("Recent searches")
                    .font(NeonFont.display(16))
                    .foregroundColor(Neon.text)
                Spacer()
                Button("Clear") { onClear() }
                    .buttonStyle(NeonButtonStyle())
                    .help("Forget your recent searches")
            }
            SearchFlowLayout(spacing: 8) {
                ForEach(recent, id: \.self) { text in
                    SearchChip(title: text, symbol: "clock.arrow.circlepath", selected: false) { onPick(text) }
                        .contextMenu {
                            Button("Remove from recent searches") { onRemove(text) }
                        }
                }
            }
        }
    }

    private var tips: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Tips")
                .font(NeonFont.display(16))
                .foregroundColor(Neon.text)
            SearchTip(symbol: "textformat", text: "Type any part of a name, like “sky sp”, “bbc one” or “matrix”. Capitals and accents don't matter.")
            SearchTip(symbol: "line.3.horizontal.decrease.circle", text: "Every word has to match, so adding a word narrows the results.")
            SearchTip(symbol: "cursorarrow.click.2", text: "Click a channel to watch it, or a film or show to open its page. Right-click for more, like Add to Library or Add to Favourites.")
            SearchTip(symbol: "command", text: "Press ⌘F from anywhere in the app to come back here.")
        }
        .padding(18)
        .frame(maxWidth: 640, alignment: .leading)
        .neonPanel()
    }
}

@MainActor
private struct SearchTip: View {
    let symbol: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(Neon.cyan)
                .frame(width: 22)
            Text(text)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Results

@MainActor
private struct SearchResultsView: View {
    let results: [MediaItem]
    let query: String
    let filter: SearchFilter
    let badges: [String: String]
    let hitLimit: Bool
    let onOpen: (MediaItem, [MediaItem]) -> Void
    let onShowAll: (ContentKind) -> Void

    private var live: [MediaItem] { results.filter { $0.kind == .live } }
    private var films: [MediaItem] { results.filter { $0.kind == .movie } }
    private var shows: [MediaItem] { results.filter { $0.kind == .series } }
    /// With All selected, each kind shows a preview and a Show all button.
    private var previewing: Bool { filter == .all }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 30) {
                Text(summary)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                if !live.isEmpty {
                    SearchLiveSection(items: live, cap: previewing ? 8 : nil, onOpen: onOpen, onShowAll: { onShowAll(.live) })
                }
                if !films.isEmpty {
                    SearchPosterSection(title: "Films", items: films, cap: previewing ? 12 : nil, badges: badges,
                                        onOpen: onOpen, onShowAll: { onShowAll(.movie) })
                }
                if !shows.isEmpty {
                    SearchPosterSection(title: "Series", items: shows, cap: previewing ? 12 : nil, badges: badges,
                                        onOpen: onOpen, onShowAll: { onShowAll(.series) })
                }
                if hitLimit {
                    Text("Showing the closest matches. Add another word to narrow things down.")
                        .font(NeonFont.body(12))
                        .foregroundColor(Neon.textMuted)
                }
            }
            .padding(.horizontal, 28)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var summary: String {
        var parts: [String] = []
        if !live.isEmpty { parts.append(SearchText.count(live.count, .live)) }
        if !films.isEmpty { parts.append(SearchText.count(films.count, .movie)) }
        if !shows.isEmpty { parts.append(SearchText.count(shows.count, .series)) }
        if parts.isEmpty { return "Searching…" }
        return parts.joined(separator: " · ") + " matching “\(query)”"
    }
}

@MainActor
private struct SearchSectionHeader: View {
    let title: String
    let count: Int
    var showAll: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(title)
                .font(NeonFont.display(18))
                .foregroundColor(Neon.text)
            Text(formatCount(count))
                .font(NeonFont.display(13))
                .foregroundColor(Neon.magenta)
            Spacer()
            if let showAll {
                Button("Show all \(formatCount(count))") { showAll() }
                    .buttonStyle(NeonButtonStyle())
            }
        }
    }
}

@MainActor
private struct SearchLiveSection: View {
    let items: [MediaItem]
    let cap: Int?
    let onOpen: (MediaItem, [MediaItem]) -> Void
    let onShowAll: () -> Void

    private var shown: [MediaItem] {
        guard let cap else { return items }
        return Array(items.prefix(cap))
    }

    var body: some View {
        let visible = shown
        VStack(alignment: .leading, spacing: 10) {
            SearchSectionHeader(title: "Live TV", count: items.count, showAll: visible.count < items.count ? onShowAll : nil)
            LazyVStack(spacing: 6) {
                ForEach(Array(visible.enumerated()), id: \.element.id) { index, item in
                    Button { onOpen(item, items) } label: {
                        ChannelRow(item: item, number: index + 1)
                    }
                    .buttonStyle(.plain)
                    .contextMenu { MediaContextMenu(item: item, list: items) }
                }
            }
        }
    }
}

@MainActor
private struct SearchPosterSection: View {
    let title: String
    let items: [MediaItem]
    let cap: Int?
    let badges: [String: String]
    let onOpen: (MediaItem, [MediaItem]) -> Void
    let onShowAll: () -> Void

    private var shown: [MediaItem] {
        guard let cap else { return items }
        return Array(items.prefix(cap))
    }

    var body: some View {
        let visible = shown
        VStack(alignment: .leading, spacing: 12) {
            SearchSectionHeader(title: title, count: items.count, showAll: visible.count < items.count ? onShowAll : nil)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], spacing: 22) {
                ForEach(visible) { item in
                    PosterCard(item: item, badge: badges[item.source]) { onOpen(item, items) }
                }
            }
        }
    }
}

// MARK: - Small parts

/// A neon filter or recent-search chip.
@MainActor
private struct SearchChip: View {
    let title: String
    let symbol: String
    var count: Int? = nil
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: symbol)
                    .font(.system(size: 12, weight: .semibold))
                Text(title)
                    .font(NeonFont.body(14, bold: true))
                    .lineLimit(1)
                if let count {
                    Text(formatCount(count))
                        .font(NeonFont.body(12, bold: true))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 1)
                        .background(Capsule().fill(selected ? Neon.onCyan.opacity(0.18) : Neon.cyan.opacity(0.16)))
                }
            }
            .foregroundColor(foreground)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(HudShape(cut: 8).fill(fill))
            .overlay(HudShape(cut: 8).stroke(Neon.cyan.opacity(selected ? 1 : (hovering ? 0.7 : 0.25)), lineWidth: 1))
            .shadow(color: Neon.cyan.opacity(selected ? 0.45 : 0), radius: 10)
            .contentShape(HudShape(cut: 8))
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }

    private var foreground: Color {
        if selected { return Neon.onCyan }
        return hovering ? Neon.cyan : Neon.text
    }

    private var fill: Color {
        if selected { return Neon.cyan }
        return hovering ? Neon.surfaceRaised : Neon.surface.opacity(0.85)
    }
}

/// Lays chips out left to right, wrapping onto new lines.
private struct SearchFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth {
                y += rowHeight + spacing
                x = 0
                rowHeight = 0
            }
            x += size.width
            widest = max(widest, x)
            x += spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: widest, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
