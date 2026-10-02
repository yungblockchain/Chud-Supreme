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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * An actor or director: photo, biography, what they're known for (titles in your playlists
 * open straight away) and a QR code to their IMDb page.
 */
@Composable
fun PersonScreen(
    state: PersonState,
    onOpenTitle: (TmdbTitle) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val firstTitle = remember { FocusRequester() }
    val person = state.person
    LaunchedEffect(person?.id) {
        for (attempt in 0 until FOCUS_TRIES) {
            yield()
            if (runCatching { firstTitle.requestFocus() }.isSuccess) break
            delay(FOCUS_RETRY_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(start = 64.dp, top = 48.dp, end = 64.dp, bottom = 48.dp),
            modifier = Modifier
                .fillMaxSize()
                // The details page underneath stays put until Back.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
        ) {
            item(key = "header") {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    AsyncImage(
                        model = person?.photo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(TvColors.SurfaceRaised),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = person?.name ?: stringResource(R.string.dial_details_loading),
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 34.sp,
                        )
                        val born = listOfNotNull(person?.born, person?.birthplace).joinToString(" · ")
                        if (born.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dial_person_born, born),
                                color = TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 16.sp,
                            )
                        }
                        person?.biography?.let {
                            Text(
                                text = it,
                                color = TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 820.dp),
                            )
                        }
                        if (!state.loading && person == null) {
                            Text(
                                text = stringResource(R.string.dial_details_unavailable),
                                color = TvColors.TextMuted,
                                fontFamily = TvFonts.Body,
                                fontSize = 15.sp,
                            )
                        }
                    }
                    person?.imdbId?.let { ImdbLink(url = "https://www.imdb.com/name/$it/") }
                }
            }
            val titles = person?.knownFor.orEmpty()
            if (titles.isNotEmpty()) {
                item(key = "known-for") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.dial_person_known_for),
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.focusGroup(),
                        ) {
                            itemsIndexed(titles, key = { _, title -> "${title.kind}-${title.id}" }) { index, title ->
                                TitlePoster(
                                    title = title,
                                    onClick = { onOpenTitle(title) },
                                    focusRequester = firstTitle.takeIf { index == 0 },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A TMDB title as a poster card (trending row, filmographies). */
@Composable
fun TitlePoster(
    title: TmdbTitle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    FocusFrame(
        onClick = onClick,
        onFocus = onFocused,
        shape = RoundedCornerShape(12.dp),
        focusRequester = focusRequester,
        semanticsLabel = listOfNotNull(title.title, title.year, badge).joinToString(", "),
        modifier = modifier.width(136.dp),
    ) { focused ->
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(8.dp),
        ) {
            Box {
                PosterArt(
                    model = title.poster,
                    modifier = Modifier
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                badge?.let {
                    Text(
                        text = it,
                        color = TvColors.OnFocus,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(TvColors.Positive, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = title.title,
                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            title.year?.let {
                Text(
                    text = it,
                    color = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private const val FOCUS_TRIES = 10
private const val FOCUS_RETRY_MS = 32L
