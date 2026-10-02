import SwiftUI

// Browsing a source: categories on the left (in the viewer's order, hidden ones left out), the
// channels or posters of the chosen category on the right. Once a source's full list has
// downloaded, every category opens instantly and "All" is available; before that, categories
// load one at a time from the provider.

enum BrowseSpecial {
    static let all = "__all__"
    static let favourites = "__favourites__"
    static let recent = "__recent__"
}

enum BrowseSort: String, CaseIterable, Identifiable {
    case provider = "Provider order"
    case name = "A to Z"
    case added = "Newest added"
    case rating = "Top rated"
    var id: String { rawValue }
}

/// Loads the chosen category of one kind of content for the active source.
@MainActor
final class BrowseLoader: ObservableObject {
    let kind: ContentKind
    @Published var selected: String? = nil
    @Published private(set) var items: [MediaItem] = []
    @Published private(set) var loading = false
    @Published private(set) var error: String? = nil
    private var loadedKey = ""

    init(kind: ContentKind) {
        self.kind = kind
        selected = UserDefaults.standard.string(forKey: "browse.category.\(kind.rawValue)")
    }

    func select(_ id: String) {
        selected = id
        UserDefaults.standard.set(id, forKey: "browse.category.\(kind.rawValue)")
    }

    /// The categories to show, with the special entries first.
    func categories(_ catalog: CatalogStore, userData: UserData) -> [ContentCategory] {
        var list: [ContentCategory] = []
        if kind == .live { list.append(ContentCategory(id: BrowseSpecial.favourites, name: "★ Favourites")) }
        if catalog.hasFullList(kind) {
            if kind != .live { list.append(ContentCategory(id: BrowseSpecial.recent, name: "Recently added")) }
            list.append(ContentCategory(id: BrowseSpecial.all, name: "All \(kind == .live ? "channels" : (kind == .movie ? "films" : "series"))"))
        }
        list += userData.arranged(catalog.categories[kind] ?? [], source: catalog.source.id, kind: kind)
        return list
    }

    func load(_ catalog: CatalogStore, userData: UserData) async {
        let cats = categories(catalog, userData: userData)
        if cats.count <= 1 || (cats.count <= 2 && !catalog.hasFullList(kind)) {
            _ = await catalog.loadCategories(kind)
        }
        let available = categories(catalog, userData: userData)
        var target = selected ?? ""
        if !available.contains(where: { $0.id == target }) {
            let firstReal = available.first { $0.id != BrowseSpecial.favourites && $0.id != BrowseSpecial.recent }
            target = firstReal?.id ?? available.first?.id ?? ""
            selected = target
        }
        let key = "\(catalog.source.id)|\(target)|\(catalog.version)|\(userData.favourites.items.count)"
        guard key != loadedKey, !target.isEmpty else { return }
        loading = true
        error = nil
        defer { loading = false }
        switch target {
        case BrowseSpecial.favourites:
            items = userData.favourites.items.filter { $0.kind == kind && $0.source == catalog.source.id }
        case BrowseSpecial.all:
            let hidden = userData.arrangement(catalog.source.id, kind).hidden
            items = catalog.all(kind).filter { !hidden.contains($0.categoryId ?? "") }
        case BrowseSpecial.recent:
            items = catalog.recentlyAdded(kind, limit: 200)
        default:
            do {
                items = try await catalog.items(kind, category: target)
            } catch {
                items = []
                self.error = (error as? LocalizedError)?.errorDescription ?? "Couldn't load this category. Try again in a moment."
                return
            }
        }
        if selected == target { loadedKey = key }
    }
}

/// The category list down the left.
@MainActor
struct CategoryColumn: View {
    @EnvironmentObject private var userData: UserData
    @ObservedObject var loader: BrowseLoader
    @ObservedObject var catalog: CatalogStore
    @State private var editing = false
    @State private var filter = ""

    var body: some View {
        let all = loader.categories(catalog, userData: userData)
        let shown = filter.isEmpty ? all : all.filter { $0.name.localizedCaseInsensitiveContains(filter) }
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Categories").font(NeonFont.display(13)).foregroundColor(Neon.textSecondary)
                Spacer()
                Button { editing = true } label: { Image(systemName: "slider.horizontal.3") }
                    .buttonStyle(.plain)
                    .foregroundColor(Neon.cyan)
                    .help("Reorder or hide categories")
            }
            .padding(.horizontal, 12)
            .padding(.top, 20)
            if all.count > 12 {
                TextField("Find a category", text: $filter)
                    .textFieldStyle(.plain)
                    .font(NeonFont.body(13))
                    .padding(7)
                    .neonPanel()
                    .padding(.horizontal, 10)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(shown) { category in
                        CategoryButton(name: category.name, selected: loader.selected == category.id) {
                            withAnimation(Motion.quick) { loader.select(category.id) }
                        }
                    }
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 12)
            }
        }
        .frame(width: 250)
        .background(Neon.backgroundSoft.opacity(0.6))
        .sheet(isPresented: $editing) {
            CategoryManager(catalog: catalog, kind: loader.kind)
                .environmentObject(userData)
        }
    }
}

@MainActor
private struct CategoryButton: View {
    let name: String
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Text(name)
                .font(NeonFont.body(14, bold: selected))
                .foregroundColor(selected ? Neon.onCyan : (hovering ? Neon.cyan : Neon.text))
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(HudShape(cut: 6).fill(selected ? Neon.cyan : (hovering ? Neon.surface : Color.clear)))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

/// Reorder and hide one source's categories.
@MainActor
struct CategoryManager: View {
    @EnvironmentObject private var userData: UserData
    @Environment(\.dismiss) private var dismiss
    let catalog: CatalogStore
    let kind: ContentKind
    @State private var order: [ContentCategory] = []

    var body: some View {
        let hidden = userData.arrangement(catalog.source.id, kind).hidden
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                NeonTitle(text: "Arrange categories", size: 22)
                Spacer()
                Button("Reset") {
                    userData.resetArrangement(source: catalog.source.id, kind: kind)
                    order = userData.arranged(catalog.categories[kind] ?? [], source: catalog.source.id, kind: kind, includeHidden: true)
                }
                .buttonStyle(NeonButtonStyle())
                Button("Done") { dismiss() }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .keyboardShortcut(.defaultAction)
            }
            Text("Drag to reorder. Untick a category to hide it from browsing, the guide and \"All\".")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textSecondary)
            List {
                ForEach(order) { category in
                    HStack(spacing: 12) {
                        Image(systemName: "line.3.horizontal").foregroundColor(Neon.textMuted)
                        Toggle(isOn: Binding(
                            get: { !hidden.contains(category.id) },
                            set: { visible in userData.setHidden(category.id, hidden: !visible, source: catalog.source.id, kind: kind) }
                        )) {
                            Text(category.name)
                                .font(NeonFont.body(14))
                                .foregroundColor(hidden.contains(category.id) ? Neon.textMuted : Neon.text)
                        }
                        .toggleStyle(.checkbox)
                        Spacer()
                        Button { move(category, by: -1) } label: { Image(systemName: "chevron.up") }.buttonStyle(.plain)
                        Button { move(category, by: 1) } label: { Image(systemName: "chevron.down") }.buttonStyle(.plain)
                    }
                    .padding(.vertical, 2)
                }
                .onMove { source, destination in
                    order.move(fromOffsets: source, toOffset: destination)
                    save()
                }
            }
            .scrollContentBackground(.hidden)
            .background(Neon.surface.opacity(0.5))
            HStack {
                Button("Show all") {
                    for category in order { userData.setHidden(category.id, hidden: false, source: catalog.source.id, kind: kind) }
                }
                .buttonStyle(NeonButtonStyle())
                Button("Hide all") {
                    for category in order { userData.setHidden(category.id, hidden: true, source: catalog.source.id, kind: kind) }
                }
                .buttonStyle(NeonButtonStyle())
                Spacer()
                Text("\(order.count - hidden.count) of \(order.count) shown").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            }
        }
        .padding(24)
        .frame(width: 560, height: 620)
        .background(NeonBackdrop())
        .onAppear {
            order = userData.arranged(catalog.categories[kind] ?? [], source: catalog.source.id, kind: kind, includeHidden: true)
        }
    }

    private func move(_ category: ContentCategory, by step: Int) {
        guard let index = order.firstIndex(of: category) else { return }
        let target = index + step
        guard target >= 0, target < order.count else { return }
        withAnimation(Motion.quick) { order.swapAt(index, target) }
        save()
    }

    private func save() {
        userData.setOrder(order.map { $0.id }, source: catalog.source.id, kind: kind)
    }
}

// MARK: - Live TV

@MainActor
struct LiveTVView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let catalog = model.catalog {
            LiveTVContent(catalog: catalog)
                .id(catalog.source.id)
        } else {
            EmptyState(symbol: "tv", title: "No source", message: "Add an Xtream login or an M3U playlist in Settings.")
        }
    }
}

@MainActor
private struct LiveTVContent: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @EnvironmentObject private var playback: PlaybackCenter
    @ObservedObject var catalog: CatalogStore
    @StateObject private var loader = BrowseLoader(kind: .live)
    @State private var query = ""

    private var shown: [MediaItem] {
        query.isEmpty ? loader.items : loader.items.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        HStack(spacing: 0) {
            CategoryColumn(loader: loader, catalog: catalog)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    NeonTitle(text: "Live TV")
                    Text("\(formatCount(shown.count)) channels")
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textMuted)
                    Spacer()
                    TextField("Filter this list", text: $query)
                        .textFieldStyle(.plain)
                        .font(NeonFont.body(14))
                        .padding(8)
                        .neonPanel()
                        .frame(width: 240)
                    Button { playback.openMultiview() } label: { Label("Multiview", systemImage: "rectangle.split.2x2") }
                        .buttonStyle(NeonButtonStyle())
                        .help("Watch up to four channels at once")
                }
                CatalogStatusLine(phase: catalog.phases[.live], noun: "channels")
                if let error = loader.error {
                    Text(error).font(NeonFont.body(13)).foregroundColor(Neon.danger)
                }
                if loader.loading && loader.items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if shown.isEmpty {
                    EmptyState(
                        symbol: loader.selected == BrowseSpecial.favourites ? "star" : "tv",
                        title: loader.selected == BrowseSpecial.favourites ? "No favourite channels yet" : "Nothing here",
                        message: loader.selected == BrowseSpecial.favourites
                            ? "Right-click any channel and choose Add to Favourites."
                            : "This category is empty, or nothing matches the filter."
                    )
                } else {
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                                Button {
                                    model.playLive(item, in: shown)
                                } label: {
                                    ChannelRow(item: item, number: item.number ?? (index + 1), playing: playback.current?.item?.id == item.id)
                                }
                                .buttonStyle(.plain)
                                .contextMenu { MediaContextMenu(item: item, list: shown) }
                            }
                        }
                        .padding(.vertical, 6)
                        .padding(.trailing, 6)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
        }
        .task(id: "\(loader.selected ?? "")|\(catalog.version)|\(userData.favourites.items.count)") {
            await loader.load(catalog, userData: userData)
        }
    }
}

// MARK: - Films and series

@MainActor
struct LibraryBrowser: View {
    @EnvironmentObject private var model: AppModel
    let kind: ContentKind

    var body: some View {
        if let catalog = model.catalog {
            if catalog.kinds.contains(kind) {
                LibraryBrowserContent(catalog: catalog, kind: kind)
                    .id("\(catalog.source.id)-\(kind.rawValue)")
            } else {
                EmptyState(symbol: kind == .series ? "square.stack.3d.up" : "film",
                           title: "Not in this playlist",
                           message: "M3U playlists don't list series separately. Switch to an Xtream source to browse series.")
            }
        } else {
            EmptyState(symbol: "film", title: "No source", message: "Add an Xtream login or an M3U playlist in Settings.")
        }
    }
}

@MainActor
private struct LibraryBrowserContent: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @ObservedObject var catalog: CatalogStore
    let kind: ContentKind
    @StateObject private var loader: BrowseLoader
    @State private var query = ""
    @State private var sort: BrowseSort = .provider

    init(catalog: CatalogStore, kind: ContentKind) {
        self.catalog = catalog
        self.kind = kind
        _loader = StateObject(wrappedValue: BrowseLoader(kind: kind))
    }

    private var shown: [MediaItem] {
        let filtered = query.isEmpty ? loader.items : loader.items.filter { $0.name.localizedCaseInsensitiveContains(query) }
        switch sort {
        case .provider: return filtered
        case .name: return filtered.sorted { $0.cleanName.localizedCaseInsensitiveCompare($1.cleanName) == .orderedAscending }
        case .added: return filtered.sorted { ($0.added ?? 0) > ($1.added ?? 0) }
        case .rating: return filtered.sorted { (Double($0.rating ?? "") ?? 0) > (Double($1.rating ?? "") ?? 0) }
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            CategoryColumn(loader: loader, catalog: catalog)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 14) {
                    NeonTitle(text: kind == .movie ? "Films" : "Series")
                    Text("\(formatCount(shown.count)) titles").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                    Spacer()
                    Picker("Sort", selection: $sort) {
                        ForEach(BrowseSort.allCases) { option in Text(option.rawValue).tag(option) }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .frame(width: 150)
                    TextField("Filter this list", text: $query)
                        .textFieldStyle(.plain)
                        .font(NeonFont.body(14))
                        .padding(8)
                        .neonPanel()
                        .frame(width: 220)
                }
                CatalogStatusLine(phase: catalog.phases[kind], noun: kind == .movie ? "films" : "series")
                if let error = loader.error {
                    Text(error).font(NeonFont.body(13)).foregroundColor(Neon.danger)
                }
                if loader.loading && loader.items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if shown.isEmpty {
                    EmptyState(symbol: kind == .movie ? "film" : "square.stack.3d.up", title: "Nothing here",
                               message: "This category is empty, or nothing matches the filter.")
                } else {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 190), spacing: 20)], spacing: 24) {
                            ForEach(shown) { item in
                                PosterCard(item: item, progress: progress(item)) { model.open(item) }
                            }
                        }
                        .padding(.vertical, 10)
                        .padding(.trailing, 8)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
        }
        .task(id: "\(loader.selected ?? "")|\(catalog.version)") {
            await loader.load(catalog, userData: userData)
        }
    }

    private func progress(_ item: MediaItem) -> Double? {
        guard kind == .movie else { return nil }
        let key = model.resumeKey(for: item)
        let position = model.resumePosition(for: key)
        guard position > 0 else { return nil }
        let entry = userData.history.first { $0.item.id == item.id }
        guard let duration = entry?.duration, duration > 0 else { return nil }
        return min(1, position / duration)
    }
}

func clock(_ seconds: Double) -> String {
    guard seconds.isFinite else { return "0:00" }
    let total = Int(max(0, seconds))
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}
