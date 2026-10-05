package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.copyXtreamEpisode
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DetailsKind { Film, Series }

/** A programme that has aired on a favourite channel and can be replayed. */
@Immutable
data class MissedProgramme(val channel: Channel, val programme: GuideProgramme)

private const val BOOKMARK_GAP_MS = 5_000L

/** A film or episode playing in the built-in player. */
@Immutable
data class OnDemandPlayback(
    val subtitleTarget: SubtitleTarget,
    val series: Channel? = null,
    val episode: SeriesEpisode? = null,
    /** The same thing as Trakt knows it, for scrobbling; null when it can't be named. */
    val traktItem: TraktItem? = null,
)

@Immutable
data class DetailsState(
    val channel: Channel,
    val kind: DetailsKind,
    val loading: Boolean = true,
    val film: VodDetails? = null,
    val series: SeriesDetails? = null,
    val selectedSeason: String? = null,
    /** Saved position for a film, or for the series' last-opened episode; 0 if none. */
    val resumeMs: Long = 0L,
    val seriesProgress: SeriesProgress? = null,
)

@Immutable
data class GuideSchedule(
    val channelId: Int,
    val loading: Boolean = true,
    val programmes: List<GuideProgramme> = emptyList(),
    val supported: Boolean = true,
)

private data class TimedListing(val fetchedAt: Long, val programmes: List<GuideProgramme>)

@HiltViewModel
class DialViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DialSettingsStore,
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
    private val programmeRepository: ProgrammeRepository,
    private val reminderStore: ReminderStore,
) : ViewModel() {

    val preferences: StateFlow<DialPreferences> = store.preferences

    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<Channel>>(emptyList())
    val continueWatching: StateFlow<List<Channel>> = _continueWatching.asStateFlow()

    private val _nowNext = MutableStateFlow<Map<Int, List<GuideProgramme>>>(emptyMap())
    val nowNext: StateFlow<Map<Int, List<GuideProgramme>>> = _nowNext.asStateFlow()

    private val _schedule = MutableStateFlow<GuideSchedule?>(null)
    val schedule: StateFlow<GuideSchedule?> = _schedule.asStateFlow()

    /** Full listings for the timeline grid, by channel id. Absent = not loaded yet. */
    private val _listings = MutableStateFlow<Map<Int, List<GuideProgramme>>>(emptyMap())
    val listings: StateFlow<Map<Int, List<GuideProgramme>>> = _listings.asStateFlow()

    /** Streams to open in VLC or another app, collected by App. */
    private val _externalPlayback = MutableSharedFlow<ExternalPlayback>(extraBufferCapacity = 1)
    val externalPlayback: SharedFlow<ExternalPlayback> = _externalPlayback.asSharedFlow()

    /** Short notices (string resources) shown as a toast. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** The film or episode in the built-in player, for subtitle searches and "Up next". */
    private val _nowPlaying = MutableStateFlow<OnDemandPlayback?>(null)
    val nowPlaying: StateFlow<OnDemandPlayback?> = _nowPlaying.asStateFlow()

    /** Intro and credits markers for the series playing now (empty for films and live TV). */
    private val _skipMarkers = MutableStateFlow(SkipMarkers())
    val skipMarkers: StateFlow<SkipMarkers> = _skipMarkers.asStateFlow()

    /** A kids profile's play time today, after adding [deltaMs]. */
    fun addKidsPlayTime(profileId: String, deltaMs: Long): Long = store.addKidsPlayTime(profileId, deltaMs)

    /** Per-channel refresh-rate rules; a version counter so the player re-reads after a change. */
    private val _frameRateVersion = MutableStateFlow(0)
    val frameRateVersion: StateFlow<Int> = _frameRateVersion.asStateFlow()

    fun frameRateMode(channelId: Int): FrameRateMode = store.frameRateMode(channelId)

    fun cycleFrameRateMode(channelId: Int) {
        val next = FrameRateMode.entries.nextAfter(store.frameRateMode(channelId))
        store.saveFrameRateMode(channelId, next)
        _frameRateVersion.update { it + 1 }
    }

    /** Bookmarks for the film or episode playing now. */
    private val _bookmarks = MutableStateFlow<List<Long>>(emptyList())
    val bookmarks: StateFlow<List<Long>> = _bookmarks.asStateFlow()
    private var bookmarkChannelId: Int? = null

    fun loadBookmarks(channelId: Int?) {
        bookmarkChannelId = channelId
        _bookmarks.value = channelId?.let(store::bookmarks).orEmpty()
    }

    fun addBookmark(positionMs: Long) {
        val id = bookmarkChannelId ?: return
        // Two bookmarks within a few seconds of each other are one bookmark.
        if (_bookmarks.value.any { abs(it - positionMs) < BOOKMARK_GAP_MS }) return
        val next = (_bookmarks.value + positionMs).sorted()
        _bookmarks.value = next
        store.saveBookmarks(id, next)
    }

    fun clearBookmarks() {
        val id = bookmarkChannelId ?: return
        _bookmarks.value = emptyList()
        store.saveBookmarks(id, emptyList())
    }

    fun updateSkipMarkers(transform: (SkipMarkers) -> SkipMarkers) {
        val series = _nowPlaying.value?.series ?: return
        val next = transform(_skipMarkers.value)
        _skipMarkers.value = next
        store.saveSkipMarkers(series.id, next)
    }

    private var detailsJob: Job? = null
    private var scheduleJob: Job? = null
    private val nowNextCache = mutableMapOf<Int, TimedListing>()
    private val nowNextInFlight = mutableSetOf<Int>()
    private val guideRequests = Semaphore(4)
    private val credentialsCache = mutableMapOf<String, XtreamCredentials?>()
    private val listingFetchedAt = mutableMapOf<Int, Long>()
    private val listingInFlight = mutableSetOf<Int>()
    private val listingOrder = ArrayDeque<Int>()

    val favouriteGroups: StateFlow<List<FavouriteGroup>> = store.favouriteGroups

    /** Each favourite group's channels, in the group's order (missing ones left out). */
    private val _groupChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupChannels: StateFlow<Map<String, List<Channel>>> = _groupChannels.asStateFlow()

    init {
        viewModelScope.launch {
            store.history.collect { refreshContinueWatching() }
        }
        viewModelScope.launch {
            store.favouriteGroups.collect { groups ->
                val cache = mutableMapOf<Int, Channel?>()
                _groupChannels.value = groups.associate { group ->
                    group.id to group.channelIds.mapNotNull { id ->
                        cache.getOrPut(id) { runCatching { channelRepository.get(id) }.getOrNull() }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ favourite groups */

    fun createGroup(name: String, channelId: Int?) {
        if (name.isBlank()) return
        store.createGroup(name, channelId)
    }

    fun toggleInGroup(groupId: String, channelId: Int) = store.toggleInGroup(groupId, channelId)

    fun moveInGroup(groupId: String, channelId: Int, delta: Int) =
        store.moveInGroup(groupId, channelId, delta)

    fun moveGroup(groupId: String, delta: Int) = store.moveGroup(groupId, delta)

    fun deleteGroup(groupId: String) = store.deleteGroup(groupId)

    /* ------------------------------------------------------------------ settings */

    fun updatePreferences(transform: (DialPreferences) -> DialPreferences) = store.update(transform)

    fun clearContinueWatching() {
        store.clearHistory()
        _continueWatching.value = emptyList()
    }

    /* ------------------------------------------------------------------ startup */

    fun rememberLastChannel(channelId: Int) {
        store.lastChannelId = channelId
    }

    /** Plays the last live channel watched. Returns false if there isn't one any more. */
    suspend fun playLastChannel(): Boolean {
        val id = store.lastChannelId ?: return false
        val channel = channelRepository.get(id) ?: return false
        playerManager.play(MediaCommand.Common(channel.id))
        return true
    }

    /* ------------------------------------------------------------------ details */

    fun openDetails(channel: Channel, playlist: Playlist?) {
        val kind = if (playlist?.isSeries == true) DetailsKind.Series else DetailsKind.Film
        _details.value = DetailsState(channel = channel, kind = kind)
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val credentials = credentialsFor(channel.playlistUrl)
            val itemId = XtreamCatalog.idFromUrl(channel.url)
            when (kind) {
                DetailsKind.Film -> {
                    val film = if (credentials != null && itemId != null) {
                        XtreamCatalog.vodDetails(credentials, itemId)
                    } else null
                    val resume = savedPosition(channel.url)
                    updateDetailsFor(channel.id) {
                        it.copy(loading = false, film = film, resumeMs = resume)
                    }
                }
                DetailsKind.Series -> {
                    val series = if (credentials != null && itemId != null) {
                        XtreamCatalog.seriesDetails(credentials, itemId)
                    } else null
                    val progress = store.seriesProgress(channel.id)
                    val resume = progress?.let { savedPosition(episodeUrl(channel, it)) } ?: 0L
                    val season = progress?.season
                        ?.takeIf { key -> series?.seasons?.any { it.key == key } == true }
                        ?: series?.seasons?.firstOrNull()?.key
                    updateDetailsFor(channel.id) {
                        it.copy(
                            loading = false,
                            series = series,
                            selectedSeason = season,
                            seriesProgress = progress,
                            resumeMs = resume,
                        )
                    }
                }
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /** Applies [transform] only if the details page still shows [channelId]. */
    private fun updateDetailsFor(channelId: Int, transform: (DetailsState) -> DetailsState) {
        _details.update { current ->
            if (current != null && current.channel.id == channelId) transform(current) else current
        }
    }

    fun selectSeason(key: String) {
        _details.update { it?.copy(selectedSeason = key) }
    }

    /** Re-reads saved positions after the player closes, so "Resume from" stays accurate. */
    fun refreshAfterPlayback() {
        refreshContinueWatching()
        val current = _details.value ?: return
        viewModelScope.launch {
            val resume = when (current.kind) {
                DetailsKind.Film -> savedPosition(current.channel.url)
                DetailsKind.Series -> store.seriesProgress(current.channel.id)
                    ?.let { savedPosition(episodeUrl(current.channel, it)) } ?: 0L
            }
            val progress = store.seriesProgress(current.channel.id)
            updateDetailsFor(current.channel.id) {
                it.copy(resumeMs = resume, seriesProgress = progress)
            }
        }
    }

    /* ------------------------------------------------------------------ outside players */

    /**
     * Whether [channel] should open in VLC or another app rather than the built-in player.
     * Channels that need DRM always stay in the built-in player. If VLC was chosen but isn't
     * installed, this says so and falls back to the built-in player.
     */
    fun playsExternally(channel: Channel): Boolean {
        val player = preferences.value.player
        if (player == DialPlayer.BuiltIn || channel.licenseType != null) return false
        // Media-server channels resolve their address at play time; only plain links travel.
        if (!channel.url.startsWith("http://", ignoreCase = true) &&
            !channel.url.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (player == DialPlayer.Vlc && !ExternalPlayers.isInstalled(context, ExternalPlayers.VLC_PACKAGE)) {
            _messages.tryEmit(R.string.dial_player_vlc_missing)
            return false
        }
        return true
    }

    fun playLiveExternally(channel: Channel) {
        viewModelScope.launch {
            runCatching { channelRepository.reportPlayed(channel.id) }
                .onFailure { if (it is CancellationException) throw it }
            emitExternal(url = channel.url, title = channel.title, positionMs = 0L, resumeUrl = null)
        }
    }

    /** Saves where the outside player stopped, or clears it if the video was watched to the end. */
    fun onExternalResult(playback: ExternalPlayback, positionMs: Long, durationMs: Long) {
        val url = playback.resumeUrl ?: return
        viewModelScope.launch {
            val finished = durationMs > 0L && positionMs >= durationMs - FINISHED_MARGIN_MS
            val position = if (finished || positionMs < MIN_RESUME_MS) -1L else positionMs
            runCatching { playerManager.saveCwPosition(url, position) }
                .onFailure { if (it is CancellationException) throw it }
            refreshAfterPlayback()
        }
    }

    private fun emitExternal(url: String, title: String, positionMs: Long, resumeUrl: String?) {
        val player = preferences.value.player.takeIf { it != DialPlayer.BuiltIn } ?: DialPlayer.Ask
        _externalPlayback.tryEmit(
            ExternalPlayback(
                url = url,
                title = title,
                positionMs = positionMs,
                resumeUrl = resumeUrl,
                player = player,
            )
        )
    }

    fun playFilm(channel: Channel, fromStart: Boolean, external: Boolean = false) {
        store.recordOnDemand(channel.id)
        viewModelScope.launch {
            val resume = preferences.value.resumePlayback && !fromStart
            if (external) {
                val position = if (resume) savedPosition(channel.url) else 0L
                emitExternal(channel.url, channel.title, position, resumeUrl = channel.url)
                return@launch
            }
            if (fromStart) playerManager.onResetPlayback(channel.url)
            val film = _details.value?.takeIf { it.channel.id == channel.id }?.film
            val filmTitle = film?.title ?: OpenSubtitles.cleanTitle(channel.title)
            val filmYear = film?.year ?: OpenSubtitles.yearIn(channel.title)
            _nowPlaying.value = OnDemandPlayback(
                subtitleTarget = SubtitleTarget(
                    title = film?.title ?: channel.title,
                    year = filmYear,
                    tmdbId = film?.tmdbId,
                ),
                traktItem = TraktItem.Movie(
                    title = filmTitle,
                    year = filmYear?.toIntOrNull(),
                    tmdbId = film?.tmdbId?.toIntOrNull(),
                ),
            )
            _skipMarkers.value = SkipMarkers()
            playerManager.play(MediaCommand.Common(channel.id), applyContinueWatching = resume)
        }
    }

    fun playEpisode(
        series: Channel,
        episode: SeriesEpisode,
        fromStart: Boolean,
        external: Boolean = false,
    ) {
        store.recordOnDemand(series.id)
        store.saveSeriesProgress(
            series.id,
            SeriesProgress(
                season = episode.season,
                episodeId = episode.id,
                episodeNum = episode.episodeNum,
                title = episode.title,
                containerExtension = episode.containerExtension,
            )
        )
        viewModelScope.launch {
            val info = episode.toEpisodeInfo()
            val resume = preferences.value.resumePlayback && !fromStart
            if (external) {
                val url = series.copyXtreamEpisode(info).url
                val position = if (resume) savedPosition(url) else 0L
                val title = listOf(series.title, episode.title).filter { it.isNotBlank() }.joinToString(" · ")
                emitExternal(url, title, position, resumeUrl = url)
                return@launch
            }
            if (fromStart) playerManager.onResetPlayback(series.copyXtreamEpisode(info).url)
            val details = _details.value?.takeIf { it.channel.id == series.id }?.series
            _nowPlaying.value = OnDemandPlayback(
                subtitleTarget = SubtitleTarget(
                    title = details?.title ?: series.title,
                    year = details?.year,
                    parentTmdbId = details?.tmdbId,
                    season = episode.season.toIntOrNull(),
                    episode = episode.episodeNum?.toIntOrNull(),
                ),
                series = series,
                episode = episode,
                traktItem = episode.episodeNum?.toIntOrNull()?.let { number ->
                    TraktItem.Episode(
                        title = details?.title ?: OpenSubtitles.cleanTitle(series.title),
                        year = details?.year?.toIntOrNull(),
                        showTmdbId = details?.tmdbId?.toIntOrNull(),
                        season = episode.season.toIntOrNull() ?: 1,
                        number = number,
                    )
                },
            )
            _skipMarkers.value = store.skipMarkers(series.id)
            playerManager.play(MediaCommand.XtreamEpisode(series.id, info), applyContinueWatching = resume)
        }
    }

    /** "Continue S2 E5": the saved episode, resumed where it stopped. */
    fun continueSeries(series: Channel, progress: SeriesProgress, external: Boolean = false) {
        playEpisode(
            series = series,
            episode = SeriesEpisode(
                id = progress.episodeId,
                season = progress.season,
                episodeNum = progress.episodeNum,
                title = progress.title.orEmpty(),
                containerExtension = progress.containerExtension,
                plot = null,
                duration = null,
                image = null,
            ),
            fromStart = false,
            external = external,
        )
    }

    private fun episodeUrl(series: Channel, progress: SeriesProgress): String =
        series.copyXtreamEpisode(
            SeriesEpisode(
                id = progress.episodeId,
                season = progress.season,
                episodeNum = progress.episodeNum,
                title = progress.title.orEmpty(),
                containerExtension = progress.containerExtension,
                plot = null,
                duration = null,
                image = null,
            ).toEpisodeInfo()
        ).url

    private suspend fun savedPosition(url: String): Long =
        runCatching { playerManager.getCwPosition(url) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it > MIN_RESUME_MS }
            ?: 0L

    /* ------------------------------------------------------------------ continue watching */

    fun refreshContinueWatching() {
        val ids = store.history.value
        viewModelScope.launch {
            val items = ids.mapNotNull { id ->
                val channel = channelRepository.get(id)
                if (channel == null) {
                    store.forget(id)
                    return@mapNotNull null
                }
                val playlist = playlistRepository.get(channel.playlistUrl)
                when {
                    playlist?.isSeries == true ->
                        channel.takeIf { store.seriesProgress(id) != null }
                    playlist?.isVod == true ->
                        channel.takeIf { savedPosition(channel.url) > 0L }
                    else -> null
                }
            }
            _continueWatching.value = items
        }
    }

    /* ------------------------------------------------------------------ guide and catch-up */

    fun requestNowNext(channel: Channel) {
        val now = System.currentTimeMillis()
        val cached = nowNextCache[channel.id]
        val fresh = cached != null &&
            now - cached.fetchedAt < NOW_NEXT_TTL_MS &&
            cached.programmes.firstOrNull()?.hasEndedBy(now) != true
        if (fresh || !nowNextInFlight.add(channel.id)) return
        viewModelScope.launch {
            try {
                val listings = guideRequests.withPermit {
                    val credentials = credentialsFor(channel.playlistUrl)
                    val streamId = XtreamCatalog.idFromUrl(channel.url)
                    if (credentials == null || streamId == null) {
                        // M3U playlists: the next few programmes from their XMLTV guide.
                        xmltvProgrammes(channel, now, now + FUTURE_WINDOW_MS).orEmpty().take(3)
                    } else {
                        XtreamCatalog.shortEpg(credentials, streamId)
                    }
                }
                val current = listings.filterNot { it.hasEndedBy(System.currentTimeMillis()) }
                nowNextCache[channel.id] = TimedListing(System.currentTimeMillis(), current)
                _nowNext.update { it + (channel.id to current) }
            } finally {
                nowNextInFlight.remove(channel.id)
            }
        }
    }

    /**
     * Loads one channel's full listing for the timeline grid. Called as rows scroll into view;
     * cached for [LISTING_TTL_MS], and only the most recent [MAX_LISTINGS] channels are kept.
     */
    fun requestListing(channel: Channel) {
        val fetchedAt = listingFetchedAt[channel.id]
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < LISTING_TTL_MS) return
        if (!listingInFlight.add(channel.id)) return
        viewModelScope.launch {
            try {
                val programmes = guideRequests.withPermit {
                    val credentials = credentialsFor(channel.playlistUrl)
                    val streamId = XtreamCatalog.idFromUrl(channel.url)
                    if (credentials == null || streamId == null) {
                        val start = System.currentTimeMillis()
                        xmltvProgrammes(channel, start - GRID_PAST_MS, start + GRID_FUTURE_MS).orEmpty()
                    } else {
                        XtreamCatalog.fullEpg(credentials, streamId)
                    }
                }
                val now = System.currentTimeMillis()
                val trimmed = programmes.filter {
                    it.endMillis > now - GRID_PAST_MS && it.startMillis < now + GRID_FUTURE_MS
                }
                listingFetchedAt[channel.id] = now
                listingOrder.remove(channel.id)
                listingOrder.addLast(channel.id)
                val evicted = mutableListOf<Int>()
                while (listingOrder.size > MAX_LISTINGS) {
                    val oldest = listingOrder.removeFirst()
                    listingFetchedAt.remove(oldest)
                    evicted += oldest
                }
                _listings.update { (it - evicted.toSet()) + (channel.id to trimmed) }
            } finally {
                listingInFlight.remove(channel.id)
            }
        }
    }

    fun loadSchedule(channel: Channel) {
        if (_schedule.value?.channelId == channel.id && _schedule.value?.loading == false) return
        scheduleJob?.cancel()
        _schedule.value = GuideSchedule(channelId = channel.id)
        scheduleJob = viewModelScope.launch {
            // Let focus settle while scrolling the channel list before asking the server.
            delay(250)
            val credentials = credentialsFor(channel.playlistUrl)
            val streamId = XtreamCatalog.idFromUrl(channel.url)
            val now = System.currentTimeMillis()
            if (credentials == null || streamId == null) {
                // M3U playlists: their XMLTV guide, if one is attached.
                val programmes = xmltvProgrammes(channel, now, now + FUTURE_WINDOW_MS)
                _schedule.value = if (programmes == null) {
                    GuideSchedule(channel.id, loading = false, supported = false)
                } else {
                    GuideSchedule(channel.id, loading = false, programmes = programmes)
                }
                return@launch
            }
            val programmes = XtreamCatalog.fullEpg(credentials, streamId)
                .filter { it.endMillis > now - ARCHIVE_WINDOW_MS && it.startMillis < now + FUTURE_WINDOW_MS }
                // Past programmes are only useful if they can be replayed.
                .filter { !it.hasEndedBy(now) || it.hasArchive }
            _schedule.value = GuideSchedule(channel.id, loading = false, programmes = programmes)
        }
    }

    /* ------------------------------------------------------------------ what you missed */

    /** Programmes that ended in the last day on favourite channels and can still be replayed. */
    private val _missed = MutableStateFlow<List<MissedProgramme>>(emptyList())
    val missed: StateFlow<List<MissedProgramme>> = _missed.asStateFlow()
    private var missedLoadedAt = 0L
    private var missedJob: Job? = null

    /** This evening's programmes on favourite channels (loaded alongside "what you missed"). */
    private val _tonight = MutableStateFlow<List<TonightProgramme>>(emptyList())
    val tonight: StateFlow<List<TonightProgramme>> = _tonight.asStateFlow()

    /** Programme reminders still to come. */
    val reminders: StateFlow<List<Reminder>> = reminderStore.reminders

    /** Sets or clears a reminder; true when it's now set. */
    fun toggleReminder(channel: Channel, programme: GuideProgramme): Boolean = reminderStore.toggle(channel, programme)

    fun dismissReminder(reminder: Reminder) = reminderStore.remove(reminder)

    /** The channel a reminder points at (null if it has gone from the playlist). */
    suspend fun channelById(id: Int): Channel? = channelRepository.get(id)

    fun purgeReminders() = reminderStore.purge()

    fun loadMissed(favourites: List<Channel>) {
        if (favourites.isEmpty()) {
            _missed.value = emptyList()
            _tonight.value = emptyList()
            return
        }
        if (System.currentTimeMillis() - missedLoadedAt < MISSED_TTL_MS) return
        missedLoadedAt = System.currentTimeMillis()
        missedJob?.cancel()
        missedJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val window = tonightWindow(now)
            val found = mutableListOf<MissedProgramme>()
            val evening = mutableListOf<TonightProgramme>()
            for ((position, channel) in favourites.take(TONIGHT_CHANNELS).withIndex()) {
                val credentials = credentialsFor(channel.playlistUrl)
                val programmes = if (credentials != null) {
                    val streamId = XtreamCatalog.idFromUrl(channel.url) ?: continue
                    guideRequests.withPermit {
                        runCatching { XtreamCatalog.fullEpg(credentials, streamId) }.getOrDefault(emptyList())
                    }
                } else {
                    xmltvProgrammes(channel, now - MISSED_WINDOW_MS, window.last) ?: continue
                }
                if (position < MISSED_CHANNELS) programmes
                    .filter { it.hasArchive && it.hasEndedBy(now) && it.endMillis > now - MISSED_WINDOW_MS }
                    .sortedByDescending { it.endMillis }
                    .take(MISSED_PER_CHANNEL)
                    .forEach { found += MissedProgramme(channel, it) }
                programmes
                    .filter { !it.hasEndedBy(now) && it.startMillis <= window.last && it.endMillis > window.first }
                    .sortedBy { it.startMillis }
                    .take(TONIGHT_PER_CHANNEL)
                    .forEach { evening += TonightProgramme(channel, it) }
                _missed.value = found.sortedByDescending { it.programme.endMillis }.take(MISSED_MAX)
                _tonight.value = evening.sortedBy { it.programme.startMillis }.take(TONIGHT_MAX)
            }
        }
    }

    /** Live TV and catch-up aren't films or episodes. */
    fun clearNowPlaying() {
        _nowPlaying.value = null
        _skipMarkers.value = SkipMarkers()
    }

    /**
     * The episode after the one playing: next in its season, else the first of the next season.
     * Needs the series page to have loaded its seasons (it has, if the episode was started there).
     */
    fun nextEpisode(): Pair<Channel, SeriesEpisode>? {
        val playing = _nowPlaying.value ?: return null
        val series = playing.series ?: return null
        val current = playing.episode ?: return null
        val seasons = _details.value?.takeIf { it.channel.id == series.id }?.series?.seasons
            ?: return null
        val all = seasons.flatMap { it.episodes }
        val index = all.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return all.getOrNull(index + 1)?.let { series to it }
    }

    fun playCatchUp(channel: Channel, programme: GuideProgramme, external: Boolean = false) {
        _nowPlaying.value = null
        _skipMarkers.value = SkipMarkers()
        viewModelScope.launch {
            val credentials = credentialsFor(channel.playlistUrl) ?: return@launch
            val streamId = XtreamCatalog.idFromUrl(channel.url) ?: return@launch
            val url = XtreamCatalog.timeshiftUrl(credentials, streamId, programme) ?: return@launch
            if (external) {
                emitExternal(url, programme.title, positionMs = 0L, resumeUrl = null)
                return@launch
            }
            playerManager.play(
                MediaCommand.Url(channelId = channel.id, url = url, title = programme.title),
                applyContinueWatching = false,
            )
        }
    }

    /**
     * Live TV paused longer than the player can hold: the channel's catch-up stream from the
     * moment of the pause, so nothing is missed. Without catch-up for that programme, playback
     * simply carries on from the live picture.
     */
    fun resumeLiveFrom(channel: Channel, pausedAtMs: Long) {
        viewModelScope.launch {
            val credentials = credentialsFor(channel.playlistUrl)
            val streamId = XtreamCatalog.idFromUrl(channel.url)
            val programme = if (credentials != null && streamId != null) {
                guideRequests.withPermit {
                    runCatching { XtreamCatalog.fullEpg(credentials, streamId) }.getOrDefault(emptyList())
                }.firstOrNull { it.hasArchive && pausedAtMs in it.startMillis until it.endMillis }
            } else null
            val url = if (credentials != null && streamId != null && programme != null) {
                XtreamCatalog.timeshiftUrl(credentials, streamId, programme)
            } else null
            if (url == null) {
                playerManager.pauseOrContinue(true)
                return@launch
            }
            _nowPlaying.value = null
            _skipMarkers.value = SkipMarkers()
            playerManager.play(
                MediaCommand.Url(channelId = channel.id, url = url, title = programme.title),
                applyContinueWatching = false,
            )
            // Into the recording at the moment the pause began.
            delay(RESUME_SEEK_DELAY_MS)
            playerManager.player.value?.seekTo((pausedAtMs - programme.startMillis).coerceAtLeast(0L))
        }
    }

    /** Whether [channel] is the kind that can have catch-up (an Xtream live stream). */
    fun mayHaveCatchUp(channel: Channel): Boolean = channel.url.contains("/live/") && XtreamCatalog.idFromUrl(channel.url) != null

    /**
     * Programmes for an M3U channel from its playlist's XMLTV guide, matched on tvg-id. Null when
     * the channel has no tvg-id or the playlist has no guide attached.
     */
    private suspend fun xmltvProgrammes(channel: Channel, from: Long, to: Long): List<GuideProgramme>? {
        val relationId = channel.relationId?.takeIf { it.isNotBlank() } ?: return null
        val playlist = playlistRepository.get(channel.playlistUrl) ?: return null
        if (playlist.epgUrls.isEmpty()) return null
        return runCatching {
            programmeRepository.getProgrammesInRange(channel.playlistUrl, relationId, from, to)
        }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
            .filter { it.end > it.start }
            .map { programme ->
                GuideProgramme(
                    title = programme.title,
                    description = programme.description,
                    startMillis = programme.start,
                    endMillis = programme.end,
                    serverStart = null,
                    hasArchive = false,
                )
            }
    }

    private suspend fun credentialsFor(playlistUrl: String): XtreamCredentials? {
        if (credentialsCache.containsKey(playlistUrl)) return credentialsCache[playlistUrl]
        val credentials = XtreamCatalog.credentialsFor(playlistRepository.get(playlistUrl))
        credentialsCache[playlistUrl] = credentials
        return credentials
    }

    private companion object {
        const val MIN_RESUME_MS = 30_000L
        const val FINISHED_MARGIN_MS = 3 * 60_000L
        const val NOW_NEXT_TTL_MS = 10 * 60_000L
        const val ARCHIVE_WINDOW_MS = 7 * 24 * 60 * 60_000L
        const val MISSED_WINDOW_MS = 24 * 60 * 60_000L
        const val MISSED_TTL_MS = 30 * 60_000L
        const val MISSED_CHANNELS = 12
        const val MISSED_PER_CHANNEL = 2
        const val MISSED_MAX = 20
        const val TONIGHT_PER_CHANNEL = 3
        const val TONIGHT_CHANNELS = 20
        const val RESUME_SEEK_DELAY_MS = 400L
        const val TONIGHT_MAX = 24
        const val FUTURE_WINDOW_MS = 24 * 60 * 60_000L
        const val LISTING_TTL_MS = 30 * 60_000L
        const val MAX_LISTINGS = 300
        const val GRID_PAST_MS = 26 * 60 * 60_000L
        const val GRID_FUTURE_MS = 26 * 60 * 60_000L
    }
}
