import SwiftUI

/// Live scores from API-Sports. When a channel name contains one of the teams, it can be played
/// straight from the fixture. The key is the viewer's own API-Sports key.
struct MatchCentreView: View {
    @EnvironmentObject private var model: AppModel
    @State private var fixtures: [LiveFixture] = []
    @State private var message = "Loading live fixtures…"
    @State private var loading = false

    private static let key = "6adb1a1f9ea8091f6f5fb16e4abc0fc5"

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                NeonTitle(text: "Match centre", size: 34)
                Spacer()
                Button("Refresh") { Task { await load() } }
                    .buttonStyle(NeonButtonStyle())
                    .disabled(loading)
            }
            Text("Live scores. If your provider has a channel named after one of the clubs, Play opens it.")
                .font(NeonFont.body(15))
                .foregroundColor(Neon.textSecondary)
            if fixtures.isEmpty {
                Text(message)
                    .font(NeonFont.body(15))
                    .foregroundColor(Neon.textMuted)
                    .padding(.top, 12)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(fixtures) { fixture in
                            fixtureRow(fixture)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .task { await load() }
    }

    private func fixtureRow(_ fixture: LiveFixture) -> some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(fixture.league)
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.magenta)
                Text("\(fixture.home)  \(fixture.score)  \(fixture.away)")
                    .font(NeonFont.display(18))
                    .foregroundColor(Neon.text)
                Text(fixture.status)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
            }
            Spacer(minLength: 8)
            if let channel = channel(matching: fixture) {
                Button("Play") { model.playLive(channel, in: model.catalog?.all(.live) ?? [channel]) }
                    .buttonStyle(NeonButtonStyle())
            }
        }
        .padding(14)
        .neonPanel()
    }

    private func channel(matching fixture: LiveFixture) -> MediaItem? {
        let live = model.catalog?.all(.live) ?? []
        let home = fixture.home.lowercased()
        let away = fixture.away.lowercased()
        return live.first { item in
            let name = item.name.lowercased()
            return name.contains(home) || name.contains(away)
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        guard let url = URL(string: "https://v3.football.api-sports.io/fixtures?live=all") else { return }
        var request = URLRequest(url: url)
        request.setValue(Self.key, forHTTPHeaderField: "x-apisports-key")
        request.timeoutInterval = 20
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard code < 400,
                  let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let rows = root["response"] as? [[String: Any]] else {
                message = "Live scores didn't answer (\(code)). The free plan allows a limited number of calls a day."
                fixtures = []
                return
            }
            fixtures = rows.compactMap(LiveFixture.init(json:))
            message = fixtures.isEmpty ? "No live fixtures right now." : ""
        } catch {
            message = "Couldn't reach the scores service. Check your connection."
            fixtures = []
        }
    }
}

private struct LiveFixture: Identifiable {
    let id: Int
    let league: String
    let home: String
    let away: String
    let score: String
    let status: String

    init?(json: [String: Any]) {
        let fixture = json["fixture"] as? [String: Any]
        let teams = json["teams"] as? [String: Any]
        let goals = json["goals"] as? [String: Any]
        let leagueJSON = json["league"] as? [String: Any]
        let statusJSON = fixture?["status"] as? [String: Any]
        guard let id = fixture?["id"] as? Int,
              let homeJSON = teams?["home"] as? [String: Any],
              let awayJSON = teams?["away"] as? [String: Any],
              let home = homeJSON["name"] as? String,
              let away = awayJSON["name"] as? String else { return nil }
        self.id = id
        self.home = home
        self.away = away
        let homeGoals = goals?["home"] as? Int
        let awayGoals = goals?["away"] as? Int
        if let homeGoals, let awayGoals {
            score = "\(homeGoals) – \(awayGoals)"
        } else {
            score = "vs"
        }
        let short = statusJSON?["short"] as? String ?? "LIVE"
        let elapsed = statusJSON?["elapsed"] as? Int
        status = elapsed == nil ? short : "\(short) \(elapsed ?? 0)'"
        let leagueName = leagueJSON?["name"] as? String ?? "Football"
        let country = leagueJSON?["country"] as? String ?? ""
        league = country.isEmpty ? leagueName : "\(country) · \(leagueName)"
    }
}
