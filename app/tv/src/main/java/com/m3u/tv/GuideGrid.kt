package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/*
 * Dial timeline grid, laid out like TiviMate's main guide: channels down the side, time across the
 * top, each programme a block as wide as it is long, and an amber line at the current time.
 *
 * Instead of scrolling each row separately, the whole grid shares one time window. Focusing a
 * programme that sits near an edge moves that window, so every row, the time bar and the now line
 * stay in step. Up/down moves between channels at the same time of day; left/right moves through
 * time. The panel on top describes whatever is focused.
 */

private const val MINUTE_MS = 60_000L
private const val HALF_HOUR_MS = 30 * MINUTE_MS
private const val PAST_RANGE_MS = 24 * 60 * MINUTE_MS
private const val FUTURE_RANGE_MS = 24 * 60 * MINUTE_MS
/** Programmes this far outside the window stay composed so the remote can step onto them. */
private const val BUFFER_MS = 3 * 60 * MINUTE_MS
private const val DP_PER_MINUTE = 5f
private val CHANNEL_COLUMN_WIDTH = 208.dp
private val ROW_HEIGHT = 56.dp

@Composable
fun GuideGrid(
    channels: List<Channel>,
    numbers: Map<Int, Int>,
    listings: Map<Int, List<GuideProgramme>>,
    now: Long,
    onRequestListing: (Channel) -> Unit,
    onPlayLive: (Channel) -> Unit,
    onPlayCatchUp: (Channel, GuideProgramme) -> Unit,
    modifier: Modifier = Modifier,
    /** Reminders set, as [reminderKey]s; OK on a programme still to come sets or clears one. */
    reminderKeys: Set<String> = emptySet(),
    onRemind: (Channel, GuideProgramme) -> Unit = { _, _ -> },
) {
    val gridStart = remember { floorToHalfHour(System.currentTimeMillis() - PAST_RANGE_MS) }
    val gridEnd = remember { floorToHalfHour(System.currentTimeMillis() + FUTURE_RANGE_MS) }
    var windowStart by remember {
        mutableLongStateOf(floorToHalfHour(System.currentTimeMillis()) - HALF_HOUR_MS)
    }
    var focused by remember { mutableStateOf<Pair<Channel, GuideProgramme?>?>(null) }

    BoxWithConstraints(modifier) {
        val timelineWidth = maxWidth - CHANNEL_COLUMN_WIDTH
        val spanMs = (timelineWidth.value / DP_PER_MINUTE * MINUTE_MS).toLong().coerceAtLeast(HALF_HOUR_MS)
        val windowEnd = windowStart + spanMs

        // Keep the focused programme on screen: at least half an hour of it (or all of it, if shorter).
        fun reveal(programme: GuideProgramme) {
            val needed = minOf(programme.endMillis - programme.startMillis, HALF_HOUR_MS)
            val shown = minOf(programme.endMillis, windowEnd) - maxOf(programme.startMillis, windowStart)
            if (shown >= needed) return
            val target = if (programme.startMillis >= windowStart) {
                // Off to the right: bring its start in, with half an hour of context before it.
                floorToHalfHour(programme.startMillis) - HALF_HOUR_MS
            } else {
                // Off to the left: line its start up with the left edge.
                floorToHalfHour(programme.startMillis)
            }
            windowStart = target.coerceIn(gridStart, maxOf(gridStart, gridEnd - spanMs))
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            GridInfoPanel(
                focused = focused,
                numbers = numbers,
                now = now,
            )
            TimeBar(
                windowStart = windowStart,
                windowEnd = windowEnd,
                now = now,
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusGroup()
            ) {
                items(channels, key = { it.id }) { channel ->
                    LaunchedEffect(channel.id) { onRequestListing(channel) }
                    GridRow(
                        channel = channel,
                        number = numbers[channel.id],
                        programmes = listings[channel.id],
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = now,
                        onFocusProgramme = { programme ->
                            focused = channel to programme
                            if (programme != null) reveal(programme)
                        },
                        onPlayLive = { onPlayLive(channel) },
                        onPlayCatchUp = { programme -> onPlayCatchUp(channel, programme) },
                        onRemind = { programme -> onRemind(channel, programme) },
                        reminderKeys = reminderKeys,
                        channelId = channel.id,
                    )
                }
            }
        }
    }
}

@Composable
private fun GridInfoPanel(
    focused: Pair<Channel, GuideProgramme?>?,
    numbers: Map<Int, Int>,
    now: Long,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
    ) {
        val channel = focused?.first
        val programme = focused?.second
        if (channel == null) {
            Text(
                text = stringResource(R.string.dial_grid_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            )
        } else {
            FocusedProgrammeInfo(channel = channel, programme = programme, numbers = numbers, now = now)
        }
    }
}

@Composable
private fun FocusedProgrammeInfo(
    channel: Channel,
    programme: GuideProgramme?,
    numbers: Map<Int, Int>,
    now: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = listOfNotNull(numbers[channel.id]?.toString(), channel.title.title()).joinToString("  "),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = programme?.title ?: stringResource(R.string.dial_grid_no_info),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (programme != null) {
            val minutes = ((programme.endMillis - programme.startMillis) / MINUTE_MS).toInt()
            val status = when {
                programme.isOnAt(now) -> stringResource(R.string.dial_guide_now)
                programme.hasEndedBy(now) && programme.hasArchive ->
                    stringResource(R.string.dial_grid_catch_up_description)
                !programme.hasEndedBy(now) -> stringResource(R.string.dial_grid_upcoming)
                else -> null
            }
            Text(
                text = listOfNotNull(
                    stringResource(
                        R.string.dial_guide_time_range,
                        clockTime(programme.startMillis),
                        clockTime(programme.endMillis),
                    ),
                    stringResource(R.string.dial_grid_minutes, minutes),
                    status,
                ).joinToString("   "),
                color = TvColors.Focus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
            )
            if (programme.description.isNotBlank()) {
                Text(
                    text = programme.description,
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TimeBar(windowStart: Long, windowEnd: Long, now: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        Box(Modifier.width(CHANNEL_COLUMN_WIDTH))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            var tick = floorToHalfHour(windowStart)
            while (tick < windowEnd) {
                if (tick >= windowStart) {
                    key(tick) {
                        Text(
                            text = clockTime(tick),
                            color = TvColors.TextSecondary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.timelineSlot(
                                offset = offsetFor(tick, windowStart),
                                width = 72.dp,
                            )
                        )
                    }
                }
                tick += HALF_HOUR_MS
            }
            if (now in windowStart until windowEnd) {
                NowMarker(offsetFor(now, windowStart))
            }
        }
    }
}

@Composable
private fun GridRow(
    channel: Channel,
    number: Int?,
    programmes: List<GuideProgramme>?,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    onFocusProgramme: (GuideProgramme?) -> Unit,
    onPlayLive: () -> Unit,
    onPlayCatchUp: (GuideProgramme) -> Unit,
    onRemind: (GuideProgramme) -> Unit = {},
    reminderKeys: Set<String> = emptySet(),
    channelId: Int = 0,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
    ) {
        GridChannelCell(
            channel = channel,
            number = number,
            modifier = Modifier
                .width(CHANNEL_COLUMN_WIDTH)
                .fillMaxHeight()
                .padding(end = 6.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
        ) {
            val slice = programmes?.let {
                visibleSlice(it, windowStart - BUFFER_MS, windowEnd + BUFFER_MS)
            }.orEmpty()
            val coversWindow = slice.any { it.endMillis > windowStart && it.startMillis < windowEnd }
            if (programmes == null || !coversWindow) {
                PlaceholderCell(
                    loading = programmes == null,
                    onFocus = { onFocusProgramme(null) },
                    onClick = onPlayLive,
                )
            } else {
                slice.forEach { programme ->
                    key(programme.startMillis) {
                        ProgrammeCell(
                            programme = programme,
                            windowStart = windowStart,
                            now = now,
                            onFocus = { onFocusProgramme(programme) },
                            onClick = {
                                val current = System.currentTimeMillis()
                                when {
                                    programme.isOnAt(current) -> onPlayLive()
                                    programme.hasEndedBy(current) && programme.hasArchive ->
                                        onPlayCatchUp(programme)
                                    !programme.hasEndedBy(current) -> onRemind(programme)
                                    else -> Unit
                                }
                            },
                            reminded = reminderKey(channelId, programme.startMillis) in reminderKeys,
                        )
                    }
                }
            }
            if (now in windowStart until windowEnd) {
                NowMarker(offsetFor(now, windowStart))
            }
        }
    }
}

@Composable
private fun GridChannelCell(channel: Channel, number: Int?, modifier: Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TvColors.Surface.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = number?.toString().orEmpty(),
            color = TvColors.Focus,
            fontFamily = TvFonts.Accent,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.width(40.dp)
        )
        AsyncImage(
            model = channel.cover,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = channel.title.title(),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgrammeCell(
    programme: GuideProgramme,
    windowStart: Long,
    now: Long,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    reminded: Boolean = false,
) {
    val onNow = programme.isOnAt(now)
    val ended = programme.hasEndedBy(now)
    val replayable = ended && programme.hasArchive
    val width = ((programme.endMillis - programme.startMillis) / MINUTE_MS.toFloat() * DP_PER_MINUTE).dp
    // A programme that began before the visible window keeps its title at the window's left
    // edge rather than off-screen, where only the end of it ("...iew") would show.
    val hiddenStart = if (programme.startMillis < windowStart) {
        minOf(offsetFor(windowStart, programme.startMillis), maxOf(width - 48.dp, 0.dp))
    } else {
        0.dp
    }
    FocusFrame(
        onClick = onClick,
        onFocus = onFocus,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1f,
        focusedBorderWidth = 3.dp,
        semanticsLabel = listOfNotNull(
            clockTime(programme.startMillis),
            programme.title,
            if (replayable) stringResource(R.string.dial_guide_catch_up) else null,
        ).joinToString(". "),
        modifier = Modifier
            .timelineSlot(offset = offsetFor(programme.startMillis, windowStart), width = width)
            .padding(end = 3.dp, top = 2.dp, bottom = 2.dp)
    ) { focused ->
        // Past programmes that can't be replayed are dimmed, like TiviMate.
        if (ended && !replayable && !focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
            )
        }
        if (onNow && !focused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(TvColors.SurfaceRaised)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp + hiddenStart, end = 10.dp)
        ) {
            if (replayable || reminded) {
                Icon(
                    imageVector = if (reminded) Icons.Rounded.Notifications else Icons.Rounded.History,
                    contentDescription = null,
                    tint = if (focused) TvColors.OnFocus else TvColors.Focus,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = programme.title,
                color = when {
                    focused -> TvColors.OnFocus
                    ended && !replayable -> TvColors.TextMuted
                    else -> TvColors.TextPrimary
                },
                fontFamily = TvFonts.Body,
                fontWeight = if (onNow) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onNow) {
            val fraction = ((now - programme.startMillis).toFloat() /
                (programme.endMillis - programme.startMillis).toFloat()).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(if (focused) TvColors.OnFocus else TvColors.Focus)
            )
        }
    }
}

@Composable
private fun PlaceholderCell(
    loading: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    FocusFrame(
        onClick = onClick,
        onFocus = onFocus,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1f,
        focusedBorderWidth = 3.dp,
        semanticsLabel = stringResource(
            if (loading) R.string.dial_grid_loading else R.string.dial_grid_no_info
        ),
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 3.dp, top = 2.dp, bottom = 2.dp)
    ) { focused ->
        Text(
            text = stringResource(if (loading) R.string.dial_grid_loading else R.string.dial_grid_no_info),
            color = if (focused) TvColors.OnFocus else TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp)
        )
    }
}

@Composable
private fun NowMarker(offset: Dp) {
    Box(
        Modifier
            .timelineSlot(offset = offset, width = 2.dp)
            .background(TvColors.Focus)
    )
}

/**
 * Places a child at [offset] from the left of its row with an exact [width], even when that
 * reaches past the visible edge (the row clips it). Reports zero width so rows never grow.
 */
private fun Modifier.timelineSlot(offset: Dp, width: Dp): Modifier = layout { measurable, constraints ->
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
    val placeable = measurable.measure(
        Constraints.fixed(width.roundToPx().coerceAtLeast(1), height)
    )
    layout(0, height) {
        placeable.place(offset.roundToPx(), 0)
    }
}

private fun offsetFor(time: Long, windowStart: Long): Dp =
    ((time - windowStart) / MINUTE_MS.toFloat() * DP_PER_MINUTE).dp

/** Programmes that touch [from, to], plus one neighbour either side for the remote to step onto. */
private fun visibleSlice(programmes: List<GuideProgramme>, from: Long, to: Long): List<GuideProgramme> {
    if (programmes.isEmpty()) return emptyList()
    val first = programmes.indexOfFirst { it.endMillis > from }.let { if (it < 0) programmes.lastIndex else it }
    val last = programmes.indexOfLast { it.startMillis < to }.let { if (it < 0) 0 else it }
    if (last < first) return emptyList()
    return programmes.subList(maxOf(0, first - 1), minOf(programmes.lastIndex, last + 1) + 1)
}

/** Rounds down to the half hour in the device's own time zone. */
private fun floorToHalfHour(millis: Long): Long {
    val offset = TimeZone.getDefault().getOffset(millis)
    return millis - Math.floorMod(millis + offset, HALF_HOUR_MS)
}

private fun clockTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
