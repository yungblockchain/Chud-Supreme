import AppKit
import SwiftUI

// Markets: a read-only crypto tracker.
// - Memecoins from DEX Screener's public API (no key needed). Limits: 60 requests a minute for
//   boosts and profiles, 300 for pairs, tokens and search. Pump.fun tokens come from the same
//   data (Solana tokens on pump.fun's exchanges, or mints ending in "pump"), since pump.fun and
//   GMGN don't publish a documented public API.
// - Top coins from CoinGecko's free API, or CoinMarketCap when the viewer saved a key.
// - Robinhood: coins Robinhood Crypto lists (a hand-curated list), priced by CoinGecko.
// - Hyperliquid perpetuals from Hyperliquid's public info endpoint.
// Charts are in MarketCharts.swift.
// Nothing here trades, signs or touches a wallet. The Telegram buttons only copy an address,
// open a t.me link, or send a text message to the viewer's own bot chat.

struct SocialLink: Hashable {
    let label: String
    let url: URL
}

struct MarketPair: Identifiable, Hashable {
    let chainId: String
    let dexId: String
    let pairAddress: String
    let url: String?
    let baseAddress: String
    let baseName: String
    let baseSymbol: String
    let priceUsd: Double?
    let change5m: Double?
    let change1h: Double?
    let change6h: Double?
    let change24h: Double?
    let volume24h: Double?
    let liquidityUsd: Double?
    let marketCap: Double?
    let fdv: Double?
    let buys24h: Int?
    let sells24h: Int?
    let createdAt: Date?
    var imageUrl: String?
    var boosts: Int?
    var description: String?
    var links: [SocialLink]

    var id: String { "\(chainId):\(baseAddress.lowercased())" }
    var watchKey: String { "\(chainId):\(baseAddress)" }
    var isPumpFun: Bool { chainId == "solana" && (dexId.lowercased().contains("pump") || baseAddress.hasSuffix("pump")) }

    // Non-optional values for sorting table columns (missing values sort last).
    var sortPrice: Double { priceUsd ?? -1 }
    var sort5m: Double { change5m ?? -.greatestFiniteMagnitude }
    var sort1h: Double { change1h ?? -.greatestFiniteMagnitude }
    var sort24h: Double { change24h ?? -.greatestFiniteMagnitude }
    var sortMarketCap: Double { marketCap ?? fdv ?? -1 }
    var sortLiquidity: Double { liquidityUsd ?? -1 }
    var sortVolume: Double { volume24h ?? -1 }
}

struct TokenRef: Hashable {
    let chainId: String
    let tokenAddress: String
    var icon: String? = nil
    var description: String? = nil
    var boosts: Int? = nil

    var key: String { "\(chainId):\(tokenAddress.lowercased())" }

    static func fromWatchKey(_ key: String) -> TokenRef? {
        guard let colon = key.firstIndex(of: ":") else { return nil }
        let chain = String(key[..<colon])
        let address = String(key[key.index(after: colon)...])
        return chain.isEmpty || address.isEmpty ? nil : TokenRef(chainId: chain, tokenAddress: address)
    }
}

enum DexScreener {
    private static let base = "https://api.dexscreener.com"

    static func topBoosted() async throws -> [TokenRef] { refs(try await get("/token-boosts/top/v1")) }
    static func latestBoosted() async throws -> [TokenRef] { refs(try await get("/token-boosts/latest/v1")) }
    static func latestProfiles() async throws -> [TokenRef] { refs(try await get("/token-profiles/latest/v1")) }

    static func search(_ query: String) async throws -> [MarketPair] {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        let encoded = query.addingPercentEncoding(withAllowedCharacters: allowed) ?? query
        let root = try await get("/latest/dex/search?q=\(encoded)")
        let pairs = (root as? [String: Any])?["pairs"] as? [Any] ?? []
        return pairs.compactMap(parsePair)
    }

    /// Market data for each token, in the order given, using each token's deepest pool.
    static func tokens(_ refs: [TokenRef]) async throws -> [MarketPair] {
        guard !refs.isEmpty else { return [] }
        var best: [String: MarketPair] = [:]
        let byChain = Dictionary(grouping: refs, by: { $0.chainId })
        for (chain, chainRefs) in byChain {
            var seen = Set<String>()
            let addresses = chainRefs.map(\.tokenAddress).filter { seen.insert($0).inserted }
            for start in stride(from: 0, to: addresses.count, by: 30) {
                let chunk = addresses[start..<min(start + 30, addresses.count)]
                let list = try await get("/tokens/v1/\(chain)/\(chunk.joined(separator: ","))") as? [Any] ?? []
                for pair in list.compactMap(parsePair) {
                    if let current = best[pair.id], (current.liquidityUsd ?? 0) >= (pair.liquidityUsd ?? 0) { continue }
                    best[pair.id] = pair
                }
            }
        }
        var seenKeys = Set<String>()
        return refs.compactMap { ref -> MarketPair? in
            guard var pair = best[ref.key], seenKeys.insert(ref.key).inserted else { return nil }
            if pair.imageUrl == nil { pair.imageUrl = ref.icon }
            pair.description = ref.description
            if pair.boosts == nil { pair.boosts = ref.boosts }
            return pair
        }
    }

    private static func refs(_ root: Any) -> [TokenRef] {
        let items: [Any]
        if let list = root as? [Any] { items = list } else if let one = root as? [String: Any] { items = [one] } else { items = [] }
        var seen = Set<String>()
        return items.compactMap { raw -> TokenRef? in
            guard let item = raw as? [String: Any],
                  let chain = str(item, "chainId"),
                  let address = str(item, "tokenAddress") else { return nil }
            let ref = TokenRef(
                chainId: chain,
                tokenAddress: address,
                icon: str(item, "icon"),
                description: str(item, "description"),
                boosts: num(item, "totalAmount").map { Int($0) }
            )
            return seen.insert(ref.key).inserted ? ref : nil
        }
    }

    private static func parsePair(_ raw: Any) -> MarketPair? {
        guard let pair = raw as? [String: Any],
              let baseToken = pair["baseToken"] as? [String: Any],
              let chain = str(pair, "chainId"),
              let address = str(baseToken, "address") else { return nil }
        let change = pair["priceChange"] as? [String: Any]
        let volume = pair["volume"] as? [String: Any]
        let txns = (pair["txns"] as? [String: Any])?["h24"] as? [String: Any]
        let info = pair["info"] as? [String: Any]
        var links: [SocialLink] = []
        for site in info?["websites"] as? [Any] ?? [] {
            if let entry = site as? [String: Any], let link = str(entry, "url"), let url = URL(string: link) {
                links.append(SocialLink(label: str(entry, "label") ?? "Website", url: url))
            }
        }
        for social in info?["socials"] as? [Any] ?? [] {
            if let entry = social as? [String: Any], let link = str(entry, "url"), let url = URL(string: link) {
                links.append(SocialLink(label: (str(entry, "type") ?? "Link").capitalized, url: url))
            }
        }
        return MarketPair(
            chainId: chain,
            dexId: str(pair, "dexId") ?? "",
            pairAddress: str(pair, "pairAddress") ?? "",
            url: str(pair, "url"),
            baseAddress: address,
            baseName: str(baseToken, "name") ?? "",
            baseSymbol: str(baseToken, "symbol") ?? "?",
            priceUsd: num(pair, "priceUsd"),
            change5m: num(change, "m5"),
            change1h: num(change, "h1"),
            change6h: num(change, "h6"),
            change24h: num(change, "h24"),
            volume24h: num(volume, "h24"),
            liquidityUsd: num(pair["liquidity"] as? [String: Any], "usd"),
            marketCap: num(pair, "marketCap"),
            fdv: num(pair, "fdv"),
            buys24h: num(txns, "buys").map { Int($0) },
            sells24h: num(txns, "sells").map { Int($0) },
            createdAt: num(pair, "pairCreatedAt").map { Date(timeIntervalSince1970: $0 / 1000) },
            imageUrl: str(info, "imageUrl"),
            boosts: num(pair["boosts"] as? [String: Any], "active").map { Int($0) },
            description: nil,
            links: links
        )
    }

    /// Throws on network errors and rate limits so the screen can say the update failed.
    private static func get(_ path: String) async throws -> Any {
        guard let url = URL(string: base + path) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ChudStreams/1.0 (Macintosh)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let code = (response as? HTTPURLResponse)?.statusCode, (200...299).contains(code) else {
            throw URLError(.badServerResponse)
        }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }
}

func num(_ dict: [String: Any]?, _ key: String) -> Double? {
    guard let value = dict?[key] else { return nil }
    if let number = value as? NSNumber { return number.doubleValue }
    if let text = value as? String { return Double(text) }
    return nil
}

// MARK: HTTP for coins, perps and charts

enum MarketHTTPError: Error {
    case status(Int)
    case badData

    var statusCode: Int? {
        if case .status(let code) = self { return code }
        return nil
    }
}

/// JSON requests with 15-second timeouts and a User-Agent. Any non-2xx reply (rate limits
/// included) throws, so screens can say the update failed.
enum MarketHTTP {
    static let userAgent = "ChudStreams/1.0 (Macintosh)"

    static func get(_ address: String, headers: [String: String] = [:]) async throws -> Any {
        guard let url = URL(string: address) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        return try await send(request)
    }

    static func post(_ address: String, body: [String: Any]) async throws -> Any {
        guard let url = URL(string: address) else { throw URLError(.badURL) }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        return false
    }

    private static func send(_ request: URLRequest) async throws -> Any {
        let (data, response) = try await URLSession.shared.data(for: request)
        let code: Int = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200...299).contains(code) else { throw MarketHTTPError.status(code) }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }
}

// MARK: Big coins (CoinGecko, CoinMarketCap)

enum MarketCoins {
    /// Hand-curated: CoinGecko ids of coins Robinhood Crypto lists in the US. Robinhood has no
    /// public API for its coin list, so this only has coins known to be listed. Check
    /// robinhood.com/us/en/crypto and update it when Robinhood adds or removes coins.
    static let robinhoodIds: [String] = [
        "bitcoin", "ethereum", "solana", "dogecoin", "shiba-inu", "pepe", "bonk", "dogwifcoin",
        "avalanche-2", "chainlink", "cardano", "ripple", "litecoin", "bitcoin-cash", "stellar",
        "uniswap", "aave", "compound-governance-token", "tezos", "ethereum-classic",
    ]

    private static let coinGeckoMarkets = "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=100&page=1&price_change_percentage=1h,24h,7d&sparkline=false"

    /// Top 100 by market cap: CoinMarketCap when a key is saved (falling back to CoinGecko if
    /// that fails), otherwise CoinGecko.
    static func top() async throws -> MarketLoadResult {
        if let key = Secrets.get(.coinMarketCap) {
            do {
                let rows: [MarketRow] = try await coinMarketCap(key: key)
                if !rows.isEmpty { return MarketLoadResult(rows: rows, source: "CoinMarketCap") }
            } catch {
                if MarketHTTP.isCancellation(error) { throw error }
            }
        }
        let rows: [MarketRow] = try await coinGecko(ids: nil)
        return MarketLoadResult(rows: rows, source: "CoinGecko")
    }

    /// CoinGecko market data sorted by market cap; nil ids means the top 100.
    static func coinGecko(ids: [String]?) async throws -> [MarketRow] {
        guard let ids else { return try await coinGeckoPage(coinGeckoMarkets) }
        var rows: [MarketRow] = []
        for start in stride(from: 0, to: ids.count, by: 100) {
            let chunk: [String] = Array(ids[start..<min(start + 100, ids.count)])
            let page: [MarketRow] = try await coinGeckoPage(coinGeckoMarkets + "&ids=" + chunk.joined(separator: ","))
            rows.append(contentsOf: page)
        }
        return rows
    }

    private static func coinGeckoPage(_ address: String) async throws -> [MarketRow] {
        let root: Any = try await MarketHTTP.get(address)
        let list: [Any] = root as? [Any] ?? []
        return list.compactMap(parseCoinGecko)
    }

    private static func coinMarketCap(key: String) async throws -> [MarketRow] {
        let address = "https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/latest?limit=100&convert=USD"
        let root: Any = try await MarketHTTP.get(address, headers: ["X-CMC_PRO_API_KEY": key])
        let list: [Any] = (root as? [String: Any])?["data"] as? [Any] ?? []
        return list.compactMap(parseCoinMarketCap)
    }

    private static func parseCoinGecko(_ raw: Any) -> MarketRow? {
        guard let item = raw as? [String: Any], let id = str(item, "id") else { return nil }
        let symbol: String = (str(item, "symbol") ?? id).uppercased()
        let name: String = str(item, "name") ?? symbol
        var row = MarketRow(kind: .coin, id: "cg:" + id, symbol: symbol, name: name, subtitle: name)
        row.imageUrl = str(item, "image")
        row.priceUsd = num(item, "current_price")
        row.change1h = num(item, "price_change_percentage_1h_in_currency")
        row.change24h = num(item, "price_change_percentage_24h_in_currency") ?? num(item, "price_change_percentage_24h")
        row.change7d = num(item, "price_change_percentage_7d_in_currency")
        row.volume24h = num(item, "total_volume")
        row.marketCap = num(item, "market_cap")
        row.fdv = num(item, "fully_diluted_valuation")
        row.rank = int(item, "market_cap_rank")
        row.high24h = num(item, "high_24h")
        row.low24h = num(item, "low_24h")
        row.circulatingSupply = num(item, "circulating_supply")
        row.coinGeckoId = id
        return row
    }

    private static func parseCoinMarketCap(_ raw: Any) -> MarketRow? {
        guard let item = raw as? [String: Any], let cmcId = str(item, "id") else { return nil }
        let symbol: String = str(item, "symbol") ?? "?"
        let name: String = str(item, "name") ?? symbol
        let quote = (item["quote"] as? [String: Any])?["USD"] as? [String: Any]
        var row = MarketRow(kind: .coin, id: "cmc:" + cmcId, symbol: symbol, name: name, subtitle: name)
        row.imageUrl = "https://s2.coinmarketcap.com/static/img/coins/64x64/\(cmcId).png"
        row.priceUsd = num(quote, "price")
        row.change1h = num(quote, "percent_change_1h")
        row.change24h = num(quote, "percent_change_24h")
        row.change7d = num(quote, "percent_change_7d")
        row.volume24h = num(quote, "volume_24h")
        row.marketCap = num(quote, "market_cap")
        row.fdv = num(quote, "fully_diluted_market_cap")
        row.rank = int(item, "cmc_rank")
        row.circulatingSupply = num(item, "circulating_supply")
        row.coinMarketCapSlug = str(item, "slug")
        return row
    }
}

/// CoinMarketCap rows don't carry a CoinGecko id, which charts and the watchlist use. This looks
/// one up with CoinGecko's search (matching symbol, then name, then market-cap rank) and
/// remembers it for the session.
@MainActor
enum MarketCoinIds {
    private static var known: [String: String] = [:]

    private static func key(_ symbol: String, _ name: String) -> String {
        symbol.uppercased() + "|" + name.lowercased()
    }

    static func cached(symbol: String, name: String) -> String? {
        known[key(symbol, name)]
    }

    static func resolve(symbol: String, name: String) async throws -> String {
        let cacheKey: String = key(symbol, name)
        if let id = known[cacheKey] { return id }
        var found: String? = try await search(name, symbol: symbol, name: name)
        if found == nil && name.lowercased() != symbol.lowercased() {
            found = try await search(symbol, symbol: symbol, name: name)
        }
        guard let id = found else { throw MarketHTTPError.badData }
        known[cacheKey] = id
        return id
    }

    private static func search(_ text: String, symbol: String, name: String) async throws -> String? {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        let query: String = text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text
        let root: Any = try await MarketHTTP.get("https://api.coingecko.com/api/v3/search?query=" + query)
        let coins: [Any] = (root as? [String: Any])?["coins"] as? [Any] ?? []
        return best(coins, symbol: symbol, name: name)
    }

    private static func best(_ coins: [Any], symbol: String, name: String) -> String? {
        var bestId: String? = nil
        var bestScore: Int = Int.max
        for raw in coins {
            guard let coin = raw as? [String: Any], let id = str(coin, "id") else { continue }
            guard (str(coin, "symbol") ?? "").uppercased() == symbol.uppercased() else { continue }
            let rank: Int = int(coin, "market_cap_rank") ?? 1_000_000
            let exactName: Bool = (str(coin, "name") ?? "").lowercased() == name.lowercased()
            let score: Int = exactName ? rank : rank + 10_000_000
            if score < bestScore {
                bestScore = score
                bestId = id
            }
        }
        return bestId
    }
}

// MARK: Hyperliquid perps

enum MarketPerps {
    static let infoURL = "https://api.hyperliquid.xyz/info"

    /// Every listed perpetual, highest 24h volume first.
    static func all() async throws -> [MarketRow] {
        let root: Any = try await MarketHTTP.post(infoURL, body: ["type": "metaAndAssetCtxs"])
        guard let parts = root as? [Any], parts.count >= 2,
              let meta = parts[0] as? [String: Any],
              let universe = meta["universe"] as? [Any],
              let contexts = parts[1] as? [Any] else { throw MarketHTTPError.badData }
        var rows: [MarketRow] = []
        let count: Int = min(universe.count, contexts.count)
        for index in 0..<count {
            guard let asset = universe[index] as? [String: Any],
                  let context = contexts[index] as? [String: Any],
                  let made = makeRow(asset: asset, context: context) else { continue }
            rows.append(made)
        }
        return rows.sorted { $0.sortVolume > $1.sortVolume }
    }

    private static func makeRow(asset: [String: Any], context: [String: Any]) -> MarketRow? {
        guard let coin = str(asset, "name") else { return nil }
        if (asset["isDelisted"] as? Bool) == true { return nil }
        let leverage: Int? = int(asset, "maxLeverage")
        let subtitle: String = leverage.map { "Hyperliquid perp, up to \($0)x" } ?? "Hyperliquid perp"
        let mark: Double? = num(context, "markPx") ?? num(context, "midPx") ?? num(context, "oraclePx")
        let previous: Double? = num(context, "prevDayPx")
        var row = MarketRow(kind: .perp, id: "hl:" + coin, symbol: coin, name: coin + " perpetual", subtitle: subtitle)
        row.priceUsd = mark
        row.prevDayPrice = previous
        if let mark, let previous, previous > 0 {
            row.change24h = (mark - previous) / previous * 100
        }
        row.volume24h = num(context, "dayNtlVlm")
        if let mark, let size = num(context, "openInterest") {
            row.openInterestUsd = size * mark
        }
        row.fundingHourly = num(context, "funding")
        row.maxLeverage = leverage
        row.perpCoin = coin
        return row
    }
}

// MARK: Model

enum MarketKind: String {
    case dex
    case coin
    case perp
}

/// One table row: a DEX token, a big coin or a perp. Fields a kind doesn't have stay nil.
struct MarketRow: Identifiable, Hashable {
    let kind: MarketKind
    let id: String
    let symbol: String
    let name: String
    let subtitle: String
    var imageUrl: String? = nil
    var priceUsd: Double? = nil
    var change5m: Double? = nil
    var change1h: Double? = nil
    var change6h: Double? = nil
    var change24h: Double? = nil
    var change7d: Double? = nil
    var volume24h: Double? = nil
    var liquidityUsd: Double? = nil
    var marketCap: Double? = nil
    var fdv: Double? = nil
    var rank: Int? = nil
    var high24h: Double? = nil
    var low24h: Double? = nil
    var circulatingSupply: Double? = nil
    var prevDayPrice: Double? = nil
    var openInterestUsd: Double? = nil
    var fundingHourly: Double? = nil
    var maxLeverage: Int? = nil
    /// DEX rows only: the chain, and the full DEX Screener pair.
    var chainId: String? = nil
    var pair: MarketPair? = nil
    var coinGeckoId: String? = nil
    var coinMarketCapSlug: String? = nil
    var perpCoin: String? = nil

    static func dex(_ pair: MarketPair) -> MarketRow {
        var row = MarketRow(
            kind: .dex,
            id: "dex:" + pair.id,
            symbol: pair.baseSymbol,
            name: pair.baseName,
            subtitle: "\(MarketFormat.chain(pair.chainId))  \(pair.dexId)"
        )
        row.imageUrl = pair.imageUrl
        row.priceUsd = pair.priceUsd
        row.change5m = pair.change5m
        row.change1h = pair.change1h
        row.change6h = pair.change6h
        row.change24h = pair.change24h
        row.volume24h = pair.volume24h
        row.liquidityUsd = pair.liquidityUsd
        row.marketCap = pair.marketCap
        row.fdv = pair.fdv
        row.chainId = pair.chainId
        row.pair = pair
        return row
    }

    var kindLabel: String {
        switch kind {
        case .dex: return "DEX"
        case .coin: return "Coin"
        case .perp: return "Perp"
        }
    }

    var rankText: String {
        guard let rank else { return "—" }
        return "\(rank)"
    }

    /// Market cap for tokens and coins, open interest for perps.
    var sizeValue: Double? {
        kind == .perp ? openInterestUsd : (marketCap ?? fdv)
    }

    var chartSource: ChartSource? {
        switch kind {
        case .dex:
            guard let pair, !pair.pairAddress.isEmpty else { return nil }
            let network: String = ChartSource.geckoNetwork(for: pair.chainId)
            return .gecko(network: network, pool: pair.pairAddress, token: pair.baseAddress)
        case .coin:
            if let coinGeckoId { return .coinGecko(id: coinGeckoId) }
            return .coinGeckoSymbol(symbol: symbol, name: name)
        case .perp:
            guard let perpCoin else { return nil }
            return .hyperliquid(coin: perpCoin)
        }
    }

    // Non-optional values for sorting table columns (missing values sort last).
    var sortRank: Int { rank ?? Int.max }
    var sortPrice: Double { priceUsd ?? -1 }
    var sort5m: Double { change5m ?? -.greatestFiniteMagnitude }
    var sort1h: Double { change1h ?? -.greatestFiniteMagnitude }
    var sort24h: Double { change24h ?? -.greatestFiniteMagnitude }
    var sort7d: Double { change7d ?? -.greatestFiniteMagnitude }
    var sortMarketCap: Double { marketCap ?? fdv ?? -1 }
    var sortLiquidity: Double { liquidityUsd ?? -1 }
    var sortVolume: Double { volume24h ?? -1 }
    var sortOpenInterest: Double { openInterestUsd ?? -1 }
    var sortFunding: Double { fundingHourly ?? -.greatestFiniteMagnitude }
    var sortSize: Double { sizeValue ?? -1 }
}

struct MarketLoadResult {
    var rows: [MarketRow]
    var source: String
    /// Some of the data (watchlist sources) couldn't be updated.
    var partial: Bool = false
}

/// A chain filter option; a nil id means every chain.
struct ChainOption: Hashable {
    let id: String?
    let label: String
}

enum MarketChains {
    static let all: [ChainOption] = [
        ChainOption(id: nil, label: "All chains"),
        ChainOption(id: "solana", label: "Solana"),
        ChainOption(id: "base", label: "Base"),
        ChainOption(id: "ethereum", label: "Ethereum"),
        ChainOption(id: "bsc", label: "BNB Chain"),
    ]
}

/// Which set of table columns a section shows.
enum MarketTableKind {
    case dex
    case coins
    case perps
    case mixed
}

enum MarketSection: String, CaseIterable, Identifiable {
    case trending = "Trending"
    case pumpFun = "Pump.fun"
    case new = "New"
    case topCoins = "Top coins"
    case robinhood = "Robinhood"
    case hyperliquid = "Hyperliquid"
    case watchlist = "Watchlist"
    case search = "Search"

    var id: String { rawValue }

    var tableKind: MarketTableKind {
        switch self {
        case .trending, .pumpFun, .new, .search: return .dex
        case .topCoins, .robinhood: return .coins
        case .hyperliquid: return .perps
        case .watchlist: return .mixed
        }
    }

    /// The chain filter only applies to DEX tokens.
    var usesChainFilter: Bool {
        tableKind == .dex || tableKind == .mixed
    }

    var defaultSource: String {
        switch tableKind {
        case .dex: return "DEX Screener"
        case .coins: return "CoinGecko"
        case .perps: return "Hyperliquid"
        case .mixed: return "DEX Screener, CoinGecko and Hyperliquid"
        }
    }
}

@MainActor
final class MarketsModel: ObservableObject {
    @Published var section: MarketSection = .trending
    @Published var chain: String? = nil
    @Published var query = ""
    @Published private(set) var items: [MarketRow] = []
    @Published private(set) var loading = false
    @Published private(set) var failed = false
    @Published private(set) var updatedAt: Date? = nil
    @Published private(set) var sourceName: String = MarketSection.trending.defaultSource
    @Published private(set) var watchlist: [String]
    @Published private(set) var notice: String? = nil

    private let defaults = UserDefaults.standard
    private static let watchlistKey = "markets.watchlist"

    // One refresh at a time: a second request for the same section and query waits for the one
    // running; a request for something else cancels it.
    private var loadTask: Task<Void, Never>? = nil
    private var loadKey: String? = nil
    private var loadGeneration = 0

    init() {
        watchlist = UserDefaults.standard.stringArray(forKey: MarketsModel.watchlistKey) ?? []
    }

    var shown: [MarketRow] {
        let filterChain: String? = section.usesChainFilter ? chain : nil
        let watchOnly: Bool = section == .watchlist
        return items.filter { row in
            let chainMatches: Bool = filterChain == nil || row.chainId == filterChain
            let watchMatches: Bool = !watchOnly || isWatched(row)
            return chainMatches && watchMatches
        }
    }

    /// Watchlist keys: "chain:address" for DEX tokens (as before), "cg:<CoinGecko id>" for
    /// coins and "hl:<coin>" for Hyperliquid perps.
    func watchKey(for row: MarketRow) -> String? {
        switch row.kind {
        case .dex:
            return row.pair?.watchKey
        case .perp:
            return row.perpCoin.map { "hl:" + $0 }
        case .coin:
            if let id = row.coinGeckoId { return "cg:" + id }
            return MarketCoinIds.cached(symbol: row.symbol, name: row.name).map { "cg:" + $0 }
        }
    }

    func isWatched(_ row: MarketRow) -> Bool {
        guard let key = watchKey(for: row) else { return false }
        return watchlist.contains(key)
    }

    func toggleWatch(_ row: MarketRow) {
        if let key = watchKey(for: row) {
            toggleKey(key)
            return
        }
        guard row.kind == .coin else { return }
        // CoinMarketCap rows need their CoinGecko id first.
        let symbol: String = row.symbol
        let name: String = row.name
        Task { [weak self] in
            do {
                let id: String = try await MarketCoinIds.resolve(symbol: symbol, name: name)
                self?.toggleKey("cg:" + id)
            } catch {
                self?.notice = "Couldn't match \(symbol) to a CoinGecko coin, so it can't be watched right now."
            }
        }
    }

    private func toggleKey(_ key: String) {
        if let index = watchlist.firstIndex(of: key) {
            watchlist.remove(at: index)
        } else {
            watchlist.append(key)
        }
        defaults.set(watchlist, forKey: MarketsModel.watchlistKey)
    }

    func selectSection(_ next: MarketSection) {
        guard next != section else { return }
        loadTask?.cancel()
        loadTask = nil
        loadKey = nil
        loading = false
        section = next
        items = []
        failed = false
        updatedAt = nil
        notice = nil
        sourceName = next.defaultSource
    }

    func refresh() async {
        let requested: MarketSection = section
        let text: String = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let key: String = requested.rawValue + "|" + text
        if let running = loadTask, loadKey == key {
            await running.value
            return
        }
        loadTask?.cancel()
        loadTask = nil
        loadKey = nil
        if requested == .search && text.isEmpty {
            items = []
            loading = false
            return
        }
        loadGeneration += 1
        let generation: Int = loadGeneration
        let previous: [MarketRow] = items
        loading = true
        let task: Task<Void, Never> = Task { [weak self] in
            guard let self else { return }
            await self.performLoad(requested, query: text, previous: previous)
        }
        loadTask = task
        loadKey = key
        await task.value
        if generation == loadGeneration {
            loadTask = nil
            loadKey = nil
            loading = false
        }
    }

    private func performLoad(_ requested: MarketSection, query: String, previous: [MarketRow]) async {
        do {
            let result: MarketLoadResult = try await load(requested, query: query, previous: previous)
            if Task.isCancelled || section != requested { return }
            items = result.rows
            sourceName = result.source
            failed = result.partial
            updatedAt = Date()
        } catch {
            if Task.isCancelled || section != requested || MarketHTTP.isCancellation(error) { return }
            // Keep the last good data on screen; the status line says the update failed.
            failed = true
        }
    }

    private func load(_ section: MarketSection, query: String, previous: [MarketRow]) async throws -> MarketLoadResult {
        let dexSource = "DEX Screener"
        switch section {
        case .trending:
            let boosted: [TokenRef] = try await DexScreener.topBoosted()
            let pairs: [MarketPair] = try await DexScreener.tokens(Array(boosted.prefix(60)))
            return MarketLoadResult(rows: pairs.map(MarketRow.dex), source: dexSource)
        case .new:
            let profiles: [TokenRef] = try await DexScreener.latestProfiles()
            let pairs: [MarketPair] = try await DexScreener.tokens(Array(profiles.prefix(60)))
            return MarketLoadResult(rows: pairs.map(MarketRow.dex), source: dexSource)
        case .pumpFun:
            let profiles: [TokenRef] = try await DexScreener.latestProfiles()
            let latest: [TokenRef] = try await DexScreener.latestBoosted()
            let top: [TokenRef] = try await DexScreener.topBoosted()
            var seen = Set<String>()
            let refs: [TokenRef] = (profiles + latest + top)
                .filter { $0.chainId == "solana" && $0.tokenAddress.hasSuffix("pump") }
                .filter { seen.insert($0.tokenAddress).inserted }
            let pairs: [MarketPair] = try await DexScreener.tokens(Array(refs.prefix(60)))
            return MarketLoadResult(rows: pairs.map(MarketRow.dex), source: dexSource)
        case .topCoins:
            return try await MarketCoins.top()
        case .robinhood:
            let rows: [MarketRow] = try await MarketCoins.coinGecko(ids: MarketCoins.robinhoodIds)
            return MarketLoadResult(rows: rows, source: "CoinGecko")
        case .hyperliquid:
            let rows: [MarketRow] = try await MarketPerps.all()
            return MarketLoadResult(rows: rows, source: "Hyperliquid")
        case .watchlist:
            return try await loadWatchlist(previous: previous)
        case .search:
            let found: [MarketPair] = try await DexScreener.search(query)
            var bestByToken: [String: MarketPair] = [:]
            for pair in found where (bestByToken[pair.id]?.liquidityUsd ?? -1) < (pair.liquidityUsd ?? 0) {
                bestByToken[pair.id] = pair
            }
            let sorted: [MarketPair] = bestByToken.values.sorted { ($0.liquidityUsd ?? 0) > ($1.liquidityUsd ?? 0) }
            return MarketLoadResult(rows: Array(sorted.prefix(60)).map(MarketRow.dex), source: dexSource)
        }
    }

    /// Loads each kind of watched market from its own API. If one source fails, its rows from the
    /// last update stay on screen and the status line says the update failed.
    private func loadWatchlist(previous: [MarketRow]) async throws -> MarketLoadResult {
        let keys: [String] = watchlist
        var coinIds: [String] = []
        var perpCoins = Set<String>()
        var dexRefs: [TokenRef] = []
        for key in keys {
            if key.hasPrefix("cg:") {
                coinIds.append(String(key.dropFirst(3)))
            } else if key.hasPrefix("hl:") {
                perpCoins.insert(String(key.dropFirst(3)))
            } else if let ref = TokenRef.fromWatchKey(key) {
                dexRefs.append(ref)
            }
        }
        var found: [String: MarketRow] = [:]
        var sources: [String] = []
        var attempted = 0
        var failures = 0
        if !dexRefs.isEmpty {
            attempted += 1
            sources.append("DEX Screener")
            do {
                let pairs: [MarketPair] = try await DexScreener.tokens(dexRefs)
                for pair in pairs {
                    found[pair.watchKey.lowercased()] = MarketRow.dex(pair)
                }
            } catch {
                if MarketHTTP.isCancellation(error) { throw error }
                failures += 1
                keepPrevious(previous, kind: .dex, into: &found)
            }
        }
        if !coinIds.isEmpty {
            attempted += 1
            sources.append("CoinGecko")
            do {
                let coins: [MarketRow] = try await MarketCoins.coinGecko(ids: coinIds)
                for coin in coins {
                    if let id = coin.coinGeckoId { found["cg:" + id.lowercased()] = coin }
                }
            } catch {
                if MarketHTTP.isCancellation(error) { throw error }
                failures += 1
                keepPrevious(previous, kind: .coin, into: &found)
            }
        }
        if !perpCoins.isEmpty {
            attempted += 1
            sources.append("Hyperliquid")
            do {
                let perps: [MarketRow] = try await MarketPerps.all()
                for perp in perps {
                    if let coin = perp.perpCoin, perpCoins.contains(coin) { found["hl:" + coin.lowercased()] = perp }
                }
            } catch {
                if MarketHTTP.isCancellation(error) { throw error }
                failures += 1
                keepPrevious(previous, kind: .perp, into: &found)
            }
        }
        if attempted > 0 && failures == attempted { throw MarketHTTPError.status(0) }
        var seen = Set<String>()
        var rows: [MarketRow] = []
        for key in keys {
            if let row = found[key.lowercased()], seen.insert(row.id).inserted {
                rows.append(row)
            }
        }
        let source: String = sources.isEmpty ? MarketSection.watchlist.defaultSource : sources.joined(separator: ", ")
        return MarketLoadResult(rows: rows, source: source, partial: failures > 0)
    }

    private func keepPrevious(_ previous: [MarketRow], kind: MarketKind, into found: inout [String: MarketRow]) {
        for row in previous where row.kind == kind {
            if let key = watchKey(for: row) { found[key.lowercased()] = row }
        }
    }
}

// MARK: Formatting

enum MarketFormat {
    private static let subscripts: [Character] = ["₀", "₁", "₂", "₃", "₄", "₅", "₆", "₇", "₈", "₉"]

    /// "$1,234.56", "$0.1234", or DEX Screener's "$0.0₅1234" for very small prices.
    static func price(_ value: Double?) -> String {
        guard let value, value > 0 else { return "—" }
        if value >= 1 {
            let formatter = NumberFormatter()
            formatter.numberStyle = .decimal
            formatter.minimumFractionDigits = 2
            formatter.maximumFractionDigits = 2
            return "$" + (formatter.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value))
        }
        if value >= 0.001 { return String(format: "$%.4f", value) }
        var zeros = Int(floor(-log10(value)))
        if value * pow(10, Double(zeros)) >= 1 { zeros -= 1 }
        let significant = String(String(format: "%.0f", value * pow(10, Double(zeros + 4))).prefix(4))
        let count = String(String(zeros).compactMap { digit in digit.wholeNumberValue.map { subscripts[$0] } })
        return "$0.0\(count)\(significant)"
    }

    /// "$950", "$12.3K", "$4.56M", "$1.20B".
    static func usd(_ value: Double?) -> String {
        guard let value else { return "—" }
        switch abs(value) {
        case 1e9...: return String(format: "$%.2fB", value / 1e9)
        case 1e6...: return String(format: "$%.2fM", value / 1e6)
        case 1e3...: return String(format: "$%.1fK", value / 1e3)
        default: return String(format: "$%.0f", value)
        }
    }

    /// Like `usd` without the dollar sign, for coin amounts: "19.80M".
    static func amount(_ value: Double?) -> String {
        guard let value else { return "—" }
        switch abs(value) {
        case 1e9...: return String(format: "%.2fB", value / 1e9)
        case 1e6...: return String(format: "%.2fM", value / 1e6)
        case 1e3...: return String(format: "%.1fK", value / 1e3)
        default: return String(format: "%.0f", value)
        }
    }

    static func percent(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%+.1f%%", value)
    }

    /// Hyperliquid funding is an hourly rate: "+0.0013%".
    static func funding(_ hourly: Double?) -> String {
        guard let hourly else { return "—" }
        return String(format: "%+.4f%%", hourly * 100)
    }

    /// The hourly funding rate over a year, not compounded: "+11.4% a year".
    static func fundingYearly(_ hourly: Double?) -> String {
        guard let hourly else { return "—" }
        return String(format: "%+.1f%% a year", hourly * 100 * 24 * 365)
    }

    static func age(_ date: Date?) -> String {
        guard let date else { return "—" }
        let minutes = max(0, Int(Date().timeIntervalSince(date) / 60))
        if minutes < 60 { return "\(minutes)m" }
        if minutes < 48 * 60 { return "\(minutes / 60)h" }
        return "\(minutes / (24 * 60))d"
    }

    static func chain(_ id: String) -> String {
        if let known = MarketChains.all.first(where: { $0.id == id }) { return known.label }
        switch id {
        case "arbitrum": return "Arbitrum"
        case "polygon": return "Polygon"
        case "avalanche": return "Avalanche"
        case "sui": return "Sui"
        case "ton": return "TON"
        case "tron": return "Tron"
        default: return id.capitalized
        }
    }

    static func shortAddress(_ address: String) -> String {
        address.count <= 12 ? address : "\(address.prefix(6))…\(address.suffix(4))"
    }

    static func dexScreenerLink(_ pair: MarketPair) -> String {
        if let url = pair.url, !url.isEmpty { return url }
        let target: String = pair.pairAddress.isEmpty ? pair.baseAddress : pair.pairAddress
        return "https://dexscreener.com/\(pair.chainId)/\(target)"
    }
}

// MARK: Outside links

struct MarketLink: Hashable {
    let label: String
    let url: URL
}

enum MarketLinks {
    @MainActor
    static func links(for row: MarketRow) -> [MarketLink] {
        let resolved: String? = row.coinGeckoId ?? MarketCoinIds.cached(symbol: row.symbol, name: row.name)
        return links(for: row, coinGeckoId: resolved)
    }

    static func links(for row: MarketRow, coinGeckoId: String?) -> [MarketLink] {
        var result: [MarketLink] = []
        switch row.kind {
        case .dex:
            guard let pair = row.pair else { return [] }
            let address: String = pair.baseAddress
            add(&result, "DEX Screener", MarketFormat.dexScreenerLink(pair))
            if let chain = birdeyeChain(pair.chainId) {
                add(&result, "Birdeye", "https://birdeye.so/token/\(address)?chain=\(chain)")
            }
            if let chain = gmgnChain(pair.chainId) {
                add(&result, "GMGN", "https://gmgn.ai/\(chain)/token/\(address)")
            }
            if pair.chainId == "solana" && !pair.pairAddress.isEmpty {
                add(&result, "Photon", "https://photon-sol.tinyastro.io/en/lp/\(pair.pairAddress)")
            }
            add(&result, "Arkham", "https://intel.arkm.com/explorer/token/\(address)")
            if let site = explorer(chain: pair.chainId, address: address) {
                add(&result, site.label, site.address)
            }
        case .coin:
            if let coinGeckoId {
                add(&result, "CoinGecko", "https://www.coingecko.com/en/coins/\(coinGeckoId)")
            }
            if let slug = row.coinMarketCapSlug {
                add(&result, "CoinMarketCap", "https://coinmarketcap.com/currencies/\(slug)/")
            }
        case .perp:
            if let coin = row.perpCoin {
                let encoded: String = coin.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? coin
                add(&result, "Hyperliquid", "https://app.hyperliquid.xyz/trade/\(encoded)")
            }
        }
        return result
    }

    private static func add(_ list: inout [MarketLink], _ label: String, _ address: String) {
        if let url = URL(string: address) {
            list.append(MarketLink(label: label, url: url))
        }
    }

    private static func birdeyeChain(_ chainId: String) -> String? {
        switch chainId {
        case "solana", "ethereum", "base", "bsc": return chainId
        default: return nil
        }
    }

    private static func gmgnChain(_ chainId: String) -> String? {
        switch chainId {
        case "solana": return "sol"
        case "ethereum": return "eth"
        case "base": return "base"
        case "bsc": return "bsc"
        default: return nil
        }
    }

    /// The chain's block explorer token page.
    private static func explorer(chain: String, address: String) -> (label: String, address: String)? {
        switch chain {
        case "solana": return ("Solscan", "https://solscan.io/token/\(address)")
        case "ethereum": return ("Etherscan", "https://etherscan.io/token/\(address)")
        case "base": return ("BaseScan", "https://basescan.org/token/\(address)")
        case "bsc": return ("BscScan", "https://bscscan.com/token/\(address)")
        case "arbitrum": return ("Arbiscan", "https://arbiscan.io/token/\(address)")
        case "polygon": return ("PolygonScan", "https://polygonscan.com/token/\(address)")
        case "avalanche": return ("Snowtrace", "https://snowtrace.io/token/\(address)")
        case "sui": return ("Suiscan", "https://suiscan.xyz/mainnet/coin/\(address)")
        case "ton": return ("Tonviewer", "https://tonviewer.com/\(address)")
        case "tron": return ("Tronscan", "https://tronscan.org/#/token20/\(address)")
        default: return nil
        }
    }
}

// MARK: Telegram hand-off

// Read-only hand-off: "Open in trading bot" copies the address and opens a t.me link to the
// viewer's own bot, where any trade happens. "Send to Telegram" messages the viewer's own chat
// through their own bot. The bot token lives in the Keychain and is never logged or shown.

struct MarketTelegramResult {
    var ok: Bool
    var message: String
    var chatId: String? = nil
    var chatMissing: Bool = false
}

struct MarketTelegramReply {
    let code: Int
    let ok: Bool
    let json: [String: Any]?
}

enum MarketTelegram {
    static let tradingBotKey = "telegram.tradingBot"
    static let chatIdKey = "telegram.chatId"
    static let noTokenMessage = "Add your Telegram bot token in Settings > Services first."
    static let connectionMessage = "Couldn't reach Telegram. Check your connection and try again."

    /// "@MyBot", "t.me/MyBot" or "https://t.me/MyBot?start=x" all become "MyBot".
    static func cleanBotName(_ raw: String) -> String {
        var name: String = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["https://", "http://", "www.", "t.me/", "telegram.me/", "@"] {
            if name.lowercased().hasPrefix(prefix) {
                name = String(name.dropFirst(prefix.count))
            }
        }
        if let mark = name.firstIndex(of: "?") { name = String(name[..<mark]) }
        if let slash = name.firstIndex(of: "/") { name = String(name[..<slash]) }
        return name
    }

    static func isValidBotName(_ name: String) -> Bool {
        guard (4...32).contains(name.count) else { return false }
        return name.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }
    }

    static func botURL(bot: String, address: String) -> URL? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-_")
        let start: String = address.addingPercentEncoding(withAllowedCharacters: allowed) ?? address
        return URL(string: "https://t.me/\(bot)?start=\(start)")
    }

    static func message(for pair: MarketPair) -> String {
        var lines: [String] = []
        lines.append(pair.baseName.isEmpty ? pair.baseSymbol : "\(pair.baseSymbol) (\(pair.baseName))")
        let exchange: String = pair.dexId.isEmpty ? "" : " on \(pair.dexId)"
        lines.append("Chain: \(MarketFormat.chain(pair.chainId))\(exchange)")
        lines.append("Price: \(MarketFormat.price(pair.priceUsd))")
        lines.append("24h change: \(MarketFormat.percent(pair.change24h))")
        lines.append("Market cap: \(MarketFormat.usd(pair.marketCap ?? pair.fdv))")
        lines.append("Liquidity: \(MarketFormat.usd(pair.liquidityUsd))")
        lines.append("")
        lines.append(pair.baseAddress)
        lines.append("")
        lines.append(MarketFormat.dexScreenerLink(pair))
        return lines.joined(separator: "\n")
    }

    static func send(text: String, chatId: String) async -> MarketTelegramResult {
        guard let token = Secrets.get(.telegramBot) else {
            return MarketTelegramResult(ok: false, message: noTokenMessage)
        }
        let body: [String: Any] = ["chat_id": chatId, "text": text, "disable_web_page_preview": true]
        do {
            let reply: MarketTelegramReply = try await call("sendMessage", token: token, body: body)
            if reply.ok {
                return MarketTelegramResult(ok: true, message: "Sent to your Telegram chat.")
            }
            let description: String = (str(reply.json, "description") ?? "").lowercased()
            if reply.code == 400 && description.contains("chat not found") {
                return MarketTelegramResult(
                    ok: false,
                    message: "Telegram couldn't find your chat. Send /start to your bot, then press Find my chat.",
                    chatMissing: true
                )
            }
            return MarketTelegramResult(ok: false, message: reason(reply, token: token))
        } catch {
            return MarketTelegramResult(ok: false, message: connectionMessage)
        }
    }

    /// The chat of the latest message sent to the viewer's bot.
    static func findChat() async -> MarketTelegramResult {
        guard let token = Secrets.get(.telegramBot) else {
            return MarketTelegramResult(ok: false, message: noTokenMessage)
        }
        do {
            let reply: MarketTelegramReply = try await call("getUpdates", token: token, body: nil)
            guard reply.ok else {
                return MarketTelegramResult(ok: false, message: reason(reply, token: token))
            }
            let updates: [Any] = reply.json?["result"] as? [Any] ?? []
            for raw in updates.reversed() {
                guard let update = raw as? [String: Any], let id = chatId(in: update) else { continue }
                return MarketTelegramResult(ok: true, message: "Found your chat. Press Send to Telegram.", chatId: id)
            }
            return MarketTelegramResult(
                ok: false,
                message: "No messages yet. Open your bot in Telegram, send /start, then press Find my chat again."
            )
        } catch {
            return MarketTelegramResult(ok: false, message: connectionMessage)
        }
    }

    private static func chatId(in update: [String: Any]) -> String? {
        for key in ["message", "edited_message"] {
            let entry = update[key] as? [String: Any]
            let chat = entry?["chat"] as? [String: Any]
            if let id = str(chat, "id") { return id }
        }
        return nil
    }

    /// Telegram's own error text, with the token scrubbed out just in case.
    private static func reason(_ reply: MarketTelegramReply, token: String) -> String {
        if reply.code == 401 || reply.code == 404 {
            return "Telegram didn't accept your bot token. Check it in Settings > Services."
        }
        let text: String = str(reply.json, "description") ?? "HTTP \(reply.code)"
        return "Telegram said: " + text.replacingOccurrences(of: token, with: "…")
    }

    private static func call(_ method: String, token: String, body: [String: Any]?) async throws -> MarketTelegramReply {
        guard let url = URL(string: ServiceURL.telegram + "/bot" + token + "/" + method) else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(MarketHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code: Int = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json: [String: Any]? = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let ok: Bool = (200...299).contains(code) && (json?["ok"] as? Bool) == true
        return MarketTelegramReply(code: code, ok: ok, json: json)
    }
}

// MARK: Views

@MainActor
struct MarketsView: View {
    @StateObject private var markets = MarketsModel()
    @State private var selectedId: MarketRow.ID? = nil
    @State private var sortOrder: [KeyPathComparator<MarketRow>] = []

    private var rows: [MarketRow] {
        sortOrder.isEmpty ? markets.shown : markets.shown.sorted(using: sortOrder)
    }

    private var selectedRow: MarketRow? {
        guard let selectedId else { return nil }
        return markets.items.first { $0.id == selectedId }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            Picker("Section", selection: Binding(
                get: { markets.section },
                set: { markets.selectSection($0) }
            )) {
                ForEach(MarketSection.allCases) { section in
                    Text(section.rawValue).tag(section)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 780)
            if markets.section == .search {
                searchBar
            }
            Text(status)
                .font(NeonFont.body(13))
                .foregroundColor(markets.failed ? Neon.danger : Neon.textSecondary)
            if let notice = markets.notice {
                Text(notice)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.magenta)
            }
            HStack(alignment: .top, spacing: 18) {
                table
                detail
                    .frame(minWidth: 360, idealWidth: 400, maxWidth: 460)
            }
        }
        .padding(24)
        .task(id: markets.section) {
            // Refresh every 30 seconds while the tab is open; search waits for a query.
            while !Task.isCancelled {
                await markets.refresh()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
        .onChange(of: markets.section) { _ in
            sortOrder = []
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            NeonTitle(text: "Markets")
            Spacer()
            if markets.section.usesChainFilter {
                Picker("Chain", selection: $markets.chain) {
                    ForEach(MarketChains.all, id: \.label) { chain in
                        Text(chain.label).tag(chain.id)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 140)
            }
            Button {
                Task { await markets.refresh() }
            } label: {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .buttonStyle(NeonButtonStyle())
            .disabled(markets.loading)
        }
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            TextField("Token name, symbol or contract address", text: $markets.query)
                .textFieldStyle(.plain)
                .font(NeonFont.body(15))
                .padding(10)
                .neonPanel()
                .frame(maxWidth: 520)
                .onSubmit { Task { await markets.refresh() } }
            Button("Search") { Task { await markets.refresh() } }
                .buttonStyle(NeonButtonStyle(prominent: true))
        }
    }

    @ViewBuilder
    private var detail: some View {
        if let row = selectedRow {
            MarketDetail(row: row, watched: markets.isWatched(row)) { markets.toggleWatch(row) }
                .id(row.id)
        } else {
            Text("Select a token, coin or perp to see its chart and details. Double-click to add it to your watchlist or remove it. Right-click for more.")
                .font(NeonFont.body(14))
                .foregroundColor(Neon.textSecondary)
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .topLeading)
                .neonPanel()
        }
    }

    private var status: String {
        let source: String = markets.sourceName
        if markets.failed && rows.isEmpty { return "Couldn't reach \(source). Trying again in 30 seconds." }
        if markets.loading && rows.isEmpty { return "Loading market data…" }
        if rows.isEmpty {
            switch markets.section {
            case .watchlist: return "Your watchlist is empty. Double-click any token, coin or perp to add it."
            case .search: return "Search for a token by name, symbol or contract address."
            default: return markets.section.usesChainFilter ? "Nothing to show for this chain right now." : "Nothing to show right now."
            }
        }
        if markets.failed { return "The last update failed, so these prices may be out of date. Trying again in 30 seconds." }
        let time: String = markets.updatedAt?.formatted(date: .omitted, time: .standard) ?? "—"
        let lead: String = markets.section == .robinhood ? "Coins on Robinhood Crypto (a hand-picked list). " : ""
        return "\(lead)Updated \(time). Data from \(source), refreshed every 30 seconds. Click a column heading to sort."
    }

    // MARK: Tables (one per column set; macOS 13 tables can't switch columns conditionally)

    @ViewBuilder
    private var table: some View {
        switch markets.section.tableKind {
        case .dex: styled(dexTable)
        case .coins: styled(coinTable)
        case .perps: styled(perpTable)
        case .mixed: styled(mixedTable)
        }
    }

    private func styled<Content: View>(_ content: Content) -> some View {
        content
            .tableStyle(.inset(alternatesRowBackgrounds: false))
            .scrollContentBackground(.hidden)
            .background(HudShape(cut: 12).fill(Neon.surface.opacity(0.55)))
            .contextMenu(forSelectionType: MarketRow.ID.self) { ids in
                rowMenu(ids)
            } primaryAction: { ids in
                if let row = markets.items.first(where: { ids.contains($0.id) }) { markets.toggleWatch(row) }
            }
    }

    @ViewBuilder
    private func rowMenu(_ ids: Set<MarketRow.ID>) -> some View {
        if let row = markets.items.first(where: { ids.contains($0.id) }) {
            Button(markets.isWatched(row) ? "Remove from watchlist" : "Add to watchlist") { markets.toggleWatch(row) }
            ForEach(MarketLinks.links(for: row).prefix(3), id: \.self) { link in
                Button("Open on \(link.label)") { NSWorkspace.shared.open(link.url) }
            }
            if let pair = row.pair {
                Button("Copy token address") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(pair.baseAddress, forType: .string)
                }
            }
        }
    }

    private var dexTable: some View {
        Table(rows, selection: $selectedId, sortOrder: $sortOrder) {
            TableColumn("Token", value: \.symbol) { row in
                MarketTokenCell(row: row, watched: markets.isWatched(row))
            }
            .width(min: 150, ideal: 190)
            TableColumn("Price", value: \.sortPrice) { row in
                Text(MarketFormat.price(row.priceUsd)).font(NeonFont.body(13, bold: true))
            }
            .width(min: 90, ideal: 110)
            TableColumn("5m", value: \.sort5m) { row in ChangeLabel(value: row.change5m) }
                .width(min: 56, ideal: 64)
            TableColumn("1h", value: \.sort1h) { row in ChangeLabel(value: row.change1h) }
                .width(min: 56, ideal: 64)
            TableColumn("24h", value: \.sort24h) { row in ChangeLabel(value: row.change24h) }
                .width(min: 60, ideal: 70)
            TableColumn("Market cap", value: \.sortMarketCap) { row in
                Text(MarketFormat.usd(row.marketCap ?? row.fdv)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
            TableColumn("Liquidity", value: \.sortLiquidity) { row in
                Text(MarketFormat.usd(row.liquidityUsd)).font(NeonFont.body(13))
            }
            .width(min: 76, ideal: 88)
            TableColumn("Volume 24h", value: \.sortVolume) { row in
                Text(MarketFormat.usd(row.volume24h)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
        }
    }

    private var coinTable: some View {
        Table(rows, selection: $selectedId, sortOrder: $sortOrder) {
            TableColumn("#", value: \.sortRank) { row in
                Text(row.rankText).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            }
            .width(min: 30, ideal: 38)
            TableColumn("Coin", value: \.symbol) { row in
                MarketTokenCell(row: row, watched: markets.isWatched(row))
            }
            .width(min: 150, ideal: 190)
            TableColumn("Price", value: \.sortPrice) { row in
                Text(MarketFormat.price(row.priceUsd)).font(NeonFont.body(13, bold: true))
            }
            .width(min: 90, ideal: 110)
            TableColumn("1h", value: \.sort1h) { row in ChangeLabel(value: row.change1h) }
                .width(min: 56, ideal: 64)
            TableColumn("24h", value: \.sort24h) { row in ChangeLabel(value: row.change24h) }
                .width(min: 60, ideal: 70)
            TableColumn("7d", value: \.sort7d) { row in ChangeLabel(value: row.change7d) }
                .width(min: 60, ideal: 70)
            TableColumn("Market cap", value: \.sortMarketCap) { row in
                Text(MarketFormat.usd(row.marketCap ?? row.fdv)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
            TableColumn("Volume 24h", value: \.sortVolume) { row in
                Text(MarketFormat.usd(row.volume24h)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
        }
    }

    private var perpTable: some View {
        Table(rows, selection: $selectedId, sortOrder: $sortOrder) {
            TableColumn("Market", value: \.symbol) { row in
                MarketTokenCell(row: row, watched: markets.isWatched(row))
            }
            .width(min: 150, ideal: 190)
            TableColumn("Mark price", value: \.sortPrice) { row in
                Text(MarketFormat.price(row.priceUsd)).font(NeonFont.body(13, bold: true))
            }
            .width(min: 90, ideal: 110)
            TableColumn("24h", value: \.sort24h) { row in ChangeLabel(value: row.change24h) }
                .width(min: 60, ideal: 70)
            TableColumn("Volume 24h", value: \.sortVolume) { row in
                Text(MarketFormat.usd(row.volume24h)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
            TableColumn("Open interest", value: \.sortOpenInterest) { row in
                Text(MarketFormat.usd(row.openInterestUsd)).font(NeonFont.body(13))
            }
            .width(min: 86, ideal: 100)
            TableColumn("Funding (1h)", value: \.sortFunding) { row in
                Text(MarketFormat.funding(row.fundingHourly)).font(NeonFont.body(13)).foregroundColor(Neon.textSecondary)
            }
            .width(min: 86, ideal: 100)
        }
    }

    private var mixedTable: some View {
        Table(rows, selection: $selectedId, sortOrder: $sortOrder) {
            TableColumn("Token", value: \.symbol) { row in
                MarketTokenCell(row: row, watched: markets.isWatched(row))
            }
            .width(min: 150, ideal: 190)
            TableColumn("Type", value: \.kindLabel) { row in
                Text(row.kindLabel).font(NeonFont.body(12, bold: true)).foregroundColor(Neon.textMuted)
            }
            .width(min: 44, ideal: 52)
            TableColumn("Price", value: \.sortPrice) { row in
                Text(MarketFormat.price(row.priceUsd)).font(NeonFont.body(13, bold: true))
            }
            .width(min: 90, ideal: 110)
            TableColumn("1h", value: \.sort1h) { row in ChangeLabel(value: row.change1h) }
                .width(min: 56, ideal: 64)
            TableColumn("24h", value: \.sort24h) { row in ChangeLabel(value: row.change24h) }
                .width(min: 60, ideal: 70)
            TableColumn("Mkt cap / OI", value: \.sortSize) { row in
                Text(MarketFormat.usd(row.sizeValue)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 96)
            TableColumn("Volume 24h", value: \.sortVolume) { row in
                Text(MarketFormat.usd(row.volume24h)).font(NeonFont.body(13))
            }
            .width(min: 80, ideal: 92)
        }
    }
}

@MainActor
private struct ChangeLabel: View {
    let value: Double?

    var body: some View {
        Text(MarketFormat.percent(value))
            .font(NeonFont.body(13, bold: true))
            .foregroundColor(value == nil ? Neon.textMuted : (value! >= 0 ? Neon.positive : Neon.danger))
    }
}

@MainActor
private struct MarketIcon: View {
    let row: MarketRow
    let size: CGFloat

    var body: some View {
        Group {
            if row.imageUrl != nil || row.kind == .dex {
                RemoteImage(url: row.imageUrl, contentMode: .fill)
            } else {
                ZStack {
                    Neon.surfaceRaised
                    Text(String(row.symbol.prefix(1)))
                        .font(NeonFont.display(size * 0.45))
                        .foregroundColor(Neon.cyan)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }
}

@MainActor
private struct MarketTokenCell: View {
    let row: MarketRow
    let watched: Bool

    var body: some View {
        HStack(spacing: 8) {
            MarketIcon(row: row, size: 22)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    Text(row.symbol).font(NeonFont.body(13, bold: true))
                    if watched {
                        Image(systemName: "star.fill").font(.system(size: 9)).foregroundColor(Neon.magenta)
                    }
                }
                Text(row.subtitle)
                    .font(NeonFont.body(11))
                    .foregroundColor(Neon.textMuted)
                    .lineLimit(1)
            }
        }
    }
}

@MainActor
private struct MarketDetail: View {
    let row: MarketRow
    let watched: Bool
    let onToggleWatch: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                MarketDetailHeader(row: row)
                Text(MarketFormat.price(row.priceUsd))
                    .font(NeonFont.display(26))
                    .foregroundColor(Neon.cyan)
                MarketChanges(row: row)
                if let source = row.chartSource {
                    MarketChartPanel(source: source)
                }
                stats
                if let pair = row.pair {
                    MarketDexExtras(pair: pair)
                }
                MarketLinkGrid(links: MarketLinks.links(for: row))
                Button(watched ? "Remove from watchlist" : "Add to watchlist", action: onToggleWatch)
                    .buttonStyle(NeonButtonStyle(prominent: !watched))
                if let pair = row.pair {
                    MarketTelegramPanel(pair: pair)
                }
            }
            .padding(16)
        }
        .neonPanel()
    }

    @ViewBuilder
    private var stats: some View {
        switch row.kind {
        case .dex:
            if let pair = row.pair {
                MarketDexStats(pair: pair)
            }
        case .coin:
            MarketCoinStats(row: row)
        case .perp:
            MarketPerpStats(row: row)
        }
    }
}

@MainActor
private struct MarketDetailHeader: View {
    let row: MarketRow

    var body: some View {
        HStack(spacing: 12) {
            MarketIcon(row: row, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.symbol).font(NeonFont.body(20, bold: true)).foregroundColor(Neon.text)
                Text(row.subtitle == row.name ? row.name : "\(row.name)  ·  \(row.subtitle)")
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(1)
            }
        }
    }
}

@MainActor
private struct MarketChanges: View {
    let row: MarketRow

    var body: some View {
        HStack(spacing: 16) {
            switch row.kind {
            case .dex:
                item("5m", row.change5m)
                item("1h", row.change1h)
                item("6h", row.change6h)
                item("24h", row.change24h)
            case .coin:
                item("1h", row.change1h)
                item("24h", row.change24h)
                item("7d", row.change7d)
            case .perp:
                item("24h", row.change24h)
            }
        }
    }

    private func item(_ label: String, _ value: Double?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(NeonFont.body(11)).foregroundColor(Neon.textMuted)
            ChangeLabel(value: value)
        }
    }
}

@MainActor
private struct MarketStatLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label).font(NeonFont.body(13)).foregroundColor(Neon.textMuted)
            Spacer()
            Text(value).font(NeonFont.body(13, bold: true)).foregroundColor(Neon.text)
        }
    }
}

@MainActor
private struct MarketDexStats: View {
    let pair: MarketPair

    var body: some View {
        VStack(spacing: 6) {
            MarketStatLine(label: "Market cap", value: MarketFormat.usd(pair.marketCap))
            MarketStatLine(label: "Fully diluted value", value: MarketFormat.usd(pair.fdv))
            MarketStatLine(label: "Liquidity", value: MarketFormat.usd(pair.liquidityUsd))
            MarketStatLine(label: "24h volume", value: MarketFormat.usd(pair.volume24h))
            if let buys = pair.buys24h, let sells = pair.sells24h {
                MarketStatLine(label: "24h trades", value: "\(buys) buys, \(sells) sells")
            }
            MarketStatLine(label: "Pool age", value: MarketFormat.age(pair.createdAt))
            MarketStatLine(label: "Chain and exchange", value: "\(MarketFormat.chain(pair.chainId))  \(pair.dexId)")
            MarketStatLine(label: "Token address", value: MarketFormat.shortAddress(pair.baseAddress))
            if let boosts = pair.boosts, boosts > 0 {
                MarketStatLine(label: "Active boosts", value: "\(boosts)")
            }
        }
    }
}

@MainActor
private struct MarketCoinStats: View {
    let row: MarketRow

    private var supply: String {
        guard row.circulatingSupply != nil else { return "—" }
        return MarketFormat.amount(row.circulatingSupply) + " " + row.symbol
    }

    var body: some View {
        VStack(spacing: 6) {
            MarketStatLine(label: "Rank", value: row.rank == nil ? "—" : "#" + row.rankText)
            MarketStatLine(label: "Market cap", value: MarketFormat.usd(row.marketCap))
            MarketStatLine(label: "Fully diluted value", value: MarketFormat.usd(row.fdv))
            MarketStatLine(label: "24h volume", value: MarketFormat.usd(row.volume24h))
            if row.high24h != nil || row.low24h != nil {
                MarketStatLine(label: "24h high", value: MarketFormat.price(row.high24h))
                MarketStatLine(label: "24h low", value: MarketFormat.price(row.low24h))
            }
            MarketStatLine(label: "Circulating supply", value: supply)
        }
    }
}

@MainActor
private struct MarketPerpStats: View {
    let row: MarketRow

    private var leverage: String {
        guard let maxLeverage = row.maxLeverage else { return "—" }
        return "\(maxLeverage)x"
    }

    var body: some View {
        VStack(spacing: 6) {
            MarketStatLine(label: "Mark price", value: MarketFormat.price(row.priceUsd))
            MarketStatLine(label: "Price 24h ago", value: MarketFormat.price(row.prevDayPrice))
            MarketStatLine(label: "24h volume", value: MarketFormat.usd(row.volume24h))
            MarketStatLine(label: "Open interest", value: MarketFormat.usd(row.openInterestUsd))
            MarketStatLine(label: "Funding (hourly)", value: MarketFormat.funding(row.fundingHourly))
            MarketStatLine(label: "Funding (yearly rate)", value: MarketFormat.fundingYearly(row.fundingHourly))
            MarketStatLine(label: "Max leverage", value: leverage)
        }
    }
}

@MainActor
private struct MarketDexExtras: View {
    let pair: MarketPair
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let description = pair.description, !description.isEmpty {
                Text(description)
                    .font(NeonFont.body(13))
                    .foregroundColor(Neon.textSecondary)
                    .lineLimit(6)
            }
            if !pair.links.isEmpty {
                HStack(spacing: 10) {
                    ForEach(pair.links.prefix(4), id: \.self) { link in
                        Link(link.label, destination: link.url)
                            .font(NeonFont.body(13, bold: true))
                            .foregroundColor(Neon.magenta)
                    }
                }
            }
            MarketSmallButton(title: copied ? "Address copied" : "Copy token address", systemImage: "doc.on.doc") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(pair.baseAddress, forType: .string)
                copied = true
            }
        }
    }
}

@MainActor
private struct MarketLinkGrid: View {
    let links: [MarketLink]
    private let columns: [GridItem] = [GridItem(.adaptive(minimum: 100), spacing: 8, alignment: .leading)]

    var body: some View {
        if !links.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Open in")
                    .font(NeonFont.body(12, bold: true))
                    .foregroundColor(Neon.textMuted)
                LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                    ForEach(links, id: \.self) { link in
                        MarketSmallButton(title: link.label, systemImage: "arrow.up.right", fill: true) {
                            NSWorkspace.shared.open(link.url)
                        }
                    }
                }
            }
        }
    }
}

/// A compact neon outline button for the detail panel.
@MainActor
private struct MarketSmallButton: View {
    let title: String
    var systemImage: String? = nil
    var fill: Bool = false
    let action: () -> Void
    @State private var hovering = false
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        let lit: Bool = hovering && isEnabled
        Button(action: action) {
            HStack(spacing: 5) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 10, weight: .bold))
                }
                Text(title)
                    .font(NeonFont.body(12, bold: true))
                    .lineLimit(1)
            }
            .foregroundColor(lit ? Neon.onCyan : Neon.cyan)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: fill ? .infinity : nil)
            .background(HudShape(cut: 6).fill(lit ? Neon.cyan : Neon.surface))
            .overlay(HudShape(cut: 6).stroke(Neon.cyan.opacity(0.5), lineWidth: 1))
            .contentShape(HudShape(cut: 6))
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.45)
        .onHover { hovering = $0 }
    }
}

/// Telegram hand-off for a DEX token. Read-only: no wallet, key, seed phrase or signing here.
@MainActor
private struct MarketTelegramPanel: View {
    let pair: MarketPair
    @AppStorage(MarketTelegram.tradingBotKey) private var tradingBot: String = ""
    @AppStorage(MarketTelegram.chatIdKey) private var chatId: String = ""
    @State private var editingBot = false
    @State private var botDraft = ""
    @State private var botNote: String? = nil
    @State private var botNoteOk = true
    @State private var sendNote: String? = nil
    @State private var sendOk = false
    @State private var needsChat = false
    @State private var working = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Telegram")
                .font(NeonFont.body(13, bold: true))
                .foregroundColor(Neon.textSecondary)
            HStack(spacing: 8) {
                MarketSmallButton(title: "Open in trading bot", systemImage: "bolt") { openInBot() }
                if !tradingBot.isEmpty && !editingBot {
                    MarketSmallButton(title: "Change bot") { startEditing() }
                }
            }
            if editingBot {
                botEditor
            }
            if let botNote {
                note(botNote, ok: botNoteOk)
            }
            MarketSmallButton(title: working ? "Working…" : "Send to Telegram", systemImage: "paperplane") { send() }
                .disabled(working)
            if needsChat && chatId.isEmpty {
                Text("Open your own bot in Telegram and send it /start, then press Find my chat.")
                    .font(NeonFont.body(12))
                    .foregroundColor(Neon.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                MarketSmallButton(title: "Find my chat", systemImage: "magnifyingglass") { findChat() }
                    .disabled(working)
            }
            if let sendNote {
                note(sendNote, ok: sendOk)
            }
            Text("Read-only: CHUD STREAMS never holds a wallet or signs trades. Any trade happens in your bot.")
                .font(NeonFont.body(11))
                .foregroundColor(Neon.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HudShape(cut: 8).fill(Neon.background.opacity(0.35)))
        .overlay(HudShape(cut: 8).stroke(Neon.magenta.opacity(0.3), lineWidth: 1))
    }

    private var botEditor: some View {
        HStack(spacing: 8) {
            TextField("Trading bot username (the name after t.me/)", text: $botDraft)
                .textFieldStyle(.plain)
                .font(NeonFont.body(13))
                .padding(7)
                .neonPanel()
                .onSubmit { saveBot() }
            MarketSmallButton(title: "Save") { saveBot() }
        }
    }

    private func note(_ text: String, ok: Bool) -> some View {
        Text(text)
            .font(NeonFont.body(12))
            .foregroundColor(ok ? Neon.positive : Neon.danger)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func startEditing() {
        botDraft = tradingBot
        editingBot = true
        botNote = nil
    }

    private func saveBot() {
        let cleaned: String = MarketTelegram.cleanBotName(botDraft)
        guard MarketTelegram.isValidBotName(cleaned) else {
            botNoteOk = false
            botNote = "That doesn't look like a Telegram username. Use letters, numbers and underscores."
            return
        }
        tradingBot = cleaned
        editingBot = false
        botNoteOk = true
        botNote = "Saved @\(cleaned). Press Open in trading bot."
    }

    /// Copies the contract address, then opens the viewer's trading bot with it as the start
    /// parameter. The bot does the rest; nothing is traded from here.
    private func openInBot() {
        let bot: String = MarketTelegram.cleanBotName(tradingBot)
        guard MarketTelegram.isValidBotName(bot) else {
            botDraft = ""
            editingBot = true
            botNoteOk = true
            botNote = "Enter your trading bot's Telegram username, then press Save."
            return
        }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pair.baseAddress, forType: .string)
        botNoteOk = true
        botNote = "Address copied"
        guard let url = MarketTelegram.botURL(bot: bot, address: pair.baseAddress) else { return }
        Task {
            try? await Task.sleep(nanoseconds: 600_000_000)
            NSWorkspace.shared.open(url)
        }
    }

    private func send() {
        guard !working else { return }
        guard Secrets.has(.telegramBot) else {
            sendOk = false
            sendNote = MarketTelegram.noTokenMessage
            return
        }
        guard !chatId.isEmpty else {
            needsChat = true
            sendOk = false
            sendNote = "Your chat isn't set up yet."
            return
        }
        working = true
        sendNote = nil
        let text: String = MarketTelegram.message(for: pair)
        let chat: String = chatId
        Task {
            let result: MarketTelegramResult = await MarketTelegram.send(text: text, chatId: chat)
            working = false
            if result.chatMissing {
                chatId = ""
                needsChat = true
            }
            sendOk = result.ok
            sendNote = result.message
        }
    }

    private func findChat() {
        guard !working else { return }
        working = true
        sendNote = nil
        Task {
            let result: MarketTelegramResult = await MarketTelegram.findChat()
            working = false
            if let id = result.chatId {
                chatId = id
                needsChat = false
            }
            sendOk = result.ok
            sendNote = result.message
        }
    }
}
