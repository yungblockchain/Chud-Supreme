package com.m3u.tv

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Free fixture and shot data that does not need a key.
 * openfootball covers the EFL and National League. OpenLigaDB covers the German third tier.
 * StatsBomb open data is historical, so a shot map only appears when that public set
 * actually contains the two clubs. SportsGameOdds is only called when a key is saved.
 */
internal object FootballFeeds {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    data class Extra(
        val id: String,
        val leagueId: String,
        val league: String,
        val home: String,
        val away: String,
        val detail: String,
        val state: String,
        val homeScore: String?,
        val awayScore: String?,
    )

    data class Price(
        val home: String,
        val away: String,
        val homeOdds: String?,
        val drawOdds: String?,
        val awayOdds: String?,
    )

    data class Shot(
        val x: Float,
        val y: Float,
        val home: Boolean,
        val goal: Boolean,
    )

    suspend fun extras(): List<Extra> = withContext(Dispatchers.IO) {
        val files = listOf(
            "2026-27/2-championship.txt" to ("eng.2b" to "Championship fixtures"),
            "2026-27/3-league1.txt" to ("eng.3" to "League One"),
            "2026-27/4-league2.txt" to ("eng.4" to "League Two"),
            "2026-27/5-nationalleague.txt" to ("eng.5" to "National League"),
            "2025-26/3-league1.txt" to ("eng.3" to "League One"),
            "2025-26/4-league2.txt" to ("eng.4" to "League Two"),
            "2025-26/5-nationalleague.txt" to ("eng.5" to "National League"),
        )
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Extra>()
        for ((path, league) in files) {
            if (league.first in seen) continue
            val text = http(
                "https://raw.githubusercontent.com/openfootball/england/master/$path",
            )
            if (text.isBlank() || text.startsWith("404")) continue
            val parsed = parseOpenFootball(text, league.first, league.second)
            if (parsed.isEmpty()) continue
            seen += league.first
            out += parsed
        }
        out += openLiga()
        out
    }

    suspend fun prices(apiKey: String): List<Price> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext emptyList()
        val leagues = "EPL,LA_LIGA,BUNDESLIGA,SERIE_A,LIGUE_1,UEFA_CHAMPIONS_LEAGUE,MLS,EFL_CHAMPIONSHIP"
        val text = http(
            "https://api.sportsgameodds.com/v2/events?leagueID=$leagues&oddsAvailable=true&limit=40&apiKey=${encode(key)}",
        )
        val root = text.asObject() ?: return@withContext emptyList()
        root["data"]?.asArray().orEmpty().mapNotNull { element ->
            val event = element.asObject() ?: return@mapNotNull null
            val teams = event["teams"]?.asObject() ?: return@mapNotNull null
            val home = teamName(teams["home"]?.asObject()) ?: return@mapNotNull null
            val away = teamName(teams["away"]?.asObject()) ?: return@mapNotNull null
            val odds = event["odds"]?.asObject() ?: return@mapNotNull null
            Price(
                home = home,
                away = away,
                homeOdds = moneyline(odds, "home"),
                drawOdds = moneyline(odds, "draw"),
                awayOdds = moneyline(odds, "away"),
            )
        }
    }

    suspend fun shots(home: String, away: String): List<Shot> = withContext(Dispatchers.IO) {
        val competitions = http(
            "https://raw.githubusercontent.com/statsbomb/open-data/master/data/competitions.json",
        ).asArray() ?: return@withContext emptyList()
        val seasons = competitions.mapNotNull { it.asObject() }
            .sortedByDescending { it.text("season_id")?.toIntOrNull() ?: 0 }
            .distinctBy { it.text("competition_id") }
            .take(8)
        for (season in seasons) {
            val competitionId = season.text("competition_id") ?: continue
            val seasonId = season.text("season_id") ?: continue
            val matches = http(
                "https://raw.githubusercontent.com/statsbomb/open-data/master/data/matches/$competitionId/$seasonId.json",
            ).asArray() ?: continue
            val match = matches.mapNotNull { it.asObject() }.firstOrNull { row ->
                val homeName = row["home_team"]?.asObject()?.text("home_team_name") ?: return@firstOrNull false
                val awayName = row["away_team"]?.asObject()?.text("away_team_name") ?: return@firstOrNull false
                sameClub(homeName, home) && sameClub(awayName, away) ||
                    sameClub(homeName, away) && sameClub(awayName, home)
            } ?: continue
            val matchId = match.text("match_id") ?: continue
            val events = http(
                "https://raw.githubusercontent.com/statsbomb/open-data/master/data/events/$matchId.json",
                limit = 3_000_000,
            ).asArray() ?: return@withContext emptyList()
            return@withContext events.mapNotNull { element ->
                val event = element.asObject() ?: return@mapNotNull null
                if (event["type"]?.asObject()?.text("name") != "Shot") return@mapNotNull null
                val location = event["location"]?.asArray() ?: return@mapNotNull null
                val x = location.getOrNull(0)?.textValue()?.toFloatOrNull() ?: return@mapNotNull null
                val y = location.getOrNull(1)?.textValue()?.toFloatOrNull() ?: return@mapNotNull null
                val team = event["team"]?.asObject()?.text("name").orEmpty()
                val byHome = sameClub(team, home)
                val goal = event["shot"]?.asObject()?.get("outcome")?.asObject()?.text("name") == "Goal"
                Shot(x, y, byHome, goal)
            }.take(80)
        }
        emptyList()
    }

    private fun parseOpenFootball(text: String, leagueId: String, league: String): List<Extra> {
        var year = 2026
        var month = 8
        var day = 1
        val out = mutableListOf<Extra>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("=") || line.startsWith("▪")) continue
            val date = Regex("""(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+([A-Za-z]{3})\s+(\d{1,2})(?:\s+(\d{4}))?""").find(line)
            if (date != null && !line.contains(" v ")) {
                months[date.groupValues[1].lowercase(Locale.US)]?.let { month = it }
                day = date.groupValues[2].toIntOrNull() ?: day
                date.groupValues[3].toIntOrNull()?.let { year = it }
                continue
            }
            if (!line.contains(" v ")) continue
            val match = Regex("""^(?:(\d{1,2}:\d{2})\s+)?(.+?)\s+v\s+(.+)$""").find(line) ?: continue
            val time = match.groupValues[1]
            var home = match.groupValues[2].trim()
            var away = match.groupValues[3].trim()
            val score = Regex("""(\d+)\s*-\s*(\d+)""").find(away)
            val homeScore = score?.groupValues?.get(1)
            val awayScore = score?.groupValues?.get(2)
            if (score != null) away = away.substring(0, score.range.first).trim()
            home = tidy(home)
            away = tidy(away)
            if (home.length < 3 || away.length < 3) continue
            val whenText = "%04d-%02d-%02d%s".format(year, month, day, if (time.isBlank()) "" else " $time")
            out += Extra(
                id = "of-$leagueId-$whenText-$home-$away",
                leagueId = leagueId,
                league = league,
                home = home,
                away = away,
                detail = if (homeScore != null) whenText else whenText,
                state = if (homeScore != null) "post" else "pre",
                homeScore = homeScore,
                awayScore = awayScore,
            )
        }
        return out
    }

    private fun openLiga(): List<Extra> {
        val text = http("https://api.openligadb.de/getmatchdata/bl3/2026")
        val rows = text.asArray() ?: return emptyList()
        return rows.mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val home = row["team1"]?.asObject()?.text("teamName") ?: return@mapNotNull null
            val away = row["team2"]?.asObject()?.text("teamName") ?: return@mapNotNull null
            val finished = row["matchIsFinished"]?.textValue() == "true"
            val result = row["matchResults"]?.asArray().orEmpty().mapNotNull { it.asObject() }
                .lastOrNull()
            Extra(
                id = "ol-${row.text("matchID") ?: "$home-$away"}",
                leagueId = "ger.3",
                league = "3. Liga",
                home = home,
                away = away,
                detail = row.text("matchDateTime")?.take(16)?.replace("T", " ").orEmpty(),
                state = if (finished) "post" else "pre",
                homeScore = result?.text("pointsTeam1"),
                awayScore = result?.text("pointsTeam2"),
            )
        }
    }

    private fun moneyline(odds: JsonObject, side: String): String? {
        val entry = odds.entries.firstOrNull { (key, value) ->
            val lower = key.lowercase(Locale.US)
            lower.contains(side) && (lower.contains("ml") || lower.contains("1x2") || lower.contains("money")) &&
                value is JsonObject
        }?.value?.asObject() ?: return null
        return entry.text("bookOdds") ?: entry.text("fairOdds") ?: entry.text("odds")
    }

    private fun teamName(team: JsonObject?): String? {
        val names = team?.get("names")?.asObject()
        return names?.text("long") ?: names?.text("medium") ?: team?.text("name")
    }

    internal fun sameClub(left: String, right: String): Boolean {
        val a = fold(left)
        val b = fold(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        val aw = a.split(' ').filter { it.length >= 4 }
        val bw = b.split(' ').filter { it.length >= 4 }
        return aw.any { token -> bw.any { other -> token == other } }
    }

    private fun fold(name: String): String =
        name.lowercase(Locale.US)
            .replace(Regex("\\b(fc|afc|cf|sc)\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tidy(name: String): String =
        name.replace(Regex("\\s+(FC|AFC|CF)$"), "").replace(Regex("\\s+"), " ").trim()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun http(url: String, limit: Int = 1_500_000): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "ChudMAXXX/1.0 (Android TV)")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else return ""
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(8_192)
                val out = StringBuilder()
                while (out.length < limit) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
        } catch (_: Exception) {
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun String.asObject(): JsonObject? =
        runCatching { json.parseToJsonElement(this) as? JsonObject }.getOrNull()

    private fun String.asArray(): JsonArray? =
        runCatching { json.parseToJsonElement(this) as? JsonArray }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? = this as? JsonArray
    private fun kotlinx.serialization.json.JsonElement.textValue(): String? =
        (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.text(name: String): String? =
        this[name]?.textValue()?.takeIf { it.isNotBlank() && it != "null" }
}

private const val FOLLOW_PREFS = "chud-clubs"

internal fun loadFollowed(context: Context): Set<String> =
    context.getSharedPreferences(FOLLOW_PREFS, Context.MODE_PRIVATE)
        .getStringSet("names", emptySet())
        .orEmpty()

internal fun toggleFollow(context: Context, name: String, current: Set<String>): Set<String> {
    val next = if (name in current) current - name else current + name
    context.getSharedPreferences(FOLLOW_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet("names", next)
        .apply()
    return next
}

@HiltViewModel
internal class MatchExtrasViewModel @Inject constructor(
    private val secrets: SecretStore,
) : ViewModel() {
    fun oddsKey(): String? = secrets.get(SecretName.SportsGameOdds)

    fun sportsKey(): String? = secrets.get(SecretName.ApiSports)
}

