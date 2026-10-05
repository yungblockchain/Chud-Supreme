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

/** What the last Trakt action on the details page came to. */
enum class TraktNotice { Rated, RatingRemoved, Commented, CommentTooShort, Watched, Failed }

@Immutable
data class DetailsExtrasState(
    val channelId: Int,
    val loading: Boolean = true,
    val extras: TitleExtras? = null,
    val comments: List<TraktComment> = emptyList(),
    val hasTmdbKey: Boolean = false,
    val hasTraktKey: Boolean = false,
    /** TMDB, Trakt, IMDb, Rotten Tomatoes... whichever answered. */
    val ratings: List<RatingBadge> = emptyList(),
    val traktSignedIn: Boolean = false,
    /** The film or show as Trakt knows it (for rating, commenting, marking watched). */
    val traktItem: TraktItem? = null,
    val myRating: Int? = null,
    val busy: Boolean = false,
    val notice: TraktNotice? = null,
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
    private val trakt: TraktService,
    val scrobbler: TraktScrobbler,
) : ViewModel() {

    /** The person's Trakt rows for Home (empty when signed out). */
    private val _traktRows = MutableStateFlow<List<TraktRow>>(emptyList())
    val traktRows: StateFlow<List<TraktRow>> = _traktRows.asStateFlow()
    val traktAccount: StateFlow<TraktAccount?> = trakt.account
    private var traktRowsLoadedAt = 0L
    private var traktRowsJob: Job? = null

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

    init {
        // Signing in or out on the Services tab shows on a details page that's already open.
        // (After every property above: the flow replays at once on the main thread.)
        viewModelScope.launch {
            trakt.account.collect { account ->
                _extras.update { it?.copy(traktSignedIn = account != null) }
            }
        }
    }

    fun hasTmdbKey(): Boolean = secrets.has(SecretName.Tmdb)

    /** "Because you watched X": TMDB's picks after the last film or series opened. */
    private val _becauseYouWatched = MutableStateFlow<Pair<String, List<TmdbTitle>>?>(null)
    val becauseYouWatched: StateFlow<Pair<String, List<TmdbTitle>>?> = _becauseYouWatched.asStateFlow()
    private var becauseFor: Int? = null

    fun loadBecauseYouWatched(last: Channel?, isSeries: Boolean) {
        val key = secrets.get(SecretName.Tmdb) ?: return
        if (last == null) {
            _becauseYouWatched.value = null
            return
        }
        if (becauseFor == last.id) return
        becauseFor = last.id
        viewModelScope.launch(Dispatchers.IO) {
            val kind = if (isSeries) MediaKind.Tv else MediaKind.Movie
            val title = runCatching {
                TmdbClient.search(key, kind, OpenSubtitles.cleanTitle(last.title), OpenSubtitles.yearIn(last.title))
            }.getOrNull() ?: return@launch
            val picks = runCatching { TmdbClient.recommendations(key, kind, title.id) }.getOrDefault(emptyList())
            if (becauseFor == last.id) _becauseYouWatched.value = if (picks.isEmpty()) null else title.title to picks
        }
    }

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
            val year = details.film?.year ?: details.series?.year ?: OpenSubtitles.yearIn(details.channel.title)
            val cleanTitle = OpenSubtitles.cleanTitle(details.film?.title ?: details.series?.title ?: details.channel.title)
            val traktItem: TraktItem = if (kind == MediaKind.Movie) {
                TraktItem.Movie(cleanTitle, year?.toIntOrNull(), extras?.tmdbId)
            } else {
                TraktItem.Show(cleanTitle, year?.toIntOrNull(), extras?.tmdbId)
            }
            val tmdbBadge = extras?.rating?.let { listOf(RatingBadge(RatingSource.Tmdb, "%.1f".format(it), extras.votes)) }.orEmpty()
            update(details.channel.id) {
                it.copy(
                    loading = false,
                    extras = extras,
                    ratings = tmdbBadge,
                    traktSignedIn = trakt.signedIn,
                    traktItem = traktItem,
                )
            }
            if (extras == null) return@launch
            // The other ratings, each in its own breath so one slow service doesn't hold the rest.
            if (traktKey != null) {
                launch {
                    val comments = runCatching { TmdbClient.traktComments(traktKey, kind, extras.tmdbId) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(emptyList())
                    update(details.channel.id) { it.copy(comments = comments) }
                }
                launch {
                    val community = runCatching { trakt.ratings(kind, extras.tmdbId) }.getOrNull()
                    if (community != null) {
                        addRatings(details.channel.id, listOf(RatingBadge(RatingSource.Trakt, "${(community.rating * 10).toInt()}%", community.votes)))
                    }
                }
                if (trakt.signedIn) {
                    launch {
                        val mine = runCatching { trakt.myRating(kind, extras.tmdbId) }.getOrNull()
                        update(details.channel.id) { it.copy(myRating = mine) }
                    }
                }
            }
            secrets.get(SecretName.MdbList)?.let { key ->
                launch {
                    addRatings(details.channel.id, runCatching { RatingsClient.mdbList(key, kind, extras.tmdbId) }.getOrDefault(emptyList()))
                }
            }
            val omdbKey = secrets.get(SecretName.Omdb)
            if (omdbKey != null && extras.imdbId != null && secrets.get(SecretName.MdbList) == null) {
                launch {
                    addRatings(details.channel.id, runCatching { RatingsClient.omdb(omdbKey, extras.imdbId) }.getOrDefault(emptyList()))
                }
            }
        }
    }

    /** Adds badges from one source, keeping one pill per source, TMDB first. */
    private fun addRatings(channelId: Int, badges: List<RatingBadge>) {
        if (badges.isEmpty()) return
        update(channelId) { state ->
            val merged = (state.ratings + badges).distinctBy { it.source }.sortedBy { it.source.ordinal }
            state.copy(ratings = merged)
        }
    }

    /* ---------------------------------------------------------------------- Trakt actions */

    fun rate(rating: Int) = traktAction { item ->
        if (trakt.rate(item, rating)) {
            update(item) { it.copy(myRating = rating.takeIf { r -> r in 1..10 }) }
            if (rating in 1..10) TraktNotice.Rated else TraktNotice.RatingRemoved
        } else TraktNotice.Failed
    }

    fun postComment(text: String, spoiler: Boolean) = traktAction { item ->
        if (text.trim().split(Regex("\\s+")).size < MIN_COMMENT_WORDS) TraktNotice.CommentTooShort
        else if (trakt.comment(item, text.trim(), spoiler)) TraktNotice.Commented
        else TraktNotice.Failed
    }

    /** Films only; a series is marked episode by episode as they play. */
    fun markWatched() = traktAction { item ->
        if (trakt.markWatched(item)) TraktNotice.Watched else TraktNotice.Failed
    }

    fun clearNotice() {
        _extras.update { it?.copy(notice = null) }
    }

    private fun traktAction(block: suspend (TraktItem) -> TraktNotice) {
        val state = _extras.value ?: return
        val item = state.traktItem ?: return
        if (state.busy) return
        _extras.update { it?.copy(busy = true, notice = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val notice = runCatching { block(item) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(TraktNotice.Failed)
            update(state.channelId) { it.copy(busy = false, notice = notice) }
        }
    }

    private fun update(item: TraktItem, transform: (DetailsExtrasState) -> DetailsExtrasState) {
        _extras.update { if (it?.traktItem == item) transform(it) else it }
    }

    /* -------------------------------------------------------------------------- Home rows */

    fun loadTraktRows(force: Boolean = false) {
        if (!trakt.signedIn) {
            _traktRows.value = emptyList()
            return
        }
        if (!force && System.currentTimeMillis() - traktRowsLoadedAt < TRAKT_ROWS_TTL_MS && _traktRows.value.isNotEmpty()) return
        traktRowsJob?.cancel()
        traktRowsJob = viewModelScope.launch(Dispatchers.IO) {
            val rows = runCatching { trakt.homeRows() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(emptyList())
            traktRowsLoadedAt = System.currentTimeMillis()
            _traktRows.value = rows
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
        const val TRAKT_ROWS_TTL_MS = 10 * 60_000L
        const val MIN_COMMENT_WORDS = 5
        const val MAX_TRENDING = 20
        const val SEARCH_LIMIT = 40
    }
}
