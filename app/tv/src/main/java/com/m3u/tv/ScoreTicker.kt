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
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------------------------------
 * The score ticker: on a sports channel, the matches being played right now go by along the bottom
 * of the picture, one at a time, while the controls are hidden (the Match Centre's feed, so no
 * extra requests). Settings › Dial › Player switches it off.
 * ---------------------------------------------------------------------------------------------- */

private val SPORTS_WORDS = listOf(
    "sport", "football", "soccer", "premier", "league", "liga", "laliga", "serie a", "bundesliga", "ligue",
    "bein", "dazn", "espn", "tnt sports", "bt sport", "sky sports", "eleven", "arena", "match", "uefa", "fifa",
    "supersport", "canal+ foot", "movistar", "ziggo sport", "viaplay", "fox soccer", "goal", "futbol", "fútbol",
)

/** Whether a channel looks like sport, by its name or its category. */
fun isSportsChannel(channel: Channel?): Boolean {
    if (channel == null) return false
    val text = (channel.title + " " + channel.category).lowercase(Locale.ROOT)
    return SPORTS_WORDS.any { it in text }
}

@Composable
fun ScoreTicker(visible: Boolean, modifier: Modifier = Modifier) {
    var scores by remember { mutableStateOf<List<LiveScore>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            scores = withContext(Dispatchers.IO) { liveScores() }
            delay(TICKER_REFRESH_MS)
        }
    }
    LaunchedEffect(scores) {
        index = 0
        while (scores.size > 1) {
            delay(TICKER_ROTATE_MS)
            index = (index + 1) % scores.size
        }
    }
    val score = scores.getOrNull(index)
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
                    .widthIn(max = 760.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = stringResource(R.string.dial_ticker_live),
                    color = Color.White,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvColors.Danger)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (shown.clock.isNotBlank()) {
                    Text(text = shown.clock, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 14.sp, maxLines = 1)
                }
                Text(
                    text = "${shown.home} ${shown.homeScore}–${shown.awayScore} ${shown.away}",
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
                    color = TvColors.TextMuted,
                    fontFamily = TvFonts.Body,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val TICKER_REFRESH_MS = 120_000L
private const val TICKER_ROTATE_MS = 7_000L
