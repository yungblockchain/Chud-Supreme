import AppKit
import Libmpv
import OpenGL.GL
import OpenGL.GL3
import QuartzCore

// The built-in player: mpv (with FFmpeg and libass) rendering through OpenGL into a layer that
// SwiftUI can place anywhere: full screen, the mini player in the corner, or a multiview tile.
// Each MPVPlayer is one mpv instance; multiview runs up to four at once.
//
// Threading: mpv calls back from its own threads. MPVCore drains events on a private queue and
// hands them to the main thread, where MPVPlayer publishes them to SwiftUI. Rendering happens on
// the main thread inside CAOpenGLLayer's draw call.

// MARK: - Events and tracks

enum MPVEvent {
    case property(String, Any?)
    case startFile
    case fileLoaded
    case endFile(reason: EndReason, error: String?)
    case log(level: String, text: String)
}

enum EndReason: Equatable {
    case finished, stopped, error, other
}

/// An audio, subtitle or video track as mpv lists it.
struct MPVTrack: Identifiable, Hashable {
    let id: Int
    let type: String
    let title: String?
    let language: String?
    let codec: String?
    let isDefault: Bool
    let isForced: Bool
    let isExternal: Bool
    let selected: Bool
    let channels: String?

    var label: String {
        var parts: [String] = []
        if let title, !title.isEmpty { parts.append(title) }
        if let language, !language.isEmpty {
            let name = Locale.current.localizedString(forLanguageCode: language) ?? language
            if !parts.contains(name) { parts.append(name) }
        }
        if parts.isEmpty { parts.append("Track \(id)") }
        var extras: [String] = []
        if let codec { extras.append(codec.uppercased()) }
        if let channels, !channels.isEmpty { extras.append(channels) }
        if isForced { extras.append("forced") }
        if isExternal { extras.append("added") }
        return extras.isEmpty ? parts.joined(separator: " · ") : parts.joined(separator: " · ") + "  (" + extras.joined(separator: ", ") + ")"
    }
}

// MARK: - Core (one mpv instance, thread-safe)

final class MPVCore: @unchecked Sendable {
    private(set) var handle: OpaquePointer?
    private let queue = DispatchQueue(label: "chud.mpv.events", qos: .userInitiated)
    /// Called on the main thread with each batch of events.
    var onEvents: (([MPVEvent]) -> Void)?

    static let observed: [(String, mpv_format)] = [
        ("time-pos", MPV_FORMAT_DOUBLE),
        ("duration", MPV_FORMAT_DOUBLE),
        ("pause", MPV_FORMAT_FLAG),
        ("paused-for-cache", MPV_FORMAT_FLAG),
        ("seeking", MPV_FORMAT_FLAG),
        ("eof-reached", MPV_FORMAT_FLAG),
        ("demuxer-cache-duration", MPV_FORMAT_DOUBLE),
        ("cache-buffering-state", MPV_FORMAT_INT64),
        ("track-list", MPV_FORMAT_NODE),
        ("sub-delay", MPV_FORMAT_DOUBLE),
        ("audio-delay", MPV_FORMAT_DOUBLE),
        ("speed", MPV_FORMAT_DOUBLE),
        ("volume", MPV_FORMAT_DOUBLE),
        ("mute", MPV_FORMAT_FLAG),
        ("video-params/w", MPV_FORMAT_INT64),
        ("video-params/h", MPV_FORMAT_INT64),
        ("video-params/gamma", MPV_FORMAT_STRING),
        ("video-params/sig-peak", MPV_FORMAT_DOUBLE),
        ("video-codec", MPV_FORMAT_STRING),
        ("hwdec-current", MPV_FORMAT_STRING),
        ("container-fps", MPV_FORMAT_DOUBLE),
        ("media-title", MPV_FORMAT_STRING),
    ]

    /// Creates and starts mpv with the given options, or returns nil if mpv can't start.
    init?(options: [(String, String)], logLevel: String = ProcessInfo.processInfo.environment["CHUD_TOUR"] != nil ? "v" : "warn") {
        guard let created = mpv_create() else { return nil }
        for (name, value) in options {
            mpv_set_option_string(created, name, value)
        }
        guard mpv_initialize(created) >= 0 else {
            mpv_terminate_destroy(created)
            return nil
        }
        handle = created
        mpv_request_log_messages(created, logLevel)
        for (index, entry) in MPVCore.observed.enumerated() {
            mpv_observe_property(created, UInt64(index + 1), entry.0, entry.1)
        }
        // The callback holds a reference to this core until destroy() releases it.
        let context = Unmanaged.passRetained(self).toOpaque()
        mpv_set_wakeup_callback(created, { context in
            guard let context else { return }
            Unmanaged<MPVCore>.fromOpaque(context).takeUnretainedValue().scheduleDrain()
        }, context)
    }

    private func scheduleDrain() {
        queue.async { [weak self] in self?.drain() }
    }

    private func drain() {
        var batch: [MPVEvent] = []
        while let handle, let event = mpv_wait_event(handle, 0) {
            let e = event.pointee
            if e.event_id == MPV_EVENT_NONE { break }
            switch e.event_id {
            case MPV_EVENT_PROPERTY_CHANGE:
                if let data = e.data {
                    let property = data.assumingMemoryBound(to: mpv_event_property.self).pointee
                    batch.append(.property(String(cString: property.name), MPVCore.value(of: property)))
                }
            case MPV_EVENT_START_FILE:
                batch.append(.startFile)
            case MPV_EVENT_FILE_LOADED:
                batch.append(.fileLoaded)
            case MPV_EVENT_END_FILE:
                if let data = e.data {
                    let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    let reason: EndReason
                    switch end.reason {
                    case MPV_END_FILE_REASON_EOF: reason = .finished
                    case MPV_END_FILE_REASON_STOP, MPV_END_FILE_REASON_QUIT: reason = .stopped
                    case MPV_END_FILE_REASON_ERROR: reason = .error
                    default: reason = .other
                    }
                    let message = end.error < 0 ? String(cString: mpv_error_string(end.error)) : nil
                    batch.append(.endFile(reason: reason, error: message))
                }
            case MPV_EVENT_LOG_MESSAGE:
                if let data = e.data {
                    let log = data.assumingMemoryBound(to: mpv_event_log_message.self).pointee
                    let level = log.level.map { String(cString: $0) } ?? "info"
                    let prefix = log.prefix.map { String(cString: $0) } ?? "mpv"
                    let text = log.text.map { String(cString: $0) } ?? ""
                    batch.append(.log(level: level, text: "[\(prefix)] \(text.trimmingCharacters(in: .whitespacesAndNewlines))"))
                }
            case MPV_EVENT_SHUTDOWN:
                break
            default:
                break
            }
        }
        guard !batch.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in self?.onEvents?(batch) }
    }

    private static func value(of property: mpv_event_property) -> Any? {
        guard let data = property.data else { return nil }
        switch property.format {
        case MPV_FORMAT_DOUBLE: return data.load(as: Double.self)
        case MPV_FORMAT_FLAG: return data.load(as: Int32.self) != 0
        case MPV_FORMAT_INT64: return data.load(as: Int64.self)
        case MPV_FORMAT_STRING:
            return data.load(as: UnsafePointer<CChar>?.self).map { String(cString: $0) }
        case MPV_FORMAT_NODE: return value(of: data.load(as: mpv_node.self))
        default: return nil
        }
    }

    static func value(of node: mpv_node) -> Any? {
        switch node.format {
        case MPV_FORMAT_STRING: return node.u.string.map { String(cString: $0) }
        case MPV_FORMAT_FLAG: return node.u.flag != 0
        case MPV_FORMAT_INT64: return node.u.int64
        case MPV_FORMAT_DOUBLE: return node.u.double_
        case MPV_FORMAT_NODE_ARRAY:
            guard let list = node.u.list?.pointee, let values = list.values else { return [Any]() }
            return (0..<Int(list.num)).compactMap { value(of: values[$0]) }
        case MPV_FORMAT_NODE_MAP:
            guard let list = node.u.list?.pointee, let values = list.values, let keys = list.keys else { return [String: Any]() }
            var map: [String: Any] = [:]
            for index in 0..<Int(list.num) {
                if let key = keys[index], let value = value(of: values[index]) {
                    map[String(cString: key)] = value
                }
            }
            return map
        default:
            return nil
        }
    }

    // MARK: Commands

    func command(_ args: [String]) {
        guard let handle else { return }
        var pointers: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
        pointers.append(nil)
        defer { pointers.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        pointers.withUnsafeMutableBufferPointer { buffer in
            _ = mpv_command_async(handle, 0, buffer.baseAddress)
        }
    }

    func set(_ name: String, _ value: String) {
        guard let handle else { return }
        mpv_set_property_string(handle, name, value)
    }

    func set(_ name: String, flag: Bool) {
        guard let handle else { return }
        var data: Int32 = flag ? 1 : 0
        mpv_set_property(handle, name, MPV_FORMAT_FLAG, &data)
    }

    func set(_ name: String, double: Double) {
        guard let handle else { return }
        var data = double
        mpv_set_property(handle, name, MPV_FORMAT_DOUBLE, &data)
    }

    func string(_ name: String) -> String? {
        guard let handle, let pointer = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(pointer) }
        return String(cString: pointer)
    }

    func double(_ name: String) -> Double? {
        guard let handle else { return nil }
        var data = 0.0
        return mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, &data) >= 0 ? data : nil
    }

    /// Stops mpv and frees it. Any render context must be freed first (MPVPlayer does that).
    /// `keepAlive` (a Metal layer mpv draws into) stays alive until mpv has fully stopped.
    func destroy(keepAlive: AnyObject? = nil) {
        guard let handle else { return }
        mpv_set_wakeup_callback(handle, nil, nil)
        self.handle = nil
        onEvents = nil
        let retained = Unmanaged.passUnretained(self)
        queue.async {
            mpv_terminate_destroy(handle)
            withExtendedLifetime(keepAlive) {}
            retained.release()
        }
    }
}

// MARK: - Player (published state for SwiftUI)

final class MPVPlayer: ObservableObject, Identifiable {
    let id = UUID()
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var paused = false
    @Published private(set) var buffering = false
    @Published private(set) var bufferPercent: Int = 0
    @Published private(set) var cachedSeconds: Double = 0
    @Published private(set) var seeking = false
    @Published private(set) var ended = false
    @Published private(set) var loaded = false
    @Published private(set) var failure: String? = nil
    @Published private(set) var tracks: [MPVTrack] = []
    @Published private(set) var subtitleDelay: Double = 0
    @Published private(set) var audioDelay: Double = 0
    @Published private(set) var speed: Double = 1
    @Published private(set) var volume: Double = 100
    @Published private(set) var muted = false
    @Published private(set) var videoSize: CGSize? = nil
    @Published private(set) var isHDR = false
    @Published private(set) var videoCodec: String? = nil
    @Published private(set) var hardwareDecoder: String? = nil
    @Published private(set) var frameRate: Double? = nil
    @Published private(set) var mediaTitle: String = ""
    @Published private(set) var lyrics: String? = nil
    private var lyricTask: Task<Void, Never>?

    /// Called on the main thread when a file ends (finished, stopped or failed).
    var onEnd: ((EndReason, String?) -> Void)?
    /// mpv warnings and errors, for the error log.
    var onLog: ((String, String) -> Void)?

    private(set) var core: MPVCore?
    /// For the OpenGL renderer: mpv's render context, created with the player so video output is
    /// ready before any view appears (mpv turns video off if a file starts without one).
    private(set) var gl: MPVGLRenderer?

    /// False if mpv couldn't start (the app then falls back to Apple's player).
    var isAvailable: Bool { core != nil }

    /// For the Metal renderer: the layer mpv draws into by itself (gpu-next through MoltenVK).
    let metalLayer: MPVMetalLayer?
    /// The options this player was started with, so a settings change can tell it needs a new one.
    let signature: String

    init(options: [(String, String)], metal: Bool = false) {
        signature = options.map { "\($0.0)=\($0.1)" }.joined(separator: ";") + (metal ? ";metal" : "")
        var all = options
        if metal {
            let layer = MPVMetalLayer()
            metalLayer = layer
            // mpv takes the layer's address as a number ("wid").
            let address = Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque())
            all.append(("wid", String(address)))
        } else {
            metalLayer = nil
            all = all.map { $0.0 == "vo" ? ("vo", "libmpv") : $0 }
        }
        core = MPVCore(options: all)
        core?.onEvents = { [weak self] events in self?.handle(events) }
        if !metal, let core {
            gl = MPVGLRenderer(core: core)
            if let gl, !gl.accelerated {
                // Apple's software OpenGL can't run mpv's high-quality scaling filters (they come
                // out as a grid of black lines) and is slow anyway, so use the simple ones.
                ErrorLog.note("Player: software OpenGL (\(gl.rendererName)), using simple scaling")
                for name in ["scale", "cscale", "dscale"] { core.set(name, "bilinear") }
                for name in ["sigmoid-upscaling", "correct-downscaling", "linear-downscaling", "deband"] { core.set(name, "no") }
                core.set("dither-depth", "no")
            }
            if gl == nil {
                // No OpenGL: fall back to mpv's own Metal output rather than no picture at all.
                ErrorLog.record("Player", detail: "OpenGL render context unavailable")
            }
        }
    }

    var usesMetal: Bool { metalLayer != nil }

    deinit {
        gl?.destroy()
        core?.destroy(keepAlive: metalLayer)
    }

    var audioTracks: [MPVTrack] { tracks.filter { $0.type == "audio" } }
    var subtitleTracks: [MPVTrack] { tracks.filter { $0.type == "sub" } }
    var selectedSubtitle: MPVTrack? { subtitleTracks.first { $0.selected } }
    var selectedAudio: MPVTrack? { audioTracks.first { $0.selected } }

    // MARK: Control

    /// Opens a stream or file, starting at `start` seconds (0 for the beginning or live).
    func load(_ url: URL, start: Double = 0, extraOptions: [String: String] = [:]) {
        ended = false
        loaded = false
        failure = nil
        position = 0
        duration = 0
        core?.set("start", start > 1 ? String(format: "%.1f", start) : "none")
        for (name, value) in extraOptions { core?.set(name, value) }
        core?.command(["loadfile", url.isFileURL ? url.path : url.absoluteString, "replace"])
        core?.set("pause", flag: false)
    }

    func stop() {
        core?.command(["stop"])
    }

    func togglePause() { core?.set("pause", flag: !paused) }
    func setPaused(_ value: Bool) { core?.set("pause", flag: value) }

    func seek(to seconds: Double) {
        core?.command(["seek", String(format: "%.2f", max(0, seconds)), "absolute+keyframes"])
    }

    func seek(by seconds: Double) {
        core?.command(["seek", String(format: "%.2f", seconds), "relative+keyframes"])
    }

    func selectAudio(_ id: Int) { core?.set("aid", String(id)) }
    func selectSubtitle(_ id: Int?) { core?.set("sid", id.map(String.init) ?? "no") }

    func setSubtitleDelay(_ seconds: Double) { core?.set("sub-delay", double: seconds) }
    func setAudioDelay(_ seconds: Double) { core?.set("audio-delay", double: seconds) }
    func setSpeed(_ value: Double) { core?.set("speed", double: min(4, max(0.25, value))) }
    func setVolume(_ value: Double) { core?.set("volume", double: min(130, max(0, value))) }
    func setMuted(_ value: Bool) { core?.set("mute", flag: value) }

    /// Adds a subtitle file (downloaded from OpenSubtitles, or chosen by the viewer) and selects it.
    func addSubtitle(_ url: URL, title: String?, language: String?) {
        var args = ["sub-add", url.isFileURL ? url.path : url.absoluteString, "select"]
        args.append(title ?? "Added subtitles")
        if let language { args.append(language) }
        core?.command(args)
    }

    /// Applies a setting while playing (subtitle size, tone mapping and so on).
    func setOption(_ name: String, _ value: String) { core?.set(name, value) }

    func shutdown() {
        gl?.destroy()
        gl = nil
        metalLayer?.removeFromSuperlayer()
        core?.destroy(keepAlive: metalLayer)
        core = nil
    }

    // MARK: Events

    private func handle(_ events: [MPVEvent]) {
        for event in events {
            switch event {
            case .property(let name, let value):
                apply(name, value)
            case .startFile:
                loaded = false
                ended = false
            case .fileLoaded:
                loaded = true
                failure = nil
            case .endFile(let reason, let error):
                if reason == .finished {
                    if !ended {
                        ended = true
                        onEnd?(.finished, nil)
                    }
                } else {
                    if reason == .error { failure = error ?? "The stream couldn't be played." }
                    onEnd?(reason, error)
                }
            case .log(let level, let text):
                onLog?(level, text)
            }
        }
    }

    private func apply(_ name: String, _ value: Any?) {
        switch name {
        case "time-pos": position = (value as? Double) ?? 0
        case "duration": duration = (value as? Double) ?? 0
        case "pause": paused = (value as? Bool) ?? false
        case "paused-for-cache": buffering = (value as? Bool) ?? false
        case "seeking": seeking = (value as? Bool) ?? false
        case "eof-reached":
            // With keep-open, reaching the end pauses on the last frame instead of ending the file.
            if (value as? Bool) == true, loaded, !ended {
                ended = true
                onEnd?(.finished, nil)
            }
        case "demuxer-cache-duration": cachedSeconds = (value as? Double) ?? 0
        case "cache-buffering-state": bufferPercent = Int((value as? Int64) ?? 0)
        case "track-list": tracks = MPVPlayer.parseTracks(value)
        case "sub-delay": subtitleDelay = (value as? Double) ?? 0
        case "audio-delay": audioDelay = (value as? Double) ?? 0
        case "speed": speed = (value as? Double) ?? 1
        case "volume": volume = (value as? Double) ?? 100
        case "mute": muted = (value as? Bool) ?? false
        case "video-params/w":
            videoWidth = Double((value as? Int64) ?? 0)
            updateVideoSize()
        case "video-params/h":
            videoHeight = Double((value as? Int64) ?? 0)
            updateVideoSize()
        case "video-params/gamma":
            let gamma = (value as? String)?.lowercased() ?? ""
            isHDR = gamma == "pq" || gamma == "hlg"
        case "video-params/sig-peak":
            if let peak = value as? Double, peak > 1 { isHDR = true }
        case "video-codec": videoCodec = value as? String
        case "hwdec-current": hardwareDecoder = value as? String
        case "container-fps": frameRate = value as? Double
        case "media-title":
            let text = (value as? String) ?? ""
            mediaTitle = text
            lyricTask?.cancel()
            let lowered = text.lowercased()
            guard lowered.contains("radio") || lowered.contains(" fm") || lowered.contains("music"),
                  ExtraSettings.current.radioLyrics else {
                lyrics = nil
                return
            }
            lyricTask = Task { [weak self] in
                let words = await RadioLyrics.find(song: text)
                let line = words?.replacingOccurrences(of: "\n", with: " ")
                await MainActor.run {
                    guard !Task.isCancelled else { return }
                    self?.lyrics = line
                }
            }
        default: break
        }
    }

    private var videoWidth = 0.0
    private var videoHeight = 0.0

    private func updateVideoSize() {
        videoSize = videoWidth > 0 && videoHeight > 0 ? CGSize(width: videoWidth, height: videoHeight) : nil
    }

    static func parseTracks(_ value: Any?) -> [MPVTrack] {
        guard let list = value as? [Any] else { return [] }
        return list.compactMap { raw -> MPVTrack? in
            guard let map = raw as? [String: Any], let id = map["id"] as? Int64, let type = map["type"] as? String else { return nil }
            var channels: String? = nil
            if let count = map["demux-channel-count"] as? Int64 {
                switch count {
                case 1: channels = "mono"
                case 2: channels = "stereo"
                case 6: channels = "5.1"
                case 8: channels = "7.1"
                default: channels = "\(count) ch"
                }
            }
            return MPVTrack(
                id: Int(id),
                type: type,
                title: map["title"] as? String,
                language: map["lang"] as? String,
                codec: map["codec"] as? String,
                isDefault: (map["default"] as? Bool) ?? false,
                isForced: (map["forced"] as? Bool) ?? false,
                isExternal: (map["external"] as? Bool) ?? false,
                selected: (map["selected"] as? Bool) ?? false,
                channels: type == "audio" ? channels : nil
            )
        }
    }
}

// MARK: - OpenGL renderer

private func mpvGetProcAddress(_ context: UnsafeMutableRawPointer?, _ name: UnsafePointer<CChar>?) -> UnsafeMutableRawPointer? {
    guard let name else { return nil }
    let symbol = CFStringCreateWithCString(kCFAllocatorDefault, name, CFStringBuiltInEncodings.ASCII.rawValue)
    let bundle = CFBundleGetBundleWithIdentifier("com.apple.opengl" as CFString)
    return CFBundleGetFunctionPointerForName(bundle, symbol)
}

private func mpvRenderUpdate(_ context: UnsafeMutableRawPointer?) {
    guard let context else { return }
    let renderer = Unmanaged<MPVGLRenderer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async { renderer.updated() }
}

/// One player's OpenGL context and mpv render context. A view's MPVGLLayer draws with it; when no
/// view is showing the player, new frames are consumed without drawing so playback keeps going.
final class MPVGLRenderer {
    let pixelFormat: CGLPixelFormatObj
    let context: CGLContextObj
    private(set) var renderContext: OpaquePointer?
    /// The layer currently showing this player (set by MPVVideoView).
    weak var layer: MPVGLLayer?
    private var retained: Unmanaged<MPVGLRenderer>?

    /// Frames drawn on screen by all players (the GitHub tour checks video really reaches the screen).
    static var framesDrawn = 0
    /// Diagnostics for the tour: update calls, new-frame signals, displays asked for, frames skipped.
    static var updates = 0
    static var frameSignals = 0
    static var displays = 0
    static var skips = 0
    static var draws = 0

    /// The OpenGL renderer's name, and whether it's a GPU (false for Apple's software renderer,
    /// which virtual Macs and some broken setups fall back to).
    let rendererName: String
    let accelerated: Bool

    init?(core: MPVCore) {
        guard let handle = core.handle, let made = MPVGLRenderer.makeContext() else { return nil }
        let format = made.0
        let context = made.1
        pixelFormat = format
        self.context = context
        CGLSetCurrentContext(context)
        defer { CGLSetCurrentContext(nil) }
        let name = glGetString(GLenum(GL_RENDERER)).map { String(cString: $0) } ?? "unknown"
        rendererName = name
        accelerated = !name.lowercased().contains("software")
        var initParams = mpv_opengl_init_params(get_proc_address: mpvGetProcAddress, get_proc_address_ctx: nil)
        let apiType = strdup(MPV_RENDER_API_TYPE_OPENGL)
        defer { free(apiType) }
        var created: OpaquePointer?
        withUnsafeMutablePointer(to: &initParams) { initPointer in
            var params = [
                mpv_render_param(type: MPV_RENDER_PARAM_API_TYPE, data: UnsafeMutableRawPointer(apiType)),
                mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, data: UnsafeMutableRawPointer(initPointer)),
                mpv_render_param(),
            ]
            _ = params.withUnsafeMutableBufferPointer { buffer in
                mpv_render_context_create(&created, handle, buffer.baseAddress)
            }
        }
        guard let created else {
            CGLReleaseContext(context)
            CGLReleasePixelFormat(format)
            return nil
        }
        renderContext = created
        let retained = Unmanaged.passRetained(self)
        self.retained = retained
        mpv_render_context_set_update_callback(created, mpvRenderUpdate, retained.toOpaque())
        // Also ask mpv for new frames 60 times a second: its "new frame" notification alone can
        // go quiet, and asking is cheap (this is how the player self-test drives it).
        let timer = Timer(timeInterval: 1.0 / 60.0, repeats: true) { [weak self] _ in self?.updated() }
        RunLoop.main.add(timer, forMode: .common)
        pollTimer = timer
    }

    private var pollTimer: Timer? = nil

    private static func makeContext() -> (CGLPixelFormatObj, CGLContextObj)? {
        let attempts: [[CGLPixelFormatAttribute]] = [
            [kCGLPFAOpenGLProfile, CGLPixelFormatAttribute(UInt32(kCGLOGLPVersion_3_2_Core.rawValue)),
             kCGLPFAAccelerated, kCGLPFADoubleBuffer, kCGLPFAAllowOfflineRenderers, CGLPixelFormatAttribute(0)],
            [kCGLPFAOpenGLProfile, CGLPixelFormatAttribute(UInt32(kCGLOGLPVersion_3_2_Core.rawValue)),
             kCGLPFADoubleBuffer, kCGLPFAAllowOfflineRenderers, CGLPixelFormatAttribute(0)],
            [kCGLPFADoubleBuffer, kCGLPFAAllowOfflineRenderers, CGLPixelFormatAttribute(0)],
        ]
        for attributes in attempts {
            var format: CGLPixelFormatObj?
            var count: GLint = 0
            guard CGLChoosePixelFormat(attributes, &format, &count) == kCGLNoError, let format else { continue }
            var context: CGLContextObj?
            guard CGLCreateContext(format, nil, &context) == kCGLNoError, let context else {
                CGLReleasePixelFormat(format)
                continue
            }
            var swapInterval: GLint = 1
            CGLSetParameter(context, kCGLCPSwapInterval, &swapInterval)
            return (format, context)
        }
        return nil
    }

    /// mpv has a new frame (main thread).
    func updated() {
        guard let renderContext else { return }
        MPVGLRenderer.updates += 1
        let flags = mpv_render_context_update(renderContext)
        guard flags & UInt64(MPV_RENDER_UPDATE_FRAME.rawValue) != 0 else { return }
        MPVGLRenderer.frameSignals += 1
        if let layer, layer.superlayer != nil {
            // Drawn straight away (as IINA does): waiting for Core Animation's next pass can
            // stall mpv, which waits for each frame to be shown.
            MPVGLRenderer.displays += 1
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            layer.display()
            CATransaction.commit()
            CATransaction.flush()
        } else {
            MPVGLRenderer.skips += 1
            skipFrame()
        }
    }

    static var diagnostics: String {
        "updates \(updates), new frames \(frameSignals), displays \(displays), layer draws \(draws), drawn \(framesDrawn), skipped \(skips)"
    }

    /// Draws the current frame into the framebuffer that's bound (called by the layer).
    func render(framebuffer: Int32, width: Int32, height: Int32) {
        guard let renderContext else { return }
        var fbo = mpv_opengl_fbo(fbo: framebuffer, w: width, h: height, internal_format: 0)
        var flip: Int32 = 1
        withUnsafeMutablePointer(to: &fbo) { fboPointer in
            withUnsafeMutablePointer(to: &flip) { flipPointer in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(fboPointer)),
                    mpv_render_param(type: MPV_RENDER_PARAM_FLIP_Y, data: UnsafeMutableRawPointer(flipPointer)),
                    mpv_render_param(),
                ]
                _ = params.withUnsafeMutableBufferPointer { buffer in
                    mpv_render_context_render(renderContext, buffer.baseAddress)
                }
            }
        }
        glFlush()
        mpv_render_context_report_swap(renderContext)
        MPVGLRenderer.framesDrawn += 1
    }

    /// Lets mpv move on to the next frame without drawing (no view is showing this player).
    private func skipFrame() {
        guard let renderContext else { return }
        CGLSetCurrentContext(context)
        defer { CGLSetCurrentContext(nil) }
        var skip: Int32 = 1
        var fbo = mpv_opengl_fbo(fbo: 0, w: 16, h: 16, internal_format: 0)
        withUnsafeMutablePointer(to: &fbo) { fboPointer in
            withUnsafeMutablePointer(to: &skip) { skipPointer in
                var params = [
                    mpv_render_param(type: MPV_RENDER_PARAM_OPENGL_FBO, data: UnsafeMutableRawPointer(fboPointer)),
                    mpv_render_param(type: MPV_RENDER_PARAM_SKIP_RENDERING, data: UnsafeMutableRawPointer(skipPointer)),
                    mpv_render_param(),
                ]
                _ = params.withUnsafeMutableBufferPointer { buffer in
                    mpv_render_context_render(renderContext, buffer.baseAddress)
                }
            }
        }
        mpv_render_context_report_swap(renderContext)
    }

    /// Frees the render context; must happen before mpv itself is destroyed. Safe to call twice.
    func destroy() {
        pollTimer?.invalidate()
        pollTimer = nil
        guard let renderContext else { return }
        CGLSetCurrentContext(context)
        mpv_render_context_set_update_callback(renderContext, nil, nil)
        mpv_render_context_free(renderContext)
        CGLSetCurrentContext(nil)
        self.renderContext = nil
        layer = nil
        // Released on the next turn: an update may already be queued for this renderer.
        if let retained {
            self.retained = nil
            DispatchQueue.main.async { retained.release() }
        }
    }

    deinit {
        CGLReleaseContext(context)
        CGLReleasePixelFormat(pixelFormat)
    }
}

/// Shows one player's OpenGL renderer in a view.
final class MPVGLLayer: CAOpenGLLayer {
    private(set) var renderer: MPVGLRenderer?

    init(renderer: MPVGLRenderer) {
        self.renderer = renderer
        super.init()
        isOpaque = true
        isAsynchronous = false
        needsDisplayOnBoundsChange = true
        backgroundColor = NSColor.black.cgColor
    }

    override init() {
        super.init()
    }

    /// Core Animation copies layers for presentation; the copy never renders video.
    override init(layer: Any) {
        super.init(layer: layer)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
    }

    // Core Animation releases what these "copy" methods return, so each hands out a retain.
    override func copyCGLPixelFormat(forDisplayMask mask: UInt32) -> CGLPixelFormatObj {
        if let renderer { return CGLRetainPixelFormat(renderer.pixelFormat) }
        return super.copyCGLPixelFormat(forDisplayMask: mask)
    }

    override func copyCGLContext(forPixelFormat pf: CGLPixelFormatObj) -> CGLContextObj {
        if let renderer { return CGLRetainContext(renderer.context) }
        return super.copyCGLContext(forPixelFormat: pf)
    }

    override func canDraw(inCGLContext ctx: CGLContextObj, pixelFormat pf: CGLPixelFormatObj,
                          forLayerTime t: CFTimeInterval, displayTime ts: UnsafePointer<CVTimeStamp>?) -> Bool {
        true
    }

    override func draw(inCGLContext ctx: CGLContextObj, pixelFormat pf: CGLPixelFormatObj,
                       forLayerTime t: CFTimeInterval, displayTime ts: UnsafePointer<CVTimeStamp>?) {
        MPVGLRenderer.draws += 1
        var viewport: [GLint] = [0, 0, 0, 0]
        glGetIntegerv(GLenum(GL_VIEWPORT), &viewport)
        guard let renderer, renderer.renderContext != nil else {
            glClearColor(0, 0, 0, 1)
            glClear(GLbitfield(GL_COLOR_BUFFER_BIT))
            glFlush()
            return
        }
        var framebuffer: GLint = 0
        glGetIntegerv(GLenum(GL_DRAW_FRAMEBUFFER_BINDING), &framebuffer)
        renderer.render(framebuffer: Int32(framebuffer), width: Int32(viewport[2]), height: Int32(viewport[3]))
    }
}

/// A CAMetalLayer for mpv's gpu-next renderer. MoltenVK briefly asks for a 1×1 drawable while
/// finishing; ignoring that keeps the picture from blinking.
final class MPVMetalLayer: CAMetalLayer {
    override init() {
        super.init()
        commonInit()
    }

    override init(layer: Any) {
        super.init(layer: layer)
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        commonInit()
    }

    private func commonInit() {
        backgroundColor = NSColor.black.cgColor
        framebufferOnly = true
        wantsExtendedDynamicRangeContent = true
        contentsGravity = .resizeAspect
    }

    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue }
        }
    }
}

/// Shows one MPVPlayer's picture. OpenGL players get a layer here that draws with their renderer;
/// Metal players bring their own layer, which moves here (so the mini player and full screen share
/// one picture).
final class MPVVideoView: NSView {
    private var glLayer: MPVGLLayer? = nil
    private(set) weak var player: MPVPlayer?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    private func setup() {
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.masksToBounds = true
    }

    override var isOpaque: Bool { true }
    override var isFlipped: Bool { true }

    // SwiftUI sizes this view by setting its frame; keep the video layers the same size.
    override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        updateLayerFrames()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        updateLayerFrames()
    }

    override func layout() {
        super.layout()
        updateLayerFrames()
    }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        updateLayerFrames()
    }

    private func updateLayerFrames() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let scale = window?.backingScaleFactor ?? 2
        if let glLayer {
            glLayer.frame = bounds
            glLayer.contentsScale = scale
        }
        if let metal = player?.metalLayer, metal.superlayer === layer {
            metal.frame = bounds
            metal.contentsScale = scale
            metal.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        }
        CATransaction.commit()
        glLayer?.setNeedsDisplay()
    }

    func show(_ player: MPVPlayer?) {
        guard self.player !== player else { return }
        clear()
        guard let player else { return }
        self.player = player
        if let metal = player.metalLayer {
            metal.removeFromSuperlayer()
            layer?.addSublayer(metal)
        } else if let renderer = player.gl {
            let created = MPVGLLayer(renderer: renderer)
            created.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
            layer?.addSublayer(created)
            glLayer = created
            renderer.layer = created
        }
        updateLayerFrames()
    }

    func clear() {
        if let player, let metal = player.metalLayer, metal.superlayer === layer {
            metal.removeFromSuperlayer()
        }
        if let glLayer {
            if glLayer.renderer?.layer === glLayer { glLayer.renderer?.layer = nil }
            glLayer.removeFromSuperlayer()
        }
        glLayer = nil
        player = nil
    }
}
