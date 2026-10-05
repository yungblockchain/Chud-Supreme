package com.m3u.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.tv.model.keyCode
import com.m3u.i18n.R.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Launch extra naming the tab to open first, e.g. `--es destination games` (see [tvDestinationFromExtra]). */
const val EXTRA_DESTINATION = "destination"

/**
 * Maps the [EXTRA_DESTINATION] launch extra to a tab: a tab name such as "games" or "guide", or
 * "settings" for the settings tab. Anything else means the normal start.
 */
fun tvDestinationFromExtra(value: String?): TvDestination? {
    val name = value?.trim()?.lowercase() ?: return null
    if (name == "settings") return TvDestination.Status
    // The old single Browse tab is Live TV now.
    if (name == "library" || name == "browse") return TvDestination.Live
    if (name == "addons") return TvDestination.Infinite
    if (name == "match" || name == "matchcentre" || name == "match-centre") return TvDestination.MatchCentre
    return TvDestination.entries.firstOrNull { it.name.lowercase() == name }
}

/** The tab for Live TV, Films or Series. */
private val CatalogKind.destination: TvDestination
    get() = when (this) {
        CatalogKind.Live -> TvDestination.Live
        CatalogKind.Films -> TvDestination.Films
        CatalogKind.Series -> TvDestination.Series
    }

/** How long a screen gets to take focus itself before the app puts focus in it. */
private const val FOCUS_RESCUE_MS = 450L
private const val SETTINGS_TAB_ADDONS = 4

/** The remote counts as resting after this long without a key press. */
private const val REMOTE_IDLE_AFTER_MS = 3_000L
private const val REMOTE_IDLE_CHECK_MS = 1_000L

/** How long the "press Back again" hint waits for the second press. */
private const val EXIT_WINDOW_MS = 2_500L

/**
 * Closes the app for good: the screen goes, then the process, so nothing stays in memory.
 * (Android would otherwise keep it cached in the background.)
 */
private fun closeAppCompletely(activity: Activity) {
    activity.finishAndRemoveTask()
    Handler(Looper.getMainLooper()).postDelayed(
        { Process.killProcess(Process.myPid()) },
        EXIT_PROCESS_DELAY_MS,
    )
}

private const val EXIT_PROCESS_DELAY_MS = 400L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun App(
    initialDestination: TvDestination? = null,
    viewModel: TvHomeViewModel = hiltViewModel(),
    dial: DialViewModel = hiltViewModel(),
    claude: ClaudeViewModel = hiltViewModel(),
    metadata: MetadataViewModel = hiltViewModel(),
    services: ServicesSettingsViewModel = hiltViewModel(),
    accounts: XtreamAccountViewModel = hiltViewModel(),
    multiview: MultiviewViewModel = hiltViewModel(),
    party: WatchPartyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasXtreamSession by accounts.hasSession.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val playbackFailed by viewModel.playbackFailed.collectAsStateWithLifecycle()
    val guideLine by viewModel.guideLine.collectAsStateWithLifecycle()
    val remoteControlCode by viewModel.remoteControlCode.collectAsStateWithLifecycle()
    val view = LocalView.current
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val context = LocalContext.current
    val diagnosticsShareTitle = stringResource(string.feat_setting_extension_diagnostics_share_title)
    val currentDiagnosticsShareTitle by rememberUpdatedState(diagnosticsShareTitle)
    var destination by remember { mutableStateOf(initialDestination ?: TvDestination.Home) }
    var settingsTab by remember { mutableIntStateOf(0) }
    val menuFocus = remember { FocusRequester() }
    var surface by remember { mutableStateOf(TvSurface.Browse) }
    val closePlayer = {
        viewModel.releasePlayer()
        surface = TvSurface.Browse
    }
    val minimizePlayer = {
        surface = TvSurface.Mini
        // Swapping the full-screen surface for the corner one clears the picture.
        // Tell the player to keep going instead of sitting on a paused frame.
        if (isPlaying || playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
            viewModel.pauseOrContinue(true)
        }
    }
    // Browsing, with or without the mini player in the corner.
    val onBrowse = surface == TvSurface.Browse || surface == TvSurface.Mini

    // Dial: settings, details pages, continue watching.
    val preferences by dial.preferences.collectAsStateWithLifecycle()
    val details by dial.details.collectAsStateWithLifecycle()
    val continueWatching by dial.continueWatching.collectAsStateWithLifecycle()
    val nowPlaying by dial.nowPlaying.collectAsStateWithLifecycle()

    // What's playing, and whether up/down should flip channels. Flipping walks the list the channel
    // was opened from: the selected playlist if it's in there, otherwise favourites.
    val playingId = currentChannel?.id
    val playingPlaylist = state.playlists.firstOrNull { it.url == currentChannel?.playlistUrl }
    val catchUp = currentChannel?.url?.contains("/timeshift/") == true
    val live = !catchUp &&
        (playingPlaylist == null || !(playingPlaylist.isVod || playingPlaylist.isSeries))
    val zapChannels = when {
        playingId == null || !live -> emptyList()
        state.channels.any { it.id == playingId } -> state.channels
        state.favorites.any { it.id == playingId } -> state.favorites
        else -> listOfNotNull(currentChannel)
    }
    val zapIndex = zapChannels.indexOfFirst { it.id == playingId }
    val zap: (Int) -> Unit = { step ->
        if (zapIndex >= 0 && zapChannels.size > 1) {
            viewModel.play(zapChannels[Math.floorMod(zapIndex + step, zapChannels.size)])
        }
    }

    // Films and series open their details page; live channels play straight away, in the
    // built-in player or in VLC / another app if that's the choice in Settings.
    val openOrPlay: (Channel) -> Unit = { channel ->
        val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
        if (playlist != null && (playlist.isVod || playlist.isSeries)) {
            dial.openDetails(channel, playlist)
        } else if (dial.playsExternally(channel)) {
            dial.playLiveExternally(channel)
        } else {
            viewModel.play(channel)
            surface = TvSurface.Player
        }
    }

    // Outside players: start them, and save where they stopped when they return.
    var pendingExternal by remember { mutableStateOf<ExternalPlayback?>(null) }
    val externalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val playback = pendingExternal ?: return@rememberLauncherForActivityResult
        pendingExternal = null
        ExternalPlayers.resultOf(result.data)?.let { (position, duration) ->
            dial.onExternalResult(playback, position, duration)
        }
    }
    val chooserTitle = stringResource(R.string.dial_player_choose)
    val noPlayerMessage = stringResource(R.string.dial_player_none)
    LaunchedEffect(dial) {
        dial.externalPlayback.collect { playback ->
            pendingExternal = playback
            try {
                externalLauncher.launch(ExternalPlayers.intentFor(playback, chooserTitle))
            } catch (_: ActivityNotFoundException) {
                pendingExternal = null
                Toast.makeText(context, noPlayerMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(dial) {
        dial.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Launch screen with the spinning mascot, once per app start (not on rotation or resume).
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val showSplash = !splashDone && preferences.launchAnimation

    var startupHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startupHandled) return@LaunchedEffect
        startupHandled = true
        // A tab asked for by the launch intent wins over the startup setting.
        if (initialDestination != null) return@LaunchedEffect
        when (dial.preferences.value.startup) {
            DialStartup.Home -> Unit
            DialStartup.Guide -> destination = TvDestination.Guide
            DialStartup.LastChannel -> if (dial.playLastChannel()) surface = TvSurface.Player
        }
    }
    LaunchedEffect(playingId) {
        viewModel.refreshGuide(currentChannel)
    }
    LaunchedEffect(playingId, live, playingPlaylist != null) {
        if (playingId != null && live && playingPlaylist != null) dial.rememberLastChannel(playingId)
        if (live || catchUp) dial.clearNowPlaying()
    }

    // Menus at the fastest refresh rate the Fire TV offers at this resolution.
    LaunchedEffect(preferences.fastMenus, surface) {
        if (surface == TvSurface.Browse || surface == TvSurface.Mini) {
            view.context.findActivity()?.let { DisplayModes.applyMenuMode(it, preferences.fastMenus) }
        }
    }

    // Watch party: what the host is playing, for guests; and a guest's player coming to the front.
    val partyHosting by party.hosting.collectAsStateWithLifecycle()
    val partyGuest by party.guest.collectAsStateWithLifecycle()
    val skipMarkers by dial.skipMarkers.collectAsStateWithLifecycle()
    LaunchedEffect(currentChannel, nowPlaying, live, player?.currentMediaItem?.mediaId) {
        val channel = currentChannel
        val url = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: channel?.url
        party.updateProgramme(
            if (channel == null || url == null) null else PartyProgramme(
                title = channel.title,
                url = url,
                live = live,
                seriesTitle = nowPlaying?.series?.title,
                season = nowPlaying?.episode?.season,
                episode = nowPlaying?.episode?.toEpisodeInfo(),
            )
        )
    }
    val partyHostStream = stringResource(R.string.dial_party_host_stream)
    val partyStarted = stringResource(R.string.dial_party_started)
    val partyFailed = stringResource(R.string.dial_party_failed)
    LaunchedEffect(party) {
        party.events.collect { event ->
            when (event) {
                PartyEvent.ShowPlayer -> surface = TvSurface.Player
                is PartyEvent.JoinedStream ->
                    if (!event.ownStream) Toast.makeText(context, partyHostStream, Toast.LENGTH_LONG).show()
            }
        }
    }
    val partyErrors = PartyFailure.entries.associateWith { reason ->
        stringResource(
            when (reason) {
                PartyFailure.BadCode -> R.string.dial_party_error_code
                PartyFailure.NoNetwork -> R.string.dial_party_error_network
                PartyFailure.HostNotFound -> R.string.dial_party_error_host
                PartyFailure.Rejected -> R.string.dial_party_error_rejected
                PartyFailure.NothingPlaying -> R.string.dial_party_error_nothing
            }
        )
    }
    LaunchedEffect(partyGuest) {
        val failed = partyGuest as? GuestState.Failed ?: return@LaunchedEffect
        Toast.makeText(context, partyErrors[failed.reason], Toast.LENGTH_LONG).show()
    }
    val startParty: () -> Unit = {
        val code = party.startHosting()
        Toast.makeText(context, if (code != null) partyStarted.format(code) else partyFailed, Toast.LENGTH_LONG).show()
    }
    val partyControls = PartyControls(
        hosting = partyHosting,
        guest = partyGuest,
        onStartHosting = startParty,
        onStopHosting = party::stopHosting,
        onJoin = party::join,
        onLeave = party::leave,
    )

    // Up next: when an episode finishes, the following one starts after a short countdown.
    val upNext = if (
        surface == TvSurface.Player &&
        playbackState == Player.STATE_ENDED &&
        preferences.autoplayNextEpisode
    ) {
        remember(playingId, nowPlaying) { dial.nextEpisode() }
    } else null
    LaunchedEffect(surface) {
        if (surface == TvSurface.Browse || surface == TvSurface.Mini) {
            dial.refreshAfterPlayback()
            viewModel.refreshRecentlyPlayed()
        }
    }

    // TMDB and Trakt (with the person's own keys).
    val trending by metadata.trending.collectAsStateWithLifecycle()
    val detailsExtras by metadata.extras.collectAsStateWithLifecycle()
    val person by metadata.person.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val notInPlaylists = stringResource(R.string.dial_trending_not_found)
    LaunchedEffect(destination) {
        if (destination == TvDestination.Home) metadata.loadTrending()
    }
    LaunchedEffect(details?.channel?.id, details?.loading) {
        val current = details ?: return@LaunchedEffect
        if (!current.loading) metadata.loadExtras(current)
    }
    val openTmdbTitle: (TmdbTitle, Channel?) -> Unit = { title, known ->
        scope.launch {
            val channel = known ?: metadata.findInCatalogue(title)
            if (channel == null) {
                Toast.makeText(context, notInPlaylists.format(title.title), Toast.LENGTH_SHORT).show()
            } else {
                metadata.closePerson()
                openOrPlay(channel)
            }
        }
    }

    // The phone page: what's typed on the phone lands here.
    val keySaved = stringResource(R.string.dial_phone_key_saved)
    val skinSaved = stringResource(R.string.dial_phone_skin_saved)
    LaunchedEffect(services) {
        services.phoneMessages.collect { message ->
            when (message) {
                is PhoneMessage.Search -> {
                    destination = TvDestination.Search
                    viewModel.search(message.query)
                }
                is PhoneMessage.AskClaude -> {
                    destination = TvDestination.Claude
                    claude.send(message.question)
                }
                is PhoneMessage.XtreamLogin -> {
                    destination = TvDestination.Account
                    accounts.setMode(SignInMode.Xtream)
                    accounts.updateServer(message.server)
                    accounts.updateUsername(message.username)
                    accounts.updatePassword(message.password)
                }
                is PhoneMessage.M3uPlaylist -> {
                    destination = TvDestination.Account
                    accounts.setMode(SignInMode.M3u)
                    accounts.updatePlaylistUrl(message.url)
                    accounts.updateEpgUrl(message.epgUrl)
                }
                is PhoneMessage.KeySaved -> Toast.makeText(context, keySaved, Toast.LENGTH_SHORT).show()
                is PhoneMessage.SkinApplied ->
                    Toast.makeText(context, skinSaved.format(message.name), Toast.LENGTH_SHORT).show()
                is PhoneMessage.JoinParty -> party.join(message.code)
            }
        }
    }

    // Error reports: a stream that stopped for good (after the automatic retries).
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    LaunchedEffect(playbackFailed, playbackError) {
        if (!playbackFailed) return@LaunchedEffect
        val channel = currentChannel ?: return@LaunchedEffect
        val host = runCatching { Uri.parse(channel.url).host }.getOrNull() ?: "?"
        withContext(Dispatchers.IO) {
            CrashReports.recordError(
                context = context,
                title = "Stream stopped: ${channel.title}",
                details = "Error: ${playbackError ?: "unknown"}\nLive: $live\nServer: $host",
            )
        }
    }
    // Send saved reports when the app starts, if that's switched on.
    LaunchedEffect(Unit) {
        if (dial.preferences.value.autoSendReports) services.sendReports()
    }

    // Multiview: the main player stops (the Fire TV has few video decoders), tiles take over.
    val openMultiview: (Channel) -> Unit = { channel ->
        viewModel.releasePlayer()
        multiview.add(channel)
        surface = TvSurface.Multiview
    }

    // Hold-OK menus.
    val favouriteGroups by dial.favouriteGroups.collectAsStateWithLifecycle()
    val groupChannels by dial.groupChannels.collectAsStateWithLifecycle()
    var menuChannel by remember { mutableStateOf<Channel?>(null) }
    var menuCategory by remember { mutableStateOf<String?>(null) }
    var menuReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    var browseHasFocus by remember { mutableStateOf(false) }
    val contentFocus = remember { FocusRequester() }
    val openChannelMenu = remember {
        { channel: Channel, requester: FocusRequester ->
            menuReturnFocus = requester
            menuChannel = channel
        }
    }
    val openCategoryMenu = remember {
        { name: String, requester: FocusRequester ->
            menuReturnFocus = requester
            menuCategory = name
        }
    }
    // Back on the card or chip the menu was opened from.
    LaunchedEffect(menuChannel == null && menuCategory == null) {
        val target = menuReturnFocus ?: return@LaunchedEffect
        if (menuChannel != null || menuCategory != null) return@LaunchedEffect
        yield()
        runCatching { target.requestFocus() }
        menuReturnFocus = null
    }
    fun kindOf(channel: Channel): MenuItemKind {
        val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
        return when {
            playlist?.isSeries == true -> MenuItemKind.Series
            playlist?.isVod == true -> MenuItemKind.Film
            else -> MenuItemKind.Live
        }
    }
    val askClaudePrompt = stringResource(R.string.dial_menu_ask_claude_prompt)
    fun askClaudeAbout(channel: Channel) {
        claude.send(askClaudePrompt.format(channel.title, channel.category))
        destination = TvDestination.Claude
    }

    val backTarget = tvAppBackTarget(
        playerVisible = surface == TvSurface.Player,
        providerSubscriptionVisible = state.providerSubscriptionForm != null,
        extensionSettingsVisible = state.extensionSettings != null,
    )
    BackHandler(enabled = backTarget != TvAppBackTarget.ACTIVITY) {
        when (backTarget) {
            TvAppBackTarget.PLAYER -> if (preferences.backToMini) minimizePlayer() else closePlayer()
            TvAppBackTarget.PROVIDER_SUBSCRIPTION -> viewModel.closeProviderSubscription()
            TvAppBackTarget.EXTENSION_SETTINGS -> viewModel.closeExtensionSettings()
            TvAppBackTarget.ACTIVITY -> Unit
        }
    }

    // Back on the main menu: the first press says "press again", the second closes the app
    // completely (the process ends, so everything it held in memory is freed). Screens with their
    // own Back step (a game, a details page) handle Back first.
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    var exitHintVisible by remember { mutableStateOf(false) }
    BackHandler(
        enabled = backTarget == TvAppBackTarget.ACTIVITY && details == null &&
            !showSplash && preferences.backTwiceToExit && surface == TvSurface.Browse
    ) {
        val now = SystemClock.uptimeMillis()
        if (exitHintVisible && now - exitArmedAt < EXIT_WINDOW_MS) {
            viewModel.releasePlayer()
            view.context.findActivity()?.let(::closeAppCompletely)
        } else {
            exitArmedAt = now
            exitHintVisible = true
        }
    }
    // With the mini player showing, Back closes it first.
    BackHandler(enabled = surface == TvSurface.Mini && details == null, onBack = closePlayer)
    // No screensaver while video plays, loads or reconnects (full screen, mini or Multiview):
    // the screensaver would stop the stream.
    val keepAwake = surface == TvSurface.Multiview ||
        ((surface == TvSurface.Player || surface == TvSurface.Mini) &&
            (isPlaying || playbackState == Player.STATE_BUFFERING || reconnecting))
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    // Whether the remote was used in the last few seconds (LocalTvRemoteBusy). Only what reads it
    // (the menu's logo) redraws when it flips, never this whole screen.
    val remoteBusy = remember { mutableStateOf(false) }
    val lastKeyAt = remember { longArrayOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REMOTE_IDLE_CHECK_MS)
            if (remoteBusy.value && SystemClock.uptimeMillis() - lastKeyAt[0] >= REMOTE_IDLE_AFTER_MS) {
                remoteBusy.value = false
            }
        }
    }

    LaunchedEffect(exitArmedAt) {
        if (exitArmedAt == 0L) return@LaunchedEffect
        delay(EXIT_WINDOW_MS)
        exitHintVisible = false
    }

    // Home button, screensaver or the TV switching off: stop the stream so it doesn't die in the
    // background, then pick it up again when the app is back on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.sleepPlayer()
                    multiview.stopAll()
                }
                Lifecycle.Event.ON_START -> {
                    viewModel.wakePlayer()
                    multiview.resumeAll()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(view) {
        viewModel.remoteDirections.collect { direction ->
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction.keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction.keyCode))
        }
    }
    LaunchedEffect(viewModel, localeTag) {
        viewModel.updateLocale(localeTag)
    }
    LaunchedEffect(viewModel, context) {
        viewModel.extensionDiagnostics.collect { payload ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    },
                    currentDiagnosticsShareTitle,
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { event ->
                // Note the remote being used (decorative motion waits for it to rest).
                lastKeyAt[0] = SystemClock.uptimeMillis()
                if (!remoteBusy.value) remoteBusy.value = true
                // The Menu key opens the side menu while browsing (it may be hidden to a strip).
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu &&
                    surface == TvSurface.Browse && details == null && !showSplash &&
                    menuChannel == null && menuCategory == null
                ) {
                    runCatching { menuFocus.requestFocus() }
                    return@onPreviewKeyEvent true
                }
                // Mini player: Menu brings it back full screen; play/pause works from anywhere.
                if (surface != TvSurface.Mini || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> {
                        surface = TvSurface.Player
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        viewModel.pauseOrContinue(!isPlaying)
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Only what's playing (or was last played) sets the backdrop, so browsing categories
        // never swaps the picture behind the whole screen.
        TvBackdrop(channel = currentChannel ?: state.recent)
        val browsing = onBrowse && details == null && !showSplash
        // Nothing focused after the launch animation, a tab change or an overlay closing (the
        // screen asked for focus while it couldn't take it): put focus in the screen, so the
        // first key press does something sensible.
        LaunchedEffect(browsing, destination, menuChannel == null && menuCategory == null) {
            if (!browsing || menuChannel != null || menuCategory != null) return@LaunchedEffect
            delay(FOCUS_RESCUE_MS)
            if (!browseHasFocus) runCatching { contentFocus.requestFocus() }
        }
        CompositionLocalProvider(
            LocalTvFocusEnabled provides browsing,
            LocalChannelMenu provides openChannelMenu.takeIf { browsing },
            LocalCategoryMenu provides openCategoryMenu.takeIf { browsing },
            LocalTvRemoteBusy provides remoteBusy,
        ) {
            // The menu folds to a strip of icons at the left edge and opens over the screen, so
            // screens start just after the strip and never re-flow when it opens.
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { browseHasFocus = it.hasFocus }
            ) {
                TvBrowsePane(
                    modifier = Modifier
                        .padding(start = railCollapsedWidth())
                        .clipToBounds()
                        .focusRequester(contentFocus)
                        .focusGroup(),
                    destination = destination,
                    state = state,
                    onOpenLibrary = { destination = TvDestination.Live },
                    onPlaylist = viewModel::selectPlaylist,
                    onOpenCatalog = viewModel::openCatalog,
                    onShowCatalog = { kind ->
                        viewModel.openCatalog(kind)
                        destination = kind.destination
                    },
                    onAddSource = { destination = TvDestination.Account },
                    onPlayResolved = { surface = TvSurface.Player },
                    onManageAddons = {
                        settingsTab = SETTINGS_TAB_ADDONS
                        destination = TvDestination.Status
                    },
                    onWatchFixture = { home, away, report ->
                        viewModel.watchFixture(home, away) { channel ->
                            if (channel == null) {
                                report("No channel title matches both $home and $away.")
                            } else if (dial.playsExternally(channel)) {
                                dial.playLiveExternally(channel)
                                report("Opening ${channel.title}")
                            } else {
                                viewModel.play(channel)
                                surface = TvSurface.Player
                            }
                        }
                    },
                    signedIn = state.playlists.isNotEmpty() || hasXtreamSession,
                    restoringLibrary = state.playlists.isEmpty() && hasXtreamSession,
                    settingsTab = settingsTab,
                    onSettingsTab = { settingsTab = it },
                    onRefresh = viewModel::refreshSelectedPlaylist,
                    onPlay = openOrPlay,
                    onPlayRecent = { state.recent?.let(openOrPlay) },
                    onExternalExtensionsEnabled = viewModel::setExternalExtensionsEnabled,
                    onEnableExtension = viewModel::enableExtensionPlugin,
                    onReauthorizeExtension = viewModel::reauthorizeExtensionPlugin,
                    onDisableExtension = viewModel::disableExtensionPlugin,
                    onRevokeExtension = viewModel::revokeExtensionPlugin,
                    onClearExtensionData = viewModel::clearExtensionData,
                    onExportExtensionDiagnostics = viewModel::exportExtensionDiagnostics,
                    onOpenExtensionSettings = { extensionId ->
                        viewModel.openExtensionSettings(extensionId, localeTag)
                    },
                    onCloseExtensionSettings = viewModel::closeExtensionSettings,
                    onUpdateExtensionSetting = { sectionId, fieldKey, editToken, value ->
                        viewModel.updateExtensionSetting(
                            sectionId,
                            fieldKey,
                            editToken,
                            value,
                            localeTag,
                        )
                    },
                    onRefreshProviders = viewModel::refreshSubscriptionProviders,
                    onOpenProviderSubscription = viewModel::openProviderSubscription,
                    onReauthenticateProvider = viewModel::reauthenticateProviderAccount,
                    onCloseProviderSubscription = viewModel::closeProviderSubscription,
                    onUpdateProviderTitle = viewModel::updateProviderSubscriptionTitle,
                    onSelectProviderKind = viewModel::selectProviderKind,
                    onUpdateProviderSetting = viewModel::updateProviderSetting,
                    onSubmitProviderSubscription = viewModel::submitProviderSubscription,
                    continueWatching = continueWatching,
                    onSelectCategory = viewModel::selectCategory,
                    onSearch = viewModel::search,
                    guideContent = {
                        GuideScreen(
                            state = state,
                            dial = dial,
                            onSelectPlaylist = viewModel::selectPlaylist,
                            onSelectCategory = viewModel::selectCategory,
                            onPlayLive = { channel ->
                                if (dial.playsExternally(channel)) {
                                    dial.playLiveExternally(channel)
                                } else {
                                    viewModel.play(channel)
                                    surface = TvSurface.Player
                                }
                            },
                            onPlayCatchUp = { channel, programme ->
                                if (dial.playsExternally(channel)) {
                                    dial.playCatchUp(channel, programme, external = true)
                                } else {
                                    dial.playCatchUp(channel, programme)
                                    surface = TvSurface.Player
                                }
                            },
                        )
                    },
                    favouritesContent = {
                        FavouritesScreen(
                            state = state,
                            groups = favouriteGroups,
                            groupChannels = groupChannels,
                            onPlay = openOrPlay,
                            onMoveGroup = dial::moveGroup,
                            onDeleteGroup = dial::deleteGroup,
                        )
                    },
                    trending = trending,
                    onOpenTrending = { entry -> openTmdbTitle(entry.title, entry.channel) },
                    myLibraryContent = {
                        MyLibraryScreen(
                            state = state,
                            continueWatching = continueWatching,
                            onPlay = openOrPlay,
                        )
                    },
                    claudeContent = {
                        ClaudeScreen(onPlay = openOrPlay)
                    },
                    playbackSettingsContent = {
                        PlaybackSettingsScreen(
                            preferences = preferences,
                            onUpdate = dial::updatePreferences,
                        )
                    },
                    servicesSettingsContent = {
                        ServicesSettingsScreen()
                    },
                    dialSettingsContent = {
                        DialSettingsScreen(
                            preferences = preferences,
                            onUpdate = dial::updatePreferences,
                            onClearHistory = dial::clearContinueWatching,
                            pairingCode = remoteControlCode?.toString()?.padStart(6, '0'),
                            party = partyControls,
                        )
                    },
                )
                TvNavigationRail(
                    selected = destination,
                    onSelect = { destination = it },
                    focusRequester = menuFocus,
                )
            }
        }

        details?.let { current ->
            DetailsScreen(
                state = current,
                active = onBrowse && person == null,
                isFavourite = state.favorites.any { it.id == current.channel.id },
                onPlayFilm = { fromStart ->
                    if (dial.playsExternally(current.channel)) {
                        dial.playFilm(current.channel, fromStart, external = true)
                    } else {
                        dial.playFilm(current.channel, fromStart)
                        surface = TvSurface.Player
                    }
                },
                onContinueSeries = { progress ->
                    if (dial.playsExternally(current.channel)) {
                        dial.continueSeries(current.channel, progress, external = true)
                    } else {
                        dial.continueSeries(current.channel, progress)
                        surface = TvSurface.Player
                    }
                },
                onPlayEpisode = { episode ->
                    if (dial.playsExternally(current.channel)) {
                        dial.playEpisode(current.channel, episode, fromStart = false, external = true)
                    } else {
                        dial.playEpisode(current.channel, episode, fromStart = false)
                        surface = TvSurface.Player
                    }
                },
                onSelectSeason = dial::selectSeason,
                onToggleFavourite = { viewModel.toggleFavorite(current.channel) },
                onBack = dial::closeDetails,
                extras = detailsExtras,
                onOpenPerson = { member -> metadata.openPerson(member.id) },
            )
        }

        person?.let { current ->
            PersonScreen(
                state = current,
                onOpenTitle = { title -> openTmdbTitle(title, null) },
                onBack = metadata::closePerson,
            )
        }

        menuChannel?.let { channel ->
            val kind = kindOf(channel)
            val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
            val external = dial.preferences.value.player != DialPlayer.BuiltIn
            ChannelMenu(
                channel = channel,
                kind = kind,
                favourite = state.favorites.any { it.id == channel.id },
                groups = favouriteGroups,
                actions = ChannelMenuActions(
                    play = {
                        when (kind) {
                            MenuItemKind.Film -> if (dial.playsExternally(channel)) {
                                dial.playFilm(channel, fromStart = false, external = true)
                            } else {
                                dial.playFilm(channel, fromStart = false)
                                surface = TvSurface.Player
                            }
                            else -> openOrPlay(channel)
                        }
                    },
                    playFromStart = if (kind == MenuItemKind.Film) {
                        {
                            dial.playFilm(channel, fromStart = true)
                            surface = TvSurface.Player
                        }
                    } else null,
                    details = if (kind != MenuItemKind.Live) {
                        { dial.openDetails(channel, playlist) }
                    } else null,
                    toggleFavourite = { viewModel.toggleFavorite(channel) },
                    playExternally = when {
                        !channel.url.startsWith("http", ignoreCase = true) || external -> null
                        kind == MenuItemKind.Live -> { { dial.playLiveExternally(channel) } }
                        kind == MenuItemKind.Film -> {
                            { dial.playFilm(channel, fromStart = false, external = true) }
                        }
                        else -> null
                    },
                    hide = if (kind == MenuItemKind.Live) {
                        { viewModel.hideChannel(channel) }
                    } else null,
                    askClaude = { askClaudeAbout(channel) },
                    addToMultiview = if (kind == MenuItemKind.Live && multiview.supports(channel)) {
                        { openMultiview(channel) }
                    } else null,
                    toggleGroup = { groupId -> dial.toggleInGroup(groupId, channel.id) },
                    createGroup = { name -> dial.createGroup(name, channel.id) },
                ),
                onDismiss = { menuChannel = null },
            )
        }

        menuCategory?.let { name ->
            val index = state.categories.indexOfFirst { it.name == name }
            CategoryMenu(
                name = name,
                index = index,
                count = state.categories.size,
                hiddenCount = state.hiddenCategoryCount,
                onMoveToFront = { viewModel.moveCategoryToFront(name) },
                onMove = { delta -> viewModel.moveCategory(name, delta) },
                onHide = { viewModel.hideCategory(name) },
                onShowAll = viewModel::showAllCategories,
                onReset = viewModel::resetCategories,
                onDismiss = { menuCategory = null },
            )
        }

        if (surface == TvSurface.Player) {
            TvPlayerScreen(
                player = player,
                channel = currentChannel,
                channelNumber = (zapIndex + 1).takeIf { zapIndex >= 0 && preferences.showChannelNumbers },
                live = live,
                canZap = live && zapIndex >= 0 && zapChannels.size > 1,
                isFavourite = playingId != null && state.favorites.any { it.id == playingId },
                isPlaying = isPlaying,
                playbackState = playbackState,
                reconnecting = reconnecting,
                failed = playbackFailed,
                guideLine = guideLine,
                preferences = preferences,
                subtitleTarget = nowPlaying?.subtitleTarget,
                onUpdatePreferences = dial::updatePreferences,
                onPlayPause = { viewModel.pauseOrContinue(!isPlaying) },
                onNextChannel = { zap(1) },
                onPreviousChannel = { zap(-1) },
                onToggleFavourite = { currentChannel?.let(viewModel::toggleFavorite) },
                onBack = if (preferences.backToMini) minimizePlayer else closePlayer,
                onClose = closePlayer,
                onMinimize = minimizePlayer,
                onMultiview = { currentChannel?.let(openMultiview) },
                skipMarkers = skipMarkers,
                onUpdateSkipMarkers = if (nowPlaying?.series != null) dial::updateSkipMarkers else null,
                onNextEpisode = if (nowPlaying?.series != null) {
                    { dial.nextEpisode()?.let { (series, episode) -> dial.playEpisode(series, episode, fromStart = true) } }
                } else null,
                partyHosting = partyHosting,
                partyGuest = partyGuest,
                onStartParty = startParty,
                onStopParty = party::stopHosting,
                onLeaveParty = party::leave,
            )
        }

        if (surface == TvSurface.Mini) {
            player?.let { current ->
                MiniPlayer(
                    player = current,
                    channel = currentChannel,
                    size = preferences.miniSize,
                    modifier = Modifier
                        .align(
                            when (preferences.miniCorner) {
                                MiniCorner.BottomRight -> Alignment.BottomEnd
                                MiniCorner.BottomLeft -> Alignment.BottomStart
                                MiniCorner.TopRight -> Alignment.TopEnd
                                MiniCorner.TopLeft -> Alignment.TopStart
                            }
                        )
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
        }

        if (surface == TvSurface.Multiview) {
            val liveChannels = remember(state.favorites, state.channels, state.playlists) {
                (state.favorites + state.channels)
                    .distinctBy { it.id }
                    .filter { kindOf(it) == MenuItemKind.Live }
            }
            MultiviewScreen(
                candidates = liveChannels,
                onOpenFull = { channel ->
                    viewModel.play(channel)
                    surface = TvSurface.Player
                },
                onClose = { surface = TvSurface.Browse },
                viewModel = multiview,
            )
        }

        upNext?.let { (series, episode) ->
            UpNextCard(
                episode = episode,
                onPlay = { dial.playEpisode(series, episode, fromStart = true) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 48.dp),
            )
        }

        if (showSplash) {
            BrandSplash(onFinished = { splashDone = true })
        }

        AnimatedVisibility(
            visible = exitHintVisible && onBrowse,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.dial_exit_hint),
                color = TvColors.OnFocus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvColors.Focus)
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
