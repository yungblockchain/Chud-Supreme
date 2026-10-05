package com.m3u.tv.stremio

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.tv.DialTextField
import com.m3u.tv.FocusFrame
import com.m3u.tv.R
import com.m3u.tv.SettingRow
import com.m3u.tv.SettingsSection
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvFonts
import com.m3u.tv.stepperKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * Infinity, laid out like a streaming service: a hero that follows the focused title, then rows
 * (continue watching, library, up next, calendar, and each addon catalog) in the order the person
 * chose, with poster or landscape cards. Rows can be renamed, moved and hidden on the Rows page.
 * ---------------------------------------------------------------------------------------------- */

private const val HERO_FOLLOW_MS = 350L

/** A built-in shelf shown as a row. */
private data class Shelf(val id: String, val items: List<CatalogItem>, val style: CardStyle, val continueRow: Boolean = false)

@Composable
internal fun InfinityBrowsePage(
    state: StremioUiState,
    viewModel: StremioViewModel,
    onManageAddons: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val layout = state.layout
    var focusedItem by remember { mutableStateOf<CatalogItem?>(null) }
    var heroItem by remember { mutableStateOf<CatalogItem?>(null) }
    // Pages swap inside one tab, so each puts focus somewhere sensible when it appears.
    LaunchedEffect(state.page) {
        yield()
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(focusedItem) {
        val next = focusedItem ?: return@LaunchedEffect
        delay(HERO_FOLLOW_MS)
        heroItem = next
    }
    val shelves = when (state.shelf) {
        "library" -> listOf(Shelf("library", state.library, layout.cardStyle))
        "upnext" -> listOf(Shelf("upnext", state.upNext, layout.cardStyle))
        "calendar" -> listOf(Shelf("calendar", state.calendar, layout.cardStyle))
        else -> listOf(
            Shelf("continue", state.continueWatching, layout.continueStyle, continueRow = true),
            Shelf("library", state.library.take(16), layout.cardStyle),
            Shelf("upnext", state.upNext, layout.cardStyle),
            Shelf("calendar", state.calendar, layout.cardStyle),
        )
    }.filter { it.items.isNotEmpty() }
    val catalogRows = if (state.shelf == "home") layout.arrange(state.rows) else emptyList()
    val hero = heroItem ?: state.continueWatching.firstOrNull() ?: shelves.firstOrNull()?.items?.firstOrNull()
        ?: catalogRows.firstOrNull()?.items?.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        if (layout.hero) {
            InfinityHeroBackdrop(hero)
        }
        LazyColumn(
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) {
            item(key = "bar") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 48.dp).focusGroup(),
                ) {
                    Text(
                        text = stringResource(R.string.dial_nav_infinite),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Accent,
                        fontSize = 28.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    listOf(
                        "home" to R.string.dial_infinity_shelf_home,
                        "library" to R.string.dial_infinity_shelf_library,
                        "upnext" to R.string.dial_infinity_shelf_upnext,
                        "calendar" to R.string.dial_infinity_shelf_calendar,
                    ).forEachIndexed { index, (id, label) ->
                        TvActionButton(
                            text = stringResource(label),
                            icon = Icons.Rounded.PlayArrow,
                            selected = state.shelf == id,
                            showTextWhenUnfocused = state.shelf == id,
                            focusRequester = first.takeIf { index == 0 },
                            onClick = { viewModel.setShelf(id) },
                        )
                    }
                    TvActionButton(
                        text = stringResource(R.string.dial_addons_search),
                        icon = Icons.Rounded.Search,
                        selected = searchOpen,
                        showTextWhenUnfocused = false,
                        onClick = { searchOpen = !searchOpen },
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_infinity_rows),
                        icon = Icons.Rounded.Tune,
                        showTextWhenUnfocused = false,
                        onClick = viewModel::openRows,
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_addons_manage),
                        icon = Icons.Rounded.Extension,
                        showTextWhenUnfocused = false,
                        onClick = onManageAddons,
                    )
                }
            }
            if (searchOpen) {
                item(key = "search") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 48.dp).widthIn(max = 820.dp),
                    ) {
                        DialTextField(
                            label = stringResource(R.string.dial_addons_search),
                            value = query,
                            onValueChange = { query = it },
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                            readOnly = false,
                            onDone = { viewModel.search(query) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(
                                "all" to R.string.dial_infinity_kind_all,
                                "movie" to R.string.dial_infinity_kind_films,
                                "series" to R.string.dial_infinity_kind_series,
                            ).forEach { (id, label) ->
                                TvActionButton(
                                    text = stringResource(label),
                                    icon = Icons.Rounded.Search,
                                    selected = state.searchKind == id,
                                    onClick = { viewModel.setSearchKind(id) },
                                )
                            }
                            TvActionButton(
                                text = stringResource(R.string.dial_addons_search_go),
                                icon = Icons.Rounded.Search,
                                onClick = { viewModel.search(query) },
                            )
                        }
                    }
                }
            }
            if (layout.hero && hero != null) {
                item(key = "hero") { InfinityHeroText(hero, state.traktUser) }
            }
            if (state.addons.none { it.enabled && it.catalogs.isNotEmpty() }) {
                item(key = "starter") {
                    Row(modifier = Modifier.padding(horizontal = 48.dp)) {
                        TvActionButton(
                            text = stringResource(R.string.dial_addons_starter),
                            icon = Icons.Rounded.PlayArrow,
                            onClick = viewModel::installStarter,
                        )
                    }
                }
            }
            state.message?.let { message -> item(key = "message") { InfinityStatus(message) } }
            if (state.loading) item(key = "loading") { InfinityStatus(stringResource(R.string.dial_addons_loading)) }
            if (!state.loading && shelves.isEmpty() && state.shelf != "home") {
                item(key = "empty-shelf") {
                    InfinityStatus(
                        stringResource(
                            when (state.shelf) {
                                "library" -> R.string.dial_infinity_empty_library
                                "upnext" -> R.string.dial_infinity_empty_upnext
                                else -> R.string.dial_infinity_empty_calendar
                            }
                        )
                    )
                }
            }
            items(shelves, key = { "shelf-${it.id}" }) { shelf ->
                InfinityRow(
                    title = stringResource(
                        when (shelf.id) {
                            "continue" -> R.string.dial_infinity_row_continue
                            "library" -> R.string.dial_infinity_shelf_library
                            "upnext" -> R.string.dial_infinity_shelf_upnext
                            else -> R.string.dial_infinity_shelf_calendar
                        }
                    ),
                    items = shelf.items,
                    style = shelf.style,
                    ratings = layout.ratingsOnCards,
                    onFocused = { focusedItem = it },
                    onOpen = { item ->
                        val episode = item.type == "series" && item.id.count { char -> char == ':' } >= 2
                        if (episode) viewModel.loadStreams("series", item.id, item.name) else viewModel.openItem(item)
                    },
                )
            }
            if (!state.loading && catalogRows.isEmpty() && state.shelf == "home" && state.addons.none { it.catalogs.isNotEmpty() }) {
                item(key = "empty") { InfinityStatus(stringResource(R.string.dial_addons_empty)) }
            }
            items(catalogRows, key = { "row-${it.key}" }) { row ->
                InfinityRow(
                    title = layout.nameOf(row),
                    items = row.items,
                    style = layout.cardStyle,
                    ratings = layout.ratingsOnCards,
                    onFocused = { focusedItem = it },
                    onOpen = { viewModel.openItem(it) },
                )
            }
        }
    }
}

/** The big picture behind everything: the focused title's backdrop, faded into the page. */
@Composable
private fun InfinityHeroBackdrop(item: CatalogItem?) {
    Crossfade(targetState = item?.background ?: item?.poster, label = "infinity-hero") { image ->
        Box(Modifier.fillMaxSize()) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopEnd,
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .fillMaxHeight(0.7f)
                        .align(Alignment.TopEnd)
                        .alpha(0.55f),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to TvColors.Background,
                            0.45f to TvColors.Background.copy(alpha = 0.85f),
                            1f to TvColors.Background.copy(alpha = 0.2f),
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to TvColors.Background.copy(alpha = 0.6f),
                            1f to TvColors.Background,
                        )
                    )
            )
        }
    }
}

/** The hero's words: name, year and rating, plot. Not focusable; the cards are. */
@Composable
private fun InfinityHeroText(item: CatalogItem, traktUser: String?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 48.dp)
            .widthIn(max = 720.dp),
    ) {
        Text(
            text = item.name.substringBefore(" · "),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 40.sp,
            lineHeight = 46.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            item.releaseInfo,
            item.imdbRating?.let { stringResource(R.string.dial_infinity_imdb, it) },
            if (item.type == "series") stringResource(R.string.dial_infinity_kind_series) else stringResource(R.string.dial_infinity_kind_films),
        ).joinToString("  ·  ")
        Text(text = meta, color = TvColors.Focus, fontFamily = TvFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        item.description?.takeIf { it.isNotBlank() }?.let { plot ->
            Text(
                text = plot,
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        traktUser?.let {
            Text(
                text = stringResource(R.string.dial_infinity_trakt_user, it),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun InfinityRow(
    title: String,
    items: List<CatalogItem>,
    style: CardStyle,
    ratings: Boolean,
    onFocused: (CatalogItem) -> Unit,
    onOpen: (CatalogItem) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 10.dp),
            modifier = Modifier.fillMaxWidth().focusGroup(),
        ) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                InfinityCard(item, style, ratings, onFocused = { onFocused(item) }, onClick = { onOpen(item) })
            }
        }
    }
}

/** A poster (2:3) or a landscape (16:9) card, with the name and a rating badge. */
@Composable
private fun InfinityCard(
    item: CatalogItem,
    style: CardStyle,
    ratings: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val landscape = style == CardStyle.Landscape || item.posterShape == "landscape"
    val width = if (landscape) 272.dp else 150.dp
    // "63%" at the end of a Trakt continue-watching name is how far through it the person is.
    val progress = Regex("(\\d{1,3})%$").find(item.name)?.groupValues?.get(1)?.toIntOrNull()
    val name = item.name.substringBeforeLast(" · ").takeIf { progress != null } ?: item.name
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusFrame(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            semanticsLabel = item.name,
            focusedScale = 1.04f,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) onFocused() },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.Surface),
            ) {
                AsyncImage(
                    model = if (landscape) item.background ?: item.poster else item.poster ?: item.background,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ratings && item.imdbRating != null) {
                    Text(
                        text = "★ ${item.imdbRating}",
                        color = Color.White,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                if (progress != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress / 100f)
                                .background(TvColors.Focus),
                        )
                    }
                }
            }
        }
        Text(
            text = name,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            maxLines = if (landscape) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun InfinityStatus(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 48.dp).widthIn(max = 860.dp),
    )
}

/* ------------------------------------------------------------------------------- rows page */

/** Rename, move and hide rows; pick card styles. OK toggles, Left/Right move, long names wrap. */
@Composable
internal fun InfinityRowsPage(state: StremioUiState, viewModel: StremioViewModel) {
    val layout = state.layout
    val shown = layout.arrange(state.rows)
    val all = shown + state.rows.filter { layout.of(it.key).hidden }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    val first = remember { FocusRequester() }
    val renameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yield()
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SettingsSection(stringResource(R.string.dial_infinity_look)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_infinity_card_style),
                value = stringResource(if (layout.cardStyle == CardStyle.Poster) R.string.dial_infinity_poster else R.string.dial_infinity_landscape),
                onClick = { viewModel.setCardStyle(if (layout.cardStyle == CardStyle.Poster) CardStyle.Landscape else CardStyle.Poster) },
                focusRequester = first,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_infinity_continue_style),
                value = stringResource(if (layout.continueStyle == CardStyle.Poster) R.string.dial_infinity_poster else R.string.dial_infinity_landscape),
                onClick = { viewModel.setContinueStyle(if (layout.continueStyle == CardStyle.Poster) CardStyle.Landscape else CardStyle.Poster) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_infinity_hero),
                value = if (layout.hero) on else off,
                onClick = { viewModel.setHero(!layout.hero) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_infinity_ratings_on_cards),
                value = if (layout.ratingsOnCards) on else off,
                onClick = { viewModel.setRatingsOnCards(!layout.ratingsOnCards) },
            )
        }
        item { SettingsSection(stringResource(R.string.dial_infinity_rows)) }
        item { InfinityStatusPlain(stringResource(R.string.dial_infinity_rows_hint)) }
        items(all, key = { "layout-${it.key}" }) { row ->
            val hidden = layout.of(row.key).hidden
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingRow(
                    label = "${layout.nameOf(row)}  ·  ${row.addonName}",
                    value = when {
                        hidden -> stringResource(R.string.dial_value_hidden)
                        else -> stringResource(R.string.dial_value_position, shown.indexOf(row) + 1)
                    },
                    onClick = { viewModel.toggleRowHidden(row.key) },
                    onKey = { event ->
                        !hidden && stepperKeys(event) { delta -> viewModel.moveRow(row.key, delta) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        text = stringResource(R.string.dial_infinity_rename),
                        icon = Icons.Rounded.Tune,
                        selected = renaming == row.key,
                        focusRequester = renameFocus.takeIf { renaming == row.key },
                        onClick = {
                            if (renaming == row.key) {
                                renaming = null
                            } else {
                                renaming = row.key
                                draft = layout.nameOf(row)
                            }
                        },
                    )
                    if (layout.of(row.key).name != null) {
                        TvActionButton(
                            text = stringResource(R.string.dial_infinity_original_name),
                            icon = Icons.Rounded.CheckCircle,
                            onClick = { viewModel.renameRow(row.key, "") },
                        )
                    }
                }
                if (renaming == row.key) {
                    Box(Modifier.widthIn(max = 820.dp)) {
                        DialTextField(
                            label = stringResource(R.string.dial_infinity_row_name),
                            value = draft,
                            onValueChange = { draft = it },
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                            readOnly = false,
                            onDone = {
                                viewModel.renameRow(row.key, draft)
                                // Back onto the Rename button before the field goes away.
                                runCatching { renameFocus.requestFocus() }
                                renaming = null
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_infinity_reset_layout),
                value = "",
                onClick = viewModel::resetLayout,
            )
        }
    }
}

@Composable
private fun InfinityStatusPlain(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 14.sp,
        modifier = Modifier.widthIn(max = 820.dp),
    )
}

/* ---------------------------------------------------------------------------- details page */

@Composable
internal fun InfinityDetailsPage(state: StremioUiState, viewModel: StremioViewModel) {
    val details = state.details
    val primary = remember { FocusRequester() }
    LaunchedEffect(details?.id) {
        if (details == null) return@LaunchedEffect
        yield()
        runCatching { primary.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = details?.background ?: details?.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.34f),
        )
        Box(
            Modifier
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
            contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().clipToBounds().focusGroup(),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    AsyncImage(
                        model = details?.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(196.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(TvColors.Surface),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        if (details?.logo != null) {
                            AsyncImage(
                                model = details.logo,
                                contentDescription = details.name,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier.widthIn(max = 480.dp).height(96.dp),
                            )
                        } else {
                            Text(
                                text = details?.name ?: stringResource(R.string.dial_addons_loading),
                                color = TvColors.TextPrimary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 36.sp,
                                lineHeight = 42.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val pills = listOfNotNull(
                            details?.releaseInfo,
                            details?.imdbRating?.let { stringResource(R.string.dial_infinity_imdb, it) },
                            details?.runtime,
                        ) + details?.genres.orEmpty().take(3)
                        if (pills.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pills.forEach { pill ->
                                    Text(
                                        text = pill,
                                        color = TvColors.TextPrimary,
                                        fontFamily = TvFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(TvColors.SurfaceRaised)
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                        details?.description?.let { plot ->
                            Text(
                                text = plot,
                                color = TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 17.sp,
                                lineHeight = 26.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 760.dp),
                            )
                        }
                        details?.director?.takeIf { it.isNotEmpty() }?.let { directors ->
                            InfinityCredit(stringResource(R.string.dial_details_director, directors.joinToString(", ")))
                        }
                        details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                            InfinityCredit(stringResource(R.string.dial_details_cast, cast.take(8).joinToString(", ")))
                        }
                        if (details != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                                if (details.videos.isEmpty()) {
                                    TvActionButton(
                                        text = stringResource(R.string.dial_addons_play),
                                        icon = Icons.Rounded.PlayArrow,
                                        focusRequester = primary,
                                        onClick = { viewModel.loadStreams(details.type, details.id, details.name) },
                                    )
                                }
                                TvActionButton(
                                    text = stringResource(if (state.inLibrary) R.string.dial_infinity_in_library else R.string.dial_infinity_add_library),
                                    icon = Icons.Rounded.CheckCircle,
                                    selected = state.inLibrary,
                                    focusRequester = primary.takeIf { details.videos.isNotEmpty() },
                                    onClick = viewModel::toggleLibrary,
                                )
                            }
                        }
                        if (state.detailsLoading) InfinityCredit(stringResource(R.string.dial_addons_loading))
                    }
                }
            }
            details?.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
                val seasons = videos.mapNotNull { it.season }.distinct().sorted()
                if (seasons.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup()) {
                            items(seasons) { season ->
                                TvActionButton(
                                    text = stringResource(R.string.dial_addons_season, season),
                                    icon = Icons.Rounded.PlayArrow,
                                    selected = season == state.season,
                                    onClick = { viewModel.selectSeason(season) },
                                )
                            }
                        }
                    }
                }
                val shown = videos.filter { state.season == null || it.season == state.season }
                items(shown, key = { it.id }) { video ->
                    InfinityEpisodeRow(
                        video = video,
                        onClick = {
                            viewModel.loadStreams(
                                "series",
                                video.id,
                                listOf(details.name, video.title).filter { it.isNotBlank() }.joinToString(" · "),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfinityCredit(text: String) {
    Text(
        text = text,
        color = TvColors.TextMuted,
        fontFamily = TvFonts.Body,
        fontSize = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 760.dp),
    )
}

/** An episode: thumbnail, "S01E04  Title", air date and a line of plot. */
@Composable
private fun InfinityEpisodeRow(video: MetaVideo, onClick: () -> Unit) {
    FocusFrame(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        semanticsLabel = video.title,
        modifier = Modifier.widthIn(max = 960.dp),
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(168.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.Surface),
            ) {
                if (video.thumbnail != null) {
                    AsyncImage(
                        model = video.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    text = infinityEpisodeLabel(video),
                    color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                video.released?.take(10)?.let { date ->
                    Text(
                        text = date,
                        color = if (focused) TvColors.OnFocus.copy(alpha = 0.8f) else TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 13.sp,
                    )
                }
                video.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun infinityEpisodeLabel(video: MetaVideo): String {
    val season = video.season?.toString()?.padStart(2, '0')
    val episode = video.episode?.toString()?.padStart(2, '0')
    val code = if (season != null && episode != null) "S${season}E${episode}  " else ""
    return code + video.title
}
