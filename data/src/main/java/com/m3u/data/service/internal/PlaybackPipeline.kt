package com.m3u.data.service.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.m3u.core.foundation.architecture.preferences.BufferProfile
import com.m3u.core.foundation.architecture.preferences.SubtitleMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoRenderer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.Locale

/*
 * How the built-in player is put together, from the TV's Playback settings: decoders, Dolby
 * Vision and Dolby audio handling, buffering, which subtitles turn on, and the audio/subtitle
 * sync offsets (which can change while something plays).
 */

/** Settings read when a player is created (they apply from the next stream). */
internal data class PlaybackOptions(
    val tunneling: Boolean = false,
    val dolbyVisionAsHdr10: Boolean = false,
    val audioPassthrough: Boolean = true,
    val preferSoftwareDecoder: Boolean = false,
    val bufferProfile: Int = BufferProfile.BALANCED,
    val subtitleMode: Int = SubtitleMode.FORCED_ONLY,
    val styledSubtitles: Boolean = true,
    /** Anime4K line shader. Off leaves the picture on the direct, HDR-capable path. */
    val anime4k: Boolean = false,
    /** Per-group brightness, contrast and colour. Off leaves the picture alone. */
    val pictureControls: Boolean = false,
    /** The preset for the channel group now playing. [PicturePresets.NORMAL] adds nothing. */
    val picturePreset: String = PicturePresets.NORMAL,
) {
    /** True when this playback will run the picture through the GPU. */
    val usesGpuPicture: Boolean
        get() = anime4k || (pictureControls && picturePreset != PicturePresets.NORMAL)

    fun effectsKey(): String = "$anime4k:$pictureControls:$picturePreset"
}

/** Sync offsets, read by the renderers on the playback thread on every frame. */
internal class PlaybackSync {
    @Volatile
    var audioDelayUs: Long = 0L

    @Volatile
    var subtitleDelayUs: Long = 0L
}

@OptIn(UnstableApi::class)
internal class PlaybackRenderersFactory(
    context: Context,
    private val options: PlaybackOptions,
    private val sync: PlaybackSync,
) : NextRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        if (options.dolbyVisionAsHdr10) {
            setMediaCodecSelector(WithoutDolbyVisionDecoders)
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val sink = if (options.audioPassthrough) {
            // With a context, the sink passes Dolby Digital/DTS through when the HDMI device
            // says it can take them.
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        } else {
            // Without one it assumes plain stereo PCM, so surround tracks are decoded here.
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder()
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
        return DelayedAudioSink(sink, sync)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        if (options.preferSoftwareDecoder) {
            // The player uses the first renderer that can play a format, so put FFmpeg first.
            val index = out.indexOfFirst { it is FfmpegVideoRenderer }
            if (index > 0) out.add(0, out.removeAt(index))
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, built)
        built.mapTo(out) { DelayedTextRenderer(it, sync) }
    }
}

/**
 * Leaves Dolby Vision decoders out, so Dolby Vision profile 8/9 streams play from their
 * HDR10/HLG/SDR base layer (for TVs that show Dolby Vision wrongly or not at all).
 */
@OptIn(UnstableApi::class)
private val WithoutDolbyVisionDecoders = MediaCodecSelector { mimeType, secure, tunneling ->
    if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
        emptyList()
    } else {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
    }
}

/**
 * Audio delay: the video follows the audio clock, so reporting the audio a little further
 * along makes the picture run ahead, which is the same as the sound coming later.
 * (With tunneled playback the TV keeps sync itself and this has no effect.)
 */
@OptIn(UnstableApi::class)
private class DelayedAudioSink(
    sink: AudioSink,
    private val sync: PlaybackSync,
) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        return (position + sync.audioDelayUs).coerceAtLeast(0L)
    }
}

/** Subtitle delay: the subtitle renderer is shown an earlier (or later) point in the video. */
@OptIn(UnstableApi::class)
private class DelayedTextRenderer(
    renderer: Renderer,
    private val sync: PlaybackSync,
) : ForwardingRenderer(renderer) {
    @Throws(ExoPlaybackException::class)
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - sync.subtitleDelayUs, elapsedRealtimeUs)
    }
}

@OptIn(UnstableApi::class)
internal fun createLoadControl(profile: Int): LoadControl = when (profile) {
    BufferProfile.FAST_START -> DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 20_000,
            /* maxBufferMs = */ 40_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 1_500,
        )
        .build()

    BufferProfile.LARGE -> DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 60_000,
            /* maxBufferMs = */ 120_000,
            /* bufferForPlaybackMs = */ 2_500,
            /* bufferForPlaybackAfterRebufferMs = */ 5_000,
        )
        .build()

    else -> DefaultLoadControl()
}

/** Which subtitle tracks the player turns on by itself. */
internal fun TrackSelectionParameters.Builder.applySubtitleMode(
    mode: Int,
): TrackSelectionParameters.Builder = when (mode) {
    SubtitleMode.OFF -> setIgnoredTextSelectionFlags(
        C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED
    )

    SubtitleMode.ALWAYS -> setPreferredTextLanguages(
        *listOfNotNull(Locale.getDefault().language.takeIf { it.isNotBlank() }, "en")
            .distinct()
            .toTypedArray()
    ).setSelectUndeterminedTextLanguage(true)

    // Forced only: forced tracks still switch on; ones merely marked "default" don't.
    else -> setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
}
