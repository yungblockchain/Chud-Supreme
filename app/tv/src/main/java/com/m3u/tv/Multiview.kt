package com.m3u.tv

import android.content.Context
import android.widget.Toast
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.ui.platform.LocalContext
import com.m3u.data.repository.channel.ChannelRepository
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * Multiview: up to four live channels at once, in a grid. Sound follows the highlighted tile.
 * Each tile has its own lightweight player capped at 720p, since the Fire TV has a limited
 * number of video decoders.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class MultiviewTile(val channel: Channel, val player: ExoPlayer)

/** A saved set of up to four channels, by name. */
@Immutable
data class MultiviewPreset(val id: String, val name: String, val channelIds: List<Int>)

@Singleton
class MultiviewPresetStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("multiview_presets", Context.MODE_PRIVATE)
    private val _presets = MutableStateFlow(read())
    val presets: StateFlow<List<MultiviewPreset>> = _presets.asStateFlow()

    fun save(name: String, channelIds: List<Int>): MultiviewPreset {
        val preset = MultiviewPreset(System.currentTimeMillis().toString(36), name.trim().take(40), channelIds.take(4))
        write((_presets.value + preset).takeLast(MAX))
        return preset
    }

    fun remove(id: String) = write(_presets.value.filterNot { it.id == id })

    fun reload() {
        _presets.value = read()
    }

    private fun write(presets: List<MultiviewPreset>) {
        _presets.value = presets
        val json = JsonArray(
            presets.map { preset ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(preset.id),
                        "name" to JsonPrimitive(preset.name),
                        "channels" to JsonArray(preset.channelIds.map(::JsonPrimitive)),
                    )
                )
            }
        )
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private fun read(): List<MultiviewPreset> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            MultiviewPreset(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                channelIds = item["channels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "presets"
        const val MAX = 12
    }
}

@HiltViewModel
class MultiviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val presetStore: MultiviewPresetStore,
    private val channelRepository: ChannelRepository,
) : ViewModel() {
    val presets: StateFlow<List<MultiviewPreset>> = presetStore.presets

    /** Keeps the tiles showing as a preset, named after the channels unless [name] is given. */
    fun savePreset(name: String? = null): MultiviewPreset? {
        val tiles = _tiles.value
        if (tiles.isEmpty()) return null
        val label = name?.takeIf { it.isNotBlank() }
            ?: tiles.joinToString(" + ") { it.channel.title.title().take(14) }
        return presetStore.save(label, tiles.map { it.channel.id })
    }

    fun removePreset(preset: MultiviewPreset) = presetStore.remove(preset.id)

    /** Replaces the tiles with the preset's channels (the ones still in the playlists). */
    fun loadPreset(preset: MultiviewPreset) {
        viewModelScope.launch {
            val channels = preset.channelIds.mapNotNull { channelRepository.get(it) }.filter(::supports)
            releaseAll()
            for (channel in channels.take(MAX_TILES)) {
                val player = createPlayer(channel)
                _tiles.value = _tiles.value + MultiviewTile(channel, player)
            }
            applyAudio()
        }
    }

    private val _tiles = MutableStateFlow<List<MultiviewTile>>(emptyList())
    val tiles: StateFlow<List<MultiviewTile>> = _tiles.asStateFlow()

    private val _audio = MutableStateFlow(0)
    val audio: StateFlow<Int> = _audio.asStateFlow()

    // Pending reconnects, cancelled when a tile stops or goes.
    private val retryJobs = mutableMapOf<ExoPlayer, Job>()

    /** Can this channel be shown in a tile (it needs a plain stream address)? */
    fun supports(channel: Channel): Boolean =
        channel.url.startsWith("http", ignoreCase = true) || channel.url.startsWith("rtmp", ignoreCase = true)

    /** Adds a channel (or, when four are showing, replaces the one with sound). */
    fun add(channel: Channel) {
        if (!supports(channel) || _tiles.value.any { it.channel.id == channel.id }) return
        if (_tiles.value.size >= MAX_TILES) {
            replace(_audio.value, channel)
            return
        }
        viewModelScope.launch {
            val player = createPlayer(channel)
            _tiles.value = _tiles.value + MultiviewTile(channel, player)
            applyAudio()
        }
    }

    fun replace(index: Int, channel: Channel) {
        if (!supports(channel)) return
        val current = _tiles.value.getOrNull(index) ?: return add(channel)
        viewModelScope.launch {
            val player = createPlayer(channel)
            retryJobs.remove(current.player)?.cancel()
            current.player.release()
            _tiles.value = _tiles.value.toMutableList().also { it[index] = MultiviewTile(channel, player) }
            applyAudio()
        }
    }

    fun remove(index: Int) {
        val tile = _tiles.value.getOrNull(index) ?: return
        retryJobs.remove(tile.player)?.cancel()
        tile.player.release()
        _tiles.value = _tiles.value.filterIndexed { i, _ -> i != index }
        _audio.value = _audio.value.coerceIn(0, (_tiles.value.size - 1).coerceAtLeast(0))
        applyAudio()
    }

    fun setAudio(index: Int) {
        if (_audio.value == index) return
        _audio.value = index
        applyAudio()
    }

    /** The app left the screen: stop every tile (their connections and decoders). */
    fun stopAll() = _tiles.value.forEach {
        retryJobs.remove(it.player)?.cancel()
        it.player.stop()
    }

    /** Back on screen: reconnect every tile at the live edge. */
    fun resumeAll() = _tiles.value.forEach { tile ->
        if (tile.player.playbackState == Player.STATE_IDLE) {
            tile.player.seekToDefaultPosition()
            tile.player.prepare()
        }
    }

    fun releaseAll() {
        _tiles.value.forEach {
            retryJobs.remove(it.player)?.cancel()
            it.player.release()
        }
        _tiles.value = emptyList()
        _audio.value = 0
    }

    override fun onCleared() {
        releaseAll()
    }

    private fun applyAudio() {
        _tiles.value.forEachIndexed { index, tile ->
            tile.player.volume = if (index == _audio.value) 1f else 0f
        }
    }

    private suspend fun createPlayer(channel: Channel): ExoPlayer {
        val userAgent = runCatching { playlistRepository.get(channel.playlistUrl)?.userAgent }.getOrNull()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(FLAG_ALLOW_NON_IDR_KEYFRAMES or FLAG_DETECT_ACCESS_UNITS)
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(TILE_MAX_WIDTH, TILE_MAX_HEIGHT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 25_000, 1_000, 2_000)
                    .build()
            )
            .build()
        player.addListener(object : Player.Listener {
            private var retries = 0
            override fun onPlayerError(error: PlaybackException) {
                if (retries++ >= MAX_RETRIES) return
                retryJobs.remove(player)?.cancel()
                retryJobs[player] = viewModelScope.launch {
                    delay(RETRY_WAIT_MS * retries)
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) retries = 0
            }
        })
        player.volume = 0f
        player.setMediaItem(MediaItem.fromUri(channel.url))
        player.playWhenReady = true
        player.prepare()
        return player
    }

    companion object {
        const val MAX_TILES = 4
        private const val TILE_MAX_WIDTH = 1280
        private const val TILE_MAX_HEIGHT = 720
        private const val MAX_RETRIES = 5
        private const val RETRY_WAIT_MS = 2_000L
        private const val USER_AGENT = "ChudStreams/1.0 (Android TV)"
    }
}

@Composable
fun MultiviewScreen(
    candidates: List<Channel>,
    onOpenFull: (Channel) -> Unit,
    onClose: () -> Unit,
    viewModel: MultiviewViewModel = hiltViewModel(),
) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val presetSaved = stringResource(R.string.dial_multiview_preset_saved)
    // Picking a channel: for a new tile (index = tiles.size) or to replace one.
    var picking by remember { mutableStateOf<Int?>(null) }
    var tileMenu by remember { mutableStateOf<Int?>(null) }
    val firstTile = remember { FocusRequester() }

    BackHandler(enabled = picking == null && tileMenu == null) {
        viewModel.releaseAll()
        onClose()
    }
    LaunchedEffect(tiles.isEmpty()) {
        yield()
        runCatching { firstTile.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val cells = tiles.map { it as MultiviewTile? } + if (tiles.size < MultiviewViewModel.MAX_TILES) listOf(null) else emptyList()
        val rows = if (cells.size <= 2) listOf(cells) else cells.chunked(2)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    row.forEachIndexed { columnIndex, tile ->
                        val index = rowIndex * 2 + columnIndex
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            if (tile == null) {
                                AddTile(
                                    focusRequester = firstTile.takeIf { index == 0 },
                                    onClick = { picking = tiles.size },
                                )
                            } else {
                                VideoTile(
                                    tile = tile,
                                    hasSound = index == audio,
                                    focusRequester = firstTile.takeIf { index == 0 },
                                    onFocus = { viewModel.setAudio(index) },
                                    onClick = { tileMenu = index },
                                )
                            }
                        }
                    }
                }
            }
        }

        tileMenu?.let { index ->
            val tile = tiles.getOrNull(index)
            if (tile != null) {
                MenuPanel(
                    title = tile.channel.title.title(),
                    subtitle = stringResource(R.string.dial_multiview_title),
                    entries = listOf(
                        MenuEntry(stringResource(R.string.dial_multiview_full), Icons.Rounded.Fullscreen, {
                            tileMenu = null
                            val channel = tile.channel
                            viewModel.releaseAll()
                            onOpenFull(channel)
                        }),
                        MenuEntry(stringResource(R.string.dial_multiview_change), Icons.Rounded.SwapHoriz, {
                            tileMenu = null
                            picking = index
                        }),
                        MenuEntry(stringResource(R.string.dial_multiview_remove), Icons.Rounded.Delete, {
                            tileMenu = null
                            viewModel.remove(index)
                        }),
                        MenuEntry(stringResource(R.string.dial_multiview_save_preset), Icons.Rounded.Bookmark, {
                            tileMenu = null
                            val saved = viewModel.savePreset()
                            if (saved != null) Toast.makeText(context, presetSaved.format(saved.name), Toast.LENGTH_SHORT).show()
                        }),
                    ),
                    onDismiss = { tileMenu = null },
                )
            }
        }

        picking?.let { index ->
            ChannelPicker(
                channels = candidates.filter(viewModel::supports),
                onPick = { channel ->
                    picking = null
                    if (index < tiles.size) viewModel.replace(index, channel) else viewModel.add(channel)
                },
                onDismiss = { picking = null },
                modifier = Modifier.align(Alignment.CenterEnd),
                // Adding to an empty screen: the saved presets come first (hold OK forgets one).
                presets = if (tiles.isEmpty()) presets else emptyList(),
                onPreset = { preset ->
                    picking = null
                    viewModel.loadPreset(preset)
                },
                onForgetPreset = viewModel::removePreset,
            )
        }
    }
}

@Composable
private fun VideoTile(
    tile: MultiviewTile,
    hasSound: Boolean,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        PlayerSurface(
            player = tile.player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )
        FocusFrame(
            onClick = onClick,
            onFocus = onFocus,
            focusRequester = focusRequester,
            focusedScale = 1f,
            focusedBorderWidth = 4.dp,
            transparent = true,
            semanticsLabel = tile.channel.title,
            modifier = Modifier
                .fillMaxSize()
                .border(if (hasSound) 2.dp else 0.dp, TvColors.Focus.copy(alpha = if (hasSound) 0.6f else 0f)),
        ) {
            // Transparent over the video; only the label shows.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (hasSound) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = TvColors.Focus,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = tile.channel.title.title(),
                    color = Color.White,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AddTile(focusRequester: FocusRequester?, onClick: () -> Unit) {
    FocusFrame(
        onClick = onClick,
        focusRequester = focusRequester,
        focusedScale = 1f,
        semanticsLabel = stringResource(R.string.dial_multiview_add),
        modifier = Modifier.fillMaxSize(),
    ) { focused ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = if (focused) TvColors.OnFocus else TvColors.Focus,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.dial_multiview_add),
                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
    }
}

/** A side list of live channels to put in a tile. */
@Composable
private fun ChannelPicker(
    channels: List<Channel>,
    onPick: (Channel) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    presets: List<MultiviewPreset> = emptyList(),
    onPreset: (MultiviewPreset) -> Unit = {},
    onForgetPreset: (MultiviewPreset) -> Unit = {},
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) {
        for (attempt in 0 until PICKER_FOCUS_TRIES) {
            yield()
            if (runCatching { first.requestFocus() }.isSuccess) break
            delay(PICKER_FOCUS_RETRY_MS)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxHeight()
            .width(420.dp)
            .background(TvColors.Background.copy(alpha = 0.95f))
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_multiview_pick),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        if (channels.isEmpty()) {
            Text(
                text = stringResource(R.string.dial_multiview_pick_empty),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            itemsIndexed(presets, key = { _, preset -> "preset-${preset.id}" }) { index, preset ->
                FocusFrame(
                    onClick = { onPreset(preset) },
                    onLongClick = { onForgetPreset(preset) },
                    focusRequester = first.takeIf { index == 0 },
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.02f,
                    semanticsLabel = preset.name,
                    modifier = Modifier.fillMaxWidth(),
                ) { focused ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = stringResource(R.string.dial_multiview_preset_label),
                            color = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextMuted,
                            fontFamily = TvFonts.Body,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = preset.name,
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                FocusFrame(
                    onClick = { onPick(channel) },
                    focusRequester = first.takeIf { index == 0 && presets.isEmpty() },
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.02f,
                    semanticsLabel = channel.title,
                    modifier = Modifier.fillMaxWidth(),
                ) { focused ->
                    Text(
                        text = channel.title.title(),
                        color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private const val PICKER_FOCUS_TRIES = 10
private const val PICKER_FOCUS_RETRY_MS = 32L

/** The video in a corner while browsing. Not focusable: Menu makes it full screen again. */
@Composable
fun MiniPlayer(
    player: Player,
    channel: Channel?,
    modifier: Modifier = Modifier,
    size: MiniSize = MiniSize.Medium,
) {
    val width = when (size) {
        MiniSize.Small -> MINI_WIDTH_SMALL
        MiniSize.Medium -> MINI_WIDTH
        MiniSize.Large -> MINI_WIDTH_LARGE
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.width(width),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .border(2.dp, TvColors.Focus.copy(alpha = 0.7f)),
        ) {
            PlayerSurface(
                player = player,
                // TextureView composites with the menus. SurfaceView punches a hole through
                // the whole screen, which hid the UI whenever the video was minimised.
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
            LaunchedEffect(player) {
                (player as? ExoPlayer)?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                    player.play()
                }
            }
        }
        Text(
            text = channel?.title?.title().orEmpty(),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.dial_mini_hint),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 12.sp,
        )
    }
}

private val MINI_WIDTH = 384.dp
private val MINI_WIDTH_SMALL = 288.dp
private val MINI_WIDTH_LARGE = 520.dp
