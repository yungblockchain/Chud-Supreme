package com.m3u.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------------------------------
 * Remote lock (quick settings): while it's on, the app ignores the remote, controllers and the
 * phone page's remote pad, so a toddler can't change the channel or wander off. Up, Up, Down,
 * Down, Left, Right unlocks (long enough that button-mashing won't find it). Volume and power still work, and so does the Fire TV's Home button (Fire OS keeps
 * that one to itself).
 * ---------------------------------------------------------------------------------------------- */

object RemoteLock {
    private val UNLOCK = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
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

@Composable
fun RemoteLockBadge(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = TvColors.TextPrimary, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.dial_remote_locked_badge),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
    }
}
