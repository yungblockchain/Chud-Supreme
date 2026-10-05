package com.m3u.tv

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.tv.material3.Text
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * Player extras: pictures along the progress bar, the "stats for nerds" panel, the skip-intro /
 * skip-credits prompt and the "still watching?" check.
 * ---------------------------------------------------------------------------------------------- */

/** A frame every this many seconds is enough for the seek preview; nearer requests share one. */
private const val PREVIEW_BUCKET_MS = 10_000L
private const val PREVIEW_WIDTH = 320
private const val PREVIEW_HEIGHT = 180
private const val PREVIEW_CACHE = 48
private const val PREVIEW_MAX_FAILURES = 3

/**
 * Pulls single frames out of the film being played, for the picture above the progress bar.
 * Android's own frame grabber does the work on a background thread; a stream it can't read (or
 * one that keeps failing) just means no pictures, never a stall. Only the latest request is
 * generated, so holding Right doesn't queue a frame for every step.
 */
class SeekPreviews(private val uri: Uri) {
    private val cache = LruCache<Long, Bitmap>(PREVIEW_CACHE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seek-preview").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val wanted = AtomicLong(-1L)
    private var retriever: MediaMetadataRetriever? = null
    @Volatile private var failures = 0
    @Volatile private var closed = false

    val supported: Boolean = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

    /** The cached picture nearest [positionMs], if one has been made already. */
    fun cached(positionMs: Long): Bitmap? = cache.get(bucketOf(positionMs))

    /** Asks for the picture at [positionMs]; [onReady] runs on the main thread when it exists. */
    fun request(positionMs: Long, onReady: (Long, Bitmap) -> Unit) {
        if (!supported || closed || failures >= PREVIEW_MAX_FAILURES) return
        val bucket = bucketOf(positionMs)
        cache.get(bucket)?.let {
            onReady(bucket, it)
            return
        }
        wanted.set(bucket)
        runCatching {
            executor.execute {
                if (closed || wanted.get() != bucket) return@execute
                val frame = grab(bucket) ?: return@execute
                cache.put(bucket, frame)
                main.post { if (!closed) onReady(bucket, frame) }
            }
        }
    }

    fun close() {
        closed = true
        runCatching {
            executor.execute {
                runCatching { retriever?.release() }
                retriever = null
            }
            executor.shutdown()
        }
        cache.evictAll()
    }

    private fun grab(bucket: Long): Bitmap? = runCatching {
        val source = retriever ?: MediaMetadataRetriever().also {
            it.setDataSource(uri.toString(), mapOf("User-Agent" to USER_AGENT))
            retriever = it
        }
        val timeUs = bucket * 1_000L
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            source.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW_WIDTH, PREVIEW_HEIGHT,
            )
        } else {
            source.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                Bitmap.createScaledBitmap(full, PREVIEW_WIDTH, PREVIEW_WIDTH * full.height / full.width, true)
            }
        }
        if (frame == null) failures++ else failures = 0
        frame
    }.getOrElse {
        failures++
        runCatching { retriever?.release() }
        retriever = null
        null
    }

    private fun bucketOf(positionMs: Long): Long = (positionMs / PREVIEW_BUCKET_MS) * PREVIEW_BUCKET_MS

    private companion object {
        const val USER_AGENT = "Chud Supreme"
    }
}

/** Provider streams (Xtream's /movie/ and /series/ paths) are usually limited to one connection. */
private fun Uri.isProviderStream(): Boolean {
    val path = path.orEmpty()
    return path.contains("/movie/") || path.contains("/series/") || path.contains("/live/")
}

/** A [SeekPreviews] for what the player is playing now; closed when that changes. */
@Composable
fun rememberSeekPreviews(player: Player?, mode: SeekPreviewMode): SeekPreviews? {
    val uri = player?.currentMediaItem?.localConfiguration?.uri
    val previews = remember(uri, mode) {
        when {
            uri == null || mode == SeekPreviewMode.Off -> null
            mode == SeekPreviewMode.DirectLinks && uri.isProviderStream() -> null
            else -> SeekPreviews(uri).takeIf { it.supported }
        }
    }
    DisposableEffect(previews) {
        onDispose { previews?.close() }
    }
    return previews
}

/** The picture at the progress-bar cursor, with its time underneath. */
@Composable
fun SeekPreviewBubble(
    previews: SeekPreviews?,
    positionMs: Long,
    label: String,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableStateOf<Pair<Long, Bitmap>?>(null) }
    LaunchedEffect(previews, positionMs) {
        val source = previews ?: return@LaunchedEffect
        source.cached(positionMs)?.let { frame = (positionMs / PREVIEW_BUCKET_MS) to it }
        // A short pause so a held key doesn't ask for a frame per step.
        delay(PREVIEW_DEBOUNCE_MS)
        source.request(positionMs) { bucket, bitmap -> frame = bucket to bitmap }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        val current = frame
        if (previews != null) {
            Box(
                modifier = Modifier
                    .width(240.dp)
                    .height(135.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f)),
            ) {
                if (current != null) {
                    Image(
                        bitmap = current.second.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = label,
            color = TvColors.OnFocus,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(TvColors.Focus)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

private const val PREVIEW_DEBOUNCE_MS = 250L

/* ------------------------------------------------------------------------------ stats panel */

@Immutable
data class PlaybackStats(
    val video: Format? = null,
    val audio: Format? = null,
    val decoder: String? = null,
    val audioDecoder: String? = null,
    val bitrateEstimate: Long = 0L,
    val bufferedMs: Long = 0L,
    val droppedFrames: Int = 0,
    val renderedFrames: Int = 0,
    val speed: Float = 1f,
    val host: String? = null,
    val displayHz: Float = 0f,
)

/** Reads the player once a second while [active]; dropped frames and bandwidth come from events. */
@Composable
fun rememberPlaybackStats(player: Player?, active: Boolean, displayHz: Float): State<PlaybackStats> {
    val stats = remember { mutableStateOf(PlaybackStats()) }
    var dropped by remember(player) { mutableIntStateOf(0) }
    var bitrate by remember(player) { mutableStateOf(0L) }
    var decoder by remember(player) { mutableStateOf<String?>(null) }
    var audioDecoder by remember(player) { mutableStateOf<String?>(null) }
    DisposableEffect(player, active) {
        val exo = player as? ExoPlayer
        if (exo == null || !active) return@DisposableEffect onDispose { }
        val listener = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                dropped += droppedFrames
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long,
            ) {
                bitrate = bitrateEstimate
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                decoder = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }
        }
        exo.addAnalyticsListener(listener)
        onDispose { exo.removeAnalyticsListener(listener) }
    }
    LaunchedEffect(player, active, displayHz) {
        val exo = player as? ExoPlayer
        if (exo == null || !active) return@LaunchedEffect
        while (true) {
            val counters = runCatching { exo.videoDecoderCounters }.getOrNull()
            stats.value = PlaybackStats(
                video = exo.videoFormat,
                audio = exo.audioFormat,
                decoder = decoder,
                audioDecoder = audioDecoder,
                bitrateEstimate = bitrate,
                bufferedMs = (exo.bufferedPosition - exo.currentPosition).coerceAtLeast(0L),
                droppedFrames = counters?.droppedBufferCount ?: dropped,
                renderedFrames = counters?.renderedOutputBufferCount ?: 0,
                speed = exo.playbackParameters.speed,
                host = exo.currentMediaItem?.localConfiguration?.uri?.host,
                displayHz = displayHz,
            )
            delay(1_000L)
        }
    }
    return stats
}

/** The "stats for nerds" panel, top left, in a monospaced face so the numbers line up. */
@Composable
fun PlaybackStatsPanel(stats: PlaybackStats, modifier: Modifier = Modifier) {
    val video = stats.video
    val audio = stats.audio
    val lines = buildList {
        add(
            stringResource(R.string.dial_stats_video).padEnd(STAT_LABEL) + listOfNotNull(
                video?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" },
                video?.takeIf { it.frameRate > 0f }?.let { "%.2f fps".format(it.frameRate) },
                video?.sampleMimeType?.let { codecLabel(it) },
                video?.takeIf { it.bitrate > 0 }?.let { "%.1f Mbps".format(it.bitrate / 1_000_000f) },
            ).joinToString(" · ").ifEmpty { "—" }
        )
        add(
            stringResource(R.string.dial_stats_audio).padEnd(STAT_LABEL) + listOfNotNull(
                audio?.sampleMimeType?.let { codecLabel(it) },
                audio?.takeIf { it.channelCount > 0 }?.let { "${it.channelCount} ch" },
                audio?.takeIf { it.sampleRate > 0 }?.let { "${it.sampleRate / 1000} kHz" },
                audio?.takeIf { it.bitrate > 0 }?.let { "${it.bitrate / 1000} kbps" },
            ).joinToString(" · ").ifEmpty { "—" }
        )
        add(stringResource(R.string.dial_stats_decoder).padEnd(STAT_LABEL) + (stats.decoder ?: "—"))
        if (stats.audioDecoder != null) add(stringResource(R.string.dial_stats_audio_decoder).padEnd(STAT_LABEL) + stats.audioDecoder)
        add(
            stringResource(R.string.dial_stats_network).padEnd(STAT_LABEL) +
                (if (stats.bitrateEstimate > 0) "%.1f Mbps".format(stats.bitrateEstimate / 1_000_000f) else "—")
        )
        add(stringResource(R.string.dial_stats_buffer).padEnd(STAT_LABEL) + "%.1f s".format(stats.bufferedMs / 1000f))
        add(stringResource(R.string.dial_stats_dropped).padEnd(STAT_LABEL) + "${stats.droppedFrames} / ${stats.renderedFrames}")
        if (stats.displayHz > 0f) add(stringResource(R.string.dial_stats_display).padEnd(STAT_LABEL) + "%.2f Hz".format(stats.displayHz))
        if (stats.speed != 1f) add(stringResource(R.string.dial_stats_speed).padEnd(STAT_LABEL) + "${stats.speed}x")
        if (stats.host != null) add(stringResource(R.string.dial_stats_server).padEnd(STAT_LABEL) + stats.host)
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .widthIn(max = 520.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_stats_title),
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private const val STAT_LABEL = 11

private fun codecLabel(mime: String): String = when (mime) {
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_MPEG_L2 -> "MP2"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
    else -> mime.substringAfter('/')
}

/* -------------------------------------------------------------------------------- skipping */

/** What the skip prompt offers right now. */
enum class SkipKind { Intro, Credits }

/** Which skip applies at [positionMs] of a [durationMs]-long episode, or null. */
fun SkipMarkers.skipAt(positionMs: Long, durationMs: Long): SkipKind? = when {
    hasIntro && positionMs >= introStartMs && positionMs < introEndMs - SKIP_GRACE_MS -> SkipKind.Intro
    hasCredits && durationMs > 0L && positionMs >= durationMs - creditsFromEndMs -> SkipKind.Credits
    else -> null
}

/** The last second of an intro doesn't offer a skip; the picture is about to move on anyway. */
private const val SKIP_GRACE_MS = 1_000L

/** "Skip intro · OK" in the bottom-right corner; the player handles the key. */
@Composable
fun SkipPrompt(kind: SkipKind, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (kind) {
                SkipKind.Intro -> R.string.dial_skip_intro_prompt
                SkipKind.Credits -> R.string.dial_skip_credits_prompt
            }
        ),
        color = TvColors.TextPrimary,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/* -------------------------------------------------------------------------- still watching */

/** Asks whether anyone's still there; without an answer the player stops after a minute. */
@Composable
fun StillWatchingCard(
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var secondsLeft by remember { mutableIntStateOf(STILL_WATCHING_SECONDS) }
    val continueFocus = remember { FocusRequester() }
    val currentOnStop by rememberUpdatedState(onStop)
    LaunchedEffect(Unit) {
        repeat(STILL_WATCHING_FOCUS_TRIES) {
            yield()
            if (runCatching { continueFocus.requestFocus() }.isSuccess) return@repeat
            delay(STILL_WATCHING_FOCUS_RETRY_MS)
        }
        while (secondsLeft > 0) {
            delay(1_000L)
            secondsLeft--
        }
        currentOnStop()
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .width(460.dp)
            .background(TvColors.Background.copy(alpha = 0.94f), HudShape)
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.dial_still_watching_title),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        Text(
            text = stringResource(R.string.dial_still_watching_countdown, secondsLeft),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_still_watching_continue),
                icon = Icons.Rounded.PlayArrow,
                focusRequester = continueFocus,
                onClick = onContinue,
            )
            TvActionButton(
                text = stringResource(R.string.dial_still_watching_stop),
                icon = Icons.Rounded.Close,
                onClick = onStop,
            )
        }
    }
}

private const val STILL_WATCHING_SECONDS = 60
private const val STILL_WATCHING_FOCUS_TRIES = 10
private const val STILL_WATCHING_FOCUS_RETRY_MS = 32L
