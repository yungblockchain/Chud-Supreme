package com.m3u.tv

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Match Centre. Live, upcoming and finished football from ESPN's public scoreboard
 * (no key). Opening a match loads the summary: lineups, key events and team stats.
 * Moneyline prices on that same board are shown as decimal odds. They are information,
 * not a book. Cloudbet, SoftGamings, BetSolutions and ZenSports need a private partner
 * key, so they are not called. The bet control writes a paper slip on the device.
 * It does not send Solana or Ethereum.
 * WhoScored, Sofascore, FBref and Understat do not offer a free keyless API either.
 */

private val MATCH_LEAGUES = listOf(
    "eng.1" to "Premier League",
    "esp.1" to "LaLiga",
    "ger.1" to "Bundesliga",
    "ita.1" to "Serie A",
    "fra.1" to "Ligue 1",
    "uefa.champions" to "Champions League",
    "uefa.europa" to "Europa League",
    "eng.2" to "Championship",
    "eng.2b" to "Championship fixtures",
    "eng.3" to "League One",
    "eng.4" to "League Two",
    "eng.5" to "National League",
    "usa.1" to "MLS",
    "ned.1" to "Eredivisie",
    "por.1" to "Primeira Liga",
    "ger.3" to "3. Liga",
)

private enum class MatchFilter { Live, Upcoming, Results }

@Immutable
private data class FixtureSide(val name: String, val score: String?)

@Immutable
private data class MatchOdds(
    val home: String?,
    val draw: String?,
    val away: String?,
    val total: String?,
    val provider: String?,
    val details: String?,
) {
    fun line(): String {
        val bits = buildList {
            fractional(home)?.let { add("1 $it") }
            fractional(draw)?.let { add("X $it") }
            fractional(away)?.let { add("2 $it") }
            total?.let { add("O/U $it") }
        }
        if (bits.isEmpty()) return details?.takeIf { it.isNotBlank() }.orEmpty()
        val who = provider?.let { "  ·  $it" }.orEmpty()
        return bits.joinToString("   ") + who
    }
}

@Immutable
private data class Fixture(
    val id: String,
    val leagueId: String,
    val league: String,
    val home: FixtureSide,
    val away: FixtureSide,
    val state: String,
    val detail: String,
    val venue: String?,
    val start: String?,
    val odds: MatchOdds?,
    val apiId: Int? = null,
)

@Immutable
private data class LineupPlayer(val name: String, val number: String?, val position: String?)

@Immutable
private data class MatchStat(val label: String, val home: String, val away: String)

@Immutable
private data class MatchDetail(
    val headline: String?,
    val homeXi: List<LineupPlayer>,
    val awayXi: List<LineupPlayer>,
    val note: String?,
    val stats: List<MatchStat>,
    val events: List<String>,
    val odds: MatchOdds?,
    val injuries: List<String> = emptyList(),
)

private fun mergeSports(board: List<Fixture>, live: List<ApiSports.Row>): List<Fixture> {
    val used = mutableSetOf<Int>()
    val updated = board.map { fixture ->
        val row = live.firstOrNull { sports ->
            sports.apiId !in used &&
                sports.leagueKey == fixture.leagueId &&
                FootballFeeds.sameClub(sports.home, fixture.home.name) &&
                FootballFeeds.sameClub(sports.away, fixture.away.name)
        } ?: return@map fixture
        used += row.apiId
        fixture.copy(
            home = fixture.home.copy(score = row.homeScore ?: fixture.home.score),
            away = fixture.away.copy(score = row.awayScore ?: fixture.away.score),
            state = row.state,
            detail = row.detail,
            apiId = row.apiId,
        )
    }
    val extra = live.filter { it.apiId !in used }.map { row ->
        Fixture(
            id = "api-${row.apiId}",
            leagueId = row.leagueKey,
            league = row.league,
            home = FixtureSide(row.home, row.homeScore),
            away = FixtureSide(row.away, row.awayScore),
            state = row.state,
            detail = row.detail,
            venue = null,
            start = null,
            odds = null,
            apiId = row.apiId,
        )
    }
    return updated + extra
}

@Composable
fun MatchCentreScreen(
    onWatch: (home: String, away: String, report: (String) -> Unit) -> Unit = { _, _, report ->
        report("No channel search is available.")
    },
) {
    var leagueIndex by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(MatchFilter.Live) }
    var fixtures by remember { mutableStateOf<List<Fixture>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Fixture?>(null) }
    var detail by remember { mutableStateOf<MatchDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var watchNote by remember { mutableStateOf<String?>(null) }
    var showF1 by remember { mutableStateOf(false) }
    var f1 by remember { mutableStateOf<F1Weekend?>(null) }
    var f1Loading by remember { mutableStateOf(false) }
    var f1Reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(showF1, f1Reloads) {
        if (!showF1) return@LaunchedEffect
        f1Loading = true
        try {
            f1 = FormulaOne.weekend(force = f1Reloads > 0) ?: f1
        } finally {
            f1Loading = false
        }
    }
    // The competitions row starts focused on the chosen one.
    val leagueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { leagueFocus.requestFocus() }
    }
    val context = LocalContext.current
    var shots by remember { mutableStateOf<List<FootballFeeds.Shot>>(emptyList()) }
    var followed by remember { mutableStateOf(loadFollowed(context)) }
    val extras = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<MatchExtrasViewModel>()
    val slips = rememberPaperSlips()

    LaunchedEffect(reload) {
        loading = true
        error = null
        val loaded = runCatching { MatchFeed.loadAll() }
        loaded.onSuccess { board ->
            fixtures = board
            val key = extras.oddsKey()
            if (!key.isNullOrBlank()) {
                val prices = runCatching { FootballFeeds.prices(key) }.getOrDefault(emptyList())
                if (prices.isNotEmpty()) {
                    fixtures = board.map { fixture ->
                        val price = prices.firstOrNull {
                            FootballFeeds.sameClub(it.home, fixture.home.name) &&
                                FootballFeeds.sameClub(it.away, fixture.away.name)
                        } ?: return@map fixture
                        fixture.copy(
                            odds = MatchOdds(
                                home = price.homeOdds ?: fixture.odds?.home,
                                draw = price.drawOdds ?: fixture.odds?.draw,
                                away = price.awayOdds ?: fixture.odds?.away,
                                total = fixture.odds?.total,
                                provider = "SportsGameOdds",
                                details = fixture.odds?.details,
                            ),
                        )
                    }
                }
            }
            val sportsKey = extras.sportsKey()
            if (!sportsKey.isNullOrBlank()) {
                val live = runCatching { ApiSports.live(sportsKey) }.getOrDefault(emptyList())
                if (live.isNotEmpty()) fixtures = mergeSports(fixtures, live)
            }
        }.onFailure { error = it.message ?: "Scores didn't load" }
        loading = false
    }

    LaunchedEffect(selected?.id) {
        watchNote = null
        val fixture = selected ?: run {
            detail = null
            return@LaunchedEffect
        }
        detailLoading = true
        if (fixture.id.startsWith("of-") || fixture.id.startsWith("ol-")) {
            detail = MatchDetail(
                headline = fixture.detail,
                homeXi = emptyList(),
                awayXi = emptyList(),
                note = "This row is from openfootball or OpenLigaDB, not the live scoreboard.",
                stats = emptyList(),
                events = emptyList(),
                odds = fixture.odds,
            )
            shots = emptyList()
            detailLoading = false
            return@LaunchedEffect
        }
        detail = runCatching { MatchFeed.loadDetail(fixture) }.getOrNull()
        val sportsKey = extras.sportsKey()
        val apiId = fixture.apiId
        if (!sportsKey.isNullOrBlank() && apiId != null) {
            val sheet = runCatching { ApiSports.sheet(sportsKey, apiId) }.getOrNull()
            if (sheet != null) {
                val current = detail
                detail = MatchDetail(
                    headline = current?.headline ?: fixture.detail,
                    homeXi = sheet.homeXi.map { LineupPlayer(it.name, it.number, it.position) }.ifEmpty { current?.homeXi.orEmpty() },
                    awayXi = sheet.awayXi.map { LineupPlayer(it.name, it.number, it.position) }.ifEmpty { current?.awayXi.orEmpty() },
                    note = current?.note,
                    stats = sheet.stats.map { MatchStat(it.first, it.second, it.third) }.ifEmpty { current?.stats.orEmpty() },
                    events = sheet.events.ifEmpty { current?.events.orEmpty() },
                    odds = current?.odds ?: fixture.odds,
                    injuries = current?.injuries.orEmpty(),
                )
            }
        }
        shots = runCatching { FootballFeeds.shots(fixture.home.name, fixture.away.name) }.getOrDefault(emptyList())
        detailLoading = false
    }

    val league = MATCH_LEAGUES[leagueIndex]
    val shown = fixtures
        .filter { it.leagueId == league.first }
        .filter { fixture ->
            when (filter) {
                MatchFilter.Live -> fixture.state == "in"
                MatchFilter.Upcoming -> fixture.state == "pre"
                MatchFilter.Results -> fixture.state == "post"
            }
        }

    if (selected != null) {
        val fixture = selected!!
        val context = LocalContext.current
        MatchDetailPage(
            fixture = fixture,
            detail = detail,
            loading = detailLoading,
            note = watchNote,
            slips = slips.lines,
            followed = fixture.home.name in followed || fixture.away.name in followed,
            shots = shots,
            onFollow = {
                followed = toggleFollow(context, fixture.home.name, followed)
            },
            onBack = { selected = null },
            onWatch = {
                watchNote = "Looking through your channels…"
                onWatch(selected!!.home.name, selected!!.away.name) { message -> watchNote = message }
            },
            onSlip = { selection, price ->
                val teams = "${selected!!.home.name} vs ${selected!!.away.name}"
                val priceBit = price?.let { " @ $it" }.orEmpty()
                slips.add("$teams  ·  $selection$priceBit")
                watchNote = "Paper slip saved on this device. No Solana or Ethereum was sent."
            },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Match Centre",
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 28.sp,
                    modifier = Modifier.weight(1f),
                )
                TvActionButton(
                    text = "Refresh",
                    icon = Icons.Rounded.Refresh,
                    onClick = { if (showF1) f1Reloads++ else reload++ },
                )
            }
        }
        item {
            Text(
                text = "Scores, lineups and decimal odds from the public ESPN board. A bet here is a paper slip on this device. No crypto is sent.",
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(MATCH_LEAGUES.size) { index ->
                    val item = MATCH_LEAGUES[index]
                    TvActionButton(
                        text = item.second,
                        icon = Icons.Rounded.SportsSoccer,
                        selected = !showF1 && index == leagueIndex,
                        onClick = {
                            showF1 = false
                            leagueIndex = index
                        },
                        focusRequester = leagueFocus.takeIf { index == leagueIndex },
                    )
                }
                item {
                    TvActionButton(
                        text = stringResource(R.string.dial_f1_title),
                        icon = Icons.Rounded.SportsMotorsports,
                        selected = showF1,
                        onClick = {
                            if (!showF1 && f1 == null) f1Loading = true
                            showF1 = true
                        },
                    )
                }
            }
        }
        if (showF1) {
            formulaOneItems(f1, f1Loading)
            return@LazyColumn
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MatchFilter.entries.forEach { item ->
                    TvActionButton(
                        text = item.name,
                        icon = Icons.Rounded.SportsSoccer,
                        selected = filter == item,
                        onClick = { filter = item },
                    )
                }
            }
        }
        when {
            loading && fixtures.isEmpty() -> item { Status("Loading scores…") }
            error != null && fixtures.isEmpty() -> item { Status(error ?: "Scores didn't load") }
            shown.isEmpty() -> item { Status("Nothing in ${filter.name.lowercase()} for ${league.second}.") }
            else -> items(shown, key = { it.id }) { fixture ->
                FocusFrame(
                    onClick = { selected = fixture },
                    semanticsLabel = "${fixture.home.name} versus ${fixture.away.name}",
                    modifier = Modifier.widthIn(max = 980.dp),
                ) { focused ->
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(
                            text = fixture.detail,
                            color = if (focused) TvColors.OnFocus else TvColors.Focus,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "${fixture.home.name}  ${fixture.home.score ?: "–"}    ${fixture.away.score ?: "–"}  ${fixture.away.name}",
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val place = listOfNotNull(fixture.venue, fixture.start).joinToString("  ·  ")
                        if (place.isNotBlank()) {
                            Text(
                                text = place,
                                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val prices = fixture.odds?.line().orEmpty()
                        if (prices.isNotBlank()) {
                            Text(
                                text = prices,
                                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                                fontFamily = TvFonts.Body,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchDetailPage(
    fixture: Fixture,
    detail: MatchDetail?,
    loading: Boolean,
    note: String?,
    slips: List<String>,
    followed: Boolean,
    shots: List<FootballFeeds.Shot>,
    onFollow: () -> Unit,
    onBack: () -> Unit,
    onWatch: () -> Unit,
    onSlip: (selection: String, price: String?) -> Unit,
) {
    val odds = detail?.odds ?: fixture.odds
    LazyColumn(
        contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TvActionButton(
                text = "Back to fixtures",
                icon = Icons.Rounded.KeyboardArrowLeft,
                onClick = onBack,
            )
        }
        item {
            Text(
                text = "${fixture.home.name}  ${fixture.home.score ?: "–"}  –  ${fixture.away.score ?: "–"}  ${fixture.away.name}",
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 26.sp,
            )
        }
        item {
            Text(
                text = listOfNotNull(fixture.league, fixture.detail, fixture.venue).joinToString("  ·  "),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
            )
        }
        item {
            PitchBoard(
                home = fixture.home.name,
                away = fixture.away.name,
                events = detail?.events.orEmpty(),
                homeXi = detail?.homeXi.orEmpty(),
                awayXi = detail?.awayXi.orEmpty(),
                shots = shots,
            )
        }
        item {
            TvActionButton(
                text = if (followed) "Following ${fixture.home.name}" else "Follow ${fixture.home.name}",
                icon = Icons.Rounded.SportsSoccer,
                onClick = onFollow,
            )
        }
        item {
            val prices = odds?.line().orEmpty()
            Text(
                text = if (prices.isBlank()) "No odds posted for this fixture." else prices,
                color = TvColors.Focus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }
        item {
            TvActionButton(
                text = "Watch live",
                icon = Icons.Rounded.PlayArrow,
                supportingText = "Find both teams in your channels",
                onClick = onWatch,
            )
        }
        note?.let { message -> item { Status(message) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paper slip. Cloudbet, SoftGamings, BetSolutions and ZenSports are not connected. No Solana or Ethereum leaves this device.",
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    modifier = Modifier.widthIn(max = 860.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvActionButton(
                        text = odds?.home?.let { "Home ${fractional(it)}" } ?: "Home",
                        icon = Icons.Rounded.Paid,
                        onClick = { onSlip(fixture.home.name, odds?.home) },
                    )
                    TvActionButton(
                        text = odds?.draw?.let { "Draw ${fractional(it)}" } ?: "Draw",
                        icon = Icons.Rounded.Paid,
                        onClick = { onSlip("Draw", odds?.draw) },
                    )
                    TvActionButton(
                        text = odds?.away?.let { "Away ${fractional(it)}" } ?: "Away",
                        icon = Icons.Rounded.Paid,
                        onClick = { onSlip(fixture.away.name, odds?.away) },
                    )
                }
            }
        }
        if (slips.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Saved slips",
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    slips.take(6).forEach { line -> Status(line) }
                }
            }
        }
        detail?.headline?.let { headline -> item { Status(headline) } }
        if (loading) {
            item { Status("Loading stats and lineups…") }
        } else {
            if (detail?.injuries?.isNotEmpty() == true) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Team news",
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        detail.injuries.forEach { line -> Status(line) }
                    }
                }
            }
            if (detail?.events?.isNotEmpty() == true) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Live stats",
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        detail.events.forEach { event -> Status(event) }
                    }
                }
            }
            if (detail?.stats?.isNotEmpty() == true) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${fixture.home.name}   /   ${fixture.away.name}",
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        detail.stats.forEach { stat ->
                            Status("${stat.label}    ${stat.home}  –  ${stat.away}")
                        }
                    }
                }
            }
            if (detail == null || (detail.homeXi.isEmpty() && detail.awayXi.isEmpty())) {
                item { Status(detail?.note ?: "Lineups are not published for this match yet.") }
            } else {
                item { LineupBlock(fixture.home.name, detail.homeXi) }
                item { LineupBlock(fixture.away.name, detail.awayXi) }
            }
        }
    }
}

/** Decimal price (2.50) as a fraction (3/2). Already-fractional text is left alone. */
private fun fractional(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.contains('/')) return raw
    val dec = raw.toDoubleOrNull() ?: return raw
    if (dec <= 1.01) return raw
    val target = dec - 1.0
    var bestN = 1
    var bestD = 1
    var bestErr = Double.MAX_VALUE
    for (d in 1..24) {
        val n = kotlin.math.round(target * d).toInt().coerceAtLeast(1)
        val err = kotlin.math.abs(target - n.toDouble() / d)
        if (err < bestErr - 0.0001 || (kotlin.math.abs(err - bestErr) < 0.0001 && d < bestD)) {
            bestErr = err
            bestN = n
            bestD = d
        }
    }
    val g = gcd(bestN, bestD)
    return "${bestN / g}/${bestD / g}"
}

private fun gcd(a: Int, b: Int): Int {
    var x = kotlin.math.abs(a)
    var y = kotlin.math.abs(b)
    while (y != 0) {
        val t = x % y
        x = y
        y = t
    }
    return x.coerceAtLeast(1)
}

@Composable
private fun PitchBoard(
    home: String,
    away: String,
    events: List<String>,
    homeXi: List<LineupPlayer>,
    awayXi: List<LineupPlayer>,
    shots: List<FootballFeeds.Shot>,
) {
    val pulse = rememberInfiniteTransition(label = "pitch")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pitch-phase",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Pitch",
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Text(
            text = when {
                shots.isNotEmpty() -> "Shot map from StatsBomb's public archive for this fixture. Live ball-tracking is not in that set."
                events.isEmpty() -> "No ball-tracking feed. Markers appear when this match reports a goal, card or sub."
                else -> "Goals, cards and subs from the live board. Not a Sportsradar tracker."
            },
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        ) {
            val grass = Color(0xFF1B6B3A)
            val line = Color.White.copy(alpha = 0.85f)
            drawRect(grass, size = size)
            val inset = 16.dp.toPx()
            val pitch = Size(size.width - inset * 2, size.height - inset * 2)
            val origin = Offset(inset, inset)
            drawRect(line, origin, pitch, style = Stroke(width = 2.dp.toPx()))
            drawLine(line, Offset(size.width / 2, origin.y), Offset(size.width / 2, origin.y + pitch.height), strokeWidth = 2.dp.toPx())
            drawCircle(line, radius = pitch.height * 0.16f, center = Offset(size.width / 2, size.height / 2), style = Stroke(width = 2.dp.toPx()))
            fun DrawScope.row(players: List<LineupPlayer>, attackingRight: Boolean) {
                val groups = listOf("G", "D", "M", "F")
                groups.forEachIndexed { band, code ->
                    val linePlayers = players.filter { it.position?.startsWith(code) == true }.ifEmpty {
                        if (code == "M") players.filter { it.position == null } else emptyList()
                    }
                    linePlayers.forEachIndexed { index, _ ->
                        val along = (band + 1f) / (groups.size + 1f)
                        val xFrac = if (attackingRight) along else 1f - along
                        val yFrac = (index + 1f) / (linePlayers.size + 1f)
                        drawCircle(
                            Color.White.copy(alpha = 0.9f),
                            radius = 6.dp.toPx(),
                            center = Offset(origin.x + pitch.width * xFrac, origin.y + pitch.height * yFrac),
                        )
                    }
                }
            }
            if (homeXi.isNotEmpty()) row(homeXi, attackingRight = false)
            if (awayXi.isNotEmpty()) row(awayXi, attackingRight = true)
            shots.forEach { shot ->
                val x = if (shot.home) shot.x / 120f else 1f - shot.x / 120f
                val y = shot.y / 80f
                drawCircle(
                    if (shot.goal) Color(0xFF3DFF9A) else Color.White.copy(alpha = 0.75f),
                    radius = if (shot.goal) 6.dp.toPx() else 3.5.dp.toPx(),
                    center = Offset(origin.x + pitch.width * x, origin.y + pitch.height * y),
                )
            }
            val marks = events.take(12)
            marks.forEachIndexed { index, event ->
                val lower = event.lowercase()
                val homeSide = lower.contains(home.lowercase().take(6)) || index % 2 == 0 && !lower.contains(away.lowercase().take(6))
                val xFrac = if (homeSide) 0.18f + (index % 4) * 0.07f else 0.82f - (index % 4) * 0.07f
                val yFrac = 0.18f + (index % 6) * 0.12f
                val bob = if (index == marks.lastIndex) kotlin.math.sin(phase * Math.PI * 2).toFloat() * 8.dp.toPx() else 0f
                val at = Offset(origin.x + pitch.width * xFrac, origin.y + pitch.height * yFrac + bob)
                val color = when {
                    lower.contains("goal") -> Color(0xFF3DFF9A)
                    lower.contains("card") || lower.contains("yellow") -> Color(0xFFFFD24A)
                    lower.contains("red") -> Color(0xFFFF4D6A)
                    lower.contains("sub") -> Color(0xFF7AD7FF)
                    else -> Color.White
                }
                drawCircle(color, radius = 8.dp.toPx(), center = at)
            }
        }
    }
}

@Composable
private fun LineupBlock(team: String, players: List<LineupPlayer>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = team,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        players.forEach { player ->
            val bits = listOfNotNull(player.number, player.position).joinToString("  ")
            Text(
                text = if (bits.isBlank()) player.name else "$bits   ${player.name}",
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}

private class PaperSlips(
    val lines: List<String>,
    val add: (String) -> Unit,
)

@Composable
private fun rememberPaperSlips(): PaperSlips {
    val context = LocalContext.current
    val prefs = remember {
        context.applicationContext.getSharedPreferences("chud_match_slips", Context.MODE_PRIVATE)
    }
    var lines by remember {
        mutableStateOf(
            prefs.getString("lines", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList(),
        )
    }
    val add: (String) -> Unit = { line ->
        val current = prefs.getString("lines", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        val next = (listOf(line) + current).distinct().take(8)
        prefs.edit().putString("lines", next.joinToString("\n")).apply()
        lines = next
    }
    return PaperSlips(lines, add)
}

/** A match being played now, for the player's score ticker. */
@Immutable
data class LiveScore(val league: String, val home: String, val away: String, val homeScore: String, val awayScore: String, val clock: String)

/** The matches being played now (the Match Centre's own feed, cached for two minutes). */
suspend fun liveScores(): List<LiveScore> = runCatching { MatchFeed.loadAll() }.getOrDefault(emptyList())
    .filter { it.state == "in" }
    .map { fixture ->
        LiveScore(
            league = fixture.league,
            home = fixture.home.name,
            away = fixture.away.name,
            homeScore = fixture.home.score ?: "0",
            awayScore = fixture.away.score ?: "0",
            clock = fixture.detail,
        )
    }

private object MatchFeed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var cached: List<Fixture> = emptyList()
    private var cachedAt = 0L

    suspend fun loadAll(): List<Fixture> {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < 120_000) return cached
        val loaded = loadAllFresh()
        if (loaded.isNotEmpty()) {
            cached = loaded
            // From when the fetch finished, so a caller asking every two minutes gets the cache.
            cachedAt = System.currentTimeMillis()
        }
        return loaded
    }

    private suspend fun loadAllFresh(): List<Fixture> = coroutineScope {
        val espnIds = setOf(
            "eng.1", "esp.1", "ger.1", "ita.1", "fra.1", "uefa.champions", "uefa.europa",
            "eng.2", "usa.1", "ned.1", "por.1",
        )
        val espn = MATCH_LEAGUES.filter { it.first in espnIds }.map { (id, name) ->
            async { runCatching { scoreboard(id, name) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
        val extra = runCatching { FootballFeeds.extras() }.getOrDefault(emptyList()).map { row ->
            Fixture(
                id = row.id,
                leagueId = row.leagueId,
                league = row.league,
                home = FixtureSide(row.home, row.homeScore),
                away = FixtureSide(row.away, row.awayScore),
                state = row.state,
                detail = row.detail,
                venue = null,
                start = null,
                odds = null,
            )
        }
        espn + extra
    }

    suspend fun loadDetail(fixture: Fixture): MatchDetail = withContext(Dispatchers.IO) {
        val root = get(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/${fixture.leagueId}/summary?event=${fixture.id}"
        )
        val header = root["header"]?.asObject()?.get("competitions")?.asArray()?.firstOrNull()?.asObject()
        val note = header?.text("notes")
            ?: root["header"]?.asObject()?.text("season")
        val rosters = root["rosters"]?.asArray().orEmpty()
        fun xi(side: String): List<LineupPlayer> {
            val roster = rosters.firstOrNull { item ->
                item.asObject()?.get("homeAway")?.textValue() == side ||
                    item.asObject()?.get("team")?.asObject()?.text("displayName") ==
                    if (side == "home") fixture.home.name else fixture.away.name
            }?.asObject()
            val players = roster?.get("roster")?.asArray().orEmpty()
            return players.mapNotNull { element ->
                val player = element.asObject() ?: return@mapNotNull null
                val athlete = player["athlete"]?.asObject()
                val name = athlete?.text("displayName") ?: player.text("displayName") ?: return@mapNotNull null
                val starter = player["starter"]?.textValue()
                if (starter == "false") return@mapNotNull null
                LineupPlayer(
                    name = name,
                    number = athlete?.text("jersey") ?: player.text("jersey"),
                    position = athlete?.get("position")?.asObject()?.text("abbreviation")
                        ?: player.text("position"),
                )
            }
        }
        MatchDetail(
            headline = header?.get("status")?.asObject()?.get("type")?.asObject()?.text("detail"),
            homeXi = xi("home"),
            awayXi = xi("away"),
            note = note,
            stats = readStats(root),
            events = readEvents(root),
            odds = header?.let(::readOdds) ?: fixture.odds,
            injuries = readInjuries(root),
        )
    }

    private suspend fun scoreboard(leagueId: String, leagueName: String): List<Fixture> =
        withContext(Dispatchers.IO) {
            val root = get("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueId/scoreboard")
            root["events"]?.asArray().orEmpty().mapNotNull { element ->
                val event = element.asObject() ?: return@mapNotNull null
                val competition = event["competitions"]?.asArray()?.firstOrNull()?.asObject() ?: event
                val competitors = competition["competitors"]?.asArray().orEmpty().mapNotNull { it.asObject() }
                val home = competitors.firstOrNull { it.text("homeAway") == "home" } ?: return@mapNotNull null
                val away = competitors.firstOrNull { it.text("homeAway") == "away" } ?: return@mapNotNull null
                val status = (competition["status"] ?: event["status"])?.asObject()
                val type = status?.get("type")?.asObject()
                Fixture(
                    id = event.text("id") ?: return@mapNotNull null,
                    leagueId = leagueId,
                    league = leagueName,
                    home = FixtureSide(
                        name = home["team"]?.asObject()?.text("displayName") ?: "Home",
                        score = home.text("score"),
                    ),
                    away = FixtureSide(
                        name = away["team"]?.asObject()?.text("displayName") ?: "Away",
                        score = away.text("score"),
                    ),
                    state = type?.text("state") ?: "pre",
                    detail = type?.text("shortDetail") ?: type?.text("detail") ?: "",
                    venue = competition["venue"]?.asObject()?.text("fullName"),
                    start = event.text("date"),
                    odds = readOdds(competition),
                )
            }
        }

    private fun get(url: String): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudMAXXX/1.0 (Android TV)")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299 || text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text).asObject() ?: JsonObject(emptyMap())
        } finally {
            connection.disconnect()
        }
    }
}

private fun readOdds(competition: JsonObject): MatchOdds? {
    val odds = competition["odds"]?.asArray()?.firstOrNull()?.asObject()
        ?: competition["pickcenter"]?.asArray()?.firstOrNull()?.asObject()
        ?: return null
    val moneyline = odds["moneyline"]?.asObject()
    val parsed = MatchOdds(
        home = teamMoney(odds["homeTeamOdds"]?.asObject()) ?: closeOdds(moneyline?.get("home")?.asObject()),
        draw = teamMoney(odds["drawOdds"]?.asObject()) ?: closeOdds(moneyline?.get("draw")?.asObject()),
        away = teamMoney(odds["awayTeamOdds"]?.asObject()) ?: closeOdds(moneyline?.get("away")?.asObject()),
        total = odds.text("overUnder"),
        provider = odds["provider"]?.asObject()?.text("name") ?: "ESPN",
        details = odds.text("details"),
    )
    return parsed.takeIf { it.line().isNotBlank() }
}

private fun closeOdds(side: JsonObject?): String? {
    if (side == null) return null
    val close = side["close"]?.asObject()
    val open = side["open"]?.asObject()
    return americanToDecimal(close?.text("odds") ?: open?.text("odds") ?: side.text("odds"))
}

private fun teamMoney(node: JsonObject?): String? {
    if (node == null) return null
    return moneyDecimal(node["moneyLine"] ?: node["moneyline"])
}

private fun moneyDecimal(element: kotlinx.serialization.json.JsonElement?): String? {
    val raw = when (element) {
        null -> return null
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.text("decimal")
            ?: element.text("odds")
            ?: element["close"]?.asObject()?.text("odds")
            ?: element["open"]?.asObject()?.text("odds")
        else -> return null
    }
    return americanToDecimal(raw)
}

private fun americanToDecimal(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val n = raw.trim().removePrefix("+").toDoubleOrNull() ?: return null
    val dec = when {
        kotlin.math.abs(n) >= 100.0 -> if (n > 0) n / 100.0 + 1.0 else 100.0 / kotlin.math.abs(n) + 1.0
        n in 1.01..40.0 -> n
        else -> return null
    }
    return String.format(Locale.US, "%.2f", dec)
}

private fun readInjuries(root: JsonObject): List<String> {
    val rows = root["injuries"]?.asArray().orEmpty()
    return rows.flatMap { element ->
        val side = element.asObject() ?: return@flatMap emptyList()
        val team = side["team"]?.asObject()?.text("displayName").orEmpty()
        side["injuries"]?.asArray().orEmpty().mapNotNull { item ->
            val injury = item.asObject() ?: return@mapNotNull null
            val name = injury["athlete"]?.asObject()?.text("displayName") ?: return@mapNotNull null
            val status = injury.text("status") ?: injury["type"]?.asObject()?.text("description")
            listOfNotNull(team.takeIf { it.isNotBlank() }, name, status).joinToString("  ")
        }
    }.take(16)
}

private fun readEvents(root: JsonObject): List<String> {
    val source = root["keyEvents"]?.asArray() ?: root["plays"]?.asArray() ?: return emptyList()
    return source.mapNotNull { element ->
        val item = element.asObject() ?: return@mapNotNull null
        val minute = item["clock"]?.asObject()?.text("displayValue")
        val type = item["type"]?.asObject()?.text("text")
        val who = item["athletesInvolved"]?.asArray()?.firstOrNull()?.asObject()?.text("displayName")
        val text = item.text("text") ?: item.text("shortText") ?: type ?: return@mapNotNull null
        listOfNotNull(minute, who?.takeIf { !text.contains(it, ignoreCase = true) }, text)
            .joinToString("  ")
            .takeIf { it.isNotBlank() }
    }.take(16)
}

private fun readStats(root: JsonObject): List<MatchStat> {
    val teams = root["boxscore"]?.asObject()?.get("teams")?.asArray().orEmpty().mapNotNull { it.asObject() }
    if (teams.size < 2) return emptyList()
    fun side(name: String): JsonObject? = teams.firstOrNull { it.text("homeAway") == name }
        ?: if (name == "home") teams.firstOrNull() else teams.getOrNull(1)
    val home = side("home") ?: return emptyList()
    val away = side("away") ?: return emptyList()
    val awayMap = away["statistics"]?.asArray().orEmpty().mapNotNull { it.asObject() }.associateBy {
        it.text("name") ?: it.text("abbreviation") ?: it.text("label") ?: ""
    }
    return home["statistics"]?.asArray().orEmpty().mapNotNull { element ->
        val stat = element.asObject() ?: return@mapNotNull null
        val key = stat.text("name") ?: stat.text("label") ?: return@mapNotNull null
        val label = stat.text("label") ?: stat.text("displayName") ?: key
        val homeValue = stat.text("displayValue") ?: return@mapNotNull null
        val other = awayMap[key] ?: awayMap[stat.text("abbreviation").orEmpty()]
        val awayValue = other?.text("displayValue") ?: "–"
        MatchStat(label, homeValue, awayValue)
    }.take(8)
}

private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? = this as? JsonArray
private fun kotlinx.serialization.json.JsonElement.textValue(): String? =
    (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.text(name: String): String? = this[name]?.textValue()?.takeIf { it.isNotBlank() && it != "null" }

/** Home row for clubs followed from a fixture. */
@Composable
fun FollowedClubsRow() {
    val context = LocalContext.current
    val names = remember { loadFollowed(context) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(names) {
        if (names.isEmpty()) return@LaunchedEffect
        val board = runCatching { MatchFeed.loadAll() }.getOrDefault(emptyList())
        lines = board.filter { fixture ->
            names.any { club ->
                FootballFeeds.sameClub(club, fixture.home.name) || FootballFeeds.sameClub(club, fixture.away.name)
            }
        }.take(6).map { fixture ->
            val score = listOfNotNull(fixture.home.score, fixture.away.score)
            val middle = if (score.size == 2) score.joinToString("-") else "v"
            "${fixture.home.name}  $middle  ${fixture.away.name}   ·   ${fixture.league}"
        }
    }
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Followed clubs",
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        lines.forEach { line -> Status(line) }
    }
}

