package com.m3u.tv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
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
import java.text.DateFormat
import java.util.Date

/* -------------------------------------------------------------------------------------------------
 * The Radio tab: stations (popular, near you, by genre, search, recent) and podcasts (charts,
 * followed, search), with a show's episodes as a list. OK plays; a station can be added to
 * favourites from the player like any channel.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun RadioScreen(
    onPlaying: () -> Unit,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val firstSection = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    BackHandler(enabled = state.openPodcast != null) { viewModel.back() }
    LaunchedEffect(Unit) {
        viewModel.open(state.section)
        withFrameNanos { }
        runCatching { firstSection.requestFocus() }
    }
    LaunchedEffect(followed) { if (state.section == RadioSection.MyPodcasts) viewModel.open(RadioSection.MyPodcasts) }
    val sections = remember {
        listOf(
            RadioSection.Popular, RadioSection.Near, RadioSection.Genres, RadioSection.SearchStations, RadioSection.Recent,
            RadioSection.TopPodcasts, RadioSection.MyPodcasts, RadioSection.SearchPodcasts,
        )
    }
    val openPodcast = state.openPodcast
    if (openPodcast != null) {
        EpisodesPage(state, openPodcast, viewModel, onPlaying, followed.any { it.id == openPodcast.id })
        return
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
                    text = stringResource(R.string.dial_nav_radio),
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 26.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
            }
            itemsIndexed(sections, key = { _, section -> section.key }) { index, section ->
                if (section == RadioSection.TopPodcasts) {
                    Text(
                        text = stringResource(R.string.dial_radio_podcasts_heading),
                        color = TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                TvActionButton(
                    text = section.label(),
                    icon = section.icon(),
                    onClick = { viewModel.open(section) },
                    selected = state.section == section,
                    focusRequester = firstSection.takeIf { index == 0 },
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
            val section = state.section
            val searching = section == RadioSection.SearchStations || section == RadioSection.SearchPodcasts
            if (searching) {
                val fieldFocus = remember(section) { FocusRequester() }
                Box(Modifier.widthIn(max = 720.dp)) {
                    DialTextField(
                        label = stringResource(if (section == RadioSection.SearchPodcasts) R.string.dial_radio_search_podcasts else R.string.dial_radio_search_stations),
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = stringResource(R.string.dial_radio_search_placeholder),
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                        readOnly = false,
                        focusRequester = fieldFocus,
                        downFocus = gridFocus.takeIf { state.stations.isNotEmpty() || state.podcasts.isNotEmpty() },
                    )
                }
                LaunchedEffect(section) {
                    withFrameNanos { }
                    runCatching { fieldFocus.requestFocus() }
                }
            } else {
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
                            state.loading -> stringResource(R.string.dial_radio_loading)
                            state.failed -> stringResource(R.string.dial_radio_failed)
                            section == RadioSection.Recent && state.stations.isEmpty() -> stringResource(R.string.dial_radio_recent_empty)
                            section == RadioSection.MyPodcasts && state.podcasts.isEmpty() -> stringResource(R.string.dial_radio_my_podcasts_empty)
                            section.isPodcasts -> stringResource(R.string.dial_radio_podcasts_hint)
                            else -> stringResource(R.string.dial_radio_stations_hint)
                        },
                        color = TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                    )
                }
            }
            if (section == RadioSection.Genres && state.tags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.focusGroup(),
                ) {
                    items(state.tags, key = { it }) { tag ->
                        TvActionButton(
                            text = tag.replaceFirstChar { it.uppercase() },
                            icon = Icons.Rounded.Label,
                            onClick = { viewModel.selectTag(tag) },
                            selected = state.tag == tag,
                            showTextWhenUnfocused = true,
                        )
                    }
                }
            }
            if (section.isPodcasts) {
                PodcastGrid(state.podcasts, followed.map { it.id }.toSet(), viewModel, gridFocus)
            } else {
                StationGrid(state.stations, viewModel, onPlaying, gridFocus, recent)
            }
        }
    }
}

private val RadioSection.isPodcasts: Boolean
    get() = this == RadioSection.TopPodcasts || this == RadioSection.MyPodcasts || this == RadioSection.SearchPodcasts

@Composable
private fun StationGrid(
    stations: List<RadioStation>,
    viewModel: RadioViewModel,
    onPlaying: () -> Unit,
    firstFocus: FocusRequester,
    recent: List<RadioStation>,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        gridItemsIndexed(stations, key = { _, station -> station.id }) { index, station ->
            ArtworkCard(
                title = station.name,
                subtitle = listOfNotNull(
                    station.country,
                    station.codec?.uppercase()?.let { codec -> if (station.bitrate > 0) "$codec ${station.bitrate}k" else codec },
                ).joinToString(" · ").ifBlank { station.tags.take(2).joinToString(", ") },
                artwork = station.logo,
                fallback = Icons.Rounded.Radio,
                onClick = { viewModel.playStation(station, onPlaying) },
                focusRequester = firstFocus.takeIf { index == 0 },
                corner = if (recent.any { it.id == station.id }) stringResource(R.string.dial_radio_played) else null,
            )
        }
    }
}

@Composable
private fun PodcastGrid(
    podcasts: List<Podcast>,
    followedIds: Set<String>,
    viewModel: RadioViewModel,
    firstFocus: FocusRequester,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 40.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        gridItemsIndexed(podcasts, key = { _, podcast -> podcast.id }) { index, podcast ->
            ArtworkCard(
                title = podcast.name,
                subtitle = listOfNotNull(podcast.author, podcast.genre).joinToString(" · "),
                artwork = podcast.artwork,
                fallback = Icons.Rounded.Podcasts,
                onClick = { viewModel.openPodcast(podcast) },
                onLongClick = { viewModel.toggleFollow(podcast) },
                focusRequester = firstFocus.takeIf { index == 0 },
                corner = if (podcast.id in followedIds) stringResource(R.string.dial_radio_following) else null,
            )
        }
    }
}

/** A square picture with a name under it: a station's logo or a podcast's cover. */
@Composable
private fun ArtworkCard(
    title: String,
    subtitle: String,
    artwork: String?,
    fallback: ImageVector,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    corner: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusFrame(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = RoundedCornerShape(12.dp),
            semanticsLabel = title,
            focusedScale = 1.05f,
            focusRequester = focusRequester,
            focusedFill = TvColors.SurfaceRaised,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TvColors.Surface),
            ) {
                if (artwork != null) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                    )
                } else {
                    Icon(
                        imageVector = fallback,
                        contentDescription = null,
                        tint = TvColors.TextMuted,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp),
                    )
                }
                if (corner != null) {
                    Text(
                        text = corner,
                        color = TvColors.OnFocus,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvColors.Focus)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
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
private fun EpisodesPage(
    state: RadioState,
    podcast: Podcast,
    viewModel: RadioViewModel,
    onPlaying: () -> Unit,
    following: Boolean,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 32.dp, end = 48.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.width(260.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TvColors.Surface),
            ) {
                AsyncImage(model = podcast.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(
                text = podcast.name,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            podcast.author?.let {
                Text(text = it, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TvActionButton(
                text = stringResource(if (following) R.string.dial_radio_following else R.string.dial_radio_follow),
                icon = if (following) Icons.Rounded.Check else Icons.Rounded.Star,
                checked = following,
                onClick = { viewModel.toggleFollow(podcast) },
                focusRequester = firstFocus,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.dial_radio_back_hint),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 12.sp,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 40.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusGroup(),
        ) {
            item {
                Text(
                    text = when {
                        state.episodesLoading -> stringResource(R.string.dial_radio_loading)
                        state.episodes.isEmpty() -> stringResource(R.string.dial_radio_no_episodes)
                        else -> stringResource(R.string.dial_radio_episodes, state.episodes.size)
                    },
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            items(state.episodes, key = { it.id }) { episode ->
                EpisodeRow(episode = episode, onClick = { viewModel.playEpisode(episode, onPlaying) })
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, onClick: () -> Unit) {
    val date = remember(episode.releasedMs) {
        episode.releasedMs.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    }
    val duration = episode.durationMs.takeIf { it > 0 }?.let { formatDuration(it / 1000) }
    FocusFrame(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.01f,
        semanticsLabel = episode.title,
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.78f) else TvColors.TextSecondary
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = listOfNotNull(date, duration).joinToString(" · "),
                color = secondary,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
            )
            Text(
                text = episode.title,
                color = primary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            episode.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = secondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = if (focused) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RadioSection.label(): String = when (this) {
    RadioSection.Popular -> stringResource(R.string.dial_radio_popular)
    RadioSection.Near -> stringResource(R.string.dial_radio_near)
    RadioSection.Genres -> stringResource(R.string.dial_radio_genres)
    RadioSection.SearchStations -> stringResource(R.string.dial_radio_search_stations)
    RadioSection.Recent -> stringResource(R.string.dial_radio_recent)
    RadioSection.TopPodcasts -> stringResource(R.string.dial_radio_top_podcasts)
    RadioSection.MyPodcasts -> stringResource(R.string.dial_radio_my_podcasts)
    RadioSection.SearchPodcasts -> stringResource(R.string.dial_radio_search_podcasts)
}

private fun RadioSection.icon(): ImageVector = when (this) {
    RadioSection.Popular -> Icons.Rounded.Whatshot
    RadioSection.Near -> Icons.Rounded.Place
    RadioSection.Genres -> Icons.Rounded.Label
    RadioSection.SearchStations -> Icons.Rounded.Search
    RadioSection.Recent -> Icons.Rounded.History
    RadioSection.TopPodcasts -> Icons.Rounded.Podcasts
    RadioSection.MyPodcasts -> Icons.Rounded.Star
    RadioSection.SearchPodcasts -> Icons.Rounded.Search
}
