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
import androidx.compose.foundation.lazy.items
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

/** The watch-party controls shown in Settings. */
class PartyControls(
    val hosting: HostedParty?,
    val guest: GuestState,
    val onStartHosting: () -> Unit,
    val onStopHosting: () -> Unit,
    val onJoin: (String) -> Unit,
    val onLeave: () -> Unit,
)

@Composable
fun DialSettingsScreen(
    preferences: DialPreferences,
    onUpdate: ((DialPreferences) -> DialPreferences) -> Unit,
    onClearHistory: () -> Unit,
    pairingCode: String? = null,
    party: PartyControls? = null,
) {
    var cleared by remember { mutableStateOf(false) }
    var partyCode by rememberSaveable { mutableStateOf("") }
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

        item { SettingsSection(stringResource(R.string.dial_settings_section_updates)) }
        item { UpdateRows() }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_screensaver),
                value = if (preferences.screensaverMinutes == 0) off else stringResource(R.string.dial_value_minutes, preferences.screensaverMinutes),
                onClick = {
                    onUpdate { it.copy(screensaverMinutes = DialPreferences.SCREENSAVER_OPTIONS.nextAfter(it.screensaverMinutes)) }
                },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_home)) }
        item {
            Text(
                text = stringResource(R.string.dial_setting_home_rows_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        items(preferences.homeRows, key = { "home-row-${it.id}" }) { row ->
            val hidden = row in preferences.homeRowsHidden
            SettingRow(
                label = row.label(),
                value = if (hidden) stringResource(R.string.dial_value_hidden) else stringResource(R.string.dial_value_shown),
                onClick = {
                    onUpdate { it.copy(homeRowsHidden = if (hidden) it.homeRowsHidden - row else it.homeRowsHidden + row) }
                },
                onKey = { event ->
                    stepperKeys(event) { delta ->
                        onUpdate { prefs ->
                            val order = prefs.homeRows.toMutableList()
                            val from = order.indexOf(row)
                            val to = (from + delta).coerceIn(0, order.lastIndex)
                            if (from >= 0 && to != from) order.add(to, order.removeAt(from))
                            prefs.copy(homeRows = order)
                        }
                    }
                },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_sound_language)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_audio_language),
                value = languageLabel(preferences.audioLanguage),
                onClick = { onUpdate { it.copy(audioLanguage = DialPreferences.LANGUAGE_OPTIONS.nextAfter(it.audioLanguage)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_subtitle_language),
                value = languageLabel(preferences.subtitleLanguage),
                onClick = { onUpdate { it.copy(subtitleLanguage = DialPreferences.LANGUAGE_OPTIONS.nextAfter(it.subtitleLanguage)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_foreign_subtitles),
                value = onOff(preferences.foreignAudioSubtitles),
                onClick = { onUpdate { it.copy(foreignAudioSubtitles = !it.foreignAudioSubtitles) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_auto_subtitles),
                value = onOff(preferences.autoSubtitles),
                onClick = { onUpdate { it.copy(autoSubtitles = !it.autoSubtitles) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_night_mode),
                value = onOff(preferences.nightMode),
                onClick = { onUpdate { it.copy(nightMode = !it.nightMode) } },
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
            Text(
                text = stringResource(R.string.dial_setting_sound_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.widthIn(max = 820.dp),
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
                label = stringResource(R.string.dial_setting_hide_watched),
                value = onOff(preferences.hideWatched),
                onClick = { onUpdate { it.copy(hideWatched = !it.hideWatched) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_live)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_merge_duplicates),
                value = onOff(preferences.mergeDuplicates),
                onClick = { onUpdate { it.copy(mergeDuplicates = !it.mergeDuplicates) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_preferred_quality),
                value = stringResource(
                    when (preferences.preferredQuality) {
                        PreferredQuality.Best -> R.string.dial_quality_best
                        PreferredQuality.Fhd -> R.string.dial_quality_fhd
                        PreferredQuality.Hd -> R.string.dial_quality_hd
                        PreferredQuality.Sd -> R.string.dial_quality_sd
                    }
                ),
                onClick = { onUpdate { it.copy(preferredQuality = PreferredQuality.entries.nextAfter(it.preferredQuality)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_live_badges),
                value = onOff(preferences.liveBadges),
                onClick = { onUpdate { it.copy(liveBadges = !it.liveBadges) } },
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
                label = stringResource(R.string.dial_setting_mini_corner),
                value = stringResource(
                    when (preferences.miniCorner) {
                        MiniCorner.BottomRight -> R.string.dial_corner_bottom_right
                        MiniCorner.BottomLeft -> R.string.dial_corner_bottom_left
                        MiniCorner.TopRight -> R.string.dial_corner_top_right
                        MiniCorner.TopLeft -> R.string.dial_corner_top_left
                    }
                ),
                onClick = { onUpdate { it.copy(miniCorner = MiniCorner.entries.nextAfter(it.miniCorner)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_mini_size),
                value = stringResource(
                    when (preferences.miniSize) {
                        MiniSize.Small -> R.string.dial_size_small
                        MiniSize.Medium -> R.string.dial_size_medium
                        MiniSize.Large -> R.string.dial_size_large
                    }
                ),
                onClick = { onUpdate { it.copy(miniSize = MiniSize.entries.nextAfter(it.miniSize)) } },
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
                label = stringResource(R.string.dial_setting_seek_previews),
                value = stringResource(
                    when (preferences.seekPreviews) {
                        SeekPreviewMode.Off -> R.string.dial_value_off
                        SeekPreviewMode.DirectLinks -> R.string.dial_previews_direct
                        SeekPreviewMode.Always -> R.string.dial_previews_always
                    }
                ),
                onClick = { onUpdate { it.copy(seekPreviews = SeekPreviewMode.entries.nextAfter(it.seekPreviews)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_auto_skip),
                value = onOff(preferences.autoSkip),
                onClick = { onUpdate { it.copy(autoSkip = !it.autoSkip) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_setting_still_watching),
                value = if (preferences.stillWatchingHours == 0) off
                else stringResource(R.string.dial_value_hours, preferences.stillWatchingHours),
                onClick = {
                    onUpdate {
                        it.copy(stillWatchingHours = DialPreferences.STILL_WATCHING_OPTIONS.nextAfter(it.stillWatchingHours))
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

        // Player buttons: OK shows or hides one, Left/Right move it along the row.
        item { SettingsSection(stringResource(R.string.dial_settings_section_player_buttons)) }
        item {
            Text(
                text = stringResource(R.string.dial_player_buttons_hint),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        val shown = preferences.playerButtons
        val all = shown + PlayerButton.entries.filter { it !in shown }
        all.forEach { button ->
            item(key = "button-${button.id}") {
                val visible = button in shown
                SettingRow(
                    label = stringResource(button.label),
                    value = when {
                        !visible -> stringResource(R.string.dial_value_hidden)
                        button.required -> stringResource(R.string.dial_value_always)
                        else -> stringResource(R.string.dial_value_position, shown.indexOf(button) + 1)
                    },
                    onClick = {
                        if (button.required) return@SettingRow
                        onUpdate {
                            it.copy(
                                playerButtons = if (visible) it.playerButtons - button else it.playerButtons + button
                            )
                        }
                    },
                    onKey = { event ->
                        visible && stepperKeys(event) { delta ->
                            onUpdate { prefs ->
                                val order = prefs.playerButtons.toMutableList()
                                val from = order.indexOf(button)
                                if (from >= 0) {
                                    val to = (from + delta).coerceIn(0, order.lastIndex)
                                    order.add(to, order.removeAt(from))
                                }
                                prefs.copy(playerButtons = order)
                            }
                        }
                    },
                )
            }
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_player_buttons_reset),
                value = "",
                onClick = { onUpdate { it.copy(playerButtons = PlayerButton.entries) } },
            )
        }

        // Watch party: host from the player; join here with the host's code.
        if (party != null) {
            item { SettingsSection(stringResource(R.string.dial_party_title)) }
            item {
                Text(
                    text = stringResource(R.string.dial_party_settings_hint),
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            val hosting = party.hosting
            val guest = party.guest
            when {
                hosting != null -> item {
                    SettingRow(
                        label = stringResource(R.string.dial_party_end),
                        value = stringResource(R.string.dial_party_code_guests, hosting.code, hosting.guests),
                        onClick = party.onStopHosting,
                    )
                }
                guest is GuestState.InParty -> item {
                    SettingRow(
                        label = stringResource(R.string.dial_party_leave),
                        value = guest.title,
                        onClick = party.onLeave,
                    )
                }
                guest is GuestState.Joining -> item {
                    SettingRow(
                        label = stringResource(R.string.dial_party_leave),
                        value = stringResource(R.string.dial_party_joining, guest.code),
                        onClick = party.onLeave,
                    )
                }
                else -> {
                    item {
                        SettingRow(
                            label = stringResource(R.string.dial_party_start),
                            value = stringResource(R.string.dial_party_start_value),
                            onClick = party.onStartHosting,
                        )
                    }
                    item {
                        Box(Modifier.widthIn(max = 820.dp)) {
                            DialTextField(
                                label = stringResource(R.string.dial_party_code_label),
                                value = partyCode,
                                onValueChange = { partyCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                                readOnly = false,
                                onDone = { if (partyCode.length == 6) party.onJoin(partyCode) },
                            )
                        }
                    }
                    item {
                        SettingRow(
                            label = stringResource(R.string.dial_party_join),
                            value = when (guest) {
                                is GuestState.Failed -> stringResource(
                                    when (guest.reason) {
                                        PartyFailure.BadCode -> R.string.dial_party_error_code
                                        PartyFailure.NoNetwork -> R.string.dial_party_error_network
                                        PartyFailure.HostNotFound -> R.string.dial_party_error_host
                                        PartyFailure.Rejected -> R.string.dial_party_error_rejected
                                        PartyFailure.NothingPlaying -> R.string.dial_party_error_nothing
                                    }
                                )
                                else -> partyCode
                            },
                            onClick = { if (partyCode.length == 6) party.onJoin(partyCode) },
                        )
                    }
                }
            }
        }

        item { SettingsSection(stringResource(R.string.dial_settings_section_weather)) }
        item { WeatherRows() }

        item { SettingsSection(stringResource(R.string.dial_settings_section_backup)) }
        item { BackupRows() }

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

internal val PlayerButton.label: Int
    get() = when (this) {
        PlayerButton.PlayPause -> R.string.dial_button_play_pause
        PlayerButton.ChannelUp -> R.string.dial_player_next_channel
        PlayerButton.ChannelDown -> R.string.dial_player_previous_channel
        PlayerButton.Rewind -> R.string.dial_button_rewind
        PlayerButton.FastForward -> R.string.dial_button_forward
        PlayerButton.StartOver -> R.string.dial_player_start_over
        PlayerButton.Favourite -> R.string.dial_button_favourite
        PlayerButton.Mini -> R.string.dial_player_mini
        PlayerButton.Multiview -> R.string.dial_multiview_title
        PlayerButton.Options -> R.string.dial_options_title
        PlayerButton.Stats -> R.string.dial_stats_title
        PlayerButton.Party -> R.string.dial_party_title
        PlayerButton.Sleep -> R.string.dial_player_sleep
        PlayerButton.Close -> R.string.dial_button_close
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
    focusRequester: FocusRequester? = null,
) {
    FocusFrame(
        onClick = onClick,
        onKey = onKey,
        focusRequester = focusRequester,
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

@Composable
private fun HomeRow.label(): String = stringResource(
    when (this) {
        HomeRow.BecauseYouWatched -> R.string.dial_home_row_because
        HomeRow.NewEpisodes -> R.string.dial_new_episodes_title
        HomeRow.Anime -> R.string.dial_anime_title
        HomeRow.LastWatched -> R.string.dial_home_row_last_watched
        HomeRow.Trending -> R.string.dial_home_row_trending
        HomeRow.Trakt -> R.string.dial_home_row_trakt
        HomeRow.ContinueWatching -> R.string.dial_continue_title
        HomeRow.Tonight -> R.string.dial_tonight_title
        HomeRow.Missed -> R.string.dial_home_row_missed
        HomeRow.Clubs -> R.string.dial_home_row_clubs
        HomeRow.Doors -> R.string.dial_home_browse
    }
)

/** "Build N is out: install" or "Up to date", with the download's progress while it runs. */
@Composable
fun UpdateRows() {
    val updater: UpdaterViewModel = hiltViewModel()
    val notices: NoticesViewModel = hiltViewModel()
    val state by updater.state.collectAsStateWithLifecycle()
    val latest by notices.updateBuild.collectAsStateWithLifecycle()
    val mine = BuildConfig.CHUD_BUILD
    SettingRow(
        label = if (mine > 0) stringResource(R.string.dial_setting_build, mine) else stringResource(R.string.dial_setting_build_dev),
        value = when (val current = state) {
            is UpdateState.Downloading -> stringResource(R.string.dial_update_downloading, current.percent)
            is UpdateState.Ready -> stringResource(R.string.dial_update_ready)
            is UpdateState.Failed -> stringResource(R.string.dial_update_failed, current.message)
            UpdateState.Idle -> latest?.let { stringResource(R.string.dial_update_available, it) } ?: stringResource(R.string.dial_update_none)
        },
        onClick = {
            when (val current = state) {
                is UpdateState.Failed -> updater.reset()
                is UpdateState.Downloading -> Unit
                is UpdateState.Ready -> updater.open(current.file)
                UpdateState.Idle -> if (latest != null) updater.install()
            }
        },
    )
}

/** "Any" or the language's own name for an ISO code. */
@Composable
private fun languageLabel(code: String): String =
    if (code.isEmpty()) stringResource(R.string.dial_language_any) else languageName(code)
