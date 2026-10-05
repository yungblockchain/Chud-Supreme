package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.util.Locale

/* -------------------------------------------------------------------------------------------------
 * The YouTube tab: sections down the left (live, trending, music, gaming, trailers, podcasts, the
 * channels followed, history, search, options), a grid of videos on the right. OK plays; holding
 * OK follows the video's channel.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun YouTubeScreen(
    onPlaying: () -> Unit,
    viewModel: YouTubeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val firstSection = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        viewModel.open(state.section)
        withFrameNanos { }
        runCatching { firstSection.requestFocus() }
    }
    LaunchedEffect(state.section, history) {
        if (state.section == YtSection.History) viewModel.refresh()
    }
    val sections = remember(followed) {
        listOf(
            YtSection.Live, YtSection.Trending, YtSection.Music, YtSection.Gaming, YtSection.Trailers, YtSection.Podcasts,
        ) + followed.map { YtSection.Followed(it) } + listOf(YtSection.History, YtSection.Search, YtSection.Options)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, top = 28.dp, end = 32.dp),
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier
                .width(236.dp)
                .fillMaxHeight()
                .focusGroup()
                .focusProperties { right = gridFocus },
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.dial_nav_youtube),
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 26.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
            }
            items(sections, key = { it.key }) { section ->
                TvActionButton(
                    text = section.label(),
                    icon = section.icon(),
                    onClick = { viewModel.open(section) },
                    // Holding OK on a followed channel unfollows it; on the history, clears it.
                    onLongClick = when (section) {
                        is YtSection.Followed -> ({ viewModel.toggleFollow(section.channel) })
                        YtSection.History -> ({ viewModel.clearHistory() })
                        else -> null
                    },
                    selected = state.section == section,
                    focusRequester = firstSection.takeIf { section == sections.first() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "refresh") {
                TvActionButton(
                    text = stringResource(R.string.dial_youtube_refresh),
                    icon = Icons.Rounded.Refresh,
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (val section = state.section) {
                YtSection.Options -> YouTubeOptions(prefs, viewModel, gridFocus)
                YtSection.Search -> {
                    val fieldFocus = remember { FocusRequester() }
                    Box(Modifier.widthIn(max = 720.dp)) {
                        DialTextField(
                            label = stringResource(R.string.dial_youtube_search_label),
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = stringResource(R.string.dial_youtube_search_placeholder),
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                            readOnly = false,
                            focusRequester = fieldFocus,
                            downFocus = gridFocus.takeIf { state.items.isNotEmpty() },
                        )
                    }
                    LaunchedEffect(Unit) {
                        withFrameNanos { }
                        runCatching { fieldFocus.requestFocus() }
                    }
                    if (state.channelResults.isNotEmpty()) {
                        ChannelResultsRow(
                            channels = state.channelResults,
                            followedIds = followed.map { it.id }.toSet(),
                            onToggle = viewModel::toggleFollow,
                        )
                    }
                    VideoGrid(state, viewModel, onPlaying, gridFocus)
                }
                else -> {
                    Column {
                        Text(
                            text = section.label(),
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                        )
                        Text(
                            text = when {
                                state.loading -> stringResource(R.string.dial_youtube_loading)
                                state.failed -> stringResource(R.string.dial_youtube_failed)
                                section == YtSection.History && state.items.isEmpty() -> stringResource(R.string.dial_youtube_history_empty)
                                section == YtSection.History -> stringResource(R.string.dial_youtube_history_hint)
                                section is YtSection.Followed -> stringResource(R.string.dial_youtube_followed_hint)
                                else -> stringResource(R.string.dial_youtube_hint)
                            },
                            color = TvColors.TextSecondary,
                            fontFamily = TvFonts.Body,
                            fontSize = 14.sp,
                        )
                    }
                    VideoGrid(state, viewModel, onPlaying, gridFocus)
                }
            }
        }
    }
}

@Composable
private fun VideoGrid(
    state: YouTubeState,
    viewModel: YouTubeViewModel,
    onPlaying: () -> Unit,
    firstFocus: FocusRequester,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        gridItemsIndexed(state.items, key = { _, video -> video.id }) { index, video ->
            VideoCard(
                video = video,
                opening = state.opening?.id == video.id,
                onClick = { viewModel.play(video, onPlaying) },
                onLongClick = { viewModel.toggleFollowOf(video) },
                focusRequester = firstFocus.takeIf { index == 0 },
            )
        }
    }
}

@Composable
fun VideoCard(
    video: YouTubeVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    opening: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusFrame(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = RoundedCornerShape(10.dp),
            semanticsLabel = video.title,
            focusedScale = 1.04f,
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.Surface),
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val badge = when {
                    opening -> stringResource(R.string.dial_youtube_opening)
                    video.live -> stringResource(R.string.dial_youtube_live)
                    video.durationSec > 0 -> formatDuration(video.durationSec)
                    else -> null
                }
                if (badge != null) {
                    Text(
                        text = badge,
                        color = Color.White,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (video.live && !opening) TvColors.Danger else Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = video.title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val line = listOfNotNull(
            video.channel,
            video.views.takeIf { it >= 0 }?.let { formatViews(it) },
            video.uploaded,
        ).joinToString(" · ")
        if (line.isNotBlank()) {
            Text(
                text = line,
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChannelResultsRow(
    channels: List<YouTubeChannel>,
    followedIds: Set<String>,
    onToggle: (YouTubeChannel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.dial_youtube_channels),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(channels, key = { it.id }) { channel ->
                val following = channel.id in followedIds
                TvActionButton(
                    text = channel.name,
                    icon = if (following) Icons.Rounded.Check else Icons.Rounded.Person,
                    onClick = { onToggle(channel) },
                    checked = following,
                    supportingText = stringResource(if (following) R.string.dial_youtube_following else R.string.dial_youtube_follow),
                )
            }
        }
    }
}

@Composable
private fun YouTubeOptions(prefs: YouTubePrefs, viewModel: YouTubeViewModel, firstFocus: FocusRequester) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_youtube_options),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_youtube_play_in),
                value = stringResource(if (prefs.playInApp) R.string.dial_youtube_play_here else R.string.dial_youtube_play_app),
                onClick = { viewModel.updatePreferences { it.copy(playInApp = !it.playInApp) } },
                focusRequester = firstFocus,
            )
        }
        item {
            val heights = listOf(720, 1080, 2160)
            SettingRow(
                label = stringResource(R.string.dial_youtube_quality),
                value = if (prefs.maxHeight >= 2160) "4K" else "${prefs.maxHeight}p",
                onClick = { viewModel.updatePreferences { it.copy(maxHeight = heights.nextAfter(it.maxHeight)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_youtube_sponsorblock),
                value = stringResource(if (prefs.sponsorBlock) R.string.dial_value_on else R.string.dial_value_off),
                onClick = { viewModel.updatePreferences { it.copy(sponsorBlock = !it.sponsorBlock) } },
            )
        }
        item {
            Text(
                text = stringResource(R.string.dial_youtube_sponsorblock_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        items(SPONSORBLOCK_CATEGORIES, key = { it }) { category ->
            val on = category in prefs.sponsorCategories
            TvActionButton(
                text = category.sponsorLabel(),
                icon = if (on) Icons.Rounded.Check else Icons.Rounded.PlayCircle,
                checked = on,
                enabled = prefs.sponsorBlock,
                focusableWhenDisabled = true,
                onClick = {
                    viewModel.updatePreferences {
                        it.copy(sponsorCategories = if (on) it.sponsorCategories - category else it.sponsorCategories + category)
                    }
                },
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
    }
}

@Composable
private fun String.sponsorLabel(): String = stringResource(
    when (this) {
        "sponsor" -> R.string.dial_sponsor_sponsor
        "selfpromo" -> R.string.dial_sponsor_selfpromo
        "interaction" -> R.string.dial_sponsor_interaction
        "intro" -> R.string.dial_sponsor_intro
        "outro" -> R.string.dial_sponsor_outro
        "preview" -> R.string.dial_sponsor_preview
        "music_offtopic" -> R.string.dial_sponsor_music
        else -> R.string.dial_sponsor_filler
    }
)

@Composable
private fun YtSection.label(): String = when (this) {
    YtSection.Live -> stringResource(R.string.dial_youtube_section_live)
    YtSection.Trending -> stringResource(R.string.dial_youtube_section_trending)
    YtSection.Music -> stringResource(R.string.dial_youtube_section_music)
    YtSection.Gaming -> stringResource(R.string.dial_youtube_section_gaming)
    YtSection.Trailers -> stringResource(R.string.dial_youtube_section_trailers)
    YtSection.Podcasts -> stringResource(R.string.dial_youtube_section_podcasts)
    YtSection.History -> stringResource(R.string.dial_youtube_section_history)
    YtSection.Search -> stringResource(R.string.dial_nav_search)
    YtSection.Options -> stringResource(R.string.dial_youtube_options)
    is YtSection.Followed -> channel.name
}

private fun YtSection.icon(): ImageVector = when (this) {
    YtSection.Live -> Icons.Rounded.LiveTv
    YtSection.Trending -> Icons.Rounded.Whatshot
    YtSection.Music -> Icons.Rounded.MusicNote
    YtSection.Gaming -> Icons.Rounded.SportsEsports
    YtSection.Trailers -> Icons.Rounded.Theaters
    YtSection.Podcasts -> Icons.Rounded.Podcasts
    YtSection.History -> Icons.Rounded.History
    YtSection.Search -> Icons.Rounded.Search
    YtSection.Options -> Icons.Rounded.Settings
    is YtSection.Followed -> Icons.Rounded.Person
}

/** "12:34" or "1:02:03". */
internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** "1.2M views", "48K views". */
internal fun formatViews(views: Long): String = when {
    views >= 10_000_000 -> "${views / 1_000_000}M"
    views >= 1_000_000 -> String.format(Locale.US, "%.1fM", views / 1_000_000.0)
    views >= 1_000 -> "${views / 1_000}K"
    else -> views.toString()
}
