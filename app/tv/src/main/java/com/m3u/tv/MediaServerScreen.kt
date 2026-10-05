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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

/* -------------------------------------------------------------------------------------------------
 * The Media server tab: sign in to Jellyfin or Emby, then rows (continue, latest per library),
 * library grids and series pages. Plays through the usual player.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun MediaServerScreen(
    onPlaying: () -> Unit,
    viewModel: MediaServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.page != ServerPage.Browse) { viewModel.back() }
    when {
        state.session == null -> ServerSignIn(state, viewModel)
        state.page is ServerPage.Library -> ServerLibraryPage(state, (state.page as ServerPage.Library).library, viewModel, onPlaying)
        state.page is ServerPage.Series -> ServerSeriesPage(state, (state.page as ServerPage.Series).series, viewModel, onPlaying)
        else -> ServerBrowse(state, viewModel, onPlaying)
    }
}

@Composable
private fun ServerSignIn(state: MediaServerState, viewModel: MediaServerViewModel) {
    var kind by rememberSaveable { mutableStateOf(ServerKind.Jellyfin) }
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_server_title),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 28.sp,
            )
        }
        item {
            Text(
                text = stringResource(R.string.dial_server_intro),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerKind.entries.forEachIndexed { index, option ->
                    TvActionButton(
                        text = option.name,
                        icon = Icons.Rounded.Dns,
                        selected = kind == option,
                        focusRequester = first.takeIf { index == 0 },
                        onClick = { kind = option },
                    )
                }
            }
        }
        item {
            Box(Modifier.widthIn(max = 820.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_server_address),
                    value = server,
                    onValueChange = { server = it },
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    readOnly = false,
                    placeholder = "http://192.168.1.10:8096",
                )
            }
        }
        item {
            Box(Modifier.widthIn(max = 820.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_server_username),
                    value = username,
                    onValueChange = { username = it },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                    readOnly = false,
                )
            }
        }
        item {
            Box(Modifier.widthIn(max = 820.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_server_password),
                    value = password,
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    secret = true,
                    onDone = { viewModel.signIn(kind, server, username, password) },
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvActionButton(
                    text = stringResource(if (state.signingIn) R.string.dial_server_signing_in else R.string.dial_server_sign_in),
                    icon = Icons.Rounded.PlayArrow,
                    enabled = !state.signingIn,
                    onClick = { viewModel.signIn(kind, server, username, password) },
                )
                state.error?.let { error ->
                    Text(
                        text = stringResource(
                            when (error) {
                                ServerError.Unreachable -> R.string.dial_server_error_unreachable
                                ServerError.WrongLogin -> R.string.dial_server_error_login
                                ServerError.Other -> R.string.dial_server_error_other
                            }
                        ),
                        color = TvColors.Danger,
                        fontFamily = TvFonts.Body,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerBrowse(state: MediaServerState, viewModel: MediaServerViewModel, onPlaying: () -> Unit) {
    val session = state.session ?: return
    var focused by remember { mutableStateOf<ServerItem?>(null) }
    val first = remember { FocusRequester() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (focusedOnce) return@LaunchedEffect
        withFrameNanos { }
        if (runCatching { first.requestFocus() }.isSuccess) focusedOnce = true
    }
    Box(Modifier.fillMaxSize()) {
        ServerBackdrop(focused?.backdrop ?: focused?.poster)
        LazyColumn(
            contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "bar") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 48.dp).focusGroup(),
                ) {
                    Text(
                        text = session.serverName ?: stringResource(R.string.dial_server_title),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Accent,
                        fontSize = 28.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_server_refresh),
                        icon = Icons.Rounded.Refresh,
                        showTextWhenUnfocused = false,
                        focusRequester = first,
                        onClick = viewModel::refresh,
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_server_sign_out, session.username),
                        icon = Icons.Rounded.Logout,
                        showTextWhenUnfocused = false,
                        onClick = viewModel::signOut,
                    )
                }
            }
            if (state.libraries.isNotEmpty()) {
                item(key = "libraries") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        modifier = Modifier.focusGroup(),
                    ) {
                        items(state.libraries, key = { it.id }) { library ->
                            TvActionButton(
                                text = library.name,
                                icon = Icons.Rounded.VideoLibrary,
                                onClick = { viewModel.openLibrary(library) },
                            )
                        }
                    }
                }
            }
            state.error?.let { item(key = "error") { ServerStatus(stringResource(R.string.dial_server_error_unreachable)) } }
            if (state.loading && state.rows.isEmpty()) item(key = "loading") { ServerStatus(stringResource(R.string.dial_addons_loading)) }
            items(state.rows, key = { it.id }) { row ->
                ServerRowView(
                    title = row.name.ifBlank { stringResource(R.string.dial_server_continue) },
                    items = row.items,
                    onFocused = { focused = it },
                    onOpen = { viewModel.play(it, onPlaying) },
                )
            }
        }
    }
}

@Composable
private fun ServerLibraryPage(state: MediaServerState, library: ServerLibrary, viewModel: MediaServerViewModel, onPlaying: () -> Unit) {
    val first = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    LaunchedEffect(state.libraryItems.isNotEmpty()) {
        if (state.libraryItems.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    // Fetch the next page as the last rows come into view.
    val lastVisible = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    LaunchedEffect(lastVisible, state.libraryItems.size, state.libraryComplete) {
        if (!state.libraryComplete && lastVisible >= state.libraryItems.size - PAGE_AHEAD) viewModel.loadMoreOfLibrary()
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            text = library.name,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 28.sp,
            modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 8.dp),
        )
        if (state.loading && state.libraryItems.isEmpty()) ServerStatus(stringResource(R.string.dial_addons_loading))
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(150.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
            modifier = Modifier.fillMaxSize().focusGroup(),
        ) {
            gridItems(state.libraryItems, key = { it.id }) { item ->
                ServerCard(
                    item = item,
                    focusRequester = first.takeIf { item == state.libraryItems.firstOrNull() },
                    onFocused = {},
                    onClick = { viewModel.play(item, onPlaying) },
                )
            }
        }
    }
}

@Composable
private fun ServerSeriesPage(state: MediaServerState, series: ServerItem, viewModel: MediaServerViewModel, onPlaying: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.episodes.isNotEmpty()) {
        if (state.episodes.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        ServerBackdrop(series.backdrop ?: series.poster)
        LazyColumn(
            contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 48.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().focusGroup(),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    AsyncImage(
                        model = series.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(196.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp)).background(TvColors.Surface),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        Text(
                            text = series.name,
                            color = TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp,
                            lineHeight = 42.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(series.year?.toString(), series.rating?.let { "★ %.1f".format(it) }).joinToString("  ·  ")
                        if (meta.isNotBlank()) Text(text = meta, color = TvColors.Focus, fontFamily = TvFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        series.overview?.let { plot ->
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
                    }
                }
            }
            if (state.seasons.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.focusGroup()) {
                        items(state.seasons, key = { it.id }) { season ->
                            TvActionButton(
                                text = season.name,
                                icon = Icons.Rounded.VideoLibrary,
                                selected = season == state.selectedSeason,
                                onClick = { viewModel.selectSeason(season) },
                            )
                        }
                    }
                }
            }
            if (state.loading) item { ServerStatusPlain(stringResource(R.string.dial_addons_loading)) }
            items(state.episodes, key = { it.id }) { episode ->
                ServerEpisodeRow(
                    episode = episode,
                    focusRequester = first.takeIf { episode == state.episodes.firstOrNull() },
                    onClick = { viewModel.play(episode, onPlaying) },
                )
            }
        }
    }
}

@Composable
private fun ServerBackdrop(image: String?) {
    Box(Modifier.fillMaxSize()) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd,
                modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.7f).align(Alignment.TopEnd).alpha(0.45f),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to TvColors.Background,
                    0.45f to TvColors.Background.copy(alpha = 0.85f),
                    1f to TvColors.Background.copy(alpha = 0.2f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to TvColors.Background.copy(alpha = 0.6f),
                    1f to TvColors.Background,
                )
            )
        )
    }
}

@Composable
private fun ServerRowView(title: String, items: List<ServerItem>, onFocused: (ServerItem) -> Unit, onOpen: (ServerItem) -> Unit) {
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
            items(items, key = { it.id }) { item ->
                ServerCard(item, onFocused = { onFocused(item) }, onClick = { onOpen(item) })
            }
        }
    }
}

/** A poster for films and series, a landscape still for episodes; a progress line when started. */
@Composable
private fun ServerCard(
    item: ServerItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val landscape = item.type == "Episode"
    val width = if (landscape) 272.dp else 150.dp
    val label = if (landscape) listOfNotNull(item.seriesName, item.name).joinToString(" · ") else item.name
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusFrame(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            semanticsLabel = label,
            focusedScale = 1.04f,
            focusRequester = focusRequester,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TvColors.Surface),
            ) {
                AsyncImage(
                    model = item.poster ?: item.backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                item.rating?.let { rating ->
                    Text(
                        text = "★ %.1f".format(rating),
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
                item.progressPercent?.let { percent ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(TvColors.Focus))
                    }
                }
            }
        }
        Text(
            text = label,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            maxLines = if (landscape) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ServerEpisodeRow(episode: ServerItem, focusRequester: FocusRequester?, onClick: () -> Unit) {
    FocusFrame(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        semanticsLabel = episode.name,
        focusRequester = focusRequester,
        modifier = Modifier.widthIn(max = 960.dp),
    ) { focused ->
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(TvColors.Surface)) {
                AsyncImage(model = episode.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                episode.progressPercent?.let { percent ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).background(TvColors.Focus))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                val code = if (episode.season != null && episode.episode != null) {
                    "S${episode.season.toString().padStart(2, '0')}E${episode.episode.toString().padStart(2, '0')}  "
                } else ""
                Text(
                    text = code + episode.name,
                    color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.runtimeMinutes?.let { minutes ->
                    Text(
                        text = stringResource(R.string.dial_details_minutes, minutes),
                        color = if (focused) TvColors.OnFocus.copy(alpha = 0.8f) else TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 13.sp,
                    )
                }
                episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
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

private const val PAGE_AHEAD = 12

@Composable
private fun ServerStatus(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.padding(horizontal = 48.dp).widthIn(max = 860.dp),
    )
}

@Composable
private fun ServerStatusPlain(text: String) {
    Text(text = text, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 16.sp)
}
