package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.m3u.business.setting.ExtensionSettingsOperationQueue
import com.m3u.business.setting.ProviderDiscoveryState
import com.m3u.business.setting.ProviderSubscriptionForm
import com.m3u.business.setting.ProviderSubscriptionFormBuildResult
import com.m3u.business.setting.supports
import com.m3u.core.foundation.architecture.preferences.PreferencesKeys
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.core.foundation.architecture.preferences.set
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.ChannelCategoryCount
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.extension.ExtensionSettingEditToken
import com.m3u.data.repository.extension.ExtensionSettingUpdateResult
import com.m3u.data.repository.extension.ExtensionSettingsConfiguration
import com.m3u.data.repository.extension.ExtensionSettingsRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.repository.plugin.ExtensionPluginRepository
import com.m3u.data.repository.plugin.InstalledPlugin
import com.m3u.data.repository.plugin.PluginAuthorizationToken
import com.m3u.data.repository.plugin.PluginDataClearResult
import com.m3u.data.repository.plugin.PluginEnableResult
import com.m3u.data.repository.provider.DiscoveredSubscriptionProvider
import com.m3u.data.repository.provider.ProviderAccountSummary
import com.m3u.data.repository.provider.ProviderDiscoveryException
import com.m3u.data.repository.provider.SubscriptionProviderRepository
import com.m3u.data.repository.tv.TvRepository
import com.m3u.data.service.DPadReactionService
import com.m3u.data.service.MediaCommand
import com.m3u.tv.stremio.StremioIds
import com.m3u.data.service.PlayerManager
import com.m3u.extension.api.ExtensionId
import com.m3u.extension.api.subscription.SubscriptionProviderDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class TvUiState(
    val playlists: List<Playlist> = emptyList(),
    val counts: Map<Playlist, Int> = emptyMap(),
    val selectedPlaylist: Playlist? = null,
    /** The selected playlist's entries: all of them, or just [selectedCategory]. */
    val channels: List<Channel> = emptyList(),
    /** With duplicates merged: every copy of a shown channel (HD, FHD, 4K…), by its id. */
    val variants: Map<Int, List<Channel>> = emptyMap(),
    /** Categories of the selected playlist, in the provider's order. */
    val categories: List<ChannelCategoryCount> = emptyList(),
    /** Null means every category ("All"), which only big-enough playlists skip. */
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val searchResults: List<Channel> = emptyList(),
    val searching: Boolean = false,
    val favorites: List<Channel> = emptyList(),
    val recent: Channel? = null,
    /** The last few channels, films and episodes played, newest first. */
    val recentlyPlayed: List<Channel> = emptyList(),
    /** How many of the selected playlist's categories are hidden. */
    val hiddenCategoryCount: Int = 0,
    val loadingChannels: Boolean = false,
    val externalExtensionsEnabled: Boolean = false,
    val extensionPlugins: List<InstalledPlugin> = emptyList(),
    val extensionSettings: ExtensionSettingsConfiguration? = null,
    val extensionPluginOperationFailed: Boolean = false,
    val providerDiscoveryState: ProviderDiscoveryState = ProviderDiscoveryState.Loading,
    val providerAccounts: List<ProviderAccountSummary> = emptyList(),
    val providerSubscriptionForm: ProviderSubscriptionForm? = null,
    val providerSubscriptionDescriptor: SubscriptionProviderDescriptor? = null,
    val providerSubscriptionUnavailable: Boolean = false,
    val providerSubscriptionTitle: String = "",
    val providerSubscriptionInProgress: Boolean = false,
    val providerSubscriptionFeedback: TvProviderSubscriptionFeedback? = null,
) {
    val channelCount: Int get() = counts.values.sum()

    /** "All" is offered unless the playlist is too big to hold in memory at once. */
    val allCategoriesAllowed: Boolean
        get() = categories.sumOf { it.count } <= ALL_CATEGORIES_LIMIT
    val heroChannel: Channel? get() = recent ?: channels.firstOrNull()
}

/** The three things a source can hold, each with its own tab: Live TV, Films and Series. */
enum class CatalogKind { Live, Films, Series }

/** Xtream accounts come in as three playlists (live, films, series); M3U playlists are live. */
val Playlist.catalogKind: CatalogKind
    get() = when {
        isSeries -> CatalogKind.Series
        isVod -> CatalogKind.Films
        else -> CatalogKind.Live
    }

sealed interface TvProviderSubscriptionFeedback {
    data object InvalidSettings : TvProviderSubscriptionFeedback
    data object Failed : TvProviderSubscriptionFeedback
    data class Added(val channelCount: Int) : TvProviderSubscriptionFeedback
}

@HiltViewModel
class TvHomeViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val playerManager: PlayerManager,
    private val extensionPluginRepository: ExtensionPluginRepository,
    private val extensionSettingsRepository: ExtensionSettingsRepository,
    private val subscriptionProviderRepository: SubscriptionProviderRepository,
    private val settings: Settings,
    private val dialStore: DialSettingsStore,
    private val profiles: ProfileStore,
    private val programmes: ProgrammeRepository,
    private val edits: ChannelEditStore,
    private val logos: ChannelLogos,
    tvRepository: TvRepository,
    dPadReactionService: DPadReactionService
) : ViewModel() {
    private val _state = MutableStateFlow(TvUiState())

    /** The UI state, with logos from the tv-logos collection for live channels that have none. */
    val state: StateFlow<TvUiState> = combine(_state, logos.index) { current, index ->
        if (index.isEmpty()) current else withLogos(current)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUiState())

    private fun withLogos(current: TvUiState): TvUiState {
        val live = current.playlists.filter { !it.isVod && !it.isSeries }.map { it.url }.toSet()
        fun Channel.wantsLogo() = cover.isNullOrBlank() && playlistUrl in live
        fun fill(channels: List<Channel>): List<Channel> {
            if (channels.none { it.wantsLogo() }) return channels
            return channels.map { channel ->
                if (!channel.wantsLogo()) channel else logos.logoFor(channel.title)?.let { channel.copy(cover = it) } ?: channel
            }
        }
        return current.copy(
            channels = fill(current.channels),
            favorites = fill(current.favorites),
            recentlyPlayed = fill(current.recentlyPlayed),
            searchResults = fill(current.searchResults),
        )
    }

    // Where each tab (Live TV, Films, Series) was left: its source, and each source's category.
    // Written from the main thread and from the loader's IO thread, hence synchronized (and a
    // HashMap underneath, which can hold a null "All").
    private val lastPlaylistByKind: MutableMap<CatalogKind, String> =
        Collections.synchronizedMap(HashMap())
    private val lastCategoryByUrl: MutableMap<String, String?> =
        Collections.synchronizedMap(HashMap())
    private val _extensionDiagnostics = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val extensionDiagnostics = _extensionDiagnostics.asSharedFlow()

    val player: StateFlow<Player?> = playerManager.player

    private val _guideLine = MutableStateFlow<String?>(null)
    val guideLine: StateFlow<String?> = _guideLine.asStateFlow()

    fun refreshGuide(channel: Channel?) {
        viewModelScope.launch {
            if (channel == null) {
                _guideLine.value = null
                return@launch
            }
            val now = runCatching { programmes.getProgrammeCurrently(channel.id) }.getOrNull()
            val relation = channel.relationId
            val next = if (relation.isNullOrBlank()) {
                null
            } else {
                val from = System.currentTimeMillis()
                runCatching {
                    programmes.getProgrammesInRange(channel.playlistUrl, relation, from, from + 8 * 3_600_000L)
                }.getOrDefault(emptyList())
                    .filter { programme -> now == null || programme.start >= now.end }
                    .minByOrNull { it.start }
            }
            _guideLine.value = listOfNotNull(
                now?.title?.takeIf { it.isNotBlank() }?.let { "Now  $it" },
                next?.title?.takeIf { it.isNotBlank() }?.let { "Next  $it" },
            ).joinToString("    ·    ").ifBlank { null }
        }
    }
    private val _nowTitles = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** What's on now on each of a list of channels (channel id to programme title). */
    val nowTitles: StateFlow<Map<Int, String>> = _nowTitles.asStateFlow()

    /** Looks up what's on now for [channels] (the player's channel list), a playlist at a time. */
    fun loadNowTitles(channels: List<Channel>) {
        viewModelScope.launch {
            val titles = mutableMapOf<Int, String>()
            channels.groupBy { it.playlistUrl }.forEach { (playlistUrl, members) ->
                val now = runCatching { programmes.getProgrammesCurrently(playlistUrl) }.getOrDefault(emptyMap())
                members.forEach { channel ->
                    channel.relationId?.let(now::get)?.title?.takeIf { it.isNotBlank() }?.let { titles[channel.id] = it }
                }
            }
            _nowTitles.value = titles
        }
    }

    /** What's playing, under its custom name when the editor gave it one. */
    val currentChannel: StateFlow<Channel?> = combine(playerManager.channel, edits.names) { channel, names ->
        channel?.let { current -> names[current.url]?.let { current.copy(title = it) } ?: current }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, playerManager.channel.value)
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val playbackState: StateFlow<Int> = playerManager.playbackState
    val reconnecting: StateFlow<Boolean> = playerManager.reconnecting
    /** Name of the error a stream stopped with (for error reports), or null. */
    val playbackError: StateFlow<String?> = playerManager.playbackException
        .map { it?.errorCodeName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
    val playbackFailed: StateFlow<Boolean> = playerManager.playbackException
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)
    val remoteControlCode: StateFlow<Int?> = tvRepository.broadcastCodeOnTv
    val remoteDirections = dPadReactionService.incoming
    private var loadChannelsJob: Job? = null
    private var providerDiscoveryJob: Job? = null
    private var providerDiscoveryGeneration: Long = 0L
    private var providerReauthenticationJob: Job? = null
    private var providerSubscriptionJob: Job? = null
    private var providerLocaleTag: String? = null
    private var extensionSettingsLoadJob: Job? = null
    private var extensionSettingsRequestedId: ExtensionId? = null
    @Volatile
    private var sortLocale: Locale = Locale.getDefault()

    fun updateLocale(localeTag: String) {
        val requestedLocaleTag = localeTag.trim().takeIf(String::isNotEmpty)
        val providerLocaleChanged = providerLocaleTag != requestedLocaleTag
        providerLocaleTag = requestedLocaleTag
        val locale = requestedLocaleTag
            ?.let(Locale::forLanguageTag)
            ?: Locale.getDefault()
        if (locale != sortLocale) {
            sortLocale = locale
            _state.update { state ->
                state.copy(
                    playlists = state.playlists.sortedWith(
                        localeAwareComparator(
                            primarySelector = Playlist::title,
                            locale = locale,
                        )
                    ),
                )
            }
        }
        if (providerLocaleChanged) {
            startSubscriptionProviderDiscovery(requestedLocaleTag)
            extensionSettingsRequestedId?.value?.let { extensionId ->
                openExtensionSettings(extensionId, requestedLocaleTag)
            }
        }
    }
    private var extensionSettingsGeneration = 0L
    private var extensionSettingsUpdateGeneration = 0L
    private val extensionSettingsOperationQueue = ExtensionSettingsOperationQueue(
        scope = viewModelScope,
        onFailure = {
            _state.update {
                it.copy(
                    extensionPluginOperationFailed = true,
                )
            }
        },
    )

    // The selected playlist's categories as the provider lists them, before the saved layout.
    private var rawCategories: List<ChannelCategoryCount> = emptyList()
    private var rawCategoriesUrl: String? = null

    init {
        observePlaylists()
        observeFavorites()
        observeRecent()
        observeCategoryLayouts()
        observeExternalExtensions()
        observeProviderAccounts()
        refreshSubscriptionProviders()
        observeProfile()
    }

    /** A kids profile picked (or left): the lists are read again with the right filter. */
    private fun observeProfile() {
        viewModelScope.launch {
            profiles.active.map { it?.kids == true }.distinctUntilChanged().drop(1).collect {
                _state.value.selectedPlaylist?.url?.let { url -> loadChannels(url) }
                _state.update { it.copy(searchResults = emptyList()) }
                _state.value.searchQuery.takeIf { it.isNotBlank() }?.let(::search)
            }
        }
        // Merging duplicates switched on or off: the live list is read again.
        viewModelScope.launch {
            dialStore.preferences.map { it.mergeDuplicates to it.preferredQuality }.distinctUntilChanged().drop(1).collect {
                _state.value.selectedPlaylist?.url?.let { url -> loadChannels(url) }
            }
        }
        // A channel renamed or moved in the editor: every list is read again, so a name put
        // back to the provider's shows that way too.
        viewModelScope.launch {
            edits.version.drop(1).collect {
                _state.value.selectedPlaylist?.url?.let { url -> loadChannels(url) }
                refreshRecentlyPlayed()
                _state.value.searchQuery.takeIf { it.isNotBlank() }?.let(::search)
                val favorites = runCatching { channelRepository.observeAllFavorite().first() }.getOrNull() ?: return@collect
                val shown = edits.applyNames(if (profiles.kidsActive) favorites.filterNot { isAdultCategory(it.category) } else favorites)
                _state.update { it.copy(favorites = shown) }
            }
        }
    }

    fun selectPlaylist(playlist: Playlist) {
        if (_state.value.selectedPlaylist?.url == playlist.url) return
        lastPlaylistByKind[playlist.catalogKind] = playlist.url
        // Back where this source was left; the old list goes at once, so a tab never shows
        // another tab's channels while its own load.
        _state.update {
            it.copy(
                selectedPlaylist = playlist,
                selectedCategory = lastCategoryByUrl[playlist.url],
                categories = emptyList(),
                channels = emptyList(),
                loadingChannels = true,
            )
        }
        loadChannels(playlist.url, keepCategory = true)
    }

    /**
     * Live TV, Films or Series opened: shows the source of that kind used last (or the first),
     * unless one of that kind is already showing.
     */
    fun openCatalog(kind: CatalogKind) {
        val state = _state.value
        if (state.selectedPlaylist?.catalogKind == kind) return
        val sources = state.playlists.filter { it.catalogKind == kind }
        val target = sources.firstOrNull { it.url == lastPlaylistByKind[kind] }
            ?: sources.firstOrNull()
            ?: return
        selectPlaylist(target)
    }

    /** Shows one category of the selected playlist, or all of it for null (when allowed). */
    fun selectCategory(category: String?) {
        val state = _state.value
        val url = state.selectedPlaylist?.url ?: return
        if (category == state.selectedCategory) return
        if (category == null && !state.allCategoriesAllowed) return
        lastCategoryByUrl[url] = category
        _state.update { it.copy(selectedCategory = category, loadingChannels = true) }
        loadChannels(url, keepCategory = true)
    }

    private var searchJob: Job? = null

    /** Searches titles across every playlist, a moment after typing stops. */
    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < SEARCH_MIN_LENGTH) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(searching = true) }
            delay(SEARCH_DEBOUNCE_MS)
            val results = edits.applyNames(channelRepository.searchUnhidden(trimmed, SEARCH_LIMIT))
                .let { all -> if (profiles.kidsActive) all.filterNot { isAdultCategory(it.category) || isAdultCategory(it.title) } else all }
            _state.update { state ->
                if (state.searchQuery.trim() == trimmed) {
                    state.copy(searchResults = results, searching = false)
                } else {
                    state
                }
            }
        }
    }

    fun refreshSelectedPlaylist() {
        val playlist = _state.value.selectedPlaylist ?: return
        viewModelScope.launch {
            playlistRepository.refresh(playlist.url)
            loadChannels(playlist.url)
        }
    }

    fun play(channel: Channel) {
        viewModelScope.launch {
            playerManager.play(MediaCommand.Common(channel.id))
            channelRepository.reportPlayed(channel.id)
        }
    }

    /** Channel title that names both teams, or null. The caller opens the player. */
    fun watchFixture(home: String, away: String, onFound: (Channel?) -> Unit) {
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) {
                FixtureChannelFinder.find(channelRepository, home, away)
            }
            onFound(channel)
        }
    }

    fun playRecent() {
        _state.value.recent?.let(::play)
    }

    /** Hides a channel from lists (Settings > Sources lists hidden ones to bring back). */
    fun hideChannel(channel: Channel) {
        viewModelScope.launch {
            channelRepository.hide(channel.id, true)
            _state.value.selectedPlaylist?.url?.let { loadChannels(it) }
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            channelRepository.favouriteOrUnfavourite(channel.id)
        }
    }

    fun pauseOrContinue(continuePlayback: Boolean) {
        // A stream that stopped (dropped, or the Fire TV slept) starts again rather than
        // "continuing" nothing; live channels rejoin at the live edge.
        if (continuePlayback && playerManager.playbackState.value == Player.STATE_IDLE) {
            playerManager.wake()
        } else {
            playerManager.pauseOrContinue(continuePlayback)
        }
    }

    /** The app left the screen: free the stream and decoder, keeping what was playing. */
    fun sleepPlayer() {
        playerManager.sleep()
    }

    /** The app is back on screen: pick the stream up again if it stopped. */
    fun wakePlayer() {
        playerManager.wake()
    }

    fun releasePlayer() {
        playerManager.release()
    }

    fun setExternalExtensionsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings[PreferencesKeys.EXTERNAL_EXTENSIONS] = enabled }
    }

    fun enableExtensionPlugin(
        packageName: String,
        serviceName: String,
        authorizationToken: PluginAuthorizationToken,
    ) {
        viewModelScope.launch {
            updateExtensionPluginResult(
                extensionPluginRepository.enable(
                    packageName,
                    serviceName,
                    authorizationToken,
                )
            )
            refreshExtensionPlugins()
        }
    }

    fun reauthorizeExtensionPlugin(
        packageName: String,
        serviceName: String,
        authorizationToken: PluginAuthorizationToken,
    ) {
        viewModelScope.launch {
            updateExtensionPluginResult(
                extensionPluginRepository.reauthorize(
                    packageName,
                    serviceName,
                    authorizationToken,
                )
            )
            refreshExtensionPlugins()
        }
    }

    fun disableExtensionPlugin(extensionId: String) {
        closeExtensionSettingsIfActive(extensionId)
        viewModelScope.launch {
            extensionPluginRepository.disable(extensionId)
            refreshExtensionPlugins()
        }
    }

    fun revokeExtensionPlugin(
        packageName: String,
        serviceName: String,
        extensionId: String?,
    ) {
        if (extensionId == null) {
            reportExtensionOperationFailure()
            return
        }
        closeExtensionSettingsIfActive(extensionId)
        extensionSettingsOperationQueue.launchDestructive(extensionId) {
            extensionPluginRepository.revoke(packageName, serviceName)
            _state.update {
                it.copy(
                    extensionPluginOperationFailed = false,
                )
            }
            refreshExtensionPlugins()
        }
    }

    fun openExtensionSettings(extensionId: String, localeTag: String?) {
        val requestedExtensionId = ExtensionId(extensionId)
        val generation = ++extensionSettingsGeneration
        extensionSettingsLoadJob?.cancel()
        extensionSettingsRequestedId = requestedExtensionId
        _state.update { it.copy(extensionSettings = null) }
        extensionSettingsLoadJob = extensionSettingsOperationQueue.launchOperation(extensionId) {
            val configuration = withContext(Dispatchers.IO) {
                extensionSettingsRepository.configuration(
                    requestedExtensionId,
                    localeTag,
                    TV_SETTINGS_SURFACE,
                )
            }
            if (generation == extensionSettingsGeneration) {
                _state.update {
                    it.copy(
                        extensionSettings = configuration,
                        extensionPluginOperationFailed = false,
                    )
                }
            }
        }
    }

    fun closeExtensionSettings() {
        extensionSettingsGeneration++
        extensionSettingsLoadJob?.cancel()
        extensionSettingsLoadJob = null
        extensionSettingsRequestedId = null
        _state.update { it.copy(extensionSettings = null) }
    }

    private fun closeExtensionSettingsIfActive(extensionId: String) {
        if (
            _state.value.extensionSettings?.extensionId?.value == extensionId ||
            extensionSettingsRequestedId?.value == extensionId
        ) {
            closeExtensionSettings()
        }
    }

    fun clearExtensionData(
        packageName: String,
        serviceName: String,
        extensionId: String?,
    ) {
        if (extensionId == null) {
            reportExtensionOperationFailure()
            return
        }
        closeExtensionSettingsIfActive(extensionId)
        extensionSettingsOperationQueue.launchDestructive(extensionId) {
            when (
                val result = withContext(Dispatchers.IO) {
                    extensionPluginRepository.clearData(packageName, serviceName)
                }
            ) {
                is PluginDataClearResult.Cleared -> {
                    _state.update {
                        it.copy(
                            extensionPluginOperationFailed = false,
                        )
                    }
                }
                is PluginDataClearResult.Rejected -> {
                    _state.update {
                        it.copy(
                            extensionPluginOperationFailed = true,
                        )
                    }
                }
            }
        }
    }

    fun exportExtensionDiagnostics(extensionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            extensionPluginRepository.diagnostics(extensionId)?.let { payload ->
                _extensionDiagnostics.emit(payload)
            }
        }
    }

    fun updateExtensionSetting(
        sectionId: String,
        fieldKey: String,
        editToken: ExtensionSettingEditToken,
        rawValue: String?,
        localeTag: String?,
    ) {
        val extensionId = _state.value.extensionSettings?.extensionId ?: return
        val generation = extensionSettingsGeneration
        val updateGeneration = ++extensionSettingsUpdateGeneration
        extensionSettingsOperationQueue.launchUpdate(extensionId.value) update@{
            val result = withContext(Dispatchers.IO) {
                val update = extensionSettingsRepository.update(
                    extensionId,
                    sectionId,
                    fieldKey,
                    editToken,
                    rawValue,
                )
                TvExtensionSettingsRefreshResult(
                    configuration = extensionSettingsRepository.configuration(
                        extensionId,
                        localeTag,
                        TV_SETTINGS_SURFACE,
                    ),
                    rejected = update is ExtensionSettingUpdateResult.Rejected,
                )
            }
            if (
                generation != extensionSettingsGeneration ||
                updateGeneration != extensionSettingsUpdateGeneration ||
                _state.value.extensionSettings?.extensionId != extensionId
            ) {
                return@update
            }
            _state.update {
                it.copy(
                    extensionSettings = result.configuration,
                    extensionPluginOperationFailed = result.rejected,
                )
            }
        }
    }

    fun refreshSubscriptionProviders() {
        startSubscriptionProviderDiscovery(providerLocaleTag)
    }

    private fun startSubscriptionProviderDiscovery(localeTag: String?): Job {
        val previousJob = providerDiscoveryJob
        val generation = ++providerDiscoveryGeneration
        return viewModelScope.launch {
            previousJob?.cancelAndJoin()
            try {
                loadSubscriptionProviders(localeTag)
            } finally {
                if (providerDiscoveryGeneration == generation) {
                    providerDiscoveryJob = null
                }
            }
        }.also { job -> providerDiscoveryJob = job }
    }

    private suspend fun awaitLatestSubscriptionProviderDiscovery(
        forceRefresh: Boolean,
    ) {
        var awaitedJob = if (forceRefresh) {
            startSubscriptionProviderDiscovery(providerLocaleTag)
        } else {
            providerDiscoveryJob ?: startSubscriptionProviderDiscovery(providerLocaleTag)
        }
        while (true) {
            awaitedJob.join()
            val latestJob = providerDiscoveryJob
            if (latestJob == null || latestJob === awaitedJob) return
            awaitedJob = latestJob
        }
    }

    private suspend fun loadSubscriptionProviders(
        localeTag: String?,
    ): List<DiscoveredSubscriptionProvider>? {
        val previousDiscoveryState = _state.value.providerDiscoveryState
        val previousProviderUnavailable = _state.value.providerSubscriptionUnavailable
        _state.update { current ->
            current.copy(
                providerDiscoveryState = ProviderDiscoveryState.Loading,
                providerSubscriptionUnavailable = false,
            )
        }
        return try {
            val providers = withContext(Dispatchers.IO) {
                subscriptionProviderRepository.discoverProviders(localeTag)
            }
            val discoveryState = if (providers.isEmpty()) {
                ProviderDiscoveryState.Empty
            } else {
                ProviderDiscoveryState.Ready(providers)
            }
            _state.update { current ->
                val form = current.providerSubscriptionForm
                val matchingProvider = form?.let { activeForm ->
                    providers.firstOrNull { provider ->
                        provider.descriptor.providerId == activeForm.providerId &&
                            provider.descriptor.variants.any { variant ->
                                variant.kind == activeForm.providerKind
                            }
                    }
                }
                current.copy(
                    providerDiscoveryState = discoveryState,
                    providerSubscriptionForm = matchingProvider
                        ?.descriptor
                        ?.let { descriptor -> requireNotNull(form).updateDescriptor(descriptor) }
                        ?: form,
                    providerSubscriptionDescriptor = when {
                        form == null -> null
                        matchingProvider != null -> matchingProvider.descriptor
                        else -> current.providerSubscriptionDescriptor
                    },
                    providerSubscriptionUnavailable = form != null && !discoveryState.supports(form),
                )
            }
            providers
        } catch (cancelled: CancellationException) {
            _state.update { current ->
                if (current.providerDiscoveryState is ProviderDiscoveryState.Loading) {
                    current.copy(
                        providerDiscoveryState = previousDiscoveryState,
                        providerSubscriptionUnavailable = previousProviderUnavailable,
                    )
                } else {
                    current
                }
            }
            throw cancelled
        } catch (error: Exception) {
            _state.update { current ->
                current.copy(
                    providerDiscoveryState = ProviderDiscoveryState.Failed(
                        failureCount = (error as? ProviderDiscoveryException)?.failureCount,
                    ),
                    providerSubscriptionUnavailable =
                        current.providerSubscriptionForm != null,
                )
            }
            null
        }
    }

    fun openProviderSubscription(providerId: String, providerKind: String) {
        val descriptor = currentProviders().firstOrNull { provider ->
            provider.descriptor.providerId.value == providerId
        }?.descriptor ?: return
        val kind = descriptor.variants.firstOrNull { variant ->
            variant.kind.value == providerKind && variant.userSelectable
        }?.kind ?: return
        _state.update { state ->
            state.copy(
                providerSubscriptionForm = ProviderSubscriptionForm.create(descriptor, kind),
                providerSubscriptionDescriptor = descriptor,
                providerSubscriptionUnavailable = false,
                providerSubscriptionTitle = descriptor.displayName,
                providerSubscriptionFeedback = null,
            )
        }
    }

    fun reauthenticateProviderAccount(playlistUrl: String) {
        val account = _state.value.providerAccounts.firstOrNull { summary ->
            summary.playlistUrl == playlistUrl && summary.requiresReauthentication
        } ?: return
        providerReauthenticationJob?.cancel()
        providerReauthenticationJob = viewModelScope.launch {
            awaitLatestSubscriptionProviderDiscovery(forceRefresh = false)
            var descriptor = currentProviders().providerFor(account)?.descriptor
            if (descriptor == null) {
                awaitLatestSubscriptionProviderDiscovery(forceRefresh = true)
                descriptor = currentProviders().providerFor(account)?.descriptor
            }
            if (descriptor == null) {
                _state.update {
                    it.copy(providerSubscriptionFeedback = TvProviderSubscriptionFeedback.Failed)
                }
                return@launch
            }
            _state.update { current ->
                current.copy(
                    providerSubscriptionForm = ProviderSubscriptionForm.createForReauthentication(
                        descriptor = descriptor,
                        account = account,
                    ),
                    providerSubscriptionDescriptor = descriptor,
                    providerSubscriptionUnavailable = false,
                    providerSubscriptionTitle = account.playlistTitle,
                    providerSubscriptionFeedback = null,
                )
            }
        }
    }

    fun closeProviderSubscription() {
        if (_state.value.providerSubscriptionInProgress) return
        _state.update { current ->
            current.copy(
                providerSubscriptionForm = null,
                providerSubscriptionDescriptor = null,
                providerSubscriptionUnavailable = false,
                providerSubscriptionTitle = "",
                providerSubscriptionFeedback = null,
            )
        }
    }

    fun updateProviderSubscriptionTitle(title: String) {
        _state.update {
            it.copy(providerSubscriptionTitle = title, providerSubscriptionFeedback = null)
        }
    }

    fun selectProviderKind(kindValue: String) {
        val currentState = _state.value
        if (currentState.providerSubscriptionUnavailable) return
        val form = currentState.providerSubscriptionForm ?: return
        val descriptor = currentState.providerSubscriptionDescriptor
            ?.takeIf { it.providerId == form.providerId }
            ?: return
        val kind = descriptor.variants.firstOrNull { variant ->
            variant.kind.value == kindValue
        }?.kind ?: return
        if (kind == form.providerKind) return
        _state.update { current ->
            current.copy(
                providerSubscriptionForm = ProviderSubscriptionForm.create(descriptor, kind),
                providerSubscriptionDescriptor = descriptor,
                providerSubscriptionFeedback = null,
            )
        }
    }

    fun updateProviderSetting(fieldKey: String, value: String?) {
        _state.update { current ->
            current.copy(
                providerSubscriptionForm = current.providerSubscriptionForm?.update(fieldKey, value),
                providerSubscriptionFeedback = null,
            )
        }
    }

    fun submitProviderSubscription() {
        if (providerSubscriptionJob?.isActive == true) return
        val current = _state.value
        val form = current.providerSubscriptionForm ?: return
        if (
            current.providerSubscriptionUnavailable ||
            !current.providerDiscoveryState.supports(form)
        ) {
            return
        }
        if (current.providerSubscriptionTitle.isBlank()) {
            _state.update {
                it.copy(providerSubscriptionFeedback = TvProviderSubscriptionFeedback.InvalidSettings)
            }
            return
        }
        val buildResult = runCatching {
            form.buildRequest(
                title = current.providerSubscriptionTitle,
                stageCredential = subscriptionProviderRepository::stageCredential,
            )
        }.getOrElse {
            _state.update {
                it.copy(providerSubscriptionFeedback = TvProviderSubscriptionFeedback.Failed)
            }
            return
        }
        when (val result = buildResult) {
            is ProviderSubscriptionFormBuildResult.Invalid -> {
                _state.update {
                    it.copy(
                        providerSubscriptionForm = result.form,
                        providerSubscriptionFeedback = TvProviderSubscriptionFeedback.InvalidSettings,
                    )
                }
            }

            is ProviderSubscriptionFormBuildResult.Ready -> {
                providerSubscriptionJob = viewModelScope.launch {
                    _state.update {
                        it.copy(
                            providerSubscriptionInProgress = true,
                            providerSubscriptionFeedback = null,
                        )
                    }
                    try {
                        val subscription = withContext(Dispatchers.IO) {
                            subscriptionProviderRepository.subscribe(result.request)
                        }
                        _state.update {
                            it.copy(
                                providerSubscriptionForm = null,
                                providerSubscriptionDescriptor = null,
                                providerSubscriptionUnavailable = false,
                                providerSubscriptionTitle = "",
                                providerSubscriptionInProgress = false,
                                providerSubscriptionFeedback = TvProviderSubscriptionFeedback.Added(
                                    subscription.channelCount
                                ),
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        _state.update { it.copy(providerSubscriptionInProgress = false) }
                        throw cancelled
                    } catch (_: Exception) {
                        _state.update {
                            it.copy(
                                providerSubscriptionInProgress = false,
                                providerSubscriptionFeedback = TvProviderSubscriptionFeedback.Failed,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeExternalExtensions() {
        viewModelScope.launch {
            settings.data
                .map { preferences -> preferences[PreferencesKeys.EXTERNAL_EXTENSIONS] ?: false }
                .collect { enabled ->
                    _state.update { it.copy(externalExtensionsEnabled = enabled) }
                    refreshExtensionPlugins()
                }
        }
    }

    private fun refreshExtensionPlugins() {
        viewModelScope.launch {
            val plugins = withContext(Dispatchers.IO) {
                extensionPluginRepository.installedPlugins()
            }
            _state.update { it.copy(extensionPlugins = plugins) }
            refreshSubscriptionProviders()
        }
    }

    private fun observeProviderAccounts() {
        viewModelScope.launch {
            subscriptionProviderRepository.observeAccountSummaries().collect { accounts ->
                _state.update { it.copy(providerAccounts = accounts) }
            }
        }
    }

    private fun currentProviders(): List<DiscoveredSubscriptionProvider> =
        (_state.value.providerDiscoveryState as? ProviderDiscoveryState.Ready)
            ?.providers
            .orEmpty()

    private fun updateExtensionPluginResult(result: PluginEnableResult) {
        _state.update { state ->
            state.copy(
                extensionPluginOperationFailed = result is PluginEnableResult.Rejected,
            )
        }
    }

    private fun reportExtensionOperationFailure() {
        _state.update {
            it.copy(
                extensionPluginOperationFailed = true,
            )
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            playlistRepository
                .observeAllCounts()
                .flowOn(Dispatchers.Default)
                .collect { counts ->
                    val state = _state.value
                    val playlists = counts.keys
                        .filterNot { it.source == DataSource.EPG }
                        .filterNot { it.url in STAND_IN_PLAYLISTS }
                        .sortedWith(
                            localeAwareComparator(
                                primarySelector = Playlist::title,
                                locale = sortLocale,
                            )
                        )
                    val previous = state.selectedPlaylist
                    val selected = previous
                        ?.let { active -> playlists.firstOrNull { it.url == active.url } }
                        ?: playlists.firstOrNull()
                    val previousCount = previous?.let { playlist -> state.counts.countFor(playlist.url) }
                    val selectedCount = selected?.let { playlist -> counts.countFor(playlist.url) }

                    _state.update {
                        it.copy(
                            playlists = playlists,
                            counts = counts,
                            selectedPlaylist = selected
                        )
                    }

                    if (selected != null && (selected.url != previous?.url || selectedCount != previousCount)) {
                        loadChannels(selected.url, keepCategory = selected.url == previous?.url)
                    }
                }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            channelRepository.observeAllFavorite().distinctUntilChanged().collect { favorites ->
                val shown = edits.applyNames(if (profiles.kidsActive) favorites.filterNot { isAdultCategory(it.category) } else favorites)
                _state.update { it.copy(favorites = shown) }
            }
        }
    }

    private fun observeRecent() {
        viewModelScope.launch {
            // Room re-emits on every write to the channel table (thousands during an import);
            // only a change to what was last played needs the list re-read.
            channelRepository.observePlayedRecently().distinctUntilChanged().collect { recent ->
                _state.update { it.copy(recent = recent) }
                refreshRecentlyPlayed()
            }
        }
    }

    /** Re-reads the "last watched" list (Home), e.g. after the player closes. */
    fun refreshRecentlyPlayed() {
        viewModelScope.launch(Dispatchers.IO) {
            val recent = runCatching { channelRepository.getPlayedRecently(RECENTLY_PLAYED_LIMIT) }
                .getOrDefault(emptyList())
            _state.update { it.copy(recentlyPlayed = edits.applyNames(recent)) }
        }
    }

    private fun observeCategoryLayouts() {
        viewModelScope.launch {
            dialStore.categoryLayouts.collect { layouts ->
                val url = rawCategoriesUrl ?: return@collect
                val layout = layouts[url] ?: CategoryLayout()
                _state.update { state ->
                    if (state.selectedPlaylist?.url != url) return@update state
                    state.copy(
                        categories = layout.arrange(rawCategories) { it.name },
                        hiddenCategoryCount = rawCategories.count { it.name in layout.hidden },
                    )
                }
            }
        }
    }

    fun moveCategory(name: String, delta: Int) {
        val url = _state.value.selectedPlaylist?.url ?: return
        dialStore.moveCategory(url, _state.value.categories.map { it.name }, name, delta)
    }

    fun moveCategoryToFront(name: String) {
        val url = _state.value.selectedPlaylist?.url ?: return
        dialStore.moveCategoryToFront(url, _state.value.categories.map { it.name }, name)
    }

    fun hideCategory(name: String) {
        val state = _state.value
        val url = state.selectedPlaylist?.url ?: return
        // Keep at least one category showing.
        if (state.categories.size <= 1) return
        dialStore.hideCategory(url, name)
        if (state.selectedCategory == name) {
            state.categories.firstOrNull { it.name != name }?.let { selectCategory(it.name) }
        }
    }

    fun showAllCategories() {
        val url = _state.value.selectedPlaylist?.url ?: return
        dialStore.showAllCategories(url)
    }

    fun resetCategories() {
        val url = _state.value.selectedPlaylist?.url ?: return
        dialStore.resetCategories(url)
    }

    /**
     * Loads the selected playlist one category at a time. A playlist small enough is shown whole
     * ("All"); a huge one (50k channels, 130k films) opens on its first category instead, so the
     * Fire TV never holds the whole catalogue in memory.
     */
    private fun loadChannels(url: String, keepCategory: Boolean = true) {
        loadChannelsJob?.cancel()
        loadChannelsJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loadingChannels = true) }
            // A kids profile never sees adult categories, whatever the playlist calls them.
            val providerCategories = channelRepository.getCategoryCounts(url)
                .let { all -> if (profiles.kidsActive) all.filterNot { isAdultCategory(it.name) } else all }
            rawCategories = providerCategories
            rawCategoriesUrl = url
            val layout = dialStore.categoryLayouts.value[url] ?: CategoryLayout()
            val categories = layout.arrange(providerCategories) { it.name }
            val hiddenCount = providerCategories.count { it.name in layout.hidden }
            val total = categories.sumOf { it.count }
            val wanted = _state.value.selectedCategory.takeIf { keepCategory }
            val category = when {
                wanted != null && categories.any { it.name == wanted } -> wanted
                total <= ALL_CATEGORIES_LIMIT -> null
                else -> categories.firstOrNull()?.name
            }
            lastCategoryByUrl[url] = category
            val playlist = _state.value.selectedPlaylist?.takeIf { it.url == url }
            // Films and series read best A to Z; live channels keep the provider's numbering.
            val byTitle = playlist != null && (playlist.isVod || playlist.isSeries)
            val arranged = channelRepository.getUnhidden(url, category, byTitle)
                .let { all -> if (profiles.kidsActive) all.filterNot { isAdultCategory(it.category) } else all }
                // Custom orders apply within a category; the "All" list keeps the provider's order.
                .let { all -> if (byTitle || category == null) edits.applyNames(all) else edits.arrange(url, all) }
            // Live TV with duplicates merged: one card per name, the preferred quality in front.
            val dialPrefs = dialStore.preferences.value
            val (channels, variants) = if (!byTitle && dialPrefs.mergeDuplicates) {
                ChannelVariants.merge(arranged, dialPrefs.preferredQuality)
            } else arranged to emptyMap()
            _state.update { state ->
                if (state.selectedPlaylist?.url == url) {
                    state.copy(
                        categories = categories,
                        hiddenCategoryCount = hiddenCount,
                        selectedCategory = category,
                        channels = channels,
                        variants = variants,
                        loadingChannels = false
                    )
                } else {
                    state
                }
            }
        }
    }

    private fun Map<Playlist, Int>.countFor(url: String): Int? =
        entries.firstOrNull { it.key.url == url }?.value

    companion object {
        /** Playlists that stand in for other sources; never shown as sources themselves. */
        val STAND_IN_PLAYLISTS: Set<String> = setOf(
            StremioIds.PLAYLIST_URL, MediaServerViewModel.PLAYLIST_URL,
            YouTubeViewModel.PLAYLIST_URL, RadioViewModel.STATIONS_URL, RadioViewModel.PODCASTS_URL,
            FilesViewModel.PLAYLIST_URL,
        )
        private const val TV_SETTINGS_SURFACE = "tv"
        const val SEARCH_MIN_LENGTH = 2
        const val SEARCH_DEBOUNCE_MS = 350L
        const val SEARCH_LIMIT = 300
        const val RECENTLY_PLAYED_LIMIT = 10
    }
}

/**
 * Largest playlist the Fire TV shows as a single "All" list. Bigger ones are browsed a category
 * at a time (about 30 MB of channel data at this size).
 */
const val ALL_CATEGORIES_LIMIT = 40_000

private data class TvExtensionSettingsRefreshResult(
    val configuration: ExtensionSettingsConfiguration?,
    val rejected: Boolean,
)

private fun List<DiscoveredSubscriptionProvider>.providerFor(
    account: ProviderAccountSummary,
): DiscoveredSubscriptionProvider? = singleOrNull { provider ->
    provider.descriptor.providerId == account.providerId &&
        provider.descriptor.variants.any { variant ->
            variant.kind == account.providerKind
        }
}

/**
 * Picks a live channel whose title mentions both clubs. Short words that show up in
 * dozens of channel names (city, united) only count inside a longer phrase.
 */
internal object FixtureChannelFinder {
    private val stop = setOf(
        "fc", "afc", "cf", "sc", "ac", "club", "the", "and", "de", "da", "do",
        "fk", "bk", "sk", "if", "sv", "cd", "ud", "rc", "ss", "as", "us",
    )
    private val weak = setOf(
        "city", "united", "town", "athletic", "hotspur", "wanderers", "rovers",
        "albion", "sporting", "real", "football", "soccer", "calcio",
    )
    private val alias = mapOf(
        "man city" to listOf("manchester city", "man city"),
        "manchester city" to listOf("manchester city", "man city"),
        "man utd" to listOf("manchester united", "man utd"),
        "man united" to listOf("manchester united", "man utd"),
        "manchester united" to listOf("manchester united", "man utd"),
        "spurs" to listOf("tottenham", "spurs"),
        "tottenham hotspur" to listOf("tottenham", "spurs"),
        "wolves" to listOf("wolverhampton", "wolves"),
        "wolverhampton wanderers" to listOf("wolverhampton", "wolves"),
        "psg" to listOf("psg", "paris saint"),
        "paris saint germain" to listOf("psg", "paris saint"),
        "paris saint-germain" to listOf("psg", "paris saint"),
        "inter milan" to listOf("inter"),
        "internazionale" to listOf("inter"),
        "ac milan" to listOf("milan"),
        "atletico madrid" to listOf("atletico"),
        "atlético madrid" to listOf("atletico"),
        "athletic bilbao" to listOf("bilbao"),
        "real madrid" to listOf("real madrid"),
        "real sociedad" to listOf("real sociedad"),
        "real betis" to listOf("betis"),
        "borussia dortmund" to listOf("dortmund"),
        "bayern munich" to listOf("bayern"),
        "bayern munchen" to listOf("bayern"),
        "nottingham forest" to listOf("nottingham"),
        "west ham united" to listOf("west ham"),
        "newcastle united" to listOf("newcastle"),
        "brighton and hove albion" to listOf("brighton"),
    )

    suspend fun find(repository: ChannelRepository, home: String, away: String): Channel? {
        val homeNeedles = needles(home)
        val awayNeedles = needles(away)
        val queries = (homeNeedles + awayNeedles).distinct().sortedByDescending { it.length }.take(8)
        if (queries.isEmpty()) return null
        val found = LinkedHashMap<Int, Channel>()
        for (query in queries) {
            runCatching { repository.searchUnhidden(query.take(24), 50) }
                .getOrDefault(emptyList())
                .forEach { found[it.id] = it }
        }
        return found.values
            .map { channel -> channel to score(fold(channel.title), homeNeedles, awayNeedles) }
            .filter { it.second >= 40 }
            .maxWithOrNull(compareBy<Pair<Channel, Int>> { it.second }.thenBy { -it.first.title.length })
            ?.first
    }

    private fun needles(name: String): List<String> {
        val folded = fold(name)
        val words = folded.split(' ').filter { it.length >= 3 && it !in stop }
        val strong = words.filter { it.length >= 4 && it !in weak }
        val usable = if (strong.isNotEmpty()) strong else words
        val phrase = usable.joinToString(" ").takeIf { usable.size >= 2 && it.length >= 6 }
        return (alias[folded].orEmpty() + listOfNotNull(phrase) + usable + listOf(folded))
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .take(5)
    }

    private fun score(title: String, homeNeedles: List<String>, awayNeedles: List<String>): Int {
        val homeHit = homeNeedles.any { containsToken(title, it) }
        val awayHit = awayNeedles.any { containsToken(title, it) }
        var points = 0
        if (homeHit) points += 10
        if (awayHit) points += 10
        if (homeHit && awayHit) points += 30
        if (title.contains(" vs ") || title.contains(" v ") || title.contains(" x ")) points += 4
        if (homeHit && awayHit && (
                title.contains("sport") || title.contains("sky") || title.contains("tnt") ||
                    title.contains("dazn") || title.contains("espn") || title.contains("bein") ||
                    title.contains("premier") || title.contains("laliga") || title.contains("bt ")
                )
        ) {
            points += 12
        }
        if (title.contains("radio") || title.contains("news") || title.contains("highlight") || title.contains("replay")) {
            points -= 20
        }
        return points
    }

    private fun containsToken(title: String, token: String): Boolean {
        if (token.contains(' ')) return title.contains(token)
        if (token.length >= 4) return title.contains(token)
        return Regex("(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)").containsMatchIn(title)
    }

    private fun fold(name: String): String =
        name.lowercase(Locale.US)
            .replace('á', 'a').replace('à', 'a').replace('ä', 'a').replace('â', 'a')
            .replace('é', 'e').replace('è', 'e').replace('ë', 'e').replace('ê', 'e')
            .replace('í', 'i').replace('ï', 'i').replace('î', 'i')
            .replace('ó', 'o').replace('ö', 'o').replace('ô', 'o')
            .replace('ú', 'u').replace('ü', 'u').replace('û', 'u')
            .replace('ñ', 'n').replace('ç', 'c').replace('ø', 'o').replace('å', 'a')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
