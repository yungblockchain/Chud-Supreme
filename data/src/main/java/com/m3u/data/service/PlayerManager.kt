package com.m3u.data.service

import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

interface PlayerManager {
    val player: StateFlow<Player?>
    val size: StateFlow<Rect>

    val channel: StateFlow<Channel?>
    val playlist: StateFlow<Playlist?>

    val playbackState: StateFlow<@Player.State Int>
    val playbackException: StateFlow<PlaybackException?>
    val isPlaying: StateFlow<Boolean>

    val tracksGroups: StateFlow<List<Tracks.Group>>
    val cacheSpace: Flow<Long>

    /** True while a stream that dropped is being reconnected automatically. */
    val reconnecting: StateFlow<Boolean>

    fun chooseTrack(group: TrackGroup, index: Int)
    fun clearTrack(type: @C.TrackType Int)
    suspend fun play(
        command: MediaCommand,
        applyContinueWatching: Boolean = true
    )
    suspend fun replay()

    /**
     * The app went out of sight (Home button, screensaver, TV switched off): stop streaming so
     * the connection and the video decoder are free, but keep what was playing for [wake].
     */
    fun sleep()

    /**
     * Picks playback back up after [sleep], or after a stream stopped with an error: live
     * channels reconnect at the live edge, films and episodes carry on from where they were.
     * Does nothing while the stream is still running.
     */
    fun wake()
    fun release()
    fun clearCache()
    fun pauseOrContinue(value: Boolean)
    fun updateSpeed(race: Float)

    suspend fun recordVideo(uri: Uri)

    /**
     * Adds a subtitle file (for example one downloaded from OpenSubtitles) to what's playing,
     * keeps the position, and switches it on.
     */
    fun addSubtitle(uri: Uri, mimeType: String, language: String?, label: String)

    val cwPosition: SharedFlow<Long>
    suspend fun onResetPlayback(channelUrl: String)
    suspend fun getCwPosition(channelUrl: String): Long

    /**
     * Saves a resume position reported by an outside player (VLC and others hand back where
     * they stopped), so "Resume from" works the same as with the built-in player.
     */
    suspend fun saveCwPosition(channelUrl: String, positionMs: Long)
    suspend fun reloadThumbnail(channelUrl: String): Uri?
    suspend fun syncThumbnail(channelUrl: String): Uri?
}

@Immutable
data class PlayerTrack(
    val type: @C.TrackType Int,
    val group: TrackGroup,
    val index: Int,
    val format: Format
)

@Immutable
sealed class MediaCommand(open val channelId: Int) {
    data class Common(override val channelId: Int) : MediaCommand(channelId)
    data class XtreamEpisode(
        override val channelId: Int,
        val episode: XtreamEpisodeInfo
    ) : MediaCommand(channelId)

    /**
     * Dial: play another address in a channel's context (headers, user agent, playlist),
     * e.g. an Xtream catch-up (timeshift) recording of a past programme on that channel.
     */
    data class Url(
        override val channelId: Int,
        val url: String,
        val title: String? = null,
    ) : MediaCommand(channelId)
}

val PlayerManager.tracks: Flow<Map<@C.TrackType Int, List<PlayerTrack>>>
    get() = tracksGroups.mapLatest { all ->
        // Group all tracks by their type
        all.groupBy { it.type }
            .mapValues { (_, innerGroups) ->
                // For each group, flatten the list of tracks and filter out unsupported tracks
                innerGroups.flatMap { trackGroup ->
                    List(trackGroup.length) { index ->
                        trackGroup.takeIf { it.isTrackSupported(index) }?.let {
                            PlayerTrack(
                                type = it.type,
                                group = it.mediaTrackGroup,
                                index = index,
                                format = it.getTrackFormat(index)
                            )
                        }
                    }.filterNotNull()
                }
            }
    }.flowOn(Dispatchers.IO)

val PlayerManager.currentTracks: Flow<Map<@C.TrackType Int, PlayerTrack?>>
    get() = tracksGroups.mapLatest { currentTracksGroups ->
        currentTracksGroups
            .groupBy { it.type }
            .mapValues { (_, tracksGroups) ->
                tracksGroups
                    .asSequence()
                    .mapNotNull { trackGroup ->
                        (0 until trackGroup.length)
                            .firstOrNull {
                                trackGroup.isTrackSupported(it) &&
                                        trackGroup.isTrackSelected(it)
                            }
                            ?.let {
                                PlayerTrack(
                                    type = trackGroup.type,
                                    group = trackGroup.mediaTrackGroup,
                                    index = it,
                                    format = trackGroup.getTrackFormat(it)
                                )
                            }
                    }
                    .firstOrNull()
            }
    }.flowOn(Dispatchers.IO)