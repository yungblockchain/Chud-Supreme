package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TrendingEntry(
    val title: TmdbTitle,
    /** The matching film or series in the person's playlists, once found. */
    val channel: Channel? = null,
)

@Immutable
data class DetailsExtrasState(
    val channelId: Int,
    val loading: Boolean = true,
    val extras: TitleExtras? = null,
    val comments: List<TraktComment> = emptyList(),
    val hasTmdbKey: Boolean = false,
    val hasTraktKey: Boolean = false,
)

@Immutable
data class PersonState(
    val id: Int,
    val loading: Boolean = true,
    val person: PersonDetails? = null,
)

/** TMDB trending, cast and people, and Trakt comments, for Home and the details pages. */
@HiltViewModel
class MetadataViewModel @Inject constructor(
    private val secrets: SecretStore,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val _trending = MutableStateFlow<List<TrendingEntry>>(emptyList())
    val trending: StateFlow<List<TrendingEntry>> = _trending.asStateFlow()

    private val _extras = MutableStateFlow<DetailsExtrasState?>(null)
    val extras: StateFlow<DetailsExtrasState?> = _extras.asStateFlow()

    private val _person = MutableStateFlow<PersonState?>(null)
    val person: StateFlow<PersonState?> = _person.asStateFlow()

    private var trendingLoadedAt = 0L
    private var trendingJob: Job? = null
    private var extrasJob: Job? = null
    private var personJob: Job? = null
    private val playlists = mutableMapOf<String, Playlist?>()

    fun hasTmdbKey(): Boolean = secrets.has(SecretName.Tmdb)

    /** Films and shows trending this week, matched against the person's playlists. */
    fun loadTrending() {
        val key = secrets.get(SecretName.Tmdb) ?: run {
            _trending.value = emptyList()
            return
        }
        if (System.currentTimeMillis() - trendingLoadedAt < TRENDING_TTL_MS && _trending.value.isNotEmpty()) return
        trendingJob?.cancel()
        trendingJob = viewModelScope.launch(Dispatchers.IO) {
            val (movies, shows) = try {
                TmdbClient.trending(key, MediaKind.Movie) to TmdbClient.trending(key, MediaKind.Tv)
            } catch (e: TmdbException) {
                return@launch
            }
            // Alternate films and shows so both are near the front.
            val titles = (
                movies.zip(shows).flatMap { (a, b) -> listOf(a, b) } +
                    movies.drop(shows.size) + shows.drop(movies.size)
                ).take(MAX_TRENDING)
            trendingLoadedAt = System.currentTimeMillis()
            _trending.value = titles.map { TrendingEntry(it) }
            titles.forEach { title ->
                val channel = findInCatalogue(title) ?: return@forEach
                _trending.update { entries ->
                    entries.map { if (it.title == title) it.copy(channel = channel) else it }
                }
            }
        }
    }

    /** Cast, TMDB synopsis and IMDb id for a details page, plus Trakt comments. */
    fun loadExtras(details: DetailsState) {
        val current = _extras.value
        if (current != null && current.channelId == details.channel.id && !current.loading) return
        val tmdbKey = secrets.get(SecretName.Tmdb)
        val traktKey = secrets.get(SecretName.TraktClientId)
        _extras.value = DetailsExtrasState(
            channelId = details.channel.id,
            loading = tmdbKey != null,
            hasTmdbKey = tmdbKey != null,
            hasTraktKey = traktKey != null,
        )
        if (tmdbKey == null) return
        extrasJob?.cancel()
        extrasJob = viewModelScope.launch(Dispatchers.IO) {
            val kind = if (details.kind == DetailsKind.Series) MediaKind.Tv else MediaKind.Movie
            val knownId = (details.film?.tmdbId ?: details.series?.tmdbId)?.toIntOrNull()
            val extras = runCatching {
                val id = knownId ?: TmdbClient.search(
                    key = tmdbKey,
                    kind = kind,
                    query = OpenSubtitles.cleanTitle(details.film?.title ?: details.series?.title ?: details.channel.title),
                    year = details.film?.year ?: details.series?.year ?: OpenSubtitles.yearIn(details.channel.title),
                )?.id
                id?.let { TmdbClient.extras(tmdbKey, kind, it) }
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            update(details.channel.id) { it.copy(loading = false, extras = extras) }
            if (extras != null && traktKey != null) {
                val comments = runCatching { TmdbClient.traktComments(traktKey, kind, extras.tmdbId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrDefault(emptyList())
                update(details.channel.id) { it.copy(comments = comments) }
            }
        }
    }

    fun openPerson(id: Int) {
        val key = secrets.get(SecretName.Tmdb) ?: return
        _person.value = PersonState(id)
        personJob?.cancel()
        personJob = viewModelScope.launch(Dispatchers.IO) {
            val person = runCatching { TmdbClient.person(key, id) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            _person.update { if (it?.id == id) it.copy(loading = false, person = person) else it }
        }
    }

    fun closePerson() {
        personJob?.cancel()
        _person.value = null
    }

    /** The film or series with this title in the person's playlists, if there is one. */
    suspend fun findInCatalogue(title: TmdbTitle): Channel? {
        val wanted = normalise(title.title)
        if (wanted.isEmpty()) return null
        val candidates = runCatching { channelRepository.searchUnhidden(title.title, SEARCH_LIMIT) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        return candidates.firstOrNull { channel ->
            val playlist = playlists.getOrPut(channel.playlistUrl) {
                runCatching { playlistRepository.get(channel.playlistUrl) }.getOrNull()
            }
            val rightKind = when (title.kind) {
                MediaKind.Movie -> playlist?.isVod == true
                MediaKind.Tv -> playlist?.isSeries == true
            }
            rightKind && normalise(OpenSubtitles.cleanTitle(channel.title)) == wanted
        } ?: candidates.firstOrNull { channel ->
            val playlist = playlists[channel.playlistUrl]
            val rightKind = when (title.kind) {
                MediaKind.Movie -> playlist?.isVod == true
                MediaKind.Tv -> playlist?.isSeries == true
            }
            rightKind && normalise(channel.title).contains(wanted)
        }
    }

    private fun update(channelId: Int, transform: (DetailsExtrasState) -> DetailsExtrasState) {
        _extras.update { if (it?.channelId == channelId) transform(it) else it }
    }

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private companion object {
        const val TRENDING_TTL_MS = 6 * 60 * 60_000L
        const val MAX_TRENDING = 20
        const val SEARCH_LIMIT = 40
    }
}
