import Foundation
import SwiftUI

// What the viewer builds up over time: watch history (continue watching), their library of saved
// films and shows, favourite channel groups, and how they've arranged categories. Saved as small
// JSON files in Application Support so a reinstall of the app keeps them.

/// One thing the viewer watched, for Continue watching.
struct HistoryEntry: Codable, Identifiable, Hashable {
    var id: String
    var item: MediaItem
    /// For series: the episode that was playing, and the series it belongs to.
    var episode: Episode? = nil
    var title: String
    var subtitle: String? = nil
    var image: String? = nil
    var position: Double = 0
    var duration: Double = 0
    var updatedAt: Date = Date()

    var progress: Double { duration > 0 ? min(1, max(0, position / duration)) : 0 }
    var isLive: Bool { item.kind == .live }
}

struct FavouriteGroup: Codable, Identifiable, Hashable {
    static let defaultId = "favourites"
    var id: String
    var name: String
    var items: [MediaItem]
    var symbol: String = "star.fill"
}

/// A viewer's arrangement of one source's categories for one kind of content.
struct CategoryArrangement: Codable, Hashable {
    var order: [String] = []
    var hidden: Set<String> = []
}

@MainActor
final class UserData: ObservableObject {
    @Published private(set) var history: [HistoryEntry] = []
    @Published private(set) var library: [MediaItem] = []
    @Published private(set) var groups: [FavouriteGroup] = []
    @Published private(set) var arrangements: [String: CategoryArrangement] = [:]

    private var saveTask: Task<Void, Never>? = nil

    private static var folder: URL {
        let url = CatalogCache.directory.appendingPathComponent("User", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    init() {
        history = UserData.read([HistoryEntry].self, "history") ?? []
        library = UserData.read([MediaItem].self, "library") ?? []
        groups = UserData.read([FavouriteGroup].self, "groups") ?? []
        arrangements = UserData.read([String: CategoryArrangement].self, "categories") ?? [:]
        if groups.isEmpty {
            // Favourites saved by earlier versions of the app.
            var legacy: [MediaItem] = []
            if let data = UserDefaults.standard.data(forKey: "favorites"),
               let saved = try? JSONDecoder().decode([MediaItem].self, from: data) {
                legacy = saved
            }
            groups = [FavouriteGroup(id: FavouriteGroup.defaultId, name: "Favourites", items: legacy)]
            // Films and series that were favourites belong in the library too.
            for item in legacy where item.kind != .live && !library.contains(where: { $0.id == item.id }) {
                library.append(item)
            }
            scheduleSave()
        }
    }

    // MARK: Persistence

    private static func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        let url = folder.appendingPathComponent(name + ".json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private static func write<T: Encodable>(_ value: T, _ name: String) {
        let url = folder.appendingPathComponent(name + ".json")
        if let data = try? JSONEncoder().encode(value) { try? data.write(to: url, options: .atomic) }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 600_000_000)
            guard let self, !Task.isCancelled else { return }
            self.saveNow()
        }
    }

    func saveNow() {
        UserData.write(history, "history")
        UserData.write(library, "library")
        UserData.write(groups, "groups")
        UserData.write(arrangements, "categories")
    }

    // MARK: History

    /// The ten most recent things watched, newest first.
    var continueWatching: [HistoryEntry] {
        Array(history.filter { entry in
            entry.isLive || entry.duration <= 0 || entry.progress < 0.95
        }.prefix(10))
    }

    func record(_ entry: HistoryEntry) {
        var updated = entry
        updated.updatedAt = Date()
        // A series keeps one entry (its latest episode).
        let key = entry.episode != nil ? "series|\(entry.item.id)" : entry.id
        updated.id = key
        history.removeAll { $0.id == key }
        history.insert(updated, at: 0)
        if history.count > 60 { history.removeLast(history.count - 60) }
        scheduleSave()
    }

    func updateProgress(id: String, position: Double, duration: Double) {
        guard let index = history.firstIndex(where: { $0.id == id }) else { return }
        history[index].position = position
        history[index].duration = duration
        history[index].updatedAt = Date()
        scheduleSave()
    }

    func removeHistory(_ entry: HistoryEntry) {
        history.removeAll { $0.id == entry.id }
        scheduleSave()
    }

    func clearHistory() {
        history = []
        scheduleSave()
    }

    // MARK: Library (saved films and shows)

    func inLibrary(_ item: MediaItem) -> Bool { library.contains { $0.id == item.id } }

    func toggleLibrary(_ item: MediaItem) {
        if inLibrary(item) {
            library.removeAll { $0.id == item.id }
        } else {
            library.insert(item, at: 0)
        }
        scheduleSave()
    }

    func moveLibrary(from source: IndexSet, to destination: Int) {
        library.move(fromOffsets: source, toOffset: destination)
        scheduleSave()
    }

    // MARK: Favourite groups

    var favourites: FavouriteGroup {
        groups.first { $0.id == FavouriteGroup.defaultId } ?? FavouriteGroup(id: FavouriteGroup.defaultId, name: "Favourites", items: [])
    }

    func isFavourite(_ item: MediaItem) -> Bool {
        favourites.items.contains { $0.id == item.id }
    }

    func groupsContaining(_ item: MediaItem) -> [FavouriteGroup] {
        groups.filter { group in group.items.contains { $0.id == item.id } }
    }

    func toggleFavourite(_ item: MediaItem) {
        toggle(item, inGroup: FavouriteGroup.defaultId)
    }

    func toggle(_ item: MediaItem, inGroup groupId: String) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        if let position = groups[index].items.firstIndex(where: { $0.id == item.id }) {
            groups[index].items.remove(at: position)
        } else {
            groups[index].items.append(item)
        }
        scheduleSave()
    }

    @discardableResult
    func createGroup(named name: String, symbol: String = "folder.fill") -> FavouriteGroup {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let group = FavouriteGroup(id: UUID().uuidString, name: trimmed.isEmpty ? "New group" : trimmed, items: [], symbol: symbol)
        groups.append(group)
        scheduleSave()
        return group
    }

    func renameGroup(_ id: String, to name: String) {
        guard let index = groups.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        groups[index].name = trimmed
        scheduleSave()
    }

    func deleteGroup(_ id: String) {
        guard id != FavouriteGroup.defaultId else { return }
        groups.removeAll { $0.id == id }
        scheduleSave()
    }

    func moveGroups(from source: IndexSet, to destination: Int) {
        groups.move(fromOffsets: source, toOffset: destination)
        scheduleSave()
    }

    func moveItems(inGroup groupId: String, from source: IndexSet, to destination: Int) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }) else { return }
        groups[index].items.move(fromOffsets: source, toOffset: destination)
        scheduleSave()
    }

    func moveItem(inGroup groupId: String, itemId: String, by step: Int) {
        guard let index = groups.firstIndex(where: { $0.id == groupId }),
              let position = groups[index].items.firstIndex(where: { $0.id == itemId }) else { return }
        let target = position + step
        guard target >= 0, target < groups[index].items.count else { return }
        groups[index].items.swapAt(position, target)
        scheduleSave()
    }

    func moveGroup(_ groupId: String, by step: Int) {
        guard let position = groups.firstIndex(where: { $0.id == groupId }) else { return }
        let target = position + step
        guard target >= 0, target < groups.count else { return }
        groups.swapAt(position, target)
        scheduleSave()
    }

    // MARK: Category arrangement

    private func arrangementKey(_ sourceId: String, _ kind: ContentKind) -> String { "\(sourceId)|\(kind.rawValue)" }

    func arrangement(_ sourceId: String, _ kind: ContentKind) -> CategoryArrangement {
        arrangements[arrangementKey(sourceId, kind)] ?? CategoryArrangement()
    }

    /// The provider's categories in the viewer's order, without hidden ones (unless asked).
    func arranged(_ categories: [ContentCategory], source sourceId: String, kind: ContentKind, includeHidden: Bool = false) -> [ContentCategory] {
        let setup = arrangement(sourceId, kind)
        var position: [String: Int] = [:]
        for (index, id) in setup.order.enumerated() { position[id] = index }
        let ordered = categories.enumerated().sorted { a, b in
            let pa = position[a.element.id] ?? (100_000 + a.offset)
            let pb = position[b.element.id] ?? (100_000 + b.offset)
            return pa < pb
        }.map { $0.element }
        return includeHidden ? ordered : ordered.filter { !setup.hidden.contains($0.id) }
    }

    func setOrder(_ ids: [String], source sourceId: String, kind: ContentKind) {
        var setup = arrangement(sourceId, kind)
        setup.order = ids
        arrangements[arrangementKey(sourceId, kind)] = setup
        scheduleSave()
    }

    func setHidden(_ id: String, hidden: Bool, source sourceId: String, kind: ContentKind) {
        var setup = arrangement(sourceId, kind)
        if hidden { setup.hidden.insert(id) } else { setup.hidden.remove(id) }
        arrangements[arrangementKey(sourceId, kind)] = setup
        scheduleSave()
    }

    func resetArrangement(source sourceId: String, kind: ContentKind) {
        arrangements[arrangementKey(sourceId, kind)] = nil
        scheduleSave()
    }
}
