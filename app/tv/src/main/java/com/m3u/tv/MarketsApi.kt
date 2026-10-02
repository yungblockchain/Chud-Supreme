package com.m3u.tv

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * Markets tab data: DEX Screener's public API (https://docs.dexscreener.com/api/reference).
 * No key needed. Limits: 60 requests/min for boosts and profiles, 300/min for pairs, tokens and
 * search. Pump.fun tokens are read from the same data (Solana pairs on pump.fun's exchanges, or
 * mints ending in "pump"), since pump.fun and GMGN don't publish a documented public API.
 * Read-only: nothing here trades, signs or touches a wallet.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class TokenRef(
    val chainId: String,
    val tokenAddress: String,
    val icon: String? = null,
    val description: String? = null,
    val boosts: Int? = null,
) {
    val watchKey: String get() = "$chainId:$tokenAddress"

    companion object {
        fun fromWatchKey(key: String): TokenRef? {
            val chain = key.substringBefore(':', "")
            val address = key.substringAfter(':', "")
            return if (chain.isBlank() || address.isBlank()) null else TokenRef(chain, address)
        }
    }
}

@Immutable
data class MarketPair(
    val chainId: String,
    val dexId: String,
    val pairAddress: String,
    val url: String?,
    val baseAddress: String,
    val baseName: String,
    val baseSymbol: String,
    val quoteSymbol: String?,
    val priceUsd: Double?,
    val change5m: Double?,
    val change1h: Double?,
    val change6h: Double?,
    val change24h: Double?,
    val volume24h: Double?,
    val liquidityUsd: Double?,
    val marketCap: Double?,
    val fdv: Double?,
    val buys24h: Int?,
    val sells24h: Int?,
    val createdAt: Long?,
    val imageUrl: String?,
    val boosts: Int?,
    val description: String? = null,
) {
    val key: String get() = tokenKey(chainId, baseAddress)
    val watchKey: String get() = "$chainId:$baseAddress"
    val isPumpFun: Boolean
        get() = chainId == "solana" && (dexId.contains("pump", ignoreCase = true) || baseAddress.endsWith("pump"))
}

internal fun tokenKey(chainId: String, address: String) = "$chainId:${address.lowercase(Locale.ROOT)}"

internal object DexScreener {
    private const val BASE = "https://api.dexscreener.com"
    private const val TOKENS_PER_CALL = 30
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun topBoosted(): List<TokenRef> = refs(get("/token-boosts/top/v1"))
    suspend fun latestBoosted(): List<TokenRef> = refs(get("/token-boosts/latest/v1"))
    suspend fun latestProfiles(): List<TokenRef> = refs(get("/token-profiles/latest/v1"))

    suspend fun search(query: String): List<MarketPair> {
        val root = get("/latest/dex/search?q=" + URLEncoder.encode(query, Charsets.UTF_8.name()))
        return ((root as? JsonObject)?.get("pairs") as? JsonArray).orEmpty().mapNotNull(::parsePair)
    }

    /**
     * Market data for each token, in the order given. A token trades in several pools; the one
     * with the deepest liquidity is used, which is what DEX Screener's own token page shows.
     */
    suspend fun tokens(refs: List<TokenRef>): List<MarketPair> {
        if (refs.isEmpty()) return emptyList()
        val best = mutableMapOf<String, MarketPair>()
        refs.groupBy { it.chainId }.forEach { (chain, chainRefs) ->
            chainRefs.map { it.tokenAddress }.distinct().chunked(TOKENS_PER_CALL).forEach { chunk ->
                val pairs = (get("/tokens/v1/$chain/${chunk.joinToString(",")}") as? JsonArray)
                    .orEmpty()
                    .mapNotNull(::parsePair)
                pairs.forEach { pair ->
                    val current = best[pair.key]
                    if (current == null || (pair.liquidityUsd ?: 0.0) > (current.liquidityUsd ?: 0.0)) {
                        best[pair.key] = pair
                    }
                }
            }
        }
        return refs
            .mapNotNull { ref ->
                best[tokenKey(ref.chainId, ref.tokenAddress)]?.let { pair ->
                    pair.copy(
                        imageUrl = pair.imageUrl ?: ref.icon,
                        description = ref.description,
                        boosts = pair.boosts ?: ref.boosts,
                    )
                }
            }
            .distinctBy { it.key }
    }

    private fun refs(element: JsonElement?): List<TokenRef> {
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return items.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            TokenRef(
                chainId = item.text("chainId") ?: return@mapNotNull null,
                tokenAddress = item.text("tokenAddress") ?: return@mapNotNull null,
                icon = item.text("icon"),
                description = item.text("description"),
                boosts = item.number("totalAmount")?.toInt(),
            )
        }.distinctBy { tokenKey(it.chainId, it.tokenAddress) }
    }

    private fun parsePair(raw: JsonElement): MarketPair? {
        val pair = raw as? JsonObject ?: return null
        val base = pair["baseToken"] as? JsonObject ?: return null
        val quote = pair["quoteToken"] as? JsonObject
        val change = pair["priceChange"] as? JsonObject
        val volume = pair["volume"] as? JsonObject
        val txns24 = (pair["txns"] as? JsonObject)?.get("h24") as? JsonObject
        val info = pair["info"] as? JsonObject
        return MarketPair(
            chainId = pair.text("chainId") ?: return null,
            dexId = pair.text("dexId").orEmpty(),
            pairAddress = pair.text("pairAddress").orEmpty(),
            url = pair.text("url"),
            baseAddress = base.text("address") ?: return null,
            baseName = base.text("name").orEmpty(),
            baseSymbol = base.text("symbol").orEmpty(),
            quoteSymbol = quote?.text("symbol"),
            priceUsd = pair.text("priceUsd")?.toDoubleOrNull(),
            change5m = change?.number("m5"),
            change1h = change?.number("h1"),
            change6h = change?.number("h6"),
            change24h = change?.number("h24"),
            volume24h = volume?.number("h24"),
            liquidityUsd = (pair["liquidity"] as? JsonObject)?.number("usd"),
            marketCap = pair.number("marketCap"),
            fdv = pair.number("fdv"),
            buys24h = txns24?.number("buys")?.toInt(),
            sells24h = txns24?.number("sells")?.toInt(),
            createdAt = pair.number("pairCreatedAt")?.toLong(),
            imageUrl = info?.text("imageUrl"),
            boosts = (pair["boosts"] as? JsonObject)?.number("active")?.toInt(),
        )
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(name: String): Double? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    /** Throws on network errors, rate limits and bad responses so the screen can say so. */
    private suspend fun get(path: String): JsonElement? = withContext(Dispatchers.IO) {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("DEX Screener returned HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            json.parseToJsonElement(body)
        } finally {
            connection.disconnect()
        }
    }
}

/* ------------------------------------------------------------------ formatting */

private val SUBSCRIPT_DIGITS = charArrayOf('₀', '₁', '₂', '₃', '₄', '₅', '₆', '₇', '₈', '₉')

/** "$1,234.56", "$0.1234", or DEX Screener's "$0.0₅1234" for very small prices. */
internal fun formatPrice(price: Double?): String {
    if (price == null || price <= 0.0) return "—"
    return when {
        price >= 1.0 -> "$" + String.format(Locale.US, "%,.2f", price)
        price >= 0.001 -> "$" + String.format(Locale.US, "%.4f", price)
        else -> {
            // Zeros between the decimal point and the first significant digit.
            var zeros = floor(-log10(price)).toInt()
            if (price * Math.pow(10.0, zeros.toDouble()) >= 1.0) zeros -= 1
            val significant = String.format(Locale.US, "%.0f", price * Math.pow(10.0, zeros + 4.0))
                .take(4)
            val count = zeros.toString().map { SUBSCRIPT_DIGITS[it - '0'] }.joinToString("")
            "$0.0$count$significant"
        }
    }
}

/** "$950", "$12.3K", "$4.56M", "$1.2B". */
internal fun formatUsdCompact(value: Double?): String {
    if (value == null) return "—"
    val magnitude = abs(value)
    return when {
        magnitude >= 1e9 -> String.format(Locale.US, "$%.2fB", value / 1e9)
        magnitude >= 1e6 -> String.format(Locale.US, "$%.2fM", value / 1e6)
        magnitude >= 1e3 -> String.format(Locale.US, "$%.1fK", value / 1e3)
        else -> String.format(Locale.US, "$%.0f", value)
    }
}

internal fun formatPercent(value: Double?): String {
    if (value == null) return "—"
    return String.format(Locale.US, "%+.1f%%", value)
}

/** "45m", "7h", "12d" since the pool was created. */
internal fun formatAge(createdAt: Long?, now: Long): String {
    if (createdAt == null || createdAt <= 0L) return "—"
    val minutes = ((now - createdAt) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 48 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

internal fun chainLabel(chainId: String): String = when (chainId) {
    "solana" -> "Solana"
    "ethereum" -> "Ethereum"
    "base" -> "Base"
    "bsc" -> "BNB Chain"
    "arbitrum" -> "Arbitrum"
    "polygon" -> "Polygon"
    "sui" -> "Sui"
    "tron" -> "Tron"
    "robinhood" -> "Robinhood"
    "hyperliquid" -> "Hyperliquid"
    "coin" -> "Coin"
    else -> chainId.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

internal fun shortAddress(address: String): String =
    if (address.length <= 12) address else "${address.take(6)}…${address.takeLast(4)}"
