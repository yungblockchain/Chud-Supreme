package com.m3u.tv

import android.view.InputDevice
import android.view.KeyEvent

/* -------------------------------------------------------------------------------------------------
 * Game controllers. Xbox, PlayStation (DualShock 4 and DualSense), 8BitDo and the Fire TV game
 * controller all work like the remote: A / Cross is OK, B / Circle is Back, X / Square plays or
 * pauses, Y / Triangle and Start / Options open the menu, the shoulder buttons rewind and fast
 * forward, the triggers change channel. The sticks and the D-pad move focus (Android already
 * turns those into arrow keys).
 *
 * Older Fire OS doesn't know the DualSense and reports its buttons as plain numbers (1 = Square,
 * 2 = Cross, 3 = Circle, 4 = Triangle, …), so those are mapped by Sony's layout too.
 * ---------------------------------------------------------------------------------------------- */

object Gamepad {
    private const val SONY_VENDOR = 0x054c

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
        val code = event.keyCode
        val target = STANDARD[code] ?: run {
            if (!KeyEvent.isGamepadButton(code)) return null
            val device = event.device
            if (device?.vendorId == SONY_VENDOR) SONY_NUMBERED[code] else GENERIC_NUMBERED[code]
        } ?: return null
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
}
