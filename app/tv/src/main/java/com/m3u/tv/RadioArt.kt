package com.m3u.tv

import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Artist and album for a radio song title, from MusicBrainz. Cached, and at most one request a second. */
internal object RadioArt {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, RadioCredit?>()
    private val lock = Mutex()
    private var lastRequestAt = 0L

    data class RadioCredit(val artist: String?, val album: String?, val artUrl: String?) {
        val line: String? = listOfNotNull(artist, album).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
    }

    suspend fun lookup(song: String): RadioCredit? {
        val key = song.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return null
        lock.withLock {
            if (cache.containsKey(key)) return cache[key]
        }
        val credit = withContext(Dispatchers.IO) { runCatching { fetch(song.trim()) }.getOrNull() }
        lock.withLock { cache[key] = credit }
        return credit
    }

    private suspend fun fetch(song: String): RadioCredit? {
        val (artist, title) = split(song)
        val query = if (artist != null) {
            """artist:"${artist.replace("\"", "")}" AND recording:"${title.replace("\"", "")}""""
        } else {
            """recording:"${title.replace("\"", "")}""""
        }
        waitTurn()
        val root = getJson(
            json,
            "https://musicbrainz.org/ws/2/release/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=1",
            userAgent = USER_AGENT,
        ) as? JsonObject ?: return artist?.let { RadioCredit(it, null, null) }
        val release = (root["releases"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return artist?.let { RadioCredit(it, null, null) }
        val album = (release["title"] as? JsonPrimitive)?.contentOrNull
        val id = (release["id"] as? JsonPrimitive)?.contentOrNull
        val credited = (((release["artist-credit"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("name") as? JsonPrimitive)?.contentOrNull ?: artist
        val art = id?.let { "https://coverartarchive.org/release/$it/front-250" }
        if (credited.isNullOrBlank() && album.isNullOrBlank()) return null
        return RadioCredit(credited, album, art)
    }

    private fun split(song: String): Pair<String?, String> {
        val parts = song.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to song.trim()
        }
    }

    private suspend fun waitTurn() {
        val wait = 1_100L - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private const val USER_AGENT = "ChudSupreme/1.0 (https://github.com/yungblockchain/Chud-Supreme)"
}
