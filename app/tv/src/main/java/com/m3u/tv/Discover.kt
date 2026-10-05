package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/* -------------------------------------------------------------------------------------------------
 * Home rows from free, keyless sources:
 *  - New episodes: TVmaze's schedule for the series in favourites and "continue watching" — what
 *    came out this week and what airs in the next few days.
 *  - Trending anime: AniList's chart, matched against the person's playlists on OK.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class NewEpisode(
    val series: Channel,
    val label: String,
    val airsAt: Long,
    /** Out already (else coming up). */
    val aired: Boolean,
    val image: String?,
)

object TvMaze {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The latest and next episode of the show named [title], when TVmaze knows it. */
    suspend fun around(series: Channel, now: Long): NewEpisode? = withContext(Dispatchers.IO) {
        val name = OpenSubtitles.cleanTitle(series.title)
        if (name.isBlank()) return@withContext null
        val root = get(
            "https://api.tvmaze.com/singlesearch/shows?q=${URLEncoder.encode(name, "UTF-8")}" +
                "&embed%5B%5D=nextepisode&embed%5B%5D=previousepisode"
        ) as? JsonObject ?: return@withContext null
        val embedded = root["_embedded"] as? JsonObject ?: return@withContext null
        val image = (root["image"] as? JsonObject)?.get("medium")?.jsonPrimitive?.contentOrNull
        fun episode(key: String): Pair<Long, String>? {
            val item = embedded[key] as? JsonObject ?: return null
            val stamp = item["airstamp"]?.jsonPrimitive?.contentOrNull ?: return null
            val at = runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull() ?: return null
            val season = item["season"]?.jsonPrimitive?.intOrNull
            val number = item["number"]?.jsonPrimitive?.intOrNull
            val code = if (season != null && number != null) "S$season E$number" else item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return at to code
        }
        val previous = episode("previousepisode")?.takeIf { now - it.first in 0..WEEK_MS }
        val next = episode("nextepisode")?.takeIf { it.first - now in 0..WEEK_MS }
        when {
            previous != null -> NewEpisode(series, previous.second, previous.first, aired = true, image = image)
            next != null -> NewEpisode(series, next.second, next.first, aired = false, image = image)
            else -> null
        }
    }

    private fun get(url: String): JsonElement? = getJson(json, url)

    private const val WEEK_MS = 7 * 24 * 60 * 60_000L
}

object AniList {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The anime trending on AniList right now (nothing for adults). */
    suspend fun trending(): List<TmdbTitle> = withContext(Dispatchers.IO) {
        val query = """
            query { Page(perPage: 24) { media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
              id title { english romaji } coverImage { large } bannerImage description(asHtml: false)
              seasonYear averageScore format
            } } }
        """.trimIndent()
        val body = buildJsonObject { put("query", query) }.toString()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) return@withContext emptyList()
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val media = ((root["data"] as? JsonObject)?.get("Page") as? JsonObject)?.get("media") as? JsonArray
            media.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val titles = item["title"] as? JsonObject
                val name = titles?.get("english")?.jsonPrimitive?.contentOrNull
                    ?: titles?.get("romaji")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                TmdbTitle(
                    id = -(item["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null),
                    kind = if (item["format"]?.jsonPrimitive?.contentOrNull == "MOVIE") MediaKind.Movie else MediaKind.Tv,
                    title = name,
                    overview = item["description"]?.jsonPrimitive?.contentOrNull?.replace(Regex("<[^>]+>"), "")?.take(300),
                    poster = (item["coverImage"] as? JsonObject)?.get("large")?.jsonPrimitive?.contentOrNull,
                    backdrop = item["bannerImage"]?.jsonPrimitive?.contentOrNull,
                    year = item["seasonYear"]?.jsonPrimitive?.intOrNull?.toString(),
                    rating = item["averageScore"]?.jsonPrimitive?.intOrNull?.let { it / 10.0 },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}

/** One GET that answers JSON, or null. */
internal fun getJson(json: Json, url: String): JsonElement? {
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
        }
        if (connection.responseCode != 200) null
        else json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

@HiltViewModel
class DiscoverViewModel @Inject constructor() : ViewModel() {
    private val _newEpisodes = MutableStateFlow<List<NewEpisode>>(emptyList())
    val newEpisodes: StateFlow<List<NewEpisode>> = _newEpisodes.asStateFlow()

    private val _anime = MutableStateFlow<List<TmdbTitle>>(emptyList())
    val anime: StateFlow<List<TmdbTitle>> = _anime.asStateFlow()

    private var episodesFor: Set<Int> = emptySet()
    private var pendingFor: Set<Int> = emptySet()
    private var episodesAt = 0L
    private var episodesJob: Job? = null
    private var animeAt = 0L

    /** Looks up [series] on TVmaze (one at a time: it allows twenty requests in ten seconds). */
    fun loadNewEpisodes(series: List<Channel>) {
        val ids = series.map { it.id }.toSet()
        if (ids.isEmpty()) {
            _newEpisodes.value = emptyList()
            return
        }
        if (ids == episodesFor && System.currentTimeMillis() - episodesAt < TTL_MS) return
        if (episodesJob?.isActive == true && ids == pendingFor) return
        pendingFor = ids
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val found = mutableListOf<NewEpisode>()
            for (channel in series.distinctBy { OpenSubtitles.cleanTitle(it.title).lowercase(Locale.ROOT) }.take(MAX_SERIES)) {
                runCatching { TvMaze.around(channel, now) }.getOrNull()?.let { found += it }
                // Out first, newest first; then what's coming, soonest first.
                _newEpisodes.value = found.sortedWith(compareBy<NewEpisode> { !it.aired }.thenBy { if (it.aired) -it.airsAt else it.airsAt })
                delay(TVMAZE_SPACING_MS)
            }
            // Only a finished pass counts as fresh (a failed or cut-short one is tried again).
            episodesFor = ids
            episodesAt = System.currentTimeMillis()
        }
    }

    fun loadAnime() {
        if (System.currentTimeMillis() - animeAt < TTL_MS && _anime.value.isNotEmpty()) return
        animeAt = System.currentTimeMillis()
        viewModelScope.launch { _anime.value = AniList.trending() }
    }

    private companion object {
        const val TTL_MS = 3 * 60 * 60_000L
        const val MAX_SERIES = 18
        const val TVMAZE_SPACING_MS = 550L
    }
}

/** "New episodes": series cards with what came out (or comes out) and when. */
@Composable
fun NewEpisodesRow(items: List<NewEpisode>, onOpen: (Channel) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(
            title = stringResource(R.string.dial_new_episodes_title),
            subtitle = stringResource(R.string.dial_new_episodes_subtitle),
            modifier = Modifier.padding(start = 48.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 10.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(items, key = { "new-${it.series.id}" }) { item ->
                val day = SimpleDateFormat("EEE", Locale.getDefault()).format(Date(item.airsAt))
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.airsAt))
                val caption = if (item.aired) {
                    stringResource(R.string.dial_new_episode_out, item.label, day)
                } else {
                    stringResource(R.string.dial_new_episode_next, item.label, day, time)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(180.dp)) {
                    FocusFrame(
                        onClick = { onOpen(item.series) },
                        shape = RoundedCornerShape(10.dp),
                        semanticsLabel = "${item.series.title}, $caption",
                        focusedScale = 1.05f,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TvColors.Surface),
                        ) {
                            PosterArt(model = item.series.cover ?: item.image, modifier = Modifier.fillMaxSize())
                            Text(
                                text = if (item.aired) stringResource(R.string.dial_new_episode_badge) else day,
                                color = Color.White,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (item.aired) TvColors.Danger else Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = item.series.title.title(),
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = caption,
                        color = TvColors.TextMuted,
                        fontFamily = TvFonts.Body,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
