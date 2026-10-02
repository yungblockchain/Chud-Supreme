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

    /// Puts the provider login in place on every launch. A host saved without www is corrected,
    /// and the password is written again so a missed keychain prompt cannot leave the app signed out.
    static func apply(to list: inout [Source]) {
        if let index = list.firstIndex(where: { source in
            source.id == Source.mainId || (source.username == username && (source.server ?? "").contains("cool13535"))
        }) {
            list[index].kind = .xtream
            list[index].server = server
            list[index].username = username
            if list[index].name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                list[index].name = "cool13535"
            }
            Keychain.write(password, account: list[index].keychainAccount)
            return
        }
        let seeded = source
        Keychain.write(password, account: seeded.keychainAccount)
        list.insert(seeded, at: 0)
    }
}
