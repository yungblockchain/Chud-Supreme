package com.m3u.tv

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------------------------------
 * Remote lock (quick settings): while it's on, the app ignores the remote, controllers and the
 * phone page's remote pad, so a toddler can't change the channel or wander off. Up, Up, Down,
 * Down unlocks. Volume and power still work, and so does the Fire TV's Home button (Fire OS keeps
 * that one to itself).
 * ---------------------------------------------------------------------------------------------- */

object RemoteLock {
    private val UNLOCK = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
    )
    private val PASS = setOf(
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
    )
    private const val HINT_EVERY_MS = 4_000L

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val recent = ArrayDeque<Int>()
    private var hintAt = 0L

    fun lock() {
        recent.clear()
        hintAt = 0L
        _locked.value = true
    }

    /**
     * True when [event] was used up by the lock. [onHint] says how to unlock (now and then, not on
     * every press); [onUnlocked] when the sequence was pressed. Called on the main thread.
     */
    fun intercept(event: KeyEvent, onHint: () -> Unit, onUnlocked: () -> Unit): Boolean {
        if (!_locked.value) return false
        if (event.keyCode in PASS) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        recent.addLast(event.keyCode)
        while (recent.size > UNLOCK.size) recent.removeFirst()
        if (recent.size == UNLOCK.size && recent.indices.all { recent[it] == UNLOCK[it] }) {
            recent.clear()
            _locked.value = false
            onUnlocked()
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (hintAt == 0L || now - hintAt > HINT_EVERY_MS) {
            hintAt = now
            onHint()
        }
        return true
    }
}
