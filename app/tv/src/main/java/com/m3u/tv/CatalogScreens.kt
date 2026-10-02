package com.m3u.tv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ChannelCategoryCount
import com.m3u.data.database.model.Playlist
import java.text.NumberFormat
import kotlinx.coroutines.delay

/* -------------------------------------------------------------------------------------------------
 * Live TV, Films and Series: one screen each, laid out the same way. Categories run down the left;
 * the chosen category's channels or posters fill the rest as a single grid, so the remote only
 * ever scrolls one list at a time. Moving through the categories shows each one after a short
 * pause, OK on a category jumps into it, and Left from the grid goes back to the categories.
 * ---------------------------------------------------------------------------------------------- */

val CatalogKind.icon: ImageVector
    get() = when (this) {
        CatalogKind.Live -> Icons.Rounded.LiveTv
        CatalogKind.Films -> Icons.Rounded.Movie
        CatalogKind.Series -> Icons.Rounded.VideoLibrary
    }

@Composable
fun CatalogKind.title(): String = stringResource(
    when (this) {
        CatalogKind.Live -> R.string.dial_nav_live
        CatalogKind.Films -> R.string.dial_nav_films
        CatalogKind.Series -> R.string.dial_nav_series
    }
)

/** "1,613 films", "1 channel". */
@Composable
fun CatalogKind.countText(count: Int): String = pluralStringResource(
    when (this) {
        CatalogKind.Live -> R.plurals.dial_catalog_count_live
        CatalogKind.Films -> R.plurals.dial_catalog_count_films
        CatalogKind.Series -> R.plurals.dial_catalog_count_series
    },
    count,
    NumberFormat.getIntegerInstance().format(count),
)

/** A category picked in the list; a null name is "All". */
@Immutable
private data class CategoryPick(val name: String?)

@Composable
fun CatalogScreen(
    kind: CatalogKind,
    state: TvUiState,
    onOpenCatalog: (CatalogKind) -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Channel) -> Unit,
    onAddSource: () -> Unit,
) {
    val sources = remember(state.playlists, kind) { state.playlists.filter { it.catalogKind == kind } }
    val selected = state.selectedPlaylist?.takeIf { playlist ->
        playlist.catalogKind == kind && sources.any { it.url == playlist.url }
    }
    // The shared selection may belong to another tab (or the guide): switch to this tab's source,
    // unless this tab is the one fading out.
    val active = LocalTvTabActive.current
    LaunchedEffect(kind, selected == null, sources.size, active) {
        if (active && selected == null && sources.isNotEmpty()) onOpenCatalog(kind)
    }
    if (sources.isEmpty()) {
        EmptyCatalog(kind = kind, onAddSource = onAddSource)
        return
    }

    val categories = if (selected != null) state.categories else emptyList()
    val channels = if (selected != null) state.channels else emptyList()
    val loading = selected == null || state.loadingChannels
    val total = remember(categories) { categories.sumOf { it.count } }
    val shownCount = state.selectedCategory
        ?.let { name -> categories.firstOrNull { it.name == name }?.count }
        ?: total
    val uncategorised = stringResource(R.string.dial_category_uncategorised)
    val allLabel = stringResource(R.string.dial_catalog_all)
    val kindTitle = kind.title()
    val categoryLabel = state.selectedCategory?.ifBlank { uncategorised } ?: allLabel

    // One focus requester per category row, kept for the row's lifetime (never handed from row to
    // row), so "focus the category being shown" can find it.
    val categoryFocus = remember { HashMap<CategoryPick, FocusRequester>() }
    val firstItemFocus = remember { FocusRequester() }
    val categoryListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var screenHasFocus by remember { mutableStateOf(false) }
    var initialFocusDone by remember { mutableStateOf(false) }
    // The category OK was pressed on: focus goes into its grid once that category has loaded.
    var enterTarget by remember { mutableStateOf<CategoryPick?>(null) }
    var preview by remember { mutableStateOf<CategoryPick?>(null) }

    // Moving through the list: show the category the remote rests on.
    LaunchedEffect(preview) {
        val pick = preview ?: return@LaunchedEffect
        delay(CATEGORY_PREVIEW_DELAY_MS)
        onSelectCategory(pick.name)
    }
    // A different category starts at the top of its grid.
    LaunchedEffect(selected?.url, state.selectedCategory) {
        if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0)
    }
    // Opening the tab: focus goes to the category being shown (unless the remote got there first).
    LaunchedEffect(categories.isNotEmpty()) {
        if (initialFocusDone || categories.isEmpty()) return@LaunchedEffect
        initialFocusDone = true
        if (screenHasFocus) return@LaunchedEffect
        val index = state.selectedCategory
            ?.let { name -> categories.indexOfFirst { it.name == name } }
            ?.takeIf { it >= 0 }
            ?.let { it + if (state.allCategoriesAllowed) 1 else 0 }
            ?: 0
        categoryListState.scrollToItem((index - 2).coerceAtLeast(0))
        withFrameNanos { }
        categoryFocus[CategoryPick(state.selectedCategory)]?.let { requester ->
            runCatching { requester.requestFocus() }
        }
    }
    // OK on a category: into its first item once it has loaded (nothing, if it's empty).
    LaunchedEffect(enterTarget, loading, channels, state.selectedCategory) {
        val target = enterTarget ?: return@LaunchedEffect
        if (loading || state.selectedCategory != target.name) return@LaunchedEffect
        enterTarget = null
        if (channels.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(0)
        withFrameNanos { }
        runCatching { firstItemFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .padding(start = 32.dp, top = 28.dp, end = 32.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = kindTitle,
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    maxLines = 1,
                )
                Text(
                    text = if (categories.isEmpty()) {
                        stringResource(R.string.dial_catalog_loading)
                    } else {
                        stringResource(R.string.dial_catalog_subtitle, categoryLabel, kind.countText(shownCount))
                    },
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (sources.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .focusGroup()
                ) {
                    items(sources, key = { "source-${it.url}" }) { source ->
                        TvActionButton(
                            text = source.title.ifBlank { kindTitle },
                            icon = kind.icon,
                            selected = source.url == selected?.url,
                            onClick = { onSelectPlaylist(source) },
                        )
                    }
                }
            }
            TvActionButton(
                text = stringResource(R.string.dial_catalog_update),
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            CategoryList(
                categories = categories,
                allowAll = state.allCategoriesAllowed,
                total = total,
                selectedCategory = state.selectedCategory,
                listState = categoryListState,
                focusRequesters = categoryFocus,
                onPreview = { pick ->
                    preview = pick
                    // Moving on to another category cancels a pending jump into the grid.
                    if (enterTarget != null && enterTarget != pick) enterTarget = null
                },
                onOpen = { pick ->
                    preview = null
                    onSelectCategory(pick.name)
                    enterTarget = pick
                },
                modifier = Modifier
                    .width(CATEGORY_LIST_WIDTH_DP.dp)
                    .fillMaxHeight()
            )
            Spacer(Modifier.width(20.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when {
                    channels.isEmpty() && loading -> CatalogNote(stringResource(R.string.dial_catalog_loading))
                    channels.isEmpty() -> CatalogNote(stringResource(R.string.dial_catalog_nothing))
                    else -> CatalogGrid(
                        kind = kind,
                        channels = channels,
                        gridState = gridState,
                        firstItemFocus = firstItemFocus,
                        onPlay = onPlay,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    categories: List<ChannelCategoryCount>,
    allowAll: Boolean,
    total: Int,
    selectedCategory: String?,
    listState: LazyListState,
    focusRequesters: MutableMap<CategoryPick, FocusRequester>,
    onPreview: (CategoryPick) -> Unit,
    onOpen: (CategoryPick) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uncategorised = stringResource(R.string.dial_category_uncategorised)
    val allLabel = stringResource(R.string.dial_catalog_all)
    val categoryMenu = LocalCategoryMenu.current
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        // Room for the focused row to grow and glow without being clipped.
        contentPadding = PaddingValues(start = 6.dp, top = 6.dp, end = 10.dp, bottom = 32.dp),
        modifier = modifier
            .focusRestorer()
            .focusGroup()
    ) {
        if (allowAll && categories.isNotEmpty()) {
            item(key = "all") {
                val pick = CategoryPick(null)
                CategoryRow(
                    label = allLabel,
                    count = total,
                    selected = selectedCategory == null,
                    focusRequester = focusRequesters.getOrPut(pick) { FocusRequester() },
                    onFocus = { onPreview(pick) },
                    onClick = { onOpen(pick) },
                    onLongClick = null,
                )
            }
        }
        items(categories, key = { "category-${it.name}" }) { category ->
            val pick = CategoryPick(category.name)
            val isSelected = category.name == selectedCategory
            val requester = focusRequesters.getOrPut(pick) { FocusRequester() }
            CategoryRow(
                label = category.name.ifBlank { uncategorised },
                count = category.count,
                selected = isSelected,
                focusRequester = requester,
                onFocus = { onPreview(pick) },
                onClick = { onOpen(pick) },
                onLongClick = categoryMenu?.let { menu -> { menu(category.name, requester) } },
            )
        }
    }
}

@Composable
private fun CategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val countText = remember(count) { NumberFormat.getIntegerInstance().format(count) }
    // The category being shown is marked inside the row (a tint and a bar on its edge), so the
    // frame itself only ever changes for focus.
    FocusFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        selectionState = selected,
        focusRequester = focusRequester,
        focusedScale = 1.03f,
        focusedBorderWidth = 3.dp,
        semanticRole = Role.Tab,
        semanticsLabel = "$label, $countText",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
    ) { focused ->
        // Always there and only redrawn (not added and removed): Android 9 kept showing a removed
        // mark on screen.
        val marked = selected && !focused
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    if (marked) {
                        drawRect(TvColors.Focus.copy(alpha = SELECTED_TINT_ALPHA))
                        drawRect(TvColors.Focus, size = Size(SELECTED_BAR_WIDTH.toPx(), size.height))
                    }
                }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = label,
                color = when {
                    focused -> TvColors.OnFocus
                    selected -> TvColors.Focus
                    else -> TvColors.TextPrimary
                },
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = countText,
                color = if (focused) TvColors.OnFocus.copy(alpha = 0.72f) else TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CatalogGrid(
    kind: CatalogKind,
    channels: List<Channel>,
    gridState: LazyGridState,
    firstItemFocus: FocusRequester,
    onPlay: (Channel) -> Unit,
) {
    val poster = kind != CatalogKind.Live
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (poster) POSTER_MIN_WIDTH_DP.dp else LOGO_MIN_WIDTH_DP.dp),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        itemsIndexed(
            channels,
            key = { _, channel -> channel.id },
            contentType = { _, _ -> kind },
        ) { index, channel ->
            CatalogTile(
                channel = channel,
                poster = poster,
                onPlay = { onPlay(channel) },
                focusRequester = firstItemFocus.takeIf { index == 0 },
            )
        }
    }
}

/**
 * A poster (films, series) or a logo (live channels) with the title underneath. Focus grows the
 * picture and rings it; holding OK opens the item's menu.
 */
@Composable
fun CatalogTile(
    channel: Channel,
    poster: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    val openMenu = LocalChannelMenu.current
    val requester = focusRequester ?: remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.onFocusChanged { focused = it.hasFocus }
    ) {
        FocusFrame(
            onClick = onPlay,
            onLongClick = openMenu?.let { menu -> { menu(channel, requester) } },
            onFocus = onFocused,
            focusRequester = requester,
            focusedScale = 1.06f,
            focusedFill = TvColors.SurfaceRaised,
            semanticsLabel = channel.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 10f)
        ) {
            PosterArt(
                model = channel.cover,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = channel.title.title(),
            color = if (focused) TvColors.Focus else TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun CatalogNote(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = text,
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyCatalog(kind: CatalogKind, onAddSource: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = TvColors.Focus,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = stringResource(
                when (kind) {
                    CatalogKind.Live -> R.string.dial_catalog_empty_live
                    CatalogKind.Films -> R.string.dial_catalog_empty_films
                    CatalogKind.Series -> R.string.dial_catalog_empty_series
                }
            ),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.dial_catalog_empty_hint),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp)
        )
        TvActionButton(
            text = stringResource(R.string.dial_catalog_add_source),
            icon = Icons.Rounded.AccountCircle,
            onClick = onAddSource,
        )
    }
}

/* -------------------------------------------------------------------------------------------------
 * Search: one box, and what matches in live TV, films and series as three rows.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun SearchScreen(
    state: TvUiState,
    onSearch: (String) -> Unit,
    onPlay: (Channel) -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }
    val query = state.searchQuery.trim()
    val active = query.length >= SEARCH_MIN_CHARS
    val groups = remember(state.searchResults, state.playlists) {
        val kinds = state.playlists.associate { it.url to it.catalogKind }
        state.searchResults.groupBy { kinds[it.playlistUrl] ?: CatalogKind.Live }
    }
    // Down from the box goes to the first result.
    val firstResultFocus = remember { FocusRequester() }
    val firstKind = CatalogKind.entries.firstOrNull { groups[it].orEmpty().isNotEmpty() }
        .takeIf { active }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(start = 32.dp, top = 28.dp, end = 32.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        item(key = "field") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dial_nav_search),
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                )
                Box(Modifier.widthIn(max = 720.dp)) {
                    DialTextField(
                        label = stringResource(R.string.dial_search_hint),
                        value = state.searchQuery,
                        onValueChange = onSearch,
                        placeholder = stringResource(R.string.dial_library_search_placeholder),
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        readOnly = false,
                        focusRequester = fieldFocus,
                        downFocus = firstResultFocus.takeIf { firstKind != null },
                    )
                }
            }
        }
        item(key = "status") {
            Text(
                text = when {
                    !active -> stringResource(R.string.dial_search_start)
                    state.searching -> stringResource(R.string.dial_library_searching)
                    state.searchResults.isEmpty() -> stringResource(R.string.dial_library_search_none, query)
                    else -> stringResource(R.string.dial_library_search_results, query)
                },
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                maxLines = 2,
            )
        }
        if (active) {
            CatalogKind.entries.forEach { kind ->
                val matches = groups[kind].orEmpty()
                if (matches.isNotEmpty()) {
                    item(key = "results-${kind.name}") {
                        SearchResultRow(
                            kind = kind,
                            channels = matches,
                            onPlay = onPlay,
                            firstItemFocus = firstResultFocus.takeIf { kind == firstKind },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    kind: CatalogKind,
    channels: List<Channel>,
    onPlay: (Channel) -> Unit,
    firstItemFocus: FocusRequester? = null,
) {
    val poster = kind != CatalogKind.Live
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.dial_catalog_subtitle, kind.title(), kind.countText(channels.size)),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier
                .focusRestorer()
                .focusGroup()
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                CatalogTile(
                    channel = channel,
                    poster = poster,
                    onPlay = { onPlay(channel) },
                    focusRequester = firstItemFocus.takeIf { index == 0 },
                    modifier = Modifier.width(if (poster) SEARCH_POSTER_WIDTH_DP.dp else SEARCH_LOGO_WIDTH_DP.dp)
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Home: big doors into Live TV, Films and Series.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun CatalogDoors(
    state: TvUiState,
    onOpen: (CatalogKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = remember(state.counts) {
        CatalogKind.entries.associateWith { kind ->
            state.counts.entries.filter { it.key.catalogKind == kind }.sumOf { it.value }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
    ) {
        CatalogKind.entries.forEach { kind ->
            FocusFrame(
                onClick = { onOpen(kind) },
                focusedScale = 1.04f,
                semanticsLabel = kind.title(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 104.dp)
            ) { focused ->
                val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
                val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextSecondary
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = kind.icon,
                        contentDescription = null,
                        tint = if (focused) TvColors.OnFocus else TvColors.Focus,
                        modifier = Modifier.size(40.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = kind.title(),
                            color = primary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            maxLines = 1,
                        )
                        Text(
                            text = kind.countText(counts[kind] ?: 0),
                            color = secondary,
                            fontFamily = TvFonts.Body,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private const val CATEGORY_PREVIEW_DELAY_MS = 450L
private const val SELECTED_TINT_ALPHA = 0.16f
private val SELECTED_BAR_WIDTH = 4.dp
private const val CATEGORY_LIST_WIDTH_DP = 260
private const val POSTER_MIN_WIDTH_DP = 118
private const val LOGO_MIN_WIDTH_DP = 150
private const val SEARCH_POSTER_WIDTH_DP = 128
private const val SEARCH_LOGO_WIDTH_DP = 168
private const val SEARCH_MIN_CHARS = 2
