import AppKit
import SwiftUI

// Timeline guide, laid out like TiviMate's: channels down the side, time across the top, each
// programme a block as wide as it is long, and a cyan line at the current time. The whole grid
// shares one time window, so every row and the time bar move together. Schedules come straight
// from the Xtream API as rows scroll into view, so there's no big guide file to download.

private enum GuideLayout {
    static let channelWidth: CGFloat = 230
    static let rowHeight: CGFloat = 54
    /// Horizontal scale: 5 points per minute, so about two hours fit in a typical window.
    static let pointsPerSecond: CGFloat = 5.0 / 60.0
    static let halfHour: TimeInterval = 1800

    /// Rounds down to the half hour in the Mac's own time zone.
    static func floorToHalfHour(_ date: Date) -> Date {
        let offset = TimeInterval(TimeZone.current.secondsFromGMT(for: date))
        let local = date.timeIntervalSince1970 + offset
        return Date(timeIntervalSince1970: local - local.truncatingRemainder(dividingBy: halfHour) - offset)
    }

    static func initialStart() -> Date { floorToHalfHour(Date()).addingTimeInterval(-halfHour) }

    static func x(for date: Date, from start: Date) -> CGFloat {
        CGFloat(date.timeIntervalSince(start)) * pointsPerSecond
    }
}

private struct GuideSelection: Equatable {
    let channel: MediaItem
    let programme: Programme?

    var key: String { "\(channel.streamId)-\(programme?.id ?? 0)" }
}

/// Turns sideways trackpad swipes (and Shift + mouse wheel) into timeline panning while the
/// guide is open; vertical scrolling passes through to the channel list.
@MainActor
final class HorizontalScrollMonitor: ObservableObject {
    private var monitor: Any?

    func start(_ onPan: @escaping @MainActor (CGFloat) -> Void) {
        stop()
        monitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { event in
            let dx = event.scrollingDeltaX
            let dy = event.scrollingDeltaY
            guard dx != 0, abs(dx) > abs(dy) * 1.2 else { return event }
            onPan(event.hasPreciseScrollingDeltas ? dx : dx * 12)
            return nil
        }
    }

    func stop() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }
}

@MainActor
struct GuideView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if let catalog = model.catalog {
            GuideContent(catalog: catalog)
                .id(catalog.source.id)
        } else {
            EmptyState(symbol: "calendar", title: "No source", message: "Add an Xtream login or an M3U playlist in Settings.")
        }
    }
}

@MainActor
private struct GuideContent: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @ObservedObject var catalog: CatalogStore
    @StateObject private var state = BrowseLoader(kind: .live)
    @StateObject private var scroll = HorizontalScrollMonitor()
    @State private var windowStart = GuideLayout.initialStart()
    @State private var selection: GuideSelection? = nil
    @State private var query = ""
    @State private var now = Date()

    private var channels: [MediaItem] {
        query.isEmpty ? state.items : state.items.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            GuideInfoPanel(
                selection: selection,
                number: selection.flatMap { sel in sel.channel.number ?? channels.firstIndex(where: { $0.id == sel.channel.id }).map { $0 + 1 } },
                now: now,
                onWatchLive: { channel in model.playLive(channel, in: channels) },
                onCatchUp: { programme, channel in model.playCatchUp(programme, on: channel) }
            )
            GeometryReader { geometry in
                let timelineWidth = max(240, geometry.size.width - GuideLayout.channelWidth)
                let span = TimeInterval(timelineWidth / GuideLayout.pointsPerSecond)
                VStack(spacing: 6) {
                    TimeBar(windowStart: windowStart, span: span, width: timelineWidth, now: now)
                    ScrollView(.vertical) {
                        LazyVStack(spacing: 4) {
                            ForEach(Array(channels.enumerated()), id: \.element.id) { index, channel in
                                GuideRow(
                                    channel: channel,
                                    number: channel.number ?? (index + 1),
                                    programmes: model.listing(for: channel),
                                    windowStart: windowStart,
                                    span: span,
                                    width: timelineWidth,
                                    now: now,
                                    selectedKey: selection?.key,
                                    onSelect: { programme in selection = GuideSelection(channel: channel, programme: programme) },
                                    onActivate: { programme in activate(programme, on: channel) }
                                )
                                .task(id: channel.id) { await model.loadListing(for: channel) }
                            }
                        }
                        .padding(.bottom, 12)
                    }
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .padding(24)
        .task(id: "\(state.selected ?? "")|\(catalog.version)") {
            await state.load(catalog, userData: userData)
            await catalog.loadGuide()
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                now = Date()
            }
        }
        .onAppear {
            scroll.start { dx in shift(by: TimeInterval(-dx / GuideLayout.pointsPerSecond)) }
        }
        .onDisappear { scroll.stop() }
    }

    private var header: some View {
        HStack(spacing: 14) {
            NeonTitle(text: "Guide")
            Picker("Category", selection: Binding(
                get: { state.selected ?? "" },
                set: { id in state.select(id) }
            )) {
                ForEach(state.categories(catalog, userData: userData)) { category in
                    Text(category.name).tag(category.id)
                }
            }
            .pickerStyle(.menu)
            .frame(maxWidth: 300)
            TextField("Filter channels", text: $query)
                .textFieldStyle(.plain)
                .font(NeonFont.body(13))
                .padding(7)
                .neonPanel()
                .frame(width: 200)
            Spacer()
            Button { shift(by: -3600) } label: { Label("Earlier", systemImage: "chevron.left") }
                .buttonStyle(NeonButtonStyle())
                .help("Go back an hour")
            Button("Now") { windowStart = GuideLayout.initialStart() }
                .buttonStyle(NeonButtonStyle(prominent: true))
                .help("Jump back to the current time")
            Button { shift(by: 3600) } label: { Label("Later", systemImage: "chevron.right") }
                .buttonStyle(NeonButtonStyle())
                .help("Go forward an hour")
        }
    }

    private func shift(by seconds: TimeInterval) {
        let earliest = Date().addingTimeInterval(-24 * 3600)
        let latest = Date().addingTimeInterval(22 * 3600)
        windowStart = min(max(windowStart.addingTimeInterval(seconds), earliest), latest)
    }

    /// Double-click: watch if it's on now, replay if it's archived, otherwise just select it.
    private func activate(_ programme: Programme?, on channel: MediaItem) {
        selection = GuideSelection(channel: channel, programme: programme)
        guard let programme else {
            model.playLive(channel, in: channels)
            return
        }
        if programme.isOn(at: Date()) {
            model.playLive(channel, in: channels)
        } else if programme.canReplay {
            model.playCatchUp(programme, on: channel)
        }
    }
}

@MainActor
private struct GuideInfoPanel: View {
    let selection: GuideSelection?
    let number: Int?
    let now: Date
    let onWatchLive: (MediaItem) -> Void
    let onCatchUp: (Programme, MediaItem) -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 18) {
            if let selection {
                VStack(alignment: .leading, spacing: 5) {
                    Text([number.map { String($0) }, selection.channel.name].compactMap { $0 }.joined(separator: "  "))
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textMuted)
                    Text(selection.programme?.title ?? "No programme information")
                        .font(NeonFont.body(20, bold: true))
                        .foregroundColor(Neon.text)
                        .lineLimit(1)
                    if let programme = selection.programme {
                        Text(timing(programme))
                            .font(NeonFont.body(13, bold: true))
                            .foregroundColor(Neon.cyan)
                        if !programme.description.isEmpty {
                            Text(programme.description)
                                .font(NeonFont.body(13))
                                .foregroundColor(Neon.textSecondary)
                                .lineLimit(2)
                        }
                    }
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 8) {
                    Button("Watch \(selection.channel.name)") { onWatchLive(selection.channel) }
                        .buttonStyle(NeonButtonStyle(prominent: true))
                    if let programme = selection.programme, programme.canReplay {
                        Button("Watch from the start") { onCatchUp(programme, selection.channel) }
                            .buttonStyle(NeonButtonStyle())
                            .help("Plays the provider's catch-up recording of this programme")
                    }
                }
            } else {
                Text("Click a programme to see what it is. Double-click to watch it, or to replay it if it's marked with the catch-up icon. Swipe sideways on the trackpad, or hold Shift and scroll, to move through time.")
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.textSecondary)
                    .frame(maxWidth: 720, alignment: .leading)
                Spacer()
            }
        }
        .padding(14)
        .frame(height: 104, alignment: .top)
        .neonPanel()
    }

    private func timing(_ programme: Programme) -> String {
        let start = programme.start.formatted(date: .omitted, time: .shortened)
        let end = programme.end.formatted(date: .omitted, time: .shortened)
        let minutes = Int(programme.end.timeIntervalSince(programme.start) / 60)
        let status: String
        if programme.isOn(at: now) {
            status = "On now"
        } else if programme.canReplay {
            status = "Available to replay"
        } else if programme.hasEnded(by: now) {
            status = "Ended"
        } else {
            status = "Coming up"
        }
        let day = Calendar.current.isDateInToday(programme.start) ? "" : programme.start.formatted(.dateTime.weekday(.abbreviated)) + " "
        return "\(day)\(start) to \(end)   \(minutes) min   \(status)"
    }
}

@MainActor
private struct TimeBar: View {
    let windowStart: Date
    let span: TimeInterval
    let width: CGFloat
    let now: Date

    private var ticks: [Date] {
        var result: [Date] = []
        var tick = GuideLayout.floorToHalfHour(windowStart)
        let end = windowStart.addingTimeInterval(span)
        while tick < end {
            if tick >= windowStart { result.append(tick) }
            tick = tick.addingTimeInterval(GuideLayout.halfHour)
        }
        return result
    }

    var body: some View {
        HStack(spacing: 6) {
            Text(windowStart.formatted(.dateTime.weekday(.wide)))
                .font(NeonFont.display(12))
                .foregroundColor(Neon.magenta)
                .frame(width: GuideLayout.channelWidth - 6, alignment: .leading)
            ZStack(alignment: .topLeading) {
                Color.clear
                ForEach(ticks, id: \.self) { tick in
                    Text(tick.formatted(date: .omitted, time: .shortened))
                        .font(NeonFont.display(12))
                        .foregroundColor(Neon.textSecondary)
                        .offset(x: GuideLayout.x(for: tick, from: windowStart) + 4, y: 3)
                }
                if now >= windowStart && now < windowStart.addingTimeInterval(span) {
                    Rectangle()
                        .fill(Neon.cyan)
                        .frame(width: 2, height: 24)
                        .offset(x: GuideLayout.x(for: now, from: windowStart))
                }
            }
            .frame(width: width, height: 24)
            .clipped()
        }
    }
}

@MainActor
private struct GuideRow: View {
    let channel: MediaItem
    let number: Int
    let programmes: [Programme]?
    let windowStart: Date
    let span: TimeInterval
    let width: CGFloat
    let now: Date
    let selectedKey: String?
    let onSelect: (Programme?) -> Void
    let onActivate: (Programme?) -> Void

    var body: some View {
        let windowEnd = windowStart.addingTimeInterval(span)
        let visible = (programmes ?? []).filter { $0.end > windowStart && $0.start < windowEnd }
        HStack(spacing: 6) {
            HStack(spacing: 10) {
                Text("\(number)")
                    .font(NeonFont.display(13))
                    .foregroundColor(Neon.cyan)
                    .frame(width: 36, alignment: .trailing)
                ChannelLogo(url: channel.icon, size: CGSize(width: 40, height: 30))
                Text(channel.name)
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(Neon.text)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .frame(width: GuideLayout.channelWidth - 6, height: GuideLayout.rowHeight)
            .background(HudShape().fill(Neon.surface.opacity(0.7)))
            .contentShape(Rectangle())
            .onTapGesture(count: 2) { onActivate(nil) }
            .contextMenu { MediaContextMenu(item: channel) }
            .help("Double-click to watch \(channel.name)")

            ZStack(alignment: .topLeading) {
                Color.clear
                if visible.isEmpty {
                    PlaceholderBlock(text: programmes == nil ? "Loading…" : "No programme information")
                        .frame(width: width - 3, height: GuideLayout.rowHeight - 4)
                        .offset(y: 2)
                        .onTapGesture(count: 2) { onActivate(nil) }
                        .onTapGesture { onSelect(nil) }
                } else {
                    ForEach(visible) { programme in
                        let x = GuideLayout.x(for: programme.start, from: windowStart)
                        let blockWidth = max(4, CGFloat(programme.end.timeIntervalSince(programme.start)) * GuideLayout.pointsPerSecond - 3)
                        ProgrammeBlock(
                            programme: programme,
                            now: now,
                            selected: selectedKey == "\(channel.streamId)-\(programme.id)",
                            textInset: max(0, -x)
                        )
                        .frame(width: blockWidth, height: GuideLayout.rowHeight - 4)
                        .offset(x: x, y: 2)
                        .onTapGesture(count: 2) { onActivate(programme) }
                        .onTapGesture { onSelect(programme) }
                    }
                }
                if now >= windowStart && now < windowEnd {
                    Rectangle()
                        .fill(Neon.cyan)
                        .frame(width: 2, height: GuideLayout.rowHeight)
                        .offset(x: GuideLayout.x(for: now, from: windowStart))
                        .allowsHitTesting(false)
                }
            }
            .frame(width: width, height: GuideLayout.rowHeight)
            .clipped()
        }
    }
}

@MainActor
private struct ProgrammeBlock: View {
    let programme: Programme
    let now: Date
    let selected: Bool
    /// Keeps the title readable when the block starts before the visible window.
    let textInset: CGFloat
    @State private var hovering = false

    var body: some View {
        let onNow = programme.isOn(at: now)
        let ended = programme.hasEnded(by: now)
        let replayable = ended && programme.hasArchive
        let lit = selected || hovering
        ZStack(alignment: .bottomLeading) {
            HudShape(cut: 8)
                .fill(lit ? Neon.cyan : (onNow ? Neon.surfaceRaised : Neon.surface.opacity(ended && !replayable ? 0.45 : 0.8)))
            HudShape(cut: 8)
                .stroke(Neon.cyan.opacity(lit ? 1 : (onNow ? 0.6 : 0.15)), lineWidth: lit ? 2 : 1)
            HStack(spacing: 6) {
                if replayable {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(lit ? Neon.onCyan : Neon.magenta)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(programme.title)
                        .font(NeonFont.body(13, bold: onNow))
                        .foregroundColor(lit ? Neon.onCyan : (ended && !replayable ? Neon.textMuted : Neon.text))
                        .lineLimit(1)
                    Text(programme.start.formatted(date: .omitted, time: .shortened))
                        .font(NeonFont.body(11))
                        .foregroundColor(lit ? Neon.onCyan.opacity(0.75) : Neon.textMuted)
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 10 + textInset)
            .padding(.trailing, 6)
            .frame(maxHeight: .infinity)
            if onNow {
                GeometryReader { geometry in
                    let fraction = min(1, max(0, now.timeIntervalSince(programme.start) / programme.end.timeIntervalSince(programme.start)))
                    Rectangle()
                        .fill(lit ? Neon.onCyan : Neon.cyan)
                        .frame(width: geometry.size.width * CGFloat(fraction), height: 3)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
            }
        }
        .shadow(color: Neon.cyan.opacity(lit ? 0.45 : 0), radius: 8)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .help(programme.title)
    }
}

@MainActor
private struct PlaceholderBlock: View {
    let text: String

    var body: some View {
        ZStack(alignment: .leading) {
            HudShape(cut: 8).fill(Neon.surface.opacity(0.5))
            HudShape(cut: 8).stroke(Neon.cyan.opacity(0.12), lineWidth: 1)
            Text(text)
                .font(NeonFont.body(12))
                .foregroundColor(Neon.textMuted)
                .padding(.leading, 12)
        }
        .contentShape(Rectangle())
    }
}
