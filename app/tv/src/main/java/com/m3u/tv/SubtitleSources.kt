package com.m3u.tv

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extra subtitle providers beside OpenSubtitles. Both need a key the person types in
 * Settings › Services. A missing key or a failed request adds nothing; the picker stays as it was.
 */
@androidx.compose.runtime.Immutable
data class SubtitleHit(
    val provider: String,
    val id: String,
    val language: String,
    val release: String,
    val hearingImpaired: Boolean,
    /** A direct file or zip. Null when the download still needs the provider key. */
    val downloadUrl: String? = null,
)

internal object SubtitleSources {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val SUBDL = "https://api.subdl.com/api/v1/subtitles"
    private const val SUBSOURCE = "https://api.subsource.net/api/v1"
    private val subtitleExtensions = setOf("srt", "vtt", "ass", "ssa")

    suspend fun search(
        target: SubtitleTarget,
        languages: List<String>,
        subdlKey: String?,
        subsourceKey: String?,
    ): List<SubtitleHit> = withContext(Dispatchers.IO) {
        val found = ArrayList<SubtitleHit>(16)
        if (!subdlKey.isNullOrBlank()) found += runCatching { subdl(subdlKey, target, languages) }.getOrDefault(emptyList())
        if (!subsourceKey.isNullOrBlank()) found += runCatching { subsource(subsourceKey, target, languages) }.getOrDefault(emptyList())
        found.distinctBy { it.provider + ":" + it.id }.take(MAX_RESULTS)
    }

    /** Saves one subtitle from [hit] into the cache. [key] is only used for SubSource. */
    suspend fun download(context: Context, hit: SubtitleHit, key: String?): File = withContext(Dispatchers.IO) {
        val bytes = when {
            hit.downloadUrl != null -> readBytes(hit.downloadUrl, null)
            hit.provider == PROVIDER_SUBSOURCE && !key.isNullOrBlank() ->
                readBytes("$SUBSOURCE/subtitles/${URLEncoder.encode(hit.id, "UTF-8")}/download?api_key=${URLEncoder.encode(key, "UTF-8")}", key)
            else -> ByteArray(0)
        }
        if (bytes.isEmpty()) error("empty subtitle")
        writeSubtitle(context, hit.id, bytes)
    }

    private fun subdl(key: String, target: SubtitleTarget, languages: List<String>): List<SubtitleHit> {
        val params = LinkedHashMap<String, String>()
        params["api_key"] = key
        val episode = target.season != null && target.episode != null
        when {
            !target.parentTmdbId.isNullOrBlank() && episode -> {
                params["tmdb_id"] = target.parentTmdbId
                params["type"] = "tv"
                params["season_number"] = target.season.toString()
                params["episode_number"] = target.episode.toString()
            }
            !target.tmdbId.isNullOrBlank() && !episode -> {
                params["tmdb_id"] = target.tmdbId
                params["type"] = "movie"
            }
            else -> {
                params["film_name"] = OpenSubtitles.cleanTitle(target.title)
                params["type"] = if (episode) "tv" else "movie"
                target.year?.let { params["year"] = it }
                if (episode) {
                    params["season_number"] = target.season.toString()
                    params["episode_number"] = target.episode.toString()
                }
            }
        }
        val codes = languages.map { it.trim().uppercase(Locale.ROOT) }.filter { it.length == 2 }.distinct()
        if (codes.isNotEmpty()) params["languages"] = codes.joinToString(",")
        params["subs_per_page"] = "10"
        val query = params.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val root = getJson(json, "$SUBDL?$query") as? JsonObject ?: return emptyList()
        if ((root["status"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
        val subs = root["subtitles"] as? JsonArray ?: return emptyList()
        return subs.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val raw = (item["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (raw.contains("api_key", ignoreCase = true)) return@mapNotNull null
            val absolute = when {
                raw.startsWith("https://") || raw.startsWith("http://") -> raw
                raw.startsWith("/") -> "https://dl.subdl.com$raw"
                else -> "https://dl.subdl.com/$raw"
            }
            SubtitleHit(
                provider = PROVIDER_SUBDL,
                id = absolute,
                language = (item["language"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                release = (item["release_name"] as? JsonPrimitive)?.contentOrNull
                    ?: (item["name"] as? JsonPrimitive)?.contentOrNull
                    ?: "",
                hearingImpaired = (item["hi"] as? JsonPrimitive)?.booleanOrNull == true,
                downloadUrl = absolute,
            )
        }
    }

    private fun subsource(key: String, target: SubtitleTarget, languages: List<String>): List<SubtitleHit> {
        val query = LinkedHashMap<String, String>()
        query["api_key"] = key
        query["searchType"] = "text"
        query["q"] = OpenSubtitles.cleanTitle(target.title).lowercase(Locale.ROOT)
        if (target.season != null) query["season"] = target.season.toString()
        val found = getJson(json, "$SUBSOURCE/movies/search?${encode(query)}") as? JsonObject ?: return emptyList()
        val movies = found["data"] as? JsonArray ?: return emptyList()
        val movie = movies.mapNotNull { it as? JsonObject }.let { rows ->
            val year = target.year
            rows.firstOrNull { year != null && it["releaseYear"]?.jsonPrimitive?.contentOrNull == year } ?: rows.firstOrNull()
        } ?: return emptyList()
        val movieId = movie["movieId"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val language = subsourceLanguage(languages.firstOrNull { it.isNotBlank() } ?: "en")
        val params = LinkedHashMap<String, String>()
        params["api_key"] = key
        params["language"] = language
        params["limit"] = "20"
        params["movieId"] = movieId
        if (target.season != null && target.episode != null) {
            params["seasonNumber"] = target.season.toString()
            params["episodeNumber"] = target.episode.toString()
        }
        val listed = getJson(json, "$SUBSOURCE/subtitles?${encode(params)}") as? JsonObject ?: return emptyList()
        val rows = listed["data"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["subtitleId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val releases = (item["releaseInfo"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            SubtitleHit(
                provider = PROVIDER_SUBSOURCE,
                id = id,
                language = (item["language"] as? JsonPrimitive)?.contentOrNull ?: language,
                release = releases.firstOrNull().orEmpty(),
                hearingImpaired = (item["hearingImpaired"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
    }

    private fun readBytes(url: String, key: String?, depth: Int = 0): ByteArray {
        if (depth > 1) return ByteArray(0)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream, application/zip, application/json, */*")
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) return ByteArray(0)
            val type = connection.contentType.orEmpty()
            val body = connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (total < MAX_BYTES) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val take = minOf(n, MAX_BYTES - total)
                    out.write(buffer, 0, take)
                    total += take
                }
                out.toByteArray()
            }
            if (type.contains("json") || (body.isNotEmpty() && body[0] == '{'.code.toByte())) {
                val link = runCatching {
                    val root = json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
                    listOf("link", "url", "downloadUrl")
                        .firstNotNullOfOrNull { name -> (root?.get(name) as? JsonPrimitive)?.contentOrNull }
                }.getOrNull()
                if (link.isNullOrBlank() || (key != null && link.contains(key))) ByteArray(0) else readBytes(link, null, depth + 1)
            } else {
                body
            }
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            connection?.disconnect()
        }
    }

    private fun writeSubtitle(context: Context, nameHint: String, bytes: ByteArray): File {
        val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CACHED)?.forEach { it.delete() }
        if (bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (!entry.isDirectory && ext in subtitleExtensions && !name.contains("..")) {
                        val file = File(directory, "${nameHint.hashCode().toUInt().toString(16)}.$ext")
                        file.outputStream().use { out ->
                            val buffer = ByteArray(8_192)
                            var total = 0
                            while (total < MAX_BYTES) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                total += n
                            }
                        }
                        return file
                    }
                    entry = zip.nextEntry
                }
            }
        }
        val ext = when {
            nameHint.endsWith(".vtt", true) -> "vtt"
            nameHint.endsWith(".ass", true) || nameHint.endsWith(".ssa", true) -> "ass"
            else -> "srt"
        }
        return File(directory, "${nameHint.hashCode().toUInt().toString(16)}.$ext").apply { writeBytes(bytes) }
    }

    private fun encode(params: Map<String, String>): String =
        params.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }

    private fun subsourceLanguage(code: String): String = when (code.lowercase(Locale.ROOT).take(2)) {
        "en" -> "english"
        "es" -> "spanish"
        "fr" -> "french"
        "de" -> "german"
        "it" -> "italian"
        "pt" -> "portuguese"
        "nl" -> "dutch"
        "pl" -> "polish"
        "tr" -> "turkish"
        "ar" -> "arabic"
        "hi" -> "hindi"
        "ru" -> "russian"
        "ja" -> "japanese"
        "ko" -> "korean"
        "zh" -> "chinese"
        else -> "english"
    }

    const val PROVIDER_SUBDL = "Subdl"
    const val PROVIDER_SUBSOURCE = "SubSource"
    private const val MAX_RESULTS = 20
    private const val MAX_CACHED = 20
    private const val MAX_BYTES = 2_000_000
}
