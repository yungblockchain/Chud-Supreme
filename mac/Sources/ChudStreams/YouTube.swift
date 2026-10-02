import SwiftUI
import WebKit

// YouTube, separate from Infinity and from Xtream search.
// Search and trending use the baked-in Data API key. Your own recommendations, home
// algorithm and subscriptions are the YouTube site itself: sign in once in that page
// and the app keeps the session. A Data API key cannot read a private subscription list.

enum YouTubeMode: String, CaseIterable, Identifiable {
    case yours = "Your YouTube"
    case search = "Search"
    case popular = "Trending"
    var id: String { rawValue }
}

struct YouTubeVideo: Identifiable, Hashable {
    var id: String
    var title: String
    var channel: String
    var thumbnail: URL?
}

@MainActor
struct YouTubeView: View {
    @State private var mode: YouTubeMode = .yours
    @State private var query = ""
    @State private var videos: [YouTubeVideo] = []
    @State private var message = ""
    @State private var loading = false
    @State private var playing: String? = nil
    @State private var page = "https://www.youtube.com"

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                NeonTitle(text: "YouTube", size: 34)
                Spacer()
                Picker("Section", selection: $mode) {
                    ForEach(YouTubeMode.allCases) { item in
                        Text(item.rawValue).tag(item)
                    }
                }
                .pickerStyle(.segmented)
                .frame(width: 420)
            }
            if mode == .yours {
                yours
            } else if let playing {
                player(playing)
            } else {
                searchList
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onChange(of: mode) { value in
            playing = nil
            if value == .popular { Task { await loadPopular() } }
        }
    }

    private var yours: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Button("Home") { page = "https://www.youtube.com" }.buttonStyle(NeonButtonStyle(prominent: page.contains("youtube.com") && !page.contains("/feed")))
                Button("Subscriptions") { page = "https://www.youtube.com/feed/subscriptions" }.buttonStyle(NeonButtonStyle())
                Button("You") { page = "https://www.youtube.com/feed/you" }.buttonStyle(NeonButtonStyle())
            }
            Text("Sign in once on this page. Your recommendations, home algorithm and subscriptions stay in the app. This is not Infinity search and not Live TV search.")
                .font(NeonFont.body(13))
                .foregroundColor(Neon.textMuted)
            YouTubePage(address: page)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .neonPanel()
        }
    }

    private var searchList: some View {
        VStack(alignment: .leading, spacing: 12) {
            if mode == .search {
                HStack(spacing: 10) {
                    TextField("Search YouTube", text: $query)
                        .textFieldStyle(.plain)
                        .font(NeonFont.body(15))
                        .padding(10)
                        .neonPanel()
                        .onSubmit { Task { await loadSearch() } }
                    Button(loading ? "Searching…" : "Search") { Task { await loadSearch() } }
                        .buttonStyle(NeonButtonStyle(prominent: true))
                        .disabled(loading)
                }
            }
            if !message.isEmpty {
                Text(message).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(videos) { video in
                        Button { playing = video.id } label: {
                            HStack(spacing: 12) {
                                ZStack {
                                    Neon.surface
                                    if let thumbnail = video.thumbnail {
                                        AsyncImage(url: thumbnail) { image in
                                            image.resizable().scaledToFill()
                                        } placeholder: { Color.clear }
                                    }
                                }
                                .frame(width: 160, height: 90)
                                .clipped()
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(video.title).font(NeonFont.body(15, bold: true)).foregroundColor(Neon.text).lineLimit(2)
                                    Text(video.channel).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary).lineLimit(1)
                                }
                                Spacer()
                            }
                            .padding(8)
                            .neonPanel()
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .task {
            if mode == .popular && videos.isEmpty { await loadPopular() }
        }
    }

    private func player(_ id: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Button("Back to results") { playing = nil }.buttonStyle(NeonButtonStyle())
            YouTubePage(address: "https://www.youtube.com/embed/\(id)?autoplay=1")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .neonPanel()
        }
    }

    private func loadSearch() async {
        loading = true
        defer { loading = false }
        let found = await YouTubeAPI.search(query)
        videos = found
        message = found.isEmpty ? "No videos for that search." : ""
    }

    private func loadPopular() async {
        loading = true
        defer { loading = false }
        let found = await YouTubeAPI.popular()
        videos = found
        message = found.isEmpty ? "Trending didn't answer." : ""
    }
}

enum YouTubeAPI {
    static func search(_ query: String) async -> [YouTubeVideo] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2, let key = Secrets.get(.youtube) else { return [] }
        let q = trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? trimmed
        let link = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=20&q=\(q)&key=\(key)"
        return await videos(link, search: true)
    }

    static func popular() async -> [YouTubeVideo] {
        guard let key = Secrets.get(.youtube) else { return [] }
        let link = "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=20&regionCode=GB&key=\(key)"
        return await videos(link, search: false)
    }

    private static func videos(_ link: String, search: Bool) async -> [YouTubeVideo] {
        guard let url = URL(string: link) else { return [] }
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              (200...299).contains((response as? HTTPURLResponse)?.statusCode ?? 0),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = root["items"] as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            let identity = item["id"]
            let id = search ? ((identity as? [String: Any])?["videoId"] as? String) : (identity as? String)
            guard let id, let snippet = item["snippet"] as? [String: Any], let title = snippet["title"] as? String else { return nil }
            let thumbs = snippet["thumbnails"] as? [String: Any]
            let medium = (thumbs?["medium"] as? [String: Any])?["url"] as? String
            return YouTubeVideo(id: id, title: title, channel: (snippet["channelTitle"] as? String) ?? "", thumbnail: medium.flatMap { URL(string: $0) })
        }
    }
}

struct YouTubePage: NSViewRepresentable {
    let address: String

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let web = WKWebView(frame: .zero, configuration: configuration)
        web.setValue(false, forKey: "drawsBackground")
        return web
    }

    func updateNSView(_ web: WKWebView, context: Context) {
        guard context.coordinator.last != address, let url = URL(string: address) else { return }
        context.coordinator.last = address
        web.load(URLRequest(url: url))
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var last: String?
    }
}
