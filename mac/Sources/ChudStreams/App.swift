import SwiftUI

@main
@MainActor
struct ChudStreamsApp: App {
    @StateObject private var model: AppModel

    init() {
        SelfTest.runIfRequested()
        NeonFont.registerBundledFonts()
        Reports.start()
        _model = StateObject(wrappedValue: AppModel())
    }

    var body: some Scene {
        WindowGroup("CHUD STREAMS") {
            RootView()
                .environmentObject(model)
                .environmentObject(model.userData)
                .environmentObject(model.playback)
                .frame(minWidth: 1040, minHeight: 660)
                .preferredColorScheme(.dark)
        }
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandMenu("Go") {
                ForEach(AppSection.allCases) { section in
                    if let key = section.shortcut {
                        Button(section.rawValue) { model.go(section) }
                            .keyboardShortcut(key, modifiers: .command)
                    } else {
                        Button(section.rawValue) { model.go(section) }
                    }
                }
            }
            CommandMenu("Playback") {
                Button("Play or pause") { model.playback.togglePause() }
                    .keyboardShortcut("p", modifiers: [.command, .shift])
                Button("Mini player") { model.playback.minimise() }
                    .keyboardShortcut("m", modifiers: [.command, .shift])
                Button("Full screen player") { model.playback.expand() }
                    .keyboardShortcut("f", modifiers: [.command, .shift])
                Button("Multiview") { model.playback.openMultiview() }
                    .keyboardShortcut("v", modifiers: [.command, .shift])
                Button("Stop") { model.playback.stop() }
                    .keyboardShortcut(".", modifiers: .command)
            }
        }
    }
}

enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case home = "Home"
    case search = "Search"
    case live = "Live TV"
    case guide = "Guide"
    case movies = "Films"
    case series = "Series"
    case library = "Library"
    case favorites = "Favourites"
    case claude = "Ask Claude"
    case markets = "Markets"
    case games = "Arcade"
    case settings = "Settings"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .search: return "magnifyingglass"
        case .live: return "tv"
        case .guide: return "calendar"
        case .movies: return "film"
        case .series: return "square.stack.3d.up"
        case .library: return "books.vertical"
        case .favorites: return "star"
        case .claude: return "sparkles"
        case .markets: return "chart.line.uptrend.xyaxis"
        case .games: return "gamecontroller"
        case .settings: return "gearshape"
        }
    }

    /// The filled version of the symbol, for the selected sidebar item (where one exists).
    var selectedSymbol: String {
        switch self {
        case .search, .guide, .claude, .markets: return symbol
        default: return symbol + ".fill"
        }
    }

    var shortcut: KeyEquivalent? {
        switch self {
        case .home: return "1"
        case .live: return "2"
        case .guide: return "3"
        case .movies: return "4"
        case .series: return "5"
        case .library: return "6"
        case .favorites: return "7"
        case .claude: return "8"
        case .markets: return "9"
        case .search: return "f"
        case .settings: return ","
        case .games: return nil
        }
    }

    /// Sections grouped in the sidebar.
    static let watch: [AppSection] = [.home, .search, .live, .guide, .movies, .series]
    static let mine: [AppSection] = [.library, .favorites]
    static let extras: [AppSection] = [.claude, .markets, .games]
}

extension AppModel {
    func go(_ target: AppSection) {
        if playback.presentation == .full && playback.current != nil { playback.minimise() }
        if playback.multiviewVisible { playback.closeMultiview() }
        details = nil
        person = nil
        withAnimation(Motion.quick) { section = target }
    }
}

@MainActor
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    @State private var splash = UserDefaults.standard.object(forKey: "launchAnimation") as? Bool ?? true

    var body: some View {
        ZStack {
            NeonBackdrop()
            if !model.hasSources {
                OnboardingView()
                    .transition(.opacity)
            } else {
                HStack(spacing: 0) {
                    Sidebar()
                    ZStack {
                        SectionContent(section: model.section)
                            .id(model.section)
                            .transition(.asymmetric(insertion: .opacity.combined(with: .offset(y: 10)), removal: .opacity))
                        if let item = model.details {
                            DetailsPage(item: item)
                                .id(item.id)
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                                .zIndex(1)
                        }
                        if let person = model.person {
                            PersonPage(personId: person)
                                .id(person)
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                                .zIndex(2)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                }
                .task {
                    Reports.setKnownLogins(model.sources)
                    await model.refreshAccount()
                    await Reports.uploadPendingIfEnabled()
                }
            }
            PlayerStage()
                .zIndex(5)
            if playback.multiviewVisible {
                MultiviewScreen()
                    .transition(.opacity)
                    .zIndex(6)
            }
            if let notice = model.notice {
                NoticeBanner(text: notice) { withAnimation(Motion.fade) { model.notice = nil } }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .zIndex(7)
            }
            if splash {
                LaunchSplash { withAnimation(.easeOut(duration: 0.35)) { splash = false } }
                    .transition(.opacity)
                    .zIndex(10)
            }
        }
        .animation(Motion.page, value: model.details?.id)
        .animation(Motion.page, value: model.person)
        .animation(Motion.fade, value: model.notice)
        .animation(Motion.quick, value: model.section)
        // GitHub's test Mac has no real display, so animations there never finish; the
        // screenshot tour runs without them.
        .transaction { transaction in
            if TourRunner.isRunning {
                transaction.animation = nil
                transaction.disablesAnimations = true
            }
        }
        .onAppear { TourRunner.startIfRequested(model: model) }
    }
}

@MainActor
private struct SectionContent: View {
    let section: AppSection

    var body: some View {
        switch section {
        case .home: HomeView()
        case .search: SearchView()
        case .live: LiveTVView()
        case .guide: GuideView()
        case .movies: LibraryBrowser(kind: .movie)
        case .series: LibraryBrowser(kind: .series)
        case .library: LibraryView()
        case .favorites: FavoritesView()
        case .claude: ClaudeView()
        case .markets: MarketsView()
        case .games: GamesView()
        case .settings: SettingsView()
        }
    }
}

@MainActor
private struct Sidebar: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge)
                        .resizable()
                        .frame(width: 42, height: 42)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text("CHUD").font(NeonFont.display(18)).foregroundColor(Neon.cyan)
                    Text("STREAMS").font(NeonFont.display(12)).foregroundColor(Neon.magenta)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 30)
            SourcePicker()
                .padding(.horizontal, 12)
            ScrollView {
                VStack(alignment: .leading, spacing: 3) {
                    group(AppSection.watch)
                    divider
                    group(AppSection.mine)
                    divider
                    group(AppSection.extras)
                    divider
                    group([.settings])
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
            }
            Spacer(minLength: 0)
            if playback.current != nil && playback.presentation == .mini {
                Text("Mini player on. Double-click it to go full screen.")
                    .font(NeonFont.body(11))
                    .foregroundColor(Neon.textMuted)
                    .padding(.horizontal, 16)
            }
            Text("チャッド・ストリームズ")
                .font(.system(size: 11))
                .foregroundColor(Neon.magenta.opacity(0.8))
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
        }
        .frame(width: 218)
        .background(Neon.backgroundSoft.opacity(0.94))
        .overlay(Rectangle().fill(Neon.cyan.opacity(0.12)).frame(width: 1), alignment: .trailing)
    }

    private var divider: some View {
        Rectangle().fill(Neon.cyan.opacity(0.1)).frame(height: 1).padding(.vertical, 6).padding(.horizontal, 8)
    }

    private func group(_ sections: [AppSection]) -> some View {
        ForEach(sections) { section in
            SidebarItem(section: section, selected: model.section == section && model.details == nil) {
                model.go(section)
            }
        }
    }
}

@MainActor
private struct SidebarItem: View {
    let section: AppSection
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: selected ? section.selectedSymbol : section.symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .frame(width: 20)
                Text(section.rawValue).font(NeonFont.body(14, bold: true))
                Spacer()
            }
            .foregroundColor(selected ? Neon.onCyan : (hovering ? Neon.cyan : Neon.text))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                HudShape(cut: 8)
                    .fill(selected ? Neon.cyan : (hovering ? Neon.surface : Color.clear))
                    .shadow(color: Neon.cyan.opacity(selected ? 0.5 : 0), radius: 10)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .help(helpText)
    }

    private var helpText: String {
        guard let key = section.shortcut else { return section.rawValue }
        return "\(section.rawValue) (⌘\(String(key.character).uppercased()))"
    }
}

/// Switch between the viewer's accounts and playlists.
@MainActor
private struct SourcePicker: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Menu {
            ForEach(model.sources) { source in
                Button {
                    model.activeSourceId = source.id
                    model.details = nil
                } label: {
                    if source.id == model.activeSource?.id {
                        Label(source.name, systemImage: "checkmark")
                    } else {
                        Text(source.name)
                    }
                }
            }
            Divider()
            Button("Add or manage sources…") { model.go(.settings) }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: model.activeSource?.kind == .m3u ? "list.bullet.rectangle" : "server.rack")
                    .foregroundColor(Neon.cyan)
                Text(model.activeSource?.name ?? "No source")
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(Neon.text)
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 10)).foregroundColor(Neon.textMuted)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(HudShape(cut: 7).fill(Neon.surface))
            .overlay(HudShape(cut: 7).stroke(Neon.cyan.opacity(0.3), lineWidth: 1))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
    }
}

@MainActor
private struct NoticeBanner: View {
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        VStack {
            Spacer()
            HStack(spacing: 14) {
                Text(text)
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.text)
                Button("OK", action: onDismiss)
                    .buttonStyle(NeonButtonStyle())
            }
            .padding(14)
            .neonPanel(highlighted: true)
            .padding(.bottom, 24)
        }
        .task(id: text) {
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            onDismiss()
        }
    }
}

/// The logo spins twice, then the app fades in (like the Fire TV app). Can be turned off in Settings.
@MainActor
private struct LaunchSplash: View {
    let onFinish: () -> Void
    @State private var angle = 0.0
    @State private var scale = 0.7
    @State private var glow = 0.0

    var body: some View {
        ZStack {
            Neon.background.ignoresSafeArea()
            VStack(spacing: 18) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge)
                        .resizable()
                        .frame(width: 180, height: 180)
                        .rotation3DEffect(.degrees(angle), axis: (x: 0, y: 1, z: 0))
                        .scaleEffect(scale)
                        .shadow(color: Neon.cyan.opacity(glow), radius: 30)
                }
                NeonTitle(text: "CHUD STREAMS", size: 34)
                Text("チャッド・ストリームズ").font(.system(size: 14)).foregroundColor(Neon.magenta)
            }
        }
        .onAppear {
            if TourRunner.isRunning {
                onFinish()
                return
            }
            withAnimation(.easeInOut(duration: 1.4)) {
                angle = 720
                scale = 1
                glow = 0.8
            }
            Task {
                try? await Task.sleep(nanoseconds: 1_700_000_000)
                onFinish()
            }
        }
    }
}
