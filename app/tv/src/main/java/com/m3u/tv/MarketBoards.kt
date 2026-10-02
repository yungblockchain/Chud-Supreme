package com.m3u.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extra market boards.
 * Pump.fun's coin list is public.
 * GMGN's open API needs an AK/SK from GMGN, and Photon does not publish one.
 * Those two boards say so instead of inventing prices. Nothing here trades.
 */
internal object MarketBoards {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun pumpFun(): List<MarketPair> = withContext(Dispatchers.IO) {
        val body = get(
            "https://frontend-api-v3.pump.fun/coins?limit=40&offset=0&sort=last_trade_timestamp&order=DESC&includeNsfw=false"
        )
        val items = json.parseToJsonElement(body) as? JsonArray ?: return@withContext emptyList()
        items.mapNotNull { raw ->
            val coin = raw as? JsonObject ?: return@mapNotNull null
            val mint = coin.text("mint") ?: return@mapNotNull null
            val cap = coin.number("usd_market_cap")
            MarketPair(
                chainId = "solana",
                dexId = "pump.fun",
                pairAddress = mint,
                url = "https://pump.fun/coin/$mint",
                baseAddress = mint,
                baseName = coin.text("name") ?: mint,
                baseSymbol = coin.text("symbol") ?: "?",
                quoteSymbol = "SOL",
                priceUsd = cap?.let { it / 1_000_000_000.0 },
                change5m = null,
                change1h = null,
                change6h = null,
                change24h = null,
                volume24h = null,
                liquidityUsd = null,
                marketCap = cap,
                fdv = cap,
                buys24h = null,
                sells24h = null,
                createdAt = coin.number("created_timestamp")?.toLong(),
                imageUrl = coin.text("image_uri"),
                boosts = null,
                description = coin.text("description"),
            )
        }
    }

    suspend fun gmgn(): List<MarketPair> {
        throw IOException(
            "GMGN's data API needs an AK and SK from docs.gmgn.ai. The public site blocks this Fire TV, so there is no list to show."
        )
    }

    suspend fun photon(): List<MarketPair> {
        throw IOException(
            "Photon does not publish an API. Its site is a login wall, so this row cannot show Photon's own list."
        )
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(name: String): Double? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}
