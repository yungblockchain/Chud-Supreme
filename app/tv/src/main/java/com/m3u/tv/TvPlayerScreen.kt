package com.m3u.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.Timeline
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.LiveTv
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.runtime.withFrameNanos
import java.util.Locale
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import com.m3u.i18n.R.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/*
 * Dial player.
 *
 * Built for a Fire TV remote rather than a touch screen:
 *  - Controls auto-hide after a few seconds; any button brings them back without also
 *    triggering whatever was focused underneath.
 *  - Up/down (and CH+/CH-, next/previous) flip channels like a TV set, with a big channel
 *    number on screen. That number is the one bold thing in the player.
 *  - Play/pause works from the remote's media key at any time.
 *  - Films, series and catch-up use rewind/fast-forward to skip (lengths set in Settings).
 *  - The screen is kept awake while playing, so the Fire TV screensaver no longer
 *    interrupts a stream.
 *  - Sleep timer: off, 30, 60, 90, 120 minutes, then playback stops.
 *  - Hide delay, reversed channel keys, channel numbers/banner and picture size follow Settings.
 */

private const val ZAP_BANNER_MS = 3_500L
private val SLEEP_STEPS_MINUTES = listOf(30, 60, 90, 120)
/** The sleep timer's "end of this film or episode" step. */
private const val SLEEP_END_OF_VIDEO = -1
/** Stops just before the end, so binge mode or autoplay doesn't start the next episode. */
private const val SLEEP_END_MARGIN_MS = 1_500L
private const val SLEEP_FADE_MS = 30_000L
private const val SLEEP_FADE_TICK_MS = 500L
private const val SLEEP_TICK_MS = 30_000L
private const val SLEEP_MIN_VOLUME = 0.05f

/** How long the "+30 s" bubble stays after the last scrub step. */
private const val SCRUB_BUBBLE_MS = 900L
private const val SEGMENT_CHECK_MS = 400L
/** How long a film plays before "no subtitles at all" counts as true (some streams add them late). */
private const val AUTO_SUBTITLE_WAIT_MS = 4_000L
/** A live stream counts as rewindable when the player holds at least this much behind the edge. */
private const val LIVE_SEEK_MIN_MS = 30_000L
/** A live pause longer than this would outrun the player's buffer: Play takes the catch-up route. */
private const val LIVE_PAUSE_BUFFER_MS = 40_000L
private const val SEGMENT_NOTICE_MS = 2_500L

/** How often the "still watching?" clock is checked. */
private const val STILL_WATCHING_CHECK_MS = 30_000L

/** "+30 s", "−1:30", "+12:00". */
private fun scrubLabel(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "\u2212" else "+"
    val seconds = kotlin.math.abs(deltaMs) / 1000
    return if (seconds < 60) "$sign$seconds s" else "$sign${seconds / 60}:${"%02d".format(seconds % 60)}"
}

/**
 * The fast-forward / rewind ramp. A press is one step of 10 s. A hold keeps stepping about five
 * times a second: 10 s steps for the first two seconds, then 30 s, then 60 s after five
 * seconds, then 120 s after ten. Holding for ten seconds therefore covers about five minutes,
 * and twenty seconds about a quarter of an hour.
 */
class HoldScrub {
    private var holdStartedAt = 0L
    private var lastStepAt = 0L
    private var held = false

    /** A key went down (repeat count 0): the hold, if it becomes one, starts now. */
    fun prime(now: Long) {
        holdStartedAt = now
        lastStepAt = now
        held = false
    }

    /**
     * The step to take for this key-down, or null to wait (steps are rate-limited). The first
     * press is [tapMs] (the skip length from Settings); a hold ramps from there.
     */
    fun step(repeatCount: Int, now: Long, tapMs: Long): Long? {
        if (repeatCount == 0) {
            prime(now)
            return tapMs
        }
        held = true
        if (now - lastStepAt < STEP_EVERY_MS) return null
        lastStepAt = now
        val heldFor = now - holdStartedAt
        return when {
            heldFor < 2_000L -> maxOf(tapMs, 10_000L)
            heldFor < 5_000L -> 30_000L
            heldFor < 10_000L -> 60_000L
            else -> 120_000L
        }
    }

    /** On release: true if this was a hold, so the release must not count as a press. */
    fun release(): Boolean {
        val wasHeld = held
        held = false
        return wasHeld
    }

    private companion object {
        const val STEP_EVERY_MS = 200L
    }
}

private val CHANNEL_UP_KEYS = setOf(Key.DirectionUp, Key.ChannelUp, Key.PageUp, Key.MediaNext)
private val CHANNEL_DOWN_KEYS =
    setOf(Key.DirectionDown, Key.ChannelDown, Key.PageDown, Key.MediaPrevious)
private val PLAY_PAUSE_KEYS = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause)

@Composable
fun TvPlayerScreen(
    player: Player?,
    channel: Channel?,
    channelNumber: Int?,
    live: Boolean,
    canZap: Boolean,
    isFavourite: Boolean,
    isPlaying: Boolean,
    playbackState: Int,
    reconnecting: Boolean,
    failed: Boolean,
    guideLine: String? = null,
    preferences: DialPreferences,
    subtitleTarget: SubtitleTarget?,
    onUpdatePreferences: ((DialPreferences) -> DialPreferences) -> Unit,
    onPlayPause: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    onToggleFavourite: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    onMultiview: (() -> Unit)? = null,
    /** Intro and credits markers for the series playing (empty otherwise). */
    skipMarkers: SkipMarkers = SkipMarkers(),
    /** Set when a series episode is playing, so markers can be placed. */
    onUpdateSkipMarkers: (((SkipMarkers) -> SkipMarkers) -> Unit)? = null,
    /** Starts the following episode now (skipping the credits). */
    onNextEpisode: (() -> Unit)? = null,
    partyHosting: HostedParty? = null,
    partyGuest: GuestState = GuestState.Idle,
    onStartParty: (() -> Unit)? = null,
    onStopParty: () -> Unit = {},
    onLeaveParty: () -> Unit = {},
    bookmarks: List<Long> = emptyList(),
    onAddBookmark: ((Long) -> Unit)? = null,
    onClearBookmarks: () -> Unit = {},
    /** This channel's own refresh-rate rule (Default follows Settings). */
    frameRateMode: FrameRateMode = FrameRateMode.Default,
    /** SponsorBlock segments to jump over (YouTube videos). */
    skipSegments: List<SkipSegment> = emptyList(),
    /** Artwork to show in place of a picture (radio, podcasts). */
    artwork: String? = null,
    /**
     * Live TV paused for longer than the player holds: on Play, the channel's catch-up stream is
     * started from the moment of the pause (set for Xtream channels whose programme has catch-up).
     */
    onResumeLiveFrom: ((pausedAtMs: Long) -> Unit)? = null,
    /** Playing a catch-up stream of a live channel: back to the live picture. */
    onBackToLive: (() -> Unit)? = null,
    /** Other copies of this channel (HD, FHD, 4K…), and switching to one. */
    variants: List<Channel> = emptyList(),
    onPlayVariant: (Channel) -> Unit = {},
    /** Counts Menu presses (App handles the key so it can tell a press from a hold). */
    menuPresses: Int = 0,
    /** Bumped when an overlay over the player (quick settings) closes: focus comes back here. */
    refocus: Int = 0,
    /** Live: the channels being zapped through, for the channel list (Left with the controls hidden). */
    zapChannels: List<Channel> = emptyList(),
    /** The card in [zapChannels] that stands for what's playing (merged copies share one). */
    zapCurrentId: Int? = null,
    /** What's on now, by channel id, for the channel list. */
    nowTitles: Map<Int, String> = emptyMap(),
    onOpenChannelList: () -> Unit = {},
    onZapTo: (Channel) -> Unit = {},
    /** A sports channel with the score ticker on: live scores along the top. */
    scoreTicker: Boolean = false,
) {
    val view = LocalView.current
    val playPauseFocusRequester = remember { FocusRequester() }
    val optionsFocusRequester = remember { FocusRequester() }
    var optionsOpen by remember { mutableStateOf(false) }
    var channelListOpen by remember { mutableStateOf(false) }
    var channelListClosed by remember { mutableIntStateOf(0) }
    var restoreOptionsFocus by remember { mutableStateOf(false) }
    val currentOnClose by rememberUpdatedState(onClose)

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var swallowedKey by remember { mutableStateOf<Key?>(null) }
    var zapBannerVisible by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    /** What was playing when "end of this" was picked: anything else playing counts as the end. */
    var sleepItem by remember { mutableStateOf<Int?>(null) }
    val playingItem by rememberUpdatedState(channel?.id)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var videoAspect by remember(player) { mutableFloatStateOf(0f) }
    var videoFrameRate by remember(player) { mutableFloatStateOf(0f) }
    var statsVisible by remember { mutableStateOf(false) }
    var stillWatching by remember { mutableStateOf(false) }
    var lastKeyAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    /** The skip the viewer waved away (Back) — not offered again until it changes. */
    var skipDismissed by remember(player) { mutableStateOf<SkipKind?>(null) }
    val autoSkipped = remember(player) { mutableSetOf<String>() }
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val previousMode = window?.colorMode
        // Let the Fire TV switch the HDMI output into HDR / HDR10+ / Dolby Vision
        // while this surface is showing a stream that carries that metadata.
        window?.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose {
            if (window != null && previousMode != null) window.colorMode = previousMode
        }
    }
    val controlsTimeoutMs = preferences.controlsTimeoutSeconds * 1_000L
    val skipBackMs = preferences.skipBackSeconds * 1_000L
    val skipAheadMs = preferences.skipAheadSeconds * 1_000L
    // TiviMate's "reverse channel switching": up goes to the previous channel instead.
    val onChannelUpKey = if (preferences.invertChannelKeys) onPreviousChannel else onNextChannel
    val onChannelDownKey = if (preferences.invertChannelKeys) onNextChannel else onPreviousChannel

    fun showControls() {
        controlsVisible = true
        interaction++
    }

    fun seekBy(deltaMs: Long) {
        val target = player ?: return
        val length = target.duration
        val next = (target.currentPosition + deltaMs).coerceAtLeast(0L)
        target.seekTo(if (length > 0) next.coerceAtMost(length) else next)
        position = target.currentPosition.coerceAtLeast(0L)
    }

    // Fast forward and rewind: a press skips once; holding keeps skipping, in bigger and bigger
    // steps the longer the hold (10 s, then 30, 60 and 120). One scrub for the buttons and the
    // remote's media keys, so they feel the same.
    val scrub = remember { HoldScrub() }
    var scrubTotalMs by remember { mutableLongStateOf(0L) }
    var scrubShownAt by remember { mutableLongStateOf(0L) }
    fun scrubStep(forward: Boolean, repeatCount: Int) {
        val tap = if (forward) skipAheadMs else skipBackMs
        val step = scrub.step(repeatCount, SystemClock.uptimeMillis(), tap) ?: return
        val delta = if (forward) step else -step
        seekBy(delta)
        scrubTotalMs = if (repeatCount == 0) delta else scrubTotalMs + delta
        scrubShownAt = SystemClock.uptimeMillis()
        showControls()
    }
    /**
     * Key handling for a fast-forward or rewind button. The first press is left to the button's
     * own click (on release); repeats while OK is held step through the ramp, and the release
     * after a hold is swallowed so it doesn't count as a click.
     */
    fun scrubKeys(forward: Boolean): (KeyEvent) -> Boolean = handler@{ event ->
        val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (!confirm || event.type != KeyEventType.KeyDown) return@handler false
        val repeat = event.nativeKeyEvent.repeatCount
        if (repeat == 0) {
            scrub.prime(SystemClock.uptimeMillis())
            false
        } else {
            scrubStep(forward, repeat)
            true
        }
    }
    /** The click of a fast-forward or rewind button: a tap skips, a release after a hold doesn't. */
    fun scrubClick(forward: Boolean) {
        if (scrub.release()) return
        seekBy(if (forward) skipAheadMs else -skipBackMs)
        scrubTotalMs = if (forward) skipAheadMs else -skipBackMs
        scrubShownAt = SystemClock.uptimeMillis()
        showControls()
    }
    LaunchedEffect(scrubShownAt) {
        if (scrubShownAt == 0L) return@LaunchedEffect
        delay(SCRUB_BUBBLE_MS)
        scrubTotalMs = 0L
    }

    fun cycleSleepTimer() {
        // Films and episodes can stop at their own end (before the next one starts).
        val steps = (if (!live && (player?.duration ?: 0L) > 0L) listOf(SLEEP_END_OF_VIDEO) else emptyList()) +
            SLEEP_STEPS_MINUTES
        val current = sleepMinutes
        val nextMinutes = if (current == null) steps.first() else steps.getOrNull(steps.indexOf(current) + 1)
        sleepMinutes = nextMinutes
        sleepItem = channel?.id
        now = System.currentTimeMillis()
        sleepEndsAt = nextMinutes?.takeIf { it > 0 }?.let { now + it * 60_000L }
    }

    BackHandler {
        when {
            stillWatching -> {
                stillWatching = false
                lastKeyAt = SystemClock.uptimeMillis()
                if (!isPlaying) onPlayPause()
            }
            controlsVisible -> controlsVisible = false
            else -> onBack()
        }
    }

    LaunchedEffect(Unit) {
        yield()
        runCatching { playPauseFocusRequester.requestFocus() }
    }

    // Auto-hide, but never while paused: a paused picture with no controls looks frozen.
    LaunchedEffect(restoreOptionsFocus) {
        if (!restoreOptionsFocus) return@LaunchedEffect
        yield()
        // The options button may be hidden in Settings (the Menu key still opens the panel).
        runCatching { optionsFocusRequester.requestFocus() }
            .onFailure { runCatching { playPauseFocusRequester.requestFocus() } }
        restoreOptionsFocus = false
    }

    LaunchedEffect(controlsVisible, interaction, isPlaying, optionsOpen) {
        if (controlsVisible && isPlaying && !optionsOpen) {
            delay(controlsTimeoutMs)
            controlsVisible = false
        }
    }

    LaunchedEffect(channel?.id) {
        if (channel == null || !preferences.showChannelBanner) return@LaunchedEffect
        zapBannerVisible = true
        delay(ZAP_BANNER_MS)
        zapBannerVisible = false
    }

    // Track the video's shape for the picture-size setting.
    DisposableEffect(player) {
        val target = player ?: return@DisposableEffect onDispose { }
        fun update(size: VideoSize) {
            videoAspect = if (size.width > 0 && size.height > 0) {
                size.width * size.pixelWidthHeightRatio / size.height
            } else 0f
        }
        update(target.videoSize)
        videoFrameRate = target.currentTracks.selectedVideoFrameRate()
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = update(videoSize)
            override fun onTracksChanged(tracks: Tracks) {
                videoFrameRate = tracks.selectedVideoFrameRate()
            }
        }
        target.addListener(listener)
        onDispose { target.removeListener(listener) }
    }

    // Auto frame rate (TiviMate's "AFR"): switch the TV to a refresh rate that is a whole multiple
    // of the video's frame rate, so 24/25/30 fps content plays without judder. The TV blanks for
    // a moment when it switches, which is why this is off by default.
    val currentFastMenus by rememberUpdatedState(preferences.fastMenus)
    LaunchedEffect(activity, videoFrameRate, preferences.matchFrameRate, preferences.live120, live, frameRateMode) {
        val host = activity ?: return@LaunchedEffect
        val window = host.window ?: return@LaunchedEffect
        val modeId = when (frameRateMode) {
            FrameRateMode.Off -> 0
            FrameRateMode.Hz60 -> DisplayModes.modeAtLeast(host, 59f)?.modeId ?: 0
            FrameRateMode.Match -> if (videoFrameRate > 0f) bestDisplayModeFor(host, videoFrameRate) else 0
            FrameRateMode.Default -> when {
                live && preferences.live120 -> DisplayModes.modeAtLeast(host, 119f)?.modeId ?: 0
                preferences.matchFrameRate && videoFrameRate > 0f -> bestDisplayModeFor(host, videoFrameRate)
                else -> 0
            }
        }
        val params = window.attributes
        if (params.preferredDisplayModeId != modeId) {
            params.preferredDisplayModeId = modeId
            window.attributes = params
        }
    }
    DisposableEffect(activity) {
        onDispose {
            // Back to the menus' refresh rate.
            activity?.let { DisplayModes.applyMenuMode(it, currentFastMenus) }
        }
    }


    // The sleep timer: the sound fades over the last half minute, then the player closes. "End of
    // this" follows the video's own end, so pausing or seeking moves it too.
    LaunchedEffect(sleepEndsAt, sleepMinutes, player) {
        val untilEnd = sleepMinutes == SLEEP_END_OF_VIDEO
        if (!untilEnd && sleepEndsAt == null) return@LaunchedEffect
        val target = player
        try {
            while (true) {
                now = System.currentTimeMillis()
                val left = if (untilEnd) {
                    val length = target?.duration ?: C.TIME_UNSET
                    when {
                        // The credits were skipped into the next episode: this one is over.
                        playingItem != sleepItem -> 0L
                        target == null || length == C.TIME_UNSET || length <= 0L -> Long.MAX_VALUE
                        else -> length - target.currentPosition - SLEEP_END_MARGIN_MS
                    }
                } else {
                    (sleepEndsAt ?: break) - now
                }
                if (left <= 0L) {
                    sleepEndsAt = null
                    sleepMinutes = null
                    currentOnClose()
                    break
                }
                target?.volume = if (left < SLEEP_FADE_MS) (left.toFloat() / SLEEP_FADE_MS).coerceIn(SLEEP_MIN_VOLUME, 1f) else 1f
                delay(if (untilEnd || left < SLEEP_FADE_MS + SLEEP_TICK_MS) SLEEP_FADE_TICK_MS else minOf(SLEEP_TICK_MS, left - SLEEP_FADE_MS))
            }
        } finally {
            // Cancelled, changed or done: the sound back to normal for whatever plays next.
            target?.volume = 1f
        }
    }

    // Live streams with a rewind window (HLS keeps a few minutes behind the live edge): the seek
    // keys work inside it, and the progress bar shows how far behind live the picture is.
    var liveSeekable by remember(player) { mutableStateOf(false) }
    DisposableEffect(player, live) {
        val target = player ?: return@DisposableEffect onDispose { }
        fun update() {
            liveSeekable = live && target.isCurrentMediaItemSeekable && target.duration >= LIVE_SEEK_MIN_MS
        }
        update()
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = update()
            override fun onPlaybackStateChanged(playbackState: Int) = update()
        }
        target.addListener(listener)
        onDispose { target.removeListener(listener) }
    }
    val seekable = !live || liveSeekable
    // Live TV paused: the clock starts, so Play can pick up the catch-up stream from that moment
    // once the player's own buffer would have run out.
    var pausedLiveAt by remember(player) { mutableLongStateOf(0L) }
    // A real pause (the player ready, told not to play), not a stall or a tune-in.
    val pausedByUser = live && !isPlaying && playbackState == Player.STATE_READY && player?.playWhenReady == false
    LaunchedEffect(pausedByUser) {
        pausedLiveAt = if (pausedByUser) System.currentTimeMillis() else 0L
    }
    fun playPauseOrResume() {
        val resume = onResumeLiveFrom
        val pausedAt = pausedLiveAt
        // How long the player can hold a pause: its rewind window on a seekable stream, else its buffer.
        val hold = if (liveSeekable) ((player?.duration ?: 0L) - 5_000L).coerceAtLeast(LIVE_PAUSE_BUFFER_MS) else LIVE_PAUSE_BUFFER_MS
        if (resume != null && !isPlaying && live && pausedAt > 0L && System.currentTimeMillis() - pausedAt > hold) {
            resume(pausedAt)
        } else {
            onPlayPause()
        }
    }

    // Position and length: for the progress bar while the controls show, and all the time when
    // there are intro/credits markers to watch for.
    val watchMarkers = !skipMarkers.isEmpty
    LaunchedEffect(player, seekable, controlsVisible, watchMarkers) {
        if (!seekable || player == null || !(controlsVisible || watchMarkers)) return@LaunchedEffect
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Skip intro / skip credits. A marker window offers the skip (OK takes it, Back waves it
    // away); with auto-skip on, the jump happens on its own, once per episode.
    val mediaKey = player?.currentMediaItem?.mediaId.orEmpty()
    val skipKind = if (!live && watchMarkers) skipMarkers.skipAt(position, duration) else null
    LaunchedEffect(skipKind) { if (skipKind == null) skipDismissed = null }
    val skipOffered = skipKind?.takeIf { it != skipDismissed && !stillWatching }
    fun takeSkip(kind: SkipKind) {
        when (kind) {
            SkipKind.Intro -> {
                player?.seekTo(skipMarkers.introEndMs)
                position = skipMarkers.introEndMs
            }
            SkipKind.Credits -> {
                val next = onNextEpisode
                if (next != null) next() else player?.let { it.seekTo(it.duration.coerceAtLeast(0L)) }
            }
        }
        skipDismissed = kind
    }
    LaunchedEffect(skipOffered, preferences.autoSkip, mediaKey) {
        val kind = skipOffered ?: return@LaunchedEffect
        if (!preferences.autoSkip) return@LaunchedEffect
        val once = "$mediaKey:${kind.name}"
        if (once in autoSkipped) return@LaunchedEffect
        autoSkipped += once
        takeSkip(kind)
    }

    // SponsorBlock: as playback enters a segment it jumps to the segment's end, once per segment.
    var segmentSkipped by remember { mutableStateOf<String?>(null) }
    val segmentsDone = remember(mediaKey) { mutableSetOf<Long>() }
    LaunchedEffect(player, skipSegments, isPlaying, mediaKey) {
        val target = player ?: return@LaunchedEffect
        if (skipSegments.isEmpty() || !isPlaying) return@LaunchedEffect
        while (true) {
            val at = target.currentPosition
            val segment = skipSegments.firstOrNull { at >= it.startMs && at < it.endMs - 500L && it.startMs !in segmentsDone }
            if (segment != null) {
                segmentsDone += segment.startMs
                target.seekTo(segment.endMs)
                position = segment.endMs
                segmentSkipped = segment.category
            }
            delay(SEGMENT_CHECK_MS)
        }
    }
    LaunchedEffect(segmentSkipped) {
        if (segmentSkipped == null) return@LaunchedEffect
        delay(SEGMENT_NOTICE_MS)
        segmentSkipped = null
    }

    // "Still watching?" after hours of a film or series running untouched.
    val stillWatchingMs = preferences.stillWatchingHours * 3_600_000L
    LaunchedEffect(player, live, stillWatchingMs, isPlaying) {
        if (live || player == null || stillWatchingMs <= 0L || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(STILL_WATCHING_CHECK_MS)
            if (SystemClock.uptimeMillis() - lastKeyAt >= stillWatchingMs && !stillWatching) {
                stillWatching = true
                onPlayPause()
                break
            }
        }
    }

    // Stats for nerds, and the picture at the progress-bar cursor.
    val displayHz = remember(activity) { activity?.let { displayRefreshRate(it) } ?: 0f }
    val stats by rememberPlaybackStats(player, statsVisible, displayHz)

    // Language rules: the preferred audio, and subtitles when the sound is in another language.
    DisposableEffect(player, preferences.audioLanguage, preferences.subtitleLanguage, preferences.foreignAudioSubtitles, preferences.audioDescription) {
        val target = player ?: return@DisposableEffect onDispose { }
        val listener = applyLanguageRules(
            target,
            audio = preferences.audioLanguage,
            subtitles = preferences.subtitleLanguage,
            foreignSubtitles = preferences.foreignAudioSubtitles,
            describe = preferences.audioDescription,
        )
        onDispose { target.removeListener(listener) }
    }
    // A film or episode with no subtitles at all: fetched from OpenSubtitles, if that's switched on.
    val optionsViewModel: PlayerOptionsViewModel = hiltViewModel()
    val context = LocalContext.current
    val subtitleAddedText = stringResource(R.string.dial_subtitles_auto_added)
    LaunchedEffect(player, subtitleTarget, preferences.autoSubtitles, playbackState == Player.STATE_READY) {
        val target = subtitleTarget ?: return@LaunchedEffect
        val current = player ?: return@LaunchedEffect
        if (!preferences.autoSubtitles || live || playbackState != Player.STATE_READY) return@LaunchedEffect
        delay(AUTO_SUBTITLE_WAIT_MS)
        if (current.currentTracks.hasText()) return@LaunchedEffect
        val language = preferences.subtitleLanguage
            .ifEmpty { preferences.audioLanguage }
            .ifEmpty { Locale.getDefault().language }
        optionsViewModel.autoFetch(target, language)
    }
    LaunchedEffect(optionsViewModel) {
        optionsViewModel.autoAdded.collect { name ->
            Toast.makeText(context, subtitleAddedText.format(name), Toast.LENGTH_SHORT).show()
        }
    }
    // "What's going on?": the last couple of minutes of subtitles go to Claude with the title.
    val sceneViewModel: SceneExplainerViewModel = hiltViewModel()
    val sceneAnswer by sceneViewModel.answer.collectAsStateWithLifecycle()
    // Radio: the song now, and its words.
    val streamTitle = if (artwork != null) rememberStreamTitle(player, channel?.title) else null
    var lyrics by remember { mutableStateOf<String?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(streamTitle, preferences.radioLyrics, live) {
        lyrics = null
        val song = streamTitle ?: return@LaunchedEffect
        if (!preferences.radioLyrics || !live) return@LaunchedEffect
        lyricsLoading = true
        lyrics = LrcLib.lyrics(song)
        lyricsLoading = false
    }
    val dialogue = remember(player) { player?.let { RecentDialogue(it) } }
    DisposableEffect(player, dialogue) {
        val target = player ?: return@DisposableEffect onDispose { }
        val listener = dialogue ?: return@DisposableEffect onDispose { }
        target.addListener(listener)
        onDispose { target.removeListener(listener) }
    }
    // An explanation belongs to this video: gone when the player closes or the channel changes.
    DisposableEffect(channel?.id) { onDispose { sceneViewModel.dismiss() } }
    LaunchedEffect(channelListClosed) {
        if (channelListClosed == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playPauseFocusRequester.requestFocus() }
    }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        channelListOpen = false
        withFrameNanos { }
        runCatching { playPauseFocusRequester.requestFocus() }
        showControls()
    }
    // Menu (let go without holding): the options panel.
    val startMenuPresses = remember { menuPresses }
    LaunchedEffect(menuPresses) {
        if (menuPresses == startMenuPresses) return@LaunchedEffect
        channelListOpen = false
        showControls()
        optionsOpen = !optionsOpen
        if (!optionsOpen) restoreOptionsFocus = true
    }
    val sceneNoKey = stringResource(R.string.dial_scene_no_key)
    val sceneFailed = stringResource(R.string.dial_scene_failed)
    val previews = rememberSeekPreviews(player, mode = if (live) SeekPreviewMode.Off else preferences.seekPreviews)

    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        label = "dial-controls-alpha"
    )
    val sleepMinutesLeft = sleepEndsAt?.let { end ->
        (((end - now).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val key = event.key
                val isDown = event.type == KeyEventType.KeyDown
                val firstPress = isDown && event.nativeKeyEvent.repeatCount == 0
                if (isDown) lastKeyAt = SystemClock.uptimeMillis()
                if (event.type == KeyEventType.KeyUp && swallowedKey == key) {
                    swallowedKey = null
                    return@onPreviewKeyEvent true
                }
                // The options panel moves with the arrows like any list. The "still watching?" card
                // keeps Up/Down to itself: the control row underneath is only faded out, not gone.
                if (optionsOpen || channelListOpen) return@onPreviewKeyEvent false
                if (stillWatching) return@onPreviewKeyEvent key == Key.DirectionUp || key == Key.DirectionDown
                // A skip on offer: OK takes it (with the controls hidden), Back waves it away.
                val offered = skipOffered
                if (offered != null && !controlsVisible) {
                    val confirm = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
                    if (confirm || key == Key.Back) {
                        if (firstPress) {
                            if (confirm) takeSkip(offered) else skipDismissed = offered
                            swallowedKey = key
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                when {
                    key == Key.Menu -> {
                        if (firstPress) {
                            showControls()
                            optionsOpen = true
                        }
                        true
                    }
                    canZap && key in CHANNEL_UP_KEYS -> {
                        if (firstPress) onChannelUpKey()
                        true
                    }
                    canZap && key in CHANNEL_DOWN_KEYS -> {
                        if (firstPress) onChannelDownKey()
                        true
                    }
                    key in PLAY_PAUSE_KEYS -> {
                        if (firstPress) {
                            playPauseOrResume()
                            showControls()
                        }
                        true
                    }
                    seekable && (key == Key.MediaFastForward || key == Key.MediaRewind) -> {
                        if (isDown) {
                            scrubStep(forward = key == Key.MediaFastForward, repeatCount = event.nativeKeyEvent.repeatCount)
                        } else {
                            scrub.release()
                        }
                        true
                    }
                    key == Key.Back -> false
                    live && !controlsVisible && key == Key.DirectionLeft && zapChannels.size > 1 -> {
                        // The channel list, over the picture.
                        if (firstPress) {
                            channelListOpen = true
                            onOpenChannelList()
                        }
                        if (isDown) swallowedKey = key
                        true
                    }
                    !controlsVisible -> {
                        // Wake the overlay; don't let this press reach the focused button.
                        if (isDown) {
                            showControls()
                            swallowedKey = key
                        }
                        true
                    }
                    else -> {
                        if (isDown) interaction++
                        false
                    }
                }
            }
    ) {
        if (player != null) {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
            ) {
                val screenAspect = maxWidth / maxHeight
                // Fill the window. A smaller surface leaves a green video-plane
                // in the gaps on Fire TV. Crop fills those gaps instead.
                val surfaceModifier = Modifier.fillMaxSize()
                if (screenAspect > 0f) {
                    (player as? ExoPlayer)?.videoScalingMode =
                        C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                }
                PlayerSurface(
                    player = player,
                    // SurfaceView hands frames straight to the display hardware: needed for HDR10,
                    // HDR10+ and Dolby Vision output, and much lighter on the Firestick at 4K
                    // than upstream's TextureView, which composites every frame on the GPU in SDR.
                    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                    modifier = surfaceModifier
                )
            }
            SubtitleLayer(
                player = player,
                sizePercent = preferences.subtitleSizePercent,
                modifier = Modifier.fillMaxSize(),
                colour = preferences.subtitleColour,
                backdrop = preferences.subtitleBackdrop,
                raisePercent = preferences.subtitleRaisePercent,
            )
        }

        // Radio and podcasts: the artwork where the picture would be, with the song playing now
        // and (for radio) its words beside it.
        if (artwork != null) {
            val showLyrics = live && preferences.radioLyrics && streamTitle != null
            AudioArtwork(
                artwork = artwork,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = if (showLyrics) (-220).dp else 0.dp, y = (-72).dp),
            )
            streamTitle?.let { song ->
                Text(
                    text = song,
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = if (showLyrics) (-220).dp else 0.dp, y = 104.dp)
                        .widthIn(max = 420.dp),
                )
                if (showLyrics) {
                    LyricsPanel(
                        song = song,
                        lyrics = lyrics,
                        loading = lyricsLoading,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 40.dp)
                            .offset(y = (-40).dp),
                    )
                }
            }
        }
        segmentSkipped?.let { category ->
            Text(
                text = stringResource(R.string.dial_sponsor_skipped, category.replace('_', ' ')),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = 120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        // With the controls open, the same state shows under the channel name instead.
        val stateNotice = when {
            reconnecting -> stringResource(R.string.dial_player_reconnecting)
            failed && playbackState == Player.STATE_IDLE -> stringResource(R.string.dial_player_stopped)
            playbackState == Player.STATE_BUFFERING -> stringResource(R.string.dial_player_tuning)
            else -> null
        }
        if (stateNotice != null && !controlsVisible) {
            TuningPill(text = stateNotice, modifier = Modifier.align(Alignment.Center))
        }
        // "+30 s" / "−1:30" while fast-forwarding or rewinding.
        if (scrubTotalMs != 0L) {
            TuningPill(text = scrubLabel(scrubTotalMs), modifier = Modifier.align(Alignment.Center))
        }
        if (statsVisible) {
            PlaybackStatsPanel(
                stats = stats,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(40.dp),
            )
        }
        // Party pill, top right: the host's code, or the guest's sync state.
        val partyLine = when {
            partyHosting != null -> stringResource(R.string.dial_party_hosting_pill, partyHosting.code, partyHosting.guests)
            partyGuest is GuestState.InParty -> stringResource(
                if (partyGuest.synced) R.string.dial_party_guest_synced else R.string.dial_party_guest_syncing,
                partyGuest.code,
            )
            partyGuest is GuestState.Joining -> stringResource(R.string.dial_party_joining, partyGuest.code)
            else -> null
        }
        if (partyLine != null) {
            Text(
                text = partyLine,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        val offeredSkip = skipOffered
        if (offeredSkip != null && !controlsVisible) {
            SkipPrompt(
                kind = offeredSkip,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = 56.dp),
            )
        }
        if (scoreTicker) {
            ScoreTicker(
                visible = !controlsVisible && !channelListOpen && !optionsOpen && !zapBannerVisible &&
                    sceneAnswer == SceneAnswer.Idle,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
        }
        SceneAnswerCard(
            answer = sceneAnswer,
            onDismiss = sceneViewModel::dismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 40.dp),
        )
        if (stillWatching) {
            StillWatchingCard(
                onContinue = {
                    stillWatching = false
                    lastKeyAt = SystemClock.uptimeMillis()
                    if (!isPlaying) onPlayPause()
                    showControls()
                },
                onStop = onClose,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = zapBannerVisible && !controlsVisible && channel != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            ZapBanner(
                channel = channel,
                channelNumber = channelNumber.takeIf { live },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(controlsAlpha)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.9f),
                        )
                    )
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 56.dp, bottom = 40.dp)
            ) {
                NowPlaying(
                    channel = channel,
                    channelNumber = channelNumber.takeIf { live },
                    playbackState = playbackState,
                    notice = stateNotice,
                    guideLine = guideLine,
                )
                if (seekable && duration > 0L) {
                    ProgressLine(
                        position = position,
                        duration = duration,
                        skipBackMs = skipBackMs,
                        skipAheadMs = skipAheadMs,
                        previews = previews,
                        bookmarks = bookmarks,
                        onSeek = { target ->
                            player?.seekTo(target)
                            position = target
                            showControls()
                        },
                        onInteraction = { showControls() },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                ) {
                    for (button in preferences.playerButtons) {
                        when (button) {
                            PlayerButton.PlayPause -> TvIconActionButton(
                                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) {
                                    stringResource(string.tv_action_pause)
                                } else {
                                    stringResource(string.tv_action_play)
                                },
                                onClick = {
                                    playPauseOrResume()
                                    showControls()
                                },
                                focusRequester = playPauseFocusRequester
                            )
                            PlayerButton.ChannelUp -> if (canZap) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.dial_player_next_channel),
                                    onClick = {
                                        onNextChannel()
                                        showControls()
                                    },
                                )
                            }
                            PlayerButton.ChannelDown -> if (canZap) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.dial_player_previous_channel),
                                    onClick = {
                                        onPreviousChannel()
                                        showControls()
                                    },
                                )
                            }
                            PlayerButton.Rewind -> if (seekable) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.FastRewind,
                                    contentDescription = stringResource(R.string.dial_player_rewind, preferences.skipBackSeconds),
                                    onClick = { scrubClick(forward = false) },
                                    onKey = scrubKeys(forward = false),
                                )
                            }
                            PlayerButton.FastForward -> if (seekable) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.FastForward,
                                    contentDescription = stringResource(R.string.dial_player_forward, preferences.skipAheadSeconds),
                                    onClick = { scrubClick(forward = true) },
                                    onKey = scrubKeys(forward = true),
                                )
                            }
                            PlayerButton.StartOver -> if (!live) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.Replay,
                                    contentDescription = stringResource(R.string.dial_player_start_over),
                                    onClick = {
                                        player?.seekTo(0L)
                                        showControls()
                                    },
                                )
                                // Catch-up of a live channel: back to the live picture.
                                onBackToLive?.let { backToLive ->
                                    TvActionButton(
                                        text = stringResource(R.string.dial_player_back_to_live),
                                        icon = Icons.Rounded.LiveTv,
                                        onClick = backToLive,
                                        showTextWhenUnfocused = false,
                                    )
                                }
                            } else if (liveSeekable) {
                                // Behind the live edge after a rewind: jump back to it.
                                TvActionButton(
                                    text = stringResource(R.string.dial_player_back_to_live),
                                    icon = Icons.Rounded.LiveTv,
                                    onClick = {
                                        player?.seekToDefaultPosition()
                                        showControls()
                                    },
                                    showTextWhenUnfocused = false,
                                )
                            }
                            PlayerButton.Favourite -> if (channel != null) {
                                TvIconActionButton(
                                    icon = if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = stringResource(
                                        if (isFavourite) R.string.dial_player_favourite_remove
                                        else R.string.dial_player_favourite_add
                                    ),
                                    onClick = {
                                        onToggleFavourite()
                                        showControls()
                                    },
                                )
                                if (variants.size > 1) {
                                    // Another copy of this channel (HD / FHD / 4K): cycles through them.
                                    TvActionButton(
                                        text = stringResource(R.string.dial_player_other_copy, variants.size),
                                        icon = Icons.Rounded.Hd,
                                        onClick = {
                                            val index = variants.indexOfFirst { it.id == channel.id }
                                            onPlayVariant(variants[(index + 1).mod(variants.size)])
                                        },
                                        showTextWhenUnfocused = false,
                                    )
                                }
                            }
                            PlayerButton.Mini -> onMinimize?.let { minimize ->
                                TvIconActionButton(
                                    icon = Icons.Rounded.PictureInPictureAlt,
                                    contentDescription = stringResource(R.string.dial_player_mini),
                                    onClick = minimize,
                                )
                            }
                            PlayerButton.Multiview -> if (live && onMultiview != null) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.GridView,
                                    contentDescription = stringResource(R.string.dial_multiview_title),
                                    onClick = onMultiview,
                                )
                            }
                            PlayerButton.Options -> TvIconActionButton(
                                icon = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.dial_options_title),
                                onClick = {
                                    optionsOpen = true
                                    showControls()
                                },
                                focusRequester = optionsFocusRequester,
                            )
                            PlayerButton.Stats -> TvIconActionButton(
                                icon = Icons.Rounded.Analytics,
                                contentDescription = stringResource(
                                    if (statsVisible) R.string.dial_stats_hide else R.string.dial_stats_show
                                ),
                                onClick = {
                                    statsVisible = !statsVisible
                                    showControls()
                                },
                            )
                            PlayerButton.Party -> if (onStartParty != null || partyHosting != null || partyGuest !is GuestState.Idle) {
                                TvIconActionButton(
                                    icon = Icons.Rounded.Groups,
                                    contentDescription = when {
                                        partyHosting != null -> stringResource(R.string.dial_party_end)
                                        partyGuest is GuestState.Idle -> stringResource(R.string.dial_party_start)
                                        else -> stringResource(R.string.dial_party_leave)
                                    },
                                    onClick = {
                                        when {
                                            partyHosting != null -> onStopParty()
                                            partyGuest is GuestState.Idle -> onStartParty?.invoke()
                                            else -> onLeaveParty()
                                        }
                                        showControls()
                                    },
                                )
                            }
                            PlayerButton.Sleep -> TvActionButton(
                                text = stringResource(R.string.dial_player_sleep),
                                icon = Icons.Rounded.Bedtime,
                                supportingText = when {
                                    sleepMinutes == SLEEP_END_OF_VIDEO -> stringResource(R.string.dial_player_sleep_end)
                                    sleepMinutesLeft != null -> stringResource(R.string.dial_player_sleep_left, sleepMinutesLeft)
                                    else -> stringResource(R.string.dial_player_sleep_off)
                                },
                                selected = sleepEndsAt != null || sleepMinutes == SLEEP_END_OF_VIDEO,
                                onClick = {
                                    cycleSleepTimer()
                                    showControls()
                                },
                            )
                            PlayerButton.Close -> TvIconActionButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = stringResource(string.tv_action_close_player),
                                onClick = onClose
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (live) {
                            stringResource(R.string.dial_player_hint_live)
                        } else {
                            stringResource(
                                R.string.dial_player_hint_on_demand,
                                preferences.skipBackSeconds,
                                preferences.skipAheadSeconds,
                            )
                        },
                        color = TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        maxLines = 2,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = channelListOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            PlayerChannelList(
                channels = zapChannels,
                currentId = zapCurrentId ?: channel?.id,
                nowTitles = nowTitles,
                onPick = { picked ->
                    channelListOpen = false
                    channelListClosed++
                    if (picked.id != (zapCurrentId ?: channel?.id)) onZapTo(picked)
                },
                onClose = {
                    channelListOpen = false
                    channelListClosed++
                },
            )
        }

        AnimatedVisibility(
            visible = optionsOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlayerOptionsPanel(
                live = live,
                subtitleTarget = subtitleTarget,
                preferences = preferences,
                onUpdatePreferences = onUpdatePreferences,
                onClose = {
                    optionsOpen = false
                    restoreOptionsFocus = true
                    showControls()
                },
                statsVisible = statsVisible,
                onToggleStats = { statsVisible = !statsVisible },
                skipMarkers = skipMarkers,
                onUpdateSkipMarkers = onUpdateSkipMarkers,
                positionMs = { player?.currentPosition?.coerceAtLeast(0L) ?: position },
                durationMs = { player?.duration?.coerceAtLeast(0L) ?: duration },
                partyHosting = partyHosting,
                partyGuest = partyGuest,
                onStartParty = onStartParty,
                onStopParty = onStopParty,
                onLeaveParty = onLeaveParty,
                bookmarks = bookmarks,
                onAddBookmark = onAddBookmark,
                onClearBookmarks = onClearBookmarks,
                onSeekTo = { target ->
                    player?.seekTo(target)
                    position = target
                    optionsOpen = false
                    restoreOptionsFocus = true
                    showControls()
                },
                onExplainScene = if (!live && channel != null) {
                    {
                        optionsOpen = false
                        restoreOptionsFocus = true
                        val episodeLabel = subtitleTarget?.let { target ->
                            if (target.season != null && target.episode != null) "S${target.season} E${target.episode}" else null
                        }
                        sceneViewModel.explain(
                            title = subtitleTarget?.title ?: channel.title,
                            episode = episodeLabel,
                            positionMs = player?.currentPosition ?: position,
                            dialogue = dialogue?.recent().orEmpty(),
                            noKey = sceneNoKey,
                            failed = sceneFailed,
                        )
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun NowPlaying(
    channel: Channel?,
    channelNumber: Int?,
    playbackState: Int,
    notice: String?,
    guideLine: String?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channelNumber != null) {
            ChannelNumber(number = channelNumber, fontSize = 64)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.widthIn(max = 720.dp)
        ) {
            Text(
                text = channel?.title?.title().orEmpty(),
                color = TvColors.TextPrimary,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TvFonts.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = when {
                notice != null -> notice
                playbackState == Player.STATE_READY -> channel?.category?.takeIf { it.isNotBlank() }
                else -> playerStateText(playbackState)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TvColors.TextSecondary,
                    fontSize = 16.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            guideLine?.let { line ->
                Text(
                    text = line,
                    color = TvColors.TextSecondary,
                    fontSize = 15.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ZapBanner(channel: Channel?, channelNumber: Int?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(TvColors.Background.copy(alpha = 0.82f))
            .padding(horizontal = 28.dp, vertical = 18.dp)
    ) {
        if (channelNumber != null) {
            ChannelNumber(number = channelNumber, fontSize = 72)
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Text(
                text = channel?.title?.title().orEmpty(),
                color = TvColors.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = TvFonts.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            channel?.category?.takeIf { it.isNotBlank() }?.let { category ->
                Text(
                    text = category,
                    color = TvColors.TextSecondary,
                    fontSize = 15.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ChannelNumber(number: Int, fontSize: Int) {
    val description = stringResource(R.string.dial_player_channel_number, number)
    Text(
        text = number.toString(),
        color = TvColors.Focus,
        fontFamily = TvFonts.Accent,
        fontSize = fontSize.sp,
        lineHeight = fontSize.sp,
        maxLines = 1,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    )
}

/** A square of artwork, for streams that have sound but no picture (the name is in the bar). */
@Composable
private fun AudioArtwork(artwork: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .requiredSize(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(TvColors.Surface),
    ) {
        AsyncImage(
            model = artwork,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        )
    }
}

@Composable
private fun TuningPill(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TvColors.OnFocus,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(TvColors.Focus)
            .padding(horizontal = 24.dp, vertical = 10.dp)
    )
}

/**
 * The progress bar. With it focused, Left/Right move a cursor along the film without seeking
 * (holding ramps like fast-forward), with a picture of that moment above the bar; OK jumps
 * there. Leaving the bar drops the cursor.
 */
@Composable
private fun ProgressLine(
    position: Long,
    duration: Long,
    skipBackMs: Long,
    skipAheadMs: Long,
    previews: SeekPreviews?,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    bookmarks: List<Long> = emptyList(),
) {
    var focused by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<Long?>(null) }
    val scrub = remember { HoldScrub() }
    val shown = cursor ?: position
    val fraction = (shown.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val playedFraction = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    LaunchedEffect(focused) { if (!focused) cursor = null }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 960.dp)
            .onPreviewKeyEvent { event ->
                if (!focused) return@onPreviewKeyEvent false
                val key = event.key
                val forward = key == Key.DirectionRight
                val backward = key == Key.DirectionLeft
                val confirm = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
                if (event.type != KeyEventType.KeyDown) {
                    if (forward || backward) scrub.release()
                    return@onPreviewKeyEvent forward || backward
                }
                when {
                    forward || backward -> {
                        val tap = if (forward) skipAheadMs else skipBackMs
                        val step = scrub.step(event.nativeKeyEvent.repeatCount, SystemClock.uptimeMillis(), tap)
                            ?: return@onPreviewKeyEvent true
                        val from = cursor ?: position
                        cursor = (if (forward) from + step else from - step).coerceIn(0L, duration)
                        onInteraction()
                        true
                    }
                    confirm -> {
                        val target = cursor
                        if (target != null && event.nativeKeyEvent.repeatCount == 0) {
                            onSeek(target)
                            cursor = null
                        }
                        target != null
                    }
                    key == Key.Back && cursor != null -> {
                        cursor = null
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
        if (focused) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bubbleWidth = 240.dp
                val x = ((maxWidth - bubbleWidth) * fraction).coerceAtLeast(0.dp)
                SeekPreviewBubble(
                    previews = previews,
                    positionMs = shown,
                    label = formatTime(shown),
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .offset(x = x)
                        .width(bubbleWidth),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(playedFraction)
                    .background(if (focused) TvColors.Accent else TvColors.Focus)
            )
            if (cursor != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(Color.White.copy(alpha = 0.35f))
                )
            }
            // Bookmarks as small notches along the bar.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                bookmarks.forEach { mark ->
                    val at = (mark.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * at - 2.dp)
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
            }
        }
        Text(
            text = if (cursor != null) {
                stringResource(R.string.dial_player_seek_hint, formatTime(shown), formatTime(duration))
            } else {
                "${formatTime(position)} / ${formatTime(duration)}"
            },
            color = if (focused) TvColors.TextPrimary else TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun playerStateText(playbackState: Int): String = when (playbackState) {
    Player.STATE_READY -> stringResource(string.feat_channel_playback_state_ready)
    Player.STATE_ENDED -> stringResource(string.feat_channel_playback_state_ended)
    // Idle without an error only lasts a moment, while a stream (re)starts.
    else -> stringResource(R.string.dial_player_tuning)
}

/** Frame rate of the video track being played, or 0 if the stream doesn't say. */
private fun Tracks.selectedVideoFrameRate(): Float {
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            val rate = group.getTrackFormat(index).frameRate
            if (rate > 0f) return rate
        }
    }
    return 0f
}

/**
 * The display mode at the current resolution whose refresh rate is the smallest whole multiple
 * of [fps] (23.976 fps -> 23.976 Hz, 25 fps -> 50 Hz, 29.97 fps -> 59.94 Hz). 0 means "no
 * preference", which leaves the TV where it is.
 */
@Suppress("DEPRECATION")
private fun bestDisplayModeFor(activity: Activity, fps: Float): Int {
    val display = activity.windowManager.defaultDisplay ?: return 0
    val current = display.mode
    val sameResolution = display.supportedModes.filter {
        it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
    }
    for (multiple in 1..4) {
        val target = fps * multiple
        sameResolution
            .firstOrNull { kotlin.math.abs(it.refreshRate - target) < 0.05f * multiple }
            ?.let { return it.modeId }
    }
    return 0
}

/** The refresh rate the TV is running at now, for the stats panel. */
@Suppress("DEPRECATION")
private fun displayRefreshRate(activity: Activity): Float =
    runCatching { activity.windowManager.defaultDisplay?.refreshRate ?: 0f }.getOrDefault(0f)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
