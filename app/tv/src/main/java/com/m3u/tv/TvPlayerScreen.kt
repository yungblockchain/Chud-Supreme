package com.m3u.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
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

/** How long the "+30 s" bubble stays after the last scrub step. */
private const val SCRUB_BUBBLE_MS = 900L

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

    /** The step to take for this key-down, or null to wait (steps are rate-limited). */
    fun step(repeatCount: Int, now: Long): Long? {
        if (repeatCount == 0) {
            holdStartedAt = now
            lastStepAt = now
            held = false
            return TAP_STEP_MS
        }
        held = true
        if (now - lastStepAt < STEP_EVERY_MS) return null
        lastStepAt = now
        val heldFor = now - holdStartedAt
        return when {
            heldFor < 2_000L -> TAP_STEP_MS
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
        const val TAP_STEP_MS = 10_000L
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
) {
    val view = LocalView.current
    val playPauseFocusRequester = remember { FocusRequester() }
    val optionsFocusRequester = remember { FocusRequester() }
    var optionsOpen by remember { mutableStateOf(false) }
    var restoreOptionsFocus by remember { mutableStateOf(false) }
    val currentOnClose by rememberUpdatedState(onClose)

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var swallowedKey by remember { mutableStateOf<Key?>(null) }
    var zapBannerVisible by remember { mutableStateOf(false) }
    var sleepMinutes by remember { mutableStateOf<Int?>(null) }
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var videoAspect by remember(player) { mutableFloatStateOf(0f) }
    var videoFrameRate by remember(player) { mutableFloatStateOf(0f) }
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
        val step = scrub.step(repeatCount, SystemClock.uptimeMillis()) ?: return
        val delta = if (forward) step else -step
        seekBy(delta)
        scrubTotalMs = if (repeatCount == 0) delta else scrubTotalMs + delta
        scrubShownAt = SystemClock.uptimeMillis()
        showControls()
    }
    /** Key handling for a fast-forward or rewind button: OK held keeps going. */
    fun scrubKeys(forward: Boolean): (KeyEvent) -> Boolean = handler@{ event ->
        val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (!confirm) return@handler false
        when (event.type) {
            KeyEventType.KeyDown -> {
                scrubStep(forward, event.nativeKeyEvent.repeatCount)
                true
            }
            // The release after a hold is not another press.
            KeyEventType.KeyUp -> scrub.release()
            else -> false
        }
    }
    LaunchedEffect(scrubShownAt) {
        if (scrubShownAt == 0L) return@LaunchedEffect
        delay(SCRUB_BUBBLE_MS)
        scrubTotalMs = 0L
    }

    fun cycleSleepTimer() {
        val nextMinutes = when (val current = sleepMinutes) {
            null -> SLEEP_STEPS_MINUTES.first()
            else -> SLEEP_STEPS_MINUTES.firstOrNull { it > current }
        }
        sleepMinutes = nextMinutes
        now = System.currentTimeMillis()
        sleepEndsAt = nextMinutes?.let { now + it * 60_000L }
    }

    BackHandler {
        if (controlsVisible) controlsVisible = false else onBack()
    }

    LaunchedEffect(Unit) {
        yield()
        runCatching { playPauseFocusRequester.requestFocus() }
    }

    // Auto-hide, but never while paused: a paused picture with no controls looks frozen.
    LaunchedEffect(restoreOptionsFocus) {
        if (!restoreOptionsFocus) return@LaunchedEffect
        yield()
        runCatching { optionsFocusRequester.requestFocus() }
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
    LaunchedEffect(activity, videoFrameRate, preferences.matchFrameRate, preferences.live120, live) {
        val host = activity ?: return@LaunchedEffect
        val window = host.window ?: return@LaunchedEffect
        val modeId = when {
            live && preferences.live120 -> DisplayModes.modeAtLeast(host, 119f)?.modeId ?: 0
            preferences.matchFrameRate && videoFrameRate > 0f -> bestDisplayModeFor(host, videoFrameRate)
            else -> 0
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


    LaunchedEffect(sleepEndsAt) {
        val end = sleepEndsAt ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= end) {
                sleepEndsAt = null
                sleepMinutes = null
                currentOnClose()
                break
            }
            delay(minOf(30_000L, end - now))
        }
    }

    LaunchedEffect(player, live, controlsVisible) {
        if (live || player == null || !controlsVisible) return@LaunchedEffect
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

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
                if (event.type == KeyEventType.KeyUp && swallowedKey == key) {
                    swallowedKey = null
                    return@onPreviewKeyEvent true
                }
                // The options panel moves with the arrows like any list.
                if (optionsOpen) return@onPreviewKeyEvent false
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
                            onPlayPause()
                            showControls()
                        }
                        true
                    }
                    !live && (key == Key.MediaFastForward || key == Key.MediaRewind) -> {
                        if (isDown) {
                            scrubStep(forward = key == Key.MediaFastForward, repeatCount = event.nativeKeyEvent.repeatCount)
                        } else {
                            scrub.release()
                        }
                        true
                    }
                    key == Key.Back -> false
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
                if (!live && duration > 0L) {
                    ProgressLine(
                        position = position,
                        duration = duration,
                        skipMs = skipAheadMs,
                        onSeek = { target ->
                            player?.seekTo(target)
                            showControls()
                        },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                ) {
                    TvIconActionButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) {
                            stringResource(string.tv_action_pause)
                        } else {
                            stringResource(string.tv_action_play)
                        },
                        onClick = {
                            onPlayPause()
                            showControls()
                        },
                        focusRequester = playPauseFocusRequester
                    )
                    if (canZap) {
                        TvIconActionButton(
                            icon = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.dial_player_next_channel),
                            onClick = {
                                onNextChannel()
                                showControls()
                            },
                        )
                        TvIconActionButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.dial_player_previous_channel),
                            onClick = {
                                onPreviousChannel()
                                showControls()
                            },
                        )
                    }
                    if (!live) {
                        TvIconActionButton(
                            icon = Icons.Rounded.FastRewind,
                            contentDescription = stringResource(R.string.dial_player_rewind, preferences.skipBackSeconds),
                            onClick = {
                                // OK is handled by scrubKeys; this is for a tap on a touch screen.
                                seekBy(-skipBackMs)
                                showControls()
                            },
                            onKey = scrubKeys(forward = false),
                        )
                        TvIconActionButton(
                            icon = Icons.Rounded.FastForward,
                            contentDescription = stringResource(R.string.dial_player_forward, preferences.skipAheadSeconds),
                            onClick = {
                                seekBy(skipAheadMs)
                                showControls()
                            },
                            onKey = scrubKeys(forward = true),
                        )
                        TvIconActionButton(
                            icon = Icons.Rounded.Replay,
                            contentDescription = stringResource(R.string.dial_player_start_over),
                            onClick = {
                                player?.seekTo(0L)
                                showControls()
                            },
                        )
                    }
                    if (channel != null) {
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
                    }
                    onMinimize?.let { minimize ->
                        TvIconActionButton(
                            icon = Icons.Rounded.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.dial_player_mini),
                            onClick = minimize,
                        )
                    }
                    if (live && onMultiview != null) {
                        TvIconActionButton(
                            icon = Icons.Rounded.GridView,
                            contentDescription = stringResource(R.string.dial_multiview_title),
                            onClick = onMultiview,
                        )
                    }
                    TvIconActionButton(
                        icon = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.dial_options_title),
                        onClick = {
                            optionsOpen = true
                            showControls()
                        },
                        focusRequester = optionsFocusRequester,
                    )
                    TvActionButton(
                        text = stringResource(R.string.dial_player_sleep),
                        icon = Icons.Rounded.Bedtime,
                        supportingText = sleepMinutesLeft
                            ?.let { stringResource(R.string.dial_player_sleep_left, it) }
                            ?: stringResource(R.string.dial_player_sleep_off),
                        selected = sleepEndsAt != null,
                        onClick = {
                            cycleSleepTimer()
                            showControls()
                        },
                    )
                    TvIconActionButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(string.tv_action_close_player),
                        onClick = onClose
                    )
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

@Composable
private fun ProgressLine(
    position: Long,
    duration: Long,
    skipMs: Long,
    onSeek: (Long) -> Unit,
) {
    val fraction = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 960.dp)
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeek((position - skipMs).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        onSeek((position + skipMs).coerceAtMost(duration))
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
    ) {
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
                    .fillMaxWidth(fraction)
                    .background(if (focused) TvColors.Accent else TvColors.Focus)
            )
        }
        Text(
            text = "${formatTime(position)} / ${formatTime(duration)}",
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
