package com.m3u.tv

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/* -------------------------------------------------------------------------------------------------
 * Anime openings and endings, from AniSkip (times people marked, per episode): an anime episode
 * with no skip markers of its own gets "Skip intro" and the credits mark from there. The show is
 * found on AniList by its exact title (for MyAnimeList's id, which AniSkip uses), and only for
 * series filed as anime, so a live-action namesake never gets an anime's times.
 * ---------------------------------------------------------------------------------------------- */

object AniSkip {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** "title|season" to MyAnimeList id (0: looked up, not found). */
    private val ids = ConcurrentHashMap<String, Int>()
    private val ANIME = Regex("\\b(anime|animé|crunchyroll|funimation|japanese animation)\\b")

    /** Whether a series looks like anime, by its category or genre. */
    fun looksLikeAnime(category: String?, genre: String?): Boolean =
        ANIME.containsMatchIn(listOfNotNull(category, genre).joinToString(" ").lowercase(Locale.ROOT))

    suspend fun markers(title: String, season: Int, episode: Int): SkipMarkers? = withContext(Dispatchers.IO) {
        val mal = malId(title, season) ?: return@withContext null
        val root = getJson(json, "https://api.aniskip.com/v2/skip-times/$mal/$episode?types%5B%5D=op&types%5B%5D=ed&episodeLength=0")
            as? JsonObject ?: return@withContext null
        if (root["found"]?.jsonPrimitive?.booleanOrNull != true) return@withContext null
        var markers = SkipMarkers()
        (root["results"] as? JsonArray).orEmpty().forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            val interval = item["interval"] as? JsonObject ?: return@forEach
            val start = interval["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val end = interval["endTime"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val length = item["episodeLength"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            when (item["skipType"]?.jsonPrimitive?.contentOrNull) {
                "op" -> if (end > start) markers = markers.copy(introStartMs = (start * 1000).toLong(), introEndMs = (end * 1000).toLong())
                "ed" -> if (length > start) markers = markers.copy(creditsFromEndMs = ((length - start) * 1000).toLong())
            }
        }
        markers.takeUnless { it.isEmpty }
    }

    private fun malId(title: String, season: Int): Int? {
        val key = "${normalise(title)}|$season"
        ids[key]?.let { return it.takeIf { id -> id > 0 } }
        val base = normalise(title)
        val searches = if (season <= 1) listOf(title) else listOf("$title season $season", "$title $season")
        val found = searches.firstNotNullOfOrNull { query ->
            search(query).firstOrNull { (names, _) ->
                names.any { name ->
                    val candidate = normalise(name)
                    if (season <= 1) candidate == base
                    else candidate.startsWith(base) && Regex("\\b($season(st|nd|rd|th)?|season $season)\\b").containsMatchIn(candidate)
                }
            }?.second
        }
        ids[key] = found ?: 0
        return found
    }

    /** AniList's matches for [query]: their titles, and MyAnimeList ids. */
    private fun search(query: String): List<Pair<List<String>, Int>> {
        val body = buildJsonObject {
            put(
                "query",
                "query (\$q: String) { Page(perPage: 8) { media(search: \$q, type: ANIME) { idMal title { english romaji } synonyms } } }",
            )
            putJsonObject("variables") { put("q", query) }
        }.toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) return emptyList()
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
            val media = ((root?.get("data") as? JsonObject)?.get("Page") as? JsonObject)?.get("media") as? JsonArray
            media.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val mal = item["idMal"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val titles = item["title"] as? JsonObject
                val names = listOfNotNull(
                    titles?.get("english")?.jsonPrimitive?.contentOrNull,
                    titles?.get("romaji")?.jsonPrimitive?.contentOrNull,
                ) + (item["synonyms"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                names to mal
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalise(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
