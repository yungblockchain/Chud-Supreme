import Foundation

// Pieces from the Fire TV build that the Mac app did not ship yet: Anime4K, the owner's
// extra keys, weather, intro skip, and radio lyrics. macOS 13 APIs only.

struct ExtraSettings: Codable, Equatable {
    var anime4k = true
    var radioLyrics = true
    var aniskip = true

    static let key = "playback.extra.v1"

    static var current: ExtraSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              let saved = try? JSONDecoder().decode(ExtraSettings.self, from: data) else { return ExtraSettings() }
        return saved
    }

    func save() {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: ExtraSettings.key)
        }
    }
}

enum Anime4K {
    private static let files = [
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
    ]

    /// Colon-separated shader paths for mpv, or nil when the toggle is off or the files are missing.
    static func chain() -> String? {
        guard ExtraSettings.current.anime4k else { return nil }
        let folder = Bundle.main.resourceURL?.appendingPathComponent("shaders")
        let paths = files.compactMap { name -> String? in
            guard let folder else { return nil }
            let url = folder.appendingPathComponent(name)
            return FileManager.default.fileExists(atPath: url.path) ? url.path : nil
        }
        return paths.count == files.count ? paths.joined(separator: ":") : nil
    }
}

enum WeatherLine {
    static func summary() async -> String? {
        guard let url = URL(string: "https://wttr.in/?format=3") else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        request.setValue("curl/8.0", forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              text.count > 2, text.count < 80 else { return nil }
        return text
    }
}

struct IntroSkip: Equatable {
    var start: Double
    var end: Double
    var kind: String
}

enum AniSkip {
    static func find(title: String) async -> IntroSkip? {
        guard ExtraSettings.current.aniskip else { return nil }
        let episode = episodeNumber(in: title)
        let query = title.replacingOccurrences(of: #"\[[^\]]*\]|\((19|20)\d{2}\)"#, with: "", options: .regularExpression)
        guard query.count > 1,
              let searchURL = URL(string: "https://api.jikan.moe/v4/anime?limit=1&q=" + query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!) else { return nil }
        guard let (data, _) = try? await URLSession.shared.data(from: searchURL),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = root["data"] as? [[String: Any]],
              let mal = rows.first?["mal_id"] as? Int else { return nil }
        guard let skipURL = URL(string: "https://api.aniskip.com/v2/skip-times/\(mal)/\(episode)?types=op&types=ed"),
              let (skipData, _) = try? await URLSession.shared.data(from: skipURL),
              let skipRoot = try? JSONSerialization.jsonObject(with: skipData) as? [String: Any],
              let results = skipRoot["results"] as? [[String: Any]] else { return nil }
        for row in results {
            guard let interval = row["interval"] as? [String: Any],
                  let start = interval["startTime"] as? Double,
                  let end = interval["endTime"] as? Double,
                  end > start else { continue }
            let kind = (row["skipType"] as? String) ?? "op"
            if kind == "op" || kind == "ed" { return IntroSkip(start: start, end: end, kind: kind) }
        }
        return nil
    }

    private static func episodeNumber(in title: String) -> Int {
        guard let match = title.range(of: #"E(?:pisode)?\s*(\d+)"#, options: .regularExpression) else { return 1 }
        let slice = title[match]
        let digits = slice.filter(\.isNumber)
        return Int(digits) ?? 1
    }
}

enum RadioLyrics {
    static func find(song: String) async -> String? {
        guard ExtraSettings.current.radioLyrics, song.count > 3 else { return nil }
        let artist: String
        let track: String
        if let range = song.range(of: " - ") {
            artist = String(song[..<range.lowerBound])
            track = String(song[range.upperBound...])
        } else {
            artist = ""
            track = song
        }
        let urlString: String
        if !artist.isEmpty && !track.isEmpty {
            urlString = "https://lrclib.net/api/search?track_name=\(track.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? track)&artist_name=\(artist.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? artist)"
        } else {
            urlString = "https://lrclib.net/api/search?q=\(song.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? song)"
        }
        guard let url = URL(string: urlString) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("Chud Supreme (macOS; https://github.com/yungblockchain/Chud-Supreme)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 8
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]],
              let text = rows.first?["plainLyrics"] as? String,
              !text.isEmpty else { return nil }
        return text
    }
}
