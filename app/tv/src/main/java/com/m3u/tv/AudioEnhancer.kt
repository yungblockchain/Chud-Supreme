package com.m3u.tv

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale

/* -------------------------------------------------------------------------------------------------
 * Sound shaping on the stick itself. Night mode squeezes loud and quiet parts closer together so
 * explosions don't wake the house and whispers stay audible; the dialogue boost lifts the speech
 * frequencies over music and effects; the sound profile (equaliser), volume boost and virtual
 * surround come from the device the sound is going to (see AudioOutputs.kt). All of them work on
 * decoded sound: with audio passed straight through to a soundbar (Dolby / DTS bitstream), the
 * soundbar's own settings are the ones to use.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class AudioShape(
    val night: Boolean = false,
    val dialogue: Boolean = false,
    val eq: EqPreset = EqPreset.Flat,
    val boostDb: Int = 0,
    val surround: Boolean = false,
) {
    val shapesTone: Boolean get() = dialogue || eq != EqPreset.Flat
    val processes: Boolean get() = night || shapesTone || boostDb > 0
    val any: Boolean get() = processes || surround

    /** Decibels per band in [EQ_CUTOFFS]: the profile, plus the dialogue lift. */
    fun gains(): FloatArray = FloatArray(EQ_CUTOFFS.size) { band ->
        eq.gains[band] + if (dialogue) DIALOGUE_GAINS[band] else 0f
    }

    private companion object {
        /** Less rumble, more of the 1–5 kHz where speech sits. */
        val DIALOGUE_GAINS = floatArrayOf(-4f, -3f, 0f, 2f, 4f, 5f, 1f, 0f)
    }
}

class AudioEnhancer(private val player: ExoPlayer) {
    private var dynamics: Any? = null
    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null
    private var session = C.AUDIO_SESSION_ID_UNSET
    private var shape = AudioShape()

    private val listener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            session = audioSessionId
            rebuild()
        }
    }

    init {
        player.addAnalyticsListener(listener)
        session = player.audioSessionId
    }

    private val built: Boolean get() = dynamics != null || equalizer != null || loudness != null || virtualizer != null

    fun set(shape: AudioShape) {
        if (this.shape == shape && (built || !shape.any)) return
        this.shape = shape
        rebuild()
    }

    fun release() {
        player.removeAnalyticsListener(listener)
        clear()
    }

    private fun clear() {
        runCatching { (dynamics as? AudioEffect)?.release() }
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        runCatching { virtualizer?.release() }
        dynamics = null
        equalizer = null
        loudness = null
        virtualizer = null
    }

    private fun rebuild() {
        clear()
        if (!shape.any) return
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        if (shape.processes) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { dynamics = buildDynamics(session, shape) }
                    .onFailure { buildFallback() }
            } else {
                buildFallback()
            }
        }
        if (shape.surround) runCatching { virtualizer = buildVirtualizer(session) }
    }

    /** Before Android 9 there's no compressor: an equaliser and a loudness lift are all there is. */
    private fun buildFallback() {
        if (shape.shapesTone) runCatching {
            val gains = shape.gains()
            val eq = Equalizer(0, session)
            try {
                val (min, max) = eq.bandLevelRange.let { it[0] to it[1] }
                for (band in 0 until eq.numberOfBands) {
                    val centre = eq.getCenterFreq(band.toShort()) / 1000f // millihertz → hertz
                    val index = EQ_CUTOFFS.indexOfFirst { centre <= it }.let { if (it < 0) EQ_CUTOFFS.lastIndex else it }
                    eq.setBandLevel(band.toShort(), (gains[index] * 100).toInt().toShort().coerceIn(min, max))
                }
                eq.enabled = true
                equalizer = eq
            } catch (e: RuntimeException) {
                eq.release()
                throw e
            }
        }
        if (shape.boostDb > 0) runCatching {
            val enhancer = LoudnessEnhancer(session)
            try {
                enhancer.setTargetGain(shape.boostDb * 100)
                enhancer.enabled = true
                loudness = enhancer
            } catch (e: RuntimeException) {
                enhancer.release()
                throw e
            }
        }
    }

    private fun buildVirtualizer(session: Int): Virtualizer {
        val effect = Virtualizer(0, session)
        try {
            if (effect.strengthSupported) effect.setStrength(1000)
            effect.enabled = true
            // Headphone virtualisation, so 5.1 and 7.1 tracks come from around the listener
            // (the mode only takes once the effect is on).
            runCatching { effect.forceVirtualizationMode(Virtualizer.VIRTUALIZATION_MODE_BINAURAL) }
            return effect
        } catch (e: RuntimeException) {
            effect.release()
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildDynamics(session: Int, shape: AudioShape): DynamicsProcessing {
        val gains = shape.gains()
        val tone = shape.shapesTone
        // Anything that lifts the level gets the limiter, so it can't clip.
        val limit = shape.night || shape.boostDb > 0 || (tone && gains.any { it > 0f })
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            tone, EQ_CUTOFFS.size,
            shape.night, 1,
            false, 0,
            limit,
        ).build()
        val effect = DynamicsProcessing(0, session, config)
        try {
            if (tone) {
                val eq = DynamicsProcessing.Eq(true, true, EQ_CUTOFFS.size)
                EQ_CUTOFFS.forEachIndexed { band, cutoff -> eq.setBand(band, DynamicsProcessing.EqBand(true, cutoff, gains[band])) }
                effect.setPreEqAllChannelsTo(eq)
            }
            if (shape.night) {
                val mbc = DynamicsProcessing.Mbc(true, true, 1)
                mbc.setBand(
                    0,
                    DynamicsProcessing.MbcBand(
                        true, 20_000f,
                        3f, 120f, // attack, release (ms)
                        4f, -32f, 8f, // ratio, threshold (dB), knee (dB)
                        -90f, 1f, // noise gate threshold, expander ratio
                        0f, 9f, // pre-gain, post-gain (dB)
                    ),
                )
                effect.setMbcAllChannelsTo(mbc)
            }
            if (limit) {
                effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
            }
            if (shape.boostDb > 0) effect.setInputGainAllChannelsTo(shape.boostDb.toFloat())
            effect.enabled = true
            return effect
        } catch (e: RuntimeException) {
            effect.release()
            throw e
        }
    }

    private companion object {
        /** Enough for 7.1 decoded on the stick; streams with fewer channels ignore the rest. */
        const val CHANNELS = 8
    }
}

/* ----------------------------------------------------------------------------- languages */

/** "eng" / "en-GB" / "en" → "en", for comparing track languages with the person's choice. */
fun languageRoot(code: String?): String? {
    val raw = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
    val base = raw.substringBefore('-').substringBefore('_')
    if (base.length == 2) return base
    return runCatching {
        Locale.getAvailableLocales().firstOrNull { it.isO3Language == base }?.language
    }.getOrNull() ?: base
}

/**
 * Applies the language rules to a player: the preferred audio language first (and the audio
 * description track, if asked for), and subtitles in the preferred language switched on when the
 * sound isn't in the language the person speaks.
 * Returns the listener to remove later.
 */
fun applyLanguageRules(
    player: Player,
    audio: String,
    subtitles: String,
    foreignSubtitles: Boolean,
    describe: Boolean = false,
): Player.Listener {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .apply { if (audio.isNotEmpty()) setPreferredAudioLanguages(audio) }
        // Audio description: the track that describes the picture, when there is one.
        .setPreferredAudioRoleFlags(if (describe) C.ROLE_FLAG_DESCRIBES_VIDEO else 0)
        .build()
    val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val spoken = audio.ifEmpty { Locale.getDefault().language }
            val playing = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } }
            val playingLanguage = languageRoot(playing?.language)
            val wanted = subtitles.ifEmpty { spoken }
            val current = player.trackSelectionParameters.preferredTextLanguages
            val foreign = foreignSubtitles && playingLanguage != null && playingLanguage != languageRoot(spoken)
            val next: List<String>? = when {
                foreign -> listOf(wanted)
                // Subtitles already set to come on (Settings > Playback): the chosen language first.
                subtitles.isNotEmpty() && current.isNotEmpty() && current.firstOrNull() != subtitles ->
                    (listOf(subtitles) + current).distinct()
                else -> null
            }
            if (next != null && next != current) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setPreferredTextLanguages(*next.toTypedArray())
                    .build()
            }
        }
    }
    player.addListener(listener)
    return listener
}

/** Whether any subtitle track is on offer at all (embedded or added). */
fun Tracks.hasText(): Boolean = groups.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }

/** The language of the audio playing, if the stream says. */
fun Tracks.audioFormat(): Format? = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
    ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } }
