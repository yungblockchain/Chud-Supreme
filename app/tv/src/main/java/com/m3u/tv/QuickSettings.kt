package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent

/* -------------------------------------------------------------------------------------------------
 * Quick settings: hold Menu anywhere (or Y / Triangle on a controller) and the switches people
 * reach for mid-film slide in from the right: night mode, dialogue boost, the sound profile of the
 * speakers or headphones in use, subtitle size, binge mode, duplicate merging, the screensaver.
 * Back closes it.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun QuickSettingsPanel(
    preferences: DialPreferences,
    onUpdate: ((DialPreferences) -> DialPreferences) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    outputs: AudioOutputViewModel = hiltViewModel(),
) {
    val output by outputs.current.collectAsStateWithLifecycle()
    val profile by outputs.profile.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    fun onOff(value: Boolean) = if (value) on else off
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(24.dp),
        modifier = modifier
            .fillMaxHeight()
            .width(520.dp)
            .background(TvColors.Background.copy(alpha = 0.96f))
            // Left and Right would wander onto the screen underneath: the panel keeps them.
            .onPreviewKeyEvent { it.key == Key.DirectionLeft || it.key == Key.DirectionRight }
            .focusGroup(),
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_quick_settings),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_night_mode),
                value = onOff(preferences.nightMode),
                onClick = { onUpdate { it.copy(nightMode = !it.nightMode) } },
                focusRequester = first,
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_dialogue_boost),
                value = onOff(preferences.dialogueBoost),
                onClick = { onUpdate { it.copy(dialogueBoost = !it.dialogueBoost) } },
            )
        }
        item {
            // The sound profile of whatever the sound is going to (TV, soundbar, AirPods…).
            SettingRow(
                label = stringResource(R.string.dial_output_eq_for, outputLabel(output)),
                value = profile.eq.label(),
                onClick = { outputs.update { it.copy(eq = EqPreset.entries.nextAfter(it.eq)) } },
            )
        }
        if (output.kind.personal && AudioEffectsSupport.virtualizer) {
            item {
                SettingRow(
                    label = stringResource(R.string.dial_output_surround),
                    value = onOff(profile.surround),
                    onClick = { outputs.update { it.copy(surround = !it.surround) } },
                )
            }
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_playback_subtitle_size),
                value = "${preferences.subtitleSizePercent}%",
                onClick = {
                    onUpdate { it.copy(subtitleSizePercent = DialPreferences.SUBTITLE_SIZE_OPTIONS.nextAfter(it.subtitleSizePercent)) }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_binge),
                value = onOff(preferences.bingeMode),
                onClick = { onUpdate { it.copy(bingeMode = !it.bingeMode) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_merge_duplicates),
                value = onOff(preferences.mergeDuplicates),
                onClick = { onUpdate { it.copy(mergeDuplicates = !it.mergeDuplicates) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_screensaver),
                value = if (preferences.screensaverMinutes == 0) off else stringResource(R.string.dial_value_minutes, preferences.screensaverMinutes),
                onClick = {
                    onUpdate { it.copy(screensaverMinutes = DialPreferences.SCREENSAVER_OPTIONS.nextAfter(it.screensaverMinutes)) }
                },
            )
        }
        item {
            // The lock says how to undo it on the first press after it's on.
            SettingRow(
                label = stringResource(R.string.dial_remote_lock),
                value = stringResource(R.string.dial_remote_lock_value),
                onClick = {
                    onClose()
                    RemoteLock.lock()
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.dial_quick_settings_hint),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 12.sp,
            )
        }
    }
}
