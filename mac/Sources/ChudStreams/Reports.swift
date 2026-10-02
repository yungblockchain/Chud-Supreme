import AppKit
import Foundation
import MetricKit

// Crash and error reports.
//
// Errors (a stream that won't load, a failed download) go into a small rolling log. If the app
// crashes, a note is written as it goes down; on the next launch that becomes a report together
// with the recent log, compressed and kept in Application Support. Reports can be exported, or
// sent as an issue to the viewer's own (ideally private) GitHub repository. Passwords, API keys,
// usernames and server addresses are removed before anything is saved.

struct LogLine: Codable, Hashable {
    let date: Date
    let level: String
    let text: String
}

enum ErrorLog {
    private static let queue = DispatchQueue(label: "chud.errorlog")
    private static var lines: [LogLine] = []
    private static let limit = 400

    static func note(_ text: String) { add("info", text) }

    static func record(_ title: String, detail: String) { add("error", "\(title): \(detail)") }

    private static func add(_ level: String, _ text: String) {
        let line = LogLine(date: Date(), level: level, text: Redactor.scrub(text))
        queue.async {
            lines.append(line)
            if lines.count > limit { lines.removeFirst(lines.count - limit) }
            Reports.appendToLogFile(line)
        }
    }

    static func recent() -> [LogLine] {
        queue.sync { lines }
    }
}

/// Removes anything private from report text.
enum Redactor {
    static func scrub(_ text: String) -> String {
        var result = text
        // Xtream addresses carry the login: /live/user/pass/123.ts, ?username=…&password=…
        let patterns = [
            "(username|password|api_key|token|key)=[^&\\s\"']+",
            "/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/",
            "sk-ant-[A-Za-z0-9_\\-]+",
            "bot[0-9]{6,}:[A-Za-z0-9_\\-]+",
            "gh[pousr]_[A-Za-z0-9]{20,}",
            "github_pat_[A-Za-z0-9_]{20,}",
        ]
        let replacements = ["$1=•••", "/$1/•••/•••/", "sk-ant-•••", "bot•••", "gh•••", "github_pat_•••"]
        for (pattern, replacement) in zip(patterns, replacements) {
            result = result.replacingOccurrences(of: pattern, with: replacement, options: [.regularExpression, .caseInsensitive])
        }
        for secret in Secrets.allValues() {
            result = result.replacingOccurrences(of: secret, with: "•••")
        }
        for source in Reports.knownLogins() {
            result = result.replacingOccurrences(of: source, with: "•••")
        }
        return result
    }
}

struct CrashReport: Identifiable, Hashable {
    let id: String
    let date: Date
    let kind: String
    let file: URL
    var uploaded: Bool

    var title: String {
        "\(kind) · \(date.formatted(date: .abbreviated, time: .shortened))"
    }
}

enum Reports {
    static var folder: URL {
        let url = CatalogCache.directory.appendingPathComponent("Reports", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private static var logFile: URL { folder.appendingPathComponent("recent.log") }
    private static var crashMarker: URL { folder.appendingPathComponent("crash-in-progress.txt") }
    private static var uploadedKey: String { "reports.uploaded" }
    static let repoKey = "reports.githubRepo"
    static let autoUploadKey = "reports.autoUpload"

    private static var loginWords: [String] = []

    /// Usernames and passwords of the viewer's sources, for scrubbing.
    static func knownLogins() -> [String] { loginWords }

    static func setKnownLogins(_ sources: [Source]) {
        var words: [String] = []
        for source in sources {
            if let username = source.username, username.count >= 3 { words.append(username) }
            if let password = source.password, password.count >= 3 { words.append(password) }
        }
        loginWords = words
    }

    fileprivate static func appendToLogFile(_ line: LogLine) {
        let text = "\(line.date.formatted(.iso8601)) [\(line.level)] \(line.text)\n"
        guard let data = text.data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: logFile) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
            // Keep the file small.
            if let size = try? FileManager.default.attributesOfItem(atPath: logFile.path)[.size] as? Int, size > 512_000,
               let all = try? String(contentsOf: logFile) {
                try? String(all.suffix(200_000)).write(to: logFile, atomically: true, encoding: .utf8)
            }
        } else {
            try? data.write(to: logFile)
        }
    }

    // MARK: Crash capture

    private static var markerDescriptor: Int32 = -1

    /// Call once at launch: turns any crash from last time into a report and arms the handlers.
    static func start() {
        collectPreviousCrash()
        // Open the marker file now: signal handlers may only use the simplest system calls.
        let path = crashMarker.path + ".partial"
        markerDescriptor = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0o644)
        NSSetUncaughtExceptionHandler { exception in
            let text = "Uncaught exception: \(exception.name.rawValue): \(exception.reason ?? "")\n"
                + exception.callStackSymbols.joined(separator: "\n")
            try? text.write(to: Reports.crashMarker, atomically: true, encoding: .utf8)
        }
        for signalNumber in [SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE, SIGTRAP] {
            signal(signalNumber, crashSignalHandler)
        }
        if #available(macOS 12.0, *) {
            MXMetricManager.shared.add(MetricReceiver.shared)
        }
    }

    fileprivate static func writeSignalCrash(_ number: Int32) {
        let fd = markerDescriptor
        guard fd >= 0 else { return }
        var header = "Crash signal \(number)\n"
        header.withUTF8 { buffer in _ = write(fd, buffer.baseAddress, buffer.count) }
        var frames = [UnsafeMutableRawPointer?](repeating: nil, count: 64)
        let count = backtrace(&frames, 64)
        backtrace_symbols_fd(&frames, count, fd)
        fsync(fd)
    }

    private static func collectPreviousCrash() {
        let partial = URL(fileURLWithPath: crashMarker.path + ".partial")
        var body: String? = try? String(contentsOf: crashMarker)
        if body == nil, let partialText = try? String(contentsOf: partial), !partialText.isEmpty {
            body = partialText
        }
        try? FileManager.default.removeItem(at: crashMarker)
        try? FileManager.default.removeItem(at: partial)
        guard let body, !body.isEmpty else { return }
        let recent = (try? String(contentsOf: logFile)).map { String($0.suffix(20_000)) } ?? ""
        save(kind: "Crash", body: body + "\n\n--- Recent log ---\n" + recent)
    }

    // MARK: Reports on disk

    static func environment() -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        var model = [CChar](repeating: 0, count: 64)
        var size = model.count
        sysctlbyname("hw.model", &model, &size, nil, 0)
        let settings = PlaybackSettings.current
        return """
        CHUD STREAMS for Mac \(version) (\(build))
        macOS \(ProcessInfo.processInfo.operatingSystemVersionString)
        Mac: \(String(cString: model)), \(ProcessInfo.processInfo.processorCount) cores, \(ProcessInfo.processInfo.physicalMemory >> 30) GB
        Player: \(settings.engine.rawValue), renderer \(settings.renderer.rawValue), hardware decoding \(settings.hardwareDecoding ? "on" : "off")
        """
    }

    @discardableResult
    static func save(kind: String, body: String) -> CrashReport? {
        let date = Date()
        let id = "\(kind.lowercased())-\(Int(date.timeIntervalSince1970))"
        let text = Redactor.scrub(environment() + "\n\n" + body)
        guard let data = text.data(using: .utf8),
              let packed = try? (data as NSData).compressed(using: .lzfse) as Data else { return nil }
        let file = folder.appendingPathComponent(id + ".txt.lzfse")
        try? packed.write(to: file, options: .atomic)
        return CrashReport(id: id, date: date, kind: kind, file: file, uploaded: false)
    }

    /// A report the viewer asks for (Settings > Reports > Save a report now).
    @discardableResult
    static func saveSnapshot(note: String) -> CrashReport? {
        let lines = ErrorLog.recent().suffix(200).map { "\($0.date.formatted(date: .omitted, time: .standard)) [\($0.level)] \($0.text)" }
        return save(kind: "Report", body: (note.isEmpty ? "" : "Note: \(note)\n\n") + "--- Recent log ---\n" + lines.joined(separator: "\n"))
    }

    static func all() -> [CrashReport] {
        let uploaded = Set(UserDefaults.standard.stringArray(forKey: uploadedKey) ?? [])
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.creationDateKey])) ?? []
        return files.filter { $0.lastPathComponent.hasSuffix(".txt.lzfse") }.compactMap { file in
            let id = file.lastPathComponent.replacingOccurrences(of: ".txt.lzfse", with: "")
            let parts = id.split(separator: "-")
            guard parts.count >= 2, let seconds = Double(parts.last ?? "") else { return nil }
            return CrashReport(id: id, date: Date(timeIntervalSince1970: seconds), kind: String(parts[0]).capitalized,
                               file: file, uploaded: uploaded.contains(id))
        }
        .sorted { $0.date > $1.date }
    }

    static func text(of report: CrashReport) -> String {
        guard let packed = try? Data(contentsOf: report.file),
              let data = try? (packed as NSData).decompressed(using: .lzfse) as Data else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func delete(_ report: CrashReport) {
        try? FileManager.default.removeItem(at: report.file)
    }

    /// Puts every report, uncompressed, in one zip in Downloads.
    static func export() throws -> URL {
        let staging = FileManager.default.temporaryDirectory.appendingPathComponent("CHUD STREAMS reports", isDirectory: true)
        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        for report in all() {
            try text(of: report).write(to: staging.appendingPathComponent(report.id + ".txt"), atomically: true, encoding: .utf8)
        }
        let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let zip = downloads.appendingPathComponent("chud-streams-reports-\(Int(Date().timeIntervalSince1970)).zip")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = ["-c", "-k", "--keepParent", staging.path, zip.path]
        try process.run()
        process.waitUntilExit()
        return zip
    }

    // MARK: GitHub

    static var repository: String? {
        let value = UserDefaults.standard.string(forKey: repoKey)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return value.contains("/") ? value : nil
    }

    /// Files the report as an issue in the viewer's repository and returns its address.
    static func upload(_ report: CrashReport) async throws -> URL {
        guard let token = Secrets.get(.github) else {
            throw ServiceError(message: "Add a GitHub token in Settings > Services first.")
        }
        guard let repository else {
            throw ServiceError(message: "Enter the repository to send reports to, like yourname/chud-streams-reports.")
        }
        guard let url = URL(string: "\(ServiceURL.github)/repos/\(repository)/issues") else {
            throw ServiceError(message: "That repository name doesn't look right.")
        }
        var body = text(of: report)
        if body.count > 60_000 { body = String(body.prefix(60_000)) + "\n…(truncated)" }
        let payload: [String: Any] = [
            "title": "[CHUD STREAMS Mac] \(report.title)",
            "body": "```\n\(body)\n```",
            "labels": ["crash-report"],
        ]
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 || code == 403 {
            throw ServiceError(message: "GitHub didn't accept the token. It needs permission to create issues in \(repository).")
        }
        if code == 404 { throw ServiceError(message: "GitHub can't find \(repository), or the token can't see it.") }
        if code == 410 { throw ServiceError(message: "Issues are turned off in \(repository).") }
        guard (200...299).contains(code),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let link = str(root, "html_url"), let page = URL(string: link) else {
            throw ServiceError(message: "GitHub answered with error \(code).")
        }
        var uploaded = UserDefaults.standard.stringArray(forKey: uploadedKey) ?? []
        uploaded.append(report.id)
        UserDefaults.standard.set(uploaded, forKey: uploadedKey)
        return page
    }

    /// Sends reports that haven't been sent yet, if automatic sending is on.
    static func uploadPendingIfEnabled() async {
        guard UserDefaults.standard.bool(forKey: autoUploadKey), Secrets.has(.github), repository != nil else { return }
        for report in all() where !report.uploaded && report.kind == "Crash" {
            _ = try? await upload(report)
        }
    }
}

private func crashSignalHandler(_ number: Int32) {
    Reports.writeSignalCrash(number)
    signal(number, SIG_DFL)
    raise(number)
}

/// Receives crash diagnostics macOS collects (delivered on a later launch).
@available(macOS 12.0, *)
private final class MetricReceiver: NSObject, MXMetricManagerSubscriber {
    static let shared = MetricReceiver()

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        for payload in payloads {
            let crashes = payload.crashDiagnostics ?? []
            let hangs = payload.hangDiagnostics ?? []
            guard !crashes.isEmpty || !hangs.isEmpty else { continue }
            let json = String(decoding: payload.jsonRepresentation(), as: UTF8.self)
            Reports.save(kind: crashes.isEmpty ? "Hang" : "Crash", body: "macOS diagnostic report\n\n" + String(json.prefix(200_000)))
        }
    }
}
