package com.m3u.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Immutable

/** A stream to hand to VLC or another installed player. */
@Immutable
data class ExternalPlayback(
    val url: String,
    val title: String,
    /** Where to start, in milliseconds; 0 for the beginning (and for live TV). */
    val positionMs: Long,
    /** The film or episode whose resume position the player's answer updates; null for live. */
    val resumeUrl: String?,
    val player: DialPlayer,
)

/*
 * Outside players. VLC takes the title and a start position, and answers with where playback
 * stopped (https://wiki.videolan.org/Android_Player_Intents/); MX Player and Just Player send
 * "position" back as well.
 */
internal object ExternalPlayers {
    const val VLC_PACKAGE = "org.videolan.vlc"
    private const val VLC_EXTRA_TITLE = "title"
    private const val VLC_EXTRA_FROM_START = "from_start"
    private const val VLC_EXTRA_POSITION = "position"
    private const val VLC_RESULT_POSITION = "extra_position"
    private const val VLC_RESULT_DURATION = "extra_duration"
    private const val OTHER_RESULT_POSITION = "position"
    private const val OTHER_RESULT_DURATION = "duration"

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun intentFor(playback: ExternalPlayback, chooserTitle: String): Intent {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(playback.url), "video/*")
            .putExtra(Intent.EXTRA_TITLE, playback.title)
            .putExtra(VLC_EXTRA_TITLE, playback.title)
        if (playback.positionMs > 0L) {
            view.putExtra(VLC_EXTRA_FROM_START, false)
            view.putExtra(VLC_EXTRA_POSITION, playback.positionMs)
        } else {
            view.putExtra(VLC_EXTRA_FROM_START, true)
        }
        return when (playback.player) {
            DialPlayer.Vlc -> view.setPackage(VLC_PACKAGE)
            DialPlayer.Ask, DialPlayer.BuiltIn -> Intent.createChooser(view, chooserTitle)
        }
    }

    /** Where the outside player stopped and the length of the video, if it said. */
    fun resultOf(data: Intent?): Pair<Long, Long>? {
        data ?: return null
        val position = data.longOrIntExtra(VLC_RESULT_POSITION)
            ?: data.longOrIntExtra(OTHER_RESULT_POSITION)
            ?: return null
        val duration = data.longOrIntExtra(VLC_RESULT_DURATION)
            ?: data.longOrIntExtra(OTHER_RESULT_DURATION)
            ?: 0L
        return position to duration
    }

    private fun Intent.longOrIntExtra(name: String): Long? {
        if (!hasExtra(name)) return null
        val asLong = getLongExtra(name, Long.MIN_VALUE)
        if (asLong != Long.MIN_VALUE) return asLong.takeIf { it >= 0L }
        val asInt = getIntExtra(name, Int.MIN_VALUE)
        return asInt.takeIf { it != Int.MIN_VALUE && it >= 0 }?.toLong()
    }
}
