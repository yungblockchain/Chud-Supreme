package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* -------------------------------------------------------------------------------------------------
 * Radio and podcasts. Stations come from Radio Browser (a community directory of some 50,000
 * streams, free and keyless); podcasts from Apple's public podcast directory (free and keyless
 * too), with the episodes read straight from each show's listing. Both play through the usual
 * player, which shows the artwork in place of a picture.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val logo: String?,
    val country: String?,
    val tags: List<String>,
    val codec: String?,
    val bitrate: Int,
)

@Immutable
data class Podcast(
    val id: String,
    val name: String,
    val author: String?,
    val artwork: String?,
    val genre: String? = null,
)

@Immutable
data class PodcastEpisode(
    val id: String,
    val podcast: Podcast,
    val title: String,
    val url: String,
    val description: String?,
    val artwork: String?,
    val releasedMs: Long,
    val durationMs: Long,
)

/** The sections of the Radio tab. */
sealed interface RadioSection {
    val key: String

    data object Popular : RadioSection { override val key = "popular" }
    data object Near : RadioSection { override val key = "near" }
    data object Genres : RadioSection { override val key = "genres" }
    data object SearchStations : RadioSection { override val key = "search-stations" }
    data object Recent : RadioSection { override val key = "recent" }
    data object TopPodcasts : RadioSection { override val key = "top-podcasts" }
    data object MyPodcasts : RadioSection { override val key = "my-podcasts" }
    data object SearchPodcasts : RadioSection { override val key = "search-podcasts" }
}

/* ----------------------------------------------------------------------------------- store */

@Singleton
class RadioStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(readStations())
    /** Stations played here, newest first. */
    val recent: StateFlow<List<RadioStation>> = _recent.asStateFlow()

    private val _podcasts = MutableStateFlow(readPodcasts())
    /** Podcasts the person follows. */
    val podcasts: StateFlow<List<Podcast>> = _podcasts.asStateFlow()

    fun addRecent(station: RadioStation) {
        val next = (listOf(station) + _recent.value.filterNot { it.id == station.id }).take(MAX_RECENT)
        _recent.value = next
        prefs.edit().putString(KEY_RECENT, JsonArray(next.map { it.toJson() }).toString()).apply()
    }

    fun isFollowed(id: String) = _podcasts.value.any { it.id == id }

    fun toggleFollow(podcast: Podcast) {
        val current = _podcasts.value
        val next = if (current.any { it.id == podcast.id }) current.filterNot { it.id == podcast.id } else (current + podcast).take(MAX_PODCASTS)
        _podcasts.value = next
        prefs.edit().putString(KEY_PODCASTS, JsonArray(next.map { it.toJson() }).toString()).apply()
    }

    fun reload() {
        _recent.value = readStations()
        _podcasts.value = readPodcasts()
    }

    private fun readStations(): List<RadioStation> = runCatching {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonObject.toStation() }
    }.getOrDefault(emptyList())

    private fun readPodcasts(): List<Podcast> = runCatching {
        val raw = prefs.getString(KEY_PODCASTS, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonObject.toPodcast() }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_RECENT = "recent"
        const val KEY_PODCASTS = "podcasts"
        const val MAX_RECENT = 24
        const val MAX_PODCASTS = 60
    }
}

private fun RadioStation.toJson() = JsonObject(
    buildMap {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        put("url", JsonPrimitive(url))
        logo?.let { put("logo", JsonPrimitive(it)) }
        country?.let { put("country", JsonPrimitive(it)) }
        put("tags", JsonPrimitive(tags.joinToString(",")))
        codec?.let { put("codec", JsonPrimitive(it)) }
        put("bitrate", JsonPrimitive(bitrate))
    }
)

private fun JsonObject.toStation(): RadioStation? = RadioStation(
    id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null,
    name = this["name"]?.jsonPrimitive?.contentOrNull ?: return null,
    url = this["url"]?.jsonPrimitive?.contentOrNull ?: return null,
    logo = this["logo"]?.jsonPrimitive?.contentOrNull,
    country = this["country"]?.jsonPrimitive?.contentOrNull,
    tags = this["tags"]?.jsonPrimitive?.contentOrNull?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
    codec = this["codec"]?.jsonPrimitive?.contentOrNull,
    bitrate = this["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
)

private fun Podcast.toJson() = JsonObject(
    buildMap {
        put("id", JsonPrimitive(id))
        put("name", JsonPrimitive(name))
        author?.let { put("author", JsonPrimitive(it)) }
        artwork?.let { put("artwork", JsonPrimitive(it)) }
        genre?.let { put("genre", JsonPrimitive(it)) }
    }
)

private fun JsonObject.toPodcast(): Podcast? = Podcast(
    id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null,
    name = this["name"]?.jsonPrimitive?.contentOrNull ?: return null,
    author = this["author"]?.jsonPrimitive?.contentOrNull,
    artwork = this["artwork"]?.jsonPrimitive?.contentOrNull,
    genre = this["genre"]?.jsonPrimitive?.contentOrNull,
)

/* -------------------------------------------------------------------------------- clients */

/** Radio Browser: any of its mirrors answers; a mirror that's down is simply skipped. */
object RadioBrowser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val hosts = listOf("de1.api.radio-browser.info", "at1.api.radio-browser.info", "nl1.api.radio-browser.info")
    @Volatile private var preferred: String? = null

    suspend fun popular(limit: Int = 80): List<RadioStation> =
        stations("/json/stations/topclick/$limit?hidebroken=true")

    suspend fun byCountry(countryCode: String, limit: Int = 80): List<RadioStation> =
        stations("/json/stations/bycountrycodeexact/${countryCode.uppercase(Locale.US)}?order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    suspend fun byTag(tag: String, limit: Int = 80): List<RadioStation> =
        stations("/json/stations/bytagexact/${URLEncoder.encode(tag, "UTF-8")}?order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    suspend fun search(query: String, limit: Int = 60): List<RadioStation> =
        stations("/json/stations/search?name=${URLEncoder.encode(query, "UTF-8")}&order=clickcount&reverse=true&hidebroken=true&limit=$limit")

    /** The most-used tags (genres and languages), most stations first. */
    suspend fun tags(limit: Int = 40): List<String> = withContext(Dispatchers.IO) {
        val root = get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=$limit") as? JsonArray ?: return@withContext emptyList()
        root.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { tag -> tag.isNotEmpty() } }
    }

    /** Tells the directory the station was played (it ranks stations by this). */
    suspend fun countClick(station: RadioStation) = withContext(Dispatchers.IO) {
        runCatching { get("/json/url/${station.id}") }
    }

    private suspend fun stations(path: String): List<RadioStation> = withContext(Dispatchers.IO) {
        val root = get(path) as? JsonArray ?: return@withContext emptyList()
        root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val url = item.text("url_resolved")?.takeIf { it.startsWith("http") } ?: item.text("url") ?: return@mapNotNull null
            RadioStation(
                id = item.text("stationuuid") ?: return@mapNotNull null,
                name = item.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                url = url,
                logo = item.text("favicon")?.takeIf { it.startsWith("http") },
                country = item.text("country"),
                tags = item.text("tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                codec = item.text("codec"),
                bitrate = item["bitrate"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.distinctBy { it.url }
    }

    private fun get(path: String): JsonElement? {
        val order = listOfNotNull(preferred) + hosts.shuffled().filterNot { it == preferred }
        for (host in order) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://$host$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode == 200) {
                    preferred = host
                    return json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                }
            } catch (e: Exception) {
                if (preferred == host) preferred = null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private const val USER_AGENT = "ChudSupreme/1.0 (Fire TV)"
}

/** Apple's podcast directory: charts, search and each show's episode list, no key needed. */
object PodcastDirectory {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun top(countryCode: String, limit: Int = 50): List<Podcast> = withContext(Dispatchers.IO) {
        val root = get("https://rss.marketingtools.apple.com/api/v2/${countryCode.lowercase(Locale.US)}/podcasts/top/$limit/podcasts.json")
            as? JsonObject ?: return@withContext emptyList()
        (root["feed"] as? JsonObject)?.get("results")?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            Podcast(
                id = item.text("id") ?: return@mapNotNull null,
                name = item.text("name") ?: return@mapNotNull null,
                author = item.text("artistName"),
                artwork = item.text("artworkUrl100")?.replace("100x100", "600x600"),
                genre = (item["genres"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.text("name") },
            )
        }
    }

    suspend fun search(query: String, countryCode: String, limit: Int = 40): List<Podcast> = withContext(Dispatchers.IO) {
        val root = get("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=$limit&country=${countryCode.lowercase(Locale.US)}&term=${URLEncoder.encode(query, "UTF-8")}")
            as? JsonObject ?: return@withContext emptyList()
        root["results"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.toPodcastResult() }
    }

    suspend fun episodes(podcast: Podcast, countryCode: String, limit: Int = 60): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        val root = get("https://itunes.apple.com/lookup?id=${podcast.id}&entity=podcastEpisode&limit=$limit&country=${countryCode.lowercase(Locale.US)}")
            as? JsonObject ?: return@withContext emptyList()
        root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (item.text("wrapperType") != "podcastEpisode") return@mapNotNull null
            PodcastEpisode(
                id = item.text("trackId") ?: return@mapNotNull null,
                podcast = podcast,
                title = item.text("trackName") ?: return@mapNotNull null,
                url = item.text("episodeUrl") ?: return@mapNotNull null,
                description = item.text("shortDescription") ?: item.text("description"),
                artwork = item.text("artworkUrl600") ?: item.text("artworkUrl160") ?: podcast.artwork,
                releasedMs = item.text("releaseDate")?.let(::parseIsoDate) ?: 0L,
                durationMs = item["trackTimeMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.sortedByDescending { it.releasedMs }
    }

    private fun JsonObject.toPodcastResult(): Podcast? = Podcast(
        id = text("collectionId") ?: return null,
        name = text("collectionName") ?: return null,
        author = text("artistName"),
        artwork = text("artworkUrl600") ?: text("artworkUrl100"),
        genre = text("primaryGenreName"),
    )

    private fun get(url: String): JsonElement? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != 200) null
            else json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** "2026-10-04T05:00:00Z" → millis (dates only need the day; the time is a bonus). */
    private fun parseIsoDate(text: String): Long? = runCatching {
        Instant.parse(text).toEpochMilli()
    }.getOrElse {
        runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}

/* ------------------------------------------------------------------------------ viewmodel */

@Immutable
data class RadioState(
    val section: RadioSection = RadioSection.Popular,
    val stations: List<RadioStation> = emptyList(),
    val podcasts: List<Podcast> = emptyList(),
    val tags: List<String> = emptyList(),
    val tag: String? = null,
    val query: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** The podcast whose episodes are open, if any. */
    val openPodcast: Podcast? = null,
    val episodes: List<PodcastEpisode> = emptyList(),
    val episodesLoading: Boolean = false,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val store: RadioStore,
    private val playerManager: PlayerManager,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()
    val recent: StateFlow<List<RadioStation>> = store.recent
    val followed: StateFlow<List<Podcast>> = store.podcasts

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var episodesJob: Job? = null
    private val country: String get() = Locale.getDefault().country.ifBlank { "GB" }

    /** What's playing through the app's player from this tab (for the artwork). */
    var playingArtwork: String? = null
        private set
    private var playingChannelId: Int? = null

    fun open(section: RadioSection) {
        if (_state.value.section == section && (_state.value.stations.isNotEmpty() || _state.value.podcasts.isNotEmpty() || _state.value.loading)) return
        loadJob?.cancel()
        _state.update {
            it.copy(section = section, stations = emptyList(), podcasts = emptyList(), failed = false, loading = false, openPodcast = null, episodes = emptyList())
        }
        when (section) {
            RadioSection.Recent -> _state.update { it.copy(stations = store.recent.value) }
            RadioSection.MyPodcasts -> _state.update { it.copy(podcasts = store.podcasts.value) }
            RadioSection.SearchStations, RadioSection.SearchPodcasts -> setQuery(_state.value.query)
            RadioSection.Genres -> {
                val tag = _state.value.tag
                loadJob = viewModelScope.launch(Dispatchers.IO) {
                    _state.update { it.copy(loading = true) }
                    val tags = _state.value.tags.ifEmpty { runCatching { RadioBrowser.tags() }.getOrDefault(emptyList()) }
                    val chosen = tag ?: tags.firstOrNull()
                    val stations = chosen?.let { runCatching { RadioBrowser.byTag(it) }.getOrNull() }
                    _state.update {
                        if (it.section == section) it.copy(tags = tags, tag = chosen, stations = stations.orEmpty(), loading = false, failed = tags.isEmpty()) else it
                    }
                }
            }
            else -> loadJob = viewModelScope.launch(Dispatchers.IO) {
                _state.update { it.copy(loading = true) }
                val stations = runCatching {
                    when (section) {
                        RadioSection.Popular -> RadioBrowser.popular()
                        RadioSection.Near -> RadioBrowser.byCountry(country)
                        else -> emptyList()
                    }
                }.onFailure { if (it is CancellationException) throw it }
                val podcasts = if (section == RadioSection.TopPodcasts) runCatching { PodcastDirectory.top(country) } else Result.success(emptyList())
                _state.update {
                    if (it.section == section) it.copy(
                        stations = stations.getOrDefault(emptyList()),
                        podcasts = podcasts.getOrDefault(emptyList()),
                        loading = false,
                        failed = stations.isFailure || podcasts.isFailure ||
                            (section == RadioSection.TopPodcasts && podcasts.getOrDefault(emptyList()).isEmpty()) ||
                            (section != RadioSection.TopPodcasts && stations.getOrDefault(emptyList()).isEmpty()),
                    ) else it
                }
            }
        }
    }

    fun selectTag(tag: String) {
        if (_state.value.tag == tag) return
        loadJob?.cancel()
        _state.update { it.copy(tag = tag, stations = emptyList(), loading = true) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val stations = runCatching { RadioBrowser.byTag(tag) }.getOrDefault(emptyList())
            _state.update { if (it.tag == tag) it.copy(stations = stations, loading = false) else it }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        val section = _state.value.section
        if (trimmed.length < 2) {
            _state.update { it.copy(stations = emptyList(), podcasts = emptyList(), loading = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(loading = true, failed = false) }
            if (section == RadioSection.SearchPodcasts) {
                val podcasts = runCatching { PodcastDirectory.search(trimmed, country) }.getOrDefault(emptyList())
                _state.update { if (it.query.trim() == trimmed) it.copy(podcasts = podcasts, loading = false) else it }
            } else {
                val stations = runCatching { RadioBrowser.search(trimmed) }.getOrDefault(emptyList())
                _state.update { if (it.query.trim() == trimmed) it.copy(stations = stations, loading = false) else it }
            }
        }
    }

    fun openPodcast(podcast: Podcast) {
        episodesJob?.cancel()
        _state.update { it.copy(openPodcast = podcast, episodes = emptyList(), episodesLoading = true) }
        episodesJob = viewModelScope.launch(Dispatchers.IO) {
            val episodes = runCatching { PodcastDirectory.episodes(podcast, country) }.getOrDefault(emptyList())
            _state.update { if (it.openPodcast?.id == podcast.id) it.copy(episodes = episodes, episodesLoading = false) else it }
        }
    }

    /** True when Back stayed inside the tab. */
    fun back(): Boolean {
        if (_state.value.openPodcast == null) return false
        episodesJob?.cancel()
        _state.update { it.copy(openPodcast = null, episodes = emptyList(), episodesLoading = false) }
        return true
    }

    fun toggleFollow(podcast: Podcast) {
        store.toggleFollow(podcast)
        if (_state.value.section == RadioSection.MyPodcasts) _state.update { it.copy(podcasts = store.podcasts.value) }
    }

    fun playStation(station: RadioStation, onPlaying: () -> Unit) {
        viewModelScope.launch {
            val channelId = withContext(Dispatchers.IO) {
                rememberChannel(
                    playlistUrl = STATIONS_URL, playlistTitle = STATIONS_TITLE, relation = "radio:${station.id}",
                    url = station.url, title = station.name,
                    category = station.country ?: STATIONS_TITLE, cover = station.logo,
                )
            }
            playingArtwork = station.logo
            playingChannelId = channelId
            store.addRecent(station)
            if (_state.value.section == RadioSection.Recent) _state.update { it.copy(stations = store.recent.value) }
            runCatching { playerManager.play(MediaCommand.Common(channelId), applyContinueWatching = false) }
            onPlaying()
            RadioBrowser.countClick(station)
        }
    }

    fun playEpisode(episode: PodcastEpisode, onPlaying: () -> Unit) {
        viewModelScope.launch {
            val channelId = withContext(Dispatchers.IO) {
                rememberChannel(
                    playlistUrl = PODCASTS_URL, playlistTitle = PODCASTS_TITLE, relation = "podcast:${episode.id}",
                    url = episode.url, title = "${episode.podcast.name} · ${episode.title}",
                    category = episode.podcast.name, cover = episode.artwork ?: episode.podcast.artwork,
                )
            }
            playingArtwork = episode.artwork ?: episode.podcast.artwork
            playingChannelId = channelId
            runCatching { playerManager.play(MediaCommand.Common(channelId), applyContinueWatching = true) }
            onPlaying()
        }
    }

    /** Called when the player shows another channel, so the artwork never outlives its stream. */
    fun onPlayingChannel(channelId: Int?) {
        if (channelId != playingChannelId) {
            playingArtwork = null
            playingChannelId = null
        }
    }

    private suspend fun rememberChannel(
        playlistUrl: String, playlistTitle: String, relation: String,
        url: String, title: String, category: String, cover: String?,
    ): Int {
        if (playlistDao.get(playlistUrl) == null) {
            playlistDao.insertOrReplace(Playlist(title = playlistTitle, url = playlistUrl, source = DataSource.M3U))
        }
        val existing = channelDao.getByPlaylistUrlAndRelationId(playlistUrl, relation)
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = category,
                title = title,
                cover = cover,
                playlistUrl = playlistUrl,
                id = existing?.id ?: 0,
                relationId = relation,
                favourite = existing?.favourite ?: false,
            )
        ).toInt()
        if (id != 0) return id
        return channelDao.getByPlaylistUrlAndRelationId(playlistUrl, relation)?.id ?: 0
    }

    companion object {
        const val STATIONS_URL = "radio://stations"
        const val STATIONS_TITLE = "Radio"
        const val PODCASTS_URL = "podcasts://episodes"
        const val PODCASTS_TITLE = "Podcasts"
        private const val SEARCH_DEBOUNCE_MS = 500L
    }
}
