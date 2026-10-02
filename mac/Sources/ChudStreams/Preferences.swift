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
        case .app: return "CHUD STREAMS"
        case .vlc: return "VLC"
        case .tivimate: return "TiviMate"
        case .smarters: return "IPTV Smarters"
        case .custom: return "Custom"
        }
    }
    var value: String {
        switch self {
        case .app: return "CHUDSTREAMS/2.0 (Macintosh)"
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
            ("hwdec", hardwareDecoding ? "auto-safe" : "no"),
            // If the hardware decoder gives up on a stream, switch to software straight away.
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
            ("demuxer-readahead-secs", String(min(bufferSeconds, 60))),
            ("demuxer-max-bytes", forTile ? "48MiB" : "\(max(64, bufferSeconds * 4))MiB"),
            ("demuxer-max-back-bytes", forTile ? "16MiB" : "64MiB"),
            ("hls-bitrate", "max"),
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
            options.append(("audio-spdif", "ac3,eac3,dts,dts-hd,truehd"))
        }
        if exclusiveAudio { options.append(("audio-exclusive", "yes")) }
        if stereoDownmix { options.append(("audio-channels", "stereo")) } else { options.append(("audio-channels", "auto-safe")) }
        if normaliseVolume { options.append(("af", "dynaudnorm=f=250:g=15")) }
        if forTile {
            options.append(("vd-lavc-threads", "2"))
            options.append(("hwdec", hardwareDecoding ? "auto-safe" : "no"))
        }
        if renderer.usesMetal && !forTile {
            options.append(("gpu-api", "vulkan"))
            options.append(("gpu-context", "moltenvk"))
            options.append(("target-colorspace-hint", "yes"))
            if hardwareDecoding { options.append(("hwdec", "videotoolbox")) }
        }
        return options
    }
}
