package com.m3u.tv.stremio

import android.content.Context
import android.net.Uri
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
import com.m3u.tv.SecretName
import com.m3u.tv.SecretStore
import com.m3u.tv.TraktService
import com.m3u.tv.TraktSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StremioPage { Browse, Details, Streams, Addons, Rows }

private const val MAX_ROW_NAME = 40

@Immutable
data class StremioUiState(
    val page: StremioPage = StremioPage.Browse,
    val rows: List<CatalogRow> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val details: MetaDetails? = null,
    val detailsLoading: Boolean = false,
    val season: Int? = null,
    val streams: List<StreamSource> = emptyList(),
    val streamsLoading: Boolean = false,
    val resolving: String? = null,
    val addons: List<InstalledAddon> = emptyList(),
    val hasRealDebrid: Boolean = false,
    val hasTorBox: Boolean = false,
    val p2p: Boolean = true,
    val torrServe: String = "http://127.0.0.1:8090",
    val torrServeUp: Boolean? = null,
    val continueWatching: List<CatalogItem> = emptyList(),
    val library: List<CatalogItem> = emptyList(),
    val upNext: List<CatalogItem> = emptyList(),
    val calendar: List<CatalogItem> = emptyList(),
    val shelf: String = "home",
    val inLibrary: Boolean = false,
    val searchKind: String = "all",
    val subtitles: List<StreamSource> = emptyList(),
    val traktUser: String? = null,
    val traktCode: String? = null,
    val layout: InfinityLayout = InfinityLayout(),
    val traktUrl: String? = null,
)

@HiltViewModel
class StremioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: StremioAddonStore,
    private val shelfStore: InfinityShelf,
    private val secrets: SecretStore,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val playerManager: PlayerManager,
    private val trakt: TraktService,
    private val layoutStore: InfinityLayoutStore,
) : ViewModel() {
    private val _state = MutableStateFlow(StremioUiState())
    val state: StateFlow<StremioUiState> = _state.asStateFlow()

    private var playTitle: String = ""
    private var playPoster: String? = null
    private var playRelation: String = ""
    private var playCategory: String = "Addons"

    init {
        viewModelScope.launch {
            try {
                store.addons.collect { list ->
                    _state.update {
                        it.copy(
                            addons = list,
                            hasRealDebrid = secrets.has(SecretName.RealDebrid),
                            hasTorBox = secrets.has(SecretName.TorBox),
                            p2p = store.p2pEnabled,
                            torrServe = store.torrServeUrl,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A bad saved addon must not take the settings page down with it.
            }
        }
        viewModelScope.launch {
            layoutStore.layout.collect { layout -> _state.update { it.copy(layout = layout) } }
        }
        // One Trakt sign-in for the whole app (Settings > Services owns it); this page mirrors it.
        viewModelScope.launch {
            trakt.account.collect { account ->
                val before = _state.value.traktUser
                _state.update { it.copy(traktUser = account?.username) }
                if ((before == null) != (account == null)) refresh()
            }
        }
        viewModelScope.launch {
            var previous: TraktSignIn = TraktSignIn.Idle
            trakt.signIn.collect { step ->
                val justFailed = step is TraktSignIn.Failed && previous !is TraktSignIn.Failed
                previous = step
                _state.update {
                    when (step) {
                        is TraktSignIn.Code -> it.copy(traktCode = step.userCode, traktUrl = step.url)
                        is TraktSignIn.Failed -> it.copy(
                            traktCode = null,
                            traktUrl = null,
                            message = if (justFailed) "Trakt sign-in didn't finish. Try again." else it.message,
                        )
                        TraktSignIn.Idle -> it.copy(traktCode = null, traktUrl = null)
                    }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(loading = true, message = null) }
                val addons = store.enabled().filter { addon ->
                    "catalog" in addon.resources && addon.catalogs.isNotEmpty()
                }
                val rows = mutableListOf<CatalogRow>()
                for (addon in addons) {
                    val catalogs = addon.catalogs
                        .filter { it.type == "movie" || it.type == "series" }
                        .filterNot { it.extra.singleOrNull() == "search" }
                        .take(3)
                    for (catalog in catalogs) {
                        val items = runCatching {
                            StremioClient.catalog(addon, catalog.type, catalog.id).take(24)
                        }.getOrDefault(emptyList())
                        if (items.isNotEmpty()) {
                            rows += CatalogRow(
                                addonId = addon.id,
                                addonName = addon.name,
                                type = catalog.type,
                                catalogId = catalog.id,
                                name = catalog.name.ifBlank { addon.name },
                                items = items,
                            )
                        }
                    }
                }
                val continued = runCatching { continueItems() }.getOrDefault(emptyList())
                val shelves = runCatching { loadShelves() }.getOrDefault(ShelfBundle())
                _state.update {
                    it.copy(
                        loading = false,
                        rows = rows,
                        continueWatching = mergePeople(shelves.playback, continued),
                        library = mergePeople(shelves.watchlist, shelfStore.library()),
                        upNext = shelves.upNext,
                        calendar = shelves.calendar,
                        traktUser = shelves.user ?: it.traktUser,
                        message = if (rows.isEmpty() && addons.isNotEmpty()) "Those addons didn't return a catalog." else null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, message = error.message ?: "Addons didn't open") }
            }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val kind = _state.value.searchKind
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, page = StremioPage.Browse, shelf = "home") }
            val items = mutableListOf<CatalogItem>()
            for (addon in store.enabled()) {
                val types: List<String?> = if (kind == "movie" || kind == "series") listOf(kind) else listOf(null)
                for (type in types) {
                    items += runCatching { StremioClient.search(addon, trimmed, type) }.getOrDefault(emptyList())
                }
            }
            val unique = items.distinctBy { it.type to it.id }.take(40)
            _state.update {
                it.copy(
                    loading = false,
                    rows = if (unique.isEmpty()) emptyList() else listOf(
                        CatalogRow("search", "Search", kind, "search", "Results for “$trimmed”", unique),
                    ),
                    message = if (unique.isEmpty()) "Nothing matched." else null,
                )
            }
        }
    }

    fun setSearchKind(kind: String) = _state.update { it.copy(searchKind = kind) }

    fun setShelf(shelf: String) = _state.update { it.copy(shelf = shelf, page = StremioPage.Browse, message = null) }

    /* ------------------------------------------------------------------------ row layout */

    fun openRows() = _state.update { it.copy(page = StremioPage.Rows, message = null) }

    fun toggleRowHidden(key: String) = layoutStore.updateRow(key) { it.copy(hidden = !it.hidden) }

    fun renameRow(key: String, name: String) = layoutStore.updateRow(key) { it.copy(name = name.trim().take(MAX_ROW_NAME).ifBlank { null }) }

    fun moveRow(key: String, delta: Int) {
        val layout = _state.value.layout
        val rows = _state.value.rows
        layoutStore.move(layout.arrange(rows), rows.filter { layout.of(it.key).hidden }, key, delta)
    }

    fun setCardStyle(style: CardStyle) = layoutStore.update { it.copy(cardStyle = style) }

    fun setContinueStyle(style: CardStyle) = layoutStore.update { it.copy(continueStyle = style) }

    fun setHero(enabled: Boolean) = layoutStore.update { it.copy(hero = enabled) }

    fun setRatingsOnCards(enabled: Boolean) = layoutStore.update { it.copy(ratingsOnCards = enabled) }

    fun resetLayout() = layoutStore.reset()

    fun openAddons() = _state.update {
        it.copy(
            page = StremioPage.Addons,
            message = null,
            hasRealDebrid = secrets.has(SecretName.RealDebrid),
            hasTorBox = secrets.has(SecretName.TorBox),
        )
    }

    fun openItem(item: CatalogItem) {
        playTitle = item.name
        playPoster = item.poster
        playRelation = "${item.type}:${item.id}"
        playCategory = if (item.type == "series") "Series" else "Films"
        viewModelScope.launch {
            _state.update { it.copy(page = StremioPage.Details, detailsLoading = true, details = null, message = null) }
            val addon = store.enabled().firstOrNull { "meta" in it.resources }
            val fetched = addon?.let { source ->
                runCatching { StremioClient.meta(source, item.type, item.id) }.getOrNull()
            }
            val details = fetched ?: MetaDetails(
                id = item.id,
                type = item.type,
                name = item.name,
                poster = item.poster,
                background = item.background,
                logo = null,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                imdbId = item.id.takeIf { it.startsWith("tt") },
                videos = emptyList(),
            )
            _state.update {
                it.copy(
                    detailsLoading = false,
                    details = details,
                    inLibrary = shelfStore.contains(details.type, details.id),
                    season = details.videos.mapNotNull { video -> video.season }.minOrNull(),
                )
            }
        }
    }

    fun selectSeason(season: Int) = _state.update { it.copy(season = season) }

    fun loadStreams(type: String, id: String, title: String) {
        playTitle = title
        playRelation = "$type:$id"
        viewModelScope.launch {
            _state.update { it.copy(page = StremioPage.Streams, streamsLoading = true, streams = emptyList(), message = null) }
            val found = mutableListOf<StreamSource>()
            val subs = mutableListOf<StreamSource>()
            for (addon in store.enabled()) {
                if ("stream" in addon.resources) {
                    found += runCatching { StremioClient.streams(addon, type, id) }.getOrDefault(emptyList())
                }
                if ("subtitles" in addon.resources) {
                    subs += runCatching { StremioClient.streams(addon, type, id) }.getOrDefault(emptyList())
                        .filter { it.url?.startsWith("http") == true }
                }
            }
            val sorted = found.distinctBy { it.playableUrl to it.name }.sortedWith(
                compareByDescending<StreamSource> { it.isDebrid }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.seeders?.toIntOrNull() ?: 0 },
            )
            _state.update {
                it.copy(
                    streamsLoading = false,
                    streams = sorted,
                    subtitles = subs.distinctBy { it.url }.take(12),
                    message = if (sorted.isEmpty()) "No streams. Install Torrentio or AIOStreams, and add a debrid token if you have one." else null,
                )
            }
        }
    }

    fun play(source: StreamSource, onPlaying: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(resolving = source.name, message = null) }
            runCatching {
                val resolved = StreamResolver.resolve(
                    source = source,
                    realDebrid = secrets.get(SecretName.RealDebrid),
                    torbox = secrets.get(SecretName.TorBox),
                    p2pEnabled = store.p2pEnabled,
                    torrServeUrl = store.torrServeUrl,
                    cacheDir = context.cacheDir,
                )
                val id = rememberChannel(resolved.url)
                if (id == 0) throw DebridException("Couldn't save this title to the library")
                playerManager.play(MediaCommand.Url(channelId = id, url = resolved.url, title = playTitle))
                resolved.via
            }.onSuccess { via ->
                shelfStore.markWatched(playRelation)
                val subUrl = _state.value.subtitles.firstOrNull { it.url?.startsWith("http") == true }?.url
                val subName = _state.value.subtitles.firstOrNull { it.url == subUrl }?.name
                if (subUrl != null) {
                    runCatching {
                        playerManager.addSubtitle(
                            Uri.parse(subUrl),
                            if (subUrl.endsWith(".srt", true)) "application/x-subrip" else "text/vtt",
                            null,
                            subName?.ifBlank { "Subtitles" } ?: "Subtitles",
                        )
                    }
                }
                val clientId = secrets.get(SecretName.TraktClientId)
                val access = secrets.get(SecretName.TraktAccess)
                if (clientId != null && access != null) {
                    runCatching { TraktClient.scrobble(clientId, access, playRelation, start = true) }
                }
                _state.update { it.copy(resolving = null, message = "Playing with $via") }
                onPlaying()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(resolving = null, message = error.message ?: "Couldn't play that") }
            }
        }
    }

    fun installPreset(preset: PresetAddon) {
        if (preset.manifestUrl.isBlank()) {
            _state.update { it.copy(message = "Paste the manifest URL from your ${preset.name} config in Settings → Addons.") }
            return
        }
        val url = if ("torrentio" in preset.manifestUrl) torrentioUrl() else preset.manifestUrl
        install(url)
    }

    fun installStarter() {
        viewModelScope.launch {
            installAwait(AddonCatalogPresets.all.first { it.id == "com.linvo.cinemeta" }.manifestUrl)
            installAwait(torrentioUrl())
            refresh()
        }
    }

    fun install(url: String) {
        viewModelScope.launch {
            try {
                installAwait(url)
                if (_state.value.rows.isEmpty()) refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "Couldn't add that addon") }
            }
        }
    }

    fun remove(id: String) = store.remove(id)

    fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    fun saveSecret(name: SecretName, value: String) {
        try {
            secrets.put(name, value)
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: "Couldn't save that key") }
            return
        }
        _state.update {
            it.copy(
                hasRealDebrid = secrets.has(SecretName.RealDebrid),
                hasTorBox = secrets.has(SecretName.TorBox),
            )
        }
        syncTorrentio()
    }

    fun removeSecret(name: SecretName) {
        secrets.remove(name)
        _state.update {
            it.copy(
                hasRealDebrid = secrets.has(SecretName.RealDebrid),
                hasTorBox = secrets.has(SecretName.TorBox),
            )
        }
        syncTorrentio()
    }

    fun setP2p(enabled: Boolean) {
        store.p2pEnabled = enabled
        _state.update { it.copy(p2p = enabled) }
    }

    fun setTorrServe(url: String) {
        store.torrServeUrl = url
        _state.update { it.copy(torrServe = store.torrServeUrl, torrServeUp = null) }
    }

    fun testTorrServe() {
        viewModelScope.launch {
            val up = TorrServeClient.alive(store.torrServeUrl)
            _state.update { it.copy(torrServeUp = up, message = if (up) "TorrServe is running." else "TorrServe didn't answer.") }
        }
    }

    /** True when Back stayed inside Addons. */
    fun back(): Boolean {
        val next = when (_state.value.page) {
            StremioPage.Streams -> StremioPage.Details
            StremioPage.Details -> StremioPage.Browse
            StremioPage.Addons -> StremioPage.Browse
            StremioPage.Rows -> StremioPage.Browse
            StremioPage.Browse -> return false
        }
        _state.update { it.copy(page = next, resolving = null, message = null) }
        return true
    }

    fun toggleLibrary() {
        val details = _state.value.details ?: return
        val item = details.toShelfItem()
        val added = shelfStore.toggle(item)
        _state.update {
            it.copy(
                inLibrary = added,
                library = shelfStore.library(),
                message = if (added) "Saved to your library" else "Removed from your library",
            )
        }
    }

    fun connectTrakt() {
        if (!trakt.hasClient) {
            _state.update { it.copy(message = "Add a Trakt client ID and secret in Settings → Services first.") }
            return
        }
        trakt.startSignIn()
    }

    fun disconnectTrakt() {
        trakt.signOut()
        _state.update { it.copy(message = "Trakt disconnected") }
    }

    private suspend fun continueItems(): List<CatalogItem> =
        channelDao.getByPlaylistUrl(StremioIds.PLAYLIST_URL)
            .filter { it.seen > 0 && !it.relationId.isNullOrBlank() }
            .sortedByDescending { it.seen }
            .take(16)
            .mapNotNull { channel ->
                val relation = channel.relationId ?: return@mapNotNull null
                val type = relation.substringBefore(':')
                val id = relation.substringAfter(':', "")
                if (type.isBlank() || id.isBlank() || id == relation) return@mapNotNull null
                CatalogItem(
                    id = id,
                    type = type,
                    name = channel.title,
                    poster = channel.cover,
                    background = null,
                    posterShape = null,
                    releaseInfo = null,
                    imdbRating = null,
                    description = null,
                )
            }

    private suspend fun loadShelves(): ShelfBundle {
        val library = shelfStore.library()
        val local = localSeries(library)
        val clientId = secrets.get(SecretName.TraktClientId)
        var access = secrets.get(SecretName.TraktAccess)
        val refreshToken = secrets.get(SecretName.TraktRefresh)
        val secret = secrets.get(SecretName.TraktClientSecret)
        if (clientId != null && access == null && !refreshToken.isNullOrBlank() && !secret.isNullOrBlank()) {
            access = runCatching { TraktClient.refresh(clientId, secret, refreshToken) }.getOrNull()?.also { session ->
                secrets.put(SecretName.TraktAccess, session.access)
                if (session.refresh.isNotBlank()) secrets.put(SecretName.TraktRefresh, session.refresh)
            }?.access
        }
        if (clientId == null || access == null) {
            return ShelfBundle(upNext = local.first, calendar = local.second)
        }
        val user = runCatching { TraktClient.username(clientId, access) }.getOrNull()
        val watch = runCatching { TraktClient.watchlist(clientId, access) }.getOrDefault(emptyList())
        val playing = runCatching { TraktClient.playback(clientId, access) }.getOrDefault(emptyList())
        val upcoming = runCatching { TraktClient.upcoming(clientId, access, java.time.LocalDate.now().toString()) }
            .getOrDefault(emptyList())
        return ShelfBundle(
            watchlist = watch,
            playback = playing,
            upNext = mergePeople(local.first, upcoming.take(8)),
            calendar = if (upcoming.isNotEmpty()) upcoming else local.second,
            user = user,
        )
    }

    private suspend fun localSeries(library: List<CatalogItem>): Pair<List<CatalogItem>, List<CatalogItem>> {
        val metaAddon = store.enabled().firstOrNull { "meta" in it.resources } ?: return emptyList<CatalogItem>() to emptyList()
        val watched = shelfStore.watched()
        val now = System.currentTimeMillis()
        val horizon = now + 14L * 24 * 60 * 60 * 1000
        val next = mutableListOf<CatalogItem>()
        val calendar = mutableListOf<CatalogItem>()
        for (item in library.filter { it.type == "series" }.take(5)) {
            val details = runCatching { StremioClient.meta(metaAddon, "series", item.id) }.getOrNull() ?: continue
            details.videos.forEach { video ->
                val released = parseReleased(video.released) ?: return@forEach
                if (released in now..horizon) calendar += video.asShelf(details.name)
            }
            val episode = details.videos
                .filter { it.season != null && it.episode != null }
                .sortedWith(compareBy({ it.season }, { it.episode }))
                .firstOrNull { "series:${it.id}" !in watched }
            if (episode != null) next += episode.asShelf(details.name)
        }
        return next to calendar.distinctBy { it.id }.take(16)
    }

    private fun mergePeople(primary: List<CatalogItem>, extra: List<CatalogItem>): List<CatalogItem> =
        (primary + extra).distinctBy { it.type to it.id }.take(24)

    private fun parseReleased(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun MetaDetails.toShelfItem() = CatalogItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        background = background,
        posterShape = null,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        description = description,
    )

    private fun MetaVideo.asShelf(show: String) = CatalogItem(
        id = id,
        type = "series",
        name = listOf(show, shelfEpisode(this)).filter { it.isNotBlank() }.joinToString(" · "),
        poster = thumbnail,
        background = thumbnail,
        posterShape = null,
        releaseInfo = released?.take(10),
        imdbRating = null,
        description = overview,
    )

    private fun shelfEpisode(video: MetaVideo): String {
        val season = video.season?.toString()?.padStart(2, '0')
        val episode = video.episode?.toString()?.padStart(2, '0')
        val code = if (season != null && episode != null) "S${season}E${episode}" else ""
        return listOf(code, video.title).filter { it.isNotBlank() }.joinToString(" ")
    }

    private suspend fun installAwait(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) {
            _state.update { it.copy(message = "Paste a full manifest URL, starting with https://") }
            return
        }
        _state.update { it.copy(message = "Installing…") }
        runCatching { StremioClient.fetchManifest(trimmed) }
            .onSuccess { addon ->
                store.upsert(addon)
                _state.update { it.copy(message = "Installed ${addon.name}") }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(message = error.message ?: "Addon didn't answer") }
            }
    }

    private fun torrentioUrl(): String = TorrentioConfig.manifestUrl(
        secrets.get(SecretName.RealDebrid),
        secrets.get(SecretName.TorBox),
    )

    private fun syncTorrentio() {
        if (store.addons.value.none { it.manifestUrl.contains("torrentio.strem.fun") || it.id.contains("torrentio") }) return
        viewModelScope.launch {
            val url = torrentioUrl()
            runCatching { StremioClient.fetchManifest(url) }
                .onSuccess { store.upsert(it) }
                .onFailure { store.replaceTorrentio(url) }
        }
    }

    private suspend fun rememberChannel(url: String): Int {
        if (playlistDao.get(StremioIds.PLAYLIST_URL) == null) {
            playlistDao.insertOrReplace(
                Playlist(
                    title = StremioIds.PLAYLIST_TITLE,
                    url = StremioIds.PLAYLIST_URL,
                    source = DataSource.M3U,
                ),
            )
        }
        val existing = channelDao.getByPlaylistUrlAndRelationId(StremioIds.PLAYLIST_URL, playRelation)
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = playCategory,
                title = playTitle,
                cover = playPoster ?: _state.value.details?.poster,
                playlistUrl = StremioIds.PLAYLIST_URL,
                id = existing?.id ?: 0,
                relationId = playRelation,
            ),
        ).toInt()
        if (id != 0) return id
        return channelDao.getByPlaylistUrlAndRelationId(StremioIds.PLAYLIST_URL, playRelation)?.id ?: 0
    }

    private fun qualityRank(quality: String?): Int = when {
        quality == null -> 0
        "2160" in quality -> 5
        "1080" in quality -> 4
        "720" in quality -> 3
        "480" in quality -> 2
        else -> 1
    }
}

private data class ShelfBundle(
    val watchlist: List<CatalogItem> = emptyList(),
    val playback: List<CatalogItem> = emptyList(),
    val upNext: List<CatalogItem> = emptyList(),
    val calendar: List<CatalogItem> = emptyList(),
    val user: String? = null,
)
