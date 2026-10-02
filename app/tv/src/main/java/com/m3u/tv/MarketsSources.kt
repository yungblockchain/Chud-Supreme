package com.m3u.tv

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/* -------------------------------------------------------------------------------------------------
 * More market sources, all read-only: GeckoTerminal (charts, Robinhood Chain pools), Hyperliquid
 * (perps and their candles), CoinGecko and CoinMarketCap (top coins; CMC with the person's own
 * key), and Telegram (a message to the person's own bot chat). Nothing here trades or signs.
 * Photon, GMGN and Arkham have no public API; tokens link to their pages by QR code instead.
 * ---------------------------------------------------------------------------------------------- */

/** One candle: time (ms), open, high, low, close. */
@Immutable
data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double)

enum class ChartRange { Hour, Day, Week, Month }

/** Special "chains" for rows that aren't DEX pools. */
internal const val CHAIN_HYPERLIQUID = "hyperliquid"
internal const val CHAIN_COIN = "coin"

private val marketsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private suspend fun httpJson(
    url: String,
    method: String = "GET",
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): JsonElement = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        marketsJson.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.number(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal object GeckoTerminal {
    private const val BASE = "https://api.geckoterminal.com/api/v2"

    /** GeckoTerminal's network ids for the chains DEX Screener names. */
    fun network(chainId: String): String? = when (chainId) {
        "solana" -> "solana"
        "ethereum" -> "eth"
        "base" -> "base"
        "bsc" -> "bsc"
        "arbitrum" -> "arbitrum"
        "polygon" -> "polygon_pos"
        "avalanche" -> "avax"
        "robinhood" -> "robinhood"
        "sui" -> "sui-network"
        "tron" -> "tron"
        "ton" -> "ton"
        else -> null
    }

    /** Tokens of the pools trending on a network right now (their base tokens). */
    suspend fun trendingTokens(network: String, chainId: String): List<TokenRef> {
        val root = httpJson("$BASE/networks/$network/trending_pools?page=1") as? JsonObject
            ?: return emptyList()
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { item ->
            val base = ((item as? JsonObject)?.get("relationships") as? JsonObject)
                ?.get("base_token") as? JsonObject
            val id = ((base?.get("data") as? JsonObject)?.text("id")) ?: return@mapNotNull null
            TokenRef(chainId = chainId, tokenAddress = id.substringAfter('_'))
        }.distinctBy { it.tokenAddress.lowercase(Locale.ROOT) }
    }

    suspend fun candles(network: String, poolAddress: String, range: ChartRange): List<Candle> {
        val (timeframe, aggregate, limit) = when (range) {
            ChartRange.Hour -> Triple("minute", 1, 60)
            ChartRange.Day -> Triple("minute", 15, 96)
            ChartRange.Week -> Triple("hour", 4, 42)
            ChartRange.Month -> Triple("day", 1, 30)
        }
        val root = httpJson(
            "$BASE/networks/$network/pools/$poolAddress/ohlcv/$timeframe?aggregate=$aggregate&limit=$limit"
        ) as? JsonObject ?: return emptyList()
        val list = (((root["data"] as? JsonObject)?.get("attributes") as? JsonObject)
            ?.get("ohlcv_list") as? JsonArray).orEmpty()
        return list.mapNotNull { row ->
            val values = (row as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                ?: return@mapNotNull null
            if (values.size < 5) return@mapNotNull null
            Candle(values[0].toLong() * 1000, values[1], values[2], values[3], values[4])
        }.sortedBy { it.time }
    }
}

internal object Hyperliquid {
    private const val INFO = "https://api.hyperliquid.xyz/info"

    /** Perpetuals by 24-hour volume, as market rows. */
    suspend fun perps(): List<MarketPair> {
        val root = httpJson(INFO, "POST", """{"type":"metaAndAssetCtxs"}""") as? JsonArray
            ?: return emptyList()
        val universe = ((root.getOrNull(0) as? JsonObject)?.get("universe") as? JsonArray).orEmpty()
        val contexts = (root.getOrNull(1) as? JsonArray).orEmpty()
        return universe.zip(contexts).mapNotNull { (meta, context) ->
            val asset = meta as? JsonObject ?: return@mapNotNull null
            val ctx = context as? JsonObject ?: return@mapNotNull null
            if ((asset["isDelisted"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val coin = asset.text("name") ?: return@mapNotNull null
            val mark = ctx.number("markPx") ?: return@mapNotNull null
            val previous = ctx.number("prevDayPx")
            MarketPair(
                chainId = CHAIN_HYPERLIQUID,
                dexId = "perps",
                pairAddress = coin,
                url = "https://app.hyperliquid.xyz/trade/$coin",
                baseAddress = coin,
                baseName = coin,
                baseSymbol = coin,
                quoteSymbol = "USD",
                priceUsd = mark,
                change5m = null,
                change1h = null,
                change6h = null,
                change24h = previous?.takeIf { it > 0 }?.let { (mark - it) / it * 100.0 },
                volume24h = ctx.number("dayNtlVlm"),
                // Open interest (in coins) times price.
                liquidityUsd = ctx.number("openInterest")?.times(mark),
                marketCap = null,
                fdv = null,
                buys24h = null,
                sells24h = null,
                createdAt = null,
                imageUrl = null,
                boosts = null,
                description = ctx.number("funding")?.let { funding ->
                    String.format(Locale.US, "Funding %.4f%% per hour", funding * 100)
                },
            )
        }.sortedByDescending { it.volume24h ?: 0.0 }
    }

    suspend fun candles(coin: String, range: ChartRange): List<Candle> {
        val now = System.currentTimeMillis()
        val (interval, span) = when (range) {
            ChartRange.Hour -> "1m" to 60 * 60_000L
            ChartRange.Day -> "15m" to 24 * 60 * 60_000L
            ChartRange.Week -> "4h" to 7 * 24 * 60 * 60_000L
            ChartRange.Month -> "1d" to 30 * 24 * 60 * 60_000L
        }
        val request = buildJsonObject {
            put("type", "candleSnapshot")
            putJsonObject("req") {
                put("coin", coin)
                put("interval", interval)
                put("startTime", now - span)
                put("endTime", now)
            }
        }.toString()
        val rows = httpJson(INFO, "POST", request) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            Candle(
                time = item.number("t")?.toLong() ?: return@mapNotNull null,
                open = item.number("o") ?: return@mapNotNull null,
                high = item.number("h") ?: return@mapNotNull null,
                low = item.number("l") ?: return@mapNotNull null,
                close = item.number("c") ?: return@mapNotNull null,
            )
        }
    }
}

/** Top coins by market cap: CoinMarketCap with the person's key, otherwise CoinGecko. */
internal object TopCoins {
    suspend fun coinMarketCap(apiKey: String): List<MarketPair> {
        val root = httpJson(
            "https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/latest?limit=100&convert=USD",
            headers = mapOf("X-CMC_PRO_API_KEY" to apiKey),
        ) as? JsonObject ?: return emptyList()
        return (root["data"] as? JsonArray).orEmpty().mapNotNull { element ->
            val coin = element as? JsonObject ?: return@mapNotNull null
            val quote = (coin["quote"] as? JsonObject)?.get("USD") as? JsonObject ?: return@mapNotNull null
            val id = coin.text("id") ?: return@mapNotNull null
            val symbol = coin.text("symbol") ?: return@mapNotNull null
            coinRow(
                id = "cmc:$id",
                symbol = symbol,
                name = coin.text("name") ?: symbol,
                price = quote.number("price"),
                change1h = quote.number("percent_change_1h"),
                change24h = quote.number("percent_change_24h"),
                volume = quote.number("volume_24h"),
                marketCap = quote.number("market_cap"),
                fdv = quote.number("fully_diluted_market_cap"),
                image = "https://s2.coinmarketcap.com/static/img/coins/64x64/$id.png",
                source = "coinmarketcap",
                url = coin.text("slug")?.let { "https://coinmarketcap.com/currencies/$it/" },
            )
        }
    }

    suspend fun coinGecko(): List<MarketPair> {
        val root = httpJson(
            "https://api.coingecko.com/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc" +
                "&per_page=100&page=1&price_change_percentage=1h,24h"
        ) as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val coin = element as? JsonObject ?: return@mapNotNull null
            val id = coin.text("id") ?: return@mapNotNull null
            val symbol = coin.text("symbol")?.uppercase(Locale.ROOT) ?: return@mapNotNull null
            coinRow(
                id = "cg:$id",
                symbol = symbol,
                name = coin.text("name") ?: symbol,
                price = coin.number("current_price"),
                change1h = coin.number("price_change_percentage_1h_in_currency"),
                change24h = coin.number("price_change_percentage_24h"),
                volume = coin.number("total_volume"),
                marketCap = coin.number("market_cap"),
                fdv = coin.number("fully_diluted_valuation"),
                image = coin.text("image"),
                source = "coingecko",
                url = "https://www.coingecko.com/en/coins/$id",
            )
        }
    }

    /** Price history for a CoinGecko coin. */
    suspend fun coinGeckoCandles(id: String, range: ChartRange): List<Candle> {
        val days = when (range) {
            ChartRange.Hour, ChartRange.Day -> "1"
            ChartRange.Week -> "7"
            ChartRange.Month -> "30"
        }
        val root = httpJson(
            "https://api.coingecko.com/api/v3/coins/${URLEncoder.encode(id, "UTF-8")}/market_chart?vs_currency=usd&days=$days"
        ) as? JsonObject ?: return emptyList()
        val cutoff = if (range == ChartRange.Hour) System.currentTimeMillis() - 60 * 60_000L else 0L
        return (root["prices"] as? JsonArray).orEmpty().mapNotNull { row ->
            val values = (row as? JsonArray) ?: return@mapNotNull null
            val time = (values.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
                ?: return@mapNotNull null
            val price = (values.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                ?: return@mapNotNull null
            Candle(time, price, price, price, price)
        }.filter { it.time >= cutoff }
    }

    private fun coinRow(
        id: String,
        symbol: String,
        name: String,
        price: Double?,
        change1h: Double?,
        change24h: Double?,
        volume: Double?,
        marketCap: Double?,
        fdv: Double?,
        image: String?,
        source: String,
        url: String?,
    ) = MarketPair(
        chainId = CHAIN_COIN,
        dexId = source,
        pairAddress = id,
        url = url,
        baseAddress = id,
        baseName = name,
        baseSymbol = symbol,
        quoteSymbol = "USD",
        priceUsd = price,
        change5m = null,
        change1h = change1h,
        change6h = null,
        change24h = change24h,
        volume24h = volume,
        liquidityUsd = null,
        marketCap = marketCap,
        fdv = fdv,
        buys24h = null,
        sells24h = null,
        createdAt = null,
        imageUrl = image,
        boosts = null,
    )
}

/** Candles for whatever kind of row this is, or empty when there's no chart source for it. */
internal suspend fun candlesFor(pair: MarketPair, range: ChartRange): List<Candle> = when {
    pair.chainId == CHAIN_HYPERLIQUID -> Hyperliquid.candles(pair.baseSymbol, range)
    pair.chainId == CHAIN_COIN && pair.pairAddress.startsWith("cg:") ->
        TopCoins.coinGeckoCandles(pair.pairAddress.removePrefix("cg:"), range)
    // CoinMarketCap's history needs a paid plan; big coins also trade on Hyperliquid.
    pair.chainId == CHAIN_COIN -> runCatching { Hyperliquid.candles(pair.baseSymbol, range) }
        .getOrDefault(emptyList())
    else -> GeckoTerminal.network(pair.chainId)
        ?.takeIf { pair.pairAddress.isNotBlank() }
        ?.let { GeckoTerminal.candles(it, pair.pairAddress, range) }
        .orEmpty()
}

/** Pages about a token, for the QR codes. */
internal fun tokenLinks(pair: MarketPair, tradingBot: String?): List<Pair<String, String>> = buildList {
    val bot = tradingBot?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val onChain = pair.chainId != CHAIN_HYPERLIQUID && pair.chainId != CHAIN_COIN
    if (bot != null && onChain) add("Telegram · @$bot" to "https://t.me/$bot?start=${pair.baseAddress}")
    pair.url?.let { add((if (onChain) "DEX Screener" else pair.dexId.replaceFirstChar { it.uppercase() }) to it) }
    if (pair.chainId == "solana") {
        add("GMGN" to "https://gmgn.ai/sol/token/${pair.baseAddress}")
        add("Photon" to "https://photon-sol.tinyastro.io/en/lp/${pair.pairAddress}")
    }
    if (onChain && pair.baseAddress.startsWith("0x")) {
        add("Arkham" to "https://intel.arkm.com/explorer/address/${pair.baseAddress}")
    }
}

internal object TelegramBot {
    /** Sends [text] to the person's own chat with their own bot. */
    suspend fun send(token: String, chatId: String, text: String) {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            put("disable_web_page_preview", true)
        }.toString()
        val result = httpJson("https://api.telegram.org/bot$token/sendMessage", "POST", body) as? JsonObject
        if ((result?.get("ok") as? JsonPrimitive)?.contentOrNull != "true") throw IOException("Telegram refused")
    }

    fun summary(pair: MarketPair, links: List<Pair<String, String>>): String = buildString {
        append(pair.baseSymbol).append(" · ").append(pair.baseName).append('\n')
        append(chainLabel(pair.chainId)).append('\n')
        append("Price: ").append(formatPrice(pair.priceUsd))
        pair.change24h?.let { append("  (24h ").append(formatPercent(it)).append(')') }
        append('\n')
        pair.marketCap?.let { append("Market cap: ").append(formatUsdCompact(it)).append('\n') }
        pair.liquidityUsd?.let { append("Liquidity: ").append(formatUsdCompact(it)).append('\n') }
        pair.volume24h?.let { append("Volume 24h: ").append(formatUsdCompact(it)).append('\n') }
        if (pair.chainId != CHAIN_HYPERLIQUID && pair.chainId != CHAIN_COIN) {
            append("Address: ").append(pair.baseAddress).append('\n')
        }
        links.forEach { (label, url) -> append(label).append(": ").append(url).append('\n') }
        append("Sent from Chud Supreme")
    }
}
