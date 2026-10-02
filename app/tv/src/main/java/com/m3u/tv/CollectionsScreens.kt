package com.m3u.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * Favourites (live channels, in your own groups) and My Library (saved films and series, and what
 * you're partway through).
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun FavouritesScreen(
    state: TvUiState,
    groups: List<FavouriteGroup>,
    groupChannels: Map<String, List<Channel>>,
    onPlay: (Channel) -> Unit,
    onMoveGroup: (String, Int) -> Unit,
    onDeleteGroup: (String) -> Unit,
) {
    val onDemandUrls = remember(state.playlists) {
        state.playlists.filter { it.isVod || it.isSeries }.map { it.url }.toSet()
    }
    val liveFavourites = remember(state.favorites, onDemandUrls) {
        state.favorites.filterNot { it.playlistUrl in onDemandUrls }
    }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.id == selectedGroup }
    val channels = if (group == null) liveFavourites else groupChannels[group.id].orEmpty()
    var groupMenu by remember { mutableStateOf<FavouriteGroup?>(null) }
    val firstChip = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        yield()
        runCatching { firstChip.requestFocus() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 24.dp)
            .focusGroup()
    ) {
        SectionTitle(
            title = stringResource(R.string.dial_favourites_title),
            subtitle = stringResource(R.string.dial_favourites_subtitle),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusGroup(),
        ) {
            item(key = "all") {
                TvActionButton(
                    text = stringResource(R.string.dial_favourites_all, liveFavourites.size),
                    icon = Icons.Rounded.Favorite,
                    selected = group == null,
                    focusRequester = firstChip,
                    onClick = { selectedGroup = null },
                )
            }
            items(groups, key = { it.id }) { item ->
                TvActionButton(
                    text = stringResource(
                        R.string.dial_category_chip,
                        item.name,
                        groupChannels[item.id]?.size ?: item.channelIds.size,
                    ),
                    icon = Icons.Rounded.Folder,
                    selected = item.id == selectedGroup,
                    onClick = { selectedGroup = item.id },
                    onLongClick = { groupMenu = item },
                )
            }
        }
        if (channels.isEmpty()) {
            Text(
                text = stringResource(
                    if (group == null) R.string.dial_favourites_empty else R.string.dial_favourites_group_empty
                ),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        } else {
            ChannelGrid(channels = channels, onPlay = onPlay)
        }
    }

    groupMenu?.let { menuGroup ->
        val index = groups.indexOfFirst { it.id == menuGroup.id }
        MenuPanel(
            title = menuGroup.name,
            subtitle = stringResource(R.string.dial_menu_group_subtitle),
            entries = buildList {
                if (index > 0) {
                    add(
                        MenuEntry(
                            stringResource(R.string.dial_menu_category_earlier),
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            { onMoveGroup(menuGroup.id, -1) },
                        )
                    )
                }
                if (index in 0 until groups.lastIndex) {
                    add(
                        MenuEntry(
                            stringResource(R.string.dial_menu_category_later),
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            { onMoveGroup(menuGroup.id, 1) },
                        )
                    )
                }
                add(
                    MenuEntry(
                        stringResource(R.string.dial_menu_delete_group),
                        Icons.Rounded.Delete,
                        {
                            groupMenu = null
                            if (selectedGroup == menuGroup.id) selectedGroup = null
                            onDeleteGroup(menuGroup.id)
                        },
                    )
                )
            },
            onDismiss = { groupMenu = null },
        )
    }
}

@Composable
fun MyLibraryScreen(
    state: TvUiState,
    continueWatching: List<Channel>,
    onPlay: (Channel) -> Unit,
) {
    val vodUrls = remember(state.playlists) { state.playlists.filter { it.isVod }.map { it.url }.toSet() }
    val seriesUrls = remember(state.playlists) { state.playlists.filter { it.isSeries }.map { it.url }.toSet() }
    val films = remember(state.favorites, vodUrls) { state.favorites.filter { it.playlistUrl in vodUrls } }
    val series = remember(state.favorites, seriesUrls) { state.favorites.filter { it.playlistUrl in seriesUrls } }
    val firstItem = remember { FocusRequester() }

    LaunchedEffect(continueWatching.isNotEmpty(), films.isNotEmpty(), series.isNotEmpty()) {
        yield()
        runCatching { firstItem.requestFocus() }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        item {
            SectionTitle(
                title = stringResource(R.string.dial_nav_my_library),
                subtitle = stringResource(R.string.dial_my_library_subtitle),
                modifier = Modifier.padding(start = 48.dp, end = 64.dp),
            )
        }
        val rows = listOf(
            R.string.dial_continue_title to continueWatching,
            R.string.dial_my_library_films to films,
            R.string.dial_my_library_series to series,
        ).filter { it.second.isNotEmpty() }
        if (rows.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.dial_my_library_empty),
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(start = 48.dp)
                        .widthIn(max = 720.dp),
                )
            }
        }
        rows.forEachIndexed { index, (title, channels) ->
            item(key = "row-$title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(title),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                    ContentRow(
                        channels = channels,
                        onPlay = onPlay,
                        firstItemFocusRequester = firstItem.takeIf { index == 0 },
                    )
                }
            }
        }
    }
}

