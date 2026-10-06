import AppKit
import SwiftUI

// Home: a big carousel of what's trending this week (from TMDB, matched to what your provider
// actually has), then Continue watching, favourite channels, and what's new on the provider.

/// One slide of the Home carousel.
struct HeroSlide: Identifiable, Hashable {
    let id: String
    let title: String
    let overview: String
    let backdrop: String?
    let poster: String?
    let rating: Double?
    let year: Int?
    let isShow: Bool
    /// The provider's copy, when the source has it.
    let item: MediaItem?
}

@MainActor
final class HomeModel: ObservableObject {
    @Published private(set) var slides: [HeroSlide] = []
    @Published private(set) var note: String? = nil
    private var loadedFor = ""

    func load(model: AppModel, catalog: CatalogStore?) async {
        let key = "\(catalog?.source.id ?? "")|\(catalog?.version ?? 0)|\(TMDB.isConfigured)"
        guard key != loadedFor else { return }
        loadedFor = key
        guard let catalog else { return }
        if TMDB.isConfigured {
            do {
                let trending = try await TMDB.trending()
                var matched: [HeroSlide] = []
                var others: [HeroSlide] = []
                for title in trending.prefix(20) {
                    let item = catalog.match(title: title.title, year: title.year, kind: title.isShow ? .series : .movie)
                    let slide = HeroSlide(id: title.key, title: title.title, overview: title.overview,
                                          backdrop: TMDB.image(title.backdrop, size: "w1280"), poster: TMDB.image(title.poster),
                                          rating: title.rating, year: title.year, isShow: title.isShow, item: item)
                    if item != nil { matched.append(slide) } else { others.append(slide) }
                }
                slides = Array((matched + others).prefix(10))
                note = matched.isEmpty ? "Trending this week on TMDB. None of these are on your provider yet." : nil
                if let weather = await WeatherLine.summary() {
                    note = note == nil ? weather : weather + "  ·  " + (note ?? "")
                }
                return
            } catch {
                note = (error as? LocalizedError)?.errorDescription
            }
        }
        // Without TMDB: the newest films and series from the provider.
        let recent = catalog.recentlyAdded(.movie, limit: 6) + catalog.recentlyAdded(.series, limit: 4)
        slides = recent.map { item in
            HeroSlide(id: item.id, title: item.cleanName, overview: item.kind == .series ? "New series on your provider" : "Newly added film",
                      backdrop: item.icon, poster: item.icon, rating: Double(item.rating ?? ""), year: item.titleYear,
                      isShow: item.kind == .series, item: item)
        }
        if note == nil && !slides.isEmpty {
            note = "Add a TMDB key in Settings > Services to see what's trending this week here."
        }
        if let weather = await WeatherLine.summary() {
            note = note == nil ? weather : weather + "  ·  " + (note ?? "")
        }
    }
}

@MainActor
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @StateObject private var home = HomeModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 34) {
                if home.slides.isEmpty {
                    greeting
                        .padding(.horizontal, 28)
                        .padding(.top, 34)
                } else {
                    HeroCarousel(slides: home.slides, note: home.note)
                }
                continueWatching
                favouriteChannels
                if let catalog = model.catalog {
                    CatalogShelves(catalog: catalog)
                }
                savedShelf
                Spacer().frame(height: 30)
            }
        }
        .task(id: "\(model.activeSource?.id ?? "")|\(model.catalog?.version ?? 0)") {
            await home.load(model: model, catalog: model.catalog)
        }
    }

    private var greeting: some View {
        VStack(alignment: .leading, spacing: 6) {
            NeonTitle(text: greetingText, size: 30)
            if let catalog = model.catalog {
                CatalogSummary(catalog: catalog)
            }
        }
    }

    private var greetingText: String {
        let hour = Calendar.current.component(.hour, from: Date())
        if hour < 12 { return "Good morning" }
        if hour < 18 { return "Good afternoon" }
        return "Good evening"
    }

    @ViewBuilder
    private var continueWatching: some View {
        let entries = userData.continueWatching
        if !entries.isEmpty {
            ShelfRow(title: "Continue watching", subtitle: "Your last \(entries.count)") {
                ForEach(entries) { entry in
                    WideCard(title: entry.title, subtitle: entry.subtitle, image: entry.image ?? entry.item.icon,
                             progress: entry.isLive ? nil : entry.progress, live: entry.isLive) {
                        model.resume(entry)
                    }
                    .contextMenu {
                        Button("Remove from Continue watching") { userData.removeHistory(entry) }
                        if !entry.isLive {
                            Button("Open details") { model.open(entry.item) }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var favouriteChannels: some View {
        let channels = userData.favourites.items.filter { $0.kind == .live }
        if !channels.isEmpty {
            ShelfRow(title: "Favourite channels") {
                ForEach(channels) { item in
                    FavouriteChannelCard(item: item, list: channels)
                }
            }
        }
    }

    @ViewBuilder
    private var savedShelf: some View {
        if !userData.library.isEmpty {
            ShelfRow(title: "Your Library", subtitle: "Films and shows you've saved") {
                ForEach(userData.library.prefix(30)) { item in
                    PosterCard(item: item, width: 150) { model.open(item) }
                }
            }
        }
    }
}

@MainActor
private struct CatalogSummary: View {
    @ObservedObject var catalog: CatalogStore

    var body: some View {
        let parts = catalog.kinds.compactMap { kind -> String? in
            let count = catalog.count(kind)
            guard count > 0 else { return nil }
            switch kind {
            case .live: return "\(formatCount(count)) channels"
            case .movie: return "\(formatCount(count)) films"
            case .series: return "\(formatCount(count)) series"
            }
        }
        VStack(alignment: .leading, spacing: 4) {
            Text(parts.isEmpty ? catalog.source.name : "\(catalog.source.name): " + parts.joined(separator: ", "))
                .font(NeonFont.body(15))
                .foregroundColor(Neon.textSecondary)
            CatalogStatusLine(phase: catalog.phases[.movie] ?? catalog.phases[.live], noun: "catalogue")
        }
    }
}

/// Recently added films and series.
@MainActor
private struct CatalogShelves: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var catalog: CatalogStore

    var body: some View {
        VStack(alignment: .leading, spacing: 34) {
            let films = catalog.recentlyAdded(.movie, limit: 24)
            if !films.isEmpty {
                ShelfRow(title: "New films", subtitle: "Recently added by your provider") {
                    ForEach(films) { item in
                        PosterCard(item: item, width: 150) { model.open(item) }
                    }
                }
            }
            let shows = catalog.recentlyAdded(.series, limit: 24)
            if !shows.isEmpty {
                ShelfRow(title: "New series") {
                    ForEach(shows) { item in
                        PosterCard(item: item, width: 150) { model.open(item) }
                    }
                }
            }
        }
    }
}

@MainActor
private struct FavouriteChannelCard: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    let list: [MediaItem]

    var body: some View {
        let now = Date()
        let current = model.nowNext(for: item).first { $0.isOn(at: now) }
        WideCard(title: item.name, subtitle: current?.title ?? "Live", image: item.icon, live: true, width: 240) {
            model.playLive(item, in: list)
        }
        .contextMenu { MediaContextMenu(item: item, list: list) }
        .task(id: item.id) { await model.loadEPG(for: item) }
    }
}

// MARK: - Carousel

@MainActor
private struct HeroCarousel: View {
    @EnvironmentObject private var model: AppModel
    let slides: [HeroSlide]
    let note: String?
    @State private var index = 0
    @State private var hovering = false
    @State private var zoom = false

    var body: some View {
        let slide = slides[min(index, slides.count - 1)]
        ZStack(alignment: .bottomLeading) {
            GeometryReader { geometry in
                ZStack {
                    RemoteImage(url: slide.backdrop, contentMode: .fill)
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .scaleEffect(zoom ? 1.07 : 1.0)
                        .clipped()
                        .id(slide.id)
                        .transition(.opacity)
                }
            }
            LinearGradient(colors: [.clear, Neon.background.opacity(0.55), Neon.background], startPoint: .top, endPoint: .bottom)
            LinearGradient(colors: [Neon.background.opacity(0.9), .clear], startPoint: .leading, endPoint: .trailing)
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Text("TRENDING THIS WEEK").font(NeonFont.display(12)).foregroundColor(Neon.magenta)
                    if slide.item != nil {
                        Text("ON YOUR PROVIDER")
                            .font(NeonFont.display(10))
                            .foregroundColor(Neon.onCyan)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Neon.cyan))
                    }
                }
                NeonTitle(text: slide.title, size: 38)
                    .lineLimit(2)
                    .id("title-" + slide.id)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                HStack(spacing: 12) {
                    if let rating = slide.rating, rating > 0 {
                        Text(String(format: "★ %.1f", rating)).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.cyan)
                    }
                    if let year = slide.year { Text(String(year)).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.textSecondary) }
                    Text(slide.isShow ? "Series" : "Film").font(NeonFont.body(14, bold: true)).foregroundColor(Neon.textSecondary)
                }
                Text(slide.overview)
                    .font(NeonFont.body(15))
                    .foregroundColor(Neon.text.opacity(0.88))
                    .lineLimit(3)
                    .frame(maxWidth: 620, alignment: .leading)
                HStack(spacing: 12) {
                    if let item = slide.item {
                        Button { model.open(item) } label: { Label(item.kind == .series ? "Watch" : "Watch now", systemImage: "play.fill") }
                            .buttonStyle(NeonButtonStyle(prominent: true))
                    } else {
                        Button {
                            model.pendingSearch = slide.title
                            model.go(.search)
                        } label: { Label("Search your provider", systemImage: "magnifyingglass") }
                            .buttonStyle(NeonButtonStyle(prominent: true))
                    }
                    if let note {
                        Text(note).font(NeonFont.body(12)).foregroundColor(Neon.textMuted).frame(maxWidth: 360, alignment: .leading)
                    }
                }
                .padding(.top, 4)
                dots
            }
            .padding(.horizontal, 36)
            .padding(.bottom, 26)
            HStack {
                arrow("chevron.left") { step(-1) }
                Spacer()
                arrow("chevron.right") { step(1) }
            }
            .padding(.horizontal, 12)
            .frame(maxHeight: .infinity)
            .opacity(hovering ? 1 : 0)
        }
        .frame(height: 440)
        .clipped()
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .onAppear {
            withAnimation(.easeInOut(duration: 14).repeatForever(autoreverses: true)) { zoom = true }
        }
        .task(id: slides.map { $0.id }.joined()) {
            // Advance every 7 seconds, pausing while the mouse is over the carousel.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 7_000_000_000)
                if !hovering && !Task.isCancelled { step(1) }
            }
        }
    }

    private var dots: some View {
        HStack(spacing: 7) {
            ForEach(Array(slides.enumerated()), id: \.offset) { position, _ in
                Capsule()
                    .fill(position == index ? Neon.cyan : Neon.textMuted.opacity(0.5))
                    .frame(width: position == index ? 24 : 8, height: 6)
                    .onTapGesture { withAnimation(Motion.page) { index = position } }
            }
        }
        .padding(.top, 6)
    }

    private func step(_ delta: Int) {
        guard !slides.isEmpty else { return }
        withAnimation(.easeInOut(duration: 0.7)) {
            index = (index + delta + slides.count) % slides.count
        }
    }

    private func arrow(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(Neon.text)
                .frame(width: 44, height: 44)
                .background(Circle().fill(Color.black.opacity(0.5)))
                .overlay(Circle().stroke(Neon.cyan.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
