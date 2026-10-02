import Foundation

/// On-disk home for catalogues and saved files. A previous CHUD STREAMS folder is picked up
/// once so sign-in caches are not thrown away when the app is renamed.
enum AppSupport {
    static var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let modern = base.appendingPathComponent("Chud Supreme", isDirectory: true)
        let legacy = base.appendingPathComponent("CHUD STREAMS", isDirectory: true)
        let files = FileManager.default
        if !files.fileExists(atPath: modern.path), files.fileExists(atPath: legacy.path) {
            try? files.moveItem(at: legacy, to: modern)
        }
        try? files.createDirectory(at: modern, withIntermediateDirectories: true)
        return modern
    }

    /// Caches directory, with the same one-time rename.
    static func caches(_ name: String) -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let modern = base.appendingPathComponent("Chud Supreme", isDirectory: true).appendingPathComponent(name, isDirectory: true)
        let legacy = base.appendingPathComponent("CHUD STREAMS", isDirectory: true).appendingPathComponent(name, isDirectory: true)
        let files = FileManager.default
        if !files.fileExists(atPath: modern.path), files.fileExists(atPath: legacy.path) {
            try? files.createDirectory(at: modern.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? files.moveItem(at: legacy, to: modern)
        }
        try? files.createDirectory(at: modern, withIntermediateDirectories: true)
        return modern
    }
}
