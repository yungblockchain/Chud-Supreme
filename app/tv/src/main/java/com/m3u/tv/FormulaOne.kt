package com.m3u.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Formula 1 in the Match Centre: the next Grand Prix (in local time), the last race's top ten and
 * the drivers' championship, from Jolpica (the free, community-run successor of the Ergast API).
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class F1Race(val name: String, val circuit: String, val place: String, val startsAt: Long?)

@Immutable
data class F1Line(val position: String, val driver: String, val team: String, val detail: String)

@Immutable
data class F1Weekend(val next: F1Race?, val last: F1Race?, val results: List<F1Line>, val standings: List<F1Line>)

object FormulaOne {
    private const val BASE = "https://api.jolpi.ca/ergast/f1/current"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    @Volatile private var cached: F1Weekend? = null
    @Volatile private var cachedAt = 0L

    /** The F1 weekend; [force] skips the ten-minute cache (Refresh). */
    suspend fun weekend(force: Boolean = false): F1Weekend? = withContext(Dispatchers.IO) {
        if (!force) cached?.takeIf { System.currentTimeMillis() - cachedAt < CACHE_MS }?.let { return@withContext it }
        val loaded = runCatching { load() }.getOrNull() ?: return@withContext cached
        if (loaded.next == null && loaded.results.isEmpty() && loaded.standings.isEmpty()) return@withContext cached
        cached = loaded
        cachedAt = System.currentTimeMillis()
        loaded
    }

    private suspend fun load(): F1Weekend = withContext(Dispatchers.IO) {
        coroutineScope {
            val next = async { races("$BASE/next.json").firstOrNull() }
            val last = async { getJson(json, "$BASE/last/results.json") as? JsonObject }
            val standings = async { getJson(json, "$BASE/driverStandings.json") as? JsonObject }
            val lastRace = last.await()?.let(::raceList)?.firstOrNull() as? JsonObject
            val results = (lastRace?.get("Results") as? JsonArray).orEmpty().take(TOP).mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                F1Line(
                    position = item.text("position") ?: return@mapNotNull null,
                    driver = item.driver(),
                    team = (item["Constructor"] as? JsonObject)?.text("name").orEmpty(),
                    detail = (item["Time"] as? JsonObject)?.text("time") ?: item.text("status").orEmpty(),
                )
            }
            val table = ((standings.await()?.get("MRData") as? JsonObject)?.get("StandingsTable") as? JsonObject)
                ?.get("StandingsLists") as? JsonArray
            val drivers = ((table?.firstOrNull() as? JsonObject)?.get("DriverStandings") as? JsonArray).orEmpty().take(TOP)
                .mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    F1Line(
                        position = item.text("position") ?: item.text("positionText") ?: return@mapNotNull null,
                        driver = item.driver(),
                        team = ((item["Constructors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("name").orEmpty(),
                        detail = item.text("points").orEmpty(),
                    )
                }
            F1Weekend(next.await(), lastRace?.let(::raceOf), results, drivers)
        }
    }

    private fun raceList(root: JsonObject): JsonArray? =
        ((root["MRData"] as? JsonObject)?.get("RaceTable") as? JsonObject)?.get("Races") as? JsonArray

    private fun races(url: String): List<F1Race> =
        (getJson(json, url) as? JsonObject)?.let(::raceList).orEmpty().mapNotNull { (it as? JsonObject)?.let(::raceOf) }

    private fun raceOf(race: JsonObject): F1Race? {
        val circuit = race["Circuit"] as? JsonObject
        val location = circuit?.get("Location") as? JsonObject
        val date = race.text("date")
        val time = race.text("time")
        val startsAt = runCatching {
            when {
                date == null -> null
                time != null -> Instant.parse("${date}T$time").toEpochMilli()
                else -> LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }.getOrNull()
        return F1Race(
            name = race.text("raceName") ?: return null,
            circuit = circuit?.text("circuitName").orEmpty(),
            place = listOfNotNull(location?.text("locality"), location?.text("country")).joinToString(", "),
            startsAt = startsAt,
        )
    }

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.driver(): String {
        val driver = this["Driver"] as? JsonObject
        return listOfNotNull(driver?.text("givenName"), driver?.text("familyName")).joinToString(" ")
    }

    private const val CACHE_MS = 10 * 60_000L
    private const val TOP = 10
}

/** The Formula 1 page of the Match Centre, as rows of its list (each row takes focus, so the list scrolls). */
fun LazyListScope.formulaOneItems(weekend: F1Weekend?, loading: Boolean) {
    when {
        weekend == null && loading -> item(key = "f1-loading") { F1Note(stringResource(R.string.dial_f1_loading)) }
        weekend == null -> item(key = "f1-failed") { F1Note(stringResource(R.string.dial_f1_failed)) }
        else -> {
            weekend.next?.let { race ->
                item(key = "f1-next") {
                    FocusFrame(onClick = {}, focusedScale = 1.01f, semanticsLabel = race.name, modifier = Modifier.widthIn(max = 1000.dp)) { focused ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                text = stringResource(R.string.dial_f1_next),
                                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = race.name,
                                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                            )
                            val start = race.startsAt?.let {
                                DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date(it))
                            }
                            Text(
                                text = listOfNotNull(start, race.circuit.ifBlank { null }, race.place.ifBlank { null }).joinToString("  ·  "),
                                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
            if (weekend.results.isNotEmpty()) {
                item(key = "f1-last-title") { F1Heading(stringResource(R.string.dial_f1_last, weekend.last?.name.orEmpty())) }
                items(weekend.results, key = { "f1-result-${it.position}" }) { F1Row(it) }
            }
            if (weekend.standings.isNotEmpty()) {
                item(key = "f1-standings-title") { F1Heading(stringResource(R.string.dial_f1_standings)) }
                items(weekend.standings, key = { "f1-standing-${it.position}-${it.driver}" }) { line ->
                    F1Row(line.copy(detail = stringResource(R.string.dial_f1_points, line.detail)))
                }
            }
        }
    }
}

@Composable
private fun F1Heading(text: String) {
    Text(
        text = text,
        color = TvColors.Focus,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun F1Note(text: String) {
    Text(text = text, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 16.sp)
}

@Composable
private fun F1Row(line: F1Line) {
    FocusFrame(onClick = {}, focusedScale = 1.01f, semanticsLabel = line.driver, modifier = Modifier.widthIn(max = 1000.dp)) { focused ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(
                text = line.position,
                color = if (focused) TvColors.OnFocus else TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.width(32.dp),
            )
            Text(
                text = line.driver,
                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(260.dp),
            )
            Text(
                text = line.team,
                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(220.dp),
            )
            Text(
                text = line.detail,
                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                maxLines = 1,
            )
        }
    }
}
