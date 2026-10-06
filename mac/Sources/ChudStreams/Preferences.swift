import AppKit
import Foundation

// Playback settings, applied to the built-in player (mpv) when a stream starts. Changes that mpv
// can take mid-stream (subtitle look, audio delay, speed) are applied straight away.

enum PlayerEngine: String, Codable, CaseIterable, Identifiable {
    case builtIn, apple, vlc
    var id: String { rawValue }
    var label: String {
        switch self {
        case .builtIn: return "Built-in player (plays everything)"
        case .apple: return "Apple player (MP4 and HLS only)"
        case .vlc: return "VLC (opens outside the app)"
        }
    }
}

enum VideoRenderer: String, Codable, CaseIterable, Identifiable {
    case auto, standard, advanced
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Automatic (Metal on HDR screens, OpenGL otherwise)"
        case .standard: return "Standard (OpenGL)"
        case .advanced: return "Advanced (Metal): HDR output and Dolby Vision"
        }
    }

    /// Whether this setting means the Metal renderer on the current screen.
    var usesMetal: Bool {
        switch self {
        case .advanced: return true
        case .standard: return false
        case .auto:
            let headroom = NSScreen.main?.maximumPotentialExtendedDynamicRangeColorComponentValue ?? 1
            return headroom > 1.05
        }
    }
}

enum ToneMapping: String, Codable, CaseIterable, Identifiable {
    case auto, bt2390, hable, mobius, reinhard, clip
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Automatic"
        case .bt2390: return "BT.2390 (accurate)"
        case .hable: return "Hable (filmic)"
        case .mobius: return "Mobius (keeps contrast)"
        case .reinhard: return "Reinhard (soft)"
        case .clip: return "Clip (no tone mapping)"
        }
    }
}

enum DolbyVisionMode: String, Codable, CaseIterable, Identifiable {
    case auto, baseLayer
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Apply Dolby Vision metadata"
        case .baseLayer: return "Ignore it (HDR10 / SDR base layer)"
        }
    }
}

enum SubtitleMode: String, Codable, CaseIterable, Identifiable {
    case auto, forcedOnly, always, off
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Automatic (forced, or when the audio isn't your language)"
        case .forcedOnly: return "Forced subtitles only"
        case .always: return "Always on"
        case .off: return "Off"
        }
    }
}

enum UserAgentPreset: String, Codable, CaseIterable, Identifiable {
    case app, vlc, tivimate, smarters, custom
    var id: String { rawValue }
    var label: String {
        switch self {
        case .app: return "Chud Supreme"
        case .vlc: return "VLC"
        case .tivimate: return "TiviMate"
        case .smarters: return "IPTV Smarters"
        case .custom: return "Custom"
        }
    }
    var value: String {
        switch self {
        case .app: return "ChudSupreme/1.0 (Macintosh; Intel Mac OS X 13)"
        case .vlc: return "VLC/3.0.21 LibVLC/3.0.21"
        case .tivimate: return "TiviMate/5.1.6 (Android 11)"
        case .smarters: return "IPTVSmartersPro"
        case .custom: return ""
        }
    }
}

struct PlaybackSettings: Codable, Equatable {
    // Player
    var engine: PlayerEngine = .builtIn
    var renderer: VideoRenderer = .auto
    var hardwareDecoding = true
    var videoSyncSmooth = false
    var interpolation = false
    var deband = false
    // HDR and Dolby Vision
    var toneMapping: ToneMapping = .auto
    var hdrPeakDetection = true
    var dolbyVision: DolbyVisionMode = .auto
    // Network
    var bufferSeconds = 30
    var networkTimeout = 60
    var reconnect = true
    var userAgent: UserAgentPreset = .app
    var customUserAgent = ""
    // Audio
    var audioLanguages = "en,eng"
    var passthrough = false
    var exclusiveAudio = false
    var normaliseVolume = false
    var stereoDownmix = false
    var defaultVolume = 100.0
    // Subtitles
    var subtitleMode: SubtitleMode = .auto
    var subtitleLanguages = "en,eng"
    var styledSubtitles = true
    var subtitleScale = 1.0
    var subtitlePosition = 100.0
    var subtitleBackground = false
    var subtitleFont = "Helvetica Neue"
    // Behaviour
    var resume = true
    var autoplayNext = true
    var skipBack = 10
    var skipForward = 30
    var defaultSpeed = 1.0
    var miniPlayerOnClose = true
    var liveRetry = true

    static let key = "playback.settings.v2"

    /// The saved settings (read fresh each time: they're small).
    static var current: PlaybackSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode(PlaybackSettings.self, from: data) else { return PlaybackSettings() }
        return saved
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: PlaybackSettings.key) }
    }

    var userAgentString: String {
        if userAgent == .custom {
            let trimmed = customUserAgent.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? UserAgentPreset.app.value : trimmed
        }
        return userAgent.value
    }

    /// mpv options for a new player. `forTile` trims them for small multiview tiles.
    func mpvOptions(forTile: Bool = false) -> [(String, String)] {
        var options: [(String, String)] = [
            ("vo", renderer.usesMetal && !forTile ? "gpu-next" : "libmpv"),
            ("hwdec", hardwareDecoding ? "videotoolbox" : "no"),
            // If VideoToolbox cannot take a codec, decode it in software instead of showing nothing.
            ("vd-lavc-software-fallback", "yes"),
            ("keep-open", "yes"),
            ("idle", "yes"),
            ("terminal", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("osc", "no"),
            ("osd-level", "0"),
            ("user-agent", userAgentString),
            ("network-timeout", String(networkTimeout)),
            ("cache", "yes"),
            ("cache-secs", String(bufferSeconds)),
            ("demuxer-readahead-secs", String(min(bufferSeconds, 20))),
            // Capped so four live tiles plus a 4K film cannot eat the 8 GB machine.
            ("demuxer-max-bytes", forTile ? "24MiB" : "96MiB"),
            ("demuxer-max-back-bytes", forTile ? "8MiB" : "32MiB"),
            // The film or channel you opened keeps the highest rung. Tiles stay lighter.
            ("hls-bitrate", forTile ? "2500000" : "max"),
            ("volume", String(Int(defaultVolume))),
            ("volume-max", "130"),
            ("alang", audioLanguages),
            ("slang", subtitleLanguages),
            ("sub-auto", "fuzzy"),
            ("sub-font", subtitleFont),
            ("sub-scale", String(format: "%.2f", subtitleScale)),
            ("sub-pos", String(Int(subtitlePosition))),
            ("sub-ass-override", styledSubtitles ? "scale" : "force"),
            ("sub-border-style", subtitleBackground ? "background-box" : "outline-and-shadow"),
            ("screenshot-directory", "~/Pictures"),
            ("tone-mapping", toneMapping == .auto ? "auto" : toneMapping.rawValue),
            ("hdr-compute-peak", hdrPeakDetection ? "auto" : "no"),
            ("target-peak", "auto"),
            // 4K down to a Retina laptop: smooth scalers, not the heaviest ones.
            ("scale", forTile ? "bilinear" : "spline36"),
            ("dscale", forTile ? "bilinear" : "mitchell"),
            ("cscale", forTile ? "bilinear" : "spline36"),
            ("sigmoid-upscaling", "no"),
            ("deband", deband ? "yes" : "no"),
            ("speed", String(format: "%.2f", defaultSpeed)),
        ]
        if reconnect {
            options.append(("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=5"))
        }
        switch subtitleMode {
        case .auto:
            // Forced tracks (signs, foreign dialogue) always; full subtitles only when the audio
            // isn't in one of the viewer's languages.
            options.append(("sid", "auto"))
            options.append(("subs-fallback-forced", "always"))
            options.append(("subs-with-matching-audio", "forced"))
        case .forcedOnly:
            options.append(("sid", "auto"))
            options.append(("subs-fallback", "no"))
            options.append(("subs-fallback-forced", "always"))
            options.append(("subs-with-matching-audio", "forced"))
        case .always:
            options.append(("sid", "auto"))
            options.append(("subs-fallback", "yes"))
            options.append(("subs-with-matching-audio", "yes"))
        case .off:
            options.append(("sid", "no"))
        }
        if videoSyncSmooth || interpolation {
            options.append(("video-sync", "display-resample"))
            if interpolation {
                options.append(("interpolation", "yes"))
                options.append(("tscale", "oversample"))
            }
        }
        if dolbyVision == .baseLayer {
            options.append(("vf", "format=dolbyvision=no"))
        }
        if passthrough {
            // AC3, E-AC3 (including Atmos), DTS, DTS-HD and TrueHD Atmos. Speakers still decode
            // when this is off, which is the default on a MacBook.
            options.append(("audio-spdif", "ac3,eac3,dts,dts-hd,truehd"))
        }
        if exclusiveAudio { options.append(("audio-exclusive", "yes")) }
        if stereoDownmix { options.append(("audio-channels", "stereo")) } else { options.append(("audio-channels", "auto-safe")) }
        if normaliseVolume { options.append(("af", "dynaudnorm=f=250:g=15")) }
        if !forTile, let shaders = Anime4K.chain() {
            options.append(("glsl-shaders", shaders))
        }
        if forTile {
            options.append(("vd-lavc-threads", "1"))
        }
        if renderer.usesMetal && !forTile {
            options.append(("gpu-api", "vulkan"))
            options.append(("gpu-context", "moltenvk"))
            options.append(("target-colorspace-hint", "yes"))
            options.append(("tone-mapping-mode", "auto"))
            if hardwareDecoding { options.append(("hwdec", "videotoolbox")) }
        }
        return options
    }
}
