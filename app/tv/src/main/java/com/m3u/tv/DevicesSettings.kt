package com.m3u.tv

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------------------------------
 * Settings › Devices: the speakers or headphones the sound is going to (with that device's own
 * sound profile, virtual surround, volume boost and audio delay), and the game controllers (what
 * each button does, a button tester, A/B swap and a rumble test).
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class ControllerInfo(
    val id: Int,
    val name: String,
    val layout: ControllerLayout,
    val vendorId: Int,
    val productId: Int,
    val canRumble: Boolean,
)

@HiltViewModel
class ControllersViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val input = context.getSystemService(InputManager::class.java)
    private val _controllers = MutableStateFlow(read())
    val controllers: StateFlow<List<ControllerInfo>> = _controllers.asStateFlow()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    init {
        runCatching { input?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper())) }
    }

    /** True when the controller rumbled. */
    fun rumble(id: Int): Boolean = Gamepad.controllers().firstOrNull { it.id == id }?.let { Gamepad.rumble(it, TEST_RUMBLE_MS) } ?: false

    private fun refresh() {
        _controllers.value = read()
    }

    private fun read(): List<ControllerInfo> = runCatching {
        Gamepad.controllers().map { device ->
            ControllerInfo(
                id = device.id,
                name = device.name.orEmpty(),
                layout = Gamepad.layoutOf(device),
                vendorId = device.vendorId,
                productId = device.productId,
                canRumble = Gamepad.canRumble(device),
            )
        }
    }.getOrDefault(emptyList())

    override fun onCleared() {
        runCatching { input?.unregisterInputDeviceListener(listener) }
    }

    private companion object {
        const val TEST_RUMBLE_MS = 400L
    }
}

/** Names for the kinds of sound output, for messages and Settings. */
@Composable
fun outputKindNames(): Map<OutputKind, String> = mapOf(
    OutputKind.Speakers to stringResource(R.string.dial_output_kind_speakers),
    OutputKind.Hdmi to stringResource(R.string.dial_output_kind_hdmi),
    OutputKind.Bluetooth to stringResource(R.string.dial_output_kind_bluetooth),
    OutputKind.Headphones to stringResource(R.string.dial_output_kind_headphones),
    OutputKind.Usb to stringResource(R.string.dial_output_kind_usb),
)

@Composable
fun EqPreset.label(): String = stringResource(
    when (this) {
        EqPreset.Flat -> R.string.dial_eq_flat
        EqPreset.Cinema -> R.string.dial_eq_cinema
        EqPreset.Bass -> R.string.dial_eq_bass
        EqPreset.Vocal -> R.string.dial_eq_vocal
        EqPreset.Treble -> R.string.dial_eq_treble
        EqPreset.Loudness -> R.string.dial_eq_loudness
        EqPreset.AirPods -> R.string.dial_eq_airpods
    }
)

@Composable
private fun ControllerLayout.label(): String = stringResource(
    when (this) {
        ControllerLayout.Xbox -> R.string.dial_controller_layout_xbox
        ControllerLayout.PlayStation -> R.string.dial_controller_layout_playstation
        ControllerLayout.Nintendo -> R.string.dial_controller_layout_nintendo
        ControllerLayout.EightBitDo -> R.string.dial_controller_layout_8bitdo
        ControllerLayout.FireTv -> R.string.dial_controller_layout_firetv
        ControllerLayout.Standard -> R.string.dial_controller_layout_standard
    }
)

@Composable
private fun remoteKeyName(code: Int): String = stringResource(
    when (code) {
        KeyEvent.KEYCODE_DPAD_CENTER -> R.string.dial_pad_ok
        KeyEvent.KEYCODE_BACK -> R.string.dial_pad_back
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> R.string.dial_pad_play_pause
        KeyEvent.KEYCODE_MENU -> R.string.dial_pad_menu
        KeyEvent.KEYCODE_MEDIA_REWIND -> R.string.dial_pad_rewind
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> R.string.dial_pad_forward
        KeyEvent.KEYCODE_CHANNEL_UP -> R.string.dial_pad_channel_up
        KeyEvent.KEYCODE_CHANNEL_DOWN -> R.string.dial_pad_channel_down
        else -> R.string.dial_pad_unused
    }
)

/** The output in use, as people know it: "AirPods Pro · Bluetooth", "TV or soundbar (HDMI)". */
@Composable
fun outputLabel(output: AudioOutput): String {
    val kinds = outputKindNames()
    val kind = kinds.getValue(output.kind)
    return if (output.name.isBlank()) kind else "${output.name} · $kind"
}

@Composable
fun DevicesSettingsScreen(
    dial: DialViewModel = hiltViewModel(),
    outputs: AudioOutputViewModel = hiltViewModel(),
    pads: ControllersViewModel = hiltViewModel(),
) {
    val preferences by dial.preferences.collectAsStateWithLifecycle()
    val onUpdate = dial::updatePreferences
    val output by outputs.current.collectAsStateWithLifecycle()
    val profile by outputs.profile.collectAsStateWithLifecycle()
    val controllers by pads.controllers.collectAsStateWithLifecycle()
    val lastPress by Gamepad.lastPress.collectAsStateWithLifecycle()
    var rumbleResult by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    fun onOff(value: Boolean) = if (value) on else off

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SettingsSection(stringResource(R.string.dial_devices_section_sound)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_output_playing),
                value = outputLabel(output),
                onClick = {},
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_output_eq),
                value = profile.eq.label(),
                onClick = { outputs.update { it.copy(eq = EqPreset.entries.nextAfter(it.eq)) } },
            )
        }
        if (output.kind.personal) {
            item {
                SettingRow(
                    label = stringResource(R.string.dial_output_surround),
                    value = if (!AudioEffectsSupport.virtualizer) stringResource(R.string.dial_output_surround_unavailable)
                    else onOff(profile.surround),
                    onClick = { if (AudioEffectsSupport.virtualizer) outputs.update { it.copy(surround = !it.surround) } },
                )
            }
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_output_boost),
                value = if (profile.boostDb == 0) off else stringResource(R.string.dial_value_db, profile.boostDb),
                onClick = { outputs.update { it.copy(boostDb = OutputProfile.BOOST_OPTIONS.nextAfter(it.boostDb)) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_output_delay),
                value = delayText(profile.delayMs),
                onClick = { outputs.setDelay(0) },
                onKey = { event -> stepperKeys(event) { outputs.nudgeDelay(it * DEVICE_DELAY_STEP_MS) } },
            )
        }
        item { Hint(stringResource(R.string.dial_output_hint)) }
        if (output.kind == OutputKind.Bluetooth) {
            item { Hint(stringResource(R.string.dial_output_spatial_note)) }
        }

        item { SettingsSection(stringResource(R.string.dial_devices_section_controllers)) }
        if (controllers.isEmpty()) {
            item { Hint(stringResource(R.string.dial_controllers_none)) }
        }
        items(controllers, key = { it.id }) { pad ->
            val ids = "%04x:%04x".format(Locale.ROOT, pad.vendorId, pad.productId)
            SettingRow(
                label = pad.name.ifBlank { pad.layout.label() },
                value = when {
                    rumbleResult?.first == pad.id && rumbleResult?.second == true -> stringResource(R.string.dial_controller_rumbled)
                    rumbleResult?.first == pad.id -> stringResource(R.string.dial_controller_no_rumble)
                    else -> stringResource(R.string.dial_controller_value, pad.layout.label(), ids)
                },
                onClick = { rumbleResult = pad.id to pads.rumble(pad.id) },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_controller_last),
                value = lastPress?.let { press -> "${press.button} → ${remoteKeyName(press.target)}" }
                    ?: stringResource(R.string.dial_controller_press),
                onClick = {},
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_controller_swap),
                value = onOff(preferences.swapControllerAB),
                onClick = { onUpdate { it.copy(swapControllerAB = !it.swapControllerAB) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_controller_rumble_reminders),
                value = onOff(preferences.controllerRumble),
                onClick = { onUpdate { it.copy(controllerRumble = !it.controllerRumble) } },
            )
        }
        item { Hint(stringResource(R.string.dial_controller_map_hint)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = TvColors.TextMuted,
        fontFamily = TvFonts.Body,
        fontSize = 13.sp,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}

private const val DEVICE_DELAY_STEP_MS = 10
