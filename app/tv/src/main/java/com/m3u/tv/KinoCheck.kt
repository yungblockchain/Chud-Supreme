package com.m3u.tv

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Trailers from KinoCheck when TMDB has none. Cached per TMDB id. A miss or an error is just
 * "no trailer", the same as before.
 */
internal object KinoCheck {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = ConcurrentHashMap<Int, String>()
    private val missing = ConcurrentHashMap.newKeySet<Int>()

    /** A YouTube id, or null. */
    suspend fun youtubeId(tmdbId: Int): String? {
        cache[tmdbId]?.let { return it }
        if (tmdbId in missing) return null
        val found = withContext(Dispatchers.IO) {
            val root = getJson(json, "https://api.kinocheck.de/movies?tmdb_id=$tmdbId") as? JsonObject
                ?: return@withContext null
            val videos = root["videos"] as? JsonArray ?: return@withContext null
            videos.firstNotNullOfOrNull { element ->
                ((element as? JsonObject)?.get("youtube_video_id") as? JsonPrimitive)?.contentOrNull
            }
        }
        if (found.isNullOrBlank()) missing.add(tmdbId) else cache[tmdbId] = found
        return found?.takeIf { it.isNotBlank() }
    }
}
