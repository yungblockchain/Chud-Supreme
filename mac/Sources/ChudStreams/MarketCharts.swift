import AppKit
import SwiftUI

// Live candlestick charts for the Markets detail panel, drawn with Canvas in the neon style.
// Candle sources (all free, no keys):
// - DEX tokens: GeckoTerminal's public OHLCV API for the token's pool. It allows about 30 calls
//   a minute, so candles are cached for 25 seconds per pool and timeframe.
// - Big coins: CoinGecko's OHLC endpoint. It has no volume, and the free API picks the candle
//   size from the range (30 minutes up to 2 days, 4 hours up to 30 days, 4 days beyond).
// - Hyperliquid perps: Hyperliquid's candleSnapshot info request.
// Charts refresh every 30 seconds while visible. Read-only, like the rest of Markets.

// MARK: Data

struct ChartCandle: Hashable {
    let time: Date
    let open: Double
    let high: Double
    let low: Double
    let close: Double
    let volume: Double?
}

enum ChartTimeframe: String, CaseIterable, Identifiable {
    case m1 = "1m"
    case m5 = "5m"
    case m15 = "15m"
    case h1 = "1h"
    case h4 = "4h"
    case d1 = "1D"

    var id: String { rawValue }

    var seconds: TimeInterval {
        switch self {
        case .m1: return 60
        case .m5: return 300
        case .m15: return 900
        case .h1: return 3_600
        case .h4: return 14_400
        case .d1: return 86_400
        }
    }

    /// GeckoTerminal allows minute (1, 5, 15), hour (1, 4, 12) and day (1).
    var geckoPeriod: String {
        switch self {
        case .m1, .m5, .m15: return "minute"
        case .h1, .h4: return "hour"
        case .d1: return "day"
        }
    }

    var geckoAggregate: Int {
        switch self {
        case .m1, .h1, .d1: return 1
        case .m5: return 5
        case .m15: return 15
        case .h4: return 4
        }
    }

    var hyperliquidInterval: String {
        switch self {
        case .d1: return "1d"
        default: return rawValue
        }
    }

    /// CoinGecko's free OHLC picks candle size from the range, so this is the closest it gets.
    var coinGeckoDays: Int {
        switch self {
        case .m1, .m5, .m15: return 1
        case .h1: return 2
        case .h4: return 30
        case .d1: return 365
        }
    }
}

/// Where a chart's candles come from.
enum ChartSource: Hashable {
    /// A DEX pool on GeckoTerminal; `token` is the token to chart (DEX Screener's base token).
    case gecko(network: String, pool: String, token: String)
    case coinGecko(id: String)
    /// A coin known only by symbol and name (CoinMarketCap rows): the CoinGecko id is looked up first.
    case coinGeckoSymbol(symbol: String, name: String)
    case hyperliquid(coin: String)

    var cacheKey: String {
        switch self {
        case .gecko(let network, let pool, let token): return "gt:\(network):\(pool):\(token)"
        case .coinGecko(let id): return "cg:\(id)"
        case .coinGeckoSymbol(let symbol, let name): return "cgs:\(symbol):\(name)"
        case .hyperliquid(let coin): return "hl:\(coin)"
        }
    }

    var hasVolume: Bool {
        switch self {
        case .gecko, .hyperliquid: return true
        case .coinGecko, .coinGeckoSymbol: return false
        }
    }

    var provider: String {
        switch self {
        case .gecko: return "GeckoTerminal"
        case .coinGecko, .coinGeckoSymbol: return "CoinGecko"
        case .hyperliquid: return "Hyperliquid"
        }
    }

    /// DEX Screener chain id to GeckoTerminal network id.
    static func geckoNetwork(for chainId: String) -> String {
        switch chainId {
        case "ethereum": return "eth"
        case "polygon": return "polygon_pos"
        case "avalanche": return "avax"
        case "sui": return "sui-network"
        default: return chainId // solana, base, bsc, arbitrum and ton use the same id.
        }
    }
}

enum ChartAPI {
    /// Candles oldest first. Throws on network errors, rate limits and bad responses.
    static func candles(for source: ChartSource, timeframe: ChartTimeframe) async throws -> [ChartCandle] {
        let raw: [ChartCandle]
        switch source {
        case .gecko(let network, let pool, let token):
            raw = try await gecko(network: network, pool: pool, token: token, timeframe: timeframe)
        case .coinGecko(let id):
            raw = try await coinGecko(id: id, timeframe: timeframe)
        case .coinGeckoSymbol(let symbol, let name):
            let id: String = try await MarketCoinIds.resolve(symbol: symbol, name: name)
            raw = try await coinGecko(id: id, timeframe: timeframe)
        case .hyperliquid(let coin):
            raw = try await hyperliquid(coin: coin, timeframe: timeframe)
        }
        return tidy(raw)
    }

    static func message(for error: Error) -> String {
        let code: Int? = (error as? MarketHTTPError)?.statusCode
        if code == 404 { return "No chart data for this market yet." }
        if code == 429 { return "The chart service is busy (rate limit). Trying again in 30 seconds." }
        if let httpError = error as? MarketHTTPError, httpError.statusCode == nil {
            return "No chart data for this market yet."
        }
        return "Couldn't load the chart. Trying again in 30 seconds."
    }

    private static func gecko(network: String, pool: String, token: String, timeframe: ChartTimeframe) async throws -> [ChartCandle] {
        let path: String = "https://api.geckoterminal.com/api/v2/networks/\(network)/pools/\(evmSafe(pool))/ohlcv/\(timeframe.geckoPeriod)"
        let query: String = "?aggregate=\(timeframe.geckoAggregate)&limit=200&currency=usd"
        do {
            // token= keeps the chart on DEX Screener's base token even when GeckoTerminal lists
            // the pool the other way round.
            let root: Any = try await MarketHTTP.get(path + query + "&token=" + queryEncoded(evmSafe(token)))
            return geckoCandles(root)
        } catch {
            let code: Int? = (error as? MarketHTTPError)?.statusCode
            guard code == 400 || code == 422 else { throw error }
        }
        let root: Any = try await MarketHTTP.get(path + query)
        return geckoCandles(root)
    }

    private static func geckoCandles(_ root: Any) -> [ChartCandle] {
        let data = (root as? [String: Any])?["data"] as? [String: Any]
        let attributes = data?["attributes"] as? [String: Any]
        let list: [Any] = attributes?["ohlcv_list"] as? [Any] ?? []
        return list.compactMap { entry -> ChartCandle? in
            guard let values = entry as? [Any], values.count >= 5 else { return nil }
            let volume: Any? = values.count > 5 ? values[5] : nil
            return makeCandle(time: values[0], open: values[1], high: values[2], low: values[3], close: values[4], volume: volume)
        }
    }

    private static func coinGecko(id: String, timeframe: ChartTimeframe) async throws -> [ChartCandle] {
        let encoded: String = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let address: String = "https://api.coingecko.com/api/v3/coins/\(encoded)/ohlc?vs_currency=usd&days=\(timeframe.coinGeckoDays)"
        let root: Any = try await MarketHTTP.get(address)
        let list: [Any] = root as? [Any] ?? []
        return list.compactMap { entry -> ChartCandle? in
            guard let values = entry as? [Any], values.count >= 5 else { return nil }
            return makeCandle(time: values[0], open: values[1], high: values[2], low: values[3], close: values[4], volume: nil)
        }
    }

    private static func hyperliquid(coin: String, timeframe: ChartTimeframe) async throws -> [ChartCandle] {
        let now: TimeInterval = Date().timeIntervalSince1970
        let end: Int = Int(now * 1000)
        let start: Int = Int((now - timeframe.seconds * 200) * 1000)
        let request: [String: Any] = [
            "coin": coin,
            "interval": timeframe.hyperliquidInterval,
            "startTime": start,
            "endTime": end,
        ]
        let root: Any = try await MarketHTTP.post(MarketPerps.infoURL, body: ["type": "candleSnapshot", "req": request])
        let list: [Any] = root as? [Any] ?? []
        return list.compactMap { entry -> ChartCandle? in
            guard let item = entry as? [String: Any] else { return nil }
            guard let parsed = makeCandle(time: item["t"], open: item["o"], high: item["h"], low: item["l"], close: item["c"], volume: item["v"]) else {
                return nil
            }
            // Hyperliquid reports volume in coins; show it in dollars like the other sources.
            let dollars: Double? = parsed.volume.map { $0 * parsed.close }
            return ChartCandle(time: parsed.time, open: parsed.open, high: parsed.high, low: parsed.low, close: parsed.close, volume: dollars)
        }
    }

    /// Accepts numbers or numeric strings; timestamps in seconds or milliseconds.
    private static func makeCandle(time: Any?, open: Any?, high: Any?, low: Any?, close: Any?, volume: Any?) -> ChartCandle? {
        guard let stamp = number(time),
              let o = number(open),
              let h = number(high),
              let l = number(low),
              let c = number(close) else { return nil }
        guard stamp > 0, o > 0, c > 0, o.isFinite, c.isFinite, h.isFinite, l.isFinite else { return nil }
        let seconds: Double = stamp > 100_000_000_000 ? stamp / 1000 : stamp
        let top: Double = max(h, o, c)
        let bottom: Double = l > 0 ? min(l, o, c) : min(o, c)
        return ChartCandle(time: Date(timeIntervalSince1970: seconds), open: o, high: top, low: bottom, close: c, volume: number(volume))
    }

    private static func number(_ value: Any?) -> Double? {
        if let boxed = value as? NSNumber { return boxed.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }

    /// Oldest first, one candle per timestamp.
    private static func tidy(_ candles: [ChartCandle]) -> [ChartCandle] {
        let sorted: [ChartCandle] = candles.sorted { $0.time < $1.time }
        var result: [ChartCandle] = []
        for candle in sorted {
            if let last = result.last, last.time == candle.time {
                result[result.count - 1] = candle
            } else {
                result.append(candle)
            }
        }
        return result
    }

    /// EVM addresses in lower case; Solana, TON and Sui ids are case-sensitive and left alone.
    private static func evmSafe(_ address: String) -> String {
        address.hasPrefix("0x") && address.count == 42 ? address.lowercased() : address
    }

    private static func queryEncoded(_ value: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#:")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

struct ChartCacheEntry {
    let fetchedAt: Date
    let candles: [ChartCandle]
}

@MainActor
final class ChartModel: ObservableObject {
    @Published private(set) var candles: [ChartCandle] = []
    @Published private(set) var loading = false
    @Published private(set) var failed = false
    @Published private(set) var message: String? = nil

    private var shownKey: String? = nil

    /// Shared by every chart, so switching tokens or timeframes back and forth stays inside
    /// GeckoTerminal's rate limit.
    private static var cache: [String: ChartCacheEntry] = [:]
    private static let cacheSeconds: TimeInterval = 25

    var hasVolume: Bool { candles.contains { ($0.volume ?? 0) > 0 } }

    /// Loads now and every 30 seconds until the task is cancelled (the selection or timeframe
    /// changed, or the chart left the screen). Cancelling also cancels the request in flight.
    func run(source: ChartSource, timeframe: ChartTimeframe) async {
        let key: String = source.cacheKey + "|" + timeframe.rawValue
        if shownKey != key {
            shownKey = key
            candles = []
            loading = false
            failed = false
            message = nil
        }
        while !Task.isCancelled {
            await load(source: source, timeframe: timeframe, key: key)
            try? await Task.sleep(nanoseconds: 30_000_000_000)
        }
    }

    private func load(source: ChartSource, timeframe: ChartTimeframe, key: String) async {
        if let entry = ChartModel.cache[key], Date().timeIntervalSince(entry.fetchedAt) < ChartModel.cacheSeconds {
            show(entry.candles)
            return
        }
        loading = true
        do {
            let fetched: [ChartCandle] = try await ChartAPI.candles(for: source, timeframe: timeframe)
            if Task.isCancelled || shownKey != key { return }
            ChartModel.store(fetched, key: key)
            show(fetched)
        } catch {
            if Task.isCancelled || shownKey != key || MarketHTTP.isCancellation(error) { return }
            // Keep the last good candles on screen; the footer says the update failed.
            loading = false
            failed = true
            message = ChartAPI.message(for: error)
        }
    }

    private func show(_ fresh: [ChartCandle]) {
        candles = fresh
        loading = false
        failed = false
        message = fresh.isEmpty ? "No trades in this timeframe yet." : nil
    }

    private static func store(_ fresh: [ChartCandle], key: String) {
        let now = Date()
        cache = cache.filter { now.timeIntervalSince($0.value.fetchedAt) < 300 }
        cache[key] = ChartCacheEntry(fetchedAt: now, candles: fresh)
    }
}

// MARK: Layout

/// Where everything sits on the canvas: a legend band on top, candles, volume bars, time labels
/// underneath and price labels on the right. Candles are spaced by index, newest on the right.
struct ChartLayout {
    static let rightGutter: CGFloat = 66
    static let bottomGutter: CGFloat = 20
    static let topInset: CGFloat = 36
    static let targetStep: CGFloat = 5

    let candles: [ChartCandle]
    let showVolume: Bool
    let plot: CGRect
    let volumeArea: CGRect
    let low: Double
    let high: Double
    let maxVolume: Double
    let step: CGFloat
    let tickStep: Double

    init(all: [ChartCandle], size: CGSize, showVolume: Bool) {
        let width: CGFloat = max(size.width - ChartLayout.rightGutter, 20)
        let capacity: Int = max(Int(width / ChartLayout.targetStep), 10)
        let visible: [ChartCandle] = Array(all.suffix(capacity))
        let usable: CGFloat = max(size.height - ChartLayout.topInset - ChartLayout.bottomGutter, 40)
        let volumeBand: CGFloat = showVolume ? floor(usable * 0.22) : 0
        let gap: CGFloat = showVolume ? 6 : 0
        let plotHeight: CGFloat = usable - volumeBand - gap
        let range: (low: Double, high: Double) = ChartLayout.priceRange(visible)
        candles = visible
        self.showVolume = showVolume
        plot = CGRect(x: 0, y: ChartLayout.topInset, width: width, height: plotHeight)
        volumeArea = CGRect(x: 0, y: ChartLayout.topInset + plotHeight + gap, width: width, height: volumeBand)
        low = range.low
        high = range.high
        maxVolume = visible.reduce(0.0) { partial, candle in max(partial, candle.volume ?? 0) }
        step = width / CGFloat(max(visible.count, 1))
        tickStep = ChartLayout.niceStep(range.high - range.low, count: 4)
    }

    var bottom: CGFloat { showVolume ? volumeArea.maxY : plot.maxY }

    var bodyWidth: CGFloat { max(1, min(step * 0.7, 9)) }

    var span: TimeInterval {
        guard let first = candles.first, let last = candles.last else { return 0 }
        return last.time.timeIntervalSince(first.time)
    }

    func x(_ index: Int) -> CGFloat {
        plot.minX + step * (CGFloat(index) + 0.5)
    }

    func y(_ price: Double) -> CGFloat {
        let fraction: Double = (price - low) / (high - low)
        return plot.maxY - CGFloat(fraction) * plot.height
    }

    func price(atY y: CGFloat) -> Double {
        let fraction: Double = Double((plot.maxY - y) / plot.height)
        return low + fraction * (high - low)
    }

    func volumeHeight(_ volume: Double) -> CGFloat {
        guard maxVolume > 0 else { return 0 }
        return CGFloat(volume / maxVolume) * volumeArea.height
    }

    /// The candle under the pointer, if the pointer is over the candles or volume bars.
    func index(at point: CGPoint?) -> Int? {
        guard let point, !candles.isEmpty else { return nil }
        guard point.x >= plot.minX, point.x <= plot.maxX, point.y >= plot.minY, point.y <= bottom else { return nil }
        let raw: Int = Int((point.x - plot.minX) / step)
        return min(max(raw, 0), candles.count - 1)
    }

    func priceTicks() -> [Double] {
        guard tickStep > 0, high > low else { return [] }
        var ticks: [Double] = []
        var value: Double = (low / tickStep).rounded(.up) * tickStep
        var rounds = 0
        while value <= high && ticks.count < 12 && rounds < 40 {
            if value > 0 { ticks.append(value) }
            value += tickStep
            rounds += 1
        }
        return ticks
    }

    func timeLabelIndices() -> [Int] {
        let count: Int = candles.count
        if count < 2 { return count == 1 ? [0] : [] }
        let every: Int = max(count / 4, 1)
        var result: [Int] = []
        var position: Int = every / 2
        while position < count {
            result.append(position)
            position += every
        }
        return result
    }

    static func priceRange(_ candles: [ChartCandle]) -> (low: Double, high: Double) {
        guard let first = candles.first else { return (0, 1) }
        var low: Double = first.low
        var high: Double = first.high
        for candle in candles {
            low = min(low, candle.low)
            high = max(high, candle.high)
        }
        if high <= low {
            let pad: Double = max(abs(high) * 0.01, 1e-12)
            return (low - pad, high + pad)
        }
        let pad: Double = (high - low) * 0.06
        return (low - pad, high + pad)
    }

    /// 1, 2 or 5 times a power of ten, giving about `count` gridlines across `range`.
    static func niceStep(_ range: Double, count: Int) -> Double {
        let rough: Double = range / Double(max(count, 1))
        guard rough > 0, rough.isFinite else { return 0 }
        let magnitude: Double = pow(10, floor(log10(rough)))
        let residual: Double = rough / magnitude
        let nice: Double
        if residual > 5 {
            nice = 10
        } else if residual > 2 {
            nice = 5
        } else if residual > 1 {
            nice = 2
        } else {
            nice = 1
        }
        return nice * magnitude
    }

    /// The candle size: the smallest gap between candles (DEX pools skip minutes with no trades).
    static func interval(of candles: [ChartCandle]) -> TimeInterval? {
        guard candles.count > 1 else { return nil }
        var best: TimeInterval = .greatestFiniteMagnitude
        for position in 1..<candles.count {
            let gap: TimeInterval = candles[position].time.timeIntervalSince(candles[position - 1].time)
            if gap > 0 && gap < best { best = gap }
        }
        return best == .greatestFiniteMagnitude ? nil : best
    }
}

enum ChartFormat {
    static let clockFormatter: DateFormatter = makeFormatter("HH:mm")
    static let dayFormatter: DateFormatter = makeFormatter("d MMM")
    static let fullFormatter: DateFormatter = makeFormatter("d MMM HH:mm")

    static func makeFormatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = format
        return formatter
    }

    static func axisTime(_ date: Date, daily: Bool) -> String {
        daily ? dayFormatter.string(from: date) : clockFormatter.string(from: date)
    }

    static func hoverTime(_ date: Date) -> String {
        fullFormatter.string(from: date)
    }

    /// Price labels with as many decimals as the gridline spacing needs.
    static func axisPrice(_ value: Double, step: Double) -> String {
        guard value > 0, value.isFinite else { return "" }
        if value < 0.001 { return MarketFormat.price(value) }
        let wanted: Int = step > 0 && step.isFinite ? Int(ceil(-log10(step))) : 2
        let minimum: Int = value >= 1000 ? 0 : 2
        let decimals: Int = min(max(wanted, minimum), 8)
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        let body: String = formatter.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)
        return "$" + body
    }

    /// "1m", "30m", "4h", "1D", "4D".
    static func intervalLabel(_ seconds: TimeInterval) -> String {
        let minutes: Int = max(Int((seconds / 60).rounded()), 1)
        if minutes < 60 { return "\(minutes)m" }
        if minutes < 1_440 { return "\(minutes / 60)h" }
        return "\(minutes / 1_440)D"
    }

    static func change(_ candle: ChartCandle) -> Double? {
        guard candle.open > 0 else { return nil }
        return (candle.close - candle.open) / candle.open * 100
    }
}

// MARK: Drawing

enum ChartRenderer {
    static let axisFont: Font = .system(size: 10, weight: .regular, design: .monospaced)
    static let tagFont: Font = .system(size: 10, weight: .bold, design: .monospaced)

    static func draw(layout: ChartLayout, hoverIndex: Int?, hoverPoint: CGPoint?, context: GraphicsContext) {
        guard !layout.candles.isEmpty else { return }
        drawGrid(layout, context: context)
        drawPriceLabels(layout, context: context)
        drawTimeLabels(layout, context: context)
        if layout.showVolume {
            drawVolume(layout, context: context)
        }
        drawCandles(layout, context: context)
        drawLastPrice(layout, context: context)
        if let hoverIndex {
            drawCrosshair(layout, index: hoverIndex, point: hoverPoint, context: context)
        }
    }

    static func color(for candle: ChartCandle) -> Color {
        candle.close >= candle.open ? Neon.positive : Neon.danger
    }

    static func line(from start: CGPoint, to end: CGPoint) -> Path {
        var path = Path()
        path.move(to: start)
        path.addLine(to: end)
        return path
    }

    static func drawGrid(_ layout: ChartLayout, context: GraphicsContext) {
        let grid = GraphicsContext.Shading.color(Neon.cyan.opacity(0.08))
        for price in layout.priceTicks() {
            let y: CGFloat = layout.y(price)
            let path: Path = line(from: CGPoint(x: layout.plot.minX, y: y), to: CGPoint(x: layout.plot.maxX, y: y))
            context.stroke(path, with: grid, lineWidth: 1)
        }
        for index in layout.timeLabelIndices() {
            let x: CGFloat = layout.x(index)
            let path: Path = line(from: CGPoint(x: x, y: layout.plot.minY), to: CGPoint(x: x, y: layout.bottom))
            context.stroke(path, with: grid, lineWidth: 1)
        }
        var edge = Path()
        edge.move(to: CGPoint(x: layout.plot.maxX, y: layout.plot.minY))
        edge.addLine(to: CGPoint(x: layout.plot.maxX, y: layout.bottom))
        edge.addLine(to: CGPoint(x: layout.plot.minX, y: layout.bottom))
        context.stroke(edge, with: .color(Neon.cyan.opacity(0.22)), lineWidth: 1)
    }

    static func drawPriceLabels(_ layout: ChartLayout, context: GraphicsContext) {
        for price in layout.priceTicks() {
            let label: String = ChartFormat.axisPrice(price, step: layout.tickStep)
            let text: Text = Text(label).font(axisFont).foregroundColor(Neon.textMuted)
            let point = CGPoint(x: layout.plot.maxX + 6, y: layout.y(price))
            context.draw(text, at: point, anchor: .leading)
        }
    }

    static func drawTimeLabels(_ layout: ChartLayout, context: GraphicsContext) {
        let daily: Bool = layout.span > 36 * 3_600
        for index in layout.timeLabelIndices() {
            let label: String = ChartFormat.axisTime(layout.candles[index].time, daily: daily)
            let text: Text = Text(label).font(axisFont).foregroundColor(Neon.textMuted)
            let point = CGPoint(x: layout.x(index), y: layout.bottom + 4)
            context.draw(text, at: point, anchor: .top)
        }
    }

    static func drawVolume(_ layout: ChartLayout, context: GraphicsContext) {
        guard layout.maxVolume > 0 else { return }
        let width: CGFloat = layout.bodyWidth
        for (index, candle) in layout.candles.enumerated() {
            let volume: Double = candle.volume ?? 0
            if volume <= 0 { continue }
            let height: CGFloat = max(layout.volumeHeight(volume), 1)
            let rect = CGRect(x: layout.x(index) - width / 2, y: layout.volumeArea.maxY - height, width: width, height: height)
            context.fill(Path(rect), with: .color(color(for: candle).opacity(0.4)))
        }
    }

    static func drawCandles(_ layout: ChartLayout, context: GraphicsContext) {
        let width: CGFloat = layout.bodyWidth
        for (index, candle) in layout.candles.enumerated() {
            let x: CGFloat = layout.x(index)
            let tint: Color = color(for: candle)
            let wick: Path = line(from: CGPoint(x: x, y: layout.y(candle.high)), to: CGPoint(x: x, y: layout.y(candle.low)))
            context.stroke(wick, with: .color(tint), lineWidth: 1)
            let top: CGFloat = layout.y(max(candle.open, candle.close))
            let bottom: CGFloat = layout.y(min(candle.open, candle.close))
            let body = CGRect(x: x - width / 2, y: top, width: width, height: max(bottom - top, 1))
            context.fill(Path(body), with: .color(tint))
        }
    }

    static func drawLastPrice(_ layout: ChartLayout, context: GraphicsContext) {
        guard let last = layout.candles.last else { return }
        let raw: CGFloat = layout.y(last.close)
        let y: CGFloat = min(max(raw, layout.plot.minY), layout.plot.maxY)
        let path: Path = line(from: CGPoint(x: layout.plot.minX, y: y), to: CGPoint(x: layout.plot.maxX, y: y))
        context.stroke(path, with: .color(Neon.cyan.opacity(0.85)), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
        let label: String = ChartFormat.axisPrice(last.close, step: layout.tickStep / 10)
        drawAxisTag(label, y: y, fill: Neon.cyan, layout: layout, context: context)
    }

    static func drawCrosshair(_ layout: ChartLayout, index: Int, point: CGPoint?, context: GraphicsContext) {
        let x: CGFloat = layout.x(index)
        let style = StrokeStyle(lineWidth: 1, dash: [3, 3])
        let shading = GraphicsContext.Shading.color(Neon.magenta.opacity(0.75))
        let vertical: Path = line(from: CGPoint(x: x, y: layout.plot.minY), to: CGPoint(x: x, y: layout.bottom))
        context.stroke(vertical, with: shading, style: style)
        if let point, point.y >= layout.plot.minY, point.y <= layout.plot.maxY {
            let horizontal: Path = line(from: CGPoint(x: layout.plot.minX, y: point.y), to: CGPoint(x: layout.plot.maxX, y: point.y))
            context.stroke(horizontal, with: shading, style: style)
            let price: Double = layout.price(atY: point.y)
            let label: String = ChartFormat.axisPrice(price, step: layout.tickStep / 10)
            drawAxisTag(label, y: point.y, fill: Neon.magenta, layout: layout, context: context)
        }
        let time: String = ChartFormat.hoverTime(layout.candles[index].time)
        drawTimeTag(time, x: x, layout: layout, context: context)
    }

    /// A filled price tag on the right-hand axis.
    static func drawAxisTag(_ label: String, y: CGFloat, fill: Color, layout: ChartLayout, context: GraphicsContext) {
        let text: Text = Text(label).font(tagFont).foregroundColor(Neon.onCyan)
        let resolved = context.resolve(text)
        let size: CGSize = resolved.measure(in: CGSize(width: 200, height: 30))
        let rect = CGRect(x: layout.plot.maxX + 1, y: y - size.height / 2 - 2, width: size.width + 8, height: size.height + 4)
        context.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(fill))
        context.draw(resolved, at: CGPoint(x: rect.minX + 4, y: y), anchor: .leading)
    }

    /// A filled time tag under the crosshair, kept inside the plot's width.
    static func drawTimeTag(_ label: String, x: CGFloat, layout: ChartLayout, context: GraphicsContext) {
        let text: Text = Text(label).font(tagFont).foregroundColor(Neon.onCyan)
        let resolved = context.resolve(text)
        let size: CGSize = resolved.measure(in: CGSize(width: 200, height: 30))
        let width: CGFloat = size.width + 8
        let left: CGFloat = max(min(x - width / 2, layout.plot.maxX - width), layout.plot.minX)
        let rect = CGRect(x: left, y: layout.bottom + 2, width: width, height: size.height + 4)
        context.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(Neon.magenta))
        context.draw(resolved, at: CGPoint(x: rect.midX, y: rect.midY), anchor: .center)
    }
}

// MARK: Views

/// Timeframe buttons, the chart and a status footer. Give it an `.id` per market so each
/// selection starts fresh; the load task restarts whenever the timeframe changes.
@MainActor
struct MarketChartPanel: View {
    let source: ChartSource
    @StateObject private var chart = ChartModel()
    @AppStorage("markets.chartTimeframe") private var timeframeRaw: String = ChartTimeframe.m15.rawValue

    private var timeframe: ChartTimeframe { ChartTimeframe(rawValue: timeframeRaw) ?? .m15 }
    private var taskKey: String { source.cacheKey + "|" + timeframe.rawValue }

    var body: some View {
        let showVolume: Bool = source.hasVolume && chart.hasVolume
        let selected: ChartTimeframe = timeframe
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                ForEach(ChartTimeframe.allCases) { frame in
                    ChartTimeframeButton(title: frame.rawValue, selected: frame == selected) {
                        timeframeRaw = frame.rawValue
                    }
                }
                Spacer(minLength: 0)
                if chart.loading {
                    ProgressView().controlSize(.small)
                }
            }
            ZStack {
                ChartCanvasView(candles: chart.candles, showVolume: showVolume)
                if chart.candles.isEmpty {
                    Text(chart.message ?? "Loading chart…")
                        .font(NeonFont.body(13))
                        .foregroundColor(chart.failed ? Neon.danger : Neon.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(16)
                }
            }
            .frame(height: 250)
            .background(HudShape(cut: 8).fill(Neon.background.opacity(0.6)))
            .overlay(HudShape(cut: 8).stroke(Neon.cyan.opacity(0.2), lineWidth: 1))
            Text(footer)
                .font(NeonFont.body(11))
                .foregroundColor(chart.failed ? Neon.danger : Neon.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .task(id: taskKey) {
            await chart.run(source: source, timeframe: timeframe)
        }
    }

    private var footer: String {
        if chart.failed && !chart.candles.isEmpty {
            return "The last chart update failed, so these candles may be out of date. Trying again in 30 seconds."
        }
        let actual: TimeInterval = ChartLayout.interval(of: chart.candles) ?? timeframe.seconds
        let size: String = ChartFormat.intervalLabel(actual)
        if !source.hasVolume && !chart.candles.isEmpty && abs(actual - timeframe.seconds) > 1 {
            return "CoinGecko's free data has \(size) candles for this range, without volume. Refreshes every 30 seconds."
        }
        return "\(source.provider), \(size) candles. Refreshes every 30 seconds; hover for prices."
    }
}

@MainActor
private struct ChartTimeframeButton: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        let textColor: Color = selected ? Neon.onCyan : (hovering ? Neon.cyan : Neon.textSecondary)
        Button(action: action) {
            Text(title)
                .font(NeonFont.body(12, bold: true))
                .foregroundColor(textColor)
                .frame(minWidth: 30)
                .padding(.horizontal, 4)
                .padding(.vertical, 4)
                .background(HudShape(cut: 5).fill(selected ? Neon.cyan : Neon.surface))
                .overlay(HudShape(cut: 5).stroke(Neon.cyan.opacity(selected ? 0.9 : 0.35), lineWidth: 1))
                .contentShape(HudShape(cut: 5))
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .help("Show \(title) candles")
    }
}

@MainActor
private struct ChartCanvasView: View {
    let candles: [ChartCandle]
    let showVolume: Bool
    @State private var hoverPoint: CGPoint? = nil

    var body: some View {
        GeometryReader { proxy in
            plot(size: proxy.size)
        }
    }

    private func plot(size: CGSize) -> some View {
        let layout = ChartLayout(all: candles, size: size, showVolume: showVolume)
        let point: CGPoint? = hoverPoint
        let index: Int? = layout.index(at: point)
        let focus: ChartCandle? = index.map { layout.candles[$0] } ?? layout.candles.last
        let volumeShown: Bool = showVolume
        return ZStack(alignment: .topLeading) {
            Canvas { context, _ in
                ChartRenderer.draw(layout: layout, hoverIndex: index, hoverPoint: point, context: context)
            }
            if let focus {
                ChartLegend(candle: focus, showVolume: volumeShown)
                    .padding(.leading, 6)
                    .padding(.top, 4)
                    .allowsHitTesting(false)
            }
        }
        .contentShape(Rectangle())
        .onContinuousHover { phase in
            switch phase {
            case .active(let location):
                hoverPoint = location
            case .ended:
                hoverPoint = nil
            }
        }
    }
}

/// Open, high, low, close, time, volume and change for the hovered candle (or the latest one).
@MainActor
private struct ChartLegend: View {
    let candle: ChartCandle
    let showVolume: Bool

    var body: some View {
        let tint: Color = candle.close >= candle.open ? Neon.positive : Neon.danger
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 7) {
                value("O", candle.open, tint)
                value("H", candle.high, tint)
                value("L", candle.low, tint)
                value("C", candle.close, tint)
            }
            Text(detail)
                .font(NeonFont.body(10))
                .foregroundColor(Neon.textSecondary)
        }
        .lineLimit(1)
    }

    private var detail: String {
        var parts: [String] = [ChartFormat.hoverTime(candle.time)]
        if showVolume, let volume = candle.volume {
            parts.append("Vol " + MarketFormat.usd(volume))
        }
        parts.append(MarketFormat.percent(ChartFormat.change(candle)))
        return parts.joined(separator: "  ·  ")
    }

    private func value(_ label: String, _ price: Double, _ tint: Color) -> some View {
        HStack(spacing: 2) {
            Text(label)
                .font(NeonFont.body(10, bold: true))
                .foregroundColor(Neon.textMuted)
            Text(MarketFormat.price(price))
                .font(NeonFont.body(10, bold: true))
                .foregroundColor(tint)
        }
    }
}
