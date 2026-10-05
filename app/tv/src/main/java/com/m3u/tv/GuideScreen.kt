package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LiveTv
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * Dial guide. Two layouts, remembered between visits: the timeline grid (GuideGrid.kt) and a
 * TiviMate-style channel list with what's on now on the left and the focused channel's schedule
 * on the right. Past programmes the provider has archived are marked
 * "Catch up" and play from the start. Everything comes from the Xtream API on demand, so there is
 * no large XMLTV download to wait for on a Fire TV Stick.
 */

@Composable
fun GuideScreen(
    state: TvUiState,
    dial: DialViewModel,
    onSelectPlaylist: (Playlist) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onPlayLive: (Channel) -> Unit,
    onPlayCatchUp: (Channel, GuideProgramme) -> Unit,
) {
    val livePlaylists = remember(state.playlists) {
        state.playlists.filter { !it.isVod && !it.isSeries }
    }
    // Reminders: OK on a programme still to come sets one (and clears it on a second press).
    val reminders by dial.reminders.collectAsStateWithLifecycle()
    val reminderKeys = remember(reminders) { reminders.map { it.key }.toSet() }
    val context = LocalContext.current
    val reminderSet = stringResource(R.string.dial_reminder_set)
    val reminderCleared = stringResource(R.string.dial_reminder_cleared)
    val onRemind: (Channel, GuideProgramme) -> Unit = { channel, programme ->
        val set = dial.toggleReminder(channel, programme)
        Toast.makeText(context, if (set) reminderSet.format(programme.title) else reminderCleared, Toast.LENGTH_SHORT).show()
    }
    val selected = state.selectedPlaylist?.takeIf { playlist -> livePlaylists.any { it.url == playlist.url } }

    // Only while this tab is the one showing (not while it fades out behind the next tab).
    val active = LocalTvTabActive.current
    LaunchedEffect(livePlaylists, selected, active) {
        if (active && selected == null) livePlaylists.firstOrNull()?.let(onSelectPlaylist)
    }

    if (livePlaylists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GuideNote(stringResource(R.string.dial_guide_no_live))
        }
        return
    }

    // The selected category (or all of a small playlist) is loaded by TvHomeViewModel, shared
    // with the Library, so a 50k-channel provider is never held in memory all at once.
    val visible = if (selected != null) state.channels else emptyList()
    val numbers = remember(visible) {
        visible.withIndex().associate { (index, channel) -> channel.id to index + 1 }
    }
    val uncategorised = stringResource(R.string.dial_category_uncategorised)
    val showCategories = selected != null && state.categories.size > 1
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val nowNext by dial.nowNext.collectAsStateWithLifecycle()
    val schedule by dial.schedule.collectAsStateWithLifecycle()
    val listings by dial.listings.collectAsStateWithLifecycle()
    val preferences by dial.preferences.collectAsStateWithLifecycle()
    val layout = preferences.guideLayout

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 28.dp, end = 40.dp, bottom = 16.dp)
    ) {
        // One row of controls: layout, then live playlists (if more than one), then categories.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusGroup()
        ) {
            item(key = "layout-grid") {
                TvActionButton(
                    text = stringResource(R.string.dial_guide_layout_grid),
                    icon = Icons.Rounded.GridView,
                    selected = layout == DialGuideLayout.Grid,
                    onClick = { dial.updatePreferences { it.copy(guideLayout = DialGuideLayout.Grid) } },
                )
            }
            item(key = "layout-list") {
                TvActionButton(
                    text = stringResource(R.string.dial_guide_layout_list),
                    icon = Icons.Rounded.ViewAgenda,
                    selected = layout == DialGuideLayout.List,
                    onClick = { dial.updatePreferences { it.copy(guideLayout = DialGuideLayout.List) } },
                )
            }
            if (livePlaylists.size > 1) {
                items(livePlaylists, key = { "playlist-${it.url}" }) { playlist ->
                    TvActionButton(
                        text = playlist.title,
                        icon = Icons.Rounded.LiveTv,
                        selected = playlist.url == selected?.url,
                        onClick = { onSelectPlaylist(playlist) },
                    )
                }
            }
            if (showCategories) {
                if (state.allCategoriesAllowed) {
                    item(key = "all") {
                        TvActionButton(
                            text = stringResource(R.string.dial_guide_all_channels),
                            icon = Icons.Rounded.Category,
                            selected = state.selectedCategory == null,
                            onClick = { onSelectCategory(null) },
                        )
                    }
                }
                items(state.categories, key = { "category-${it.name}" }) { category ->
                    TvActionButton(
                        text = category.name.ifBlank { uncategorised },
                        icon = Icons.Rounded.Category,
                        selected = state.selectedCategory == category.name,
                        onClick = { onSelectCategory(category.name) },
                    )
                }
            }
        }

        if (layout == DialGuideLayout.Grid) {
            GuideGrid(
                channels = visible,
                numbers = numbers,
                listings = listings,
                now = now,
                onRequestListing = dial::requestListing,
                onPlayLive = onPlayLive,
                onPlayCatchUp = onPlayCatchUp,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                reminderKeys = reminderKeys,
                onRemind = onRemind,
            )
        } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .weight(0.48f)
                    .fillMaxHeight()
                    .focusGroup()
            ) {
                items(visible, key = { it.id }) { channel ->
                    LaunchedEffect(channel.id) { dial.requestNowNext(channel) }
                    GuideChannelRow(
                        channel = channel,
                        number = numbers[channel.id],
                        programmes = nowNext[channel.id],
                        now = now,
                        onFocus = {
                            focusedChannel = channel
                            dial.loadSchedule(channel)
                        },
                        onClick = { onPlayLive(channel) },
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(0.52f)
                    .fillMaxHeight()
            ) {
                val channel = focusedChannel
                val current = schedule?.takeIf { channel != null && it.channelId == channel.id }
                if (channel == null) {
                    GuideNote(stringResource(R.string.dial_guide_pick_channel))
                } else {
                    Text(
                        text = channel.title.title(),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        current == null || current.loading ->
                            GuideNote(stringResource(R.string.dial_guide_loading))
                        !current.supported ->
                            GuideNote(stringResource(R.string.dial_guide_unsupported))
                        current.programmes.isEmpty() ->
                            GuideNote(stringResource(R.string.dial_guide_empty))
                        else -> ScheduleList(
                            channel = channel,
                            programmes = current.programmes,
                            now = now,
                            onPlayLive = onPlayLive,
                            onPlayCatchUp = onPlayCatchUp,
                            onRemind = onRemind,
                            reminderKeys = reminderKeys,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun GuideChannelRow(
    channel: Channel,
    number: Int?,
    programmes: List<GuideProgramme>?,
    now: Long,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val onNow = programmes?.firstOrNull { it.isOnAt(now) }
    FocusFrame(
        onClick = onClick,
        onFocus = onFocus,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        semanticsLabel = listOfNotNull(
            number?.toString(),
            channel.title,
            onNow?.title,
        ).joinToString(". "),
        modifier = Modifier.fillMaxWidth()
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.78f) else TvColors.TextSecondary
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = number?.toString().orEmpty(),
                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                fontFamily = TvFonts.Accent,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.width(52.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                AsyncImage(
                    model = channel.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = channel.title.title(),
                    color = primary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onNow != null) {
                    Text(
                        text = onNow.title,
                        color = secondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProgressTrack(
                        fraction = ((now - onNow.startMillis).toFloat() /
                            (onNow.endMillis - onNow.startMillis).toFloat()).coerceIn(0f, 1f),
                        focused = focused,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleList(
    channel: Channel,
    programmes: List<GuideProgramme>,
    now: Long,
    onPlayLive: (Channel) -> Unit,
    onPlayCatchUp: (Channel, GuideProgramme) -> Unit,
    onRemind: (Channel, GuideProgramme) -> Unit = { _, _ -> },
    reminderKeys: Set<String> = emptySet(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(channel.id, programmes) {
        val index = programmes.indexOfFirst { !it.hasEndedBy(System.currentTimeMillis()) }
        if (index > 0) listState.scrollToItem(index)
    }
    val anyArchive = programmes.any { it.hasArchive }
    if (anyArchive) {
        Text(
            text = stringResource(R.string.dial_guide_catch_up_hint),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 13.sp,
        )
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        items(programmes, key = { "${it.startMillis}-${it.title}" }) { programme ->
            ProgrammeRow(
                programme = programme,
                now = now,
                onClick = {
                    when {
                        programme.isOnAt(System.currentTimeMillis()) -> onPlayLive(channel)
                        programme.hasEndedBy(System.currentTimeMillis()) && programme.hasArchive ->
                            onPlayCatchUp(channel, programme)
                        !programme.hasEndedBy(System.currentTimeMillis()) -> onRemind(channel, programme)
                        else -> Unit
                    }
                },
                reminded = reminderKey(channel.id, programme.startMillis) in reminderKeys,
            )
        }
    }
}

@Composable
private fun ProgrammeRow(
    programme: GuideProgramme,
    now: Long,
    onClick: () -> Unit,
    reminded: Boolean = false,
) {
    val onNow = programme.isOnAt(now)
    val replayable = programme.hasEndedBy(now) && programme.hasArchive
    val badge = when {
        onNow -> stringResource(R.string.dial_guide_now)
        replayable -> stringResource(R.string.dial_guide_catch_up)
        reminded -> stringResource(R.string.dial_guide_reminder_badge)
        else -> null
    }
    // A programme still to come says what OK does, but only while it's the one in focus.
    val remindHint = stringResource(R.string.dial_guide_remind_hint).takeIf { !onNow && !reminded && !programme.hasEndedBy(now) }
    val timeRange = stringResource(
        R.string.dial_guide_time_range,
        guideTime(programme.startMillis, now),
        guideTime(programme.endMillis, now),
    )
    FocusFrame(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        semanticsLabel = listOfNotNull(timeRange, programme.title, badge).joinToString(". "),
        modifier = Modifier.fillMaxWidth()
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.78f) else TvColors.TextSecondary
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timeRange,
                    color = secondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                )
                val shownBadge = badge ?: remindHint?.takeIf { focused }
                if (shownBadge != null) {
                    Text(
                        text = shownBadge,
                        color = if (focused) TvColors.Focus else TvColors.OnFocus,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (focused) TvColors.OnFocus else TvColors.Focus)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = programme.title,
                color = primary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme.description.isNotBlank()) {
                Text(
                    text = programme.description,
                    color = secondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (focused) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onNow) {
                ProgressTrack(
                    fraction = ((now - programme.startMillis).toFloat() /
                        (programme.endMillis - programme.startMillis).toFloat()).coerceIn(0f, 1f),
                    focused = focused,
                )
            }
        }
    }
}

@Composable
private fun ProgressTrack(fraction: Float, focused: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (focused) TvColors.OnFocus.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.16f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(if (focused) TvColors.OnFocus else TvColors.Focus)
        )
    }
}

@Composable
private fun GuideNote(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}

/** "20:00" for today, "Sat 20:00" for other days (in the device's own time format). */
private fun guideTime(millis: Long, now: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = day.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) time else "${SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis))} $time"
}
