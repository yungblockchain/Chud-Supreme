package com.m3u.tv

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.RequiresApi
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
 * frequencies over music and effects. Both work on decoded sound: with audio passed straight
 * through to a soundbar (Dolby / DTS bitstream), the soundbar's own night mode is the one to use.
 * ---------------------------------------------------------------------------------------------- */

class AudioEnhancer(private val player: ExoPlayer) {
    private var dynamics: Any? = null
    private var equalizer: Equalizer? = null
    private var session = C.AUDIO_SESSION_ID_UNSET
    private var night = false
    private var dialogue = false

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

    fun set(night: Boolean, dialogue: Boolean) {
        if (this.night == night && this.dialogue == dialogue && (dynamics != null || equalizer != null || (!night && !dialogue))) return
        this.night = night
        this.dialogue = dialogue
        rebuild()
    }

    fun release() {
        player.removeAnalyticsListener(listener)
        clear()
    }

    private fun clear() {
        runCatching { (dynamics as? AudioEffect)?.release() }
        runCatching { equalizer?.release() }
        dynamics = null
        equalizer = null
    }

    private fun rebuild() {
        clear()
        if (!night && !dialogue) return
        if (session == C.AUDIO_SESSION_ID_UNSET || session == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamics = buildDynamics(session, night, dialogue) }
                .onFailure { buildEqualizerFallback() }
        } else {
            buildEqualizerFallback()
        }
    }

    /** Before Android 9 there's no compressor: the dialogue lift is all an equaliser can do. */
    private fun buildEqualizerFallback() {
        if (!dialogue) return
        runCatching {
            val eq = Equalizer(0, session)
            val (min, max) = eq.bandLevelRange.let { it[0] to it[1] }
            for (band in 0 until eq.numberOfBands) {
                val centre = eq.getCenterFreq(band.toShort()) / 1000 // millihertz → hertz
                val level = when {
                    centre < 250 -> -400
                    centre in 1000..4000 -> 500
                    else -> 0
                }.toShort().coerceIn(min, max)
                eq.setBandLevel(band.toShort(), level)
            }
            eq.enabled = true
            equalizer = eq
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildDynamics(session: Int, night: Boolean, dialogue: Boolean): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            dialogue, PRE_EQ_BANDS,
            night, 1,
            false, 0,
            night,
        ).build()
        val effect = DynamicsProcessing(0, session, config)
        try {
            if (dialogue) {
                val eq = DynamicsProcessing.Eq(true, true, PRE_EQ_BANDS)
                eq.setBand(0, DynamicsProcessing.EqBand(true, 180f, -4f))
                eq.setBand(1, DynamicsProcessing.EqBand(true, 900f, 2f))
                eq.setBand(2, DynamicsProcessing.EqBand(true, 4_000f, 5f))
                eq.setBand(3, DynamicsProcessing.EqBand(true, 20_000f, 0f))
                effect.setPreEqAllChannelsTo(eq)
            }
            if (night) {
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
                effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
            }
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
        const val PRE_EQ_BANDS = 4
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
 * Applies the language rules to a player: the preferred audio language first, and subtitles in
 * the preferred language switched on when the sound isn't in the language the person speaks.
 * Returns the listener to remove later.
 */
fun applyLanguageRules(player: Player, audio: String, subtitles: String, foreignSubtitles: Boolean): Player.Listener {
    if (audio.isNotEmpty()) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(audio)
            .build()
    }
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
