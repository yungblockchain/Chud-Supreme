package com.m3u.tv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/*
 * Dial settings, TiviMate-style: one list, each row shows its current value and OK steps to the next.
 * The settings destination has two tabs: these, and upstream's sources/extensions screen.
 */

/** One tab of the settings destination. */
class SettingsTab(
    val label: String,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

@Composable
fun DialSettingsPane(
    tabs: List<SettingsTab>,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(selectedTab) }
    androidx.compose.runtime.LaunchedEffect(selectedTab) {
        tab = selectedTab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 48.dp, top = 32.dp, end = 48.dp, bottom = 8.dp)
        ) {
            tabs.forEachIndexed { index, item ->
                TvActionButton(
                    text = item.label,
                    icon = item.icon,
                    selected = tab == index,
                    onClick = {
                        tab = index
                        onSelectTab(index)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Crossfade(
                targetState = tab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
                modifier = Modifier.fillMaxSize(),
                label = "settings-tab",
            ) { index ->
                Box(Modifier.fillMaxSize()) {
                    tabs.getOrNull(index)?.content?.invoke()
                }
            }
        }
    }
}

@Composable
fun DialSettingsScreen(
    preferences: DialPreferences,
    onUpdate: ((DialPreferences) -> DialPreferences) -> Unit,
    onClearHistory: () -> Unit,
    pairingCode: String? = null,
) {
    var cleared by remember { mutableStateOf(false) }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    fun onOff(value: Boolean) = if (value) on else off

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { SettingsSection(stringResource(R.string.dial_settings_section_startup)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_startup),
                value = when (preferences.startup) {
                    DialStartup.Home -> stringResource(R.string.dial_value_home)
                    DialStartup.LastChannel -> stringResource(R.string.dial_value_last_channel)
                    DialStartup.Guide -> stringResource(R.string.dial_value_guide)
                },
                onClick = {
                    onUpdate { it.copy(startup = DialStartup.entries.nextAfter(it.startup)) }
                },
            )
        }

        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_launch_animation),
                value = onOff(preferences.launchAnimation),
                onClick = { onUpdate { it.copy(launchAnimation = !it.launchAnimation) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_back_twice_to_exit),
                value = onOff(preferences.backTwiceToExit),
                onClick = { onUpdate { it.copy(backTwiceToExit = !it.backTwiceToExit) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_guide)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_guide_layout),
                value = stringResource(
                    when (preferences.guideLayout) {
                        DialGuideLayout.Grid -> R.string.dial_guide_layout_grid
                        DialGuideLayout.List -> R.string.dial_guide_layout_list
                    }
                ),
                onClick = {
                    onUpdate { it.copy(guideLayout = DialGuideLayout.entries.nextAfter(it.guideLayout)) }
                },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_player)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_player),
                value = stringResource(
                    when (preferences.player) {
                        DialPlayer.BuiltIn -> R.string.dial_value_player_built_in
                        DialPlayer.Vlc -> R.string.dial_value_player_vlc
                        DialPlayer.Ask -> R.string.dial_value_player_ask
                    }
                ),
                onClick = { onUpdate { it.copy(player = DialPlayer.entries.nextAfter(it.player)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_back_in_player),
                value = stringResource(
                    if (preferences.backToMini) R.string.dial_value_mini_player else R.string.dial_value_close_player
                ),
                onClick = { onUpdate { it.copy(backToMini = !it.backToMini) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_controls_timeout),
                value = stringResource(R.string.dial_value_seconds, preferences.controlsTimeoutSeconds),
                onClick = {
                    onUpdate {
                        it.copy(
                            controlsTimeoutSeconds = DialPreferences.CONTROLS_TIMEOUT_OPTIONS
                                .nextAfter(it.controlsTimeoutSeconds)
                        )
                    }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_channel_keys),
                value = stringResource(
                    if (preferences.invertChannelKeys) R.string.dial_value_reversed
                    else R.string.dial_value_normal
                ),
                onClick = { onUpdate { it.copy(invertChannelKeys = !it.invertChannelKeys) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_channel_numbers),
                value = onOff(preferences.showChannelNumbers),
                onClick = { onUpdate { it.copy(showChannelNumbers = !it.showChannelNumbers) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_channel_banner),
                value = onOff(preferences.showChannelBanner),
                onClick = { onUpdate { it.copy(showChannelBanner = !it.showChannelBanner) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_match_frame_rate),
                value = onOff(preferences.matchFrameRate),
                onClick = { onUpdate { it.copy(matchFrameRate = !it.matchFrameRate) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_aspect),
                value = when (preferences.aspect) {
                    DialAspect.Fit -> stringResource(R.string.dial_value_fit)
                    DialAspect.Stretch -> stringResource(R.string.dial_value_stretch)
                    DialAspect.Zoom -> stringResource(R.string.dial_value_zoom)
                },
                onClick = { onUpdate { it.copy(aspect = DialAspect.entries.nextAfter(it.aspect)) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_on_demand)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_resume),
                value = onOff(preferences.resumePlayback),
                onClick = { onUpdate { it.copy(resumePlayback = !it.resumePlayback) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_skip_back),
                value = stringResource(R.string.dial_value_seconds, preferences.skipBackSeconds),
                onClick = {
                    onUpdate {
                        it.copy(skipBackSeconds = DialPreferences.SKIP_BACK_OPTIONS.nextAfter(it.skipBackSeconds))
                    }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_skip_ahead),
                value = stringResource(R.string.dial_value_seconds, preferences.skipAheadSeconds),
                onClick = {
                    onUpdate {
                        it.copy(skipAheadSeconds = DialPreferences.SKIP_AHEAD_OPTIONS.nextAfter(it.skipAheadSeconds))
                    }
                },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_clear_history),
                value = stringResource(if (cleared) R.string.dial_value_cleared else R.string.dial_value_clear),
                onClick = {
                    onClearHistory()
                    cleared = true
                },
            )
        }

        // The phone-remote pairing code lives here rather than on every screen.
        if (pairingCode != null) {
            item { SettingsSection(stringResource(R.string.dial_settings_section_phone_remote)) }
            item {
                SettingRow(
                    label = stringResource(R.string.dial_setting_pairing_code),
                    value = pairingCode,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
internal fun SettingsSection(title: String) {
    Text(
        text = title,
        color = TvColors.TextMuted,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp)
    )
}

@Composable
internal fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false },
) {
    FocusFrame(
        onClick = onClick,
        onKey = onKey,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        semanticsLabel = "$label: $value",
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 820.dp)
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = label,
                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = if (focused) TvColors.OnFocus else TvColors.Focus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                maxLines = 1,
            )
        }
    }
}
