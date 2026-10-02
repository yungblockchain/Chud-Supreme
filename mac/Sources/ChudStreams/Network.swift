import Compression
import Foundation

// Downloading and decoding big catalogues: an Xtream account with 50,000 channels and 130,000
// films sends tens of megabytes of JSON, and an M3U playlist of that size is similar. These run
// off the main thread and never hold more than one copy of the raw data.

/// A single large GET with generous timeouts and progress reports.
enum LargeDownload {
    enum Failure: Error {
        case status(Int)
    }

    /// Waits up to three minutes for the next bytes, and up to an hour in total.
    static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 180
        configuration.timeoutIntervalForResource = 3600
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: configuration)
    }()

    static func fetch(_ url: URL, headers: [String: String] = [:], progress: (@Sendable (Int64) -> Void)? = nil) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 180
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let delegate = DownloadProgressDelegate(progress: progress)
        let (fileURL, response) = try await session.download(for: request, delegate: delegate)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200...299).contains(code) else { throw Failure.status(code) }
        let data = try Data(contentsOf: fileURL, options: .mappedIfSafe)
        return Gzip.isCompressed(data) ? (Gzip.decompress(data) ?? data) : data
    }
}

private final class DownloadProgressDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let progress: (@Sendable (Int64) -> Void)?
    private var lastReport = Date.distantPast

    init(progress: (@Sendable (Int64) -> Void)?) {
        self.progress = progress
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let now = Date()
        guard now.timeIntervalSince(lastReport) > 0.25 else { return }
        lastReport = now
        progress?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
}

/// Gzip (.gz) decoding with Apple's Compression framework (raw DEFLATE inside a gzip wrapper).
enum Gzip {
    static func isCompressed(_ data: Data) -> Bool {
        data.count > 18 && data[data.startIndex] == 0x1F && data[data.startIndex + 1] == 0x8B
    }

    static func decompress(_ data: Data) -> Data? {
        guard isCompressed(data) else { return data }
        let bytes = [UInt8](data)
        let flags = bytes[3]
        var offset = 10
        if flags & 0x04 != 0 {  // extra field
            guard offset + 2 <= bytes.count else { return nil }
            offset += 2 + Int(bytes[offset]) + Int(bytes[offset + 1]) << 8
        }
        if flags & 0x08 != 0 {  // file name
            while offset < bytes.count && bytes[offset] != 0 { offset += 1 }
            offset += 1
        }
        if flags & 0x10 != 0 {  // comment
            while offset < bytes.count && bytes[offset] != 0 { offset += 1 }
            offset += 1
        }
        if flags & 0x02 != 0 { offset += 2 }  // header CRC
        guard offset < bytes.count - 8 else { return nil }
        let deflated = Array(bytes[offset..<(bytes.count - 8)])
        return inflate(deflated)
    }

    private static func inflate(_ input: [UInt8]) -> Data? {
        let chunk = 1 << 20
        var output = Data()
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        guard compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else { return nil }
        defer { compression_stream_destroy(stream) }
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunk)
        defer { buffer.deallocate() }
        return input.withUnsafeBufferPointer { source -> Data? in
            guard let base = source.baseAddress else { return nil }
            stream.pointee.src_ptr = base
            stream.pointee.src_size = input.count
            while true {
                stream.pointee.dst_ptr = buffer
                stream.pointee.dst_size = chunk
                let status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                let produced = chunk - stream.pointee.dst_size
                if produced > 0 { output.append(buffer, count: produced) }
                switch status {
                case COMPRESSION_STATUS_OK: continue
                case COMPRESSION_STATUS_END: return output
                default: return output.isEmpty ? nil : output
                }
            }
        }
    }
}

// MARK: - Lenient Xtream decoding

/// Any JSON key.
struct AnyCodingKey: CodingKey {
    let stringValue: String
    let intValue: Int? = nil
    init(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}

private extension KeyedDecodingContainer where Key == AnyCodingKey {
    /// A string, number or boolean as text; nil for missing, null, empty or "null".
    func text(_ name: String) -> String? {
        let key = AnyCodingKey(stringValue: name)
        guard contains(key) else { return nil }
        if let value = try? decode(String.self, forKey: key) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed == "null" ? nil : trimmed
        }
        if let value = try? decode(Int64.self, forKey: key) { return String(value) }
        if let value = try? decode(Double.self, forKey: key) { return String(value) }
        if let value = try? decode(Bool.self, forKey: key) { return value ? "1" : "0" }
        return nil
    }

    func integer(_ name: String) -> Int? {
        guard let text = text(name) else { return nil }
        if let value = Int(text) { return value }
        if let value = Double(text), value.isFinite, abs(value) < Double(Int.max) { return Int(value) }
        return nil
    }
}

/// One entry of get_live_streams / get_vod_streams / get_series. Never throws: a malformed entry
/// just comes out empty and is skipped.
private struct XtreamEntry: Decodable {
    var id: Int?
    var seriesId: Int?
    var name: String?
    var icon: String?
    var cover: String?
    var categoryId: String?
    var containerExtension: String?
    var rating: String?
    var epgId: String?
    var number: Int?
    var added: Double?
    var archive: Int?

    init(from decoder: Decoder) {
        guard let c = try? decoder.container(keyedBy: AnyCodingKey.self) else { return }
        id = c.integer("stream_id")
        seriesId = c.integer("series_id")
        name = c.text("name") ?? c.text("title")
        icon = c.text("stream_icon")
        cover = c.text("cover")
        categoryId = c.text("category_id")
        containerExtension = c.text("container_extension")
        rating = c.text("rating")
        epgId = c.text("epg_channel_id")
        number = c.integer("num")
        added = (c.text("added") ?? c.text("last_modified")).flatMap { Double($0) }
        archive = c.integer("tv_archive")
    }
}

/// Decodes a whole catalogue list.
enum XtreamCatalogDecoder {
    static func decode(_ data: Data, kind: ContentKind, sourceId: String) throws -> [MediaItem] {
        let entries: [XtreamEntry]
        do {
            entries = try JSONDecoder().decode([XtreamEntry].self, from: data)
        } catch {
            // An object instead of a list: an empty account ({} or {"user_info":…}) or an error page.
            if let object = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]), object is [String: Any] {
                return []
            }
            throw XtreamError.notXtream("the server")
        }
        var items: [MediaItem] = []
        items.reserveCapacity(entries.count)
        for entry in entries {
            let id = kind == .series ? entry.seriesId : entry.id
            guard let id, let name = entry.name else { continue }
            let rating = entry.rating.flatMap { $0 == "0" || $0 == "0.0" ? nil : $0 }
            items.append(MediaItem(
                kind: kind,
                streamId: id,
                name: name,
                icon: kind == .series ? (entry.cover ?? entry.icon) : entry.icon,
                categoryId: entry.categoryId,
                containerExtension: entry.containerExtension,
                rating: rating,
                sourceId: sourceId,
                epgId: entry.epgId,
                number: entry.number,
                added: entry.added,
                hasArchive: kind == .live ? (entry.archive ?? 0) > 0 : nil
            ))
        }
        return items
    }
}

// MARK: - M3U playlists

/// A parsed M3U playlist: live channels, and films (entries whose address or tags say VOD).
struct M3UPlaylist {
    var live: [MediaItem] = []
    var movies: [MediaItem] = []
    var liveGroups: [ContentCategory] = []
    var movieGroups: [ContentCategory] = []
    /// The guide address the playlist names in its header (url-tvg / x-tvg-url), if any.
    var guideURL: String? = nil
}

enum M3UParser {
    static func parse(_ data: Data, sourceId: String) -> M3UPlaylist {
        let text = String(decoding: data, as: UTF8.self)
        var playlist = M3UPlaylist()
        var liveOrder: [String] = []
        var movieOrder: [String] = []
        var liveSeen = Set<String>()
        var movieSeen = Set<String>()
        var pendingInfo: String? = nil
        var pendingGroup: String? = nil
        var usedIds = Set<Int>()
        var index = 0

        text.enumerateLines { rawLine, _ in
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { return }
            if line.hasPrefix("#EXTM3U") {
                playlist.guideURL = attribute("url-tvg", in: line) ?? attribute("x-tvg-url", in: line)
                if let first = playlist.guideURL?.split(separator: ",").first { playlist.guideURL = String(first) }
                return
            }
            if line.hasPrefix("#EXTINF") {
                pendingInfo = line
                return
            }
            if line.hasPrefix("#EXTGRP:") {
                pendingGroup = String(line.dropFirst(8)).trimmingCharacters(in: .whitespaces)
                return
            }
            if line.hasPrefix("#") { return }
            // A stream address.
            guard let info = pendingInfo else { return }
            pendingInfo = nil
            let name = displayName(from: info)
            let group = attribute("group-title", in: info) ?? pendingGroup ?? "Uncategorised"
            pendingGroup = nil
            let lower = line.lowercased()
            let path = URL(string: line)?.path.lowercased() ?? lower
            let isMovie = path.contains("/movie/") || path.contains("/series/")
                || [".mkv", ".mp4", ".avi", ".m4v", ".mov", ".wmv"].contains(where: { path.hasSuffix($0) })
            var id = stableId(line)
            while usedIds.contains(id) { id &+= 1 }
            usedIds.insert(id)
            index += 1
            let item = MediaItem(
                kind: isMovie ? .movie : .live,
                streamId: id,
                name: name,
                icon: attribute("tvg-logo", in: info) ?? attribute("logo", in: info),
                categoryId: group,
                containerExtension: isMovie ? URL(string: line)?.pathExtension.lowercased() : nil,
                rating: nil,
                sourceId: sourceId,
                url: line,
                epgId: attribute("tvg-id", in: info),
                number: attribute("tvg-chno", in: info).flatMap { Int($0) } ?? (isMovie ? nil : index),
                added: nil,
                hasArchive: (attribute("catchup", in: info) != nil || attribute("tvg-rec", in: info) != nil) ? true : nil
            )
            if isMovie {
                playlist.movies.append(item)
                if movieSeen.insert(group).inserted { movieOrder.append(group) }
            } else {
                playlist.live.append(item)
                if liveSeen.insert(group).inserted { liveOrder.append(group) }
            }
        }
        playlist.liveGroups = liveOrder.map { ContentCategory(id: $0, name: $0) }
        playlist.movieGroups = movieOrder.map { ContentCategory(id: $0, name: $0) }
        return playlist
    }

    /// The value of `name="value"` in an #EXTINF line.
    static func attribute(_ name: String, in line: String) -> String? {
        guard let start = line.range(of: name + "=\"", options: .caseInsensitive) else { return nil }
        let rest = line[start.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        let value = String(rest[..<end]).trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
    }

    /// The name after the last comma that's outside quotes.
    static func displayName(from line: String) -> String {
        var inQuotes = false
        var lastComma: String.Index? = nil
        var index = line.startIndex
        while index < line.endIndex {
            let character = line[index]
            if character == "\"" { inQuotes.toggle() }
            if character == "," && !inQuotes { lastComma = index }
            index = line.index(after: index)
        }
        if let lastComma {
            let name = line[line.index(after: lastComma)...].trimmingCharacters(in: .whitespaces)
            if !name.isEmpty { return name }
        }
        return attribute("tvg-name", in: line) ?? "Untitled"
    }

    /// A stable positive id for a stream address (FNV-1a), so favourites survive reloads.
    static func stableId(_ text: String) -> Int {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return Int(hash & 0x3FFF_FFFF_FFFF)
    }
}

// MARK: - XMLTV guides

/// Programmes from an XMLTV file, keyed by channel id, kept only for a window around now.
struct XMLTVGuide {
    var programmes: [String: [Programme]] = [:]
    /// Channel display names, lowercased, to channel id: matches channels without a tvg-id.
    var channelNames: [String: String] = [:]

    func listing(for item: MediaItem) -> [Programme] {
        if let id = item.epgId, let found = programmes[id] { return found }
        if let id = item.epgId?.lowercased(), let found = programmes[id] { return found }
        let name = item.name.lowercased()
        if let id = channelNames[name], let found = programmes[id] { return found }
        let clean = item.cleanName.lowercased()
        if let id = channelNames[clean], let found = programmes[id] { return found }
        return []
    }
}

final class XMLTVParser: NSObject, XMLParserDelegate {
    private var guide = XMLTVGuide()
    private let windowStart: Date
    private let windowEnd: Date
    private let wanted: Set<String>?
    private var element = ""
    private var text = ""
    private var channelId: String? = nil
    private var start: Date? = nil
    private var stop: Date? = nil
    private var title = ""
    private var desc = ""
    private var inProgramme = false
    private var inChannel = false

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMddHHmmss Z"
        return formatter
    }()

    private static let formatterNoZone: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMddHHmmss"
        return formatter
    }()

    /// Parses `data` (plain or gzipped XML), keeping programmes from 12 hours ago to 36 hours ahead.
    /// `channels` limits it to those channel ids (nil keeps every channel).
    static func parse(_ data: Data, channels: Set<String>?) -> XMLTVGuide {
        let xml = Gzip.isCompressed(data) ? (Gzip.decompress(data) ?? data) : data
        let now = Date()
        let parser = XMLTVParser(start: now.addingTimeInterval(-12 * 3600), end: now.addingTimeInterval(36 * 3600), wanted: channels)
        let xmlParser = XMLParser(data: xml)
        xmlParser.delegate = parser
        xmlParser.shouldResolveExternalEntities = false
        xmlParser.parse()
        for (key, list) in parser.guide.programmes {
            parser.guide.programmes[key] = list.sorted { $0.start < $1.start }
        }
        return parser.guide
    }

    private init(start: Date, end: Date, wanted: Set<String>?) {
        windowStart = start
        windowEnd = end
        self.wanted = wanted
    }

    static func date(_ value: String?) -> Date? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), value.count >= 14 else { return nil }
        if value.count > 14, let date = formatter.date(from: value) { return date }
        return formatterNoZone.date(from: String(value.prefix(14)))
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        element = elementName
        text = ""
        switch elementName {
        case "programme":
            let channel = attributeDict["channel"]
            if let wanted, let channel, !wanted.contains(channel) {
                inProgramme = false
                return
            }
            inProgramme = true
            channelId = channel
            start = XMLTVParser.date(attributeDict["start"])
            stop = XMLTVParser.date(attributeDict["stop"])
            title = ""
            desc = ""
        case "channel":
            inChannel = true
            channelId = attributeDict["id"]
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inProgramme || inChannel { text += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        switch elementName {
        case "title":
            if inProgramme && title.isEmpty { title = text.trimmingCharacters(in: .whitespacesAndNewlines) }
        case "desc":
            if inProgramme && desc.isEmpty { desc = text.trimmingCharacters(in: .whitespacesAndNewlines) }
        case "display-name":
            if inChannel, let channelId {
                let name = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if !name.isEmpty && guide.channelNames[name] == nil { guide.channelNames[name] = channelId }
            }
        case "channel":
            inChannel = false
        case "programme":
            defer { inProgramme = false }
            guard inProgramme, let channelId, let start, let stop, stop > start,
                  stop > windowStart, start < windowEnd else { return }
            let programme = Programme(
                title: title.isEmpty ? "Untitled" : title,
                description: desc,
                start: start,
                end: stop
            )
            guide.programmes[channelId, default: []].append(programme)
        default:
            break
        }
        text = ""
    }
}
