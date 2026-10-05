package com.m3u.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------------------------------
 * The score ticker: on a football channel, the matches being played right now go by along the top
 * of the picture, one at a time, while nothing else is on screen. It shares the Match Centre's
 * feed (and its two-minute cache), asks only while it's showing and the app is in front, and
 * Settings › Dial › Player switches it off.
 * ---------------------------------------------------------------------------------------------- */

/** Football channels, by whole words: brand names alone (beIN, Movistar, Viaplay) also run films. */
private val FOOTBALL = Regex(
    "\\b(sports?|football|soccer|futbol|fútbol|calcio|premier league|la ?liga|serie a|bundesliga|ligue 1|eredivisie|" +
        "champions league|europa league|uefa|fifa|bein sports?|dazn|espn|tnt sports|bt sport|sky sports?|eleven sports?|" +
        "supersport|ziggo sport|viaplay sports?|movistar (?:deportes|liga|laliga)|canal\\+ (?:foot|sport))\\b",
)

/** Sport the feed doesn't cover, and the things that only share a word with sport. */
private val NOT_FOOTBALL = Regex(
    "\\b(f1|formula|nba|nfl|nhl|mlb|cricket|golf|tennis|racing|motogp|ufc|boxing|wwe|darts|rugby|cycling|" +
        "esports?|cinema|movies?|films?|premiere|series|kids|music)\\b",
)

/** Whether a channel looks like football, by its name or its category. */
fun isSportsChannel(channel: Channel?): Boolean {
    if (channel == null) return false
    val text = (channel.title + " " + channel.category).lowercase(Locale.ROOT)
    return FOOTBALL.containsMatchIn(text) && !NOT_FOOTBALL.containsMatchIn(text)
}

@Composable
fun ScoreTicker(visible: Boolean, modifier: Modifier = Modifier) {
    var scores by remember { mutableStateOf<List<LiveScore>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                scores = withContext(Dispatchers.IO) { liveScores() }
                delay(TICKER_REFRESH_MS)
            }
        }
    }
    // One match after another; a refresh carries on from where it was.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            delay(TICKER_ROTATE_MS)
            val count = scores.size
            if (count > 1) index = (index + 1) % count
        }
    }
    val score = scores.takeIf { it.isNotEmpty() }?.let { it[index % it.size] }
    AnimatedVisibility(visible = visible && score != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        AnimatedContent(
            targetState = score,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "score-ticker",
        ) { shown ->
            if (shown == null) return@AnimatedContent
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = stringResource(R.string.dial_ticker_live),
                    color = Color.White,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvColors.Danger)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (shown.clock.isNotBlank()) {
                    Text(text = shown.clock, color = Color.White, fontFamily = TvFonts.Body, fontSize = 15.sp, maxLines = 1)
                }
                Text(
                    text = stringResource(R.string.dial_ticker_score, shown.home, shown.homeScore, shown.awayScore, shown.away),
                    color = Color.White,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = shown.league,
                    color = Color.White.copy(alpha = 0.78f),
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val TICKER_REFRESH_MS = 120_000L
private const val TICKER_ROTATE_MS = 7_000L
