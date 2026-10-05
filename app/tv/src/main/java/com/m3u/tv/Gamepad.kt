package com.m3u.tv

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------------------------------
 * Game controllers. Xbox, PlayStation (DualShock 4 and DualSense), 8BitDo and the Fire TV game
 * controller all work like the remote: A / Cross is OK, B / Circle is Back, X / Square plays or
 * pauses, Y / Triangle and Start / Options open the menu, the shoulder buttons rewind and fast
 * forward, the triggers change channel. The sticks and the D-pad move focus (Android already
 * turns those into arrow keys).
 *
 * Older Fire OS doesn't know the DualSense and reports its buttons as plain numbers (1 = Square,
 * 2 = Cross, 3 = Circle, 4 = Triangle, …), so those are mapped by Sony's layout too.
 *
 * Settings › Devices lists the controllers, shows what each button just did, can swap A and B,
 * and tries a rumble (Fire OS only lets some controllers rumble).
 * ---------------------------------------------------------------------------------------------- */

/** A controller button and the remote key it became (0: not used). */
@Immutable
data class GamepadPress(val button: String, val target: Int, val at: Long)

enum class ControllerLayout { Xbox, PlayStation, Nintendo, EightBitDo, FireTv, Standard }

object Gamepad {
    private const val SONY_VENDOR = 0x054c

    /** Nintendo-style: the right-hand button confirms. */
    @Volatile
    var swapAB: Boolean = false

    /** The button tester has focus: controller buttons are shown there, not acted on. */
    @Volatile
    var testing: Boolean = false

    private val _lastPress = MutableStateFlow<GamepadPress?>(null)
    val lastPress: StateFlow<GamepadPress?> = _lastPress.asStateFlow()

    private val STANDARD = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_BUTTON_THUMBL to KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_BUTTON_THUMBR to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )

    /** Sony's HID button order, for when the system only reports button numbers. */
    private val SONY_NUMBERED = mapOf(
        KeyEvent.KEYCODE_BUTTON_1 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, // Square
        KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_DPAD_CENTER, // Cross
        KeyEvent.KEYCODE_BUTTON_3 to KeyEvent.KEYCODE_BACK, // Circle
        KeyEvent.KEYCODE_BUTTON_4 to KeyEvent.KEYCODE_MENU, // Triangle
        KeyEvent.KEYCODE_BUTTON_5 to KeyEvent.KEYCODE_MEDIA_REWIND, // L1
        KeyEvent.KEYCODE_BUTTON_6 to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, // R1
        KeyEvent.KEYCODE_BUTTON_7 to KeyEvent.KEYCODE_CHANNEL_DOWN, // L2
        KeyEvent.KEYCODE_BUTTON_8 to KeyEvent.KEYCODE_CHANNEL_UP, // R2
        KeyEvent.KEYCODE_BUTTON_9 to KeyEvent.KEYCODE_BACK, // Create / Share
        KeyEvent.KEYCODE_BUTTON_10 to KeyEvent.KEYCODE_MENU, // Options
    )

    /** Other pads that only report numbers: the first four as OK, Back, play and menu. */
    private val GENERIC_NUMBERED = mapOf(
        KeyEvent.KEYCODE_BUTTON_1 to KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_BUTTON_3 to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_BUTTON_4 to KeyEvent.KEYCODE_MENU,
    )

    /** The remote key a controller button stands for, as a copy of [event]; null when it isn't one. */
    fun translate(event: KeyEvent): KeyEvent? {
        val code = swapped(event.keyCode, event.device?.vendorId == SONY_VENDOR)
        val target = STANDARD[code] ?: run {
            if (!KeyEvent.isGamepadButton(code)) return null
            val device = event.device
            if (device?.vendorId == SONY_VENDOR) SONY_NUMBERED[code] else GENERIC_NUMBERED[code]
        }
        if (KeyEvent.isGamepadButton(event.keyCode) && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            _lastPress.value = GamepadPress(
                button = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_"),
                target = target ?: 0,
                at = event.eventTime,
            )
        }
        if (target == null) return null
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            target,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            InputDevice.SOURCE_DPAD,
        )
    }

    private fun swapped(code: Int, sony: Boolean): Int {
        if (!swapAB) return code
        val (confirm, back) = when {
            code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_B
            sony -> KeyEvent.KEYCODE_BUTTON_2 to KeyEvent.KEYCODE_BUTTON_3
            else -> KeyEvent.KEYCODE_BUTTON_1 to KeyEvent.KEYCODE_BUTTON_2
        }
        return when (code) {
            confirm -> back
            back -> confirm
            else -> code
        }
    }

    /** The game controllers connected now (not the remote, not virtual devices). */
    fun controllers(): List<InputDevice> = InputDevice.getDeviceIds().toList()
        .mapNotNull { InputDevice.getDevice(it) }
        .filter { device ->
            !device.isVirtual &&
                (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                    device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        }
        .distinctBy { it.descriptor }

    fun layoutOf(device: InputDevice): ControllerLayout = when (device.vendorId) {
        0x045e -> ControllerLayout.Xbox
        SONY_VENDOR -> ControllerLayout.PlayStation
        0x057e -> ControllerLayout.Nintendo
        0x2dc8 -> ControllerLayout.EightBitDo
        0x1949 -> ControllerLayout.FireTv
        else -> ControllerLayout.Standard
    }

    private fun vibratorOf(device: InputDevice): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) device.vibratorManager.defaultVibrator
        else @Suppress("DEPRECATION") device.vibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    fun canRumble(device: InputDevice): Boolean = vibratorOf(device) != null

    /** A short rumble; false when this controller can't (or the system won't let it). */
    fun rumble(device: InputDevice, ms: Long): Boolean {
        val vibrator = vibratorOf(device) ?: return false
        return runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }.isSuccess
    }

    /** Rumbles every controller that can; true if any did. */
    fun rumbleAll(ms: Long): Boolean = controllers().map { rumble(it, ms) }.any { it }
}
