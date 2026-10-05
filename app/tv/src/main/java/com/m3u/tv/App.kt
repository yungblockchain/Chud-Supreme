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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.tv.stremio.StremioIds
import com.m3u.data.tv.model.keyCode
import com.m3u.i18n.R.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Launch extra naming the tab to open first, e.g. `--es destination games` (see [tvDestinationFromExtra]). */
const val EXTRA_DESTINATION = "destination"

/** Launch extra (boolean): skip the bundled provider login; the emulator walkthrough sets it. */
const val EXTRA_NO_BUNDLED_LOGIN = "no_bundled_login"

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

/** How often a kids profile's play time is counted. */
private const val KIDS_TICK_MS = 30_000L

/** How often a Jellyfin/Emby server hears where playback has got to. */
private const val SERVER_PROGRESS_MS = 10_000L

/** How often the scrobbler is told where playback has got to. */
private const val SCROBBLE_TICK_MS = 60_000L

/** Reminders: how early the card shows, and how often the clock is checked. */
private const val REMINDER_LEAD_MS = 2 * 60_000L
private const val REMINDER_CHECK_MS = 20_000L
private const val REMINDER_RUMBLE_MS = 250L
private const val NOTICE_REMINDER_WINDOW_MS = 6 * 60 * 60_000L
private const val WATCHED_CHECK_MS = 30_000L
private const val LIGHTS_DIM_DELAY_MS = 1_500L
private const val LIGHTS_UP_DELAY_MS = 4_000L
/** A key held this many repeats (about half a second) counts as a long press. */
private const val LONG_PRESS_REPEAT = 1

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
    server: MediaServerViewModel = hiltViewModel(),
    universal: UniversalSearchViewModel = hiltViewModel(),
    profilesVm: ProfilesViewModel = hiltViewModel(),
    weather: WeatherViewModel = hiltViewModel(),
    notices: NoticesViewModel = hiltViewModel(),
    youtube: YouTubeViewModel = hiltViewModel(),
    radio: RadioViewModel = hiltViewModel(),
    liveBadges: LiveBadgesViewModel = hiltViewModel(),
    discover: DiscoverViewModel = hiltViewModel(),
    smartHome: SmartHomeViewModel = hiltViewModel(),
    files: FilesViewModel = hiltViewModel(),
    outputs: AudioOutputViewModel = hiltViewModel(),
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
        youtube.stopped()
        radio.stopped()
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
    val partyGuestState by party.guest.collectAsStateWithLifecycle()

    // What's playing, and whether up/down should flip channels. Flipping walks the list the channel
    // was opened from: the selected playlist if it's in there, otherwise favourites.
    val playingId = currentChannel?.id
    val playingPlaylist = state.playlists.firstOrNull { it.url == currentChannel?.playlistUrl }
    val catchUp = currentChannel?.url?.contains("/timeshift/") == true
    // A watch-party guest follows the host's kind of stream, whatever playlist its own copy is in.
    val partyGuestLive = (partyGuestState as? GuestState.InParty)?.live
    // Media-server, addon, YouTube and podcast items are on demand, whatever their stand-in
    // playlist says; radio stations are live.
    val onDemandSource = currentChannel?.playlistUrl == MediaServerViewModel.PLAYLIST_URL ||
        currentChannel?.playlistUrl == StremioIds.PLAYLIST_URL ||
        currentChannel?.playlistUrl == YouTubeViewModel.PLAYLIST_URL ||
        currentChannel?.playlistUrl == RadioViewModel.PODCASTS_URL ||
        currentChannel?.playlistUrl == FilesViewModel.PLAYLIST_URL
    val audioSource = currentChannel?.playlistUrl == RadioViewModel.STATIONS_URL ||
        currentChannel?.playlistUrl == RadioViewModel.PODCASTS_URL
    val live = partyGuestLive ?: (!catchUp && !onDemandSource &&
        (playingPlaylist == null || !(playingPlaylist.isVod || playingPlaylist.isSeries)))
    // With duplicates merged, a copy that's playing counts as the card that stands for it.
    val shownId = playingId?.let { id ->
        state.variants.entries.firstOrNull { (_, copies) -> copies.any { it.id == id } }?.key ?: id
    }
    val zapChannels = when {
        playingId == null || !live -> emptyList()
        state.channels.any { it.id == shownId } -> state.channels
        state.favorites.any { it.id == playingId } -> state.favorites
        else -> listOfNotNull(currentChannel)
    }
    val zapIndex = zapChannels.indexOfFirst { it.id == shownId }
    val nowTitles by viewModel.nowTitles.collectAsStateWithLifecycle()
    val zap: (Int) -> Unit = { step ->
        if (zapIndex >= 0 && zapChannels.size > 1) {
            viewModel.play(zapChannels[Math.floorMod(zapIndex + step, zapChannels.size)])
        }
    }

    // Films and series open their details page; live channels play straight away, in the
    // built-in player or in VLC / another app if that's the choice in Settings.
    val openOrPlay: (Channel) -> Unit = { channel ->
        val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
        if (channel.playlistUrl == YouTubeViewModel.PLAYLIST_URL) {
            // A saved YouTube row: its streams are looked up again (the old address was a one-off).
            youtube.playSaved(channel) { surface = TvSurface.Player }
        } else if (channel.playlistUrl == FilesViewModel.PLAYLIST_URL) {
            // A file on a share: served by the stick again before it plays.
            files.playSaved(channel) { surface = TvSurface.Player }
        } else if (playlist != null && (playlist.isVod || playlist.isSeries)) {
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

    // Who's watching: the picker before anything else when there's a choice; a kids profile sees
    // fewer tabs and no adult categories, and its play time can be capped.
    val profileList by profilesVm.profiles.collectAsStateWithLifecycle()
    val activeProfile by profilesVm.active.collectAsStateWithLifecycle()
    val needsPicker = activeProfile == null && (profileList.size > 1 || profileList.any { it.hasPin })
    val kidsProfile = activeProfile?.kids == true
    val destinations = remember(kidsProfile) {
        if (kidsProfile) TvDestination.entries.filter { it in KIDS_DESTINATIONS || it == TvDestination.Status }
        else TvDestination.entries
    }
    LaunchedEffect(kidsProfile, destination) {
        if (kidsProfile && destination !in destinations) destination = TvDestination.Home
    }
    var timeUp by remember { mutableStateOf(false) }
    // The picker or the time's-up card on top: nothing behind them takes keys or focus.
    val overlayUp = needsPicker || timeUp
    val kidsLimitMs = (activeProfile?.takeIf { it.kids }?.kidsMinutes ?: 0) * 60_000L
    LaunchedEffect(activeProfile?.id) { timeUp = false }
    LaunchedEffect(activeProfile?.id, kidsLimitMs, isPlaying) {
        val profile = activeProfile ?: return@LaunchedEffect
        if (kidsLimitMs <= 0L) return@LaunchedEffect
        fun stopForToday() {
            viewModel.releasePlayer()
            surface = TvSurface.Browse
            timeUp = true
        }
        // Already over for today: the card, before anything plays.
        if (dial.addKidsPlayTime(profile.id, 0L) >= kidsLimitMs) {
            stopForToday()
            return@LaunchedEffect
        }
        if (!isPlaying) return@LaunchedEffect
        // Play time is counted by the clock, and the part-tick is written when playback pauses.
        var lastTick = SystemClock.elapsedRealtime()
        try {
            while (true) {
                delay(KIDS_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val played = dial.addKidsPlayTime(profile.id, now - lastTick)
                lastTick = now
                if (played >= kidsLimitMs) {
                    stopForToday()
                    break
                }
            }
        } finally {
            dial.addKidsPlayTime(profile.id, SystemClock.elapsedRealtime() - lastTick)
        }
    }

    var startupHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(needsPicker) {
        // Nothing starts playing behind the profile picker.
        if (startupHandled || needsPicker) return@LaunchedEffect
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
        if (playingId != null && live && playingPlaylist != null && !onDemandSource) dial.rememberLastChannel(playingId)
        if (live || catchUp || audioSource || currentChannel?.playlistUrl == YouTubeViewModel.PLAYLIST_URL ||
            currentChannel?.playlistUrl == FilesViewModel.PLAYLIST_URL
        ) dial.clearNowPlaying()
    }

    // Menus at the fastest refresh rate the Fire TV offers at this resolution.
    LaunchedEffect(preferences.fastMenus, surface) {
        if (surface == TvSurface.Browse || surface == TvSurface.Mini) {
            view.context.findActivity()?.let { DisplayModes.applyMenuMode(it, preferences.fastMenus) }
        }
    }

    // Watch party: what the host is playing, for guests; and a guest's player coming to the front.
    val partyHosting by party.hosting.collectAsStateWithLifecycle()
    val partyGuest = partyGuestState
    val skipMarkers by dial.skipMarkers.collectAsStateWithLifecycle()
    val bookmarks by dial.bookmarks.collectAsStateWithLifecycle()
    val frameRateVersion by dial.frameRateVersion.collectAsStateWithLifecycle()
    val frameRateMode = remember(playingId, frameRateVersion) { playingId?.let(dial::frameRateMode) ?: FrameRateMode.Default }
    LaunchedEffect(playingId, live) { dial.loadBookmarks(playingId.takeIf { !live }) }
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
                is PartyEvent.JoinedStream -> {
                    // The party picked the stream, not a details page: nothing of a previous film
                    // or series (its markers, its "up next") applies to it.
                    dial.clearNowPlaying()
                    if (!event.ownStream) Toast.makeText(context, partyHostStream, Toast.LENGTH_LONG).show()
                }
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

    // Search beyond the playlists. YouTube results from search play through the YouTube tab's
    // player; anything that player can't take goes to the YouTube app.
    val universalResults by universal.results.collectAsStateWithLifecycle()
    val noYouTubeApp = stringResource(R.string.dial_search_no_youtube)
    val openInYouTubeApp: (String) -> Unit = { url ->
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, noYouTubeApp, Toast.LENGTH_LONG).show()
        }
    }
    val openVideo: (VideoResult) -> Unit = { video ->
        dial.clearNowPlaying()
        youtube.play(
            YouTubeVideo(id = video.id, title = video.title, channel = video.channel, thumbnail = video.thumbnail),
            onPlaying = { surface = TvSurface.Player },
        )
    }
    val youTubeFallback = stringResource(R.string.dial_youtube_fallback)
    val trailerTitle = stringResource(R.string.dial_trailer_title)
    val noTrailer = stringResource(R.string.dial_trailer_none)
    var trailerJob by remember { mutableStateOf<Job?>(null) }
    val remoteLocked by RemoteLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(youtube) {
        youtube.events.collect { event ->
            when (event) {
                is YouTubeEvent.OpenExternally -> {
                    Toast.makeText(context, event.reason?.let { "$youTubeFallback · $it" } ?: youTubeFallback, Toast.LENGTH_LONG).show()
                    openInYouTubeApp(event.video.url)
                }
                is YouTubeEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val filesError = stringResource(R.string.dial_files_error)
    LaunchedEffect(files) {
        files.failures.collect { reason -> Toast.makeText(context, filesError.format(reason), Toast.LENGTH_LONG).show() }
    }
    // A YouTube stream the player couldn't play (YouTube changed something): the YouTube app, once.
    val sponsorSegments by youtube.segments.collectAsStateWithLifecycle()
    LaunchedEffect(playingId) {
        youtube.onPlayingChannel(playingId)
        radio.onPlayingChannel(playingId)
    }
    var youTubeFallbackFor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(playbackFailed, playingId) {
        if (playingId != null && playingId != youTubeFallbackFor) youTubeFallbackFor = null
        if (!playbackFailed || playingId == null || youTubeFallbackFor == playingId) return@LaunchedEffect
        val video = youtube.playing?.takeIf { currentChannel?.playlistUrl == YouTubeViewModel.PLAYLIST_URL } ?: return@LaunchedEffect
        youTubeFallbackFor = playingId
        closePlayer()
        Toast.makeText(context, youTubeFallback, Toast.LENGTH_SHORT).show()
        openInYouTubeApp(video.url)
    }

    // A Jellyfin/Emby item: the server hears where playback is, so its own apps resume there too.
    val serverChannelId = playingId.takeIf {
        (surface == TvSurface.Player || surface == TvSurface.Mini) &&
            currentChannel?.playlistUrl == MediaServerViewModel.PLAYLIST_URL
    }
    DisposableEffect(serverChannelId) {
        onDispose { if (serverChannelId != null) server.reportProgress(serverChannelId, 0L, paused = true, stopped = true) }
    }
    LaunchedEffect(serverChannelId, isPlaying) {
        val id = serverChannelId ?: return@LaunchedEffect
        while (true) {
            server.reportProgress(id, player?.currentPosition ?: 0L, paused = !isPlaying, stopped = false)
            delay(SERVER_PROGRESS_MS)
        }
    }

    // Up next: when an episode finishes, the following one starts after a short countdown.
    val upNext = if (
        surface == TvSurface.Player &&
        playbackState == Player.STATE_ENDED &&
        (preferences.autoplayNextEpisode || preferences.bingeMode)
    ) {
        remember(playingId, nowPlaying) { dial.nextEpisode() }
    } else null
    // The subtitle on screen, for the phone page's big-text view.
    DisposableEffect(player) {
        val target = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                CaptionFeed.update(cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            }
        }
        target.addListener(listener)
        onDispose {
            target.removeListener(listener)
            CaptionFeed.update("")
        }
    }
    // Night mode and the dialogue boost, on the player's sound wherever it shows (full screen,
    // the corner, behind a details page).
    val enhancer = remember(player) { (player as? ExoPlayer)?.let { AudioEnhancer(it) } }
    DisposableEffect(enhancer) { onDispose { enhancer?.release() } }
    LaunchedEffect(preferences.swapControllerAB) { Gamepad.swapAB = preferences.swapControllerAB }
    val audioOutput by outputs.current.collectAsStateWithLifecycle()
    val outputProfile by outputs.profile.collectAsStateWithLifecycle()
    val audioShape = AudioShape(
        night = preferences.nightMode,
        dialogue = preferences.dialogueBoost,
        eq = outputProfile.eq,
        boostDb = outputProfile.boostDb,
        surround = outputProfile.surround && audioOutput.kind.personal,
    )
    LaunchedEffect(enhancer, audioShape) { enhancer?.set(audioShape) }
    // Sound moved to other headphones or speakers: say where, and that its own settings came back.
    val outputMessage = stringResource(R.string.dial_output_now)
    val outputNames = outputKindNames()
    LaunchedEffect(outputs) {
        outputs.changes.collect { output ->
            val label = output.name.ifBlank { outputNames.getValue(output.kind) }
            Toast.makeText(context, outputMessage.format(label), Toast.LENGTH_SHORT).show()
        }
    }
    // The lights: dimmed while video plays full screen, back up a few seconds after it stops.
    val videoPlaying = surface == TvSurface.Player && isPlaying && !audioSource
    LaunchedEffect(videoPlaying) {
        delay(if (videoPlaying) LIGHTS_DIM_DELAY_MS else LIGHTS_UP_DELAY_MS)
        smartHome.onPlayback(videoPlaying)
    }
    // Binge mode: the next episode starts straight away, no countdown card.
    LaunchedEffect(upNext, preferences.bingeMode) {
        val (series, episode) = upNext ?: return@LaunchedEffect
        if (preferences.bingeMode) dial.playEpisode(series, episode, fromStart = true)
    }
    // An episode that ended, or got within the last tenth, counts as watched.
    LaunchedEffect(playbackState == Player.STATE_ENDED, nowPlaying?.episode?.id) {
        if (playbackState == Player.STATE_ENDED) dial.markPlayingWatched()
    }
    LaunchedEffect(nowPlaying?.episode?.id, isPlaying) {
        if (nowPlaying?.episode == null || !isPlaying) return@LaunchedEffect
        while (true) {
            delay(WATCHED_CHECK_MS)
            val current = player ?: break
            if (current.duration > 0 && current.currentPosition >= current.duration * 9 / 10) {
                dial.markPlayingWatched()
                break
            }
        }
    }
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
    val traktRows by metadata.traktRows.collectAsStateWithLifecycle()
    val traktAccount by metadata.traktAccount.collectAsStateWithLifecycle()
    val missed by dial.missed.collectAsStateWithLifecycle()
    val tonight by dial.tonight.collectAsStateWithLifecycle()
    val reminders by dial.reminders.collectAsStateWithLifecycle()
    val reminderKeys = remember(reminders) { reminders.map { it.key }.toSet() }
    val reminderSet = stringResource(R.string.dial_reminder_set)
    val reminderCleared = stringResource(R.string.dial_reminder_cleared)
    val reminderGone = stringResource(R.string.dial_reminder_gone)
    val onOpenTonight: (TonightProgramme) -> Unit = { item ->
        if (item.programme.startMillis <= System.currentTimeMillis()) {
            openOrPlay(item.channel)
        } else {
            val set = dial.toggleReminder(item.channel, item.programme)
            Toast.makeText(context, if (set) reminderSet.format(item.programme.title) else reminderCleared, Toast.LENGTH_SHORT).show()
        }
    }
    // When a reminded programme is about to start, a card offers to switch over.
    var dueReminder by remember { mutableStateOf<Reminder?>(null) }
    val shownReminders = remember { mutableSetOf<String>() }
    val reminderBlocked by rememberUpdatedState(showSplash || overlayUp)
    LaunchedEffect(reminders) {
        while (true) {
            val now = System.currentTimeMillis()
            dial.purgeReminders()
            val due = reminders.firstOrNull { it.key !in shownReminders && it.startMs - now <= REMINDER_LEAD_MS && it.endMs > now }
            if (due != null && dueReminder == null && !reminderBlocked) {
                shownReminders += due.key
                dueReminder = due
                if (preferences.controllerRumble) Gamepad.rumbleAll(REMINDER_RUMBLE_MS)
            }
            delay(REMINDER_CHECK_MS)
        }
    }
    val becauseYouWatched by metadata.becauseYouWatched.collectAsStateWithLifecycle()
    val newEpisodes by discover.newEpisodes.collectAsStateWithLifecycle()
    val anime by discover.anime.collectAsStateWithLifecycle()
    // New episodes for the series in favourites and "continue watching"; anime for everyone
    // except kids profiles (AniList's chart isn't sorted for age).
    LaunchedEffect(destination, state.favorites.size, continueWatching.size, kidsProfile, state.playlists.size) {
        if (destination != TvDestination.Home) return@LaunchedEffect
        val seriesUrls = state.playlists.filter { it.isSeries }.map { it.url }.toSet()
        discover.loadNewEpisodes((continueWatching + state.favorites).filter { it.playlistUrl in seriesUrls })
        if (!kidsProfile && HomeRow.Anime !in preferences.homeRowsHidden) discover.loadAnime()
    }
    LaunchedEffect(destination, continueWatching.firstOrNull()?.id) {
        if (destination != TvDestination.Home) return@LaunchedEffect
        val last = continueWatching.firstOrNull()
        val playlist = last?.let { channel -> state.playlists.firstOrNull { it.url == channel.playlistUrl } }
        metadata.loadBecauseYouWatched(last, isSeries = playlist?.isSeries == true)
    }
    LaunchedEffect(destination, traktAccount, state.favorites.size) {
        if (destination == TvDestination.Home) {
            metadata.loadTrending()
            metadata.loadTraktRows()
            dial.loadMissed(
                state.favorites.filter { channel ->
                    val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
                    playlist == null || !(playlist.isVod || playlist.isSeries)
                }
            )
        }
    }
    // Scrobbling: Trakt hears when a film or episode starts, pauses and stops, and how far in.
    val scrobbler = metadata.scrobbler
    DisposableEffect(scrobbler) {
        onDispose { scrobbler.stop() }
    }
    val scrobbleItem = if (surface == TvSurface.Player || surface == TvSurface.Mini) nowPlaying?.traktItem else null
    LaunchedEffect(scrobbleItem, isPlaying, playbackState == Player.STATE_ENDED) {
        val current = player
        val ended = playbackState == Player.STATE_ENDED
        if (ended && scrobbleItem != null) {
            scrobbler.finish(scrobbleItem)
            return@LaunchedEffect
        }
        scrobbler.update(
            item = scrobbleItem,
            positionMs = current?.currentPosition ?: 0L,
            durationMs = current?.duration ?: 0L,
            isPlaying = isPlaying,
        )
        // While playing, keep the progress fresh so a close reports where it got to.
        while (scrobbleItem != null && isPlaying && !ended) {
            delay(SCROBBLE_TICK_MS)
            val playing = player ?: break
            scrobbler.update(scrobbleItem, playing.currentPosition, playing.duration, isPlaying = true)
        }
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
    val restoredFromPhone = stringResource(R.string.dial_backup_restored_phone)
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
                is PhoneMessage.Restored ->
                    Toast.makeText(context, restoredFromPhone.format(message.settings, message.favourites), Toast.LENGTH_LONG).show()
                is PhoneMessage.Key -> {
                    // The phone page as a remote: the key lands exactly as one from the real remote
                    // (through the activity, so Back reaches the screens' own Back handling).
                    val target = view.context.findActivity()
                    if (target != null) {
                        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, message.code))
                        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, message.code))
                    } else {
                        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, message.code))
                        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, message.code))
                    }
                }
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
    val lastKeyAt = remember { longArrayOf(SystemClock.uptimeMillis()) }
    // The ambient screensaver, after the menus sit untouched for the time set in Settings.
    var screensaverOn by remember { mutableStateOf(false) }
    var quickSettingsOpen by remember { mutableStateOf(false) }
    var menuHeld by remember { mutableStateOf(false) }
    var playerMenuPresses by remember { mutableIntStateOf(0) }
    var playerRefocus by remember { mutableIntStateOf(0) }
    val screensaverMs by rememberUpdatedState(preferences.screensaverMinutes * 60_000L)
    val screensaverAllowed by rememberUpdatedState(
        surface == TvSurface.Browse && details == null && person == null && !showSplash && !overlayUp
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(REMOTE_IDLE_CHECK_MS)
            val idle = SystemClock.uptimeMillis() - lastKeyAt[0]
            if (remoteBusy.value && idle >= REMOTE_IDLE_AFTER_MS) {
                remoteBusy.value = false
            }
            if (screensaverMs > 0L && screensaverAllowed && !screensaverOn && idle >= screensaverMs) screensaverOn = true
        }
    }
    LaunchedEffect(screensaverAllowed) { if (!screensaverAllowed) screensaverOn = false }

    // Live-score badges on channel cards, while a live list is on screen, plus the count of
    // merged copies behind a card.
    val liveMatches by liveBadges.matches.collectAsStateWithLifecycle()
    val liveListShowing = onBrowse && (destination == TvDestination.Live || destination == TvDestination.Favorites || destination == TvDestination.Home)
    LaunchedEffect(liveListShowing, preferences.liveBadges) { liveBadges.watch(liveListShowing && preferences.liveBadges) }
    val channelBadges = remember(liveMatches, state.channels, state.favorites, state.variants, preferences.liveBadges) {
        val scores = if (preferences.liveBadges) liveBadges.badgesFor(state.channels + state.favorites) else emptyMap()
        val ids = scores.keys + state.variants.keys
        ids.associateWith { id -> ChannelBadge(live = scores[id], variants = state.variants[id]?.size ?: 0) }
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
                // Any key wakes the screensaver, and goes no further.
                if (screensaverOn) {
                    if (event.type == KeyEventType.KeyUp) screensaverOn = false
                    return@onPreviewKeyEvent true
                }
                val repeat = event.nativeKeyEvent.repeatCount
                // Menu: a press acts when it's let go (the side menu, the mini player back to full
                // screen, the player's options), so that holding it can mean quick settings instead.
                if (event.key == Key.Menu && !showSplash && !overlayUp) {
                    when {
                        event.type == KeyEventType.KeyDown && repeat == 0 -> menuHeld = false
                        event.type == KeyEventType.KeyDown -> if (repeat >= LONG_PRESS_REPEAT && !menuHeld) {
                            menuHeld = true
                            quickSettingsOpen = true
                        }
                        event.type == KeyEventType.KeyUp -> if (menuHeld) {
                            menuHeld = false
                        } else when {
                            quickSettingsOpen -> {
                                quickSettingsOpen = false
                                if (surface == TvSurface.Player) playerRefocus++
                            }
                            surface == TvSurface.Mini -> surface = TvSurface.Player
                            surface == TvSurface.Player -> playerMenuPresses++
                            surface == TvSurface.Browse && details == null && menuChannel == null && menuCategory == null ->
                                runCatching { menuFocus.requestFocus() }
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                // Holding Back: out of the player completely (not to the corner), off a details
                // page, or back to Home. The release is swallowed by MainActivity.
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown && repeat == LONG_PRESS_REPEAT &&
                    !showSplash && !overlayUp && !quickSettingsOpen
                ) {
                    when {
                        surface == TvSurface.Player || surface == TvSurface.Mini -> closePlayer()
                        person != null -> metadata.closePerson()
                        details != null -> dial.closeDetails()
                        else -> destination = TvDestination.Home
                    }
                    return@onPreviewKeyEvent true
                }
                // Mini player: play/pause works from anywhere.
                if (surface != TvSurface.Mini || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
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
        val browsing = onBrowse && details == null && !showSplash && !overlayUp && !quickSettingsOpen
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
            LocalChannelBadges provides channelBadges,
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
                    onSearch = { query ->
                        viewModel.search(query)
                        universal.search(query)
                    },
                    universal = universalResults,
                    remindedKeys = reminderKeys,
                    onAiring = { airing ->
                        if (airing.isOn(System.currentTimeMillis())) {
                            openOrPlay(airing.channel)
                        } else {
                            val programme = GuideProgramme(
                                title = airing.title,
                                description = "",
                                startMillis = airing.startMs,
                                endMillis = airing.endMs,
                                serverStart = null,
                                hasArchive = false,
                            )
                            val set = dial.toggleReminder(airing.channel, programme)
                            Toast.makeText(context, if (set) reminderSet.format(airing.title) else reminderCleared, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenVideo = { video -> openVideo(video) },
                    onPlayServer = { item ->
                        if (item.isSeries) {
                            server.openSeries(item)
                            destination = TvDestination.Server
                        } else {
                            server.play(item) { surface = TvSurface.Player }
                        }
                    },
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
                    traktRows = traktRows,
                    onOpenTitle = { title -> openTmdbTitle(title, null) },
                    kidsProfile = kidsProfile,
                    onSwitchProfile = {
                        viewModel.releasePlayer()
                        surface = TvSurface.Browse
                        destination = TvDestination.Home
                        profilesVm.lock()
                    },
                    tonight = tonight,
                    reminderKeys = reminderKeys,
                    onOpenTonight = onOpenTonight,
                    homeRows = preferences.homeRows,
                    hiddenRows = preferences.homeRowsHidden,
                    becauseYouWatched = becauseYouWatched,
                    newEpisodes = newEpisodes,
                    anime = if (kidsProfile) emptyList() else anime,
                    missed = missed,
                    onOpenMissed = { item ->
                        if (dial.playsExternally(item.channel)) {
                            dial.playCatchUp(item.channel, item.programme, external = true)
                        } else {
                            dial.playCatchUp(item.channel, item.programme)
                            surface = TvSurface.Player
                        }
                    },
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
                    destinations = destinations,
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
                onTraktRate = metadata::rate,
                onTraktComment = metadata::postComment,
                onTraktWatched = metadata::markWatched,
                onTrailer = { key ->
                    val name = trailerTitle.format(current.channel.title)
                    if (trailerJob?.isActive != true) trailerJob = scope.launch {
                        // Apple's preview plays straight away; YouTube's needs the extractor.
                        val preview = if (current.kind == DetailsKind.Film) {
                            ItunesTrailers.find(OpenSubtitles.cleanTitle(current.channel.title), OpenSubtitles.yearIn(current.channel.title))
                        } else null
                        // A trailer isn't the film: nothing of a previous episode (markers, up next,
                        // watched marks, scrobbling) may apply to it.
                        when {
                            preview != null -> {
                                dial.clearNowPlaying()
                                youtube.playDirect(preview, name, current.channel.cover) { surface = TvSurface.Player }
                            }
                            key != null -> {
                                dial.clearNowPlaying()
                                youtube.play(
                                    YouTubeVideo(id = key, title = name, channel = null, thumbnail = null),
                                    onPlaying = { surface = TvSurface.Player },
                                )
                            }
                            else -> Toast.makeText(context, noTrailer, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                hideWatched = preferences.hideWatched,
                onToggleHideWatched = { dial.updatePreferences { it.copy(hideWatched = !it.hideWatched) } },
                onSetWatched = { ids, watched -> dial.setWatched(current.channel.id, ids, watched) },
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
                    frameRate = frameRateVersion.let { dial.frameRateMode(channel.id) },
                    cycleFrameRate = { dial.cycleFrameRateMode(channel.id) },
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
                zapChannels = zapChannels,
                zapCurrentId = shownId,
                nowTitles = nowTitles,
                onOpenChannelList = { viewModel.loadNowTitles(zapChannels) },
                onZapTo = { picked -> viewModel.play(picked) },
                onToggleFavourite = { currentChannel?.let(viewModel::toggleFavorite) },
                onBack = if (preferences.backToMini) minimizePlayer else closePlayer,
                onClose = closePlayer,
                onMinimize = minimizePlayer,
                onMultiview = { currentChannel?.let(openMultiview) },
                skipMarkers = skipMarkers,
                onUpdateSkipMarkers = if (nowPlaying?.series != null) dial::updateSkipMarkers else null,
                onNextEpisode = remember(nowPlaying) { dial.nextEpisode() }?.let { (series, episode) ->
                    { dial.playEpisode(series, episode, fromStart = true) }
                },
                partyHosting = partyHosting,
                partyGuest = partyGuest,
                onStartParty = startParty,
                onStopParty = party::stopHosting,
                onLeaveParty = party::leave,
                bookmarks = bookmarks,
                onAddBookmark = if (!live && playingId != null) dial::addBookmark else null,
                onClearBookmarks = dial::clearBookmarks,
                frameRateMode = frameRateMode,
                skipSegments = sponsorSegments,
                artwork = if (audioSource) (radio.playingArtwork ?: currentChannel?.cover) else null,
                onResumeLiveFrom = currentChannel?.takeIf { live && dial.mayHaveCatchUp(it) }?.let { channel ->
                    { pausedAt -> dial.resumeLiveFrom(channel, pausedAt) }
                },
                onBackToLive = if (catchUp && playingId != null) {
                    { scope.launch { dial.channelById(playingId)?.let(viewModel::play) } }
                } else null,
                variants = playingId?.let { id -> state.variants.values.firstOrNull { copies -> copies.any { it.id == id } } }.orEmpty(),
                menuPresses = playerMenuPresses,
                refocus = playerRefocus,
                onPlayVariant = { copy -> viewModel.play(copy) },
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

        upNext?.takeIf { !preferences.bingeMode }?.let { (series, episode) ->
            UpNextCard(
                episode = episode,
                onPlay = { dial.playEpisode(series, episode, fromStart = true) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 48.dp),
            )
        }

        // Top right of Home: notices, then the clock and the weather.
        val weatherNow by weather.now.collectAsStateWithLifecycle()
        val weatherOn by weather.enabled.collectAsStateWithLifecycle()
        val fahrenheit by weather.fahrenheit.collectAsStateWithLifecycle()
        val updateBuild by notices.updateBuild.collectAsStateWithLifecycle()
        if (onBrowse && destination == TvDestination.Home && details == null && person == null && !showSplash && !needsPicker) {
            val noticeList = listOfNotNull(
                updateBuild?.let { Notice.Update(it) },
                traktRows.firstOrNull { it.kind == TraktRowKind.UpNext }?.titles?.size?.takeIf { it > 0 }?.let { Notice.TraktUpNext(it) },
                missed.size.takeIf { it > 0 }?.let { Notice.Missed(it) },
                partyHosting?.let { Notice.Party(it.code) },
                reminders.firstOrNull { it.startMs - System.currentTimeMillis() < NOTICE_REMINDER_WINDOW_MS }?.let {
                    Notice.Reminder(it.title, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.startMs)))
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 32.dp),
            ) {
                NoticeStrip(noticeList)
                if (weatherOn) ClockWeatherStrip(now = weatherNow, fahrenheit = fahrenheit)
            }
        }
        dueReminder?.let { reminder ->
            ReminderCard(
                reminder = reminder,
                onWatch = {
                    dueReminder = null
                    dial.dismissReminder(reminder)
                    scope.launch {
                        val channel = dial.channelById(reminder.channelId)
                        if (channel != null) openOrPlay(channel) else Toast.makeText(context, reminderGone, Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = {
                    dueReminder = null
                    dial.dismissReminder(reminder)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 48.dp),
            )
        }
        if (timeUp) {
            TimeUpCard(
                onSwitchProfile = {
                    timeUp = false
                    profilesVm.lock()
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (needsPicker && !showSplash) {
            ProfilePickerScreen(
                profiles = profileList,
                lastUsedId = profilesVm.lastUsedId,
                onPick = { profile, pin -> profilesVm.select(profile, pin) },
                modifier = Modifier.focusGroup(),
            )
        }
        if (showSplash) {
            BrandSplash(onFinished = { splashDone = true })
        }
        if (quickSettingsOpen) {
            QuickSettingsPanel(
                preferences = preferences,
                onUpdate = dial::updatePreferences,
                onClose = {
                    quickSettingsOpen = false
                    if (surface == TvSurface.Player) playerRefocus++
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        // Remote lock on: a small badge, so a parent knows why nothing answers.
        if (remoteLocked) {
            RemoteLockBadge(modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp))
        }
        if (screensaverOn) {
            val slides = remember(trending, state.recentlyPlayed) {
                trending.mapNotNull { entry ->
                    entry.title.backdrop?.let { AmbientSlide(it, entry.title.title, entry.title.overview?.take(90)) }
                }.ifEmpty {
                    state.recentlyPlayed.mapNotNull { channel -> channel.cover?.let { AmbientSlide(it, channel.title, channel.category) } }
                }
            }
            AmbientScreensaver(slides = slides)
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
