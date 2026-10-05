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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * Search beyond the playlists: the same words looked up on TMDB (films and series anywhere),
 * YouTube, and the media server. Each lands as its own row under the playlist results.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class VideoResult(val id: String, val title: String, val channel: String?, val thumbnail: String?) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

@Immutable
data class UniversalResults(
    val query: String = "",
    val titles: List<TmdbTitle> = emptyList(),
    val videos: List<VideoResult> = emptyList(),
    val server: List<ServerItem> = emptyList(),
)

@HiltViewModel
class UniversalSearchViewModel @Inject constructor(
    private val secrets: SecretStore,
    private val serverStore: MediaServerStore,
) : ViewModel() {
    private val _results = MutableStateFlow(UniversalResults())
    val results: StateFlow<UniversalResults> = _results.asStateFlow()
    private var job: Job? = null

    /** Called as the person types; waits for a pause before asking anyone. */
    fun search(query: String) {
        val trimmed = query.trim()
        job?.cancel()
        if (trimmed.length < MIN_CHARS) {
            _results.value = UniversalResults()
            return
        }
        job = viewModelScope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            _results.value = UniversalResults(query = trimmed)
            launch {
                val key = secrets.get(SecretName.Tmdb) ?: return@launch
                val titles = runCatching { TmdbClient.searchAll(key, trimmed) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                _results.update { if (it.query == trimmed) it.copy(titles = titles) else it }
            }
            launch {
                val key = secrets.get(SecretName.YouTube) ?: return@launch
                val videos = runCatching { YouTubeSearch.search(key, trimmed) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                _results.update { if (it.query == trimmed) it.copy(videos = videos) else it }
            }
            launch {
                val session = serverStore.session.value ?: return@launch
                val items = runCatching { MediaServerClient.search(session, serverStore.deviceId, trimmed) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                _results.update { if (it.query == trimmed) it.copy(server = items) else it }
            }
        }
    }

    private companion object {
        const val MIN_CHARS = 3
        const val DEBOUNCE_MS = 600L
    }
}

object YouTubeSearch {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(key: String, query: String): List<VideoResult> = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=$MAX" +
            "&safeSearch=moderate&q=${URLEncoder.encode(query, "UTF-8")}&key=${URLEncoder.encode(key, "UTF-8")}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
                ?: return@withContext emptyList()
            (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = (item["id"] as? JsonObject)?.text("videoId") ?: return@mapNotNull null
                val snippet = item["snippet"] as? JsonObject ?: return@mapNotNull null
                val thumbs = snippet["thumbnails"] as? JsonObject
                VideoResult(
                    id = id,
                    title = snippet.text("title")?.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                        ?: return@mapNotNull null,
                    channel = snippet.text("channelTitle"),
                    thumbnail = ((thumbs?.get("medium") ?: thumbs?.get("default")) as? JsonObject)?.text("url"),
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

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private const val MAX = 12
}

/** A row of YouTube results: 16:9 thumbnails with the title and channel underneath. */
@Composable
fun VideoRow(title: String, videos: List<VideoResult>, onOpen: (VideoResult) -> Unit) {
    if (videos.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(videos, key = { it.id }) { video ->
                Column(modifier = Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FocusFrame(
                        onClick = { onOpen(video) },
                        shape = RoundedCornerShape(10.dp),
                        semanticsLabel = video.title,
                        focusedScale = 1.04f,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TvColors.Surface),
                        ) {
                            AsyncImage(
                                model = video.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        text = video.title,
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    video.channel?.let { channel ->
                        Text(
                            text = channel,
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
}

/** A row of media-server results, reusing the Trakt/TMDB card for the poster look. */
@Composable
fun ServerResultRow(title: String, items: List<ServerItem>, onOpen: (ServerItem) -> Unit) {
    if (items.isEmpty()) return
    val titles = items.map { item ->
        TmdbTitle(
            id = item.id.hashCode(),
            kind = if (item.isSeries) MediaKind.Tv else MediaKind.Movie,
            title = listOfNotNull(item.seriesName, item.name).joinToString(" · "),
            overview = item.overview,
            poster = item.poster,
            backdrop = item.backdrop,
            year = item.year?.toString(),
            rating = item.rating,
        )
    }
    val byId = items.associateBy { it.id.hashCode() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(titles, key = { "server-${it.id}" }) { entry ->
                TitlePoster(
                    title = entry,
                    badge = entry.rating?.let { "★ %.1f".format(it) },
                    onClick = { byId[entry.id]?.let(onOpen) },
                )
            }
        }
    }
}

@Composable
fun TmdbResultRow(title: String, titles: List<TmdbTitle>, onOpen: (TmdbTitle) -> Unit) {
    if (titles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(titles, key = { "${it.kind}-${it.id}" }) { entry ->
                TitlePoster(
                    title = entry,
                    badge = entry.rating?.let { "★ %.1f".format(it) },
                    onClick = { onOpen(entry) },
                )
            }
        }
    }
}
