import AVKit
import AppKit
import SwiftUI

// The player's screens. One video surface sits at the top of the window for the whole session and
// only changes size: full screen, or the mini player in a corner while you browse. Multiview is a
// separate 2×2 grid of up to four live channels.

/// Watches the keyboard while a screen is open. Returning true swallows the key.
@MainActor
final class KeyMonitor: ObservableObject {
    private var monitor: Any?

    func start(_ handler: @escaping @MainActor (NSEvent) -> Bool) {
        stop()
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            // Leave typing in text fields alone.
            if let responder = NSApp.keyWindow?.firstResponder, responder is NSTextView { return event }
            if event.modifierFlags.contains(.command) { return event }
            return handler(event) ? nil : event
        }
    }

    func stop() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
    }
}

/// mpv's picture in SwiftUI.
struct VideoSurface: NSViewRepresentable {
    let player: MPVPlayer?

    func makeNSView(context: Context) -> MPVVideoView {
        let view = MPVVideoView(frame: .zero)
        view.show(player)
        return view
    }

    func updateNSView(_ view: MPVVideoView, context: Context) {
        view.show(player)
    }

    static func dismantleNSView(_ view: MPVVideoView, coordinator: ()) {
        view.clear()
    }
}

/// Apple's player, used only as a fallback.
private struct AppleSurface: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = .floating
        view.showsFullScreenToggleButton = true
        view.allowsPictureInPicturePlayback = true
        view.videoGravity = .resizeAspect
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}

// MARK: - Stage (full screen and mini player)

@MainActor
struct PlayerStage: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    @State private var dragOffset: CGSize = .zero

    private static let miniSize = CGSize(width: 400, height: 225)

    var body: some View {
        GeometryReader { geometry in
            if let request = playback.current, !playback.multiviewVisible {
                let full = playback.presentation == .full
                let size = full ? geometry.size : PlayerStage.miniSize
                ZStack {
                    Color.black
                    if let apple = playback.applePlayer {
                        AppleSurface(player: apple)
                    } else {
                        VideoSurface(player: playback.player)
                    }
                    if full {
                        PlayerControls(request: request)
                    } else {
                        MiniControls(request: request)
                    }
                }
                .frame(width: size.width, height: size.height)
                .clipShape(RoundedRectangle(cornerRadius: full ? 0 : 12, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: full ? 0 : 12, style: .continuous)
                        .stroke(Neon.cyan.opacity(full ? 0 : 0.7), lineWidth: 1.5)
                )
                .shadow(color: Neon.cyan.opacity(full ? 0 : 0.35), radius: 18)
                .offset(full ? .zero : dragOffset)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: full ? .center : playback.miniCorner)
                .padding(full ? 0 : 22)
                .gesture(miniDrag(in: geometry.size), including: full ? .none : .all)
                .transition(.opacity)
            }
        }
        .allowsHitTesting(playback.current != nil && !playback.multiviewVisible)
    }

    /// Drag the mini player; it snaps to the nearest corner.
    private func miniDrag(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in dragOffset = value.translation }
            .onEnded { value in
                let corner = playback.miniCorner
                let startX: CGFloat = corner == .bottomLeading || corner == .topLeading ? 0 : size.width
                let startY: CGFloat = corner == .topLeading || corner == .topTrailing ? 0 : size.height
                let x = startX + value.translation.width
                let y = startY + value.translation.height
                let left = x < size.width / 2
                let top = y < size.height / 2
                withAnimation(Motion.page) {
                    dragOffset = .zero
                    playback.miniCorner = top ? (left ? .topLeading : .topTrailing) : (left ? .bottomLeading : .bottomTrailing)
                }
            }
    }
}

@MainActor
private struct MiniControls: View {
    @EnvironmentObject private var playback: PlaybackCenter
    let request: PlaybackRequest
    @State private var hovering = false

    var body: some View {
        ZStack {
            if hovering {
                LinearGradient(colors: [.black.opacity(0.75), .clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                VStack {
                    HStack {
                        Text(request.title)
                            .font(NeonFont.body(13, bold: true))
                            .foregroundColor(Neon.text)
                            .lineLimit(1)
                        Spacer()
                        IconButton(symbol: "xmark", help: "Stop") { playback.stop() }
                    }
                    Spacer()
                    HStack(spacing: 14) {
                        IconButton(symbol: "arrow.up.left.and.arrow.down.right", help: "Back to full screen") { playback.expand() }
                        MiniPlayPause()
                        if request.live {
                            IconButton(symbol: "rectangle.split.2x2", help: "Multiview") { playback.openMultiview() }
                        }
                    }
                }
                .padding(10)
                .transition(.opacity)
            }
        }
        .contentShape(Rectangle())
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .onTapGesture(count: 2) { playback.expand() }
    }
}

@MainActor
private struct MiniPlayPause: View {
    @EnvironmentObject private var playback: PlaybackCenter

    var body: some View {
        if let player = playback.player {
            PlayPauseButton(player: player, size: 16)
        } else {
            IconButton(symbol: "playpause.fill", help: "Play or pause") { playback.togglePause() }
        }
    }
}

// MARK: - Full-screen controls

enum PlayerPanel: Equatable {
    case none, channels, audio, subtitles, sync, video, info, subtitleSearch
}

@MainActor
struct PlayerControls: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    let request: PlaybackRequest
    @StateObject private var keys = KeyMonitor()
    @State private var lastActivity = Date()
    @State private var visible = true
    @State private var panel: PlayerPanel = .none
    @State private var toast: String? = nil
    @State private var toastId = UUID()

    var body: some View {
        ZStack {
            if let player = playback.player, playback.applePlayer == nil {
                PlayerChrome(player: player, request: request, visible: visible || panel != .none, panel: $panel)
            } else {
                AppleChrome(request: request)
            }
            if let toast {
                Text(toast)
                    .font(NeonFont.display(16))
                    .foregroundColor(Neon.text)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(HudShape().fill(Neon.background.opacity(0.85)))
                    .overlay(HudShape().stroke(Neon.cyan.opacity(0.6), lineWidth: 1))
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 90)
                    .transition(.opacity)
                    .id(toastId)
            }
        }
        .contentShape(Rectangle())
        .onContinuousHover { phase in
            if case .active = phase { poke() }
        }
        .onTapGesture(count: 2) { toggleWindowFullScreen() }
        .onTapGesture { poke() }
        .onAppear {
            poke()
            keys.start { event in handleKey(event) }
        }
        .onDisappear {
            keys.stop()
            NSCursor.setHiddenUntilMouseMoves(false)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 400_000_000)
                let idle = Date().timeIntervalSince(lastActivity) > 3.2
                let paused = playback.player?.paused ?? false
                let shouldShow = !idle || paused || panel != .none
                if shouldShow != visible {
                    withAnimation(Motion.fade) { visible = shouldShow }
                    if !shouldShow { NSCursor.setHiddenUntilMouseMoves(true) }
                }
            }
        }
    }

    private func poke() {
        lastActivity = Date()
        if !visible { withAnimation(Motion.fade) { visible = true } }
    }

    private func flash(_ text: String) {
        toastId = UUID()
        withAnimation(Motion.fade) { toast = text }
        let id = toastId
        Task {
            try? await Task.sleep(nanoseconds: 1_300_000_000)
            if toastId == id { withAnimation(Motion.fade) { toast = nil } }
        }
    }

    private func toggleWindowFullScreen() {
        NSApp.keyWindow?.toggleFullScreen(nil)
    }

    private func handleKey(_ event: NSEvent) -> Bool {
        guard playback.isFullScreen else { return false }
        poke()
        let settings = PlaybackSettings.current
        let player = playback.player
        let characters = event.charactersIgnoringModifiers?.lowercased() ?? ""
        switch event.keyCode {
        case 53: // Esc
            if panel != .none {
                withAnimation(Motion.quick) { panel = .none }
            } else {
                playback.close()
            }
            return true
        case 49: // Space
            playback.togglePause()
            return true
        case 123: // Left
            if request.live { return false }
            playback.seek(by: -Double(settings.skipBack))
            flash("−\(settings.skipBack)s")
            return true
        case 124: // Right
            if request.live { return false }
            playback.seek(by: Double(settings.skipForward))
            flash("+\(settings.skipForward)s")
            return true
        case 126, 116: // Up, Page Up
            if request.live {
                model.zap(1)
            } else if let player {
                player.setVolume(player.volume + 5)
                flash("Volume \(Int(player.volume + 5))")
            }
            return true
        case 125, 121: // Down, Page Down
            if request.live {
                model.zap(-1)
            } else if let player {
                player.setVolume(player.volume - 5)
                flash("Volume \(Int(max(0, player.volume - 5)))")
            }
            return true
        default:
            break
        }
        guard let player else { return false }
        switch characters {
        case "f":
            toggleWindowFullScreen()
        case "m":
            player.setMuted(!player.muted)
            flash(player.muted ? "Sound on" : "Muted")
        case "s":
            cycleSubtitles(player)
        case "a":
            cycleAudio(player)
        case "i":
            togglePanel(.info)
        case "c":
            if request.live { togglePanel(.channels) }
        case "z":
            player.setSubtitleDelay(player.subtitleDelay - 0.1)
            flash(String(format: "Subtitles %+.1fs", player.subtitleDelay - 0.1))
        case "x":
            player.setSubtitleDelay(player.subtitleDelay + 0.1)
            flash(String(format: "Subtitles %+.1fs", player.subtitleDelay + 0.1))
        case ",":
            player.setAudioDelay(player.audioDelay - 0.05)
            flash(String(format: "Audio %+.2fs", player.audioDelay - 0.05))
        case ".":
            player.setAudioDelay(player.audioDelay + 0.05)
            flash(String(format: "Audio %+.2fs", player.audioDelay + 0.05))
        case "[":
            player.setSpeed(player.speed - 0.25)
            flash(String(format: "Speed %.2g×", max(0.25, player.speed - 0.25)))
        case "]":
            player.setSpeed(player.speed + 0.25)
            flash(String(format: "Speed %.2g×", min(4, player.speed + 0.25)))
        case "0":
            player.setSpeed(1)
            flash("Speed 1×")
        case "p":
            playback.minimise()
        case "n":
            playback.playNext()
        case "v":
            if request.live { playback.openMultiview() }
        default:
            return false
        }
        return true
    }

    private func togglePanel(_ target: PlayerPanel) {
        withAnimation(Motion.quick) { panel = panel == target ? .none : target }
    }

    private func cycleSubtitles(_ player: MPVPlayer) {
        let tracks = player.subtitleTracks
        guard !tracks.isEmpty else {
            flash("No subtitles in this stream")
            return
        }
        if let selected = player.selectedSubtitle, let index = tracks.firstIndex(of: selected) {
            if index + 1 < tracks.count {
                player.selectSubtitle(tracks[index + 1].id)
                flash("Subtitles: \(tracks[index + 1].label)")
            } else {
                player.selectSubtitle(nil)
                flash("Subtitles off")
            }
        } else {
            player.selectSubtitle(tracks[0].id)
            flash("Subtitles: \(tracks[0].label)")
        }
    }

    private func cycleAudio(_ player: MPVPlayer) {
        let tracks = player.audioTracks
        guard tracks.count > 1 else {
            flash("Only one audio track")
            return
        }
        let index = player.selectedAudio.flatMap { tracks.firstIndex(of: $0) } ?? -1
        let next = tracks[(index + 1) % tracks.count]
        player.selectAudio(next.id)
        flash("Audio: \(next.label)")
    }
}

/// Minimal controls over Apple's player (it draws its own transport controls).
@MainActor
private struct AppleChrome: View {
    @EnvironmentObject private var playback: PlaybackCenter
    let request: PlaybackRequest

    var body: some View {
        VStack {
            HStack {
                PlayerTitle(request: request)
                Spacer()
                IconButton(symbol: "pip.enter", help: "Mini player (P)") { playback.minimise() }
                IconButton(symbol: "xmark", help: "Close (Esc)") { playback.stop() }
            }
            .padding(20)
            Spacer()
        }
    }
}

@MainActor
private struct PlayerTitle: View {
    @EnvironmentObject private var model: AppModel
    let request: PlaybackRequest

    var body: some View {
        HStack(spacing: 14) {
            if let index = request.channelIndex, request.live {
                let number = request.item?.number ?? (index + 1)
                ZStack {
                    Text("\(number)").font(NeonFont.display(36)).foregroundColor(Neon.magenta).offset(x: 2, y: 1.5)
                    Text("\(number)").font(NeonFont.display(36)).foregroundColor(Neon.cyan)
                }
            }
            if request.live, let logo = request.item?.icon {
                RemoteImage(url: logo, contentMode: .fit)
                    .frame(width: 54, height: 40)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(request.title)
                    .font(NeonFont.body(20, bold: true))
                    .foregroundColor(Neon.text)
                    .lineLimit(1)
                if let subtitle = request.subtitle {
                    Text(subtitle).font(NeonFont.body(14)).foregroundColor(Neon.textSecondary).lineLimit(1)
                }
            }
        }
    }
}

@MainActor
private struct PlayerChrome: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    @ObservedObject var player: MPVPlayer
    let request: PlaybackRequest
    let visible: Bool
    @Binding var panel: PlayerPanel

    var body: some View {
        ZStack {
            if player.buffering || (!player.loaded && playback.errorMessage == nil) || player.seeking {
                BufferingIndicator(percent: player.bufferPercent, retrying: playback.retrying)
            }
            if let message = playback.errorMessage {
                PlaybackErrorCard(message: message)
            }
            if let countdown = playback.upNextCountdown, let next = request.upNext.first {
                UpNextCard(episode: next, countdown: countdown)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(.trailing, 40)
                    .padding(.bottom, 140)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
            if visible {
                VStack(spacing: 0) {
                    topBar
                        .padding(.horizontal, 24)
                        .padding(.top, 22)
                        .padding(.bottom, 40)
                        .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
                    Spacer()
                    bottomBar
                        .padding(.horizontal, 24)
                        .padding(.bottom, 22)
                        .padding(.top, 50)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
                }
                .transition(.opacity)
            }
            if panel != .none {
                HStack {
                    Spacer()
                    panelView
                        .frame(width: panel == .channels ? 400 : 360)
                        .frame(maxHeight: .infinity)
                        .background(Neon.background.opacity(0.94))
                        .overlay(Rectangle().fill(Neon.cyan.opacity(0.4)).frame(width: 1), alignment: .leading)
                }
                .transition(.move(edge: .trailing))
            }
        }
    }

    private var topBar: some View {
        HStack(alignment: .center, spacing: 12) {
            PlayerTitle(request: request)
            Spacer()
            if request.live {
                IconButton(symbol: "rectangle.split.2x2", help: "Multiview (V)") { playback.openMultiview() }
            }
            IconButton(symbol: "info.circle", help: "Stream info (I)") { toggle(.info) }
            IconButton(symbol: "pip.enter", help: "Mini player: keep watching while you browse (P)") { playback.minimise() }
            IconButton(symbol: "arrow.up.left.and.arrow.down.right", help: "Full screen (F)") { NSApp.keyWindow?.toggleFullScreen(nil) }
            IconButton(symbol: "xmark", help: "Close (Esc)") { playback.stop() }
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 12) {
            if request.live {
                LiveInfoBar(request: request)
            } else {
                SeekBar(player: player)
            }
            HStack(spacing: 14) {
                if !request.live {
                    IconButton(symbol: "gobackward.\(PlaybackSettings.current.skipBack)", fallback: "gobackward", help: "Back (←)") {
                        playback.seek(by: -Double(PlaybackSettings.current.skipBack))
                    }
                }
                PlayPauseButton(player: player, size: 22)
                if !request.live {
                    IconButton(symbol: "goforward.\(PlaybackSettings.current.skipForward)", fallback: "goforward", help: "Forward (→)") {
                        playback.seek(by: Double(PlaybackSettings.current.skipForward))
                    }
                }
                if request.live {
                    IconButton(symbol: "chevron.up", help: "Next channel (↑)") { model.zap(1) }
                    IconButton(symbol: "chevron.down", help: "Previous channel (↓)") { model.zap(-1) }
                }
                if request.kind == .episode && !request.upNext.isEmpty {
                    IconButton(symbol: "forward.end.fill", help: "Next episode (N)") { playback.playNext() }
                }
                VolumeControl(player: player)
                Spacer()
                if request.live {
                    PanelButton(title: "Channels", symbol: "list.bullet", active: panel == .channels) { toggle(.channels) }
                }
                PanelButton(title: "Audio", symbol: "speaker.wave.2", active: panel == .audio) { toggle(.audio) }
                PanelButton(title: "Subtitles", symbol: "captions.bubble", active: panel == .subtitles || panel == .subtitleSearch) { toggle(.subtitles) }
                PanelButton(title: "Sync", symbol: "slider.horizontal.3", active: panel == .sync) { toggle(.sync) }
                PanelButton(title: "Picture", symbol: "aspectratio", active: panel == .video) { toggle(.video) }
            }
        }
    }

    private func toggle(_ target: PlayerPanel) {
        withAnimation(Motion.quick) { panel = panel == target ? .none : target }
    }

    @ViewBuilder
    private var panelView: some View {
        switch panel {
        case .channels: ChannelPanel(request: request)
        case .audio: AudioPanel(player: player)
        case .subtitles: SubtitlePanel(player: player, onSearch: { withAnimation(Motion.quick) { panel = .subtitleSearch } })
        case .subtitleSearch: SubtitleSearchPanel(player: player, request: request)
        case .sync: SyncPanel(player: player)
        case .video: PicturePanel(player: player)
        case .info: InfoPanel(player: player, request: request)
        case .none: EmptyView()
        }
    }
}

// MARK: - Pieces

@MainActor
struct IconButton: View {
    let symbol: String
    var fallback: String? = nil
    let help: String
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Image(systemName: resolvedSymbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                .frame(width: 38, height: 38)
                .background(Circle().fill(hovering ? Neon.cyan : Color.black.opacity(0.45)))
                .overlay(Circle().stroke(Neon.cyan.opacity(hovering ? 1 : 0.35), lineWidth: 1))
                .scaleEffect(hovering ? 1.08 : 1)
        }
        .buttonStyle(.plain)
        .help(help)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }

    private var resolvedSymbol: String {
        if NSImage(systemSymbolName: symbol, accessibilityDescription: nil) != nil { return symbol }
        return fallback ?? "circle"
    }
}

@MainActor
private struct PanelButton: View {
    let title: String
    let symbol: String
    let active: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(active || hovering ? Neon.onCyan : Neon.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(HudShape(cut: 7).fill(active || hovering ? Neon.cyan : Color.black.opacity(0.45)))
                .overlay(HudShape(cut: 7).stroke(Neon.cyan.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

@MainActor
struct PlayPauseButton: View {
    @ObservedObject var player: MPVPlayer
    let size: CGFloat
    @State private var hovering = false

    var body: some View {
        Button { player.togglePause() } label: {
            Image(systemName: player.paused ? "play.fill" : "pause.fill")
                .font(.system(size: size, weight: .bold))
                .foregroundColor(Neon.onCyan)
                .frame(width: size * 2.4, height: size * 2.4)
                .background(Circle().fill(Neon.cyan))
                .shadow(color: Neon.cyan.opacity(hovering ? 0.8 : 0.4), radius: hovering ? 14 : 8)
                .scaleEffect(hovering ? 1.06 : 1)
        }
        .buttonStyle(.plain)
        .help(player.paused ? "Play (Space)" : "Pause (Space)")
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

@MainActor
private struct VolumeControl: View {
    @ObservedObject var player: MPVPlayer

    var body: some View {
        HStack(spacing: 6) {
            IconButton(symbol: player.muted || player.volume == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill", help: "Mute (M)") {
                player.setMuted(!player.muted)
            }
            Slider(value: Binding(get: { player.volume }, set: { player.setVolume($0) }), in: 0...130)
                .frame(width: 110)
                .tint(Neon.cyan)
        }
    }
}

@MainActor
private struct SeekBar: View {
    @ObservedObject var player: MPVPlayer
    @State private var dragging: Double? = nil
    @State private var hoverX: CGFloat? = nil

    var body: some View {
        let duration = max(player.duration, 1)
        let shown = dragging ?? player.position
        VStack(spacing: 6) {
            GeometryReader { geometry in
                let width = geometry.size.width
                let played = CGFloat(min(1, shown / duration)) * width
                let cached = CGFloat(min(1, (player.position + player.cachedSeconds) / duration)) * width
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.18)).frame(height: 5)
                    Capsule().fill(Color.white.opacity(0.32)).frame(width: max(0, cached), height: 5)
                    Capsule().fill(Neon.cyan).frame(width: max(0, played), height: 5)
                        .shadow(color: Neon.cyan.opacity(0.6), radius: 6)
                    Circle().fill(Neon.cyan).frame(width: 14, height: 14)
                        .offset(x: max(0, played - 7))
                    if let hoverX {
                        Text(clock(Double(hoverX / width) * duration))
                            .font(NeonFont.body(12, bold: true))
                            .foregroundColor(Neon.onCyan)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Neon.cyan))
                            .offset(x: min(max(0, hoverX - 24), width - 52), y: -22)
                    }
                }
                .frame(height: 20)
                .contentShape(Rectangle())
                .onContinuousHover { phase in
                    switch phase {
                    case .active(let location): hoverX = location.x
                    case .ended: hoverX = nil
                    }
                }
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            dragging = Double(min(max(0, value.location.x / width), 1)) * duration
                        }
                        .onEnded { value in
                            let target = Double(min(max(0, value.location.x / width), 1)) * duration
                            player.seek(to: target)
                            dragging = nil
                        }
                )
            }
            .frame(height: 20)
            HStack {
                Text(clock(shown)).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.text)
                Spacer()
                if player.speed != 1 {
                    Text(String(format: "%.2g×", player.speed)).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.magenta)
                }
                Text("−" + clock(max(0, player.duration - shown))).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
        }
    }
}

@MainActor
private struct LiveInfoBar: View {
    @EnvironmentObject private var model: AppModel
    let request: PlaybackRequest

    var body: some View {
        let programmes = request.item.map { model.nowNext(for: $0) } ?? []
        let now = Date()
        let current = programmes.first { $0.isOn(at: now) }
        let next = programmes.first { $0.start >= (current?.end ?? now) }
        HStack(alignment: .center, spacing: 16) {
            HStack(spacing: 6) {
                Circle().fill(Neon.danger).frame(width: 9, height: 9)
                Text("LIVE").font(NeonFont.display(13)).foregroundColor(Neon.danger)
            }
            VStack(alignment: .leading, spacing: 4) {
                if let current {
                    HStack {
                        Text(current.title).font(NeonFont.body(15, bold: true)).foregroundColor(Neon.text).lineLimit(1)
                        Spacer()
                        Text("\(current.start.formatted(date: .omitted, time: .shortened)) – \(current.end.formatted(date: .omitted, time: .shortened))")
                            .font(NeonFont.body(12)).foregroundColor(Neon.textSecondary)
                    }
                    ProgressView(value: now.timeIntervalSince(current.start), total: max(1, current.end.timeIntervalSince(current.start)))
                        .tint(Neon.cyan)
                } else {
                    Text("No programme information").font(NeonFont.body(14)).foregroundColor(Neon.textMuted)
                }
                if let next {
                    Text("Next \(next.start.formatted(date: .omitted, time: .shortened)): \(next.title)")
                        .font(NeonFont.body(12)).foregroundColor(Neon.textMuted).lineLimit(1)
                }
            }
        }
    }
}

@MainActor
private struct BufferingIndicator: View {
    let percent: Int
    let retrying: Int?
    @State private var spin = false

    var body: some View {
        VStack(spacing: 12) {
            Circle()
                .trim(from: 0, to: 0.72)
                .stroke(AngularGradient(colors: [Neon.cyan.opacity(0), Neon.cyan, Neon.magenta], center: .center),
                        style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .frame(width: 58, height: 58)
                .rotationEffect(.degrees(spin ? 360 : 0))
                .animation(.linear(duration: 0.9).repeatForever(autoreverses: false), value: spin)
            if let retrying {
                Text("Reconnecting (attempt \(retrying))…").font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text)
            } else if percent > 0 && percent < 100 {
                Text("Buffering \(percent)%").font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
        }
        .onAppear { spin = true }
        .allowsHitTesting(false)
    }
}

@MainActor
private struct PlaybackErrorCard: View {
    @EnvironmentObject private var playback: PlaybackCenter
    let message: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 30)).foregroundColor(Neon.danger)
            Text(message)
                .font(NeonFont.body(15))
                .foregroundColor(Neon.text)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 440)
            HStack(spacing: 12) {
                Button("Try again") { playback.retryNow() }.buttonStyle(NeonButtonStyle(prominent: true))
                Button("Close") { playback.stop() }.buttonStyle(NeonButtonStyle())
            }
        }
        .padding(26)
        .neonPanel(highlighted: true)
    }
}

@MainActor
private struct UpNextCard: View {
    @EnvironmentObject private var playback: PlaybackCenter
    let episode: Episode
    let countdown: Int

    var body: some View {
        HStack(spacing: 14) {
            RemoteImage(url: episode.image, contentMode: .fill)
                .frame(width: 160, height: 90)
                .clipShape(HudShape(cut: 10))
            VStack(alignment: .leading, spacing: 6) {
                Text("Up next in \(countdown)").font(NeonFont.display(13)).foregroundColor(Neon.magenta)
                Text("S\(episode.season) E\(episode.number ?? "?")  \(episode.title)")
                    .font(NeonFont.body(15, bold: true)).foregroundColor(Neon.text).lineLimit(2)
                HStack(spacing: 10) {
                    Button("Play now") { playback.playNext() }.buttonStyle(NeonButtonStyle(prominent: true))
                    Button("Cancel") { playback.cancelUpNext() }.buttonStyle(NeonButtonStyle())
                }
            }
        }
        .padding(14)
        .frame(width: 460)
        .neonPanel(highlighted: true)
    }
}

// MARK: - Side panels

@MainActor
private struct PanelScaffold<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(NeonFont.display(18)).foregroundColor(Neon.cyan)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) { content() }
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(20)
    }
}

@MainActor
private struct TrackRow: View {
    let title: String
    let detail: String?
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(selected ? (hovering ? Neon.onCyan : Neon.cyan) : (hovering ? Neon.onCyan : Neon.textMuted))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(NeonFont.body(14, bold: selected)).foregroundColor(hovering ? Neon.onCyan : Neon.text)
                    if let detail {
                        Text(detail).font(NeonFont.body(12)).foregroundColor(hovering ? Neon.onCyan.opacity(0.8) : Neon.textMuted)
                    }
                }
                Spacer()
            }
            .padding(10)
            .background(HudShape(cut: 7).fill(hovering ? Neon.cyan : Neon.surface.opacity(0.8)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

@MainActor
private struct AudioPanel: View {
    @ObservedObject var player: MPVPlayer
    @State private var normalise = PlaybackSettings.current.normaliseVolume

    var body: some View {
        PanelScaffold(title: "Audio") {
            if player.audioTracks.isEmpty {
                Text("This stream has no audio tracks to choose from.").font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            }
            ForEach(player.audioTracks) { track in
                TrackRow(title: track.label, detail: track.isDefault ? "Default" : nil, selected: track.selected) {
                    player.selectAudio(track.id)
                }
            }
            Divider().overlay(Neon.cyan.opacity(0.2))
            Toggle("Even out loud and quiet parts", isOn: $normalise)
                .toggleStyle(.switch)
                .font(NeonFont.body(13))
                .onChange(of: normalise) { value in
                    player.setOption("af", value ? "dynaudnorm=f=250:g=15" : "")
                    var settings = PlaybackSettings.current
                    settings.normaliseVolume = value
                    settings.save()
                }
            Text("Dolby Digital, DTS and TrueHD play in the app. To send them untouched to a receiver or TV, turn on passthrough in Settings > Playback.")
                .font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
        }
    }
}

@MainActor
private struct SubtitlePanel: View {
    @ObservedObject var player: MPVPlayer
    let onSearch: () -> Void
    @State private var styled = PlaybackSettings.current.styledSubtitles
    @State private var scale = PlaybackSettings.current.subtitleScale
    @State private var background = PlaybackSettings.current.subtitleBackground

    var body: some View {
        PanelScaffold(title: "Subtitles") {
            TrackRow(title: "Off", detail: nil, selected: player.selectedSubtitle == nil) { player.selectSubtitle(nil) }
            ForEach(player.subtitleTracks) { track in
                TrackRow(title: track.label, detail: track.isForced ? "Forced: signs and foreign dialogue" : nil, selected: track.selected) {
                    player.selectSubtitle(track.id)
                }
            }
            HStack(spacing: 10) {
                Button("Find online…", action: onSearch).buttonStyle(NeonButtonStyle(prominent: true))
                Button("Open file…") { openFile() }.buttonStyle(NeonButtonStyle())
            }
            .padding(.top, 4)
            Divider().overlay(Neon.cyan.opacity(0.2))
            Toggle("Styled subtitles (libass: keep ASS/SSA fonts, colours and positions)", isOn: $styled)
                .toggleStyle(.switch)
                .font(NeonFont.body(13))
                .onChange(of: styled) { value in
                    player.setOption("sub-ass-override", value ? "scale" : "force")
                    save { $0.styledSubtitles = value }
                }
            Toggle("Dark box behind text", isOn: $background)
                .toggleStyle(.switch)
                .font(NeonFont.body(13))
                .onChange(of: background) { value in
                    player.setOption("sub-border-style", value ? "background-box" : "outline-and-shadow")
                    save { $0.subtitleBackground = value }
                }
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: "Text size %.0f%%", scale * 100)).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
                Slider(value: $scale, in: 0.5...2.0, step: 0.05)
                    .tint(Neon.cyan)
                    .onChange(of: scale) { value in
                        player.setOption("sub-scale", String(format: "%.2f", value))
                        save { $0.subtitleScale = value }
                    }
            }
        }
    }

    private func save(_ change: (inout PlaybackSettings) -> Void) {
        var settings = PlaybackSettings.current
        change(&settings)
        settings.save()
    }

    private func openFile() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = []
        panel.allowsOtherFileTypes = true
        panel.canChooseDirectories = false
        panel.message = "Choose a subtitle file (SRT, ASS, SSA, VTT or SUB)"
        if panel.runModal() == .OK, let url = panel.url {
            player.addSubtitle(url, title: url.deletingPathExtension().lastPathComponent, language: nil)
        }
    }
}

@MainActor
private struct SubtitleSearchPanel: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var player: MPVPlayer
    let request: PlaybackRequest
    @State private var query = ""
    @State private var languages = PlaybackSettings.current.subtitleLanguages.split(separator: ",").first.map(String.init) ?? "en"
    @State private var results: [SubtitleResult] = []
    @State private var status: String? = nil
    @State private var working = false

    var body: some View {
        PanelScaffold(title: "Find subtitles") {
            if !Secrets.has(.openSubtitles) {
                Text("Add your OpenSubtitles API key in Settings > Services to search here. It's free at opensubtitles.com.")
                    .font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
            TextField("Title", text: $query)
                .textFieldStyle(.plain)
                .padding(8)
                .neonPanel()
                .onSubmit(search)
            HStack {
                TextField("Language (en, fr, es…)", text: $languages)
                    .textFieldStyle(.plain)
                    .padding(8)
                    .neonPanel()
                    .frame(width: 150)
                Button(working ? "Searching…" : "Search", action: search)
                    .buttonStyle(NeonButtonStyle(prominent: true))
                    .disabled(working)
            }
            if let status {
                Text(status).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
            ForEach(results) { result in
                TrackRow(title: result.release, detail: result.detail, selected: false) { download(result) }
            }
        }
        .onAppear {
            if query.isEmpty { query = defaultQuery }
        }
    }

    private var defaultQuery: String {
        if let episode = request.episode, let series = request.item {
            let season = Int(episode.season) ?? 1
            let number = Int(episode.number ?? "") ?? 1
            return String(format: "%@ S%02dE%02d", series.cleanName, season, number)
        }
        return request.item?.cleanName ?? request.title
    }

    private func search() {
        guard !working else { return }
        working = true
        status = nil
        Task {
            do {
                results = try await OpenSubtitles.search(query: query, languages: languages, episode: request.episode)
                status = results.isEmpty ? "No subtitles found. Try a shorter title." : nil
            } catch {
                status = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            working = false
        }
    }

    private func download(_ result: SubtitleResult) {
        status = "Downloading…"
        Task {
            do {
                let file = try await OpenSubtitles.download(result)
                player.addSubtitle(file, title: result.release, language: result.language)
                status = "Added \"\(result.release)\". It's now selected."
            } catch {
                status = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}

@MainActor
private struct SyncPanel: View {
    @ObservedObject var player: MPVPlayer

    var body: some View {
        PanelScaffold(title: "Sync") {
            syncControl(
                title: "Subtitle delay",
                hint: "Subtitles too early? Move right. Keys: Z and X.",
                value: player.subtitleDelay,
                range: -10...10,
                step: 0.1,
                set: { player.setSubtitleDelay($0) }
            )
            syncControl(
                title: "Audio delay",
                hint: "Lips move before the sound? Move left. Keys: comma and full stop.",
                value: player.audioDelay,
                range: -5...5,
                step: 0.05,
                set: { player.setAudioDelay($0) }
            )
            VStack(alignment: .leading, spacing: 6) {
                Text(String(format: "Speed %.2g×", player.speed)).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text)
                HStack(spacing: 8) {
                    ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { value in
                        Button(String(format: "%.2g×", value)) { player.setSpeed(value) }
                            .buttonStyle(NeonButtonStyle(prominent: abs(player.speed - value) < 0.01))
                    }
                }
            }
        }
    }

    private func syncControl(title: String, hint: String, value: Double, range: ClosedRange<Double>, step: Double,
                             set: @escaping (Double) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(NeonFont.body(14, bold: true)).foregroundColor(Neon.text)
                Spacer()
                Text(String(format: "%+.2f s", value)).font(NeonFont.display(14)).foregroundColor(Neon.cyan)
            }
            Slider(value: Binding(get: { value }, set: { set(($0 / step).rounded() * step) }), in: range)
                .tint(Neon.cyan)
            HStack(spacing: 8) {
                Button("−\(String(format: "%.2g", step))") { set(value - step) }.buttonStyle(NeonButtonStyle())
                Button("+\(String(format: "%.2g", step))") { set(value + step) }.buttonStyle(NeonButtonStyle())
                Spacer()
                Button("Reset") { set(0) }.buttonStyle(NeonButtonStyle())
            }
            Text(hint).font(NeonFont.body(12)).foregroundColor(Neon.textMuted)
        }
        .padding(12)
        .neonPanel()
    }
}

@MainActor
private struct PicturePanel: View {
    @ObservedObject var player: MPVPlayer
    @State private var aspect = "-1"
    @State private var fill = false
    @State private var zoom = 0.0
    @State private var deinterlace = false

    private let aspects: [(String, String)] = [("-1", "Original"), ("16:9", "16:9"), ("4:3", "4:3"), ("2.35:1", "Cinema 2.35"), ("1.85:1", "1.85")]

    var body: some View {
        PanelScaffold(title: "Picture") {
            Text("Shape").font(NeonFont.body(13, bold: true)).foregroundColor(Neon.textSecondary)
            ForEach(aspects, id: \.0) { option in
                TrackRow(title: option.1, detail: nil, selected: aspect == option.0) {
                    aspect = option.0
                    player.setOption("video-aspect-override", option.0)
                }
            }
            Toggle("Fill the screen (crops the edges)", isOn: $fill)
                .toggleStyle(.switch)
                .font(NeonFont.body(13))
                .onChange(of: fill) { value in player.setOption("panscan", value ? "1.0" : "0.0") }
            VStack(alignment: .leading, spacing: 4) {
                Text(String(format: "Zoom %+.0f%%", zoom * 100)).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
                Slider(value: $zoom, in: -0.5...0.5, step: 0.05)
                    .tint(Neon.cyan)
                    .onChange(of: zoom) { value in player.setOption("video-zoom", String(format: "%.3f", log2(1 + value))) }
            }
            Toggle("Deinterlace (for older broadcast channels)", isOn: $deinterlace)
                .toggleStyle(.switch)
                .font(NeonFont.body(13))
                .onChange(of: deinterlace) { value in player.setOption("deinterlace", value ? "yes" : "no") }
        }
    }
}

@MainActor
private struct InfoPanel: View {
    @ObservedObject var player: MPVPlayer
    let request: PlaybackRequest

    var body: some View {
        PanelScaffold(title: "Stream info") {
            row("Video", player.videoCodec ?? "—")
            if let size = player.videoSize {
                row("Resolution", "\(Int(size.width))×\(Int(size.height))" + resolutionName(size))
            }
            if let fps = player.frameRate { row("Frame rate", String(format: "%.3g fps", fps)) }
            row("Dynamic range", player.isHDR ? "HDR (tone-mapped for this screen when needed)" : "SDR")
            row("Decoding", decodingText)
            if let audio = player.selectedAudio { row("Audio", audio.label) }
            if let subtitle = player.selectedSubtitle { row("Subtitles", subtitle.label) }
            row("Buffered", String(format: "%.0f s ahead", player.cachedSeconds))
            row("Renderer", PlaybackSettings.current.renderer.label)
            if let host = request.url.host { row("Server", host) }
        }
    }

    private var decodingText: String {
        guard let decoder = player.hardwareDecoder, decoder != "no", !decoder.isEmpty else { return "Software" }
        return "Hardware (\(decoder))"
    }

    private func resolutionName(_ size: CGSize) -> String {
        if size.width >= 3800 { return "  4K" }
        if size.width >= 1900 { return "  1080p" }
        if size.width >= 1260 { return "  720p" }
        return ""
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).font(NeonFont.body(13)).foregroundColor(Neon.textMuted).frame(width: 110, alignment: .leading)
            Text(value).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.text)
            Spacer()
        }
    }
}

@MainActor
private struct ChannelPanel: View {
    @EnvironmentObject private var model: AppModel
    let request: PlaybackRequest
    @State private var query = ""

    private var channels: [MediaItem] {
        guard !query.isEmpty else { return request.channelList }
        return request.channelList.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Channels").font(NeonFont.display(18)).foregroundColor(Neon.cyan)
            TextField("Filter channels", text: $query)
                .textFieldStyle(.plain)
                .padding(8)
                .neonPanel()
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(Array(channels.enumerated()), id: \.element.id) { index, item in
                            PanelChannelRow(item: item, number: item.number ?? (index + 1), playing: item.id == request.item?.id) {
                                model.playLive(item, in: request.channelList)
                            }
                            .id(item.id)
                        }
                    }
                }
                .onAppear {
                    if let id = request.item?.id { proxy.scrollTo(id, anchor: .center) }
                }
            }
        }
        .padding(20)
    }
}

@MainActor
private struct PanelChannelRow: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    let number: Int
    let playing: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        let now = Date()
        let current = model.nowNext(for: item).first { $0.isOn(at: now) }
        Button(action: action) {
            HStack(spacing: 10) {
                Text("\(number)").font(NeonFont.display(13)).foregroundColor(lit ? Neon.onCyan : Neon.cyan).frame(width: 40, alignment: .trailing)
                RemoteImage(url: item.icon, contentMode: .fit).frame(width: 36, height: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(NeonFont.body(13, bold: true)).foregroundColor(lit ? Neon.onCyan : Neon.text).lineLimit(1)
                    if let current {
                        Text(current.title).font(NeonFont.body(11)).foregroundColor(lit ? Neon.onCyan.opacity(0.8) : Neon.textMuted).lineLimit(1)
                    }
                }
                Spacer()
                if playing {
                    Image(systemName: "speaker.wave.2.fill").foregroundColor(lit ? Neon.onCyan : Neon.magenta)
                }
            }
            .padding(8)
            .background(HudShape(cut: 7).fill(lit ? Neon.cyan : Neon.surface.opacity(0.8)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .task(id: item.id) { await model.loadEPG(for: item) }
    }

    private var lit: Bool { hovering || playing }
}

// MARK: - Multiview

@MainActor
struct MultiviewScreen: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var playback: PlaybackCenter
    @StateObject private var keys = KeyMonitor()
    @State private var picking = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    NeonTitle(text: "Multiview", size: 22)
                    Text("Click a picture to hear it. Double-click to watch it full screen.")
                        .font(NeonFont.body(13))
                        .foregroundColor(Neon.textSecondary)
                    Spacer()
                    if playback.canAddTile {
                        Button { withAnimation(Motion.quick) { picking = true } } label: { Label("Add channel", systemImage: "plus") }
                            .buttonStyle(NeonButtonStyle(prominent: true))
                    }
                    Button { playback.closeMultiview() } label: { Label("Close", systemImage: "xmark") }
                        .buttonStyle(NeonButtonStyle())
                }
                .padding(16)
                GeometryReader { geometry in
                    let columns = playback.tiles.count <= 1 ? 1 : 2
                    let rows = playback.tiles.count <= 2 ? 1 : 2
                    let width = (geometry.size.width - CGFloat(columns + 1) * 10) / CGFloat(columns)
                    let height = (geometry.size.height - CGFloat(rows + 1) * 10) / CGFloat(rows)
                    let grid = Array(repeating: GridItem(.fixed(width), spacing: 10), count: columns)
                    LazyVGrid(columns: grid, spacing: 10) {
                        ForEach(playback.tiles) { tile in
                            MultiviewTileView(tile: tile, audible: playback.audioTile == tile.id)
                                .frame(width: width, height: height)
                        }
                    }
                    .padding(10)
                }
            }
            if picking {
                MultiviewPicker(onClose: { withAnimation(Motion.quick) { picking = false } })
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .onAppear {
            keys.start { event in
                if event.keyCode == 53 {
                    if picking { withAnimation(Motion.quick) { picking = false } } else { playback.closeMultiview() }
                    return true
                }
                if let digit = Int(event.charactersIgnoringModifiers ?? ""), digit >= 1, digit <= playback.tiles.count {
                    playback.setAudio(playback.tiles[digit - 1].id)
                    return true
                }
                return false
            }
        }
        .onDisappear { keys.stop() }
    }
}

@MainActor
private struct MultiviewTileView: View {
    @EnvironmentObject private var playback: PlaybackCenter
    let tile: MultiviewTile
    let audible: Bool
    @State private var hovering = false

    var body: some View {
        ZStack(alignment: .topLeading) {
            VideoSurface(player: tile.player)
            TileStatus(player: tile.player)
            HStack(spacing: 8) {
                if audible {
                    Image(systemName: "speaker.wave.2.fill").foregroundColor(Neon.cyan)
                }
                Text(tile.item.name).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.text).lineLimit(1)
                Spacer()
                if hovering {
                    IconButton(symbol: "arrow.up.left.and.arrow.down.right", help: "Watch full screen") { playback.expandTile(tile.id) }
                    IconButton(symbol: "xmark", help: "Remove") { playback.removeTile(tile.id) }
                }
            }
            .padding(10)
            .background(LinearGradient(colors: [.black.opacity(0.7), .clear], startPoint: .top, endPoint: .bottom))
        }
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(audible ? Neon.cyan : Neon.cyan.opacity(0.15), lineWidth: audible ? 3 : 1))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .shadow(color: Neon.cyan.opacity(audible ? 0.45 : 0), radius: 14)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { playback.expandTile(tile.id) }
        .onTapGesture { playback.setAudio(tile.id) }
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }
}

@MainActor
private struct TileStatus: View {
    @ObservedObject var player: MPVPlayer

    var body: some View {
        if !player.loaded || player.buffering {
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if player.failure != nil {
            Text("This channel didn't load").font(NeonFont.body(13)).foregroundColor(Neon.danger)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// Channel chooser for multiview: favourites first, then search across all live channels.
@MainActor
private struct MultiviewPicker: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @EnvironmentObject private var playback: PlaybackCenter
    let onClose: () -> Void
    @State private var query = ""
    @State private var results: [MediaItem] = []

    var body: some View {
        HStack {
            Spacer()
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Add a channel").font(NeonFont.display(18)).foregroundColor(Neon.cyan)
                    Spacer()
                    IconButton(symbol: "xmark", help: "Close", action: onClose)
                }
                TextField("Search live channels", text: $query)
                    .textFieldStyle(.plain)
                    .padding(8)
                    .neonPanel()
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(shown) { item in
                            PanelChannelRow(item: item, number: item.number ?? 0, playing: false) {
                                playback.addTile(item)
                                onClose()
                            }
                        }
                    }
                }
            }
            .padding(20)
            .frame(width: 420)
            .frame(maxHeight: .infinity)
            .background(Neon.background.opacity(0.96))
        }
        .task(id: query) {
            try? await Task.sleep(nanoseconds: 200_000_000)
            guard !query.isEmpty, let catalog = model.catalog else {
                results = []
                return
            }
            results = await catalog.search(query, kinds: [.live], limit: 80)
        }
    }

    private var shown: [MediaItem] {
        if !query.isEmpty { return results }
        return userData.groups.flatMap { $0.items }.filter { $0.kind == .live }
    }
}
