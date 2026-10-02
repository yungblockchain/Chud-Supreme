package com.m3u.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * "Trending this week" from TMDB, turning slowly like a carousel until the remote reaches it.
 * Titles that are in the person's playlists carry a badge and open their details page.
 */
@Composable
fun TrendingRow(
    entries: List<TrendingEntry>,
    onOpen: (TrendingEntry) -> Unit,
    onFocused: (TrendingEntry) -> Unit = {},
) {
    if (entries.isEmpty()) return
    val listState = rememberLazyListState()
    var focusedInside by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size, focusedInside) {
        if (focusedInside) return@LaunchedEffect
        while (true) {
            delay(CAROUSEL_STEP_MS)
            val next = (listState.firstVisibleItemIndex + 1).let { if (it >= entries.size - 3) 0 else it }
            listState.animateScrollToItem(next)
        }
    }
    val inPlaylists = stringResource(R.string.dial_trending_in_playlists)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            title = stringResource(R.string.dial_trending_title),
            subtitle = stringResource(R.string.dial_trending_subtitle),
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 48.dp, top = 8.dp, end = 48.dp, bottom = 8.dp),
            modifier = Modifier
                .focusGroup()
                .onFocusChanged { focusedInside = it.hasFocus },
        ) {
            items(entries, key = { "${it.title.kind}-${it.title.id}" }) { entry ->
                TitlePoster(
                    title = entry.title,
                    badge = inPlaylists.takeIf { entry.channel != null },
                    onClick = { onOpen(entry) },
                    onFocused = { onFocused(entry) },
                )
            }
        }
    }
}

private const val CAROUSEL_STEP_MS = 4_000L
