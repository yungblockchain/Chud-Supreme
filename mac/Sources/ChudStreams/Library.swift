import SwiftUI
import UniformTypeIdentifiers

// The viewer's own corner of the app.
//
// Library: the films and shows they've saved (in whatever order they like), what they're partway
// through, and everything they've watched. Favourites: channel groups they build and arrange
// themselves, with the built-in Favourites group always first.

// MARK: - Library

private enum LibraryTab: String, CaseIterable, Identifiable {
    case saved = "Saved"
    case continueWatching = "Continue watching"
    case history = "History"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .saved: return "bookmark.fill"
        case .continueWatching: return "play.circle.fill"
        case .history: return "clock.arrow.circlepath"
        }
    }
}

@MainActor
struct LibraryView: View {
    @EnvironmentObject private var userData: UserData
    @State private var tab: LibraryTab = .saved

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            ZStack(alignment: .top) {
                page
                    .id(tab)
                    .transition(.opacity.combined(with: .offset(y: 8)))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .padding(.top, 28)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                NeonTitle(text: "Library")
                Text("Films and shows you've saved, what you're partway through, and everything you've watched.")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
            }
            HStack(spacing: 10) {
                ForEach(LibraryTab.allCases) { option in
                    LibraryChip(title: option.rawValue, symbol: option.symbol, count: count(for: option), selected: tab == option) {
                        withAnimation(Motion.quick) { tab = option }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 28)
    }

    @ViewBuilder
    private var page: some View {
        switch tab {
        case .saved: LibrarySavedTab()
        case .continueWatching: LibraryContinueTab()
        case .history: LibraryHistoryTab()
        }
    }

    private func count(for option: LibraryTab) -> Int {
        switch option {
        case .saved: return userData.library.count
        case .continueWatching: return userData.continueWatching.count
        case .history: return userData.history.count
        }
    }
}

// MARK: Saved

@MainActor
private struct LibrarySavedTab: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @State private var dragging: MediaItem? = nil

    private var films: [MediaItem] { userData.library.filter { $0.kind == .movie } }
    private var shows: [MediaItem] { userData.library.filter { $0.kind == .series } }

    var body: some View {
        if userData.library.isEmpty {
            EmptyState(
                symbol: "bookmark",
                title: "Nothing saved yet",
                message: "Right-click any film or show and choose Add to Library, or press the bookmark button on its page. Everything you save lands here, in whatever order you like.",
                actionTitle: "Browse films",
                action: { model.go(.movies) }
            )
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    if !films.isEmpty { section("Films", films) }
                    if !shows.isEmpty { section("Series", shows) }
                    Text("Drag posters to rearrange them, or hover for the arrows. Right-click any film or show elsewhere in the app and choose Add to Library to save more.")
                        .font(NeonFont.body(12))
                        .foregroundColor(Neon.textMuted)
                }
                .padding(.horizontal, 28)
                .padding(.top, 6)
                .padding(.bottom, 28)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func section(_ title: String, _ items: [MediaItem]) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: title, subtitle: "\(formatCount(items.count)) saved")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], spacing: 22) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    LibraryPosterTile(
                        item: item,
                        canMoveBack: index > 0,
                        canMoveForward: index < items.count - 1,
                        removeHelp: "Remove from Library",
                        onMove: { step in shift(item, by: step, within: items) },
                        onRemove: { remove(item) }
                    )
                    .onDrag {
                        dragging = item
                        return NSItemProvider(object: item.id as NSString)
                    }
                    .onDrop(of: [UTType.text], isTargeted: nil) { _ in drop(onto: item) }
                }
            }
        }
    }

    private func remove(_ item: MediaItem) {
        withAnimation(Motion.quick) { userData.toggleLibrary(item) }
    }

    /// Swaps with the neighbouring film (or show), leaving the other kind where it is.
    private func shift(_ item: MediaItem, by step: Int, within items: [MediaItem]) {
        guard let position = items.firstIndex(where: { $0.id == item.id }) else { return }
        let target = position + step
        guard target >= 0, target < items.count else { return }
        move(item, onto: items[target])
    }

    /// Puts `item` where `target` is now.
    private func move(_ item: MediaItem, onto target: MediaItem) {
        let all = userData.library
        guard let from = all.firstIndex(where: { $0.id == item.id }),
              let to = all.firstIndex(where: { $0.id == target.id }),
              from != to else { return }
        withAnimation(Motion.quick) {
            userData.moveLibrary(from: IndexSet(integer: from), to: to > from ? to + 1 : to)
        }
    }

    private func drop(onto target: MediaItem) -> Bool {
        defer { dragging = nil }
        guard let moving = dragging, moving.id != target.id, moving.kind == target.kind else { return false }
        move(moving, onto: target)
        return true
    }
}

/// A poster with move and remove buttons that appear on hover.
@MainActor
private struct LibraryPosterTile: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    let canMoveBack: Bool
    let canMoveForward: Bool
    let removeHelp: String
    let onMove: (Int) -> Void
    let onRemove: () -> Void
    @State private var hovering = false

    var body: some View {
        PosterCard(item: item) { model.open(item) }
            .overlay(alignment: .topLeading) { controls }
            .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }

    @ViewBuilder
    private var controls: some View {
        if hovering {
            HStack(spacing: 5) {
                LibraryMiniButton(symbol: "chevron.left", help: "Move left", enabled: canMoveBack) { onMove(-1) }
                LibraryMiniButton(symbol: "chevron.right", help: "Move right", enabled: canMoveForward) { onMove(1) }
                LibraryMiniButton(symbol: "xmark", help: removeHelp, destructive: true) { onRemove() }
            }
            .padding(8)
            .transition(.opacity.combined(with: .scale(scale: 0.85, anchor: .topLeading)))
        }
    }
}

// MARK: Continue watching

@MainActor
private struct LibraryContinueTab: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData

    var body: some View {
        let entries = userData.continueWatching
        if entries.isEmpty {
            EmptyState(
                symbol: "play.circle",
                title: "Nothing to continue",
                message: "Start a film, an episode or a channel and it shows up here, so you can pick up right where you stopped.",
                actionTitle: "Go to Home",
                action: { model.go(.home) }
            )
        } else {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 22)], alignment: .leading, spacing: 26) {
                    ForEach(entries) { entry in
                        LibraryContinueCard(entry: entry)
                    }
                }
                .padding(.horizontal, 28)
                .padding(.top, 6)
                .padding(.bottom, 28)
            }
        }
    }
}

@MainActor
private struct LibraryContinueCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    let entry: HistoryEntry
    @State private var hovering = false

    var body: some View {
        WideCard(
            title: entry.title,
            subtitle: LibraryFormat.subtitle(for: entry),
            image: entry.image ?? entry.item.icon,
            progress: entry.isLive ? nil : entry.progress,
            live: entry.isLive
        ) {
            model.resume(entry)
        }
        .overlay(alignment: .topTrailing) { removeButton }
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .contextMenu {
            Button(entry.isLive ? "Watch" : "Resume") { model.resume(entry) }
            if !entry.isLive {
                Button("Open details") { model.open(entry.item) }
            }
            Divider()
            Button("Remove") { remove() }
        }
    }

    @ViewBuilder
    private var removeButton: some View {
        if hovering {
            LibraryMiniButton(symbol: "xmark", help: "Remove from Continue watching", destructive: true) { remove() }
                .padding(8)
                .transition(.opacity)
        }
    }

    private func remove() {
        withAnimation(Motion.quick) { userData.removeHistory(entry) }
    }
}

// MARK: History

private struct LibraryHistoryDay: Identifiable {
    let day: Date
    let entries: [HistoryEntry]
    var id: Date { day }
}

@MainActor
private struct LibraryHistoryTab: View {
    @EnvironmentObject private var userData: UserData

    var body: some View {
        if userData.history.isEmpty {
            EmptyState(
                symbol: "clock.arrow.circlepath",
                title: "No history yet",
                message: "Every channel, film and episode you watch is listed here, newest first, so you can find it again."
            )
        } else {
            VStack(alignment: .leading, spacing: 8) {
                toolbar
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(days) { day in
                            dayHeader(day.day)
                            ForEach(day.entries) { entry in
                                LibraryHistoryRow(entry: entry)
                            }
                        }
                    }
                    .padding(.horizontal, 28)
                    .padding(.bottom, 28)
                }
            }
        }
    }

    private var toolbar: some View {
        HStack(spacing: 12) {
            Text("Everything you've watched, newest first. Click anything to pick it up again.")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textSecondary)
            Spacer()
            Button { clear() } label: {
                Label("Clear history", systemImage: "trash")
            }
            .buttonStyle(NeonButtonStyle())
        }
        .padding(.horizontal, 28)
    }

    private func dayHeader(_ day: Date) -> some View {
        Text(LibraryFormat.dayTitle(day))
            .font(NeonFont.display(14))
            .foregroundColor(Neon.magenta)
            .padding(.top, 14)
            .padding(.bottom, 2)
    }

    /// History grouped by day, newest first.
    private var days: [LibraryHistoryDay] {
        let calendar = Calendar.current
        var order: [Date] = []
        var grouped: [Date: [HistoryEntry]] = [:]
        let sorted = userData.history.sorted { $0.updatedAt > $1.updatedAt }
        for entry in sorted {
            let day = calendar.startOfDay(for: entry.updatedAt)
            if grouped[day] == nil { order.append(day) }
            grouped[day, default: []].append(entry)
        }
        return order.map { LibraryHistoryDay(day: $0, entries: grouped[$0] ?? []) }
    }

    private func clear() {
        let confirmed = TextPrompt.confirm(
            title: "Clear your watch history?",
            message: "This empties History and Continue watching. Your Library and Favourites aren't affected.",
            action: "Clear history"
        )
        guard confirmed else { return }
        withAnimation(Motion.quick) { userData.clearHistory() }
    }
}

@MainActor
private struct LibraryHistoryRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    let entry: HistoryEntry
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            Button { model.resume(entry) } label: { content }
                .buttonStyle(.plain)
            LibraryMiniButton(symbol: "xmark", help: "Remove from History", destructive: true) { remove() }
                .opacity(hovering ? 1 : 0)
                .allowsHitTesting(hovering)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(HudShape().fill(hovering ? Neon.surfaceRaised : Neon.surface.opacity(0.7)))
        .overlay(HudShape().stroke(Neon.cyan.opacity(hovering ? 0.9 : 0.15), lineWidth: 1))
        .shadow(color: Neon.cyan.opacity(hovering ? 0.3 : 0), radius: 8)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .contextMenu {
            Button(entry.isLive ? "Watch" : "Resume") { model.resume(entry) }
            if !entry.isLive {
                Button("Open details") { model.open(entry.item) }
            }
            Divider()
            Button("Remove from History") { remove() }
        }
    }

    private var content: some View {
        HStack(spacing: 14) {
            thumbnail
                .frame(width: 64, height: 50)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title)
                    .font(NeonFont.body(15, bold: true))
                    .foregroundColor(hovering ? Neon.cyan : Neon.text)
                    .lineLimit(1)
                Text(detail)
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.textMuted)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if !entry.isLive && entry.progress > 0 {
                ProgressView(value: entry.progress)
                    .tint(Neon.cyan)
                    .frame(width: 80)
            }
            Text(entry.updatedAt.formatted(date: .omitted, time: .shortened))
                .font(NeonFont.display(12))
                .foregroundColor(Neon.textSecondary)
        }
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var thumbnail: some View {
        if entry.isLive {
            ChannelLogo(url: entry.image ?? entry.item.icon, size: CGSize(width: 64, height: 44))
        } else {
            RemoteImage(url: entry.image ?? entry.item.icon, contentMode: .fill)
                .frame(width: 34, height: 50)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private var detail: String {
        var parts: [String] = [entry.subtitle ?? LibraryFormat.kindLabel(entry.item.kind)]
        if let left = LibraryFormat.remaining(entry) { parts.append(left) }
        return parts.joined(separator: " · ")
    }

    private func remove() {
        withAnimation(Motion.quick) { userData.removeHistory(entry) }
    }
}

// MARK: Shared pieces

private enum LibraryFormat {
    static func kindLabel(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "Live TV"
        case .movie: return "Film"
        case .series: return "Series"
        }
    }

    /// "32 min left" for films and episodes with a known length.
    static func remaining(_ entry: HistoryEntry) -> String? {
        guard !entry.isLive, entry.duration > 0 else { return nil }
        let seconds = max(0, entry.duration - entry.position)
        let minutes = Int((seconds / 60).rounded())
        if minutes < 1 { return "Almost finished" }
        if minutes < 60 { return "\(minutes) min left" }
        return "\(minutes / 60) h \(minutes % 60) min left"
    }

    static func subtitle(for entry: HistoryEntry) -> String {
        var parts: [String] = []
        if let subtitle = entry.subtitle, !subtitle.isEmpty { parts.append(subtitle) }
        if let left = remaining(entry) { parts.append(left) }
        parts.append(entry.updatedAt.formatted(.relative(presentation: .named)))
        return parts.joined(separator: " · ")
    }

    static func dayTitle(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return "Today" }
        if calendar.isDateInYesterday(day) { return "Yesterday" }
        let sameYear = calendar.component(.year, from: day) == calendar.component(.year, from: Date())
        if sameYear {
            return day.formatted(.dateTime.weekday(.wide).day().month(.wide))
        }
        return day.formatted(.dateTime.day().month(.wide).year())
    }
}

/// A neon tab chip with an optional count.
@MainActor
private struct LibraryChip: View {
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
                if let count, count > 0 {
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

/// A small round button for hover controls (move, remove).
@MainActor
private struct LibraryMiniButton: View {
    let symbol: String
    let help: String
    var destructive = false
    var enabled = true
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                .frame(width: 26, height: 26)
                .background(Circle().fill(hovering ? accent : Color.black.opacity(0.65)))
                .overlay(Circle().stroke(accent.opacity(hovering ? 1 : 0.5), lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .help(help)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside && enabled } }
    }

    private var accent: Color { destructive ? Neon.danger : Neon.cyan }
}

// MARK: - Favourites

@MainActor
struct FavoritesView: View {
    @EnvironmentObject private var userData: UserData
    @State private var selectedId: String = FavouriteGroup.defaultId

    private var selected: FavouriteGroup? {
        userData.groups.first { $0.id == selectedId }
            ?? userData.groups.first { $0.id == FavouriteGroup.defaultId }
            ?? userData.groups.first
    }

    var body: some View {
        HStack(spacing: 0) {
            FavouritesGroupColumn(selectedId: $selectedId)
            Rectangle()
                .fill(Neon.cyan.opacity(0.12))
                .frame(width: 1)
            ZStack(alignment: .top) {
                detail
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
    }

    @ViewBuilder
    private var detail: some View {
        if let group = selected {
            FavouritesGroupDetail(group: group)
                .id(group.id)
                .transition(.opacity.combined(with: .offset(y: 8)))
        } else {
            EmptyState(
                symbol: "star",
                title: "No favourites yet",
                message: "Right-click any channel, film or show and choose Add to Favourites."
            )
        }
    }
}

/// The list of groups: built-in Favourites first, then the viewer's own groups in their order.
@MainActor
private struct FavouritesGroupColumn: View {
    @EnvironmentObject private var userData: UserData
    @Binding var selectedId: String

    private var builtIn: FavouriteGroup? { userData.groups.first { $0.id == FavouriteGroup.defaultId } }
    private var custom: [FavouriteGroup] { userData.groups.filter { $0.id != FavouriteGroup.defaultId } }
    private var current: FavouriteGroup? {
        userData.groups.first { $0.id == selectedId } ?? builtIn ?? userData.groups.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            NeonTitle(text: "Favourites", size: 22)
                .padding(.horizontal, 18)
            Text("GROUPS")
                .font(NeonFont.display(11))
                .foregroundColor(Neon.textMuted)
                .padding(.horizontal, 20)
                .padding(.top, 6)
            groupList
            if custom.isEmpty {
                Text("Make groups like Sport, News or Kids, then right-click any channel and choose Add to group.")
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 18)
            }
            controls
        }
        .padding(.top, 28)
        .padding(.bottom, 18)
        .frame(width: 260)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Neon.backgroundSoft.opacity(0.6))
    }

    private var groupList: some View {
        List {
            if let builtIn {
                row(builtIn)
            }
            ForEach(custom) { group in
                row(group)
            }
            .onMove { source, destination in
                withAnimation(Motion.quick) { moveCustom(from: source, to: destination) }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func row(_ group: FavouriteGroup) -> some View {
        FavouritesGroupRow(group: group, selected: group.id == current?.id) {
            withAnimation(Motion.quick) { selectedId = group.id }
        }
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 2, leading: 8, bottom: 2, trailing: 8))
        .contextMenu { menu(for: group) }
    }

    @ViewBuilder
    private func menu(for group: FavouriteGroup) -> some View {
        Button("Rename…") { rename(group) }
        if group.id != FavouriteGroup.defaultId {
            Button("Move up") { shift(group, by: -1) }
                .disabled(!canShift(group, by: -1))
            Button("Move down") { shift(group, by: 1) }
                .disabled(!canShift(group, by: 1))
            Divider()
            Button("Delete group…") { delete(group) }
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
            Button { newGroup() } label: {
                Label("New group", systemImage: "plus")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(NeonButtonStyle())
            if let group = current {
                HStack(spacing: 8) {
                    control("chevron.up", help: "Move this group up", enabled: canShift(group, by: -1)) { shift(group, by: -1) }
                    control("chevron.down", help: "Move this group down", enabled: canShift(group, by: 1)) { shift(group, by: 1) }
                    control("pencil", help: "Rename this group", enabled: true) { rename(group) }
                    control("trash", help: "Delete this group", enabled: group.id != FavouriteGroup.defaultId) { delete(group) }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 16)
    }

    private func control(_ symbol: String, help: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        IconButton(symbol: symbol, help: help, action: action)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.35)
    }

    // MARK: Actions

    private func newGroup() {
        let name = TextPrompt.ask(
            title: "New favourites group",
            message: "Name the group, for example Sport, News or Kids. Then right-click any channel and choose Add to group.",
            placeholder: "Group name"
        )
        guard let name else { return }
        withAnimation(Motion.quick) {
            let group = userData.createGroup(named: name)
            selectedId = group.id
        }
    }

    private func rename(_ group: FavouriteGroup) {
        let name = TextPrompt.ask(
            title: "Rename “\(group.name)”",
            message: "Choose a new name for this group.",
            placeholder: "Group name",
            initial: group.name
        )
        guard let name else { return }
        withAnimation(Motion.quick) { userData.renameGroup(group.id, to: name) }
    }

    private func delete(_ group: FavouriteGroup) {
        guard group.id != FavouriteGroup.defaultId else { return }
        let confirmed = TextPrompt.confirm(
            title: "Delete “\(group.name)”?",
            message: "Only the group goes. Its channels, films and shows stay in your sources, in Favourites and in any other groups.",
            action: "Delete group"
        )
        guard confirmed else { return }
        withAnimation(Motion.quick) {
            if selectedId == group.id { selectedId = FavouriteGroup.defaultId }
            userData.deleteGroup(group.id)
        }
    }

    private func canShift(_ group: FavouriteGroup, by step: Int) -> Bool {
        guard let position = custom.firstIndex(where: { $0.id == group.id }) else { return false }
        let target = position + step
        return target >= 0 && target < custom.count
    }

    private func shift(_ group: FavouriteGroup, by step: Int) {
        guard canShift(group, by: step),
              let position = custom.firstIndex(where: { $0.id == group.id }) else { return }
        let target = position + step
        withAnimation(Motion.quick) {
            moveCustom(from: IndexSet(integer: position), to: step > 0 ? target + 1 : target)
        }
    }

    /// Moves within the viewer's own groups (offsets as `.onMove` gives them), keeping the
    /// built-in Favourites group where it is.
    private func moveCustom(from source: IndexSet, to destination: Int) {
        let all = userData.groups
        let positions = all.indices.filter { all[$0].id != FavouriteGroup.defaultId }
        guard source.count == 1, let first = source.first, first < positions.count else { return }
        let from = positions[first]
        let to: Int
        if destination < positions.count {
            to = positions[destination]
        } else {
            to = (positions.last ?? (all.count - 1)) + 1
        }
        guard from != to, from + 1 != to else { return }
        userData.moveGroups(from: IndexSet(integer: from), to: to)
    }
}

@MainActor
private struct FavouritesGroupRow: View {
    let group: FavouriteGroup
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: group.symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .frame(width: 20)
                Text(group.name)
                    .font(NeonFont.body(14, bold: true))
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(formatCount(group.items.count))
                    .font(NeonFont.display(11))
                    .foregroundColor(selected ? Neon.onCyan.opacity(0.75) : Neon.textMuted)
            }
            .foregroundColor(foreground)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                HudShape(cut: 8)
                    .fill(fill)
                    .shadow(color: Neon.cyan.opacity(selected ? 0.45 : 0), radius: 10)
            )
            .contentShape(Rectangle())
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
        return hovering ? Neon.surface : Color.clear
    }
}

/// One group's channels (as rows) and films and shows (as posters).
@MainActor
private struct FavouritesGroupDetail: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @EnvironmentObject private var playback: PlaybackCenter
    let group: FavouriteGroup
    @State private var dragging: MediaItem? = nil

    private var channels: [MediaItem] { group.items.filter { $0.kind == .live } }
    private var onDemand: [MediaItem] { group.items.filter { $0.kind != .live } }
    private var isBuiltIn: Bool { group.id == FavouriteGroup.defaultId }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if group.items.isEmpty {
                emptyState
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 30) {
                        if !channels.isEmpty { channelSection }
                        if !onDemand.isEmpty { posterSection }
                        Text(addHint)
                            .font(NeonFont.body(12))
                            .foregroundColor(Neon.textMuted)
                    }
                    .padding(.horizontal, 28)
                    .padding(.top, 6)
                    .padding(.bottom, 28)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(.top, 28)
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: group.symbol)
                .font(.system(size: 26, weight: .semibold))
                .foregroundColor(Neon.magenta)
                .shadow(color: Neon.magenta.opacity(0.6), radius: 10)
            VStack(alignment: .leading, spacing: 4) {
                NeonTitle(text: group.name)
                Text(summary)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
            }
            Spacer()
            if !channels.isEmpty {
                Button { playAllInMultiview() } label: {
                    Label("Play all in multiview", systemImage: "square.grid.2x2.fill")
                }
                .buttonStyle(NeonButtonStyle(prominent: true))
                .help(multiviewHelp)
            }
        }
        .padding(.horizontal, 28)
    }

    private var multiviewHelp: String {
        if channels.count > 4 {
            return "Multiview shows up to four channels, so the first four in this group play."
        }
        return "Watch this group's channels side by side."
    }

    private var summary: String {
        var parts: [String] = []
        let live = channels.count
        let other = onDemand.count
        if live > 0 { parts.append(live == 1 ? "1 channel" : "\(formatCount(live)) channels") }
        if other > 0 { parts.append(other == 1 ? "1 film or show" : "\(formatCount(other)) films and shows") }
        if parts.isEmpty { return "Nothing here yet" }
        return parts.joined(separator: " · ")
    }

    private var addHint: String {
        let how = isBuiltIn ? "Add to Favourites" : "Add to group › \(group.name)"
        return "To add more, right-click any channel, film or show and choose \(how). Drag items, or hover for the arrows, to change the order."
    }

    private var emptyState: some View {
        EmptyState(
            symbol: isBuiltIn ? "star" : group.symbol,
            title: "\(group.name) is empty",
            message: isBuiltIn
                ? "Right-click any channel, film or show and choose Add to Favourites. They appear here, and you can drag them into whatever order suits you."
                : "Right-click any channel, film or show and choose Add to group › \(group.name). Then drag them into whatever order suits you.",
            actionTitle: "Browse Live TV",
            action: { model.go(.live) }
        )
    }

    private var channelSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Channels", subtitle: "Click to watch. In the player, up and down flip through this group.")
            LazyVStack(spacing: 6) {
                ForEach(Array(channels.enumerated()), id: \.element.id) { index, item in
                    FavouritesChannelRow(
                        item: item,
                        number: index + 1,
                        list: channels,
                        group: group,
                        canMoveUp: index > 0,
                        canMoveDown: index < channels.count - 1,
                        onMove: { step in shift(item, by: step, within: channels) }
                    )
                    .onDrag {
                        dragging = item
                        return NSItemProvider(object: item.id as NSString)
                    }
                    .onDrop(of: [UTType.text], isTargeted: nil) { _ in drop(onto: item) }
                }
            }
        }
    }

    private var posterSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Films and series", subtitle: "Click to open the page.")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 18)], spacing: 22) {
                ForEach(Array(onDemand.enumerated()), id: \.element.id) { index, item in
                    LibraryPosterTile(
                        item: item,
                        canMoveBack: index > 0,
                        canMoveForward: index < onDemand.count - 1,
                        removeHelp: "Remove from \(group.name)",
                        onMove: { step in shift(item, by: step, within: onDemand) },
                        onRemove: { remove(item) }
                    )
                    .onDrag {
                        dragging = item
                        return NSItemProvider(object: item.id as NSString)
                    }
                    .onDrop(of: [UTType.text], isTargeted: nil) { _ in drop(onto: item) }
                }
            }
        }
    }

    // MARK: Actions

    /// Multiview holds four channels, so the first four of the group play.
    private func playAllInMultiview() {
        let first = Array(channels.prefix(4))
        guard !first.isEmpty else { return }
        playback.openMultiview()
        for item in first where playback.canAddTile {
            playback.addTile(item)
        }
    }

    private func remove(_ item: MediaItem) {
        withAnimation(Motion.quick) { userData.toggle(item, inGroup: group.id) }
    }

    /// Swaps with the neighbouring item of the same sort (channel, or film and show).
    private func shift(_ item: MediaItem, by step: Int, within list: [MediaItem]) {
        guard let position = list.firstIndex(where: { $0.id == item.id }) else { return }
        let target = position + step
        guard target >= 0, target < list.count else { return }
        move(item, onto: list[target])
    }

    /// Puts `item` where `target` is now.
    private func move(_ item: MediaItem, onto target: MediaItem) {
        let all = group.items
        guard let from = all.firstIndex(where: { $0.id == item.id }),
              let to = all.firstIndex(where: { $0.id == target.id }),
              from != to else { return }
        withAnimation(Motion.quick) {
            userData.moveItems(inGroup: group.id, from: IndexSet(integer: from), to: to > from ? to + 1 : to)
        }
    }

    private func drop(onto target: MediaItem) -> Bool {
        defer { dragging = nil }
        guard let moving = dragging, moving.id != target.id,
              (moving.kind == .live) == (target.kind == .live) else { return false }
        move(moving, onto: target)
        return true
    }
}

@MainActor
private struct FavouritesChannelRow: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @EnvironmentObject private var playback: PlaybackCenter
    let item: MediaItem
    let number: Int
    let list: [MediaItem]
    let group: FavouriteGroup
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onMove: (Int) -> Void
    @State private var hovering = false

    private var isPlaying: Bool { playback.current?.item?.id == item.id }

    var body: some View {
        HStack(spacing: 8) {
            Button { model.playLive(item, in: list) } label: {
                ChannelRow(item: item, number: number, playing: isPlaying)
            }
            .buttonStyle(.plain)
            .contextMenu { menu }
            controls
        }
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }

    private var controls: some View {
        VStack(spacing: 4) {
            HStack(spacing: 4) {
                LibraryMiniButton(symbol: "chevron.up", help: "Move up", enabled: canMoveUp) { onMove(-1) }
                LibraryMiniButton(symbol: "chevron.down", help: "Move down", enabled: canMoveDown) { onMove(1) }
            }
            LibraryMiniButton(symbol: "xmark", help: "Remove from \(group.name)", destructive: true) { remove() }
        }
        .opacity(hovering ? 1 : 0)
        .allowsHitTesting(hovering)
    }

    @ViewBuilder
    private var menu: some View {
        MediaContextMenu(item: item, list: list)
        Divider()
        Button("Move up") { onMove(-1) }
            .disabled(!canMoveUp)
        Button("Move down") { onMove(1) }
            .disabled(!canMoveDown)
        if group.id != FavouriteGroup.defaultId {
            Button("Remove from \(group.name)") { remove() }
        }
    }

    private func remove() {
        withAnimation(Motion.quick) { userData.toggle(item, inGroup: group.id) }
    }
}
