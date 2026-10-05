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
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.tv.material3.Icon
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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    onTraktRate: (Int) -> Unit = {},
    onTraktComment: (String, Boolean) -> Unit = { _, _ -> },
    onTraktWatched: () -> Unit = {},
    /** Plays the trailer TMDB lists (through the YouTube tab's player). */
    onTrailer: ((String) -> Unit)? = null,
    hideWatched: Boolean = false,
    onToggleHideWatched: () -> Unit = {},
    /** Marks episodes watched or not (the whole season, or one on hold-OK). */
    onSetWatched: (List<String>, Boolean) -> Unit = { _, _ -> },
) {
    BackHandler(enabled = active, onBack = onBack)
    var markedHere by remember(state.channel.id) { mutableStateOf(emptySet<String>()) }
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
                        val tmdb = extras?.extras?.takeIf { extras.channelId == state.channel.id }
                        val logo = tmdb?.logo
                        if (logo != null) {
                            // The title's own artwork, with the name underneath for screen readers.
                            AsyncImage(
                                model = logo,
                                contentDescription = title,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .widthIn(max = 480.dp)
                                    .height(96.dp),
                            )
                        } else {
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
                        }
                        MetaLine(
                            year = film?.year ?: series?.year,
                            rating = if (extras?.ratings.isNullOrEmpty()) film?.rating ?: series?.rating else null,
                            duration = film?.duration ?: tmdb?.runtimeMinutes?.let { stringResource(R.string.dial_details_minutes, it) },
                            genre = film?.genre ?: series?.genre ?: tmdb?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            certification = tmdb?.certification,
                        )
                        val ratings = extras?.ratings.orEmpty()
                        if (ratings.isNotEmpty()) RatingsRow(ratings)
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
                            // Any episode at all: for the shows you've seen a hundred times.
                            val allEpisodes = series?.seasons?.flatMap { it.episodes }.orEmpty()
                            if (allEpisodes.size > 1) {
                                TvActionButton(
                                    text = stringResource(R.string.dial_details_random_episode),
                                    icon = Icons.Rounded.Shuffle,
                                    onClick = { onPlayEpisode(allEpisodes.random()) },
                                )
                            }
                            val trailerKey = extras?.takeIf { it.channelId == state.channel.id }?.extras?.trailerKey
                            if (trailerKey != null && onTrailer != null) {
                                TvActionButton(
                                    text = stringResource(R.string.dial_details_trailer),
                                    icon = Icons.Rounded.Theaters,
                                    onClick = { onTrailer(trailerKey) },
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
            if (currentExtras?.traktItem != null) {
                item(key = "trakt") {
                    TraktPanel(
                        state = currentExtras,
                        isFilm = state.kind == DetailsKind.Film,
                        onRate = onTraktRate,
                        onComment = onTraktComment,
                        onWatched = onTraktWatched,
                    )
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
                    // Watched marks: hide them, or mark the season in one go.
                    val seasonIds = season?.episodes?.map { it.id }.orEmpty()
                    val seasonDone = seasonIds.isNotEmpty() && seasonIds.all { it in state.watched }
                    item(key = "watched-tools") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup()) {
                            TvActionButton(
                                text = stringResource(if (seasonDone) R.string.dial_details_season_unwatched else R.string.dial_details_season_watched),
                                icon = Icons.Rounded.DoneAll,
                                onClick = { onSetWatched(seasonIds, !seasonDone) },
                            )
                            TvActionButton(
                                text = stringResource(R.string.dial_details_hide_watched),
                                icon = if (hideWatched) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                checked = hideWatched,
                                onClick = onToggleHideWatched,
                            )
                            Text(
                                text = stringResource(R.string.dial_details_watched_hint),
                                color = TvColors.TextMuted,
                                fontFamily = TvFonts.Body,
                                fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                    }
                    val shownEpisodes = season?.episodes.orEmpty()
                        .filter { !hideWatched || it.id !in state.watched || it.id in markedHere }
                    items(shownEpisodes, key = { "episode-${it.id}" }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            lastWatched = episode.id == state.seriesProgress?.episodeId,
                            onClick = { onPlayEpisode(episode) },
                            progress = state.episodeProgress[episode.id],
                            watched = episode.id in state.watched,
                            onToggleWatched = {
                                // Stays on the page until it's reopened, so focus isn't lost.
                                markedHere = markedHere + episode.id
                                onSetWatched(listOf(episode.id), episode.id !in state.watched)
                            },
                        )
                    }
                    if (hideWatched && shownEpisodes.isEmpty() && seasonIds.isNotEmpty()) {
                        item(key = "all-watched") { Credit(stringResource(R.string.dial_details_all_watched)) }
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
private fun MetaLine(year: String?, rating: String?, duration: String?, genre: String?, certification: String? = null) {
    val parts = listOfNotNull(
        year,
        certification,
        rating?.let { stringResource(R.string.dial_details_rating, it) },
        duration,
        genre,
    )
    if (parts.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEach { InfoPill(text = it, minHeight = 32.dp) }
    }
}

/** One pill per source: TMDB 7.8 · Trakt 82% · IMDb 8.1 · RT 94% ... */
@Composable
private fun RatingsRow(ratings: List<RatingBadge>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ratings.forEach { badge ->
            val label = stringResource(
                when (badge.source) {
                    RatingSource.Tmdb -> R.string.dial_rating_tmdb
                    RatingSource.Trakt -> R.string.dial_rating_trakt
                    RatingSource.Imdb -> R.string.dial_rating_imdb
                    RatingSource.RottenTomatoes -> R.string.dial_rating_rt
                    RatingSource.Metacritic -> R.string.dial_rating_metacritic
                    RatingSource.Letterboxd -> R.string.dial_rating_letterboxd
                    RatingSource.Audience -> R.string.dial_rating_audience
                }
            )
            InfoPill(text = "$label ${badge.value}", minHeight = 32.dp)
        }
    }
}

/**
 * Rate, comment and mark watched on Trakt. Rating is a stepper (Left/Right pick 1–10, OK saves);
 * the comment box opens on demand and posts with its button.
 */
@Composable
private fun TraktPanel(
    state: DetailsExtrasState,
    isFilm: Boolean,
    onRate: (Int) -> Unit,
    onComment: (String, Boolean) -> Unit,
    onWatched: () -> Unit,
) {
    val rateFocus = remember { FocusRequester() }
    val commentFocus = remember { FocusRequester() }
    var ratingOpen by remember(state.channelId) { mutableStateOf(false) }
    var draftRating by remember(state.channelId, state.myRating) { mutableStateOf(state.myRating ?: 7) }
    var commentOpen by remember(state.channelId) { mutableStateOf(false) }
    var comment by remember(state.channelId) { mutableStateOf("") }
    var spoiler by remember(state.channelId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.dial_trakt_title),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        if (!state.traktSignedIn) {
            Credit(stringResource(R.string.dial_trakt_sign_in_hint))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = state.myRating?.let { stringResource(R.string.dial_trakt_rated, it) }
                    ?: stringResource(R.string.dial_trakt_rate),
                icon = Icons.Rounded.Star,
                selected = ratingOpen,
                focusRequester = rateFocus,
                onClick = { ratingOpen = !ratingOpen },
            )
            TvActionButton(
                text = stringResource(R.string.dial_trakt_comment),
                icon = Icons.Rounded.ChatBubble,
                selected = commentOpen,
                focusRequester = commentFocus,
                onClick = { commentOpen = !commentOpen },
            )
            if (isFilm) {
                TvActionButton(
                    text = stringResource(R.string.dial_trakt_watched),
                    icon = Icons.Rounded.Check,
                    enabled = !state.busy,
                    onClick = onWatched,
                )
            }
        }
        if (ratingOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FocusFrame(
                    onClick = {
                        onRate(draftRating)
                        runCatching { rateFocus.requestFocus() }
                        ratingOpen = false
                    },
                    shape = RoundedCornerShape(12.dp),
                    semanticsLabel = stringResource(R.string.dial_trakt_rating_value, draftRating),
                    onKey = { event -> stepperKeys(event) { delta -> draftRating = (draftRating + delta).coerceIn(1, 10) } },
                ) { focused ->
                    Text(
                        text = "\u2605".repeat(draftRating) + "\u2606".repeat(10 - draftRating) + "  $draftRating / 10",
                        color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
                Credit(stringResource(R.string.dial_trakt_rating_hint))
                if (state.myRating != null) {
                    TvActionButton(
                        text = stringResource(R.string.dial_trakt_unrate),
                        icon = Icons.Rounded.Close,
                        onClick = {
                            onRate(0)
                            runCatching { rateFocus.requestFocus() }
                            ratingOpen = false
                        },
                    )
                }
            }
        }
        if (commentOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 820.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_trakt_comment_label),
                    value = comment,
                    onValueChange = { comment = it.take(MAX_COMMENT) },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    placeholder = stringResource(R.string.dial_trakt_comment_placeholder),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        text = stringResource(R.string.dial_trakt_post),
                        icon = Icons.Rounded.Send,
                        enabled = !state.busy && comment.isNotBlank(),
                        onClick = {
                            onComment(comment, spoiler)
                            runCatching { commentFocus.requestFocus() }
                            commentOpen = false
                            comment = ""
                        },
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_trakt_spoiler),
                        icon = Icons.Rounded.VisibilityOff,
                        checked = spoiler,
                        onClick = { spoiler = !spoiler },
                    )
                }
            }
        }
        state.notice?.let { notice ->
            Credit(
                stringResource(
                    when (notice) {
                        TraktNotice.Rated -> R.string.dial_trakt_notice_rated
                        TraktNotice.RatingRemoved -> R.string.dial_trakt_notice_unrated
                        TraktNotice.Commented -> R.string.dial_trakt_notice_commented
                        TraktNotice.CommentTooShort -> R.string.dial_trakt_notice_short
                        TraktNotice.Watched -> R.string.dial_trakt_notice_watched
                        TraktNotice.Failed -> R.string.dial_trakt_notice_failed
                    }
                )
            )
        }
    }
}

private const val MAX_COMMENT = 1_000

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
    progress: Float? = null,
    watched: Boolean = false,
    onToggleWatched: (() -> Unit)? = null,
) {
    FocusFrame(
        onClick = onClick,
        onLongClick = onToggleWatched,
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
            if (watched) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.dial_details_watched),
                    tint = if (focused) TvColors.OnFocus else TvColors.Positive,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                episode.duration?.let {
                    Text(
                        text = it,
                        color = secondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                    )
                }
                // How far in playback got, as a short bar.
                if (progress != null && !watched) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (focused) TvColors.OnFocus.copy(alpha = 0.25f) else TvColors.TextMuted.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(if (focused) TvColors.OnFocus else TvColors.Focus),
                        )
                    }
                }
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
