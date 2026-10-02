package com.m3u.tv

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
 * API-Sports football (api-football). One live call per Match Centre load.
 * Opening a match spends one more call for lineups. The free plan is about 100 calls a day.
 */
internal object ApiSports {
    private const val BASE = "https://v3.football.api-sports.io"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val leagues = mapOf(
        39 to "eng.1",
        140 to "esp.1",
        78 to "ger.1",
        135 to "ita.1",
        61 to "fra.1",
        2 to "uefa.champions",
        3 to "uefa.europa",
        40 to "eng.2",
        41 to "eng.3",
        42 to "eng.4",
        43 to "eng.5",
        253 to "usa.1",
        88 to "ned.1",
        94 to "por.1",
        80 to "ger.3",
    )

    data class Row(
        val apiId: Int,
        val leagueKey: String,
        val league: String,
        val home: String,
        val away: String,
        val homeScore: String?,
        val awayScore: String?,
        val state: String,
        val detail: String,
    )

    data class Side(val name: String, val number: String?, val position: String?)

    data class Sheet(
        val homeXi: List<Side>,
        val awayXi: List<Side>,
        val events: List<String>,
        val stats: List<Triple<String, String, String>>,
    )

    suspend fun live(key: String): List<Row> = withContext(Dispatchers.IO) {
        val root = get(key, "/fixtures?live=all") ?: return@withContext emptyList()
        val items = root["response"] as? JsonArray ?: return@withContext emptyList()
        items.mapNotNull { raw -> parseRow(raw as? JsonObject ?: return@mapNotNull null) }
    }

    suspend fun sheet(key: String, apiId: Int): Sheet? = withContext(Dispatchers.IO) {
        val root = get(key, "/fixtures?id=$apiId") ?: return@withContext null
        val item = (root["response"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@withContext null
        val lineups = item["lineups"] as? JsonArray
        fun xi(index: Int): List<Side> {
            val team = lineups?.getOrNull(index) as? JsonObject ?: return emptyList()
            val start = team["startXI"] as? JsonArray ?: return emptyList()
            return start.mapNotNull { raw ->
                val player = (raw as? JsonObject)?.get("player") as? JsonObject ?: return@mapNotNull null
                Side(
                    name = player.text("name") ?: return@mapNotNull null,
                    number = player.text("number"),
                    position = player.text("pos"),
                )
            }
        }
        val events = (item["events"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val event = raw as? JsonObject ?: return@mapNotNull null
            val time = (event["time"] as? JsonObject)?.text("elapsed") ?: "?"
            val team = (event["team"] as? JsonObject)?.text("name").orEmpty()
            val player = (event["player"] as? JsonObject)?.text("name").orEmpty()
            val type = event.text("type").orEmpty()
            val detail = event.text("detail").orEmpty()
            "$time' $team $player $type $detail".trim()
        }
        val statistics = item["statistics"] as? JsonArray
        fun statList(index: Int): Map<String, String> {
            val block = statistics?.getOrNull(index) as? JsonObject ?: return emptyMap()
            val rows = block["statistics"] as? JsonArray ?: return emptyMap()
            return rows.mapNotNull { raw ->
                val row = raw as? JsonObject ?: return@mapNotNull null
                val type = row.text("type") ?: return@mapNotNull null
                type to (row.text("value") ?: "0")
            }.toMap()
        }
        val homeStats = statList(0)
        val awayStats = statList(1)
        val stats = (homeStats.keys + awayStats.keys).map { label ->
            Triple(label, homeStats[label] ?: "0", awayStats[label] ?: "0")
        }
        Sheet(xi(0), xi(1), events, stats)
    }

    private fun parseRow(item: JsonObject): Row? {
        val fixture = item["fixture"] as? JsonObject ?: return null
        val league = item["league"] as? JsonObject ?: return null
        val leagueId = league.number("id")?.toInt() ?: return null
        val key = leagues[leagueId] ?: return null
        val teams = item["teams"] as? JsonObject ?: return null
        val home = (teams["home"] as? JsonObject)?.text("name") ?: return null
        val away = (teams["away"] as? JsonObject)?.text("name") ?: return null
        val goals = item["goals"] as? JsonObject
        val status = (fixture["status"] as? JsonObject)
        val short = status?.text("short").orEmpty()
        val state = when (short) {
            "1H", "2H", "HT", "ET", "BT", "P", "LIVE", "INT" -> "in"
            "FT", "AET", "PEN", "AWD", "WO" -> "post"
            else -> "pre"
        }
        val elapsed = status?.text("elapsed")
        val detail = when (state) {
            "in" -> elapsed?.let { "$it'" } ?: short
            "post" -> "Full time"
            else -> status?.text("long") ?: "Upcoming"
        }
        return Row(
            apiId = fixture.number("id")?.toInt() ?: return null,
            leagueKey = key,
            league = league.text("name") ?: key,
            home = home,
            away = away,
            homeScore = goals?.text("home"),
            awayScore = goals?.text("away"),
            state = state,
            detail = "API-Sports · $detail",
        )
    }

    private fun get(key: String, path: String): JsonObject? {
        val connection = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-apisports-key", key)
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.number(name: String): Double? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
}
