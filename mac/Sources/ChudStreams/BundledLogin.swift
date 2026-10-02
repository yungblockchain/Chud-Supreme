import Foundation

/// The provider login that ships with the app, so the first launch is already signed in.
/// Changing it in Settings replaces what is saved on this Mac. It is not sent anywhere except
/// that provider.
enum BundledLogin {
    static let server = "http://cool13535.wd.ness-8k-all.online"
    static let username = "b7850079f070"
    static let password = "bc69d28478"
    static let playlist = "http://cool13535.wd.ness-8k-all.online/get.php?username=b7850079f070&password=bc69d28478&type=m3u_plus&output=ts"

    static var source: Source {
        Source(
            id: Source.mainId,
            kind: .xtream,
            name: "cool13535",
            server: server,
            username: username
        )
    }
}
