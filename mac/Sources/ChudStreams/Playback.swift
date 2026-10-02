import AVKit
import AppKit
import Combine
import SwiftUI

/// One of up to four live channels in multiview.
struct MultiviewTile: Identifiable {
    let id = UUID()
    let item: MediaItem
    let player: MPVPlayer
}

/// Runs playback for the whole app: the main player (full screen or the mini player in the corner
/// while browsing), multiview, resume points, history, retries and up next.
@MainActor
final class PlaybackCenter: ObservableObject {
    enum Presentation: Equatable { case full, mini }

    @Published private(set) var current: PlaybackRequest? = nil
    @Published var presentation: Presentation = .full
    @Published private(set) var player: MPVPlayer? = nil
    @Published private(set) var applePlayer: AVPlayer? = nil
    /// Seconds until the next episode starts, while the "Up next" card shows.
    @Published private(set) var upNextCountdown: Int? = nil
    @Published private(set) var retrying: Int? = nil
    @Published private(set) var errorMessage: String? = nil
    @Published private(set) var tiles: [MultiviewTile] = []
    @Published var multiviewVisible = false
    @Published private(set) var audioTile: UUID? = nil
    /// Which corner the mini player sits in.
    @Published var miniCorner: Alignment = .bottomTrailing

    weak var model: AppModel?
    private var progressTimer: Timer? = nil
    private var appleObserver: Any? = nil
    private var upNextTask: Task<Void, Never>? = nil
    private var retryTask: Task<Void, Never>? = nil
    private var subscriptions = Set<AnyCancellable>()

    var isActive: Bool { current != nil }
    var isFullScreen: Bool { current != nil && presentation == .full && !multiviewVisible }

    // MARK: Main player

    func play(_ request: PlaybackRequest) {
        let settings = PlaybackSettings.current
        upNextTask?.cancel()
        upNextCountdown = nil
        retryTask?.cancel()
        retrying = nil
        errorMessage = nil
        if multiviewVisible && request.live {
            // Picking a channel while multiview is open adds it there instead.
            addTile(request.item)
            return
        }
        closeMultiview()
        saveProgress()

        switch settings.engine {
        case .vlc:
            model?.openExternally(request.url)
            return
        case .apple:
            if AppModel.nativeFormats.contains(request.url.pathExtension.lowercased()) || request.url.pathExtension.isEmpty {
                startApple(request)
                return
            }
            model?.notice = "Apple's player can't open this format, so it's playing in the built-in player."
        case .builtIn:
            break
        }

        let mpv = mainPlayer(settings)
        guard mpv.isAvailable else {
            // mpv couldn't start: fall back to Apple's player, or VLC for other formats.
            ErrorLog.record("Built-in player failed to start", detail: "Falling back")
            if AppModel.nativeFormats.contains(request.url.pathExtension.lowercased()) {
                startApple(request)
            } else {
                model?.openExternally(request.url)
            }
            return
        }
        stopApple()
        let firstStart = current == nil
        current = request
        if firstStart { presentation = .full }
        var extra: [String: String] = [:]
        if !request.headers.isEmpty {
            let fields = request.headers.map { name, value in
                "\(name): \(value.replacingOccurrences(of: ",", with: " "))"
            }
            extra["http-header-fields"] = fields.joined(separator: ",")
            if let agent = request.headers["User-Agent"] { extra["user-agent"] = agent }
        }
        if let subtitle = request.subtitleURL { extra["sub-file"] = subtitle.absoluteString }
        mpv.load(request.url, start: request.startAt, extraOptions: extra)
        startProgressTimer()
    }

    private func mainPlayer(_ settings: PlaybackSettings) -> MPVPlayer {
        let metal = settings.renderer.usesMetal
        let options = settings.mpvOptions()
        let signature = options.map { "\($0.0)=\($0.1)" }.joined(separator: ";") + (metal ? ";metal" : "")
        if let existing = player, existing.signature == signature, existing.isAvailable { return existing }
        player?.shutdown()
        let created = MPVPlayer(options: options, metal: metal)
        created.onEnd = { [weak self] reason, error in self?.handleEnd(reason, error: error) }
        created.onLog = { level, text in
            if level == "error" { ErrorLog.record("Player", detail: text) }
            if TourRunner.isRunning { print("[mpv \(level)] \(text)") }
        }
        player = created
        return created
    }

    func stop() {
        saveProgress()
        upNextTask?.cancel()
        retryTask?.cancel()
        upNextCountdown = nil
        retrying = nil
        errorMessage = nil
        progressTimer?.invalidate()
        progressTimer = nil
        player?.stop()
        stopApple()
        withAnimation(Motion.page) {
            current = nil
            presentation = .full
        }
    }

    /// Esc / the close button: shrink to the mini player (if that's the setting), or stop.
    func close() {
        if presentation == .full && PlaybackSettings.current.miniPlayerOnClose && current != nil {
            minimise()
        } else {
            stop()
        }
    }

    func minimise() {
        guard current != nil else { return }
        withAnimation(Motion.page) { presentation = .mini }
    }

    func expand() {
        guard current != nil else { return }
        withAnimation(Motion.page) { presentation = .full }
    }

    func togglePause() {
        if let applePlayer {
            if applePlayer.timeControlStatus == .playing { applePlayer.pause() } else { applePlayer.play() }
        } else {
            player?.togglePause()
        }
    }

    func seek(by seconds: Double) {
        if let applePlayer {
            let target = applePlayer.currentTime().seconds + seconds
            applePlayer.seek(to: CMTime(seconds: max(0, target), preferredTimescale: 600))
        } else {
            player?.seek(by: seconds)
        }
    }

    func retryNow() {
        guard let current else { return }
        retryTask?.cancel()
        retrying = nil
        errorMessage = nil
        player?.load(current.url, start: current.live ? 0 : (player?.position ?? current.startAt))
    }

    // MARK: Progress, history and resume

    private func startProgressTimer() {
        progressTimer?.invalidate()
        progressTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.saveProgress() }
        }
    }

    /// The position and length of what's playing, in seconds.
    var positionAndDuration: (Double, Double)? {
        if let applePlayer, let item = applePlayer.currentItem {
            let position = applePlayer.currentTime().seconds
            let duration = item.duration.seconds
            guard position.isFinite, duration.isFinite else { return nil }
            return (position, duration)
        }
        guard let player, player.loaded else { return nil }
        return (player.position, player.duration)
    }

    func saveProgress() {
        guard let current, let model, let timing = positionAndDuration else { return }
        let position = timing.0
        let duration = timing.1
        guard duration > 0, position > 0 else { return }
        if let key = current.resumeKey {
            model.saveResume(key: key, seconds: position, duration: duration)
        }
        if let historyId = current.historyId, !current.live {
            model.userData.updateProgress(id: historyId, position: position, duration: duration)
        }
    }

    // MARK: End of file, retries and up next

    private func handleEnd(_ reason: EndReason, error: String?) {
        guard let current else { return }
        switch reason {
        case .finished:
            saveProgress()
            if current.kind == .episode, let next = current.upNext.first, PlaybackSettings.current.autoplayNext {
                startUpNext(next)
            } else if current.live, PlaybackSettings.current.liveRetry {
                // A live stream shouldn't end: treat it as a drop and reconnect.
                scheduleRetry(attempt: 1, message: "The stream stopped.")
            }
        case .error:
            let message = error ?? "The stream couldn't be played."
            ErrorLog.record("Playback failed: \(current.title)", detail: message)
            if PlaybackSettings.current.liveRetry && (current.live || current.kind == .catchUp || (retrying ?? 0) < 2) {
                scheduleRetry(attempt: (retrying ?? 0) + 1, message: message)
            } else {
                errorMessage = describe(message)
            }
        case .stopped, .other:
            break
        }
    }

    private func scheduleRetry(attempt: Int, message: String) {
        guard attempt <= 4, let request = current else {
            retrying = nil
            errorMessage = describe(message)
            return
        }
        retrying = attempt
        let delays: [UInt64] = [1, 3, 6, 10]
        let wait = delays[min(attempt - 1, delays.count - 1)]
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: wait * 1_000_000_000)
            guard let self, !Task.isCancelled, self.current?.id == request.id else { return }
            let position = request.live ? 0 : max(request.startAt, self.player?.position ?? 0)
            self.player?.load(request.url, start: position)
            // If it plays for 15 seconds, the retry worked.
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            if !Task.isCancelled, self.player?.loaded == true, self.current?.id == request.id { self.retrying = nil }
        }
    }

    private func describe(_ message: String) -> String {
        let lower = message.lowercased()
        if lower.contains("403") || lower.contains("forbidden") {
            return "The provider refused this stream (error 403). Your account may be in use on too many devices."
        }
        if lower.contains("404") { return "The provider says this stream doesn't exist any more (error 404)." }
        if lower.contains("loading failed") || lower.contains("unrecognized") {
            return "The stream didn't load. It may be offline right now; try again or pick another channel."
        }
        return "Playback stopped: \(message)"
    }

    private func startUpNext(_ episode: Episode) {
        upNextTask?.cancel()
        upNextCountdown = 10
        upNextTask = Task { [weak self] in
            for remaining in stride(from: 9, through: 0, by: -1) {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                self.upNextCountdown = remaining
            }
            guard let self, !Task.isCancelled else { return }
            self.playNext()
        }
    }

    func cancelUpNext() {
        upNextTask?.cancel()
        upNextCountdown = nil
    }

    func playNext() {
        upNextTask?.cancel()
        upNextCountdown = nil
        guard let current, current.kind == .episode, let series = current.item, let next = current.upNext.first else { return }
        model?.playEpisode(next, of: series, fromStart: true, upNext: Array(current.upNext.dropFirst()))
    }

    // MARK: Apple's player (fallback)

    private func startApple(_ request: PlaybackRequest) {
        player?.stop()
        stopApple()
        let avPlayer = AVPlayer(url: request.url)
        applePlayer = avPlayer
        current = request
        presentation = .full
        if request.startAt > 0 {
            avPlayer.seek(to: CMTime(seconds: request.startAt, preferredTimescale: 600))
        }
        avPlayer.play()
        startProgressTimer()
    }

    private func stopApple() {
        applePlayer?.pause()
        applePlayer = nil
    }

    // MARK: Multiview

    var canAddTile: Bool { tiles.count < 4 }

    func openMultiview() {
        if tiles.isEmpty, let current, current.live, let item = current.item {
            player?.stop()
            self.current = nil
            addTile(item)
        }
        withAnimation(Motion.page) { multiviewVisible = true }
    }

    func addTile(_ item: MediaItem?) {
        guard let item, item.kind == .live, let model else { return }
        guard tiles.count < 4 else {
            model.notice = "Multiview shows up to four channels. Close one to add another."
            return
        }
        guard !tiles.contains(where: { $0.item.id == item.id }) else { return }
        guard let url = model.liveURL(for: item, hls: false) else { return }
        if current != nil {
            saveProgress()
            player?.stop()
            current = nil
        }
        let settings = PlaybackSettings.current
        let tilePlayer = MPVPlayer(options: settings.mpvOptions(forTile: true), metal: false)
        guard tilePlayer.isAvailable else {
            model.notice = "The built-in player couldn't start another stream."
            return
        }
        tilePlayer.onLog = { level, text in
            if level == "error" { ErrorLog.record("Multiview", detail: text) }
        }
        tilePlayer.load(url)
        let tile = MultiviewTile(item: item, player: tilePlayer)
        withAnimation(Motion.quick) { tiles.append(tile) }
        setAudio(audioTile ?? tile.id)
        multiviewVisible = true
    }

    func removeTile(_ id: UUID) {
        guard let index = tiles.firstIndex(where: { $0.id == id }) else { return }
        let removed = tiles[index]
        withAnimation(Motion.quick) { _ = tiles.remove(at: index) }
        removed.player.shutdown()
        if audioTile == id { setAudio(tiles.first?.id) }
        if tiles.isEmpty { closeMultiview() }
    }

    func setAudio(_ id: UUID?) {
        audioTile = id
        for tile in tiles { tile.player.setMuted(tile.id != id) }
    }

    /// Makes one tile the main full-screen stream.
    func expandTile(_ id: UUID) {
        guard let tile = tiles.first(where: { $0.id == id }), let model else { return }
        let item = tile.item
        let list = tiles.map { $0.item }
        closeMultiview()
        model.playLive(item, in: list)
    }

    func closeMultiview() {
        for tile in tiles { tile.player.shutdown() }
        tiles = []
        audioTile = nil
        if multiviewVisible {
            withAnimation(Motion.page) { multiviewVisible = false }
        }
    }
}
