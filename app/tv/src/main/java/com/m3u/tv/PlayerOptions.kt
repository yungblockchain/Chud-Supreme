package com.m3u.tv

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.set
import com.m3u.data.service.PlayerManager
import com.m3u.data.service.PlayerTrack
import com.m3u.data.service.currentTracks
import com.m3u.data.service.tracks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * In-player options (the Menu key or the options button): audio and subtitle tracks, quality,
 * speed, audio/subtitle sync, picture size, and subtitles from OpenSubtitles. Also the subtitle
 * layer drawn over the video.
 * ---------------------------------------------------------------------------------------------- */

sealed interface SubtitleSearch {
    data object Idle : SubtitleSearch
    data object Searching : SubtitleSearch
    data class Results(val items: List<SubtitleResult>) : SubtitleSearch
    data class Loading(val fileId: Long) : SubtitleSearch
    data class Added(val language: String) : SubtitleSearch
    data class Failed(val failure: OpenSubtitlesFailure) : SubtitleSearch
}

@Immutable
data class PlayerOptionsState(
    val audio: List<PlayerTrack> = emptyList(),
    val subtitles: List<PlayerTrack> = emptyList(),
    val video: List<PlayerTrack> = emptyList(),
    val selectedAudio: PlayerTrack? = null,
    val selectedSubtitle: PlayerTrack? = null,
    val selectedVideo: PlayerTrack? = null,
)

@HiltViewModel
class PlayerOptionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager,
    private val settings: Settings,
    private val secrets: SecretStore,
) : ViewModel() {

    val tracks: StateFlow<PlayerOptionsState> = combine(
        playerManager.tracks,
        playerManager.currentTracks,
    ) { all, current ->
        PlayerOptionsState(
            audio = all[C.TRACK_TYPE_AUDIO].orEmpty(),
            subtitles = all[C.TRACK_TYPE_TEXT].orEmpty(),
            video = all[C.TRACK_TYPE_VIDEO].orEmpty(),
            selectedAudio = current[C.TRACK_TYPE_AUDIO],
            selectedSubtitle = current[C.TRACK_TYPE_TEXT],
            selectedVideo = current[C.TRACK_TYPE_VIDEO],
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlayerOptionsState())

    val playback: StateFlow<PlaybackSettingsState> = settings.data
        .map { prefs ->
            PlaybackSettingsState(
                audioDelayMs = prefs[PreferencesKeys.AUDIO_DELAY_MS] ?: 0,
                subtitleDelayMs = prefs[PreferencesKeys.SUBTITLE_DELAY_MS] ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlaybackSettingsState())

    private val _search = MutableStateFlow<SubtitleSearch>(SubtitleSearch.Idle)
    val search: StateFlow<SubtitleSearch> = _search.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var searchJob: Job? = null

    fun hasOpenSubtitlesKey(): Boolean = secrets.has(SecretName.OpenSubtitles)

    fun chooseTrack(track: PlayerTrack) = playerManager.chooseTrack(track.group, track.index)

    /** Subtitles off, or back to automatic choice for audio and video. */
    fun clearTrack(type: @C.TrackType Int) = playerManager.clearTrack(type)

    fun cycleSpeed() {
        val next = SPEEDS.nextAfter(_speed.value)
        _speed.value = next
        playerManager.updateSpeed(next)
    }

    fun resetSpeed() {
        _speed.value = 1f
    }

    fun nudgeAudioDelay(steps: Int) = viewModelScope.launch {
        settings[PreferencesKeys.AUDIO_DELAY_MS] = (playback.value.audioDelayMs + steps * STEP_MS)
            .coerceIn(-PlaybackSettingsViewModel.MAX_DELAY_MS, PlaybackSettingsViewModel.MAX_DELAY_MS)
    }

    fun nudgeSubtitleDelay(steps: Int) = viewModelScope.launch {
        settings[PreferencesKeys.SUBTITLE_DELAY_MS] = (playback.value.subtitleDelayMs + steps * STEP_MS)
            .coerceIn(-PlaybackSettingsViewModel.MAX_DELAY_MS, PlaybackSettingsViewModel.MAX_DELAY_MS)
    }

    fun resetAudioDelay() = viewModelScope.launch { settings[PreferencesKeys.AUDIO_DELAY_MS] = 0 }

    fun resetSubtitleDelay() = viewModelScope.launch { settings[PreferencesKeys.SUBTITLE_DELAY_MS] = 0 }

    fun searchSubtitles(target: SubtitleTarget) {
        val key = secrets.get(SecretName.OpenSubtitles)
        if (key == null) {
            _search.value = SubtitleSearch.Failed(OpenSubtitlesFailure.NoKey)
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = SubtitleSearch.Searching
            _search.value = try {
                SubtitleSearch.Results(OpenSubtitles.search(key, target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenSubtitlesException) {
                SubtitleSearch.Failed(e.failure)
            } catch (e: Exception) {
                SubtitleSearch.Failed(if (e is IOException) OpenSubtitlesFailure.Offline else OpenSubtitlesFailure.Other(null))
            }
        }
    }

    fun addSubtitle(result: SubtitleResult) {
        val key = secrets.get(SecretName.OpenSubtitles) ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = SubtitleSearch.Loading(result.fileId)
            _search.value = try {
                val file = OpenSubtitles.download(context, key, result)
                playerManager.addSubtitle(
                    uri = Uri.fromFile(file),
                    mimeType = MimeTypes.APPLICATION_SUBRIP,
                    language = result.language.ifBlank { null },
                    label = "OpenSubtitles · ${languageName(result.language)}",
                )
                SubtitleSearch.Added(result.language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenSubtitlesException) {
                SubtitleSearch.Failed(e.failure)
            } catch (e: Exception) {
                SubtitleSearch.Failed(if (e is IOException) OpenSubtitlesFailure.Offline else OpenSubtitlesFailure.Other(null))
            }
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _search.value = SubtitleSearch.Idle
    }

    private companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        const val STEP_MS = 1
    }
}

private const val FOCUS_ATTEMPTS = 10
private const val FOCUS_RETRY_MS = 32L

internal fun languageName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return ""
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return name.replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifBlank { code }
}

@Composable
private fun trackLabel(track: PlayerTrack, position: Int): String {
    val format: Format = track.format
    val parts = mutableListOf<String>()
    format.label?.takeIf { it.isNotBlank() }?.let(parts::add)
    languageName(format.language).takeIf { it.isNotBlank() && parts.none { p -> p.contains(it, true) } }
        ?.let(parts::add)
    when (track.type) {
        C.TRACK_TYPE_AUDIO -> {
            when (format.channelCount) {
                1 -> parts += "Mono"
                2 -> parts += "Stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
            }
            codecName(format.sampleMimeType)?.let(parts::add)
        }
        C.TRACK_TYPE_VIDEO -> {
            if (format.height > 0) parts += "${format.height}p"
            if (format.bitrate > 0) parts += "%.1f Mbps".format(format.bitrate / 1_000_000f)
            codecName(format.sampleMimeType)?.let(parts::add)
        }
        C.TRACK_TYPE_TEXT -> {
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
                parts += stringResource(R.string.dial_options_forced)
            }
            codecName(format.sampleMimeType)?.let(parts::add)
        }
    }
    return parts.joinToString(" · ").ifBlank { stringResource(R.string.dial_options_track, position) }
}

private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
    MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3/MP2"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_VTT -> "WebVTT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    else -> null
}

/**
 * Panel on the right of the player. Up/Down move through it, Left/Right adjust sync rows, OK
 * picks, Back closes it.
 */
@Composable
fun PlayerOptionsPanel(
    live: Boolean,
    subtitleTarget: SubtitleTarget?,
    preferences: DialPreferences,
    onUpdatePreferences: ((DialPreferences) -> DialPreferences) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    statsVisible: Boolean = false,
    onToggleStats: () -> Unit = {},
    skipMarkers: SkipMarkers = SkipMarkers(),
    onUpdateSkipMarkers: (((SkipMarkers) -> SkipMarkers) -> Unit)? = null,
    positionMs: () -> Long = { 0L },
    durationMs: () -> Long = { 0L },
    partyHosting: HostedParty? = null,
    partyGuest: GuestState = GuestState.Idle,
    onStartParty: (() -> Unit)? = null,
    onStopParty: () -> Unit = {},
    onLeaveParty: () -> Unit = {},
    bookmarks: List<Long> = emptyList(),
    onAddBookmark: ((Long) -> Unit)? = null,
    onClearBookmarks: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    viewModel: PlayerOptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.tracks.collectAsStateWithLifecycle()
    val sync by viewModel.playback.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }

    BackHandler {
        if (search != SubtitleSearch.Idle) viewModel.closeSearch() else onClose()
    }
    // The first row, once the list has laid it out; again when the list switches to search.
    LaunchedEffect(search::class) {
        repeat(FOCUS_ATTEMPTS) {
            yield()
            if (runCatching { firstRow.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.08f to TvColors.Background.copy(alpha = 0.92f),
                    1f to TvColors.Background.copy(alpha = 0.97f),
                )
            )
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 40.dp, top = 40.dp, end = 32.dp, bottom = 40.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (search != SubtitleSearch.Idle && subtitleTarget != null) {
                subtitleSearchItems(search, viewModel, firstRow)
                return@LazyColumn
            }

            item { OptionsHeader(stringResource(R.string.dial_options_audio)) }
            if (state.audio.isEmpty()) {
                item {
                    OptionRow(
                        label = stringResource(R.string.dial_options_none),
                        onClick = {},
                        focusRequester = firstRow,
                    )
                }
            }
            state.audio.forEachIndexed { index, track ->
                item(key = "audio-$index") {
                    OptionRow(
                        label = trackLabel(track, index + 1),
                        selected = state.selectedAudio?.let { it.group == track.group && it.index == track.index } == true,
                        onClick = { viewModel.chooseTrack(track) },
                        focusRequester = firstRow.takeIf { index == 0 },
                    )
                }
            }

            item { OptionsHeader(stringResource(R.string.dial_options_subtitles)) }
            item(key = "subtitles-off") {
                OptionRow(
                    label = stringResource(R.string.dial_options_subtitles_off),
                    selected = state.selectedSubtitle == null,
                    onClick = { viewModel.clearTrack(C.TRACK_TYPE_TEXT) },
                )
            }
            state.subtitles.forEachIndexed { index, track ->
                item(key = "text-$index") {
                    OptionRow(
                        label = trackLabel(track, index + 1),
                        selected = state.selectedSubtitle?.let { it.group == track.group && it.index == track.index } == true,
                        onClick = { viewModel.chooseTrack(track) },
                    )
                }
            }
            if (!live && subtitleTarget != null) {
                item(key = "find-subtitles") {
                    OptionRow(
                        label = stringResource(R.string.dial_options_find_subtitles),
                        value = if (viewModel.hasOpenSubtitlesKey()) null
                        else stringResource(R.string.dial_options_needs_key),
                        onClick = { viewModel.searchSubtitles(subtitleTarget) },
                    )
                }
            }

            if (state.video.size > 1) {
                item { OptionsHeader(stringResource(R.string.dial_options_quality)) }
                item(key = "quality-auto") {
                    OptionRow(
                        label = stringResource(R.string.dial_options_quality_auto),
                        onClick = { viewModel.clearTrack(C.TRACK_TYPE_VIDEO) },
                    )
                }
                state.video.forEachIndexed { index, track ->
                    item(key = "video-$index") {
                        OptionRow(
                            label = trackLabel(track, index + 1),
                            selected = state.selectedVideo?.let { it.group == track.group && it.index == track.index } == true,
                            onClick = { viewModel.chooseTrack(track) },
                        )
                    }
                }
            }

            item { OptionsHeader(stringResource(R.string.dial_options_picture_sound)) }
            if (!live) {
                item(key = "speed") {
                    OptionRow(
                        label = stringResource(R.string.dial_options_speed),
                        value = "${speed}x".replace(".0x", "x"),
                        onClick = viewModel::cycleSpeed,
                    )
                }
            }
            item(key = "aspect") {
                OptionRow(
                    label = stringResource(R.string.dial_setting_aspect),
                    value = when (preferences.aspect) {
                        DialAspect.Fit -> stringResource(R.string.dial_value_fit)
                        DialAspect.Stretch -> stringResource(R.string.dial_value_stretch)
                        DialAspect.Zoom -> stringResource(R.string.dial_value_zoom)
                    },
                    onClick = { onUpdatePreferences { it.copy(aspect = DialAspect.entries.nextAfter(it.aspect)) } },
                )
            }
            item(key = "audio-delay") {
                OptionRow(
                    label = stringResource(R.string.dial_playback_audio_delay),
                    value = delayText(sync.audioDelayMs),
                    hint = stringResource(R.string.dial_options_adjust_hint),
                    onClick = { viewModel.resetAudioDelay() },
                    onStep = { viewModel.nudgeAudioDelay(it) },
                )
            }
            item(key = "subtitle-delay") {
                OptionRow(
                    label = stringResource(R.string.dial_playback_subtitle_delay),
                    value = delayText(sync.subtitleDelayMs),
                    hint = stringResource(R.string.dial_options_adjust_hint),
                    onClick = { viewModel.resetSubtitleDelay() },
                    onStep = { viewModel.nudgeSubtitleDelay(it) },
                )
            }
            item(key = "subtitle-size") {
                OptionRow(
                    label = stringResource(R.string.dial_playback_subtitle_size),
                    value = stringResource(R.string.dial_value_percent, preferences.subtitleSizePercent),
                    onClick = {
                        onUpdatePreferences {
                            it.copy(
                                subtitleSizePercent = DialPreferences.SUBTITLE_SIZE_OPTIONS
                                    .nextAfter(it.subtitleSizePercent)
                            )
                        }
                    },
                )
            }
            item(key = "subtitle-colour") {
                OptionRow(
                    label = stringResource(R.string.dial_subtitle_colour),
                    value = stringResource(preferences.subtitleColour.label),
                    onClick = {
                        onUpdatePreferences { it.copy(subtitleColour = SubtitleColour.entries.nextAfter(it.subtitleColour)) }
                    },
                )
            }
            item(key = "subtitle-backdrop") {
                OptionRow(
                    label = stringResource(R.string.dial_subtitle_backdrop),
                    value = stringResource(preferences.subtitleBackdrop.label),
                    onClick = {
                        onUpdatePreferences { it.copy(subtitleBackdrop = SubtitleBackdrop.entries.nextAfter(it.subtitleBackdrop)) }
                    },
                )
            }
            item(key = "subtitle-raise") {
                OptionRow(
                    label = stringResource(R.string.dial_subtitle_raise),
                    value = stringResource(R.string.dial_value_percent, preferences.subtitleRaisePercent),
                    onClick = {
                        onUpdatePreferences {
                            it.copy(subtitleRaisePercent = DialPreferences.SUBTITLE_RAISE_OPTIONS.nextAfter(it.subtitleRaisePercent))
                        }
                    },
                )
            }
            item(key = "stats") {
                OptionRow(
                    label = stringResource(R.string.dial_stats_title),
                    value = stringResource(if (statsVisible) R.string.dial_value_on else R.string.dial_value_off),
                    onClick = onToggleStats,
                )
            }

            // Intro and credits markers, for the series playing.
            if (!live && onUpdateSkipMarkers != null) {
                item { OptionsHeader(stringResource(R.string.dial_skip_section)) }
                item(key = "skip-intro-start") {
                    OptionRow(
                        label = stringResource(R.string.dial_skip_mark_intro_start),
                        value = skipMarkers.introStartMs.takeIf { it >= 0L }?.let(::formatClock),
                        onClick = {
                            val at = positionMs()
                            onUpdateSkipMarkers { markers ->
                                markers.copy(
                                    introStartMs = at,
                                    introEndMs = if (markers.introEndMs <= at) -1L else markers.introEndMs,
                                )
                            }
                        },
                    )
                }
                item(key = "skip-intro-end") {
                    OptionRow(
                        label = stringResource(R.string.dial_skip_mark_intro_end),
                        value = skipMarkers.introEndMs.takeIf { it > 0L }?.let(::formatClock),
                        onClick = {
                            val at = positionMs()
                            onUpdateSkipMarkers { markers ->
                                markers.copy(
                                    introStartMs = if (markers.introStartMs < 0L || markers.introStartMs >= at) 0L else markers.introStartMs,
                                    introEndMs = at,
                                )
                            }
                        },
                    )
                }
                item(key = "skip-credits") {
                    OptionRow(
                        label = stringResource(R.string.dial_skip_mark_credits),
                        value = skipMarkers.creditsFromEndMs.takeIf { it > 0L }
                            ?.let { stringResource(R.string.dial_skip_before_end, formatClock(it)) },
                        onClick = {
                            val fromEnd = durationMs() - positionMs()
                            if (fromEnd > 0L) onUpdateSkipMarkers { it.copy(creditsFromEndMs = fromEnd) }
                        },
                    )
                }
                item(key = "skip-auto") {
                    OptionRow(
                        label = stringResource(R.string.dial_setting_auto_skip),
                        value = stringResource(if (preferences.autoSkip) R.string.dial_value_on else R.string.dial_value_off),
                        onClick = { onUpdatePreferences { it.copy(autoSkip = !it.autoSkip) } },
                    )
                }
                if (!skipMarkers.isEmpty) {
                    item(key = "skip-clear") {
                        OptionRow(
                            label = stringResource(R.string.dial_skip_clear),
                            onClick = { onUpdateSkipMarkers { SkipMarkers() } },
                        )
                    }
                }
            }

            // Bookmarks inside the film: OK jumps to one.
            if (!live && onAddBookmark != null) {
                item { OptionsHeader(stringResource(R.string.dial_bookmarks_title)) }
                item(key = "bookmark-add") {
                    OptionRow(
                        label = stringResource(R.string.dial_bookmarks_add),
                        onClick = { onAddBookmark(positionMs()) },
                    )
                }
                bookmarks.forEach { mark ->
                    item(key = "bookmark-$mark") {
                        OptionRow(
                            label = stringResource(R.string.dial_bookmarks_jump, formatClock(mark)),
                            onClick = { onSeekTo(mark) },
                        )
                    }
                }
                if (bookmarks.isNotEmpty()) {
                    item(key = "bookmark-clear") {
                        OptionRow(label = stringResource(R.string.dial_bookmarks_clear), onClick = onClearBookmarks)
                    }
                }
            }

            // Watch party.
            item { OptionsHeader(stringResource(R.string.dial_party_title)) }
            when {
                partyHosting != null -> item(key = "party-end") {
                    OptionRow(
                        label = stringResource(R.string.dial_party_end),
                        value = partyHosting.code,
                        hint = stringResource(R.string.dial_party_guests, partyHosting.guests),
                        onClick = onStopParty,
                    )
                }
                partyGuest !is GuestState.Idle -> item(key = "party-leave") {
                    OptionRow(
                        label = stringResource(R.string.dial_party_leave),
                        value = (partyGuest as? GuestState.InParty)?.code,
                        onClick = onLeaveParty,
                    )
                }
                onStartParty != null -> item(key = "party-start") {
                    OptionRow(
                        label = stringResource(R.string.dial_party_start),
                        hint = stringResource(R.string.dial_party_start_hint),
                        onClick = onStartParty,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.subtitleSearchItems(
    search: SubtitleSearch,
    viewModel: PlayerOptionsViewModel,
    firstRow: FocusRequester,
) {
    item { OptionsHeader(stringResource(R.string.dial_options_online_subtitles)) }
    when (search) {
        SubtitleSearch.Idle -> Unit
        SubtitleSearch.Searching -> item {
            OptionRow(
                label = stringResource(R.string.dial_options_searching),
                onClick = {},
                focusRequester = firstRow,
            )
        }
        is SubtitleSearch.Loading -> item {
            OptionRow(
                label = stringResource(R.string.dial_options_downloading),
                onClick = {},
                focusRequester = firstRow,
            )
        }
        is SubtitleSearch.Added -> item {
            OptionRow(
                label = stringResource(R.string.dial_options_subtitle_added, languageName(search.language)),
                onClick = viewModel::closeSearch,
                focusRequester = firstRow,
            )
        }
        is SubtitleSearch.Failed -> item {
            OptionRow(
                label = stringResource(
                    when (search.failure) {
                        OpenSubtitlesFailure.NoKey -> R.string.dial_options_error_no_key
                        OpenSubtitlesFailure.Rejected -> R.string.dial_options_error_rejected
                        OpenSubtitlesFailure.QuotaReached -> R.string.dial_options_error_quota
                        OpenSubtitlesFailure.Offline -> R.string.dial_options_error_offline
                        is OpenSubtitlesFailure.Other -> R.string.dial_options_error_other
                    }
                ),
                onClick = viewModel::closeSearch,
                focusRequester = firstRow,
            )
        }
        is SubtitleSearch.Results -> {
            if (search.items.isEmpty()) {
                item {
                    OptionRow(
                        label = stringResource(R.string.dial_options_no_results),
                        onClick = viewModel::closeSearch,
                        focusRequester = firstRow,
                    )
                }
            }
            items(search.items, key = { it.fileId }) { result ->
                OptionRow(
                    label = listOf(languageName(result.language), result.release)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    value = stringResource(R.string.dial_options_downloads, result.downloads),
                    onClick = { viewModel.addSubtitle(result) },
                    focusRequester = firstRow.takeIf { result == search.items.first() },
                )
            }
        }
    }
}

internal val SubtitleColour.label: Int
    get() = when (this) {
        SubtitleColour.White -> R.string.dial_subtitle_colour_white
        SubtitleColour.Yellow -> R.string.dial_subtitle_colour_yellow
        SubtitleColour.Cyan -> R.string.dial_subtitle_colour_cyan
    }

internal val SubtitleBackdrop.label: Int
    get() = when (this) {
        SubtitleBackdrop.Outline -> R.string.dial_subtitle_backdrop_outline
        SubtitleBackdrop.Shadow -> R.string.dial_subtitle_backdrop_shadow
        SubtitleBackdrop.Box -> R.string.dial_subtitle_backdrop_box
        SubtitleBackdrop.None -> R.string.dial_subtitle_backdrop_none
    }

@Composable
private fun OptionsHeader(title: String) {
    Text(
        text = title,
        color = TvColors.Focus,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun OptionRow(
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    hint: String? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    onStep: ((Int) -> Unit)? = null,
) {
    FocusFrame(
        onClick = onClick,
        selected = selected,
        selectionState = selected,
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.02f,
        focusRequester = focusRequester,
        semanticsLabel = listOfNotNull(label, value).joinToString(": "),
        onKey = { event -> onStep?.let { step -> stepperKeys(event, step) } ?: false },
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (focused || selected) TvColors.OnFocus else TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint != null && focused) {
                    Text(
                        text = hint,
                        color = TvColors.OnFocus.copy(alpha = 0.75f),
                        fontFamily = TvFonts.Body,
                        fontSize = 12.sp,
                    )
                }
            }
            if (value != null) {
                Text(
                    text = value,
                    color = if (focused || selected) TvColors.OnFocus else TvColors.Focus,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Subtitles over the video, sized from the Playback settings. */
@Composable
fun SubtitleLayer(
    player: Player,
    sizePercent: Int,
    modifier: Modifier = Modifier,
    colour: SubtitleColour = SubtitleColour.White,
    backdrop: SubtitleBackdrop = SubtitleBackdrop.Outline,
    raisePercent: Int = 8,
) {
    var view by remember { mutableStateOf<SubtitleView?>(null) }
    AndroidView(
        factory = { context ->
            SubtitleView(context).apply {
                setApplyEmbeddedStyles(true)
                setApplyEmbeddedFontSizes(false)
                view = this
            }
        },
        update = { subtitleView ->
            subtitleView.setFractionalTextSize(
                SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizePercent / 100f
            )
            subtitleView.setBottomPaddingFraction(raisePercent / 100f)
            val foreground = when (colour) {
                SubtitleColour.White -> AndroidColor.WHITE
                SubtitleColour.Yellow -> AndroidColor.rgb(255, 221, 0)
                SubtitleColour.Cyan -> AndroidColor.rgb(0, 229, 255)
            }
            subtitleView.setStyle(
                when (backdrop) {
                    SubtitleBackdrop.Outline -> CaptionStyleCompat(
                        foreground, AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE, AndroidColor.BLACK, null,
                    )
                    SubtitleBackdrop.Shadow -> CaptionStyleCompat(
                        foreground, AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, AndroidColor.BLACK, null,
                    )
                    SubtitleBackdrop.Box -> CaptionStyleCompat(
                        foreground, AndroidColor.argb(170, 0, 0, 0), AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_NONE, AndroidColor.TRANSPARENT, null,
                    )
                    SubtitleBackdrop.None -> CaptionStyleCompat(
                        foreground, AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT,
                        CaptionStyleCompat.EDGE_TYPE_NONE, AndroidColor.TRANSPARENT, null,
                    )
                }
            )
        },
        modifier = modifier,
    )
    DisposableEffect(player, view) {
        val target = view ?: return@DisposableEffect onDispose { }
        target.setCues(player.currentCues.cues)
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                target.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            target.setCues(emptyList())
        }
    }
}

/** "Up next" card when an episode ends: counts down, then plays the next one. */
@Composable
fun UpNextCard(
    episode: SeriesEpisode,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsLeft by remember(episode.id) { mutableIntStateOf(UP_NEXT_SECONDS) }
    var cancelled by remember(episode.id) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    val currentOnPlay by rememberUpdatedState(onPlay)
    LaunchedEffect(episode.id, cancelled) {
        if (cancelled) return@LaunchedEffect
        for (attempt in 0 until FOCUS_ATTEMPTS) {
            yield()
            if (runCatching { playFocus.requestFocus() }.isSuccess) break
            delay(FOCUS_RETRY_MS)
        }
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft--
        }
        currentOnPlay()
    }
    if (cancelled) return
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(420.dp)
            .background(TvColors.Background.copy(alpha = 0.92f), HudShape)
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_up_next),
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            text = listOfNotNull(
                episode.episodeNum?.let { stringResource(R.string.dial_up_next_episode, episode.season, it) },
                episode.title.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.dial_up_next_countdown, secondsLeft),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_up_next_play),
                icon = Icons.Rounded.PlayArrow,
                focusRequester = playFocus,
                onClick = onPlay,
            )
            TvActionButton(
                text = stringResource(R.string.dial_action_cancel),
                icon = Icons.Rounded.Close,
                onClick = { cancelled = true },
            )
        }
    }
}

private const val UP_NEXT_SECONDS = 10
