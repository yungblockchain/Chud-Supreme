import AppKit
import SwiftUI

// Settings and the first-run screen.
//
// Settings has a list of pages on the left and the chosen page on the right. Every control saves
// the moment it changes. Playback options are written one field at a time on top of the saved
// settings, so a change made from the player's own panels is never undone by this screen.
// The add-source form (Xtream login or M3U playlist) is shared with the first-run screen.

// MARK: - Settings screen

@MainActor
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var store = SettingsPlaybackStore()
    @State private var page: SettingsPage = .sources

    init() {}

    var body: some View {
        HStack(spacing: 0) {
            SettingsNav(page: $page)
            ScrollView {
                pageContent
                    .frame(maxWidth: 760, alignment: .leading)
                    .padding(.horizontal, 28)
                    .padding(.top, 30)
                    .padding(.bottom, 40)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .id(page)
        }
        .onAppear { store.reload() }
        .onChange(of: page) { _ in store.reload() }
    }

    @ViewBuilder
    private var pageContent: some View {
        switch page {
        case .sources: SettingsSourcesPage()
        case .addons: InfinityAddonsSettings()
        case .playback: SettingsPlaybackPage(store: store)
        case .hdr: SettingsHDRPage(store: store)
        case .audio: SettingsAudioPage(store: store)
        case .subtitles: SettingsSubtitlesPage(store: store)
        case .services: SettingsServicesPage()
        case .reports: SettingsReportsPage(openServices: { page = .services })
        case .general: SettingsGeneralPage(userData: model.userData)
        }
    }
}

private enum SettingsPage: String, CaseIterable, Identifiable {
    case sources, addons, playback, hdr, audio, subtitles, services, reports, general

    var id: String { rawValue }

    var title: String {
        switch self {
        case .sources: return "Sources"
        case .addons: return "Addons"
        case .playback: return "Playback"
        case .hdr: return "HDR & Dolby Vision"
        case .audio: return "Audio"
        case .subtitles: return "Subtitles"
        case .services: return "Services"
        case .reports: return "Reports"
        case .general: return "General"
        }
    }

    var symbol: String {
        switch self {
        case .sources: return "server.rack"
        case .addons: return "puzzlepiece"
        case .playback: return "play.rectangle"
        case .hdr: return "sun.max"
        case .audio: return "speaker.wave.2"
        case .subtitles: return "captions.bubble"
        case .services: return "key"
        case .reports: return "ladybug"
        case .general: return "gearshape"
        }
    }

    var blurb: String {
        switch self {
        case .sources:
            return "Your Xtream logins and M3U playlists. Switch between them from the top of the sidebar."
        case .addons:
            return "Stremio and Nuvio addon manifests. These fill Infinity and do not replace your Xtream login."
        case .playback:
            return "How streams play. Settings apply to the next stream you start."
        case .hdr:
            return "How HDR and Dolby Vision films are shown on your screen. Settings apply to the next stream you start."
        case .audio:
            return "Languages, surround sound and volume. Settings apply to the next stream you start."
        case .subtitles:
            return "When subtitles appear and how they look. Settings apply to the next stream you start."
        case .services:
            return "Your own keys for the extras. They're kept in this app's folder and only ever sent to the service they belong to."
        case .reports:
            return "Crash and error reports, to help track problems down. Nothing leaves your Mac unless you send it or turn on automatic sending."
        case .general:
            return "The launch animation, your saved data, keyboard shortcuts and version details."
        }
    }
}

/// The saved playback settings, shown by the Playback, HDR, Audio and Subtitles pages.
@MainActor
private final class SettingsPlaybackStore: ObservableObject {
    @Published private(set) var value = PlaybackSettings.current

    /// Picks up changes made elsewhere (the player's own panels).
    func reload() {
        let fresh = PlaybackSettings.current
        if fresh != value { value = fresh }
    }

    func binding<T>(_ path: WritableKeyPath<PlaybackSettings, T>) -> Binding<T> {
        Binding(
            get: { self.value[keyPath: path] },
            set: { newValue in self.update(path, to: newValue) }
        )
    }

    /// Saves one field straight away, on top of whatever is saved now.
    func update<T>(_ path: WritableKeyPath<PlaybackSettings, T>, to newValue: T) {
        value[keyPath: path] = newValue
        var saved = PlaybackSettings.current
        saved[keyPath: path] = newValue
        saved.save()
    }
}

@MainActor
private struct SettingsNav: View {
    @Binding var page: SettingsPage

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            NeonTitle(text: "Settings", size: 20)
                .padding(.horizontal, 10)
                .padding(.top, 30)
                .padding(.bottom, 14)
            ForEach(SettingsPage.allCases) { item in
                SettingsNavItem(page: item, selected: item == page) {
                    withAnimation(Motion.quick) { page = item }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .frame(width: 196)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Neon.backgroundSoft.opacity(0.55))
        .overlay(Rectangle().fill(Neon.cyan.opacity(0.12)).frame(width: 1), alignment: .trailing)
    }
}

@MainActor
private struct SettingsNavItem: View {
    let page: SettingsPage
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: page.symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .frame(width: 18)
                Text(page.title)
                    .font(NeonFont.body(13, bold: true))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            .foregroundColor(selected ? Neon.onCyan : (hovering ? Neon.cyan : Neon.text))
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                HudShape(cut: 7)
                    .fill(selected ? Neon.cyan : (hovering ? Neon.surface : Color.clear))
                    .shadow(color: Neon.cyan.opacity(selected ? 0.45 : 0), radius: 8)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

@MainActor
private struct SettingsPageHeader: View {
    let page: SettingsPage

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            NeonTitle(text: page.title, size: 24)
            Text(page.blurb)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 4)
    }
}

// MARK: - Sources

@MainActor
private struct SettingsSourcesPage: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .sources)
            if model.sources.isEmpty {
                SettingsHint(text: "No sources yet. Add one below.")
            }
            ForEach(model.sources) { source in
                sourceCard(source)
            }
            SettingsCard(title: "Add a source", subtitle: "An Xtream login from your provider, or any M3U playlist link.") {
                SourceForm()
            }
        }
    }

    @ViewBuilder
    private func sourceCard(_ source: Source) -> some View {
        if let store = model.catalog(for: source.id) {
            SettingsSourceCard(
                source: source,
                store: store,
                active: model.activeSource?.id == source.id,
                account: model.accounts[source.id]
            )
        }
    }
}

@MainActor
private struct SettingsSourceCard: View {
    @EnvironmentObject private var model: AppModel
    let source: Source
    @ObservedObject var store: CatalogStore
    let active: Bool
    let account: AccountInfo?
    @State private var checking = false
    @State private var editingLogin = false
    @State private var loginServer = ""
    @State private var loginUser = ""
    @State private var loginPassword = ""
    @State private var loginWorking = false
    @State private var loginError: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            SettingsDivider()
            lists
            if source.kind == .xtream {
                SettingsDivider()
                accountDetails
            }
            SettingsDivider()
            actions
            if editingLogin {
                SettingsDivider()
                loginEditor
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neonPanel(highlighted: active)
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: source.kind == .m3u ? "list.bullet.rectangle" : "server.rack")
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(Neon.cyan)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(source.name)
                        .font(NeonFont.body(16, bold: true))
                        .foregroundColor(Neon.text)
                        .lineLimit(1)
                    if active {
                        SettingsBadge(text: "Active")
                    }
                }
                Text(source.detail)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 0)
        }
    }

    private var lists: some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(store.kinds, id: \.self) { kind in
                labelled(SettingsText.title(kind)) {
                    status(for: kind)
                }
            }
            labelled("Guide") {
                guideLine
            }
        }
    }

    @ViewBuilder
    private func status(for kind: ContentKind) -> some View {
        let phase = store.phases[kind] ?? .idle
        switch phase {
        case .ready(let count, let updated):
            Text(SettingsText.listSummary(count: count, updated: updated))
                .font(NeonFont.body(13))
                .foregroundColor(count == 0 ? Neon.textMuted : Neon.text)
        case .idle:
            Text("Waiting to load")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textMuted)
        default:
            CatalogStatusLine(phase: phase, noun: SettingsText.noun(kind))
        }
    }

    private var guideLine: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(guideText)
                .font(NeonFont.body(13))
                .foregroundColor(Neon.text)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer(minLength: 8)
            SettingsLinkButton(title: source.epgURL == nil ? "Add XMLTV link…" : "Change…") { setGuide() }
            if source.epgURL != nil {
                SettingsLinkButton(title: "Remove", color: Neon.danger) { model.setGuide(source.id, url: "") }
            }
        }
    }

    private var guideText: String {
        if let link = source.epgURL {
            return URL(string: link)?.host ?? link
        }
        if let named = store.guideURL {
            return (URL(string: named)?.host ?? named) + " (named in the playlist)"
        }
        return source.kind == .xtream ? "From your provider" : "None"
    }

    @ViewBuilder
    private var accountDetails: some View {
        if let account {
            VStack(alignment: .leading, spacing: 7) {
                labelled("Status") {
                    infoText(SettingsText.accountStatus(account), colour: account.isActive ? Neon.positive : Neon.danger)
                }
                labelled("Expires") {
                    infoText(SettingsText.expiry(account.expiry), colour: expired(account) ? Neon.danger : Neon.text)
                }
                if let inUse = account.activeConnections, let limit = account.maxConnections {
                    labelled("Connections") {
                        infoText("\(inUse) of \(limit) in use", colour: Neon.text)
                    }
                }
                labelled("") {
                    SettingsLinkButton(title: checking ? "Checking…" : "Check again") { checkAccount() }
                        .disabled(checking)
                }
            }
        } else {
            HStack(spacing: 10) {
                Text("Account details haven't loaded yet.")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                SettingsLinkButton(title: checking ? "Checking…" : "Check now") { checkAccount() }
                    .disabled(checking)
                Spacer(minLength: 0)
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            if !active {
                Button("Make active") { makeActive() }
                    .buttonStyle(NeonButtonStyle(prominent: true))
            }
            Button(busy ? "Refreshing…" : "Refresh now") { refresh() }
                .buttonStyle(NeonButtonStyle())
                .disabled(busy)
            Button("Rename…") { rename() }
                .buttonStyle(NeonButtonStyle())
            if source.kind == .xtream {
                Button(editingLogin ? "Close login" : "Change login…") { beginLoginEdit() }
                    .buttonStyle(NeonButtonStyle())
            }
            Button("Remove…") { remove() }
                .buttonStyle(NeonButtonStyle())
            Spacer(minLength: 0)
        }
    }

    private var loginEditor: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsFormField(label: "Server", prompt: "http://example.com:8080", text: $loginServer, action: saveLogin)
            HStack(alignment: .top, spacing: 12) {
                SettingsFormField(label: "Username", text: $loginUser, action: saveLogin)
                SettingsFormField(label: "Password", prompt: "Leave blank to keep the current one", text: $loginPassword, secure: true, action: saveLogin)
            }
            SettingsHint(text: "Saved on this Mac only. The channel list reloads after the provider accepts the new login.")
            HStack(spacing: 12) {
                Button(loginWorking ? "Checking…" : "Save login") { saveLogin() }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .disabled(loginWorking || loginServer.trimmingCharacters(in: .whitespaces).isEmpty || loginUser.trimmingCharacters(in: .whitespaces).isEmpty)
                if loginWorking { ProgressView().controlSize(.small) }
            }
            if let loginError {
                Text(loginError)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func labelled<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(label)
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(Neon.textSecondary)
                .frame(width: 92, alignment: .leading)
            content()
            Spacer(minLength: 0)
        }
    }

    private func infoText(_ text: String, colour: Color) -> some View {
        Text(text)
            .font(NeonFont.body(13))
            .foregroundColor(colour)
            .lineLimit(1)
    }

    private func expired(_ account: AccountInfo) -> Bool {
        guard let expiry = account.expiry else { return false }
        return expiry < Date()
    }

    private var busy: Bool {
        store.phases.values.contains { phase in
            switch phase {
            case .loading, .decoding: return true
            default: return false
            }
        }
    }

    private func makeActive() {
        model.activeSourceId = source.id
        model.details = nil
    }

    private func refresh() {
        let store = self.store
        let model = self.model
        let isXtream = source.kind == .xtream
        Task {
            await store.refreshAll()
            if isXtream { await model.refreshAccount() }
        }
    }

    private func checkAccount() {
        guard !checking else { return }
        checking = true
        let model = self.model
        Task {
            await model.refreshAccount()
            checking = false
        }
    }

    private func rename() {
        guard let name = TextPrompt.ask(
            title: "Rename \(source.name)",
            message: "Choose a name for this source. It shows in the source switcher at the top of the sidebar.",
            placeholder: "Name",
            initial: source.name
        ) else { return }
        model.renameSource(source.id, to: name)
    }

    private func setGuide() {
        guard let link = TextPrompt.ask(
            title: "Programme guide (XMLTV)",
            message: "Paste the XMLTV guide link from your provider. It fills the Guide and what's on now for this source.",
            placeholder: "https://example.com/xmltv.php",
            initial: source.epgURL ?? ""
        ) else { return }
        let scheme = URL(string: link)?.scheme?.lowercased() ?? ""
        guard ["http", "https", "file"].contains(scheme) else {
            model.notice = "That guide link doesn't look right. It should start with http:// or https://."
            return
        }
        model.setGuide(source.id, url: link)
    }

    private func beginLoginEdit() {
        if editingLogin {
            editingLogin = false
            return
        }
        loginServer = source.server ?? ""
        loginUser = source.username ?? ""
        loginPassword = ""
        loginError = nil
        editingLogin = true
    }

    private func saveLogin() {
        guard !loginWorking else { return }
        loginWorking = true
        loginError = nil
        let model = self.model
        let id = source.id
        let server = loginServer
        let user = loginUser
        let password = loginPassword
        Task {
            do {
                try await model.updateXtream(id: id, server: server, username: user, password: password)
                loginPassword = ""
                editingLogin = false
                model.notice = "Login updated. The lists are loading again."
            } catch {
                loginError = SettingsText.message(for: error)
            }
            loginWorking = false
        }
    }

    private func remove() {
        let message = "Its saved lists and login are removed from this Mac. Favourites and history from it stay, but won't play until you add it again."
        guard TextPrompt.confirm(title: "Remove \(source.name)?", message: message, action: "Remove") else { return }
        model.removeSource(source.id)
    }
}

// MARK: - Add-source form (Settings and first run)

/// Adds an Xtream login or an M3U playlist. Used on the first-run screen and in Settings > Sources.
@MainActor
struct SourceForm: View {
    @EnvironmentObject private var model: AppModel
    private let onAdded: (() -> Void)?
    @State private var kind: SourceKind = .xtream
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var playlist = ""
    @State private var guide = ""
    @State private var name = ""
    @State private var working = false
    @State private var failure: String? = nil
    @State private var success: String? = nil

    init(onAdded: (() -> Void)? = nil) {
        self.onAdded = onAdded
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("Source type", selection: $kind) {
                Text("Xtream login").tag(SourceKind.xtream)
                Text("M3U playlist").tag(SourceKind.m3u)
            }
            .labelsHidden()
            .pickerStyle(.segmented)
            .frame(maxWidth: 300)
            if kind == .xtream {
                xtreamFields
            } else {
                m3uFields
            }
            footer
        }
        .onChange(of: kind) { _ in
            failure = nil
            success = nil
        }
    }

    private var xtreamFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsFormField(label: "Server", prompt: "http://example.com:8080", text: $server, action: { submit() })
            if linkInServer {
                SettingsHint(text: "That's a full login link, so the username and password are read from it.", color: Neon.cyan)
            } else {
                HStack(alignment: .top, spacing: 12) {
                    SettingsFormField(label: "Username", text: $username, action: { submit() })
                    SettingsFormField(label: "Password", text: $password, secure: true, action: { submit() })
                }
            }
            SettingsFormField(label: "Name (optional)", prompt: "My provider", text: $name, action: { submit() })
            SettingsHint(text: "Your provider gave you a server address, a username and a password. Got a get.php link instead? Paste the whole link into Server.")
        }
    }

    private var m3uFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsFormField(label: "Playlist link", prompt: "https://example.com/playlist.m3u", text: $playlist, action: { submit() })
            SettingsFormField(label: "Guide link (XMLTV, optional)", prompt: "https://example.com/guide.xml", text: $guide, action: { submit() })
            SettingsFormField(label: "Name (optional)", prompt: "My playlist", text: $name, action: { submit() })
            if linkInPlaylist {
                SettingsHint(text: "This is an Xtream login link (get.php), so it will be added as an Xtream login. That also brings films, series and catch-up.", color: Neon.cyan)
            } else {
                SettingsHint(text: "A provider's get.php link is recognised as an Xtream login, which also brings films, series and catch-up. If the playlist names its own guide, it's used automatically.")
            }
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button(working ? "Checking…" : "Add source") { submit() }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .disabled(working || !canSubmit)
                if working {
                    ProgressView().controlSize(.small)
                    Text(workingText)
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textSecondary)
                }
            }
            if let failure {
                Text(failure)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let success {
                Text(success)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.positive)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var linkInServer: Bool { XtreamClient.credentials(fromLink: server) != nil }
    private var linkInPlaylist: Bool { XtreamClient.credentials(fromLink: playlist) != nil }

    private var workingText: String {
        if kind == .xtream || linkInPlaylist { return "Checking the login with your provider…" }
        return "Downloading the playlist…"
    }

    private var canSubmit: Bool {
        switch kind {
        case .xtream:
            if linkInServer { return true }
            return !SettingsText.trim(server).isEmpty && !SettingsText.trim(username).isEmpty && !SettingsText.trim(password).isEmpty
        case .m3u:
            return !SettingsText.trim(playlist).isEmpty
        }
    }

    private func submit() {
        guard !working, canSubmit else { return }
        working = true
        failure = nil
        success = nil
        let model = self.model
        let chosen = self.kind
        let hadSources = model.hasSources
        let entered = (server: server, username: username, password: password, playlist: playlist, guide: guide, name: name)
        Task {
            do {
                if chosen == .xtream {
                    try await model.addXtream(server: entered.server, username: entered.username, password: entered.password, name: entered.name)
                } else {
                    try await model.addM3U(url: entered.playlist, guide: entered.guide, name: entered.name)
                }
                finish(sourceName: model.activeSource?.name ?? "your source")
            } catch {
                let message = SettingsText.message(for: error)
                failure = message
                // On the first-run screen a playlist shows the app while it downloads, so if it
                // then fails this form has been replaced: the reason goes in the banner as well.
                if !hadSources && chosen == .m3u { model.notice = message }
            }
            working = false
        }
    }

    private func finish(sourceName: String) {
        server = ""
        username = ""
        password = ""
        playlist = ""
        guide = ""
        name = ""
        success = "Added \(sourceName). It's now your active source, and its lists are loading."
        onAdded?()
    }
}

// MARK: - First run

/// Shown when there are no sources yet.
@MainActor
struct OnboardingView: View {
    init() {}

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                HStack(alignment: .center, spacing: 56) {
                    intro
                    formPanel
                }
                .padding(48)
                .frame(maxWidth: .infinity)
                .frame(minHeight: geometry.size.height)
            }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let badge = BrandImage.badge {
                Image(nsImage: badge)
                    .resizable()
                    .frame(width: 140, height: 140)
                    .shadow(color: Neon.cyan.opacity(0.45), radius: 18)
            }
            NeonTitle(text: "Chud Supreme", size: 38)
            Text("チャッド・ストリームズ")
                .font(.system(size: 13))
                .foregroundColor(Neon.magenta.opacity(0.85))
            Text("Live TV, films and series from your own provider. To get started, add the Xtream login they gave you, or any M3U playlist link.")
                .font(NeonFont.body(16))
                .foregroundColor(Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Text("Logins stay on this Mac. You can change the Xtream login later in Settings, and add addon manifests there too.")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 400, alignment: .leading)
    }

    private var formPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add your first source")
                .font(NeonFont.display(18))
                .foregroundColor(Neon.text)
            SourceForm()
        }
        .padding(24)
        .frame(width: 420, alignment: .leading)
        .neonPanel()
    }
}

// MARK: - Playback

@MainActor
private struct SettingsPlaybackPage: View {
    @ObservedObject var store: SettingsPlaybackStore

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .playback)
            playerCard
            networkCard
            watchingCard
        }
    }

    private var playerCard: some View {
        SettingsCard(title: "Player", subtitle: "The picture options below apply to the built-in player.") {
            SettingsChoice(title: "Player", hint: SettingsText.engine(store.value.engine),
                           selection: store.binding(\.engine), options: PlayerEngine.allCases,
                           label: { $0.label }, width: 340, stacked: true)
            SettingsDivider()
            SettingsChoice(title: "Renderer", hint: SettingsText.renderer(store.value.renderer),
                           selection: store.binding(\.renderer), options: VideoRenderer.allCases,
                           label: { $0.label }, width: 340, stacked: true)
            SettingsDivider()
            SettingsToggle(title: "Hardware decoding",
                           hint: "Uses the Mac's video chip, so it runs cooler and quieter. Turn off only if a stream shows green blocks or glitches.",
                           isOn: store.binding(\.hardwareDecoding))
            SettingsToggle(title: "Smooth motion",
                           hint: "Times frames to your screen's refresh rate (display resample), so slow pans don't judder.",
                           isOn: store.binding(\.videoSyncSmooth))
            SettingsToggle(title: "Interpolation",
                           hint: "Blends neighbouring frames for extra smoothness. Uses more power, and turns on smooth motion too.",
                           isOn: store.binding(\.interpolation))
            SettingsToggle(title: "Deband",
                           hint: "Smooths out colour banding in skies and dark scenes. Uses a little more power.",
                           isOn: store.binding(\.deband))
        }
    }

    private var networkCard: some View {
        SettingsCard(title: "Network") {
            SettingsChoice(title: "Buffer",
                           hint: "How far ahead to load. More rides out a shaky connection, but uses more memory.",
                           selection: store.binding(\.bufferSeconds), options: bufferOptions,
                           label: { SettingsText.seconds($0) }, width: 220, segmented: true)
            SettingsChoice(title: "Network timeout",
                           hint: "How long to wait for a slow server before giving up.",
                           selection: store.binding(\.networkTimeout), options: timeoutOptions,
                           label: { SettingsText.seconds($0) }, width: 180, segmented: true)
            SettingsDivider()
            SettingsToggle(title: "Reconnect on drop",
                           hint: "Picks the stream back up if the connection blips.",
                           isOn: store.binding(\.reconnect))
            SettingsToggle(title: "Retry live streams automatically",
                           hint: "If a live channel stops, it's started again for you.",
                           isOn: store.binding(\.liveRetry))
            SettingsDivider()
            SettingsChoice(title: "User agent",
                           hint: "Some providers only answer certain apps. If streams won't start, try VLC or TiviMate.",
                           selection: store.binding(\.userAgent), options: UserAgentPreset.allCases,
                           label: { $0.label }, width: 200)
            if store.value.userAgent == .custom {
                SettingsTextField(title: "Custom user agent", hint: "Sent exactly as typed. Leave empty to use the Chud Supreme one.",
                                  placeholder: "MyPlayer/1.0", text: store.binding(\.customUserAgent), width: 260)
            }
        }
    }

    private var watchingCard: some View {
        SettingsCard(title: "Watching") {
            SettingsToggle(title: "Resume where you left off",
                           hint: "Films and episodes pick up from where you stopped.",
                           isOn: store.binding(\.resume))
            SettingsToggle(title: "Autoplay next episode",
                           hint: "The next episode starts by itself when one ends.",
                           isOn: store.binding(\.autoplayNext))
            SettingsToggle(title: "Close shrinks to mini player",
                           hint: "Closing the player keeps it playing in a small window. Turn off to stop playback instead.",
                           isOn: store.binding(\.miniPlayerOnClose))
            SettingsDivider()
            SettingsChoice(title: "Skip back", hint: "The left arrow key.",
                           selection: store.binding(\.skipBack), options: skipBackOptions,
                           label: { SettingsText.seconds($0) }, width: 220, segmented: true)
            SettingsChoice(title: "Skip forward", hint: "The right arrow key.",
                           selection: store.binding(\.skipForward), options: skipForwardOptions,
                           label: { SettingsText.seconds($0) }, width: 220, segmented: true)
            SettingsChoice(title: "Default speed", hint: "For films and episodes. [ and ] change it while playing.",
                           selection: store.binding(\.defaultSpeed), options: speedOptions,
                           label: { SettingsText.speed($0) }, width: 280, segmented: true)
        }
    }

    private var bufferOptions: [Int] { SettingsText.including(store.value.bufferSeconds, in: [10, 30, 60, 120]) }
    private var timeoutOptions: [Int] { SettingsText.including(store.value.networkTimeout, in: [30, 60, 120]) }
    private var skipBackOptions: [Int] { SettingsText.including(store.value.skipBack, in: [5, 10, 15, 30]) }
    private var skipForwardOptions: [Int] { SettingsText.including(store.value.skipForward, in: [10, 15, 30, 60]) }
    private var speedOptions: [Double] { SettingsText.including(store.value.defaultSpeed, in: [0.75, 1.0, 1.25, 1.5, 2.0]) }
}

// MARK: - HDR and Dolby Vision

@MainActor
private struct SettingsHDRPage: View {
    @ObservedObject var store: SettingsPlaybackStore

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .hdr)
            screenCard
            toneCard
            dolbyCard
        }
    }

    private var screenCard: some View {
        SettingsCard(title: "Your screen") {
            SettingsHint(text: "Your MacBook's screen is SDR (standard dynamic range). HDR films carry far brighter highlights than it can show, so the player tone-maps them: it squeezes the brightness down to fit, keeping detail in skies and highlights, so the picture looks right instead of washed out or too dark.")
        }
    }

    private var toneCard: some View {
        SettingsCard(title: "Tone mapping") {
            SettingsChoice(title: "Tone mapping", hint: SettingsText.toneMapping(store.value.toneMapping),
                           selection: store.binding(\.toneMapping), options: ToneMapping.allCases,
                           label: { $0.label }, width: 240)
            SettingsDivider()
            SettingsToggle(title: "HDR peak detection",
                           hint: "Measures how bright each scene really is and tone-maps to match. Turn off if brightness seems to pump up and down.",
                           isOn: store.binding(\.hdrPeakDetection))
        }
    }

    private var dolbyCard: some View {
        SettingsCard(title: "Dolby Vision") {
            SettingsChoice(title: "Dolby Vision", hint: SettingsText.dolbyVision(store.value.dolbyVision),
                           selection: store.binding(\.dolbyVision), options: DolbyVisionMode.allCases,
                           label: { $0.label }, width: 300, stacked: true)
            if !store.value.renderer.usesMetal && store.value.dolbyVision == .auto {
                rendererNote
            }
        }
    }

    private var rendererNote: some View {
        HStack(alignment: .center, spacing: 12) {
            SettingsHint(text: "You're using the Standard renderer. Dolby Vision profile 5 (common in streaming copies) can look green and purple without the Advanced one, which is experimental.", color: Neon.magenta)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Use Advanced") { store.update(\.renderer, to: .advanced) }
                .buttonStyle(NeonButtonStyle())
        }
    }
}

// MARK: - Audio

@MainActor
private struct SettingsAudioPage: View {
    @ObservedObject var store: SettingsPlaybackStore

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .audio)
            languageCard
            outputCard
            volumeCard
        }
    }

    private var languageCard: some View {
        SettingsCard(title: "Language") {
            SettingsTextField(title: "Preferred audio languages",
                              hint: "Language codes in order of preference, separated by commas: for example en,eng or fr,fre,en.",
                              placeholder: "en,eng", text: store.binding(\.audioLanguages), width: 200)
        }
    }

    private var outputCard: some View {
        SettingsCard(title: "Output") {
            SettingsToggle(title: "Passthrough to a receiver",
                           hint: "Sends Dolby Digital, Dolby Digital Plus, Dolby Atmos (in Plus or TrueHD), DTS and TrueHD untouched over HDMI or optical, for a receiver or soundbar. Leave this off for the MacBook speakers and headphones so the app decodes them itself.",
                           isOn: store.binding(\.passthrough))
            SettingsDivider()
            SettingsToggle(title: "Exclusive mode",
                           hint: "Takes sole control of the sound output so nothing else is mixed in. Other apps go quiet while it plays.",
                           isOn: store.binding(\.exclusiveAudio))
            SettingsDivider()
            SettingsToggle(title: "Stereo downmix",
                           hint: "Mixes 5.1 and 7.1 surround down to two channels. Best for headphones and MacBook speakers.",
                           isOn: store.binding(\.stereoDownmix))
        }
    }

    private var volumeCard: some View {
        SettingsCard(title: "Volume") {
            SettingsToggle(title: "Even out volume",
                           hint: "Lifts quiet dialogue and tames loud explosions.",
                           isOn: store.binding(\.normaliseVolume))
            SettingsDivider()
            SettingsSlider(title: "Starting volume",
                           hint: "Above 100% boosts quiet streams, but can distort.",
                           value: store.binding(\.defaultVolume), range: 0...130, step: 5,
                           format: { "\(Int($0))%" })
        }
    }
}

// MARK: - Subtitles

@MainActor
private struct SettingsSubtitlesPage: View {
    @ObservedObject var store: SettingsPlaybackStore

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .subtitles)
            whenCard
            lookCard
        }
    }

    private var whenCard: some View {
        SettingsCard(title: "When they show") {
            SettingsChoice(title: "Subtitles",
                           hint: "Forced subtitles cover signs and short bits of foreign dialogue.",
                           selection: store.binding(\.subtitleMode), options: SubtitleMode.allCases,
                           label: { $0.label }, width: 380, stacked: true)
            SettingsDivider()
            SettingsTextField(title: "Preferred languages",
                              hint: "Language codes in order of preference, separated by commas: for example en,eng.",
                              placeholder: "en,eng", text: store.binding(\.subtitleLanguages), width: 200)
        }
    }

    private var lookCard: some View {
        SettingsCard(title: "How they look") {
            SettingsToggle(title: "Styled subtitles (ASS/SSA)",
                           hint: "Keeps the fonts, colours and positions that anime subtitles use, drawn with libass. Turn off to show them in your own style below.",
                           isOn: store.binding(\.styledSubtitles))
            SettingsDivider()
            SettingsSlider(title: "Text size",
                           hint: nil,
                           value: store.binding(\.subtitleScale), range: 0.5...2.0, step: 0.05,
                           format: { "\(Int(($0 * 100).rounded()))%" })
            SettingsSlider(title: "Position",
                           hint: "100 is the bottom of the picture. Lower numbers move subtitles up.",
                           value: store.binding(\.subtitlePosition), range: 0...100, step: 1,
                           format: { "\(Int($0))" })
            SettingsToggle(title: "Background box",
                           hint: "A dark box behind the text makes it easier to read over bright scenes.",
                           isOn: store.binding(\.subtitleBackground))
            SettingsDivider()
            SettingsTextField(title: "Font",
                              hint: "Any font installed on your Mac, for example Helvetica Neue or Avenir Next.",
                              placeholder: "Helvetica Neue", text: store.binding(\.subtitleFont), width: 220)
        }
    }
}

// MARK: - Services

@MainActor
private struct SettingsServicesPage: View {
    @AppStorage(MarketTelegram.tradingBotKey) private var tradingBot: String = ""
    @AppStorage(MarketTelegram.chatIdKey) private var chatId: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .services)
            SettingsCard(title: "Ask Claude") {
                SettingsSecretRow(key: .claude)
            }
            SettingsCard(title: "Gemini") {
                SettingsSecretRow(key: .gemini)
            }
            SettingsCard(title: "YouTube") {
                SettingsSecretRow(key: .youtube)
            }
            SettingsCard(title: "Debrid", subtitle: "Used by Infinity when a stream is a torrent instead of a file.") {
                SettingsSecretRow(key: .realDebrid)
                SettingsDivider()
                SettingsSecretRow(key: .torbox)
            }
            SettingsCard(title: "Film and TV details") {
                SettingsSecretRow(key: .tmdb)
                SettingsDivider()
                SettingsSecretRow(key: .trakt)
            }
            subtitlesCard
            SettingsCard(title: "Crash reports") {
                SettingsSecretRow(key: .github)
            }
            telegramCard
            SettingsCard(title: "Markets") {
                SettingsSecretRow(key: .coinMarketCap)
            }
        }
    }

    private var subtitlesCard: some View {
        SettingsCard(title: "Subtitles", subtitle: "The API key is all you need. Signing in as well lets you download more subtitles a day.") {
            SettingsSecretRow(key: .openSubtitles)
            SettingsDivider()
            SettingsSecretRow(key: .openSubtitlesUser)
            SettingsDivider()
            SettingsSecretRow(key: .openSubtitlesPassword)
        }
    }

    private var telegramCard: some View {
        SettingsCard(title: "Telegram", subtitle: "Used by Markets to open tokens in your trading bot and to send them to your own chat.") {
            SettingsSecretRow(key: .telegramBot)
            SettingsDivider()
            SettingsTextField(title: "Trading bot username",
                              hint: "The bot that Open in trading bot goes to: the name after t.me/.",
                              placeholder: "MyTradingBot", text: $tradingBot, width: 220)
            if !botLooksValid {
                SettingsHint(text: "That doesn't look like a Telegram username. Use letters, numbers and underscores.", color: Neon.danger)
            }
            SettingsTextField(title: "Chat ID",
                              hint: "Where Send to Telegram posts. Markets fills this in when you press Find my chat.",
                              placeholder: "123456789", text: $chatId, width: 220)
        }
    }

    private var botLooksValid: Bool {
        let trimmed = SettingsText.trim(tradingBot)
        return trimmed.isEmpty || MarketTelegram.isValidBotName(MarketTelegram.cleanBotName(trimmed))
    }
}

/// One key or login: shows whether it's saved (never the value), with Save and Remove.
@MainActor
private struct SettingsSecretRow: View {
    let key: SecretKey
    @State private var draft = ""
    @State private var saved: Bool

    init(key: SecretKey) {
        self.key = key
        _saved = State(initialValue: Secrets.has(key))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            heading
            entryRow
            SettingsHint(text: SettingsSecretInfo.hint(key))
            SettingsExternalLink(title: SettingsSecretInfo.linkTitle(key), address: SettingsSecretInfo.address(key))
        }
    }

    private var heading: some View {
        HStack(spacing: 8) {
            Text(key.label)
                .font(NeonFont.body(14, bold: true))
                .foregroundColor(Neon.text)
            SettingsBadge(text: saved ? "Saved" : "Not set", color: saved ? Neon.positive : Neon.textMuted, filled: saved)
            Spacer(minLength: 0)
        }
    }

    private var entryRow: some View {
        HStack(spacing: 10) {
            entryField
                .textFieldStyle(.plain)
                .font(NeonFont.body(14))
                .padding(8)
                .neonPanel()
                .onSubmit { save() }
            Button("Save") { save() }
                .buttonStyle(NeonButtonStyle(prominent: !SettingsText.trim(draft).isEmpty))
                .disabled(SettingsText.trim(draft).isEmpty)
            if saved {
                Button("Remove") { remove() }
                    .buttonStyle(NeonButtonStyle())
            }
        }
    }

    @ViewBuilder
    private var entryField: some View {
        if key == .openSubtitlesUser {
            TextField(placeholder, text: $draft)
        } else {
            SecureField(placeholder, text: $draft)
        }
    }

    private var placeholder: String {
        saved ? "Saved. Enter a new one to replace it." : SettingsSecretInfo.placeholder(key)
    }

    private func save() {
        let value = SettingsText.trim(draft)
        guard !value.isEmpty else { return }
        Secrets.set(key, value)
        draft = ""
        saved = Secrets.has(key)
    }

    private func remove() {
        guard TextPrompt.confirm(
            title: "Remove your \(key.label)?",
            message: "Anything that uses it stops working until you add it again.",
            action: "Remove"
        ) else { return }
        Secrets.set(key, nil)
        draft = ""
        saved = Secrets.has(key)
    }
}

private enum SettingsSecretInfo {
    static func hint(_ key: SecretKey) -> String {
        switch key {
        case .claude:
            return "Powers Ask Claude. Sign in to the Anthropic Console, open API keys and create one."
        case .tmdb:
            return "Posters, plots, cast and trending titles. Free: make an account, then copy the API key (the read access token works too)."
        case .trakt:
            return "Ratings and comments. Create an app (any name, redirect URI urn:ietf:wg:oauth:2.0:oob) and copy its Client ID."
        case .openSubtitles:
            return "Finds subtitles from inside the player. Create a consumer to get a free API key."
        case .openSubtitlesUser:
            return "Optional. Your opensubtitles.com username."
        case .openSubtitlesPassword:
            return "Optional. Your opensubtitles.com password, used with the username above."
        case .github:
            return "Sends crash reports to your own repository. Create a fine-grained token with Issues set to read and write, for that repository only."
        case .telegramBot:
            return "Your own bot. In Telegram, message @BotFather, send /newbot and copy the token it gives you."
        case .coinMarketCap:
            return "Prices and market data in Markets. The free Basic plan is enough."
        case .gemini:
            return "Powers the Gemini page. It can see the title that is playing and the live guide entry."
        case .youtube:
            return "Search and trending. Your own subscriptions open in the YouTube page, because a key cannot read them."
        case .realDebrid:
            return "Turns torrent results in Infinity into a file. The token is from real-debrid.com/apitoken."
        case .torbox:
            return "Used when Real-Debrid doesn't have the torrent cached."
        }
    }

    static func placeholder(_ key: SecretKey) -> String {
        switch key {
        case .claude: return "sk-ant-…"
        case .github: return "github_pat_…"
        case .telegramBot: return "123456789:AA…"
        case .openSubtitlesUser: return "Username"
        case .openSubtitlesPassword: return "Password"
        default: return "Paste your key"
        }
    }

    static func linkTitle(_ key: SecretKey) -> String {
        switch key {
        case .claude: return "console.anthropic.com"
        case .tmdb: return "themoviedb.org/settings/api"
        case .trakt: return "trakt.tv/oauth/applications"
        case .openSubtitles: return "opensubtitles.com/consumers"
        case .openSubtitlesUser, .openSubtitlesPassword: return "opensubtitles.com"
        case .github: return "github.com/settings/personal-access-tokens"
        case .telegramBot: return "@BotFather in Telegram"
        case .coinMarketCap: return "coinmarketcap.com/api"
        case .gemini: return "aistudio.google.com"
        case .youtube: return "console.cloud.google.com"
        case .realDebrid: return "real-debrid.com/apitoken"
        case .torbox: return "torbox.app"
        }
    }

    static func address(_ key: SecretKey) -> String {
        switch key {
        case .claude: return "https://console.anthropic.com"
        case .tmdb: return "https://www.themoviedb.org/settings/api"
        case .trakt: return "https://trakt.tv/oauth/applications"
        case .openSubtitles: return "https://www.opensubtitles.com/consumers"
        case .openSubtitlesUser, .openSubtitlesPassword: return "https://www.opensubtitles.com"
        case .github: return "https://github.com/settings/personal-access-tokens"
        case .telegramBot: return "https://t.me/BotFather"
        case .coinMarketCap: return "https://coinmarketcap.com/api/"
        case .gemini: return "https://aistudio.google.com/apikey"
        case .youtube: return "https://console.cloud.google.com/apis/library/youtube.googleapis.com"
        case .realDebrid: return "https://real-debrid.com/apitoken"
        case .torbox: return "https://torbox.app"
        }
    }
}

// MARK: - Reports

@MainActor
private struct SettingsReportsPage: View {
    let openServices: () -> Void
    @AppStorage(Reports.repoKey) private var repository: String = ""
    @AppStorage(Reports.autoUploadKey) private var autoUpload: Bool = false
    @State private var reports: [CrashReport] = []
    @State private var viewing: CrashReport? = nil
    @State private var sending: String? = nil
    @State private var sent: [String: URL] = [:]
    @State private var failures: [String: String] = [:]
    @State private var note = ""
    @State private var status: String? = nil
    @State private var logLines: [LogLine] = []
    @State private var hasToken = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .reports)
            githubCard
            reportsCard
            logCard
        }
        .onAppear { reload() }
        .sheet(item: $viewing) { report in
            SettingsReportSheet(report: report)
        }
    }

    private var githubCard: some View {
        SettingsCard(title: "Send reports to GitHub",
                     subtitle: "Reports are filed as issues in your own repository. Make it a PRIVATE repository: reports include your Mac model, settings and recent errors. Logins, keys and server addresses are removed first.") {
            SettingsTextField(title: "Repository", hint: "Owner and name, like yourname/chud-streams-reports.",
                              placeholder: "yourname/chud-streams-reports", text: $repository, width: 260)
            if !SettingsText.trim(repository).isEmpty && !repository.contains("/") {
                SettingsHint(text: "Enter it as owner/name, with a slash in between.", color: Neon.danger)
            }
            SettingsToggle(title: "Send crash reports automatically",
                           hint: "After a crash, the report is sent the next time the app starts.",
                           isOn: $autoUpload)
            SettingsDivider()
            tokenStatus
            SettingsExternalLink(title: "Create a private repository on GitHub", address: "https://github.com/new")
        }
    }

    @ViewBuilder
    private var tokenStatus: some View {
        if hasToken {
            Text("GitHub token saved.")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.positive)
        } else {
            HStack(spacing: 10) {
                Text("No GitHub token yet.")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                SettingsLinkButton(title: "Add one in Services") { openServices() }
                Spacer(minLength: 0)
            }
        }
    }

    private var reportsCard: some View {
        SettingsCard(title: "Reports",
                     subtitle: "Crashes and errors are saved here by themselves. You can also save one yourself when something isn't right.") {
            snapshotRow
            HStack(spacing: 10) {
                Button("Export all") { exportAll() }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(reports.isEmpty)
                if let status {
                    Text(status)
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            if reports.isEmpty {
                SettingsHint(text: "No reports yet. That's good news.")
            } else {
                reportList
            }
        }
    }

    private var snapshotRow: some View {
        HStack(spacing: 10) {
            TextField("What happened? (optional)", text: $note)
                .textFieldStyle(.plain)
                .font(NeonFont.body(14))
                .padding(8)
                .neonPanel()
                .onSubmit { saveSnapshot() }
            Button("Save a report now") { saveSnapshot() }
                .buttonStyle(NeonButtonStyle(prominent: true))
        }
    }

    private var reportList: some View {
        VStack(spacing: 8) {
            ForEach(reports) { report in
                SettingsReportRow(
                    report: report,
                    link: sent[report.id],
                    failure: failures[report.id],
                    sending: sending == report.id,
                    onView: { viewing = report },
                    onSend: { send(report) },
                    onDelete: { delete(report) }
                )
            }
        }
    }

    private var logCard: some View {
        SettingsCard(title: "Recent activity", subtitle: "The last 50 lines of the app's log. Private details are removed before anything is written.") {
            if logLines.isEmpty {
                SettingsHint(text: "Nothing logged yet.")
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 3) {
                        ForEach(Array(logLines.enumerated()), id: \.offset) { _, line in
                            SettingsLogLine(line: line)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                }
                .frame(height: 220)
                .background(HudShape(cut: 8).fill(Neon.background.opacity(0.5)))
            }
            Button("Refresh") { reload() }
                .buttonStyle(NeonButtonStyle())
        }
    }

    private func reload() {
        reports = Reports.all()
        logLines = Array(ErrorLog.recent().suffix(50))
        hasToken = Secrets.has(.github)
    }

    private func saveSnapshot() {
        let text = SettingsText.trim(note)
        if Reports.saveSnapshot(note: text) != nil {
            note = ""
            status = "Report saved."
        } else {
            status = "Couldn't save the report."
        }
        reload()
    }

    private func exportAll() {
        do {
            let zip = try Reports.export()
            NSWorkspace.shared.activateFileViewerSelecting([zip])
            status = "Saved to Downloads as \(zip.lastPathComponent)."
        } catch {
            status = "Couldn't export: \(SettingsText.message(for: error))"
        }
    }

    private func send(_ report: CrashReport) {
        guard sending == nil else { return }
        sending = report.id
        failures[report.id] = nil
        Task {
            do {
                let page = try await Reports.upload(report)
                sent[report.id] = page
            } catch {
                failures[report.id] = SettingsText.message(for: error)
            }
            sending = nil
            reports = Reports.all()
        }
    }

    private func delete(_ report: CrashReport) {
        guard TextPrompt.confirm(title: "Delete this report?", message: report.title, action: "Delete") else { return }
        Reports.delete(report)
        sent[report.id] = nil
        failures[report.id] = nil
        reload()
    }
}

@MainActor
private struct SettingsReportRow: View {
    let report: CrashReport
    let link: URL?
    let failure: String?
    let sending: Bool
    let onView: () -> Void
    let onSend: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: report.kind == "Crash" ? "exclamationmark.triangle" : "doc.text")
                    .foregroundColor(report.kind == "Crash" ? Neon.danger : Neon.cyan)
                Text(report.title)
                    .font(NeonFont.body(14, bold: true))
                    .foregroundColor(Neon.text)
                    .lineLimit(1)
                if report.uploaded || link != nil {
                    SettingsBadge(text: "Sent", color: Neon.positive)
                }
                Spacer(minLength: 8)
                SettingsLinkButton(title: "View") { onView() }
                SettingsLinkButton(title: sending ? "Sending…" : "Send to GitHub") { onSend() }
                    .disabled(sending)
                SettingsLinkButton(title: "Delete", color: Neon.danger) { onDelete() }
            }
            if let link {
                SettingsExternalLink(title: "Open the issue on GitHub", address: link.absoluteString)
            }
            if let failure {
                Text(failure)
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.danger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HudShape(cut: 8).fill(Neon.background.opacity(0.35)))
        .overlay(HudShape(cut: 8).stroke(Neon.cyan.opacity(0.15), lineWidth: 1))
    }
}

@MainActor
private struct SettingsReportSheet: View {
    let report: CrashReport
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Text(report.title)
                    .font(NeonFont.display(16))
                    .foregroundColor(Neon.text)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Button(copied ? "Copied" : "Copy") { copy() }
                    .buttonStyle(NeonButtonStyle())
                Button("Close") { dismiss() }
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .keyboardShortcut(.cancelAction)
            }
            ScrollView {
                Text(text.isEmpty ? "This report is empty or couldn't be read." : text)
                    .font(.system(size: 11, weight: .regular, design: .monospaced))
                    .foregroundColor(Neon.textSecondary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .background(HudShape(cut: 8).fill(Neon.background.opacity(0.6)))
        }
        .padding(20)
        .frame(width: 760, height: 540)
        .background(Neon.backgroundSoft)
        .onAppear { text = String(Reports.text(of: report).prefix(300_000)) }
    }

    private func copy() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        copied = true
    }
}

@MainActor
private struct SettingsLogLine: View {
    let line: LogLine

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(line.date.formatted(date: .omitted, time: .standard))
                .foregroundColor(Neon.textMuted)
            Text(line.text)
                .foregroundColor(line.level == "error" ? Neon.danger : Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.system(size: 11, weight: .regular, design: .monospaced))
        .textSelection(.enabled)
    }
}

// MARK: - General

@MainActor
private struct SettingsGeneralPage: View {
    @ObservedObject var userData: UserData
    @AppStorage("launchAnimation") private var launchAnimation: Bool = true

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsPageHeader(page: .general)
            SettingsCard(title: "App") {
                SettingsToggle(title: "Launch animation",
                               hint: "The logo spins for a moment when the app opens.",
                               isOn: $launchAnimation)
            }
            dataCard
            shortcutsCard
            aboutCard
        }
    }

    private var dataCard: some View {
        SettingsCard(title: "Your data") {
            SettingsRow(title: "Watch history",
                        hint: "\(SettingsText.plural(userData.history.count, "item")) in Continue watching and your history. Saved film positions are kept.") {
                Button("Clear…") { clearHistory() }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(userData.history.isEmpty)
            }
            SettingsDivider()
            SettingsRow(title: "Category arrangements",
                        hint: "Your own order of categories, and the ones you've hidden, for every source.") {
                Button("Reset…") { resetArrangements() }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(userData.arrangements.isEmpty)
            }
            SettingsDivider()
            SettingsRow(title: "App data folder",
                        hint: "Saved lists, history and reports live here.") {
                Button("Show in Finder") { NSWorkspace.shared.open(CatalogCache.directory) }
                    .buttonStyle(NeonButtonStyle())
            }
        }
    }

    private var shortcutsCard: some View {
        SettingsCard(title: "Keyboard shortcuts", subtitle: "Player keys work while the player fills the window.") {
            Text("In the player")
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(Neon.textSecondary)
            shortcutColumns(SettingsShortcuts.player)
            SettingsDivider()
            Text("Anywhere in the app")
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(Neon.textSecondary)
            shortcutColumn(SettingsShortcuts.app)
        }
    }

    private var aboutCard: some View {
        SettingsCard(title: "About") {
            HStack(spacing: 14) {
                if let badge = BrandImage.badge {
                    Image(nsImage: badge)
                        .resizable()
                        .frame(width: 56, height: 56)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Chud Supreme for Mac")
                        .font(NeonFont.display(16))
                        .foregroundColor(Neon.text)
                    Text(SettingsText.version())
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textSecondary)
                    Text(SettingsText.systemVersion())
                        .font(NeonFont.body(12))
                        .foregroundColor(Neon.textMuted)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private func shortcutColumns(_ list: [SettingsShortcut]) -> some View {
        let half = (list.count + 1) / 2
        return HStack(alignment: .top, spacing: 24) {
            shortcutColumn(Array(list.prefix(half)))
            shortcutColumn(Array(list.dropFirst(half)))
        }
    }

    private func shortcutColumn(_ list: [SettingsShortcut]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(list) { item in
                SettingsShortcutRow(shortcut: item)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func clearHistory() {
        guard TextPrompt.confirm(
            title: "Clear watch history?",
            message: "Continue watching and your viewing history are emptied. Saved positions in films and episodes are kept.",
            action: "Clear"
        ) else { return }
        userData.clearHistory()
    }

    private func resetArrangements() {
        guard TextPrompt.confirm(
            title: "Reset category arrangements?",
            message: "Every source's categories go back to the provider's order, and hidden categories come back.",
            action: "Reset"
        ) else { return }
        let keys = Array(userData.arrangements.keys)
        for key in keys {
            guard let bar = key.lastIndex(of: "|") else { continue }
            let sourceId = String(key[..<bar])
            let kindName = String(key[key.index(after: bar)...])
            if let kind = ContentKind(rawValue: kindName) {
                userData.resetArrangement(source: sourceId, kind: kind)
            }
        }
    }
}

private struct SettingsShortcut: Identifiable {
    let keys: String
    let action: String
    var id: String { keys }
}

private enum SettingsShortcuts {
    static let player: [SettingsShortcut] = [
        SettingsShortcut(keys: "Space", action: "Play or pause"),
        SettingsShortcut(keys: "← →", action: "Skip back or forward"),
        SettingsShortcut(keys: "↑ ↓", action: "Next or previous channel (volume in films)"),
        SettingsShortcut(keys: "F", action: "Full screen"),
        SettingsShortcut(keys: "M", action: "Mute"),
        SettingsShortcut(keys: "S", action: "Next subtitle track"),
        SettingsShortcut(keys: "A", action: "Next audio track"),
        SettingsShortcut(keys: "Z X", action: "Subtitles earlier or later"),
        SettingsShortcut(keys: ", .", action: "Audio earlier or later (comma, full stop)"),
        SettingsShortcut(keys: "[ ]", action: "Slower or faster"),
        SettingsShortcut(keys: "0", action: "Normal speed"),
        SettingsShortcut(keys: "P", action: "Mini player"),
        SettingsShortcut(keys: "V", action: "Multiview (live TV)"),
        SettingsShortcut(keys: "N", action: "Next episode"),
        SettingsShortcut(keys: "I", action: "Info"),
        SettingsShortcut(keys: "C", action: "Channel list (live TV)"),
        SettingsShortcut(keys: "Esc", action: "Close the panel, then the player"),
    ]

    static let app: [SettingsShortcut] = [
        SettingsShortcut(keys: "⌘1–9", action: "Home, Live TV, Guide, Films, Series, Library, Favourites, Ask Claude, Markets"),
        SettingsShortcut(keys: "⌘F", action: "Search"),
        SettingsShortcut(keys: "⌘,", action: "Settings"),
        SettingsShortcut(keys: "⇧⌘P", action: "Play or pause"),
        SettingsShortcut(keys: "⇧⌘M", action: "Mini player"),
        SettingsShortcut(keys: "⇧⌘F", action: "Full screen player"),
        SettingsShortcut(keys: "⇧⌘V", action: "Multiview"),
        SettingsShortcut(keys: "⌘.", action: "Stop"),
    ]
}

@MainActor
private struct SettingsShortcutRow: View {
    let shortcut: SettingsShortcut

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(shortcut.keys)
                .font(NeonFont.body(12, bold: true))
                .foregroundColor(Neon.cyan)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: 4).stroke(Neon.cyan.opacity(0.4), lineWidth: 1))
                .frame(width: 64, alignment: .leading)
            Text(shortcut.action)
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Building blocks

/// A titled panel holding one group of settings.
@MainActor
private struct SettingsCard<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(NeonFont.display(16))
                    .foregroundColor(Neon.magenta)
                if let subtitle {
                    SettingsHint(text: subtitle)
                }
            }
            content()
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .neonPanel()
    }
}

/// A setting's name and explanation, with its control on the right (or underneath when stacked).
@MainActor
private struct SettingsRow<Control: View>: View {
    let title: String
    var hint: String? = nil
    var stacked = false
    @ViewBuilder var control: () -> Control

    var body: some View {
        if stacked {
            VStack(alignment: .leading, spacing: 8) {
                labels
                control()
            }
        } else {
            HStack(alignment: .center, spacing: 16) {
                labels
                    .frame(maxWidth: .infinity, alignment: .leading)
                control()
            }
        }
    }

    private var labels: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(NeonFont.body(14, bold: true))
                .foregroundColor(Neon.text)
                .fixedSize(horizontal: false, vertical: true)
            if let hint {
                SettingsHint(text: hint)
            }
        }
    }
}

@MainActor
private struct SettingsToggle: View {
    let title: String
    var hint: String? = nil
    @Binding var isOn: Bool

    var body: some View {
        SettingsRow(title: title, hint: hint) {
            Toggle(title, isOn: $isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Neon.cyan)
        }
    }
}

/// A menu (or segmented control) choosing one of a few values.
@MainActor
private struct SettingsChoice<Value: Hashable>: View {
    let title: String
    var hint: String? = nil
    @Binding var selection: Value
    let options: [Value]
    let label: (Value) -> String
    var width: CGFloat = 260
    var segmented = false
    var stacked = false

    var body: some View {
        SettingsRow(title: title, hint: hint, stacked: stacked) {
            if segmented {
                picker
                    .pickerStyle(.segmented)
                    .frame(width: width)
            } else {
                picker
                    .pickerStyle(.menu)
                    .frame(width: width)
            }
        }
    }

    private var picker: some View {
        Picker(title, selection: $selection) {
            ForEach(options, id: \.self) { option in
                Text(label(option)).tag(option)
            }
        }
        .labelsHidden()
    }
}

@MainActor
private struct SettingsSlider: View {
    let title: String
    var hint: String? = nil
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double
    let format: (Double) -> String

    var body: some View {
        SettingsRow(title: "\(title): \(format(value))", hint: hint) {
            Slider(value: $value, in: range, step: step)
                .tint(Neon.cyan)
                .frame(width: 220)
        }
    }
}

/// A one-line text setting, saved as you type.
@MainActor
private struct SettingsTextField: View {
    let title: String
    var hint: String? = nil
    let placeholder: String
    @Binding var text: String
    var width: CGFloat = 260

    var body: some View {
        SettingsRow(title: title, hint: hint) {
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(NeonFont.body(14))
                .padding(8)
                .neonPanel()
                .frame(width: width)
        }
    }
}

/// A labelled field for the add-source form.
@MainActor
private struct SettingsFormField: View {
    let label: String
    var prompt: String = ""
    @Binding var text: String
    var secure = false
    var action: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(Neon.textSecondary)
            field
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(10)
                .neonPanel()
                .onSubmit { action() }
        }
    }

    @ViewBuilder
    private var field: some View {
        if secure {
            SecureField(prompt, text: $text)
        } else {
            TextField(prompt, text: $text)
        }
    }
}

@MainActor
private struct SettingsHint: View {
    let text: String
    var color: Color = Neon.textSecondary

    var body: some View {
        Text(text)
            .font(NeonFont.body(12))
            .foregroundColor(color)
            .fixedSize(horizontal: false, vertical: true)
    }
}

@MainActor
private struct SettingsDivider: View {
    var body: some View {
        Rectangle()
            .fill(Neon.cyan.opacity(0.08))
            .frame(height: 1)
    }
}

@MainActor
private struct SettingsBadge: View {
    let text: String
    var color: Color = Neon.cyan
    var filled = true

    var body: some View {
        Text(text)
            .font(NeonFont.body(11, bold: true))
            .foregroundColor(filled ? Neon.onCyan : color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Capsule().fill(filled ? color : Color.clear))
            .overlay(Capsule().stroke(color.opacity(filled ? 0 : 0.7), lineWidth: 1))
    }
}

/// A small text button for secondary actions.
@MainActor
private struct SettingsLinkButton: View {
    let title: String
    var color: Color = Neon.cyan
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(color)
                .underline(hovering && isEnabled)
                .lineLimit(1)
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.45)
        .onHover { hovering = $0 }
    }
}

/// Opens a web page in the browser.
@MainActor
private struct SettingsExternalLink: View {
    let title: String
    let address: String

    var body: some View {
        if let url = URL(string: address) {
            Link(destination: url) {
                HStack(spacing: 4) {
                    Text(title)
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 10, weight: .bold))
                }
                .font(NeonFont.body(12, bold: true))
                .foregroundColor(Neon.cyan)
            }
        }
    }
}

// MARK: - Wording

private enum SettingsText {
    static func message(for error: Error) -> String {
        if let described = (error as? LocalizedError)?.errorDescription, !described.isEmpty { return described }
        return error.localizedDescription
    }

    static func trim(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The usual choices, plus the saved value if it's something else.
    static func including<T: Comparable>(_ current: T, in base: [T]) -> [T] {
        base.contains(current) ? base : (base + [current]).sorted()
    }

    static func seconds(_ value: Int) -> String { "\(value)s" }

    static func speed(_ value: Double) -> String { String(format: "%g×", value) }

    static func plural(_ count: Int, _ word: String) -> String {
        count == 1 ? "1 \(word)" : "\(formatCount(count)) \(word)s"
    }

    static func listSummary(count: Int, updated: Date) -> String {
        guard count > 0 else { return "None" }
        let ago: String = updated.formatted(.relative(presentation: .named))
        return "\(formatCount(count)) · updated \(ago)"
    }

    static func title(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "Channels"
        case .movie: return "Films"
        case .series: return "Series"
        }
    }

    static func noun(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "channels"
        case .movie: return "films"
        case .series: return "series"
        }
    }

    static func accountStatus(_ account: AccountInfo) -> String {
        let status = (account.status ?? "Active").capitalized
        return account.isTrial ? "\(status) (trial)" : status
    }

    static func expiry(_ date: Date?) -> String {
        guard let date else { return "Never" }
        let day = date.formatted(date: .abbreviated, time: .omitted)
        if date < Date() { return "\(day) (expired)" }
        let days = Calendar.current.dateComponents([.day], from: Date(), to: date).day ?? 0
        if days == 0 { return "\(day) (today)" }
        return "\(day) (\(days) day\(days == 1 ? "" : "s") left)"
    }

    static func engine(_ engine: PlayerEngine) -> String {
        switch engine {
        case .builtIn:
            return "Plays MKV, AVI, MPEG-TS, HEVC and AV1 inside the app, with every option on these pages."
        case .apple:
            return "Apple's own player: smooth and efficient, but only MP4 and HLS. Most options here don't apply to it."
        case .vlc:
            return "Streams open in VLC, outside the app. VLC is free from videolan.org."
        }
    }

    static func renderer(_ renderer: VideoRenderer) -> String {
        switch renderer {
        case .auto:
            return "Uses Metal for HDR and Dolby Vision when this Mac's screen (or a connected TV) can show HDR, and dependable OpenGL otherwise."
        case .standard:
            return "Dependable OpenGL output. Advanced adds HDR output and Dolby Vision profile 5."
        case .advanced:
            return "Uses Metal (gpu-next) for HDR output and Dolby Vision profile 5. Experimental: if the picture goes black or the colours look wrong, switch back to Standard."
        }
    }

    static func toneMapping(_ mode: ToneMapping) -> String {
        switch mode {
        case .auto: return "Lets the player pick the best curve for each film. Right for most things."
        case .bt2390: return "The broadcast-standard curve: the most accurate brightness and colour."
        case .hable: return "Film-like, rolling off bright highlights gently. A little darker overall."
        case .mobius: return "Keeps contrast and colour in bright scenes, changing the rest very little."
        case .reinhard: return "Soft and even. Can look a little flat."
        case .clip: return "No tone mapping: anything too bright is simply cut off. Mainly for testing."
        }
    }

    static func dolbyVision(_ mode: DolbyVisionMode) -> String {
        switch mode {
        case .auto: return "Uses the Dolby Vision data so colours come out right. Profile 5 files need the Advanced renderer."
        case .baseLayer: return "Plays the plain HDR10 or SDR picture underneath instead. Try this if Dolby Vision looks green or purple."
        }
    }

    static func version() -> String {
        let info = Bundle.main.infoDictionary
        guard let short = info?["CFBundleShortVersionString"] as? String else { return "Development build" }
        if let build = info?["CFBundleVersion"] as? String, build != short {
            return "Version \(short) (build \(build))"
        }
        return "Version \(short)"
    }

    static func systemVersion() -> String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "macOS \(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
    }
}
