package com.m3u.tv

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.core.foundation.util.basic.title
import kotlinx.coroutines.yield

/*
 * Dial details page for films and series (Nuvio-style): backdrop, poster, plot and credits from the
 * provider, resume or start over, and for series a season picker with every episode listed.
 */

@Composable
fun DetailsScreen(
    state: DetailsState,
    active: Boolean,
    isFavourite: Boolean,
    onPlayFilm: (fromStart: Boolean) -> Unit,
    onContinueSeries: (SeriesProgress) -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onSelectSeason: (String) -> Unit,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    extras: DetailsExtrasState? = null,
    onOpenPerson: (CastMember) -> Unit = {},
) {
    BackHandler(enabled = active, onBack = onBack)
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(active, state.loading, state.channel.id) {
        if (active) {
            yield()
            runCatching { primaryFocus.requestFocus() }
        }
    }

    val film = state.film
    val series = state.series
    val title = film?.title ?: series?.title ?: state.channel.title.title()
    val backdrop = film?.backdrop ?: series?.backdrop ?: film?.poster ?: series?.poster ?: state.channel.cover
    val poster = film?.poster ?: series?.poster ?: state.channel.cover
    val season = series?.seasons?.firstOrNull { it.key == state.selectedSeason }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.34f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to TvColors.Background,
                        0.55f to TvColors.Background.copy(alpha = 0.82f),
                        1f to TvColors.Background.copy(alpha = 0.35f),
                    )
                )
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 64.dp, top = 48.dp, end = 64.dp, bottom = 48.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusGroup()
        ) {
            item(key = "header") {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    PosterArt(
                        model = poster,
                        modifier = Modifier
                            .width(196.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp,
                            lineHeight = 42.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MetaLine(
                            year = film?.year ?: series?.year,
                            rating = film?.rating ?: series?.rating,
                            duration = film?.duration,
                            genre = film?.genre ?: series?.genre,
                        )
                        val tmdb = extras?.extras?.takeIf { extras.channelId == state.channel.id }
                        val plot = film?.plot ?: series?.plot ?: tmdb?.overview
                        tmdb?.tagline?.let { tagline ->
                            Text(
                                text = tagline,
                                color = TvColors.Focus,
                                fontFamily = TvFonts.Body,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (plot != null) {
                            Text(
                                text = plot,
                                color = TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 17.sp,
                                lineHeight = 26.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 760.dp)
                            )
                        }
                        film?.director?.let { Credit(stringResource(R.string.dial_details_director, it)) }
                        // The provider's cast line, unless the photo row below replaces it.
                        if (tmdb?.cast.isNullOrEmpty()) {
                            (film?.cast ?: series?.cast)?.let { Credit(stringResource(R.string.dial_details_cast, it)) }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            when (state.kind) {
                                DetailsKind.Film -> FilmActions(
                                    resumeMs = state.resumeMs,
                                    primaryFocus = primaryFocus,
                                    onPlayFilm = onPlayFilm,
                                )
                                DetailsKind.Series -> SeriesActions(
                                    progress = state.seriesProgress,
                                    firstEpisode = series?.seasons?.firstOrNull()?.episodes?.firstOrNull(),
                                    primaryFocus = primaryFocus,
                                    onContinueSeries = onContinueSeries,
                                    onPlayEpisode = onPlayEpisode,
                                )
                            }
                            TvActionButton(
                                text = stringResource(
                                    if (isFavourite) R.string.dial_player_favourite_remove
                                    else R.string.dial_player_favourite_add
                                ),
                                icon = if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                onClick = onToggleFavourite,
                            )
                        }
                        when {
                            state.loading -> Credit(stringResource(R.string.dial_details_loading))
                            film == null && series == null ->
                                Credit(stringResource(R.string.dial_details_unavailable))
                            else -> Unit
                        }
                    }
                }
            }

            val currentExtras = extras?.takeIf { it.channelId == state.channel.id }
            val cast = currentExtras?.extras?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    CastRow(cast = cast, onOpenPerson = onOpenPerson)
                }
            }
            currentExtras?.extras?.imdbId?.let { imdbId ->
                item(key = "imdb") {
                    ImdbLink(url = "https://www.imdb.com/title/$imdbId/")
                }
            }

            if (state.kind == DetailsKind.Series && !state.loading) {
                val seasons = series?.seasons.orEmpty()
                if (seasons.isEmpty()) {
                    item(key = "no-episodes") {
                        Credit(stringResource(R.string.dial_details_no_episodes))
                    }
                } else {
                    item(key = "seasons") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.focusGroup()
                        ) {
                            items(seasons, key = { it.key }) { item ->
                                TvActionButton(
                                    text = stringResource(R.string.dial_details_season, item.key),
                                    icon = Icons.Rounded.VideoLibrary,
                                    selected = item.key == state.selectedSeason,
                                    onClick = { onSelectSeason(item.key) },
                                )
                            }
                        }
                    }
                    items(season?.episodes.orEmpty(), key = { "episode-${it.id}" }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            lastWatched = episode.id == state.seriesProgress?.episodeId,
                            onClick = { onPlayEpisode(episode) },
                        )
                    }
                }
            }

            val comments = currentExtras?.comments.orEmpty()
            if (comments.isNotEmpty()) {
                item(key = "comments-title") {
                    Text(
                        text = stringResource(R.string.dial_details_comments),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                    )
                }
                items(comments.size, key = { "comment-$it" }) { index ->
                    CommentCard(comments[index])
                }
            } else if (currentExtras != null && currentExtras.hasTmdbKey && !currentExtras.hasTraktKey) {
                item(key = "comments-hint") {
                    Credit(stringResource(R.string.dial_details_comments_hint))
                }
            }
        }
    }
}

@Composable
private fun FilmActions(
    resumeMs: Long,
    primaryFocus: FocusRequester,
    onPlayFilm: (fromStart: Boolean) -> Unit,
) {
    if (resumeMs > 0L) {
        TvActionButton(
            text = stringResource(R.string.dial_details_resume, formatClock(resumeMs)),
            icon = Icons.Rounded.PlayArrow,
            onClick = { onPlayFilm(false) },
            focusRequester = primaryFocus,
        )
        TvActionButton(
            text = stringResource(R.string.dial_details_start_over),
            icon = Icons.Rounded.Replay,
            onClick = { onPlayFilm(true) },
        )
    } else {
        TvActionButton(
            text = stringResource(R.string.dial_details_play),
            icon = Icons.Rounded.PlayArrow,
            onClick = { onPlayFilm(false) },
            focusRequester = primaryFocus,
        )
    }
}

@Composable
private fun SeriesActions(
    progress: SeriesProgress?,
    firstEpisode: SeriesEpisode?,
    primaryFocus: FocusRequester,
    onContinueSeries: (SeriesProgress) -> Unit,
    onPlayEpisode: (SeriesEpisode) -> Unit,
) {
    when {
        progress != null -> TvActionButton(
            text = stringResource(
                R.string.dial_details_continue_episode,
                progress.season,
                progress.episodeNum ?: "?",
            ),
            supportingText = progress.title?.takeIf { it.isNotBlank() },
            icon = Icons.Rounded.PlayArrow,
            onClick = { onContinueSeries(progress) },
            focusRequester = primaryFocus,
        )
        firstEpisode != null -> TvActionButton(
            text = stringResource(R.string.dial_details_play_first),
            icon = Icons.Rounded.PlayArrow,
            onClick = { onPlayEpisode(firstEpisode) },
            focusRequester = primaryFocus,
        )
        else -> Unit
    }
}

@Composable
private fun MetaLine(year: String?, rating: String?, duration: String?, genre: String?) {
    val parts = listOfNotNull(
        year,
        rating?.let { stringResource(R.string.dial_details_rating, it) },
        duration,
        genre,
    )
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEach { InfoPill(text = it, minHeight = 32.dp) }
    }
}

@Composable
private fun Credit(text: String) {
    Text(
        text = text,
        color = TvColors.TextMuted,
        fontFamily = TvFonts.Body,
        fontSize = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 760.dp)
    )
}

@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    lastWatched: Boolean,
    onClick: () -> Unit,
) {
    FocusFrame(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.02f,
        semanticsLabel = listOfNotNull(
            episode.episodeNum?.let { "Episode $it" },
            episode.title,
        ).joinToString(". "),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1000.dp)
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.78f) else TvColors.TextSecondary
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = stringResource(R.string.dial_details_episode, episode.episodeNum ?: "?"),
                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                fontFamily = TvFonts.Accent,
                fontSize = 20.sp,
                modifier = Modifier.width(64.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = episode.title,
                    color = primary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.plot?.let {
                    Text(
                        text = it,
                        color = secondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (lastWatched) {
                Text(
                    text = stringResource(R.string.dial_details_last_watched),
                    color = if (focused) TvColors.OnFocus else TvColors.Focus,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            episode.duration?.let {
                Text(
                    text = it,
                    color = secondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

internal fun formatClock(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** The cast with photos, drifting slowly sideways until the remote reaches it. */
@Composable
private fun CastRow(cast: List<CastMember>, onOpenPerson: (CastMember) -> Unit) {
    val listState = rememberLazyListState()
    var focusedInside by remember { mutableStateOf(false) }
    LaunchedEffect(cast, focusedInside) {
        if (focusedInside) return@LaunchedEffect
        delay(CAST_DRIFT_START_MS)
        while (true) {
            val scrolled = listState.scrollBy(CAST_DRIFT_PX)
            if (scrolled == 0f) {
                delay(CAST_DRIFT_PAUSE_MS)
                listState.animateScrollToItem(0)
                delay(CAST_DRIFT_START_MS)
            }
            delay(CAST_DRIFT_FRAME_MS)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.dial_details_cast_title),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .focusGroup()
                .onFocusChanged { focusedInside = it.hasFocus }
        ) {
            items(cast, key = { it.id }) { member ->
                FocusFrame(
                    onClick = { onOpenPerson(member) },
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.06f,
                    semanticsLabel = listOfNotNull(member.name, member.character).joinToString(", "),
                    modifier = Modifier.width(132.dp),
                ) { focused ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(10.dp),
                    ) {
                        AsyncImage(
                            model = member.photo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(TvColors.SurfaceRaised),
                        )
                        Text(
                            text = member.name,
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        member.character?.let {
                            Text(
                                text = it,
                                color = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 12.sp,
                                maxLines = 2,
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
internal fun ImdbLink(url: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(text = url, contentDescription = stringResource(R.string.dial_details_imdb_qr), size = 88.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.dial_details_imdb),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = url.removePrefix("https://www."),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun CommentCard(comment: TraktComment) {
    var revealed by remember { mutableStateOf(!comment.spoiler) }
    FocusFrame(
        onClick = { revealed = true },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.01f,
        semanticsLabel = comment.user,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1000.dp),
    ) { focused ->
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = listOfNotNull(
                    comment.user,
                    comment.rating?.let { stringResource(R.string.dial_details_comment_rating, it) },
                    stringResource(R.string.dial_details_comment_likes, comment.likes),
                ).joinToString(" · "),
                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
            Text(
                text = if (revealed) comment.text else stringResource(R.string.dial_details_spoiler),
                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = if (focused) 12 else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val CAST_DRIFT_START_MS = 2_500L
private const val CAST_DRIFT_PAUSE_MS = 2_000L
private const val CAST_DRIFT_FRAME_MS = 16L
private const val CAST_DRIFT_PX = 0.6f
