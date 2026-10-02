import AppKit
import Foundation
import SwiftUI

// Ask Claude: a chat that knows the viewer's own library. Claude gets tools to search the
// catalogue, browse categories, see what's on live TV now, read favourites, the library and
// continue watching, and start playback, so answers are about things that are actually there.
//
// It uses the viewer's own Anthropic API key (kept in the Keychain through Secrets, only ever
// sent to ServiceURL.claude). Requests go from this Mac straight to the Messages API.

// MARK: - Models, limits and messages

/// Models offered in the app, cheapest and fastest first.
enum ClaudeModelChoice: String, CaseIterable, Identifiable {
    case haiku = "claude-haiku-4-5-20251001"
    case sonnet = "claude-sonnet-5-5"
    case opus = "claude-opus-5-5"

    static let defaultsKey = "claude.model"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .haiku: return "Haiku 4.5"
        case .sonnet: return "Sonnet 5.5"
        case .opus: return "Opus 5.5"
        }
    }

    var detail: String {
        switch self {
        case .haiku: return "Fastest, and costs the least"
        case .sonnet: return "The best all-rounder"
        case .opus: return "Most capable, and costs the most"
        }
    }

    static var saved: ClaudeModelChoice {
        let raw = UserDefaults.standard.string(forKey: defaultsKey) ?? ""
        return ClaudeModelChoice(rawValue: raw) ?? .sonnet
    }
}

enum ClaudeLimits {
    static let toolRounds = 6
    static let historyMessages = 30
    static let cards = 8
    static let search = 25
    static let browse = 40
    static let whatsOn = 12
    static let listItems = 30
    static let categories = 150
    static let guideBatch = 4
    static let maxTokens = 1024
}

/// One line of the conversation as shown on screen, with the library items the reply mentions.
struct ClaudeMessage: Identifiable {
    enum Role {
        case user
        case assistant
    }

    let id = UUID()
    let role: Role
    let text: String
    var items: [MediaItem] = []
}

/// Why a request to Anthropic failed.
enum ClaudeFailure: Error {
    case noKey
    case rejected
    case noCredit
    case rateLimited
    case overloaded
    case notFound
    case offline
    case unreachable
    case insecure
    case timedOut
    case badRequest
    case badResponse
    case noReply
    case network(Int)
    case server(Int, String)

    func message(setup: Bool) -> String {
        switch self {
        case .noKey:
            return "Add your Anthropic API key to use Claude."
        case .rejected:
            return setup
                ? "Anthropic didn't accept that key. Check you copied all of it (it starts with sk-ant-) and that it hasn't been deleted."
                : "Anthropic no longer accepts your key. Remove it and add a new one from console.anthropic.com."
        case .noCredit:
            return "Your Anthropic account is out of credit. Add some at console.anthropic.com under Billing, then try again."
        case .rateLimited:
            return "Anthropic is limiting requests on your key right now. Wait a minute, then try again."
        case .overloaded:
            return "Claude is very busy right now. Try again in a moment."
        case .notFound:
            return setup
                ? "Anthropic answered with error 404. Check the app is up to date."
                : "Your key can't use this model. Pick another one from the model menu."
        case .offline:
            return "This Mac isn't connected to the internet. Check your Wi-Fi, then try again."
        case .unreachable:
            return "Couldn't reach Anthropic. Check your internet connection, or whether a firewall or VPN is blocking api.anthropic.com."
        case .insecure:
            return "Couldn't make a secure connection to Anthropic. Check the Mac's date and time, and any VPN or proxy."
        case .timedOut:
            return "Claude took too long to answer. Try again."
        case .badRequest:
            return "Something went wrong preparing that question. Start a new chat and try again."
        case .badResponse:
            return "Anthropic sent an answer the app couldn't read. Try again."
        case .noReply:
            return "Claude didn't answer that. Try asking another way."
        case .network(let code):
            return "Couldn't reach Anthropic (network error \(code)). Try again."
        case .server(let code, let detail):
            let tail = code == 400 ? " Starting a new chat usually fixes this." : ""
            if detail.isEmpty { return "Anthropic answered with error \(code).\(tail)" }
            return "Anthropic answered with error \(code): \(detail)\(tail)"
        }
    }
}

// MARK: - Messages API client

/// Minimal Anthropic API client. Works off the main thread; takes and returns raw JSON data.
enum ClaudeAPI {
    static let version = "2023-06-01"

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 90
        configuration.timeoutIntervalForResource = 120
        return URLSession(configuration: configuration)
    }()

    private static var base: String {
        var text = ServiceURL.claude.trimmingCharacters(in: .whitespacesAndNewlines)
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }

    /// Checks a key by listing one model. Throws ClaudeFailure if it doesn't work.
    static func checkKey(_ key: String) async throws {
        _ = try await send(path: "/v1/models?limit=1", method: "GET", key: key, body: nil)
    }

    /// One Messages API call. `body` is the JSON request.
    static func messages(key: String, body: Data) async throws -> Data {
        try await send(path: "/v1/messages", method: "POST", key: key, body: body)
    }

    private static func send(path: String, method: String, key: String, body: Data?) async throws -> Data {
        guard let url = URL(string: base + path) else { throw ClaudeFailure.badRequest }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 90
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue(version, forHTTPHeaderField: "anthropic-version")
        request.setValue("application/json", forHTTPHeaderField: "accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "content-type")
            request.httpBody = body
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            throw mapped(error)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw ClaudeFailure.network(-1)
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else {
            throw failure(status: code, data: data, key: key)
        }
        return data
    }

    private static func mapped(_ error: URLError) -> Error {
        switch error.code {
        case .cancelled:
            return CancellationError()
        case .timedOut:
            return ClaudeFailure.timedOut
        case .notConnectedToInternet, .networkConnectionLost:
            return ClaudeFailure.offline
        case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
            return ClaudeFailure.unreachable
        case .secureConnectionFailed:
            return ClaudeFailure.insecure
        default:
            return ClaudeFailure.network(error.code.rawValue)
        }
    }

    private static func failure(status code: Int, data: Data, key: String) -> ClaudeFailure {
        let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let error = root?["error"] as? [String: Any]
        let type = (error?["type"] as? String) ?? ""
        var detail = (error?["message"] as? String) ?? ""
        // The API never echoes the key, but make sure it can't reach the screen.
        if !key.isEmpty { detail = detail.replacingOccurrences(of: key, with: "…") }
        let searchable = detail.isEmpty ? (String(data: data.prefix(2000), encoding: .utf8) ?? "") : detail
        let mentionsCredit = searchable.lowercased().contains("credit")
        if code == 401 || code == 403 || type == "authentication_error" || type == "permission_error" { return .rejected }
        if type == "billing_error" || ((code == 400 || code == 402) && mentionsCredit) || code == 402 { return .noCredit }
        if code == 429 || type == "rate_limit_error" { return .rateLimited }
        if code == 529 || code == 503 || type == "overloaded_error" { return .overloaded }
        if code == 404 || type == "not_found_error" { return .notFound }
        return .server(code, String(detail.prefix(240)))
    }
}

// MARK: - Text and JSON helpers

enum ClaudeText {
    static let suggestions = [
        "What's good on right now?",
        "Pick me a film for tonight",
        "Find a series to binge",
        "Any live sport on?",
    ]

    static let noSource = "No source is set up. The viewer adds one in Settings."

    /// A user turn that is a typed question (not a list of tool results).
    static func isPlainUser(_ message: [String: Any]) -> Bool {
        (message["role"] as? String) == "user" && message["content"] is String
    }

    static func json(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }

    /// Keeps only the text and tool_use blocks, with only the fields the API needs back.
    static func cleanBlocks(_ content: [[String: Any]]) -> [[String: Any]] {
        var blocks: [[String: Any]] = []
        for block in content {
            let type = (block["type"] as? String) ?? ""
            if type == "text", let text = block["text"] as? String, !text.isEmpty {
                let clean: [String: Any] = ["type": "text", "text": text]
                blocks.append(clean)
            } else if type == "tool_use", let id = block["id"] as? String, let name = block["name"] as? String {
                let input = (block["input"] as? [String: Any]) ?? [:]
                let clean: [String: Any] = ["type": "tool_use", "id": id, "name": name, "input": input]
                blocks.append(clean)
            }
        }
        return blocks
    }

    /// The reply's text, with the markdown Claude sometimes adds taken out.
    static func replyText(_ blocks: [[String: Any]]) -> String {
        var parts: [String] = []
        for block in blocks where (block["type"] as? String) == "text" {
            if let text = block["text"] as? String { parts.append(text) }
        }
        let lines = parts.joined(separator: "\n").components(separatedBy: "\n").map { stripHeading($0) }
        return lines.joined(separator: "\n")
            .replacingOccurrences(of: "**", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func stripHeading(_ line: String) -> String {
        guard line.hasPrefix("#") else { return line }
        return String(line.drop(while: { $0 == "#" })).trimmingCharacters(in: .whitespaces)
    }

    static func title(_ item: MediaItem) -> String {
        let clean = item.cleanName
        return clean.isEmpty ? item.name : clean
    }

    static func label(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "live channel"
        case .movie: return "film"
        case .series: return "series"
        }
    }

    static func plural(_ kind: ContentKind) -> String {
        switch kind {
        case .live: return "live channels"
        case .movie: return "films"
        case .series: return "series"
        }
    }

    static func kind(from text: String?) -> ContentKind? {
        switch (text ?? "").lowercased() {
        case "live", "channel", "channels", "tv", "live_tv": return .live
        case "film", "films", "movie", "movies", "vod": return .movie
        case "series", "show", "shows", "tv_series": return .series
        default: return nil
        }
    }

    static func fold(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func intList(_ value: Any?) -> [Int] {
        guard let list = value as? [Any] else { return [] }
        var result: [Int] = []
        for element in list {
            if let number = element as? NSNumber {
                result.append(number.intValue)
            } else if let text = element as? String, let number = Int(text) {
                result.append(number)
            }
        }
        return result
    }

    static func unique(_ list: [MediaItem]) -> [MediaItem] {
        var seen = Set<String>()
        var result: [MediaItem] = []
        for item in list where !seen.contains(item.id) {
            seen.insert(item.id)
            result.append(item)
        }
        return result
    }

    static func findCategory(_ name: String, in categories: [ContentCategory]) -> ContentCategory? {
        let wanted = fold(name)
        if let exact = categories.first(where: { fold($0.name) == wanted }) { return exact }
        if let byId = categories.first(where: { $0.id == name }) { return byId }
        guard !wanted.isEmpty else { return nil }
        return categories.first(where: { fold($0.name).contains(wanted) })
    }

    /// "20:00" today, "Wed 20:00" on another day.
    static func timeLabel(_ date: Date) -> String {
        if Calendar.current.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        return date.formatted(.dateTime.weekday(.abbreviated).hour().minute())
    }

    /// Where a title first appears in the reply as whole words, or nil.
    static func mentionPosition(of title: String, in text: String) -> Int? {
        let needle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needle.count >= 2 else { return nil }
        // Very short titles ("It", "Up") only count when written exactly, so ordinary words don't match.
        let options: String.CompareOptions = needle.count <= 3 ? [] : [.caseInsensitive, .diacriticInsensitive]
        var searchStart = text.startIndex
        while searchStart < text.endIndex,
              let found = text.range(of: needle, options: options, range: searchStart..<text.endIndex) {
            let before: Character = found.lowerBound > text.startIndex ? text[text.index(before: found.lowerBound)] : " "
            let after: Character = found.upperBound < text.endIndex ? text[found.upperBound] : " "
            if !isWordCharacter(before) && !isWordCharacter(after) {
                return text.distance(from: text.startIndex, to: found.lowerBound)
            }
            searchStart = found.upperBound
        }
        return nil
    }

    private static func isWordCharacter(_ character: Character) -> Bool {
        character.isLetter || character.isNumber
    }

    static func dotStep(_ date: Date) -> Int {
        Int(date.timeIntervalSinceReferenceDate / 0.3) % 3
    }
}

// MARK: - Tool definitions

enum ClaudeToolSpec {
    static var all: [[String: Any]] {
        [searchCatalog, listCategories, browseCategory, whatsOn, myLists, play, recentlyAdded]
    }

    /// What the typing indicator says while a tool runs.
    static func activity(for name: String) -> String {
        switch name {
        case "search_catalog": return "Searching your library…"
        case "list_categories", "browse_category": return "Browsing your categories…"
        case "whats_on": return "Checking the guide…"
        case "my_lists": return "Looking at your lists…"
        case "play": return "Starting it…"
        case "recently_added": return "Looking at what's new…"
        default: return "Thinking…"
        }
    }

    private static func tool(_ name: String, _ description: String, properties: [String: Any], required: [String]) -> [String: Any] {
        let schema: [String: Any] = ["type": "object", "properties": properties, "required": required]
        return ["name": name, "description": description, "input_schema": schema]
    }

    private static var kindProperty: [String: Any] {
        let values: [String] = ["live", "film", "series"]
        return ["type": "string", "enum": values, "description": "live = TV channels, film = films, series = TV series."]
    }

    private static var searchCatalog: [String: Any] {
        let query: [String: Any] = ["type": "string", "description": "Words from the title, e.g. \"batman\" or \"sky sports\"."]
        return tool(
            "search_catalog",
            "Search the viewer's library by title (every word must appear; case and accents are ignored). Returns matching live channels, films and series with ids. Use short queries.",
            properties: ["query": query, "kind": kindProperty],
            required: ["query"]
        )
    }

    private static var listCategories: [String: Any] {
        tool(
            "list_categories",
            "List the provider's categories for live TV, films or series, with how many entries each holds when known.",
            properties: ["kind": kindProperty],
            required: ["kind"]
        )
    }

    private static var browseCategory: [String: Any] {
        let category: [String: Any] = ["type": "string", "description": "Category name exactly as list_categories returns it."]
        let offset: [String: Any] = ["type": "integer", "description": "How many entries to skip, for the next page."]
        return tool(
            "browse_category",
            "List entries in one category, 40 at a time, in the provider's order (often newest first). Use next_offset for more.",
            properties: ["kind": kindProperty, "category": category, "offset": offset],
            required: ["kind", "category"]
        )
    }

    private static var whatsOn: [String: Any] {
        let query: [String: Any] = ["type": "string", "description": "Words from channel names, e.g. \"sport\" or \"bbc\"."]
        let itemSchema: [String: Any] = ["type": "integer"]
        let ids: [String: Any] = ["type": "array", "items": itemSchema, "description": "Channel ids from other tools' results."]
        return tool(
            "whats_on",
            "What's on now and next on live channels: channels found by name (query), by ids (channel_ids), or with neither, the viewer's favourite and recently watched channels.",
            properties: ["query": query, "channel_ids": ids],
            required: []
        )
    }

    private static var myLists: [String: Any] {
        tool(
            "my_lists",
            "The viewer's favourites (in their groups), their library of saved films and series, and continue watching (recently played, with progress).",
            properties: [:],
            required: []
        )
    }

    private static var play: [String: Any] {
        let id: [String: Any] = ["type": "integer", "description": "The id from another tool's result."]
        return tool(
            "play",
            "Play a live channel now, or open a film's or series' page so the viewer can press Play. Only use it when the viewer asks to watch something.",
            properties: ["id": id],
            required: ["id"]
        )
    }

    private static var recentlyAdded: [String: Any] {
        tool(
            "recently_added",
            "The newest entries the provider has added (films or series), newest first.",
            properties: ["kind": kindProperty],
            required: ["kind"]
        )
    }
}

// MARK: - Tools

struct ClaudeToolOutcome {
    let text: String
    let isError: Bool

    static func ok(_ text: String) -> ClaudeToolOutcome { ClaudeToolOutcome(text: text, isError: false) }
    static func failed(_ text: String) -> ClaudeToolOutcome { ClaudeToolOutcome(text: text, isError: true) }
}

/// Runs Claude's tool calls against the viewer's library. Gives every item it shows Claude a
/// small number that stays the same for the whole conversation (MediaItem ids are strings).
@MainActor
final class ClaudeToolbox {
    private var known: [Int: MediaItem] = [:]
    private var numbers: [String: Int] = [:]
    private var nextNumber = 1
    private var turnSeen: [Int] = []
    private var categoryNames: [String: [String: String]] = [:]
    private var app: AppModel? = nil

    init() {}

    func beginTurn(app: AppModel) {
        self.app = app
        turnSeen = []
        categoryNames = [:]
    }

    func run(name: String, input: [String: Any]) async -> ClaudeToolOutcome {
        guard let app else { return .failed("The app isn't ready yet.") }
        switch name {
        case "search_catalog": return await searchCatalog(input, app: app)
        case "list_categories": return await listCategories(input, app: app)
        case "browse_category": return await browseCategory(input, app: app)
        case "whats_on": return await whatsOn(input, app: app)
        case "my_lists": return myLists(app: app)
        case "play": return play(input, app: app)
        case "recently_added": return recentlyAdded(input, app: app)
        default: return .failed("There's no tool called \(name).")
        }
    }

    /// Library items the reply names, in the order it names them.
    func mentioned(in text: String) -> [MediaItem] {
        var order: [Int] = turnSeen
        let seenThisTurn = Set(turnSeen)
        for number in known.keys.sorted() where !seenThisTurn.contains(number) {
            order.append(number)
        }
        var hits: [(position: Int, item: MediaItem)] = []
        var usedIds = Set<String>()
        var usedTitles = Set<String>()
        for number in order {
            guard let item = known[number], !usedIds.contains(item.id) else { continue }
            let title = ClaudeText.title(item)
            let titleKey = item.kind.rawValue + "|" + ClaudeText.fold(title)
            guard !usedTitles.contains(titleKey) else { continue }
            guard let position = ClaudeText.mentionPosition(of: title, in: text) else { continue }
            hits.append((position: position, item: item))
            usedIds.insert(item.id)
            usedTitles.insert(titleKey)
        }
        hits.sort { $0.position < $1.position }
        return hits.prefix(ClaudeLimits.cards).map { $0.item }
    }

    // MARK: Items as JSON

    private func remember(_ item: MediaItem) -> Int {
        let number: Int
        if let existing = numbers[item.id] {
            number = existing
        } else {
            number = nextNumber
            nextNumber += 1
            numbers[item.id] = number
        }
        known[number] = item
        if !turnSeen.contains(number) { turnSeen.append(number) }
        return number
    }

    private func categoryName(_ item: MediaItem) -> String? {
        guard let categoryId = item.categoryId, !categoryId.isEmpty, let app else { return nil }
        let key = item.source + "|" + item.kind.rawValue
        if categoryNames[key] == nil {
            var names: [String: String] = [:]
            let list = app.catalog(for: item.source)?.categories[item.kind] ?? []
            for category in list { names[category.id] = category.name }
            categoryNames[key] = names
        }
        return categoryNames[key]?[categoryId]
    }

    private func describe(_ item: MediaItem) -> [String: Any] {
        var entry: [String: Any] = [
            "id": remember(item),
            "title": ClaudeText.title(item),
            "kind": ClaudeText.label(item.kind),
        ]
        if let year = item.titleYear { entry["year"] = year }
        if let category = categoryName(item) { entry["category"] = category }
        if let rating = item.rating?.trimmingCharacters(in: .whitespaces), !rating.isEmpty, rating != "0", rating != "0.0" {
            entry["rating"] = rating
        }
        if item.kind == .live, let number = item.number { entry["number"] = number }
        if let userData = app?.userData {
            if userData.isFavourite(item) { entry["favourite"] = true }
            if item.kind != .live && userData.inLibrary(item) { entry["in_library"] = true }
        }
        return entry
    }

    private func describeAll(_ items: [MediaItem]) -> [[String: Any]] {
        var rows: [[String: Any]] = []
        for item in items { rows.append(describe(item)) }
        return rows
    }

    // MARK: The tools

    private func searchCatalog(_ input: [String: Any], app: AppModel) async -> ClaudeToolOutcome {
        guard let query = str(input, "query") else { return .failed("Give a title, or part of one, to search for.") }
        guard let catalog = app.catalog else { return .failed(ClaudeText.noSource) }
        let wanted = ClaudeText.kind(from: str(input, "kind"))
        let kinds = catalog.kinds.filter { wanted == nil || $0 == wanted }
        if kinds.isEmpty, let wanted {
            return .ok("This source has no \(ClaudeText.plural(wanted)).")
        }
        let found = await catalog.search(query, kinds: kinds, limit: ClaudeLimits.search)
        if found.isEmpty {
            let downloading = kinds.filter { !catalog.hasFullList($0) }
            if !downloading.isEmpty {
                let names = downloading.map { ClaudeText.plural($0) }.joined(separator: " and ")
                return .ok("Nothing found yet: the full list of \(names) is still downloading, so search can't see everything. Try list_categories and browse_category instead.")
            }
            return .ok("Nothing found for \"\(query)\". Try fewer or different words.")
        }
        return .ok(ClaudeText.json(describeAll(found)))
    }

    private func listCategories(_ input: [String: Any], app: AppModel) async -> ClaudeToolOutcome {
        guard let catalog = app.catalog else { return .failed(ClaudeText.noSource) }
        let kind = ClaudeText.kind(from: str(input, "kind")) ?? .movie
        guard catalog.kinds.contains(kind) else { return .ok("This source has no \(ClaudeText.plural(kind)).") }
        let loaded = await catalog.loadCategories(kind)
        let shown = app.userData.arranged(loaded, source: catalog.source.id, kind: kind)
        guard !shown.isEmpty else {
            return .ok("No \(ClaudeText.label(kind)) categories have arrived yet. Try search_catalog instead.")
        }
        var counts: [String: Int] = [:]
        if catalog.hasFullList(kind) {
            for item in catalog.all(kind) { counts[item.categoryId ?? "", default: 0] += 1 }
        }
        var rows: [[String: Any]] = []
        for category in shown.prefix(ClaudeLimits.categories) {
            var row: [String: Any] = ["category": category.name]
            if let count = counts[category.id] { row["count"] = count }
            rows.append(row)
        }
        var result: [String: Any] = ["kind": ClaudeText.label(kind), "categories": rows]
        if shown.count > ClaudeLimits.categories {
            result["note"] = "Showing the first \(ClaudeLimits.categories) of \(shown.count) categories."
        }
        return .ok(ClaudeText.json(result))
    }

    private func browseCategory(_ input: [String: Any], app: AppModel) async -> ClaudeToolOutcome {
        guard let catalog = app.catalog else { return .failed(ClaudeText.noSource) }
        let kind = ClaudeText.kind(from: str(input, "kind")) ?? .movie
        guard catalog.kinds.contains(kind) else { return .ok("This source has no \(ClaudeText.plural(kind)).") }
        guard let name = str(input, "category") else { return .failed("Name a category from list_categories.") }
        let offset = max(0, int(input, "offset") ?? 0)
        let categories = await catalog.loadCategories(kind)
        guard let match = ClaudeText.findCategory(name, in: categories) else {
            return .failed("There's no \(ClaudeText.label(kind)) category called \"\(name)\". Use list_categories to see the names.")
        }
        let entries: [MediaItem]
        do {
            entries = try await catalog.items(kind, category: match.id)
        } catch {
            let reason = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return .failed("Couldn't load that category: \(reason)")
        }
        let page = Array(entries.dropFirst(offset).prefix(ClaudeLimits.browse))
        var result: [String: Any] = [
            "category": match.name,
            "total_in_category": entries.count,
            "offset": offset,
            "items": describeAll(page),
        ]
        if offset + page.count < entries.count { result["next_offset"] = offset + page.count }
        return .ok(ClaudeText.json(result))
    }

    private func whatsOn(_ input: [String: Any], app: AppModel) async -> ClaudeToolOutcome {
        let wantedIds = ClaudeText.intList(input["channel_ids"])
        let query = str(input, "query")
        var channels: [MediaItem] = []
        if !wantedIds.isEmpty {
            for number in wantedIds {
                if let item = known[number], item.kind == .live { channels.append(item) }
            }
        } else if let query {
            guard let catalog = app.catalog else { return .failed(ClaudeText.noSource) }
            channels = await catalog.search(query, kinds: [.live], limit: ClaudeLimits.whatsOn)
        } else {
            channels = favouriteChannels(app)
        }
        channels = Array(ClaudeText.unique(channels).prefix(ClaudeLimits.whatsOn))
        guard !channels.isEmpty else {
            return .ok(noChannelsMessage(ids: wantedIds, query: query, app: app))
        }
        await loadGuides(channels, app: app)
        let now = Date()
        var rows: [[String: Any]] = []
        for channel in channels {
            var row = describe(channel)
            let programmes = app.nowNext(for: channel).filter { $0.end > now }
            let listed = programmeRows(programmes, now: now)
            row["programmes"] = listed
            if listed.isEmpty { row["note"] = "No guide listings for this channel." }
            rows.append(row)
        }
        return .ok(ClaudeText.json(rows))
    }

    private func noChannelsMessage(ids: [Int], query: String?, app: AppModel) -> String {
        if !ids.isEmpty { return "None of those ids are live channels. Use ids from search_catalog or my_lists." }
        if let query {
            if let catalog = app.catalog, !catalog.hasFullList(.live) {
                return "The channel list is still downloading, so no channels matched \"\(query)\" yet. Try again in a moment."
            }
            return "No live channels match \"\(query)\". Try a shorter name."
        }
        return "The viewer has no favourite channels and hasn't watched live TV here yet. Search by channel name instead."
    }

    private func favouriteChannels(_ app: AppModel) -> [MediaItem] {
        var list: [MediaItem] = []
        for group in app.userData.groups {
            for item in group.items where item.kind == .live { list.append(item) }
        }
        for entry in app.userData.history where entry.item.kind == .live {
            list.append(entry.item)
        }
        return list
    }

    /// Fetches now/next for the channels, a few at a time so the provider isn't flooded.
    private func loadGuides(_ channels: [MediaItem], app: AppModel) async {
        var start = 0
        while start < channels.count {
            let end = min(start + ClaudeLimits.guideBatch, channels.count)
            let batch = Array(channels[start..<end])
            await withTaskGroup(of: Void.self) { group in
                for channel in batch {
                    group.addTask { await app.loadEPG(for: channel) }
                }
            }
            start = end
        }
    }

    private func programmeRows(_ programmes: [Programme], now: Date) -> [[String: Any]] {
        var rows: [[String: Any]] = []
        for programme in programmes.prefix(3) {
            var row: [String: Any] = [
                "title": programme.title,
                "start": ClaudeText.timeLabel(programme.start),
                "end": ClaudeText.timeLabel(programme.end),
            ]
            if programme.isOn(at: now) {
                row["on_now"] = true
                let about = programme.description.trimmingCharacters(in: .whitespacesAndNewlines)
                if !about.isEmpty { row["about"] = String(about.prefix(160)) }
            }
            rows.append(row)
        }
        return rows
    }

    private func myLists(app: AppModel) -> ClaudeToolOutcome {
        let data = app.userData
        var groups: [[String: Any]] = []
        for group in data.groups where !group.items.isEmpty {
            let entries = describeAll(Array(group.items.prefix(ClaudeLimits.listItems)))
            let row: [String: Any] = ["group": group.name, "items": entries]
            groups.append(row)
        }
        let library = describeAll(Array(data.library.prefix(ClaudeLimits.listItems)))
        let relative = RelativeDateTimeFormatter()
        relative.unitsStyle = .full
        var recent: [[String: Any]] = []
        for entry in data.continueWatching {
            var row = describe(entry.item)
            if let subtitle = entry.subtitle, !subtitle.isEmpty { row["detail"] = subtitle }
            if entry.duration > 0 { row["watched_percent"] = Int((entry.progress * 100).rounded()) }
            row["last_watched"] = relative.localizedString(for: entry.updatedAt, relativeTo: Date())
            recent.append(row)
        }
        if groups.isEmpty && library.isEmpty && recent.isEmpty {
            return .ok("The viewer hasn't saved any favourites or library items yet, and hasn't watched anything here.")
        }
        let result: [String: Any] = ["favourites": groups, "library": library, "continue_watching": recent]
        return .ok(ClaudeText.json(result))
    }

    private func play(_ input: [String: Any], app: AppModel) -> ClaudeToolOutcome {
        guard let number = int(input, "id"), let item = known[number] else {
            return .failed("No item with that id. Use an id from another tool's result.")
        }
        if !turnSeen.contains(number) { turnSeen.append(number) }
        let title = ClaudeText.title(item)
        switch item.kind {
        case .live:
            app.playLive(item, in: [item])
            return .ok("Now playing \(title).")
        case .movie, .series:
            app.open(item)
            return .ok("Opened the page for \(title); the viewer presses Play there.")
        }
    }

    private func recentlyAdded(_ input: [String: Any], app: AppModel) -> ClaudeToolOutcome {
        guard let catalog = app.catalog else { return .failed(ClaudeText.noSource) }
        let kind = ClaudeText.kind(from: str(input, "kind")) ?? .movie
        let name = ClaudeText.plural(kind)
        guard catalog.kinds.contains(kind) else { return .ok("This source has no \(name).") }
        guard catalog.hasFullList(kind) else {
            return .ok("The full list of \(name) is still downloading, so recently added isn't ready yet. Try browse_category instead.")
        }
        let list = catalog.recentlyAdded(kind, limit: ClaudeLimits.search)
        guard !list.isEmpty else { return .ok("Nothing found.") }
        var rows: [[String: Any]] = []
        for item in list {
            var row = describe(item)
            if let added = item.added, added > 0 {
                row["added"] = Date(timeIntervalSince1970: added).formatted(date: .abbreviated, time: .omitted)
            }
            rows.append(row)
        }
        return .ok(ClaudeText.json(rows))
    }
}

// MARK: - Chat model

/// The conversation, the key setup and the model choice. Shared, so the chat survives switching
/// to another section and back.
@MainActor
final class ClaudeChatModel: ObservableObject {
    static let shared = ClaudeChatModel()

    @Published private(set) var configured = false
    @Published var modelChoice: ClaudeModelChoice = .sonnet {
        didSet { UserDefaults.standard.set(modelChoice.rawValue, forKey: ClaudeModelChoice.defaultsKey) }
    }
    @Published private(set) var messages: [ClaudeMessage] = []
    @Published var input = ""
    @Published var keyInput = ""
    @Published private(set) var thinking = false
    @Published private(set) var activity = ""
    @Published private(set) var checkingKey = false
    @Published private(set) var failure: String? = nil
    @Published private(set) var setupFailure: String? = nil

    /// The conversation in Messages API form.
    private var history: [[String: Any]] = []
    private var toolbox: ClaudeToolbox
    /// Bumped by New chat and Remove key, so answers to an abandoned question are dropped.
    private var generation = 0
    private var turnTask: Task<Void, Never>? = nil
    private var keyTask: Task<Void, Never>? = nil

    init() {
        toolbox = ClaudeToolbox()
        configured = Secrets.has(.claude)
        modelChoice = ClaudeModelChoice.saved
    }

    // MARK: Key

    /// Picks up a key added or removed elsewhere (Settings).
    func refreshKeyState() {
        let has = Secrets.has(.claude)
        guard has != configured else { return }
        if !has { newChat() }
        configured = has
    }

    func saveKey() {
        let key = keyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty, !checkingKey else { return }
        checkingKey = true
        setupFailure = nil
        keyTask = Task { [weak self] in
            do {
                try await ClaudeAPI.checkKey(key)
                guard !Task.isCancelled else { return }
                self?.keyAccepted(key)
            } catch {
                self?.keyFailed(error)
            }
        }
    }

    private func keyAccepted(_ key: String) {
        Secrets.set(.claude, key)
        keyInput = ""
        checkingKey = false
        setupFailure = nil
        configured = true
    }

    private func keyFailed(_ error: Error) {
        checkingKey = false
        if error is CancellationError { return }
        if let problem = error as? ClaudeFailure {
            setupFailure = problem.message(setup: true)
        } else {
            setupFailure = "Couldn't check the key. Try again."
        }
    }

    func removeKey() {
        newChat()
        keyTask?.cancel()
        keyTask = nil
        Secrets.set(.claude, nil)
        configured = false
        keyInput = ""
        checkingKey = false
        setupFailure = nil
    }

    // MARK: Conversation

    func newChat() {
        generation += 1
        turnTask?.cancel()
        turnTask = nil
        history = []
        messages = []
        toolbox = ClaudeToolbox()
        thinking = false
        activity = ""
        failure = nil
    }

    /// Sends what's in the text field.
    func sendInput(app: AppModel) {
        let text = input
        if start(text, app: app) { input = "" }
    }

    /// Sends a suggestion (or any text) without touching the text field.
    func send(_ text: String, app: AppModel) {
        _ = start(text, app: app)
    }

    private func start(_ raw: String, app: AppModel) -> Bool {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !thinking else { return false }
        guard let key = Secrets.get(.claude) else {
            configured = false
            setupFailure = ClaudeFailure.noKey.message(setup: true)
            return false
        }
        failure = nil
        messages.append(ClaudeMessage(role: .user, text: text))
        history.append(["role": "user", "content": text])
        thinking = true
        activity = "Thinking…"
        generation += 1
        let turn = generation
        let choice = modelChoice
        turnTask = Task { [weak self] in
            await self?.runTurn(turn: turn, key: key, choice: choice, app: app)
        }
        return true
    }

    private func runTurn(turn: Int, key: String, choice: ClaudeModelChoice, app: AppModel) async {
        do {
            let reply = try await answer(turn: turn, key: key, choice: choice, app: app)
            guard turn == generation else { return }
            messages.append(reply)
            thinking = false
            activity = ""
        } catch {
            guard turn == generation else { return }
            thinking = false
            activity = ""
            dropUnanswered()
            if error is CancellationError { return }
            if let problem = error as? ClaudeFailure {
                failure = problem.message(setup: false)
            } else {
                failure = "Something went wrong asking Claude. Try again."
            }
        }
    }

    /// Runs one question to its final answer, carrying out tool calls along the way.
    private func answer(turn: Int, key: String, choice: ClaudeModelChoice, app: AppModel) async throws -> ClaudeMessage {
        toolbox.beginTurn(app: app)
        let system = systemPrompt(app: app)
        let tools = ClaudeToolSpec.all
        for _ in 0..<ClaudeLimits.toolRounds {
            history = trimmedHistory()
            let body: [String: Any] = [
                "model": choice.rawValue,
                "max_tokens": ClaudeLimits.maxTokens,
                "system": system,
                "tools": tools,
                "messages": history,
            ]
            guard JSONSerialization.isValidJSONObject(body),
                  let payload = try? JSONSerialization.data(withJSONObject: body) else {
                throw ClaudeFailure.badRequest
            }
            let data = try await ClaudeAPI.messages(key: key, body: payload)
            try ensureCurrent(turn)
            guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                throw ClaudeFailure.badResponse
            }
            let raw = ((root["content"] as? [Any]) ?? []).compactMap { $0 as? [String: Any] }
            let blocks = ClaudeText.cleanBlocks(raw)
            let uses = blocks.filter { ($0["type"] as? String) == "tool_use" }
            let stop = (root["stop_reason"] as? String) ?? ""
            if stop != "tool_use" || uses.isEmpty {
                return try finish(blocks)
            }
            history.append(["role": "assistant", "content": blocks])
            let results = try await runTools(uses, turn: turn)
            history.append(["role": "user", "content": results])
        }
        let fallback = "I got lost looking through your library. Try asking again, a bit more specifically."
        appendAssistantText(fallback)
        return ClaudeMessage(role: .assistant, text: fallback)
    }

    private func finish(_ blocks: [[String: Any]]) throws -> ClaudeMessage {
        let text = ClaudeText.replyText(blocks)
        guard !text.isEmpty else { throw ClaudeFailure.noReply }
        // Only the text goes back into the history: a tool call cut off by max_tokens would
        // otherwise need a result the next request doesn't have.
        appendAssistantText(text)
        return ClaudeMessage(role: .assistant, text: text, items: toolbox.mentioned(in: text))
    }

    private func appendAssistantText(_ text: String) {
        let block: [String: Any] = ["type": "text", "text": text]
        let blocks: [[String: Any]] = [block]
        history.append(["role": "assistant", "content": blocks])
    }

    private func runTools(_ uses: [[String: Any]], turn: Int) async throws -> [[String: Any]] {
        var results: [[String: Any]] = []
        for use in uses {
            try ensureCurrent(turn)
            let id = (use["id"] as? String) ?? ""
            let name = (use["name"] as? String) ?? ""
            let input = (use["input"] as? [String: Any]) ?? [:]
            activity = ClaudeToolSpec.activity(for: name)
            let outcome = await toolbox.run(name: name, input: input)
            let text = outcome.text.isEmpty ? "Done." : outcome.text
            var block: [String: Any] = ["type": "tool_result", "tool_use_id": id, "content": text]
            if outcome.isError { block["is_error"] = true }
            results.append(block)
        }
        try ensureCurrent(turn)
        return results
    }

    private func ensureCurrent(_ turn: Int) throws {
        if turn != generation || Task.isCancelled { throw CancellationError() }
    }

    /// The last ~30 messages, starting at a typed question (the API needs a plain user turn first).
    private func trimmedHistory() -> [[String: Any]] {
        guard !history.isEmpty else { return history }
        var start = max(0, history.count - ClaudeLimits.historyMessages)
        while start < history.count && !ClaudeText.isPlainUser(history[start]) {
            start += 1
        }
        if start >= history.count {
            start = history.lastIndex(where: { ClaudeText.isPlainUser($0) }) ?? 0
        }
        return Array(history[start...])
    }

    /// Removes the question that never got an answer (and any tool calls after it), and puts
    /// its text back in the field so it can be sent again.
    private func dropUnanswered() {
        if let index = history.lastIndex(where: { ClaudeText.isPlainUser($0) }) {
            history.removeSubrange(index...)
        }
        if let last = messages.last, last.role == .user {
            messages.removeLast()
            if input.isEmpty { input = last.text }
        }
    }

    private func systemPrompt(app: AppModel) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .full
        formatter.timeStyle = .short
        let now = formatter.string(from: Date())
        var lines: [String] = []
        lines.append("You are the assistant inside Chud Supreme, an IPTV app on the viewer's Mac. It is \(now) (\(TimeZone.current.identifier)).")
        lines.append("Help them find something to watch in their own library: live TV channels, films and series from their IPTV provider. \(librarySummary(app))")
        lines.append("Use the tools to check what's actually in the library and on air before you suggest anything, and use titles exactly as the tools return them.")
        lines.append("Categories come from the provider, and titles may carry a year or tags; use your own knowledge of films and shows to pick good ones from what's there.")
        lines.append("When they ask to watch something, call play with its id: live channels start playing, films and series open their page.")
        lines.append("Keep replies short (under 100 words) and in plain text: no markdown, no headings, no tables.")
        return lines.joined(separator: "\n")
    }

    private func librarySummary(_ app: AppModel) -> String {
        guard let catalog = app.catalog else { return "No source is set up yet." }
        var parts: [String] = []
        for kind in catalog.kinds {
            let name = ClaudeText.plural(kind)
            parts.append(catalog.hasFullList(kind) ? "\(catalog.count(kind)) \(name)" : "\(name) (list still downloading)")
        }
        return "The active source, \"\(catalog.source.name)\", has " + parts.joined(separator: ", ") + ". Search and browsing use this source."
    }
}

// MARK: - Screen

@MainActor
struct ClaudeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var userData: UserData
    @ObservedObject private var chat: ClaudeChatModel

    init() {
        _chat = ObservedObject(wrappedValue: ClaudeChatModel.shared)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            content
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear { chat.refreshKeyState() }
    }

    @ViewBuilder
    private var content: some View {
        if chat.configured {
            ClaudeChatPanel(chat: chat)
        } else {
            ClaudeSetupPanel(chat: chat)
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                NeonTitle(text: "Ask Claude")
                Text(subtitle)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textMuted)
            }
            Spacer()
            if chat.configured {
                headerActions
            }
        }
    }

    private var subtitle: String {
        if chat.configured { return "\(chat.modelChoice.label) · billed to your Anthropic account" }
        return "Connect your Anthropic account to get started"
    }

    private var headerActions: some View {
        HStack(spacing: 10) {
            ClaudeModelMenu(chat: chat)
            Button {
                chat.newChat()
            } label: {
                Label("New chat", systemImage: "square.and.pencil")
            }
            .buttonStyle(NeonButtonStyle())
            .disabled(chat.messages.isEmpty && !chat.thinking && chat.failure == nil)
            .help("Start a new conversation")
            Button {
                confirmRemoveKey()
            } label: {
                Label("Remove key", systemImage: "trash")
            }
            .buttonStyle(NeonButtonStyle())
            .help("Remove your Anthropic API key from this Mac")
        }
    }

    private func confirmRemoveKey() {
        let sure = TextPrompt.confirm(
            title: "Remove your Anthropic key?",
            message: "Claude stops working here until you add a key again, and this conversation is cleared.",
            action: "Remove"
        )
        if sure { chat.removeKey() }
    }
}

@MainActor
private struct ClaudeModelMenu: View {
    @ObservedObject var chat: ClaudeChatModel

    var body: some View {
        Menu {
            ForEach(ClaudeModelChoice.allCases) { choice in
                Button {
                    chat.modelChoice = choice
                } label: {
                    if choice == chat.modelChoice {
                        Label(choice.label, systemImage: "checkmark")
                    } else {
                        Text(choice.label)
                    }
                }
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "cpu").foregroundColor(Neon.cyan)
                Text(chat.modelChoice.label)
                    .font(NeonFont.body(13, bold: true))
                    .foregroundColor(Neon.text)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 10))
                    .foregroundColor(Neon.textMuted)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(HudShape(cut: 7).fill(Neon.surface))
            .overlay(HudShape(cut: 7).stroke(Neon.cyan.opacity(0.4), lineWidth: 1))
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Which Claude model answers")
    }
}

// MARK: Setup

@MainActor
private struct ClaudeSetupPanel: View {
    @ObservedObject var chat: ClaudeChatModel

    var body: some View {
        ScrollView {
            HStack(alignment: .top, spacing: 32) {
                explanation
                form
            }
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var explanation: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connect Claude")
                .font(NeonFont.display(20))
                .foregroundColor(Neon.text)
            paragraph("Ask for something to watch and Claude looks through your own channels, films and series, checks what's on now, and can start it for you.")
            paragraph("It uses your own Anthropic API key, so what you ask is billed to your Anthropic account at Anthropic's API prices. Haiku costs the least and Opus the most.")
            paragraph("Create a key at console.anthropic.com under API keys, then paste it here. It's kept in this Mac's Keychain and only ever sent to Anthropic.")
            Button {
                openConsole()
            } label: {
                Label("Open console.anthropic.com", systemImage: "arrow.up.right.square")
            }
            .buttonStyle(NeonButtonStyle())
        }
        .frame(minWidth: 240, maxWidth: 440, alignment: .leading)
    }

    private func paragraph(_ text: String) -> some View {
        Text(text)
            .font(NeonFont.body(15))
            .foregroundColor(Neon.textSecondary)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var form: some View {
        VStack(alignment: .leading, spacing: 14) {
            fieldLabel("Anthropic API key")
            SecureField("sk-ant-…", text: $chat.keyInput)
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(10)
                .neonPanel()
                .disabled(chat.checkingKey)
                .onSubmit { chat.saveKey() }
            fieldLabel("Model")
            VStack(alignment: .leading, spacing: 8) {
                ForEach(ClaudeModelChoice.allCases) { choice in
                    ClaudeModelOption(choice: choice, selected: choice == chat.modelChoice) {
                        chat.modelChoice = choice
                    }
                }
            }
            Button {
                chat.saveKey()
            } label: {
                Label(saveTitle, systemImage: "key.fill")
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
            .disabled(!canSave)
            status
        }
        .padding(20)
        .frame(width: 360, alignment: .leading)
        .neonPanel()
    }

    private var saveTitle: String { chat.checkingKey ? "Checking…" : "Save and check" }

    private var canSave: Bool {
        !chat.checkingKey && !chat.keyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(NeonFont.body(13, bold: true))
            .foregroundColor(Neon.textSecondary)
    }

    @ViewBuilder
    private var status: some View {
        if chat.checkingKey {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Checking your key with Anthropic…")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
            }
        } else if let problem = chat.setupFailure {
            Text(problem)
                .font(NeonFont.body(13))
                .foregroundColor(Neon.danger)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func openConsole() {
        if let url = URL(string: "https://console.anthropic.com/settings/keys") {
            NSWorkspace.shared.open(url)
        }
    }
}

@MainActor
private struct ClaudeModelOption: View {
    let choice: ClaudeModelChoice
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(iconColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.label)
                        .font(NeonFont.body(14, bold: true))
                        .foregroundColor(hovering ? Neon.onCyan : Neon.text)
                    Text(choice.detail)
                        .font(NeonFont.body(12))
                        .foregroundColor(hovering ? Neon.onCyan.opacity(0.8) : Neon.textMuted)
                }
                Spacer()
            }
            .padding(10)
            .background(HudShape(cut: 7).fill(fill))
            .overlay(HudShape(cut: 7).stroke(Neon.cyan.opacity(selected ? 0.8 : 0.2), lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
    }

    private var iconColor: Color {
        if hovering { return Neon.onCyan }
        return selected ? Neon.cyan : Neon.textMuted
    }

    private var fill: Color {
        if hovering { return Neon.cyan }
        return selected ? Neon.surfaceRaised : Neon.surface.opacity(0.8)
    }
}

// MARK: Chat

private enum ClaudeAnchor {
    static let bottom = "claude-bottom"
}

@MainActor
private struct ClaudeChatPanel: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var chat: ClaudeChatModel
    @FocusState private var inputFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            transcript
            suggestions
            inputRow
        }
        .onAppear { focusInput() }
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if chat.messages.isEmpty {
                        ClaudeIntro()
                    }
                    ForEach(chat.messages) { message in
                        ClaudeBubble(message: message) { item in open(item) }
                            .id(message.id)
                    }
                    if chat.thinking {
                        ClaudeTypingIndicator(activity: chat.activity)
                    }
                    if let problem = chat.failure {
                        ClaudeFailureLine(text: problem)
                    }
                    Color.clear
                        .frame(height: 1)
                        .id(ClaudeAnchor.bottom)
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onChange(of: chat.messages.count) { _ in scrollToBottom(proxy, animated: true) }
            .onChange(of: chat.thinking) { _ in scrollToBottom(proxy, animated: true) }
            .onChange(of: chat.failure) { _ in scrollToBottom(proxy, animated: true) }
            .onAppear { scrollToBottom(proxy, animated: false) }
        }
    }

    private var suggestions: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(ClaudeText.suggestions, id: \.self) { suggestion in
                    Button {
                        chat.send(suggestion, app: model)
                    } label: {
                        Label(suggestion, systemImage: "sparkles")
                    }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(chat.thinking)
                }
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 2)
        }
    }

    private var inputRow: some View {
        HStack(spacing: 10) {
            TextField("Ask Claude what to watch…", text: $chat.input)
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(12)
                .neonPanel(highlighted: inputFocused)
                .focused($inputFocused)
                .onSubmit { submit() }
            Button {
                submit()
            } label: {
                Label("Send", systemImage: "paperplane.fill")
            }
            .buttonStyle(NeonButtonStyle(prominent: true))
            .disabled(!canSend)
        }
    }

    private var canSend: Bool {
        !chat.thinking && !chat.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func submit() {
        chat.sendInput(app: model)
        inputFocused = true
    }

    /// Channels start playing; films and series open their page.
    private func open(_ item: MediaItem) {
        model.open(item)
    }

    private func focusInput() {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 150_000_000)
            inputFocused = true
        }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool) {
        // Wait a moment so the new row is laid out before scrolling to it.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 60_000_000)
            if animated {
                withAnimation(Motion.quick) { proxy.scrollTo(ClaudeAnchor.bottom, anchor: .bottom) }
            } else {
                proxy.scrollTo(ClaudeAnchor.bottom, anchor: .bottom)
            }
        }
    }
}

@MainActor
private struct ClaudeAvatar: View {
    var body: some View {
        Image(systemName: "sparkles")
            .font(.system(size: 14, weight: .bold))
            .foregroundColor(Neon.magenta)
            .frame(width: 30, height: 30)
            .background(Circle().fill(Neon.surfaceRaised))
            .overlay(Circle().stroke(Neon.magenta.opacity(0.6), lineWidth: 1))
    }
}

@MainActor
private struct ClaudeIntro: View {
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            ClaudeAvatar()
            VStack(alignment: .leading, spacing: 6) {
                Text("What do you fancy watching?")
                    .font(NeonFont.body(16, bold: true))
                    .foregroundColor(Neon.text)
                Text("I can search your channels, films and series, see what's on live TV now, look at your favourites and library, and start things for you. Pick a suggestion below or ask me anything.")
                    .font(NeonFont.body(14))
                    .foregroundColor(Neon.textSecondary)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .neonPanel()
        }
        .frame(maxWidth: 680, alignment: .leading)
    }
}

@MainActor
private struct ClaudeBubble: View {
    let message: ClaudeMessage
    let onOpen: (MediaItem) -> Void

    var body: some View {
        if message.role == .user {
            userBubble
        } else {
            assistantBubble
        }
    }

    private var userBubble: some View {
        Text(message.text)
            .font(NeonFont.body(15))
            .foregroundColor(Neon.onCyan)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(HudShape(cut: 10).fill(Neon.cyan))
            .shadow(color: Neon.cyan.opacity(0.35), radius: 8)
            .frame(maxWidth: 560, alignment: .trailing)
            .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private var assistantBubble: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                ClaudeAvatar()
                Text(message.text)
                    .font(NeonFont.body(15))
                    .foregroundColor(Neon.text)
                    .lineSpacing(3)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .neonPanel()
            }
            .frame(maxWidth: 680, alignment: .leading)
            if !message.items.isEmpty {
                ClaudeItemStrip(items: message.items, onOpen: onOpen)
                    .padding(.leading, 34)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Cards for the library items a reply mentions: posters for films and series, a small
/// button for channels.
@MainActor
private struct ClaudeItemStrip: View {
    let items: [MediaItem]
    let onOpen: (MediaItem) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: 14) {
                ForEach(items) { item in
                    card(item)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 6)
        }
    }

    @ViewBuilder
    private func card(_ item: MediaItem) -> some View {
        if item.kind == .live {
            ClaudeChannelButton(item: item) { onOpen(item) }
        } else {
            PosterCard(item: item) { onOpen(item) }
                .frame(width: 116)
        }
    }
}

@MainActor
private struct ClaudeChannelButton: View {
    let item: MediaItem
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                ChannelLogo(url: item.icon, size: CGSize(width: 46, height: 34))
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.name)
                        .font(NeonFont.body(13, bold: true))
                        .lineLimit(1)
                    Label("Watch live", systemImage: "play.fill")
                        .font(NeonFont.body(11))
                }
                Spacer(minLength: 0)
            }
            .foregroundColor(hovering ? Neon.onCyan : Neon.text)
            .padding(8)
            .frame(width: 230, alignment: .leading)
            .background(HudShape(cut: 8).fill(hovering ? Neon.cyan : Neon.surface))
            .overlay(HudShape(cut: 8).stroke(Neon.cyan.opacity(hovering ? 1 : 0.3), lineWidth: 1))
            .shadow(color: Neon.cyan.opacity(hovering ? 0.45 : 0), radius: 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { inside in withAnimation(Motion.hover) { hovering = inside } }
        .contextMenu { MediaContextMenu(item: item) }
        .help("Play \(item.name)")
    }
}

@MainActor
private struct ClaudeTypingIndicator: View {
    let activity: String

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            ClaudeAvatar()
            TimelineView(.periodic(from: Date(), by: 0.3)) { context in
                ClaudeDots(step: ClaudeText.dotStep(context.date))
            }
            Text(activity.isEmpty ? "Thinking…" : activity)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

@MainActor
private struct ClaudeDots: View {
    let step: Int

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(Neon.cyan)
                    .frame(width: 8, height: 8)
                    .opacity(index == step ? 1 : 0.28)
                    .scaleEffect(index == step ? 1.15 : 0.9)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: step)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .neonPanel()
    }
}

@MainActor
private struct ClaudeFailureLine: View {
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(Neon.danger)
            Text(text)
                .font(NeonFont.body(14))
                .foregroundColor(Neon.danger)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(HudShape(cut: 8).fill(Neon.danger.opacity(0.1)))
        .overlay(HudShape(cut: 8).stroke(Neon.danger.opacity(0.5), lineWidth: 1))
        .frame(maxWidth: 680, alignment: .leading)
    }
}
