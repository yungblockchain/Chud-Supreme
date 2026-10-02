import Foundation

/// The provider login that ships with the app, so the first launch is already signed in.
/// Changing it in Settings replaces what is saved on this Mac.
enum BundledLogin {
    static let server = "http://www.cool13535.wd.ness-8k-all.online"
    static let username = "b7850079f070"
    static let password = "bc69d28478"
    static let playlist = "http://cool13535.wd.ness-8k-all.online/get.php?username=b7850079f070&password=bc69d28478&type=m3u_plus&output=ts"

    static var source: Source {
        Source(id: Source.mainId, kind: .xtream, name: "cool13535", server: server, username: username)
    }

    /// Puts the provider login in place. A host saved without www is corrected. A password you
    /// already changed in Settings is left alone. The screenshot tour does not call this.
    static func apply(to list: inout [Source]) {
        if let index = list.firstIndex(where: { source in
            source.kind == .xtream && source.username == username && (source.server ?? "").contains("cool13535") && source.server != server
        }) {
            list[index].server = server
            Keychain.write(password, account: list[index].keychainAccount)
            CatalogCache.remove(source: list[index].id)
            return
        }
        if list.isEmpty {
            let seeded = source
            Keychain.write(password, account: seeded.keychainAccount)
            list = [seeded]
            return
        }
        if let existing = list.first(where: { $0.username == username && $0.server == server }),
           (Keychain.read(account: existing.keychainAccount) ?? "").isEmpty {
            Keychain.write(password, account: existing.keychainAccount)
            CatalogCache.remove(source: existing.id)
        }
    }
}
