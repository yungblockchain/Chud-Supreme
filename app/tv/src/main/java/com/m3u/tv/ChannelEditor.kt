package com.m3u.tv

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * The channel editor (Settings > Channels): rename a channel, move it up or down its category,
 * hide it, bring hidden ones back. Names and orders live on the stick, keyed by the stream's
 * address, so a playlist refresh doesn't undo them.
 * ---------------------------------------------------------------------------------------------- */

@Singleton
class ChannelEditStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("channel_edits", Context.MODE_PRIVATE)

    /** Custom names by stream address. */
    private val _names = MutableStateFlow(readNames())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** Channel orders (stream addresses) by "playlist|category". */
    private val _orders = MutableStateFlow(readOrders())
    val orders: StateFlow<Map<String, List<String>>> = _orders.asStateFlow()

    /** Bumps whenever something changes, so lists can reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    val hasEdits: Boolean get() = _names.value.isNotEmpty() || _orders.value.isNotEmpty()

    fun rename(channel: Channel, name: String?) {
        val trimmed = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        val next = _names.value.toMutableMap()
        if (trimmed == null) next.remove(channel.url) else next[channel.url] = trimmed
        _names.value = next
        prefs.edit().putString(KEY_NAMES, JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString()).apply()
        _version.update { it + 1 }
    }

    /** Moves [channel] by [delta] among [shown] (its category as displayed) and keeps that order. */
    fun move(playlistUrl: String, category: String?, shown: List<Channel>, channel: Channel, delta: Int) {
        val order = shown.map { it.url }.toMutableList()
        val from = order.indexOf(channel.url)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return
        order.add(to, order.removeAt(from))
        val next = _orders.value.toMutableMap()
        next[orderKey(playlistUrl, category ?: channel.category)] = order
        saveOrders(next)
    }

    fun resetOrder(playlistUrl: String, category: String?) {
        val next = _orders.value.toMutableMap()
        if (category == null) next.keys.removeAll { it.startsWith("$playlistUrl|") } else next.remove(orderKey(playlistUrl, category))
        saveOrders(next)
    }

    fun resetAll() {
        _names.value = emptyMap()
        _orders.value = emptyMap()
        prefs.edit().clear().apply()
        _version.update { it + 1 }
    }

    /** The custom name, if any. */
    fun nameOf(channel: Channel): String? = _names.value[channel.url]

    /** [channel] with its custom name, when it has one. */
    fun applyName(channel: Channel): Channel = _names.value[channel.url]?.let { channel.copy(title = it) } ?: channel

    fun applyNames(channels: List<Channel>): List<Channel> {
        val names = _names.value
        if (names.isEmpty()) return channels
        return channels.map { channel -> names[channel.url]?.let { channel.copy(title = it) } ?: channel }
    }

    /**
     * [channels] (one category, or a whole playlist) in the saved order: within each category,
     * moved channels take their saved places and the rest keep the provider's order.
     */
    fun arrange(playlistUrl: String, channels: List<Channel>): List<Channel> {
        val orders = _orders.value
        if (orders.none { it.key.startsWith("$playlistUrl|") }) return applyNames(channels)
        val firstOfCategory = HashMap<String, Int>()
        channels.forEachIndexed { index, channel -> firstOfCategory.putIfAbsent(channel.category, index) }
        val positions = HashMap<String, Map<String, Int>>()
        val arranged = channels.withIndex().sortedWith(
            compareBy(
                { firstOfCategory[it.value.category] ?: 0 },
                { indexed ->
                    val category = indexed.value.category
                    val place = positions.getOrPut(category) {
                        orders[orderKey(playlistUrl, category)]?.withIndex()?.associate { (i, url) -> url to i }.orEmpty()
                    }
                    place[indexed.value.url] ?: Int.MAX_VALUE
                },
                { it.index },
            )
        ).map { it.value }
        return applyNames(arranged)
    }

    fun reload() {
        _names.value = readNames()
        _orders.value = readOrders()
        _version.update { it + 1 }
    }

    private fun saveOrders(next: Map<String, List<String>>) {
        _orders.value = next
        prefs.edit().putString(KEY_ORDERS, JsonObject(next.mapValues { (_, urls) -> JsonArray(urls.map(::JsonPrimitive)) }).toString()).apply()
        _version.update { it + 1 }
    }

    private fun readNames(): Map<String, String> = runCatching {
        val raw = prefs.getString(KEY_NAMES, null) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapNotNull { (url, value) -> value.jsonPrimitive.contentOrNull?.let { url to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun readOrders(): Map<String, List<String>> = runCatching {
        val raw = prefs.getString(KEY_ORDERS, null) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { (_, value) -> value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
    }.getOrDefault(emptyMap())

    private fun orderKey(playlistUrl: String, category: String) = "$playlistUrl|$category"

    private companion object {
        const val KEY_NAMES = "names"
        const val KEY_ORDERS = "orders"
        const val MAX_NAME = 60
    }
}

/* ------------------------------------------------------------------------------ viewmodel */

@Immutable
data class ChannelEditorState(
    val playlists: List<Playlist> = emptyList(),
    val playlist: Playlist? = null,
    val categories: List<String> = emptyList(),
    /** Null = the hidden channels. */
    val category: String? = null,
    val showingHidden: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val loading: Boolean = false,
    /** The channel whose name is being typed. */
    val renaming: Channel? = null,
)

@HiltViewModel
class ChannelEditorViewModel @Inject constructor(
    private val store: ChannelEditStore,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChannelEditorState())
    val state: StateFlow<ChannelEditorState> = _state.asStateFlow()
    val names: StateFlow<Map<String, String>> = store.names
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            playlistRepository.observeAll().collect { all ->
                val live = all.filter { !it.isVod && !it.isSeries && it.url !in TvHomeViewModel.STAND_IN_PLAYLISTS }
                _state.update { it.copy(playlists = live) }
                if (_state.value.playlist == null) live.firstOrNull()?.let(::selectPlaylist)
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        _state.update { it.copy(playlist = playlist, categories = emptyList(), category = null, showingHidden = false, channels = emptyList(), loading = true) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val categories = channelRepository.getCategoryCounts(playlist.url).map { it.name }
            _state.update { if (it.playlist?.url == playlist.url) it.copy(categories = categories) else it }
            categories.firstOrNull()?.let { selectCategory(it) } ?: _state.update { it.copy(loading = false) }
        }
    }

    fun selectCategory(category: String) {
        val playlist = _state.value.playlist ?: return
        _state.update { it.copy(category = category, showingHidden = false, channels = emptyList(), loading = true, renaming = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val channels = store.arrange(playlist.url, channelRepository.getUnhidden(playlist.url, category, byTitle = false))
            _state.update { if (it.category == category && !it.showingHidden) it.copy(channels = channels, loading = false) else it }
        }
    }

    fun showHidden() {
        val playlist = _state.value.playlist ?: return
        _state.update { it.copy(category = null, showingHidden = true, channels = emptyList(), loading = true, renaming = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            channelRepository.observeAllHidden().collect { hidden ->
                _state.update {
                    if (it.showingHidden) it.copy(channels = store.applyNames(hidden.filter { channel -> channel.playlistUrl == playlist.url }), loading = false) else it
                }
            }
        }
    }

    fun move(channel: Channel, delta: Int) {
        val current = _state.value
        val playlist = current.playlist ?: return
        if (current.showingHidden) return
        store.move(playlist.url, current.category, current.channels, channel, delta)
        val order = current.channels.toMutableList()
        val from = order.indexOfFirst { it.id == channel.id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        _state.update { it.copy(channels = order) }
    }

    fun startRename(channel: Channel) = _state.update { it.copy(renaming = channel) }

    fun cancelRename() = _state.update { it.copy(renaming = null) }

    fun rename(channel: Channel, name: String) {
        store.rename(channel, name)
        _state.update { it.copy(renaming = null) }
        reloadList()
    }

    fun resetName(channel: Channel) {
        store.rename(channel, null)
        _state.update { it.copy(renaming = null) }
        reloadList()
    }

    private fun reloadList() {
        val current = _state.value
        if (current.showingHidden) showHidden() else current.category?.let(::selectCategory)
    }

    fun hide(channel: Channel) {
        viewModelScope.launch {
            channelRepository.hide(channel.id, true)
            _state.update { state -> state.copy(channels = state.channels.filterNot { it.id == channel.id }) }
        }
    }

    fun unhide(channel: Channel) {
        viewModelScope.launch { channelRepository.hide(channel.id, false) }
    }

    fun resetCategoryOrder() {
        val current = _state.value
        val playlist = current.playlist ?: return
        store.resetOrder(playlist.url, current.category)
        current.category?.let(::selectCategory)
    }

    fun resetEverything() {
        store.resetAll()
        _state.value.category?.let(::selectCategory)
    }
}

/* --------------------------------------------------------------------------------- screen */

@Composable
fun ChannelEditorScreen(viewModel: ChannelEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val renaming = state.renaming
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.dial_channels_title),
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
                Text(
                    text = stringResource(R.string.dial_channels_intro),
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.widthIn(max = 860.dp),
                )
            }
        }
        if (state.playlists.size > 1) {
            item(key = "playlists") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusGroup()) {
                    items(state.playlists, key = { it.url }) { playlist ->
                        TvActionButton(
                            text = playlist.title,
                            icon = Icons.Rounded.Category,
                            onClick = { viewModel.selectPlaylist(playlist) },
                            selected = state.playlist?.url == playlist.url,
                        )
                    }
                }
            }
        }
        item(key = "categories") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.focusGroup()) {
                itemsIndexed(state.categories, key = { _, name -> name }) { index, name ->
                    TvActionButton(
                        text = name,
                        icon = Icons.Rounded.Category,
                        onClick = { viewModel.selectCategory(name) },
                        selected = !state.showingHidden && state.category == name,
                        focusRequester = firstFocus.takeIf { index == 0 },
                    )
                }
                item(key = "hidden") {
                    TvActionButton(
                        text = stringResource(R.string.dial_channels_hidden),
                        icon = Icons.Rounded.VisibilityOff,
                        onClick = viewModel::showHidden,
                        selected = state.showingHidden,
                        focusRequester = firstFocus.takeIf { state.categories.isEmpty() },
                    )
                }
            }
        }
        if (renaming != null) {
            item(key = "rename") {
                RenameBox(
                    channel = renaming,
                    initial = names[renaming.url] ?: renaming.title,
                    onDone = { viewModel.rename(renaming, it) },
                    onReset = { viewModel.resetName(renaming) },
                    onCancel = viewModel::cancelRename,
                )
            }
        }
        item(key = "hint") {
            Text(
                text = when {
                    state.loading -> stringResource(R.string.dial_radio_loading)
                    state.showingHidden -> stringResource(R.string.dial_channels_hidden_hint)
                    state.channels.isEmpty() -> stringResource(R.string.dial_channels_empty)
                    else -> stringResource(R.string.dial_channels_keys_hint)
                },
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
            )
        }
        itemsIndexed(state.channels, key = { _, channel -> channel.id }) { index, channel ->
            ChannelEditRow(
                channel = channel,
                number = index + 1,
                renamed = channel.url in names,
                hidden = state.showingHidden,
                onClick = { if (state.showingHidden) viewModel.unhide(channel) else viewModel.startRename(channel) },
                onLongClick = if (state.showingHidden) null else ({ viewModel.hide(channel) }),
                onMove = { delta -> viewModel.move(channel, delta) },
            )
        }
        if (!state.showingHidden && state.channels.isNotEmpty()) {
            item(key = "reset") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    TvActionButton(
                        text = stringResource(R.string.dial_channels_reset_order),
                        icon = Icons.Rounded.Restore,
                        onClick = viewModel::resetCategoryOrder,
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_channels_reset_all),
                        icon = Icons.Rounded.Restore,
                        onClick = viewModel::resetEverything,
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameBox(
    channel: Channel,
    initial: String,
    onDone: (String) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember(channel.id) { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(channel.id) {
        withFrameNanos { }
        runCatching { fieldFocus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(TvColors.SurfaceRaised)
            .padding(16.dp),
    ) {
        DialTextField(
            label = stringResource(R.string.dial_channels_rename_label),
            value = value,
            onValueChange = { value = it },
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
            readOnly = false,
            focusRequester = fieldFocus,
            onDone = { onDone(value) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvActionButton(text = stringResource(R.string.dial_channels_rename_save), icon = Icons.Rounded.Check, onClick = { onDone(value) })
            TvActionButton(text = stringResource(R.string.dial_channels_rename_reset), icon = Icons.Rounded.Restore, onClick = onReset)
            TvActionButton(text = stringResource(R.string.dial_channels_rename_cancel), icon = Icons.Rounded.Close, onClick = onCancel)
        }
    }
}

@Composable
private fun ChannelEditRow(
    channel: Channel,
    number: Int,
    renamed: Boolean,
    hidden: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onMove: (Int) -> Unit,
) {
    FocusFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.01f,
        semanticsLabel = channel.title,
        onKey = { event ->
            if (hidden || event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionLeft -> { onMove(-1); true }
                Key.DirectionRight -> { onMove(1); true }
                else -> false
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 860.dp),
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextMuted
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text = number.toString(),
                color = secondary,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (focused) TvColors.OnFocus.copy(alpha = 0.12f) else TvColors.Surface),
            ) {
                AsyncImage(
                    model = channel.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(4.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.title,
                    color = primary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (renamed) {
                    Text(
                        text = stringResource(R.string.dial_channels_renamed),
                        color = secondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 12.sp,
                    )
                }
            }
            Icon(
                imageVector = if (hidden) Icons.Rounded.Restore else Icons.Rounded.Edit,
                contentDescription = null,
                tint = secondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
