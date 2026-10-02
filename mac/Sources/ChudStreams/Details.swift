import AppKit
import SwiftUI

// The page for a film or series: backdrop, poster, synopsis, a slowly scrolling cast list (click
// an actor for their page), episodes, and Trakt ratings and comments. Provider details come first;
// TMDB and Trakt add to them when the viewer has keys for them.

@MainActor
final class DetailsModel: ObservableObject {
    @Published var film: VodInfo? = nil
    @Published var series: SeriesInfo? = nil
    @Published var tmdb: TMDBDetails? = nil
    @Published var tmdbNote: String? = nil
    @Published var trakt: TraktSummary? = nil
    @Published var traktNote: String? = nil
    @Published var loading = true
    @Published var season = ""

    func load(_ item: MediaItem, model: AppModel) async {
        let client = model.client(for: item)
        if item.kind == .movie {
            film = try? await client?.vodInfo(item.streamId)
        } else if item.kind == .series {
            series = try? await client?.seriesInfo(item.streamId)
            let saved = model.lastEpisode(forSeries: item)?.season
            let seasons = series?.seasons ?? []
            season = seasons.first(where: { $0.key == saved })?.key ?? seasons.first?.key ?? ""
        }
        loading = false
        await loadTMDB(item)
    }

    private func loadTMDB(_ item: MediaItem) async {
        guard TMDB.isConfigured else {
            tmdbNote = "Add a TMDB key in Settings > Services for the cast, trailers and ratings."
            return
        }
        let show = item.kind == .series
        do {
            var id = film?.tmdbId ?? series?.tmdbId
            if id == nil {
                let year = item.titleYear ?? Int(film?.year ?? series?.year ?? "")
                id = try await TMDB.find(title: item.cleanName, year: year, show: show)?.id
            }
            guard let id else {
                tmdbNote = "TMDB doesn't seem to have this title."
                return
            }
            tmdb = try await TMDB.details(id: id, show: show)
            await loadTrakt(id: id, show: show)
        } catch {
            tmdbNote = (error as? LocalizedError)?.errorDescription
        }
    }

    private func loadTrakt(id: Int, show: Bool) async {
        guard Trakt.isConfigured else {
            traktNote = "Add a Trakt client ID in Settings > Services to see ratings and comments."
            return
        }
        do {
            trakt = try await Trakt.summary(tmdbId: id, show: show)
        } catch {
            traktNote = (error as? LocalizedError)?.errorDescription
        }
    }
}

@MainActor
struct DetailsPage: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    let item: MediaItem
    @StateObject private var details = DetailsModel()
    @StateObject private var keys = KeyMonitor()
    @State private var zoom = false
    @State private var expandedPlot = false

    var body: some View {
        ZStack(alignment: .topLeading) {
            Neon.background.ignoresSafeArea()
            backdrop
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 30) {
                        Spacer().frame(height: 250)
                        header
                            .padding(.horizontal, 36)
                        if let cast = details.tmdb?.cast, !cast.isEmpty {
                            CastMarquee(cast: cast) { person in
                                withAnimation(Motion.page) { model.person = person.id }
                            }
                            .id(DetailsPage.castAnchor)
                        }
                        if item.kind == .series {
                            EpisodesSection(item: item, details: details)
                                .padding(.horizontal, 36)
                        }
                        TraktSection(details: details)
                            .padding(.horizontal, 36)
                        Spacer().frame(height: 40)
                    }
                }
                // The screenshot tour asks for the cast and comments to be shown.
                .onReceive(NotificationCenter.default.publisher(for: DetailsPage.showCast)) { _ in
                    reader.scrollTo(DetailsPage.castAnchor, anchor: .top)
                }
            }
            // Keeps the Back button readable over whatever scrolls under it.
            LinearGradient(colors: [Neon.background.opacity(0.92), Neon.background.opacity(0)], startPoint: .top, endPoint: .bottom)
                .frame(height: 96)
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
            Button {
                close()
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .buttonStyle(NeonButtonStyle())
            .keyboardShortcut(.cancelAction)
            .padding(.leading, 24)
            .padding(.top, 26)
        }
        .task { await details.load(item, model: model) }
    }

    private func close() {
        withAnimation(Motion.page) { model.details = nil }
    }

    static let castAnchor = "details.cast"
    static let showCast = Notification.Name("CHUDDetailsShowCast")

    // MARK: Backdrop

    private var backdropURL: String? {
        if let path = details.tmdb?.backdrop { return TMDB.image(path, size: "w1280") }
        return details.film?.backdrop ?? details.series?.backdrop ?? details.film?.poster ?? details.series?.poster ?? item.icon
    }

    private var backdrop: some View {
        GeometryReader { geometry in
            RemoteImage(url: backdropURL, contentMode: .fill)
                .frame(width: geometry.size.width, height: 470)
                .scaleEffect(zoom ? 1.08 : 1.0, anchor: .center)
                .clipped()
                .overlay(
                    LinearGradient(colors: [Neon.background.opacity(0.1), Neon.background.opacity(0.65), Neon.background],
                                   startPoint: .top, endPoint: .bottom)
                )
                .overlay(
                    LinearGradient(colors: [Neon.background.opacity(0.85), .clear], startPoint: .leading, endPoint: .center)
                )
                .onAppear {
                    withAnimation(.easeInOut(duration: 24).repeatForever(autoreverses: true)) { zoom = true }
                }
        }
        .frame(height: 470)
        .allowsHitTesting(false)
    }

    // MARK: Header

    private var title: String {
        details.tmdb?.title.nonEmpty ?? details.film?.title ?? details.series?.title ?? item.cleanName
    }

    private var poster: String? {
        if let path = details.tmdb?.poster { return TMDB.image(path) }
        return details.film?.poster ?? details.series?.poster ?? item.icon
    }

    private var plot: String? {
        details.tmdb?.overview.nonEmpty ?? details.film?.plot ?? details.series?.plot
    }

    private var metaParts: [String] {
        var parts: [String] = []
        if let year = details.tmdb?.year { parts.append(String(year)) } else if let year = details.film?.year ?? details.series?.year { parts.append(year) }
        if let runtime = details.tmdb?.runtime, runtime > 0 {
            parts.append(runtime >= 60 ? "\(runtime / 60)h \(runtime % 60)m" : "\(runtime)m")
        } else if let duration = details.film?.duration {
            parts.append(duration)
        }
        if let certification = details.tmdb?.certification { parts.append(certification) }
        if let genres = details.tmdb?.genres, !genres.isEmpty {
            parts.append(genres.prefix(3).joined(separator: ", "))
        } else if let genre = details.film?.genre ?? details.series?.genre {
            parts.append(genre)
        }
        if item.kind == .series, let count = details.series?.seasons.count, count > 0 {
            parts.append(count == 1 ? "1 season" : "\(count) seasons")
        }
        return parts
    }

    private var ratingText: String? {
        if let rating = details.tmdb?.rating, rating > 0 { return String(format: "★ %.1f", rating) }
        if let rating = details.film?.rating ?? details.series?.rating ?? item.rating { return "★ \(rating)" }
        return nil
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 30) {
            RemoteImage(url: poster, contentMode: .fill)
                .frame(width: 220, height: 330)
                .clipShape(HudShape(cut: 16))
                .overlay(HudShape(cut: 16).stroke(Neon.cyan.opacity(0.55), lineWidth: 1.5))
                .shadow(color: Neon.cyan.opacity(0.35), radius: 22)
            VStack(alignment: .leading, spacing: 14) {
                NeonTitle(text: title, size: 32)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 14) {
                    if let ratingText {
                        Text(ratingText).font(NeonFont.body(15, bold: true)).foregroundColor(Neon.cyan)
                    }
                    Text(metaParts.joined(separator: "  ·  "))
                        .font(NeonFont.body(14, bold: true))
                        .foregroundColor(Neon.magenta)
                }
                if let tagline = details.tmdb?.tagline, !tagline.isEmpty {
                    Text(tagline).font(NeonFont.body(15)).italic().foregroundColor(Neon.textSecondary)
                }
                if let plot {
                    Text(plot)
                        .font(NeonFont.body(15))
                        .foregroundColor(Neon.text.opacity(0.9))
                        .lineLimit(expandedPlot ? nil : 5)
                        .frame(maxWidth: 640, alignment: .leading)
                        .onTapGesture { withAnimation(Motion.quick) { expandedPlot.toggle() } }
                }
                if let directors = details.tmdb?.directors, !directors.isEmpty {
                    Text((item.kind == .series ? "Created by " : "Directed by ") + directors.joined(separator: ", "))
                        .font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                } else if let director = details.film?.director {
                    Text("Directed by \(director)").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                }
                if details.tmdb == nil, let cast = details.film?.cast ?? details.series?.cast {
                    Text("Starring \(cast)").font(NeonFont.body(13)).foregroundColor(Neon.textMuted).lineLimit(2)
                }
                actions.padding(.top, 6)
                if details.loading {
                    Text("Loading details…").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                } else if let note = details.tmdbNote {
                    Text(note).font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
                }
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var actions: some View {
        HStack(spacing: 12) {
            primaryActions
            Button {
                userData.toggleLibrary(item)
            } label: {
                Label(userData.inLibrary(item) ? "In Library" : "Add to Library",
                      systemImage: userData.inLibrary(item) ? "bookmark.fill" : "bookmark")
            }
            .buttonStyle(NeonButtonStyle())
            Button {
                userData.toggleFavourite(item)
            } label: {
                Image(systemName: userData.isFavourite(item) ? "star.fill" : "star")
            }
            .buttonStyle(NeonButtonStyle())
            .help(userData.isFavourite(item) ? "Remove from Favourites" : "Add to Favourites")
            if let key = details.tmdb?.trailerKey, let url = URL(string: "https://www.youtube.com/watch?v=\(key)") {
                Button {
                    NSWorkspace.shared.open(url)
                } label: {
                    Label("Trailer", systemImage: "play.rectangle")
                }
                .buttonStyle(NeonButtonStyle())
            }
            if let imdb = details.tmdb?.imdbId, let url = URL(string: "https://www.imdb.com/title/\(imdb)/") {
                Button("IMDb") { NSWorkspace.shared.open(url) }
                    .buttonStyle(NeonButtonStyle())
            }
        }
    }

    @ViewBuilder
    private var primaryActions: some View {
        if item.kind == .movie {
            let resume = model.resumePosition(for: model.resumeKey(for: item))
            if resume > 0 {
                Button { playFilm(fromStart: false) } label: { Label("Resume from \(clock(resume))", systemImage: "play.fill") }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                Button("Start over") { playFilm(fromStart: true) }
                    .buttonStyle(NeonButtonStyle())
            } else {
                Button { playFilm(fromStart: false) } label: { Label("Play", systemImage: "play.fill") }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .keyboardShortcut(.defaultAction)
            }
        } else if let last = model.lastEpisode(forSeries: item) {
            Button {
                playEpisode(last)
            } label: {
                Label("Continue S\(last.season) E\(last.number ?? "?")", systemImage: "play.fill")
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
        } else if let first = details.series?.seasons.first?.episodes.first {
            Button {
                playEpisode(first)
            } label: {
                Label("Play first episode", systemImage: "play.fill")
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
        }
    }

    private func playFilm(fromStart: Bool) {
        model.playMovie(item, containerExtension: details.film?.containerExtension ?? item.containerExtension,
                        fromStart: fromStart, artwork: backdropURL)
    }

    private func playEpisode(_ episode: Episode) {
        let all = details.series?.seasons.flatMap { $0.episodes } ?? []
        let following = all.firstIndex(where: { $0.id == episode.id }).map { Array(all.dropFirst($0 + 1).prefix(20)) } ?? []
        model.playEpisode(episode, of: item, fromStart: false, upNext: following, artwork: backdropURL)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Cast

/// The cast drifts slowly sideways; hovering stops it so it can be scrolled and clicked.
@MainActor
private struct CastMarquee: View {
    let cast: [TMDBCastMember]
    let onSelect: (TMDBCastMember) -> Void
    @State private var hovering = false
    @State private var offset: CGFloat = 0

    private static let cardWidth: CGFloat = 116
    private static let spacing: CGFloat = 18

    private var cycleWidth: CGFloat { CGFloat(cast.count) * (CastMarquee.cardWidth + CastMarquee.spacing) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Cast", subtitle: "Click someone to see their other work")
                .padding(.horizontal, 36)
            ZStack {
                if hovering || cast.count < 6 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        row(cast).padding(.horizontal, 36)
                    }
                    .transition(.opacity)
                } else {
                    // In an overlay, so the long moving row never widens the page.
                    Color.clear
                        .overlay(alignment: .leading) {
                            row(cast + cast)
                                .offset(x: offset)
                                .padding(.leading, 36)
                        }
                        .clipped()
                        .onAppear { start() }
                        .transition(.opacity)
                }
            }
            .frame(height: 190)
            .onHover { inside in withAnimation(Motion.fade) { hovering = inside } }
        }
    }

    private func start() {
        offset = 0
        let duration = Double(cycleWidth / 24)
        withAnimation(.linear(duration: duration).repeatForever(autoreverses: false)) {
            offset = -cycleWidth
        }
    }

    private func row(_ people: [TMDBCastMember]) -> some View {
        HStack(alignment: .top, spacing: CastMarquee.spacing) {
            ForEach(Array(people.enumerated()), id: \.offset) { _, person in
                CastCard(person: person) { onSelect(person) }
                    .frame(width: CastMarquee.cardWidth)
            }
        }
    }
}

@MainActor
private struct CastCard: View {
    let person: TMDBCastMember
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                RemoteImage(url: TMDB.image(person.photo, size: "w185"), contentMode: .fill)
                    .frame(width: 104, height: 104)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(hovering ? Neon.cyan : Neon.cyan.opacity(0.25), lineWidth: hovering ? 2.5 : 1))
                    .shadow(color: Neon.cyan.opacity(hovering ? 0.6 : 0), radius: 12)
                Text(person.name)
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(hovering ? Neon.cyan : Neon.text)
                    .lineLimit(1)
                Text(person.role)
                    .font(NeonFont.body(11))
                    .foregroundColor(Neon.textMuted)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
            .scaleEffect(hovering ? 1.05 : 1)
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

// MARK: - Episodes

@MainActor
private struct EpisodesSection: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    @ObservedObject var details: DetailsModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: "Episodes")
            if let seasons = details.series?.seasons, !seasons.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(seasons) { season in
                            Button("Season \(season.key)") { withAnimation(Motion.quick) { details.season = season.key } }
                                .buttonStyle(NeonButtonStyle(prominent: details.season == season.key))
                        }
                    }
                }
                let current = seasons.first { $0.key == details.season } ?? seasons[0]
                let last = model.lastEpisode(forSeries: item)
                LazyVStack(spacing: 8) {
                    ForEach(current.episodes) { episode in
                        EpisodeRow(
                            episode: episode,
                            resume: model.resumePosition(for: model.resumeKey(for: episode, of: item)),
                            lastWatched: last?.id == episode.id
                        ) {
                            play(episode, all: seasons)
                        }
                    }
                }
            } else if !details.loading {
                Text("The provider hasn't listed any episodes for this series.")
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.textMuted)
            }
        }
    }

    private func play(_ episode: Episode, all seasons: [Season]) {
        let all = seasons.flatMap { $0.episodes }
        let following = all.firstIndex(where: { $0.id == episode.id }).map { Array(all.dropFirst($0 + 1).prefix(20)) } ?? []
        model.playEpisode(episode, of: item, fromStart: false, upNext: following)
    }
}

@MainActor
private struct EpisodeRow: View {
    let episode: Episode
    let resume: Double
    let lastWatched: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 16) {
                ZStack {
                    RemoteImage(url: episode.image, contentMode: .fill)
                    if hovering {
                        Image(systemName: "play.fill")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(Neon.onCyan)
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(Neon.cyan))
                    }
                }
                .frame(width: 176, height: 99)
                .clipShape(HudShape(cut: 8))
                VStack(alignment: .leading, spacing: 5) {
                    Text("E\(episode.number ?? "?")  \(episode.title)")
                        .font(NeonFont.body(15, bold: true))
                        .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                        .lineLimit(1)
                    if let plot = episode.plot {
                        Text(plot)
                            .font(NeonFont.body(13))
                            .foregroundColor(hovering ? Neon.onCyan.opacity(0.85) : Neon.textSecondary)
                            .lineLimit(3)
                    }
                    HStack(spacing: 12) {
                        if lastWatched {
                            Text("Last watched").font(NeonFont.body(12, bold: true)).foregroundColor(hovering ? Neon.onCyan : Neon.magenta)
                        }
                        if resume > 0 {
                            Text("Resume \(clock(resume))").font(NeonFont.body(12)).foregroundColor(hovering ? Neon.onCyan : Neon.cyan)
                        }
                        if let duration = episode.duration {
                            Text(duration).font(NeonFont.body(12)).foregroundColor(hovering ? Neon.onCyan : Neon.textMuted)
                        }
                    }
                }
                Spacer()
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HudShape().fill(hovering ? Neon.cyan : Neon.surface.opacity(0.8)))
            .overlay(HudShape().stroke(Neon.cyan.opacity(hovering ? 1 : 0.15), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

// MARK: - Trakt

@MainActor
private struct TraktSection: View {
    @ObservedObject var details: DetailsModel
    @State private var revealed = Set<Int>()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: "Reviews and comments", subtitle: "From Trakt") {
                if let url = details.trakt?.url {
                    Link("Open on Trakt", destination: url)
                        .font(NeonFont.body(13, bold: true))
                        .foregroundColor(Neon.cyan)
                }
            }
            if let trakt = details.trakt {
                if let rating = trakt.rating {
                    HStack(spacing: 10) {
                        Text(String(format: "%.0f%%", rating * 10)).font(NeonFont.display(26)).foregroundColor(Neon.cyan)
                        Text("liked it on Trakt\(trakt.votes.map { " (\(formatCount($0)) votes)" } ?? "")")
                            .font(NeonFont.body(14)).foregroundColor(Neon.textSecondary)
                    }
                }
                if trakt.comments.isEmpty {
                    Text("No comments yet.").font(NeonFont.body(14)).foregroundColor(Neon.textMuted)
                }
                ForEach(trakt.comments) { comment in
                    commentView(comment)
                }
            } else if let note = details.traktNote {
                Text(note).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            } else if details.tmdb != nil {
                ProgressView().controlSize(.small)
            } else {
                Text("Ratings and comments appear here once TMDB and Trakt keys are added in Settings > Services.")
                    .font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            }
        }
    }

    private func commentView(_ comment: TraktComment) -> some View {
        let hidden = comment.spoiler && !revealed.contains(comment.id)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: "person.crop.circle.fill").font(.system(size: 22)).foregroundColor(Neon.magenta)
                Text(comment.user).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text)
                if let rating = comment.rating {
                    Text("★ \(rating)/10").font(NeonFont.body(12, bold: true)).foregroundColor(Neon.cyan)
                }
                if comment.review {
                    Text("REVIEW").font(NeonFont.display(10)).foregroundColor(Neon.onCyan)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(Neon.magenta))
                }
                Spacer()
                if let date = comment.date {
                    Text(date.formatted(date: .abbreviated, time: .omitted)).font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
                }
            }
            ZStack(alignment: .leading) {
                Text(comment.text)
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(comment.review ? 8 : 5)
                    .blur(radius: hidden ? 7 : 0)
                if hidden {
                    Button("Contains spoilers: show") { revealed.insert(comment.id) }
                        .buttonStyle(NeonButtonStyle())
                }
            }
            HStack(spacing: 16) {
                Label("\(comment.likes)", systemImage: "hand.thumbsup").font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
                Label("\(comment.replies)", systemImage: "bubble.left").font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
            }
        }
        .padding(14)
        .neonPanel()
    }
}

// MARK: - Person

@MainActor
final class PersonModel: ObservableObject {
    @Published var person: TMDBPerson? = nil
    @Published var error: String? = nil

    func load(_ id: Int) async {
        do {
            person = try await TMDB.person(id: id)
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? "Couldn't load this person."
        }
    }
}

@MainActor
struct PersonPage: View {
    @EnvironmentObject private var model: AppModel
    let personId: Int
    @StateObject private var state = PersonModel()
    @State private var expanded = false
    @State private var filter: String = "All"

    var body: some View {
        ZStack(alignment: .topLeading) {
            Neon.background.ignoresSafeArea()
            NeonBackdrop()
            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    Spacer().frame(height: 50)
                    if let person = state.person {
                        header(person)
                        filmography(person)
                    } else if let error = state.error {
                        EmptyState(symbol: "person.crop.circle.badge.exclamationmark", title: "Couldn't load", message: error)
                    } else {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 120)
                    }
                }
                .padding(.horizontal, 36)
                .padding(.bottom, 40)
            }
            LinearGradient(colors: [Neon.background.opacity(0.92), Neon.background.opacity(0)], startPoint: .top, endPoint: .bottom)
                .frame(height: 96)
                .frame(maxWidth: .infinity)
                .allowsHitTesting(false)
            Button {
                withAnimation(Motion.page) { model.person = nil }
            } label: {
                Label("Back", systemImage: "chevron.left")
            }
            .buttonStyle(NeonButtonStyle())
            .keyboardShortcut(.cancelAction)
            .padding(.leading, 24)
            .padding(.top, 26)
        }
        .task { await state.load(personId) }
    }

    private func header(_ person: TMDBPerson) -> some View {
        HStack(alignment: .top, spacing: 30) {
            RemoteImage(url: TMDB.image(person.photo, size: "h632"), contentMode: .fill)
                .frame(width: 210, height: 300)
                .clipShape(HudShape(cut: 16))
                .overlay(HudShape(cut: 16).stroke(Neon.cyan.opacity(0.55), lineWidth: 1.5))
                .shadow(color: Neon.cyan.opacity(0.35), radius: 20)
            VStack(alignment: .leading, spacing: 12) {
                NeonTitle(text: person.name, size: 32)
                Text(lifeLine(person)).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.magenta)
                if !person.biography.isEmpty {
                    Text(person.biography)
                        .font(NeonFont.body(14))
                        .foregroundColor(Neon.textSecondary)
                        .lineLimit(expanded ? nil : 7)
                        .frame(maxWidth: 680, alignment: .leading)
                    Button(expanded ? "Show less" : "Read more") { withAnimation(Motion.quick) { expanded.toggle() } }
                        .buttonStyle(.plain)
                        .font(NeonFont.body(13, bold: true))
                        .foregroundColor(Neon.cyan)
                }
                HStack(spacing: 12) {
                    if let imdb = person.imdbId, let url = URL(string: "https://www.imdb.com/name/\(imdb)/") {
                        Button {
                            NSWorkspace.shared.open(url)
                        } label: {
                            Label("IMDb profile", systemImage: "arrow.up.right.square")
                        }
                        .buttonStyle(NeonButtonStyle(prominent: true))
                    }
                    if let url = URL(string: "https://www.themoviedb.org/person/\(person.id)") {
                        Button("TMDB") { NSWorkspace.shared.open(url) }
                            .buttonStyle(NeonButtonStyle())
                    }
                }
            }
            Spacer(minLength: 0)
        }
    }

    private func lifeLine(_ person: TMDBPerson) -> String {
        var parts: [String] = []
        if let known = person.knownFor { parts.append(known) }
        if let born = person.born {
            parts.append("Born \(born)" + (person.birthplace.map { " in \($0)" } ?? ""))
        }
        if let died = person.died { parts.append("Died \(died)") }
        return parts.joined(separator: "  ·  ")
    }

    private func filmography(_ person: TMDBPerson) -> some View {
        let options = ["All", "Films", "TV", "In your library"]
        let credits = filtered(person.credits)
        return VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: "Previous work", subtitle: "\(person.credits.count) credits · the ones you can watch are marked") {
                Picker("Show", selection: $filter) {
                    ForEach(options, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 360)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140, maximum: 170), spacing: 18)], spacing: 22) {
                ForEach(credits) { credit in
                    CreditCard(credit: credit, available: available(credit)) { open(credit) }
                }
            }
        }
    }

    private func filtered(_ credits: [TMDBCredit]) -> [TMDBCredit] {
        switch filter {
        case "Films": return credits.filter { $0.mediaType == "movie" }
        case "TV": return credits.filter { $0.mediaType == "tv" }
        case "In your library": return credits.filter { available($0) != nil }
        default: return credits
        }
    }

    /// The provider's copy of this title, if the viewer's source has it.
    private func available(_ credit: TMDBCredit) -> MediaItem? {
        guard let catalog = model.catalog else { return nil }
        return catalog.match(title: credit.title, year: credit.year, kind: credit.mediaType == "tv" ? .series : .movie)
    }

    private func open(_ credit: TMDBCredit) {
        if let item = available(credit) {
            withAnimation(Motion.page) {
                model.person = nil
                model.details = item
            }
        } else if let url = URL(string: "https://www.themoviedb.org/\(credit.mediaType)/\(credit.tmdbId)") {
            NSWorkspace.shared.open(url)
        }
    }
}

@MainActor
private struct CreditCard: View {
    let credit: TMDBCredit
    let available: MediaItem?
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Color.clear
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .overlay(RemoteImage(url: TMDB.image(credit.poster, size: "w342"), contentMode: .fill))
                    .overlay(alignment: .topLeading) {
                        if available != nil {
                            Text("WATCH")
                                .font(NeonFont.display(10))
                                .foregroundColor(Neon.onCyan)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(Neon.cyan))
                                .padding(6)
                        }
                    }
                    .clipShape(HudShape(cut: 10))
                    .overlay(HudShape(cut: 10).stroke(available != nil ? Neon.cyan : Neon.cyan.opacity(hovering ? 0.8 : 0.15), lineWidth: available != nil ? 2 : 1))
                    .opacity(available == nil ? 0.8 : 1)
                Text(credit.title).font(NeonFont.body(13, bold: true)).foregroundColor(hovering ? Neon.cyan : Neon.text).lineLimit(2)
                Text([credit.year.map(String.init), credit.role.isEmpty ? nil : credit.role].compactMap { $0 }.joined(separator: " · "))
                    .font(NeonFont.body(11)).foregroundColor(Neon.textMuted).lineLimit(2)
            }
            .scaleEffect(hovering ? 1.04 : 1)
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .help(available != nil ? "Open it in your library" : "Not in your library: opens TMDB")
    }
}
