package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What's playing, for looking up subtitles. */
@Immutable
data class SubtitleTarget(
    val title: String,
    val year: String? = null,
    val tmdbId: String? = null,
    /** For an episode: the series' TMDB id, season and episode numbers. */
    val parentTmdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Immutable
data class SubtitleResult(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
)

sealed interface OpenSubtitlesFailure {
    data object NoKey : OpenSubtitlesFailure
    data object Rejected : OpenSubtitlesFailure
    data object QuotaReached : OpenSubtitlesFailure
    data object Offline : OpenSubtitlesFailure
    data class Other(val code: Int?) : OpenSubtitlesFailure
}

class OpenSubtitlesException(val failure: OpenSubtitlesFailure) : IOException(failure.toString())

/**
 * OpenSubtitles.com REST API with the person's own API key (a free consumer key from their
 * account). Search, then download one file to the app's cache.
 */
internal object OpenSubtitles {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private const val USER_AGENT = "ChudStreams v1.0"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Release-name clutter IPTV titles carry: "EN - ", "[4K]", "(2023)", "|MULTI|". */
    private val clutter = Regex(
        """^[A-Z]{2,3}\s*[-|:]\s*|\[[^\]]*\]|\|[^|]*\||\(\d{4}\)|\b(4K|UHD|FHD|HD|SD|HEVC|H\.?265|MULTI|VOSTFR)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val yearPattern = Regex("""\((\d{4})\)""")

    fun cleanTitle(raw: String): String =
        raw.replace(clutter, " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { raw.trim() }

    fun yearIn(raw: String): String? = yearPattern.find(raw)?.groupValues?.get(1)

    suspend fun search(apiKey: String, target: SubtitleTarget): List<SubtitleResult> {
        val languages = listOf(Locale.getDefault().language, "en")
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        // The API wants parameters lower-case and in alphabetical order (else it redirects).
        val params = sortedMapOf<String, String>()
        params["languages"] = languages
        when {
            target.parentTmdbId != null && target.season != null && target.episode != null -> {
                params["parent_tmdb_id"] = target.parentTmdbId
                params["season_number"] = target.season.toString()
                params["episode_number"] = target.episode.toString()
            }
            target.tmdbId != null -> params["tmdb_id"] = target.tmdbId
            else -> {
                params["query"] = cleanTitle(target.title).lowercase(Locale.ROOT)
                target.year?.let { params["year"] = it }
                if (target.season != null && target.episode != null) {
                    params["season_number"] = target.season.toString()
                    params["episode_number"] = target.episode.toString()
                }
            }
        }
        params["order_by"] = "download_count"
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")}"
        }
        val body = request(apiKey, "GET", "$BASE/subtitles?$query", null)
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val attributes = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
            val file = (attributes["files"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return@mapNotNull null
            val fileId = (file["file_id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            SubtitleResult(
                fileId = fileId,
                language = (attributes["language"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                release = (attributes["release"] as? JsonPrimitive)?.contentOrNull
                    ?: (file["file_name"] as? JsonPrimitive)?.contentOrNull
                    ?: "",
                downloads = (attributes["download_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                hearingImpaired = (attributes["hearing_impaired"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }.take(MAX_RESULTS)
    }

    /** Downloads [result] as an .srt file in the cache and returns it. */
    suspend fun download(context: Context, apiKey: String, result: SubtitleResult): File {
        val body = buildJsonObject {
            put("file_id", result.fileId)
            put("sub_format", "srt")
        }.toString()
        val response = json.parseToJsonElement(request(apiKey, "POST", "$BASE/download", body)).jsonObject
        val link = (response["link"] as? JsonPrimitive)?.contentOrNull
            ?: throw OpenSubtitlesException(OpenSubtitlesFailure.QuotaReached)
        return withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
            // Keep the folder small: only the latest few files.
            directory.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CACHED_FILES)
                ?.forEach { it.delete() }
            val file = File(directory, "${result.fileId}.srt")
            val connection = URL(link).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) {
                    throw OpenSubtitlesException(OpenSubtitlesFailure.Other(connection.responseCode))
                }
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            file
        }
    }

    private suspend fun request(apiKey: String, method: String, url: String, body: String?): String =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("Api-Key", apiKey)
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                when {
                    code in 200..299 -> text
                    code == 401 || code == 403 -> throw OpenSubtitlesException(OpenSubtitlesFailure.Rejected)
                    code == 406 || code == 429 -> throw OpenSubtitlesException(OpenSubtitlesFailure.QuotaReached)
                    else -> throw OpenSubtitlesException(OpenSubtitlesFailure.Other(code))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenSubtitlesException) {
                throw e
            } catch (e: IOException) {
                throw OpenSubtitlesException(OpenSubtitlesFailure.Offline)
            } finally {
                connection?.disconnect()
            }
        }

    private const val MAX_RESULTS = 30
    private const val MAX_CACHED_FILES = 20
}
