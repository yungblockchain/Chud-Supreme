package com.m3u.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.m3u.core.foundation.architecture.preferences.BufferProfile
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.SubtitleMode
import com.m3u.core.foundation.architecture.preferences.set
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/* -------------------------------------------------------------------------------------------------
 * Settings > Playback: how the built-in player decodes, buffers, handles HDR/Dolby and subtitles.
 * These live in the shared settings store because the player (data layer) reads them.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class PlaybackSettingsState(
    val tunneling: Boolean = true,
    val dolbyVisionAsHdr10: Boolean = false,
    val audioPassthrough: Boolean = true,
    val preferSoftwareDecoder: Boolean = false,
    val bufferProfile: Int = BufferProfile.BALANCED,
    val subtitleMode: Int = SubtitleMode.FORCED_ONLY,
    val styledSubtitles: Boolean = true,
    val anime4k: Boolean = false,
    val audioDelayMs: Int = 0,
    val subtitleDelayMs: Int = 0,
)

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val secrets: SecretStore,
    private val outputs: AudioOutputMonitor,
) : ViewModel() {

    val state: StateFlow<PlaybackSettingsState> = settings.data
        .map { it.toPlaybackSettings() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlaybackSettingsState())

    val savedSecrets: StateFlow<Set<SecretName>> = secrets.saved

    fun <T> set(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch { settings[key] = value }
    }

    /** The delay is kept for the speakers or headphones in use, too (Settings › Devices). */
    fun setAudioDelay(ms: Int) {
        outputs.rememberDelay(ms)
        set(PreferencesKeys.AUDIO_DELAY_MS, ms)
    }

    fun nudgeAudioDelay(stepMs: Int) = setAudioDelay((state.value.audioDelayMs + stepMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS))

    fun nudgeSubtitleDelay(stepMs: Int) = set(
        PreferencesKeys.SUBTITLE_DELAY_MS,
        (state.value.subtitleDelayMs + stepMs).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS),
    )

    fun saveSecret(name: SecretName, value: String) = secrets.put(name, value)

    fun removeSecret(name: SecretName) = secrets.remove(name)

    companion object {
        const val MAX_DELAY_MS = 10_000
        const val DELAY_STEP_MS = 1
    }
}

private fun Preferences.toPlaybackSettings(): PlaybackSettingsState {
    val defaults = PlaybackSettingsState()
    return PlaybackSettingsState(
        tunneling = this[PreferencesKeys.TUNNELING] ?: defaults.tunneling,
        dolbyVisionAsHdr10 = this[PreferencesKeys.DOLBY_VISION_AS_HDR10] ?: defaults.dolbyVisionAsHdr10,
        audioPassthrough = this[PreferencesKeys.AUDIO_PASSTHROUGH] ?: defaults.audioPassthrough,
        preferSoftwareDecoder = this[PreferencesKeys.PREFER_SOFTWARE_DECODER]
            ?: defaults.preferSoftwareDecoder,
        bufferProfile = this[PreferencesKeys.BUFFER_PROFILE] ?: defaults.bufferProfile,
        subtitleMode = this[PreferencesKeys.SUBTITLE_MODE] ?: defaults.subtitleMode,
        styledSubtitles = this[PreferencesKeys.STYLED_SUBTITLES] ?: defaults.styledSubtitles,
        anime4k = this[PreferencesKeys.ANIME4K] ?: defaults.anime4k,
        audioDelayMs = this[PreferencesKeys.AUDIO_DELAY_MS] ?: defaults.audioDelayMs,
        subtitleDelayMs = this[PreferencesKeys.SUBTITLE_DELAY_MS] ?: defaults.subtitleDelayMs,
    )
}

/** "+150 ms", "−200 ms", "0 ms". */
@Composable
internal fun delayText(ms: Int): String = when {
    ms > 0 -> stringResource(R.string.dial_value_delay_later, ms)
    ms < 0 -> stringResource(R.string.dial_value_delay_earlier, -ms)
    else -> stringResource(R.string.dial_value_delay_none)
}

/** Left/Right on a row nudge a value; returns true when it handled the key. */
internal fun stepperKeys(event: KeyEvent, onStep: (Int) -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) {
        return event.key == Key.DirectionLeft || event.key == Key.DirectionRight
    }
    return when (event.key) {
        Key.DirectionLeft -> {
            onStep(-1)
            true
        }
        Key.DirectionRight -> {
            onStep(1)
            true
        }
        else -> false
    }
}

@Composable
fun PlaybackSettingsScreen(
    preferences: DialPreferences,
    onUpdate: ((DialPreferences) -> DialPreferences) -> Unit,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedSecrets by viewModel.savedSecrets.collectAsStateWithLifecycle()
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    fun onOff(value: Boolean) = if (value) on else off
    val view = LocalView.current
    val fastestHz = remember(view) { view.context.hostActivity()?.let(DisplayModes::fastestRefreshRate) }
    var editingKey by rememberSaveable { mutableStateOf<SecretName?>(null) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { SettingsSection(stringResource(R.string.dial_playback_section_picture)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_anime4k),
                value = onOff(state.anime4k),
                onClick = { viewModel.set(PreferencesKeys.ANIME4K, !state.anime4k) },
            )
        }
        item {
            Text(
                text = stringResource(R.string.dial_playback_anime4k_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_tunneling),
                value = onOff(state.tunneling),
                onClick = { viewModel.set(PreferencesKeys.TUNNELING, !state.tunneling) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_dolby_vision),
                value = stringResource(
                    if (state.dolbyVisionAsHdr10) R.string.dial_value_dv_as_hdr10
                    else R.string.dial_value_dv_native
                ),
                onClick = {
                    viewModel.set(PreferencesKeys.DOLBY_VISION_AS_HDR10, !state.dolbyVisionAsHdr10)
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_hdr),
                value = stringResource(
                    if (state.tunneling) R.string.dial_value_hdr_on
                    else R.string.dial_value_hdr_off
                ),
                onClick = { viewModel.set(PreferencesKeys.TUNNELING, !state.tunneling) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_decoder),
                value = stringResource(
                    if (state.preferSoftwareDecoder) R.string.dial_value_decoder_software
                    else R.string.dial_value_decoder_hardware
                ),
                onClick = {
                    viewModel.set(PreferencesKeys.PREFER_SOFTWARE_DECODER, !state.preferSoftwareDecoder)
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_menu_refresh),
                value = if (preferences.fastMenus) {
                    fastestHz?.let { stringResource(R.string.dial_value_refresh_fastest, it) }
                        ?: on
                } else {
                    stringResource(R.string.dial_value_refresh_tv)
                },
                onClick = { onUpdate { it.copy(fastMenus = !it.fastMenus) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_live_120),
                value = if (preferences.live120) {
                    stringResource(R.string.dial_value_live_120_on)
                } else {
                    stringResource(R.string.dial_value_live_120_off)
                },
                onClick = { onUpdate { it.copy(live120 = !it.live120) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_playback_section_sound)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_surround),
                value = stringResource(
                    if (state.audioPassthrough) R.string.dial_value_surround_passthrough
                    else R.string.dial_value_surround_decode
                ),
                onClick = { viewModel.set(PreferencesKeys.AUDIO_PASSTHROUGH, !state.audioPassthrough) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_audio_delay),
                value = delayText(state.audioDelayMs),
                onClick = { viewModel.setAudioDelay(0) },
                onKey = { event ->
                    stepperKeys(event) { viewModel.nudgeAudioDelay(it * PlaybackSettingsViewModel.DELAY_STEP_MS) }
                },
            )
        }

        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_audio_description),
                value = stringResource(if (preferences.audioDescription) R.string.dial_value_on else R.string.dial_value_off),
                onClick = { onUpdate { it.copy(audioDescription = !it.audioDescription) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_playback_section_subtitles)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_subtitles),
                value = stringResource(
                    when (state.subtitleMode) {
                        SubtitleMode.OFF -> R.string.dial_value_subtitles_off
                        SubtitleMode.ALWAYS -> R.string.dial_value_subtitles_always
                        else -> R.string.dial_value_subtitles_forced
                    }
                ),
                onClick = {
                    viewModel.set(
                        PreferencesKeys.SUBTITLE_MODE,
                        listOf(SubtitleMode.OFF, SubtitleMode.FORCED_ONLY, SubtitleMode.ALWAYS)
                            .nextAfter(state.subtitleMode),
                    )
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_subtitle_size),
                value = stringResource(R.string.dial_value_percent, preferences.subtitleSizePercent),
                onClick = {
                    onUpdate {
                        it.copy(
                            subtitleSizePercent = DialPreferences.SUBTITLE_SIZE_OPTIONS
                                .nextAfter(it.subtitleSizePercent)
                        )
                    }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_styled_subtitles),
                value = onOff(state.styledSubtitles),
                onClick = { viewModel.set(PreferencesKeys.STYLED_SUBTITLES, !state.styledSubtitles) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_subtitle_delay),
                value = delayText(state.subtitleDelayMs),
                onClick = { viewModel.set(PreferencesKeys.SUBTITLE_DELAY_MS, 0) },
                onKey = { event ->
                    stepperKeys(event) { viewModel.nudgeSubtitleDelay(it * PlaybackSettingsViewModel.DELAY_STEP_MS) }
                },
            )
        }
        item {
            SecretRow(
                name = SecretName.OpenSubtitles,
                label = stringResource(R.string.dial_playback_opensubtitles_key),
                hint = stringResource(R.string.dial_playback_opensubtitles_hint),
                saved = SecretName.OpenSubtitles in savedSecrets,
                editing = editingKey == SecretName.OpenSubtitles,
                onEdit = { editingKey = it },
                onSave = viewModel::saveSecret,
                onRemove = viewModel::removeSecret,
            )
        }

        item { SettingsSection(stringResource(R.string.dial_playback_section_streaming)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_buffer),
                value = stringResource(
                    when (state.bufferProfile) {
                        BufferProfile.FAST_START -> R.string.dial_value_buffer_fast
                        BufferProfile.LARGE -> R.string.dial_value_buffer_large
                        else -> R.string.dial_value_buffer_balanced
                    }
                ),
                onClick = {
                    viewModel.set(
                        PreferencesKeys.BUFFER_PROFILE,
                        listOf(BufferProfile.FAST_START, BufferProfile.BALANCED, BufferProfile.LARGE)
                            .nextAfter(state.bufferProfile),
                    )
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_autoplay_next),
                value = onOff(preferences.autoplayNextEpisode),
                onClick = { onUpdate { it.copy(autoplayNextEpisode = !it.autoplayNextEpisode) } },
            )
        }
        item {
            Text(
                text = stringResource(R.string.dial_playback_footnote),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
    }
}

/**
 * A key or token row: shows whether it's set; OK opens a field to type or paste it (the phone
 * page can fill it too), with Save and Remove.
 */
@Composable
internal fun SecretRow(
    name: SecretName,
    label: String,
    hint: String,
    saved: Boolean,
    editing: Boolean,
    onEdit: (SecretName?) -> Unit,
    onSave: (SecretName, String) -> Unit,
    onRemove: (SecretName) -> Unit,
    secret: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingRow(
            label = label,
            value = stringResource(if (saved) R.string.dial_value_key_added else R.string.dial_value_key_missing),
            onClick = { onEdit(if (editing) null else name) },
        )
        if (editing) {
            var draft by remember(name) { mutableStateOf("") }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 820.dp),
            ) {
                DialTextField(
                    label = hint,
                    value = draft,
                    onValueChange = { draft = it },
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    secret = secret,
                    onDone = {
                        if (draft.isNotBlank()) {
                            onSave(name, draft)
                            onEdit(null)
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvActionButton(
                        text = stringResource(R.string.dial_action_save),
                        icon = Icons.Rounded.CheckCircle,
                        enabled = draft.isNotBlank(),
                        onClick = {
                            onSave(name, draft)
                            onEdit(null)
                        },
                    )
                    if (saved) {
                        TvActionButton(
                            text = stringResource(R.string.dial_action_remove),
                            icon = Icons.Rounded.Delete,
                            onClick = {
                                onRemove(name)
                                onEdit(null)
                            },
                        )
                    }
                    TvActionButton(
                        text = stringResource(R.string.dial_action_cancel),
                        icon = Icons.Rounded.Close,
                        onClick = { onEdit(null) },
                    )
                }
            }
        }
    }
}

internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}
