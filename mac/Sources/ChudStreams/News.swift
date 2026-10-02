import AppKit
import SwiftUI

/// RSS reader. Built-in feeds plus any address the viewer adds. Nothing here listens for
/// incoming connections, so the Mac firewall is not asked to allow the app.
struct NewsView: View {
    @State private var feeds: [NewsFeed] = NewsStore.load()
    @State private var draft = ""
    @State private var items: [NewsItem] = []
    @State private var message = "Loading feeds…"
    @State private var selected: String = NewsStore.load().first?.id ?? ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NeonTitle(text: "News", size: 34)
            HStack(spacing: 8) {
                ForEach(feeds) { feed in
                    Button(feed.title) {
                        selected = feed.id
                        Task { await load(feed) }
                    }
                    .buttonStyle(NeonButtonStyle())
                    .opacity(selected == feed.id ? 1 : 0.55)
                }
                Spacer()
            }
            HStack(spacing: 8) {
                TextField("Add a feed address", text: $draft)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 420)
                Button("Add feed") { addFeed() }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Text("Football, local news, anything with an RSS or Atom address. Click a headline to open it.")
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
            if items.isEmpty {
                Text(message).font(NeonFont.body(15)).foregroundColor(Neon.textMuted)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(items) { item in
                            Button { open(item) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.title)
                                        .font(NeonFont.body(16, bold: true))
                                        .foregroundColor(Neon.cyan)
                                        .multilineTextAlignment(.leading)
                                    if !item.summary.isEmpty {
                                        Text(item.summary)
                                            .font(NeonFont.body(13))
                                            .foregroundColor(Neon.textSecondary)
                                            .lineLimit(3)
                                            .multilineTextAlignment(.leading)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(12)
                                .neonPanel()
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task {
            if let feed = feeds.first(where: { $0.id == selected }) ?? feeds.first {
                await load(feed)
            }
        }
    }

    private func addFeed() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme == "http" || url.scheme == "https" else {
            message = "That address needs to start with http:// or https://."
            return
        }
        let feed = NewsFeed(id: trimmed, title: url.host ?? "Feed", url: trimmed)
        feeds.append(feed)
        NewsStore.save(feeds)
        draft = ""
        selected = feed.id
        Task { await load(feed) }
    }

    private func load(_ feed: NewsFeed) async {
        message = "Loading \(feed.title)…"
        items = []
        guard let url = URL(string: feed.url) else { return }
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("ChudSupreme/1.0", forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard code < 400 else {
                message = "\(feed.title) answered \(code)."
                return
            }
            let parsed = RSSParser.items(in: data)
            items = parsed
            message = parsed.isEmpty ? "That feed had no stories." : ""
        } catch {
            message = "Couldn't reach \(feed.title)."
        }
    }

    private func open(_ item: NewsItem) {
        guard let url = URL(string: item.link) else { return }
        NSWorkspace.shared.open(url)
    }
}

private struct NewsFeed: Identifiable, Codable, Equatable {
    var id: String
    var title: String
    var url: String
}

private struct NewsItem: Identifiable {
    let id = UUID()
    let title: String
    let summary: String
    let link: String
}

private enum NewsStore {
    private static let key = "news.feeds.v1"

    static func load() -> [NewsFeed] {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([NewsFeed].self, from: data),
           !saved.isEmpty {
            return saved
        }
        return [
            NewsFeed(id: "bbc-football", title: "Football", url: "https://feeds.bbci.co.uk/sport/football/rss.xml"),
            NewsFeed(id: "bbc-news", title: "World", url: "https://feeds.bbci.co.uk/news/rss.xml"),
            NewsFeed(id: "bbc-london", title: "London", url: "https://feeds.bbci.co.uk/news/england/london/rss.xml"),
        ]
    }

    static func save(_ feeds: [NewsFeed]) {
        if let data = try? JSONEncoder().encode(feeds) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}

private final class RSSParser: NSObject, XMLParserDelegate {
    private var items: [NewsItem] = []
    private var fields: [String: String] = [:]
    private var text = ""
    private var inside = false

    static func items(in data: Data) -> [NewsItem] {
        let parser = RSSParser()
        let xml = XMLParser(data: data)
        xml.delegate = parser
        xml.parse()
        return parser.items
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        let name = elementName.lowercased()
        if name == "item" || name == "entry" {
            inside = true
            fields = [:]
        }
        text = ""
        if inside, name == "link", let href = attributeDict["href"], fields["link"] == nil {
            fields["link"] = href
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text += string
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let name = elementName.lowercased()
        if !inside { text = ""; return }
        if name == "item" || name == "entry" {
            let title = RSSParser.plain(fields["title"] ?? "")
            if !title.isEmpty {
                items.append(NewsItem(title: title, summary: RSSParser.plain(fields["description"] ?? fields["summary"] ?? ""), link: fields["link"] ?? ""))
            }
            inside = false
        } else if fields[name] == nil {
            fields[name] = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        text = ""
    }

    private static func plain(_ html: String) -> String {
        let stripped = html.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
        return stripped.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
