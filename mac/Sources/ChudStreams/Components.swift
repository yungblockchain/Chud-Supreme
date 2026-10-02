import AppKit
import SwiftUI

// Shared building blocks for the screens: cards, rows, section headers, the right-click menu and
// empty states. Everything clickable lights up cyan on hover and eases in and out.

/// Big section heading with an optional subtitle and trailing content.
@MainActor
struct SectionHeader<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(NeonFont.display(18))
                    .foregroundColor(Neon.text)
                if let subtitle {
                    Text(subtitle).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
                }
            }
            Spacer()
            trailing()
        }
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = { EmptyView() }
    }
}

/// A 2:3 poster card for films and series.
@MainActor
struct PosterCard: View {
    @EnvironmentObject private var userData: UserData
    let item: MediaItem
    var width: CGFloat? = nil
    var progress: Double? = nil
    var badge: String? = nil
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                Color.clear
                    .aspectRatio(2.0 / 3.0, contentMode: .fit)
                    .overlay(RemoteImage(url: item.icon, contentMode: .fill))
                    .overlay(alignment: .topTrailing) {
                        if userData.inLibrary(item) {
                            Image(systemName: "bookmark.fill")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(Neon.magenta)
                                .padding(7)
                                .background(Circle().fill(Color.black.opacity(0.55)))
                                .padding(6)
                        }
                    }
                    .overlay(alignment: .topLeading) {
                        if let badge {
                            Text(badge)
                                .font(NeonFont.body(11, bold: true))
                                .foregroundColor(Neon.onCyan)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(Neon.cyan))
                                .padding(6)
                        }
                    }
                    .overlay(alignment: .bottom) {
                        if let progress, progress > 0 {
                            GeometryReader { geometry in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color.black.opacity(0.6))
                                    Rectangle().fill(Neon.cyan).frame(width: geometry.size.width * CGFloat(progress))
                                }
                            }
                            .frame(height: 4)
                        }
                    }
                    .clipShape(HudShape(cut: 12))
                    .overlay(HudShape(cut: 12).stroke(Neon.cyan.opacity(hovering ? 1 : 0.18), lineWidth: hovering ? 2 : 1))
                    .shadow(color: Neon.cyan.opacity(hovering ? 0.5 : 0), radius: 14)
                Text(item.cleanName)
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(hovering ? Neon.cyan : Neon.text)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let year = item.titleYear {
                    Text(String(year)).font(NeonFont.body(11)).foregroundColor(Neon.textMuted)
                }
            }
            .frame(width: width)
            .scaleEffect(hovering ? 1.04 : 1)
            .animation(Motion.hover, value: hovering)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .contextMenu { MediaContextMenu(item: item) }
    }
}

/// A wide 16:9 card: continue watching, trending, episodes.
@MainActor
struct WideCard: View {
    let title: String
    var subtitle: String? = nil
    let image: String?
    var progress: Double? = nil
    var live: Bool = false
    var width: CGFloat = 280
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 7) {
                ZStack(alignment: .bottomLeading) {
                    Color.clear
                        .aspectRatio(16.0 / 9.0, contentMode: .fit)
                        .overlay(RemoteImage(url: image, contentMode: live ? .fit : .fill, animated: live).padding(live ? 26 : 0))
                        .background(live ? Neon.surface : Color.clear)
                    LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                    if live {
                        HStack(spacing: 5) {
                            Circle().fill(Neon.danger).frame(width: 7, height: 7)
                            Text("LIVE").font(NeonFont.display(10)).foregroundColor(Neon.text)
                        }
                        .padding(8)
                    }
                    if let progress, progress > 0 {
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                Rectangle().fill(Color.white.opacity(0.25))
                                Rectangle().fill(Neon.cyan).frame(width: geometry.size.width * CGFloat(progress))
                            }
                        }
                        .frame(height: 4)
                    }
                    if hovering {
                        Image(systemName: "play.fill")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundColor(Neon.onCyan)
                            .frame(width: 52, height: 52)
                            .background(Circle().fill(Neon.cyan))
                            .shadow(color: Neon.cyan.opacity(0.7), radius: 12)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
                .clipShape(HudShape(cut: 12))
                .overlay(HudShape(cut: 12).stroke(Neon.cyan.opacity(hovering ? 1 : 0.18), lineWidth: hovering ? 2 : 1))
                .shadow(color: Neon.cyan.opacity(hovering ? 0.45 : 0), radius: 14)
                Text(title)
                    .font(NeonFont.body(14, bold: true))
                    .foregroundColor(hovering ? Neon.cyan : Neon.text)
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle).font(NeonFont.body(12)).foregroundColor(Neon.textMuted).lineLimit(1)
                }
            }
            .frame(width: width)
            .scaleEffect(hovering ? 1.03 : 1)
            .animation(Motion.hover, value: hovering)
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

/// One live channel in a list: number, logo, name, what's on now with progress, and next.
@MainActor
struct ChannelRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    let item: MediaItem
    let number: Int
    var playing: Bool = false
    @State private var hovering = false

    var body: some View {
        let now = Date()
        let programmes = model.nowNext(for: item)
        let current = programmes.first { $0.isOn(at: now) }
        let next = programmes.first { $0.start >= (current?.end ?? now) }
        HStack(spacing: 14) {
            Text("\(number)")
                .font(NeonFont.display(15))
                .foregroundColor(hovering ? Neon.onCyan : Neon.cyan)
                .frame(width: 48, alignment: .trailing)
            ChannelLogo(url: item.icon, size: CGSize(width: 58, height: 42))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(NeonFont.body(15, bold: true))
                    .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                    .lineLimit(1)
                if let current {
                    Text("Now: \(current.title)")
                        .font(NeonFont.body(13))
                        .foregroundColor(hovering ? Neon.onCyan.opacity(0.8) : Neon.textSecondary)
                        .lineLimit(1)
                    ProgressView(value: now.timeIntervalSince(current.start), total: max(1, current.end.timeIntervalSince(current.start)))
                        .tint(hovering ? Neon.onCyan : Neon.cyan)
                }
                if let next {
                    Text("Next \(next.start.formatted(date: .omitted, time: .shortened)): \(next.title)")
                        .font(NeonFont.body(12))
                        .foregroundColor(hovering ? Neon.onCyan.opacity(0.7) : Neon.textMuted)
                        .lineLimit(1)
                }
            }
            Spacer()
            if item.hasArchive == true {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundColor(hovering ? Neon.onCyan : Neon.textMuted)
                    .help("Catch-up available")
            }
            if playing {
                Image(systemName: "speaker.wave.2.fill").foregroundColor(hovering ? Neon.onCyan : Neon.magenta)
            }
            if userData.isFavourite(item) {
                Image(systemName: "star.fill").foregroundColor(hovering ? Neon.onCyan : Neon.magenta)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(HudShape().fill(hovering ? Neon.cyan : Neon.surface.opacity(0.7)))
        .overlay(HudShape().stroke(Neon.cyan.opacity(hovering ? 1 : 0.15), lineWidth: 1))
        .contentShape(Rectangle())
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .task(id: item.id) { await model.loadEPG(for: item) }
    }
}

/// The right-click (or long-press) menu for any channel, film or series.
@MainActor
struct MediaContextMenu: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @EnvironmentObject private var playback: PlaybackCenter
    let item: MediaItem
    var list: [MediaItem] = []

    var body: some View {
        if item.kind == .live {
            Button("Play") { model.playLive(item, in: list.isEmpty ? [item] : list) }
            Button("Add to multiview") {
                playback.openMultiview()
                playback.addTile(item)
            }
            Button("Play in VLC") {
                if let url = model.liveURL(for: item, hls: false) { model.openExternally(url) }
            }
        } else {
            Button("Open details") { model.open(item) }
            if item.kind == .movie {
                Button("Play") { model.playMovie(item, containerExtension: item.containerExtension, fromStart: false) }
                Button("Play from the start") { model.playMovie(item, containerExtension: item.containerExtension, fromStart: true) }
                Button("Play in VLC") {
                    let url = item.url.flatMap { URL(string: $0) } ?? model.client(for: item)?.movieURL(item, containerExtension: item.containerExtension)
                    if let url { model.openExternally(url) }
                }
            }
            Button(userData.inLibrary(item) ? "Remove from Library" : "Add to Library") { userData.toggleLibrary(item) }
        }
        Divider()
        Button(userData.isFavourite(item) ? "Remove from Favourites" : "Add to Favourites") { userData.toggleFavourite(item) }
        Menu("Add to group") {
            ForEach(userData.groups.filter { $0.id != FavouriteGroup.defaultId }) { group in
                let inGroup = group.items.contains { $0.id == item.id }
                Button((inGroup ? "✓ " : "") + group.name) { userData.toggle(item, inGroup: group.id) }
            }
            if userData.groups.count > 1 { Divider() }
            Button("New group…") {
                if let name = TextPrompt.ask(title: "New favourites group", message: "Name the group, for example Sport or Kids.", placeholder: "Group name") {
                    let group = userData.createGroup(named: name)
                    userData.toggle(item, inGroup: group.id)
                }
            }
        }
        Divider()
        Button("Search for similar") { model.pendingSearch = item.cleanName; model.section = .search }
        Button("Copy name") {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(item.name, forType: .string)
        }
    }
}

/// A native text prompt (for naming groups and similar).
enum TextPrompt {
    @MainActor
    static func ask(title: String, message: String, placeholder: String, initial: String = "") -> String? {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        alert.addButton(withTitle: "Cancel")
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 280, height: 24))
        field.placeholderString = placeholder
        field.stringValue = initial
        alert.accessoryView = field
        alert.window.initialFirstResponder = field
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        let value = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    @MainActor
    static func confirm(title: String, message: String, action: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.addButton(withTitle: action)
        alert.addButton(withTitle: "Cancel")
        return alert.runModal() == .alertFirstButtonReturn
    }
}

/// A horizontally scrolling row with a header.
@MainActor
struct ShelfRow<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: title, subtitle: subtitle)
                .padding(.horizontal, 28)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 18) {
                    content()
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 10)
            }
        }
    }
}

/// Shown when a screen has nothing yet.
@MainActor
struct EmptyState: View {
    let symbol: String
    let title: String
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .light))
                .foregroundColor(Neon.cyan)
                .shadow(color: Neon.cyan.opacity(0.6), radius: 12)
            Text(title).font(NeonFont.display(18)).foregroundColor(Neon.text)
            Text(message)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 460)
            if let actionTitle, let action {
                Button(actionTitle, action: action).buttonStyle(NeonButtonStyle(prominent: true))
            }
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A status line for a catalogue that's loading, with how much has arrived.
@MainActor
struct CatalogStatusLine: View {
    let phase: CatalogPhase?
    let noun: String

    var body: some View {
        switch phase {
        case .loading(let bytes)?:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(bytes > 0 ? "Downloading your \(noun): \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)) so far…" : "Asking the provider for your \(noun)…")
            }
            .font(NeonFont.body(13))
            .foregroundColor(Neon.textSecondary)
        case .decoding?:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Sorting your \(noun)…")
            }
            .font(NeonFont.body(13))
            .foregroundColor(Neon.textSecondary)
        case .failed(let message)?:
            Text(message).font(NeonFont.body(13)).foregroundColor(Neon.danger)
        default:
            EmptyView()
        }
    }
}

func formatCount(_ value: Int) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}
