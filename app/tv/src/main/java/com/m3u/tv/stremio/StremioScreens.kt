package com.m3u.tv.stremio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.tv.DialTextField
import com.m3u.tv.FocusFrame
import com.m3u.tv.R
import com.m3u.tv.SecretName
import com.m3u.tv.SecretRow
import com.m3u.tv.TvActionButton
import com.m3u.tv.TvColors
import com.m3u.tv.TvFonts

@Composable
fun StremioScreen(
    onPlaying: () -> Unit,
    onManageAddons: () -> Unit = {},
    viewModel: StremioViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != StremioPage.Browse && state.page != StremioPage.Addons) {
        viewModel.back()
    }
    when (state.page) {
        StremioPage.Browse, StremioPage.Addons -> BrowsePage(state, viewModel, onManageAddons)
        StremioPage.Details -> DetailsPage(state, viewModel)
        StremioPage.Streams -> StreamsPage(state, viewModel, onPlaying)
    }
}

/** Addon install, debrid tokens and P2P. Lives in Settings, not the side menu. */
@Composable
fun StremioAddonsSettings(viewModel: StremioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AddonsPage(state, viewModel)
}

@Composable
private fun BrowsePage(
    state: StremioUiState,
    viewModel: StremioViewModel,
    onManageAddons: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val first = FocusRequester()
    val catalogRows = when (state.shelf) {
        "library" -> oneRow("library", "Your library", state.library)
        "upnext" -> oneRow("upnext", "Up next", state.upNext)
        "calendar" -> oneRow("calendar", "Coming up", state.calendar)
        else -> state.rows
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.dial_nav_infinite),
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 28.sp,
                    modifier = Modifier.weight(1f),
                )
                TvActionButton(
                    text = stringResource(R.string.dial_addons_manage),
                    icon = Icons.Rounded.Extension,
                    onClick = onManageAddons,
                    focusRequester = first,
                    modifier = Modifier.focusRequester(first),
                )
            }
        }
        item {
            DialTextField(
                label = stringResource(R.string.dial_addons_search),
                value = query,
                onValueChange = { query = it },
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
                readOnly = false,
                onDone = { viewModel.search(query) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    text = stringResource(R.string.dial_addons_search_go),
                    icon = Icons.Rounded.Search,
                    onClick = { viewModel.search(query) },
                )
                if (state.addons.none { it.enabled && it.catalogs.isNotEmpty() }) {
                    TvActionButton(
                        text = stringResource(R.string.dial_addons_starter),
                        icon = Icons.Rounded.PlayArrow,
                        onClick = viewModel::installStarter,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("home" to "Home", "library" to "Library", "upnext" to "Up next", "calendar" to "Calendar").forEach { (id, label) ->
                    TvActionButton(
                        text = label,
                        icon = Icons.Rounded.PlayArrow,
                        selected = state.shelf == id,
                        onClick = { viewModel.setShelf(id) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("all" to "All", "movie" to "Films", "series" to "Series").forEach { (id, label) ->
                    TvActionButton(
                        text = label,
                        icon = Icons.Rounded.Search,
                        selected = state.searchKind == id,
                        onClick = { viewModel.setSearchKind(id) },
                    )
                }
            }
        }
        item {
            StatusLine(
                buildString {
                    append("Infinity search only looks through addons. ")
                    state.traktUser?.let { append("Trakt · $it") }
                },
            )
        }
        if (state.shelf == "home") {
            state.continueWatching.firstOrNull()?.let { hero ->
                item { HeroCard(hero) { viewModel.openItem(hero) } }
            }
            item { ShelfRow("Continue", state.continueWatching, viewModel) }
            item { ShelfRow("Library", state.library.take(16), viewModel) }
            item { ShelfRow("Up next", state.upNext, viewModel) }
            item { ShelfRow("Calendar", state.calendar, viewModel) }
        }
        state.message?.let { message -> item { StatusLine(message) } }
        if (state.loading) {
            item { StatusLine(stringResource(R.string.dial_addons_loading)) }
        }
        if (!state.loading && catalogRows.isEmpty() && state.shelf == "home" && state.addons.none { it.catalogs.isNotEmpty() }) {
            item { StatusLine(stringResource(R.string.dial_addons_empty)) }
        }
        if (!state.loading && catalogRows.isEmpty() && state.shelf != "home") {
            item {
                StatusLine(
                    when (state.shelf) {
                        "library" -> "Nothing saved yet. Open a title and choose Library."
                        "upnext" -> "Up next appears after you save a series, or when Trakt has something paused."
                        else -> "Nothing on the calendar. Save a series, or connect Trakt."
                    },
                )
            }
        }
        items(catalogRows, key = { "${it.addonId}:${it.type}:${it.catalogId}:${it.name}" }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.name,
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clipToBounds(),
                ) {
                    items(row.items, key = { "${row.catalogId}:${it.type}:${it.id}" }) { item ->
                        PosterCard(item) { viewModel.openItem(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(item: CatalogItem, onClick: () -> Unit) {
    Column(modifier = Modifier.width(148.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusFrame(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            semanticsLabel = item.name,
            focusedScale = 1f,
            raiseOnFocus = false,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AsyncImage(
                model = item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            text = item.name,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailsPage(state: StremioUiState, viewModel: StremioViewModel) {
    val details = state.details
    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 64.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AsyncImage(
                    model = details?.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(180.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    Text(
                        text = details?.name ?: stringResource(R.string.dial_addons_loading),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Accent,
                        fontSize = 28.sp,
                    )
                    val meta = listOfNotNull(details?.releaseInfo, details?.imdbRating?.let { "IMDb $it" }, details?.runtime)
                        .joinToString("  ·  ")
                    if (meta.isNotBlank()) {
                        Text(text = meta, color = TvColors.Focus, fontFamily = TvFonts.Body, fontSize = 16.sp)
                    }
                    details?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                        Text(text = genres.joinToString("  "), color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 15.sp)
                    }
                    details?.description?.let { plot ->
                        Text(text = plot, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 16.sp, modifier = Modifier.widthIn(max = 760.dp))
                    }
                    details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                        Text(
                            text = cast.take(8).joinToString(", "),
                            color = TvColors.TextSecondary,
                            fontFamily = TvFonts.Body,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (details != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                text = if (state.inLibrary) "In library" else "Library",
                                icon = Icons.Rounded.CheckCircle,
                                selected = state.inLibrary,
                                onClick = viewModel::toggleLibrary,
                            )
                            if (details.videos.isEmpty()) {
                                TvActionButton(
                                    text = stringResource(R.string.dial_addons_play),
                                    icon = Icons.Rounded.PlayArrow,
                                    onClick = { viewModel.loadStreams(details.type, details.id, details.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.detailsLoading) {
            item { StatusLine(stringResource(R.string.dial_addons_loading)) }
        }
        details?.videos?.takeIf { it.isNotEmpty() }?.let { videos ->
            val seasons = videos.mapNotNull { it.season }.distinct().sorted()
            if (seasons.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                FocusFrame(
                    onClick = {
                        viewModel.loadStreams(
                            "series",
                            video.id,
                            listOf(details.name, video.title).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                    },
                    semanticsLabel = video.title,
                    modifier = Modifier.widthIn(max = 860.dp),
                ) { focused ->
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(
                            text = episodeLabel(video),
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
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
    }
}

@Composable
private fun StreamsPage(state: StremioUiState, viewModel: StremioViewModel, onPlaying: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 48.dp, end = 64.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_addons_streams),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 28.sp,
            )
        }
        state.resolving?.let { name -> item { StatusLine(stringResource(R.string.dial_addons_resolving, name)) } }
        state.message?.let { message -> item { StatusLine(message) } }
        if (state.streamsLoading) item { StatusLine(stringResource(R.string.dial_addons_loading)) }
        if (state.subtitles.isNotEmpty()) {
            item { StatusLine("${state.subtitles.size} subtitle tracks. The first one turns on when this starts.") }
        }
        items(state.streams, key = { "${it.addonId}:${it.name}:${it.playableUrl}" }) { source ->
            FocusFrame(
                onClick = { viewModel.play(source, onPlaying) },
                enabled = state.resolving == null,
                semanticsLabel = source.name,
                modifier = Modifier.widthIn(max = 920.dp),
            ) { focused ->
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(
                        text = source.name.ifBlank { source.addonName },
                        color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val bits = listOfNotNull(
                        source.quality,
                        source.size,
                        source.seeders?.let { "$it peers" },
                        when {
                            source.isDebrid -> "Debrid"
                            source.isMagnet -> "P2P"
                            else -> "Direct"
                        },
                        source.addonName,
                    )
                    Text(
                        text = bits.joinToString("  ·  "),
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

@Composable
private fun AddonsPage(state: StremioUiState, viewModel: StremioViewModel) {
    var manifest by rememberSaveable { mutableStateOf("") }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var torr by rememberSaveable(state.torrServe) { mutableStateOf(state.torrServe) }
    // A plain column, not a lazy list. The lazy list crashed this page: its keys collided with
    // the preset rows, and a nested scroll inside Settings measured it with an infinite height.
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 48.dp, end = 64.dp, top = 28.dp, bottom = 48.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_addons_manage),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 28.sp,
        )
        state.message?.let { message -> StatusLine(message) }
        StatusLine(stringResource(R.string.dial_addons_presets_hint))
        AddonCatalogPresets.all.forEach { preset ->
            val installed = state.addons.any { addon ->
                addon.id == preset.id || preset.manifestUrl.isNotBlank() &&
                    addon.manifestUrl.startsWith(preset.manifestUrl.substringBefore("/manifest"))
            }
            FocusFrame(
                onClick = { viewModel.installPreset(preset) },
                semanticsLabel = preset.name,
                modifier = Modifier.widthIn(max = 920.dp),
            ) { focused ->
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(
                        text = preset.name + if (installed) "  ·  " + stringResource(R.string.dial_addons_installed) else "",
                        color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = preset.description,
                        color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        DialTextField(
            label = stringResource(R.string.dial_addons_paste),
            value = manifest,
            onValueChange = { manifest = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = { viewModel.install(manifest) },
        )
        TvActionButton(
            text = stringResource(R.string.dial_addons_install),
            icon = Icons.Rounded.CheckCircle,
            enabled = manifest.isNotBlank(),
            onClick = { viewModel.install(manifest) },
        )
        if (state.addons.isNotEmpty()) {
            StatusLine(stringResource(R.string.dial_addons_installed_header))
            state.addons.forEach { addon ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvActionButton(
                        text = if (addon.enabled) addon.name else "${addon.name} (off)",
                        icon = Icons.Rounded.Extension,
                        selected = addon.enabled,
                        onClick = { viewModel.setEnabled(addon.id, !addon.enabled) },
                    )
                    TvActionButton(
                        text = "Configure",
                        icon = Icons.Rounded.Search,
                        onClick = { manifest = addon.manifestUrl },
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_addons_remove),
                        icon = Icons.Rounded.Delete,
                        onClick = { viewModel.remove(addon.id) },
                    )
                }
            }
        }
        StatusLine("Trakt")
        StatusLine(
            when {
                state.traktUser != null -> "Connected as ${state.traktUser}. Watchlist, up next and scrobbles follow this account."
                state.traktCode != null -> "On ${state.traktUrl ?: "trakt.tv/activate"} enter ${state.traktCode}"
                else -> "Add your Trakt client ID and secret under Settings → Services, then connect. Library stays on this Fire TV either way."
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = if (state.traktUser == null) "Connect Trakt" else "Refresh Trakt",
                icon = Icons.Rounded.CheckCircle,
                onClick = viewModel::connectTrakt,
            )
            if (state.traktUser != null || state.traktCode != null) {
                TvActionButton(
                    text = "Disconnect",
                    icon = Icons.Rounded.Delete,
                    onClick = viewModel::disconnectTrakt,
                )
            }
        }
        StatusLine(stringResource(R.string.dial_services_section_debrid))
        SecretRow(
            name = SecretName.RealDebrid,
            label = stringResource(R.string.dial_services_realdebrid),
            hint = stringResource(R.string.dial_services_realdebrid_hint),
            saved = state.hasRealDebrid,
            editing = editingKey == SecretName.RealDebrid.key,
            onEdit = { editingKey = it?.key },
            onSave = viewModel::saveSecret,
            onRemove = viewModel::removeSecret,
        )
        SecretRow(
            name = SecretName.TorBox,
            label = stringResource(R.string.dial_services_torbox),
            hint = stringResource(R.string.dial_services_torbox_hint),
            saved = state.hasTorBox,
            editing = editingKey == SecretName.TorBox.key,
            onEdit = { editingKey = it?.key },
            onSave = viewModel::saveSecret,
            onRemove = viewModel::removeSecret,
        )
        TvActionButton(
            text = stringResource(R.string.dial_addons_p2p),
            icon = Icons.Rounded.PlayArrow,
            selected = state.p2p,
            onClick = { viewModel.setP2p(!state.p2p) },
            supportingText = if (state.p2p) stringResource(R.string.dial_addons_p2p_on) else stringResource(R.string.dial_addons_p2p_off),
        )
        DialTextField(
            label = stringResource(R.string.dial_addons_torrserve),
            value = torr,
            onValueChange = { torr = it },
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            readOnly = false,
            onDone = { viewModel.setTorrServe(torr) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_action_save),
                icon = Icons.Rounded.CheckCircle,
                onClick = { viewModel.setTorrServe(torr) },
            )
            TvActionButton(
                text = stringResource(R.string.dial_addons_torrserve_test),
                icon = Icons.Rounded.Search,
                onClick = viewModel::testTorrServe,
                supportingText = when (state.torrServeUp) {
                    true -> stringResource(R.string.dial_addons_torrserve_up)
                    false -> stringResource(R.string.dial_addons_torrserve_down)
                    null -> null
                },
            )
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}

private fun episodeLabel(video: MetaVideo): String {
    val season = video.season?.toString()?.padStart(2, '0')
    val episode = video.episode?.toString()?.padStart(2, '0')
    val code = if (season != null && episode != null) "S${season}E${episode}  " else ""
    return code + video.title
}

private fun oneRow(id: String, name: String, items: List<CatalogItem>): List<CatalogRow> =
    if (items.isEmpty()) emptyList() else listOf(CatalogRow(id, name, "movie", id, name, items))

@Composable
private fun ShelfRow(title: String, items: List<CatalogItem>, viewModel: StremioViewModel) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().clipToBounds(),
        ) {
            items(items, key = { "$title:${it.type}:${it.id}" }) { item ->
                PosterCard(item) {
                    val episode = item.type == "series" && item.id.count { char -> char == ':' } >= 2
                    if (episode) viewModel.loadStreams("series", item.id, item.name) else viewModel.openItem(item)
                }
            }
        }
    }
}

@Composable
private fun HeroCard(item: CatalogItem, onClick: () -> Unit) {
    FocusFrame(
        onClick = onClick,
        semanticsLabel = item.name,
        focusedScale = 1f,
        raiseOnFocus = false,
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            AsyncImage(
                model = item.background ?: item.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(text = "Continue", color = if (focused) TvColors.OnFocus else TvColors.Focus, fontFamily = TvFonts.Body, fontSize = 14.sp)
                Text(
                    text = item.name,
                    color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 26.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.description?.let { plot ->
                    Text(
                        text = plot,
                        color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 15.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
