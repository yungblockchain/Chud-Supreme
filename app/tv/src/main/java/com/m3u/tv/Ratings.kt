package com.m3u.tv

import androidx.compose.runtime.Immutable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/* -------------------------------------------------------------------------------------------------
 * Ratings from more than one place, for the details page: TMDB (always), Trakt (with the person's
 * client id), and IMDb / Rotten Tomatoes / Metacritic / Letterboxd through OMDb or MDBList when
 * those keys are set. Each source is one pill; a missing key just means fewer pills.
 * ---------------------------------------------------------------------------------------------- */

enum class RatingSource { Tmdb, Trakt, Imdb, RottenTomatoes, Metacritic, Letterboxd, Audience }

@Immutable
data class RatingBadge(val source: RatingSource, val value: String, val votes: Int? = null)

object RatingsClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** IMDb, Rotten Tomatoes and Metacritic from OMDb, by IMDb id. */
    suspend fun omdb(key: String, imdbId: String): List<RatingBadge> {
        val root = fetch("https://www.omdbapi.com/?apikey=${encode(key)}&i=${encode(imdbId)}") ?: return emptyList()
        if (root.text("Response") == "False") return emptyList()
        val badges = mutableListOf<RatingBadge>()
        root.text("imdbRating")?.takeIf { it != "N/A" }?.let { value ->
            badges += RatingBadge(RatingSource.Imdb, value, root.text("imdbVotes")?.replace(",", "")?.toIntOrNull())
        }
        (root["Ratings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { entry ->
            val value = entry.text("Value") ?: return@forEach
            when (entry.text("Source")) {
                "Rotten Tomatoes" -> badges += RatingBadge(RatingSource.RottenTomatoes, value)
                "Metacritic" -> badges += RatingBadge(RatingSource.Metacritic, value.substringBefore('/'))
            }
        }
        return badges
    }

    /** Every rating MDBList knows, by TMDB id. */
    suspend fun mdbList(key: String, kind: MediaKind, tmdbId: Int): List<RatingBadge> {
        val type = if (kind == MediaKind.Movie) "movie" else "show"
        val root = fetch("https://api.mdblist.com/tmdb/$type/$tmdbId?apikey=${encode(key)}") ?: return emptyList()
        return (root["ratings"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val value = (entry["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val votes = entry.int("votes")
            when (entry.text("source")) {
                "imdb" -> RatingBadge(RatingSource.Imdb, "%.1f".format(value), votes)
                "metacritic" -> RatingBadge(RatingSource.Metacritic, value.toInt().toString(), votes)
                "tomatoes" -> RatingBadge(RatingSource.RottenTomatoes, "${value.toInt()}%", votes)
                "tomatoesaudience" -> RatingBadge(RatingSource.Audience, "${value.toInt()}%", votes)
                "letterboxd" -> RatingBadge(RatingSource.Letterboxd, "%.1f".format(value), votes)
                else -> null
            }
        }
    }

    private suspend fun fetch(url: String): JsonObject? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}

/* -------------------------------------------------------------------------------------------------
 * Scrobbling: tells Trakt when a film or episode starts, pauses and stops, with how far in it is.
 * The app reports what's playing; this keeps the calls in order and quiet when switched off.
 * ---------------------------------------------------------------------------------------------- */

@Singleton
class TraktScrobbler @Inject constructor(
    private val trakt: TraktService,
    private val store: DialSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val order = Mutex()
    private var current: TraktItem? = null
    private var playing = false
    private var progress = 0f

    /**
     * The state of the player right now. Call it whenever the item, play/pause or position changes
     * (a pause, a seek, a close); it works out which Trakt calls that needs.
     */
    fun update(item: TraktItem?, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        val enabled = store.preferences.value.traktScrobble && trakt.signedIn
        val percent = if (durationMs > 0L) (positionMs * 100f / durationMs).coerceIn(0f, 100f) else progress
        val previous = current
        val wasPlaying = playing
        val previousProgress = progress
        current = item
        playing = isPlaying && item != null
        progress = percent
        if (!enabled) return
        scope.launch {
            order.withLock {
                if (item != previous) {
                    if (previous != null) trakt.scrobble(TraktService.ScrobbleAction.Stop, previous, previousProgress)
                    if (item != null && isPlaying) trakt.scrobble(TraktService.ScrobbleAction.Start, item, percent)
                } else if (item != null && isPlaying != wasPlaying) {
                    trakt.scrobble(
                        if (isPlaying) TraktService.ScrobbleAction.Start else TraktService.ScrobbleAction.Pause,
                        item,
                        percent,
                    )
                }
            }
        }
    }

    /** Playback is over (the player closed): a stop with the last known progress. */
    fun stop() = update(null, 0L, 0L, isPlaying = false)

    /** The film or episode ran to the end: a stop at 100%, so Trakt counts it as watched. */
    fun finish(item: TraktItem) {
        val enabled = store.preferences.value.traktScrobble && trakt.signedIn
        val wasCurrent = current == item
        current = null
        playing = false
        progress = 0f
        if (!enabled || !wasCurrent) return
        scope.launch { order.withLock { trakt.scrobble(TraktService.ScrobbleAction.Stop, item, 100f) } }
    }
}
