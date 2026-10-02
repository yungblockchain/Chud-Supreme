package com.m3u.tv

import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Immutable
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import com.m3u.data.parser.xtream.XtreamInput
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * Xtream catalogue calls the upstream data layer doesn't make: film and series details with seasons,
 * programme listings for the guide, and catch-up (timeshift) addresses.
 * All calls go straight to the provider's player_api.php and never throw; a failure returns null
 * or an empty list so screens can show a calm "not available" state.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class VodDetails(
    val title: String?,
    val plot: String?,
    val year: String?,
    val rating: String?,
    val duration: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val poster: String?,
    val backdrop: String?,
    /** TMDB id when the provider sends one (most do), for cast, trending and subtitles. */
    val tmdbId: String? = null,
)

@Immutable
data class SeriesDetails(
    val title: String?,
    val plot: String?,
    val year: String?,
    val rating: String?,
    val genre: String?,
    val cast: String?,
    val poster: String?,
    val backdrop: String?,
    val seasons: List<SeriesSeason>,
    val tmdbId: String? = null,
)

@Immutable
data class SeriesSeason(
    val key: String,
    val episodes: List<SeriesEpisode>,
)

@Immutable
data class SeriesEpisode(
    val id: String,
    val season: String,
    val episodeNum: String?,
    val title: String,
    val containerExtension: String?,
    val plot: String?,
    val duration: String?,
    val image: String?,
) {
    fun toEpisodeInfo(): XtreamEpisodeInfo = XtreamEpisodeInfo(
        containerExtension = containerExtension,
        episodeNum = episodeNum,
        id = id,
        title = title,
    )
}

@Immutable
data class GuideProgramme(
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Start as the server writes it ("2026-09-28 20:00:00"), needed for timeshift addresses. */
    val serverStart: String?,
    val hasArchive: Boolean,
) {
    fun isOnAt(now: Long): Boolean = now in startMillis until endMillis
    fun hasEndedBy(now: Long): Boolean = endMillis <= now
}

internal object XtreamCatalog {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serverDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
    private val timeshiftStamp = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})""")
    private val base64Shape = Regex("""^[A-Za-z0-9+/=\s]+$""")

    /** Xtream login behind an imported playlist, or null for non-Xtream sources. */
    fun credentialsFor(playlist: Playlist?): XtreamCredentials? {
        playlist ?: return null
        val input = XtreamInput.decodeFromPlaylistUrlOrNull(playlist.url) ?: return null
        if (input.username.isBlank() || input.password.isBlank()) return null
        val server = XtreamClient.normalizeServer(input.basicUrl) ?: return null
        return XtreamCredentials(server, input.username, input.password)
    }

    /** Stream, film or series id: the last path segment of its address, minus any extension. */
    fun idFromUrl(url: String): Int? =
        runCatching { Uri.parse(url).lastPathSegment }
            .getOrNull()
            ?.substringBefore('.')
            ?.toIntOrNull()

    suspend fun vodDetails(credentials: XtreamCredentials, vodId: Int): VodDetails? {
        val root = call(credentials, "get_vod_info", "vod_id" to vodId.toString()) as? JsonObject
            ?: return null
        val info = root["info"] as? JsonObject
        val movie = root["movie_data"] as? JsonObject
        if (info == null && movie == null) return null
        return VodDetails(
            title = movie?.text("name") ?: info?.text("name"),
            plot = info?.text("plot") ?: info?.text("description"),
            year = info?.text("releasedate")?.take(4) ?: info?.text("year"),
            rating = info?.text("rating")?.takeUnless { it == "0" },
            duration = info?.text("duration"),
            genre = info?.text("genre"),
            cast = info?.text("cast") ?: info?.text("actors"),
            director = info?.text("director"),
            poster = info?.text("movie_image") ?: info?.text("cover_big"),
            backdrop = info?.text("backdrop_path"),
            tmdbId = (info?.text("tmdb_id") ?: movie?.text("tmdb_id") ?: info?.text("tmdb"))
                ?.takeIf { id -> id.all(Char::isDigit) && id != "0" },
        )
    }

    suspend fun seriesDetails(credentials: XtreamCredentials, seriesId: Int): SeriesDetails? {
        val root = call(credentials, "get_series_info", "series_id" to seriesId.toString()) as? JsonObject
            ?: return null
        val info = root["info"] as? JsonObject
        val seasons = parseEpisodes(root["episodes"])
        if (info == null && seasons.isEmpty()) return null
        return SeriesDetails(
            title = info?.text("name"),
            plot = info?.text("plot"),
            year = (info?.text("releaseDate") ?: info?.text("release_date"))?.take(4),
            rating = info?.text("rating")?.takeUnless { it == "0" },
            genre = info?.text("genre"),
            cast = info?.text("cast"),
            poster = info?.text("cover"),
            backdrop = info?.text("backdrop_path"),
            seasons = seasons,
            tmdbId = (info?.text("tmdb") ?: info?.text("tmdb_id"))
                ?.takeIf { id -> id.all(Char::isDigit) && id != "0" },
        )
    }

    /** Now and next few programmes; cheap enough to call for each visible guide row. */
    suspend fun shortEpg(credentials: XtreamCredentials, streamId: Int, limit: Int = 3): List<GuideProgramme> =
        parseListings(
            call(
                credentials,
                "get_short_epg",
                "stream_id" to streamId.toString(),
                "limit" to limit.toString(),
            )
        )

    /** Full listing for one channel, including which past programmes can be replayed. */
    suspend fun fullEpg(credentials: XtreamCredentials, streamId: Int): List<GuideProgramme> =
        parseListings(call(credentials, "get_simple_data_table", "stream_id" to streamId.toString()))

    /** Standard Xtream timeshift address: /timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/id.ts */
    fun timeshiftUrl(credentials: XtreamCredentials, streamId: Int, programme: GuideProgramme): String? {
        val match = programme.serverStart?.let { timeshiftStamp.find(it) } ?: return null
        val (day, hour, minute) = match.destructured
        val minutes = ((programme.endMillis - programme.startMillis) / 60_000L).coerceAtLeast(1L)
        return "${credentials.server}/timeshift/${Uri.encode(credentials.username)}/" +
            "${Uri.encode(credentials.password)}/$minutes/$day:$hour-$minute/$streamId.ts"
    }

    private fun parseEpisodes(element: JsonElement?): List<SeriesSeason> {
        val bySeason = linkedMapOf<String, MutableList<SeriesEpisode>>()
        fun add(seasonKey: String, raw: JsonElement) {
            val episode = raw as? JsonObject ?: return
            val id = episode.text("id") ?: return
            val details = episode["info"] as? JsonObject
            val season = episode.text("season") ?: seasonKey
            bySeason.getOrPut(season) { mutableListOf() } += SeriesEpisode(
                id = id,
                season = season,
                episodeNum = episode.text("episode_num"),
                title = episode.text("title") ?: "",
                containerExtension = episode.text("container_extension"),
                plot = details?.text("plot"),
                duration = details?.text("duration"),
                image = details?.text("movie_image"),
            )
        }
        when (element) {
            is JsonObject -> element.forEach { (seasonKey, episodes) ->
                (episodes as? JsonArray)?.forEach { add(seasonKey, it) }
            }
            // Some panels send a list of seasons instead of a map keyed by season number.
            is JsonArray -> element.forEachIndexed { index, episodes ->
                (episodes as? JsonArray)?.forEach { add((index + 1).toString(), it) }
            }
            else -> Unit
        }
        return bySeason.entries
            .sortedWith(compareBy({ it.key.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
            .map { (key, episodes) ->
                SeriesSeason(
                    key = key,
                    episodes = episodes.sortedWith(
                        compareBy({ it.episodeNum?.toIntOrNull() ?: Int.MAX_VALUE }, { it.title })
                    ),
                )
            }
    }

    private fun parseListings(element: JsonElement?): List<GuideProgramme> {
        val listings = (element as? JsonObject)?.get("epg_listings") as? JsonArray ?: return emptyList()
        return listings.mapNotNull { raw ->
            val item = raw as? JsonObject ?: return@mapNotNull null
            val serverStart = item.text("start")
            val start = item.text("start_timestamp")?.toLongOrNull()?.times(1000)
                ?: serverStart?.let(::parseServerTime)
                ?: return@mapNotNull null
            val end = item.text("stop_timestamp")?.toLongOrNull()?.times(1000)
                ?: (item.text("end") ?: item.text("stop"))?.let(::parseServerTime)
                ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            GuideProgramme(
                title = decodeMaybeBase64(item.text("title")).ifBlank { "—" },
                description = decodeMaybeBase64(item.text("description")),
                startMillis = start,
                endMillis = end,
                serverStart = serverStart,
                hasArchive = item.text("has_archive") == "1",
            )
        }.sortedBy { it.startMillis }
    }

    private fun parseServerTime(value: String): Long? =
        runCatching { serverDateFormat.get()!!.parse(value)?.time }.getOrNull()

    /** Xtream sends EPG titles and descriptions base64-encoded, but not every panel does. */
    private fun decodeMaybeBase64(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val compact = value.trim()
        if (compact.length % 4 != 0 || !base64Shape.matches(compact)) return compact
        val decoded = runCatching {
            String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return compact
        val readable = decoded.isNotBlank() &&
            decoded.none { it == '\uFFFD' } &&
            decoded.all { !it.isISOControl() || it == '\n' || it == '\r' || it == '\t' }
        return if (readable) decoded.trim() else compact
    }

    private fun JsonObject.text(name: String): String? {
        val value = when (val element = this[name]) {
            is JsonPrimitive -> element.contentOrNull
            // backdrop_path is usually a list of images; take the first.
            is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        return value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private suspend fun call(
        credentials: XtreamCredentials,
        action: String,
        vararg params: Pair<String, String>,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(credentials.server)
            append("/player_api.php?username=")
            append(URLEncoder.encode(credentials.username, Charsets.UTF_8.name()))
            append("&password=")
            append(URLEncoder.encode(credentials.password, Charsets.UTF_8.name()))
            append("&action=").append(action)
            params.forEach { (key, value) ->
                append('&').append(key).append('=')
                append(URLEncoder.encode(value, Charsets.UTF_8.name()))
            }
        }
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            runCatching { json.parseToJsonElement(body) }.getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
