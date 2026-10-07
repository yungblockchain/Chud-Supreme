package com.m3u.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.m3u.data.database.model.Channel
import com.m3u.data.parser.xtream.XtreamEpisodeInfo
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/* -------------------------------------------------------------------------------------------------
 * Watch party: two Fire TVs on the same home network play the same thing in step.
 *
 * The host starts a party and gets a six-letter code. The code is not looked up anywhere: it
 * packs the last two numbers of the host's network address, which of the phone-page ports it
 * is on, and a secret. A guest on the same network types the code, works out the host's
 * address from it, and asks the host every couple of seconds what it's playing and where.
 * Play, pause and seeks follow the host; the guest's clock never comes into it (the trip
 * time of each request does), so two boxes with clocks seconds apart still line up.
 * A small gap is closed by playing a touch faster or slower for a few seconds; only a big
 * one seeks, so the guest doesn't rebuffer every time the network hiccups.
 *
 * Nothing leaves the home network and there is no server in the middle.
 * ---------------------------------------------------------------------------------------------- */

/** Six letters, no look-alikes (no 0/O, 1/I). Each letter carries five bits. */
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val CODE_LENGTH = 6

object PartyCodes {
    /**
     * Thirty bits: sixteen for the host's last two address numbers, two for the port slot, twelve
     * for the secret the host checks.
     */
    fun encode(addressTail: Int, portSlot: Int, secret: Int): String {
        var bits = ((addressTail and 0xFFFF).toLong() shl 14) or
            ((portSlot and 0x3).toLong() shl 12) or
            (secret and 0xFFF).toLong()
        val letters = CharArray(CODE_LENGTH)
        for (index in CODE_LENGTH - 1 downTo 0) {
            letters[index] = CODE_ALPHABET[(bits and 0x1F).toInt()]
            bits = bits shr 5
        }
        return String(letters)
    }

    fun decode(code: String): PartyCode? {
        val clean = code.trim().uppercase().replace('0', 'O').replace('1', 'I')
        if (clean.length != CODE_LENGTH) return null
        var bits = 0L
        for (letter in clean) {
            val value = CODE_ALPHABET.indexOf(letter)
            if (value < 0) return null
            bits = (bits shl 5) or value.toLong()
        }
        return PartyCode(
            addressTail = ((bits shr 14) and 0xFFFF).toInt(),
            portSlot = ((bits shr 12) and 0x3).toInt(),
            secret = (bits and 0xFFF).toInt(),
            text = clean,
        )
    }
}

@Immutable
data class PartyCode(val addressTail: Int, val portSlot: Int, val secret: Int, val text: String)

/** What the host is playing, as the guest sees it. */
@Immutable
data class PartyProgramme(
    val title: String,
    val url: String,
    val live: Boolean,
    val seriesTitle: String? = null,
    val episode: XtreamEpisodeInfo? = null,
    val season: String? = null,
) {
    /** Same thing playing? */
    val key: String get() = url
}

@Immutable
data class HostedParty(val code: String, val guests: Int, val startedAt: Long)

sealed interface GuestState {
    data object Idle : GuestState
    data class Joining(val code: String) : GuestState
    data class InParty(
        val code: String,
        val title: String,
        val synced: Boolean,
        val ownStream: Boolean,
        /** Whether the host is on live TV (the guest's player follows this, not its own playlist). */
        val live: Boolean,
    ) : GuestState
    data class Failed(val code: String, val reason: PartyFailure) : GuestState
}

enum class PartyFailure { BadCode, NoNetwork, HostNotFound, Rejected, NothingPlaying }

/** The guest has started something and the player should come to the front. */
sealed interface PartyEvent {
    data object ShowPlayer : PartyEvent
    data class JoinedStream(val ownStream: Boolean) : PartyEvent
}

@Singleton
class WatchParty @Inject constructor(
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val companion: PhoneCompanion,
) : PartyEndpoint {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    private val _hosting = MutableStateFlow<HostedParty?>(null)
    val hosting: StateFlow<HostedParty?> = _hosting.asStateFlow()

    private val _guest = MutableStateFlow<GuestState>(GuestState.Idle)
    val guest: StateFlow<GuestState> = _guest.asStateFlow()

    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PartyEvent> = _events.asSharedFlow()

    /** What the host is playing, kept current by the app (title and episode aren't in the player). */
    @Volatile var hostProgramme: PartyProgramme? = null

    @Volatile private var secret = -1
    private val guestSeen = HashMap<String, Long>()
    private var guestJob: Job? = null

    /** The viewer's own speed while a catch-up is running, and the speed the party set. */
    private var nudgeBase: Float? = null
    private var nudgeSpeed = 1f

    init {
        companion.party = this
    }

    /* ---------------------------------------------------------------------------- hosting */

    /** Starts hosting; null when the Fire TV has no home-network address or the page won't start. */
    fun startHosting(): String? {
        _hosting.value?.let { return it.code }
        if (!companion.running) companion.start()
        val port = companion.port ?: return null
        val slot = PhoneCompanion.PORTS.indexOf(port).takeIf { it in 0..2 } ?: return null
        val address = localAddress() ?: return null
        val tail = (address.address[2].toInt() and 0xFF shl 8) or (address.address[3].toInt() and 0xFF)
        secret = random.nextInt(0x1000)
        val code = PartyCodes.encode(tail, slot, secret)
        guestSeen.clear()
        _hosting.value = HostedParty(code = code, guests = 0, startedAt = SystemClock.uptimeMillis())
        return code
    }

    fun stopHosting() {
        secret = -1
        _hosting.value = null
    }

    /** Called on the phone-page worker thread; answers from the main thread's view of the player. */
    override fun state(code: String, guestId: String): PartyAnswer {
        val decoded = PartyCodes.decode(code) ?: return PartyAnswer.NoParty
        if (secret < 0 || decoded.secret != secret) return PartyAnswer.NoParty
        val latch = CountDownLatch(1)
        var json: String? = null
        main.post {
            json = runCatching { snapshot() }.getOrNull()
            latch.countDown()
        }
        latch.await(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS)
        if (guestId.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            synchronized(guestSeen) {
                guestSeen[guestId] = now
                guestSeen.entries.removeAll { now - it.value > GUEST_GONE_MS }
                val count = guestSeen.size
                _hosting.value?.let { if (it.guests != count) _hosting.value = it.copy(guests = count) }
            }
        }
        return json?.let { PartyAnswer.State(it) } ?: PartyAnswer.Busy
    }

    override fun onServerStopped() {
        stopHosting()
    }

    private fun snapshot(): String {
        val player = playerManager.player.value
        val programme = hostProgramme
        val fields = mutableMapOf<String, JsonPrimitive>(
            "ok" to JsonPrimitive(true),
            "playing" to JsonPrimitive(player?.isPlaying == true || player?.playWhenReady == true),
            "position" to JsonPrimitive(player?.currentPosition ?: 0L),
            "duration" to JsonPrimitive(player?.duration?.coerceAtLeast(0L) ?: 0L),
        )
        if (programme != null) {
            fields["title"] = JsonPrimitive(programme.title)
            fields["url"] = JsonPrimitive(programme.url)
            fields["live"] = JsonPrimitive(programme.live)
            programme.seriesTitle?.let { fields["series"] = JsonPrimitive(it) }
            programme.season?.let { fields["season"] = JsonPrimitive(it) }
            programme.episode?.let { episode ->
                episode.id?.let { fields["episodeId"] = JsonPrimitive(it) }
                episode.episodeNum?.let { fields["episodeNum"] = JsonPrimitive(it) }
                episode.title?.let { fields["episodeTitle"] = JsonPrimitive(it) }
                episode.containerExtension?.let { fields["ext"] = JsonPrimitive(it) }
            }
        }
        return JsonObject(fields).toString()
    }

    /* ------------------------------------------------------------------------------ guest */

    fun join(code: String) {
        val decoded = PartyCodes.decode(code)
        if (decoded == null) {
            _guest.value = GuestState.Failed(code.trim().uppercase(), PartyFailure.BadCode)
            return
        }
        val own = localAddress()
        if (own == null) {
            _guest.value = GuestState.Failed(decoded.text, PartyFailure.NoNetwork)
            return
        }
        val hostAddress = "${own.address[0].toInt() and 0xFF}.${own.address[1].toInt() and 0xFF}." +
            "${decoded.addressTail shr 8}.${decoded.addressTail and 0xFF}"
        val endpoint = "http://$hostAddress:${PhoneCompanion.PORTS[decoded.portSlot]}/party?code=${decoded.text}&guest=$guestId"
        guestJob?.cancel()
        _guest.value = GuestState.Joining(decoded.text)
        guestJob = scope.launch { follow(decoded.text, endpoint) }
    }

    fun leave() {
        guestJob?.cancel()
        guestJob = null
        endNudge()
        _guest.value = GuestState.Idle
    }

    private suspend fun follow(code: String, endpoint: String) {
        try {
            followLoop(code, endpoint)
        } finally {
            endNudge()
        }
    }

    private suspend fun followLoop(code: String, endpoint: String) {
        var currentKey: String? = null
        var ownStream = false
        var expectedUri: String? = null
        var misses = 0
        var smoothedTripMs = -1L
        while (true) {
            val sentAt = SystemClock.uptimeMillis()
            val reply = withContext(Dispatchers.IO) { fetch(endpoint) }
            val receivedAt = SystemClock.uptimeMillis()
            when (reply) {
                is Reply.Gone -> {
                    _guest.value = GuestState.Failed(code, PartyFailure.Rejected)
                    return
                }
                is Reply.Unreachable -> {
                    misses++
                    if (_guest.value is GuestState.Joining || misses >= MAX_MISSES) {
                        _guest.value = GuestState.Failed(code, PartyFailure.HostNotFound)
                        return
                    }
                    (_guest.value as? GuestState.InParty)?.let { _guest.value = it.copy(synced = false) }
                    delay(POLL_MS)
                    continue
                }
                is Reply.State -> Unit
            }
            misses = 0
            val state = reply.state
            val programme = state.programme
            if (programme == null) {
                if (_guest.value is GuestState.Joining) {
                    _guest.value = GuestState.Failed(code, PartyFailure.NothingPlaying)
                    return
                }
                delay(POLL_MS)
                continue
            }
            if (programme.key != currentKey) {
                endNudge()
                ownStream = startPlaying(programme)
                currentKey = programme.key
                _events.tryEmit(PartyEvent.JoinedStream(ownStream))
                _events.tryEmit(PartyEvent.ShowPlayer)
                // Give the stream a moment to open before judging the position.
                delay(START_GRACE_MS)
                expectedUri = playerManager.player.value?.currentMediaItem?.localConfiguration?.uri?.toString()
            }
            // Only steer the party's own stream: if the viewer opened something else, leave it be.
            val playingUri = playerManager.player.value?.currentMediaItem?.localConfiguration?.uri?.toString()
            val onPartyStream = expectedUri != null && playingUri == expectedUri
            if (!onPartyStream) endNudge()
            // A reply that took much longer than usual says little about when the host answered,
            // so it only updates play and pause; the next ordinary reply does the steering.
            val tripMs = receivedAt - sentAt
            val slowTrip = smoothedTripMs >= 0 && tripMs > smoothedTripMs * 2 + SLOW_TRIP_GRACE_MS
            smoothedTripMs = if (smoothedTripMs < 0) tripMs else (smoothedTripMs * 3 + tripMs) / 4
            val synced = onPartyStream && align(state, programme, tripMs, receivedAt, steer = !slowTrip)
            _guest.value = GuestState.InParty(code, programme.title, synced, ownStream, programme.live)
            delay(POLL_MS)
        }
    }

    /** Plays what the host plays: the same title from this box's own playlists, or the host's stream. */
    private suspend fun startPlaying(programme: PartyProgramme): Boolean {
        val own = runCatching { findOwn(programme) }.getOrNull()
        if (own != null) {
            runCatching { playerManager.play(own, applyContinueWatching = false) }
            return true
        }
        val anyChannel = runCatching { channelRepository.searchUnhidden("", 1).firstOrNull() }.getOrNull()
        if (anyChannel != null) {
            runCatching {
                playerManager.play(
                    MediaCommand.Url(anyChannel.id, programme.url, programme.title),
                    applyContinueWatching = false,
                )
            }
        }
        return false
    }

    private suspend fun findOwn(programme: PartyProgramme): MediaCommand? {
        val wanted = programme.seriesTitle ?: programme.title
        val matches = channelRepository.searchUnhidden(wanted, SEARCH_LIMIT)
            .filter { it.title.equals(wanted, ignoreCase = true) }
        val episode = programme.episode
        if (episode != null) {
            // An episode id only means the same thing on the same provider.
            val hostServer = runCatching { URL(programme.url).host }.getOrNull() ?: return null
            val series = matches.firstOrNull { runCatching { URL(it.url).host }.getOrNull() == hostServer }
                ?: return null
            return MediaCommand.XtreamEpisode(series.id, episode)
        }
        val channel: Channel = matches.firstOrNull { it.url == programme.url } ?: matches.firstOrNull() ?: return null
        return MediaCommand.Common(channel.id)
    }

    /**
     * Brings this box to where the host is. Live TV only follows play and pause. A big gap
     * seeks; a small one plays a little faster or slower until it closes.
     */
    private fun align(
        state: HostState,
        programme: PartyProgramme,
        tripMs: Long,
        receivedAt: Long,
        steer: Boolean,
    ): Boolean {
        val player = playerManager.player.value ?: return false
        if (state.playing != (player.playWhenReady)) playerManager.pauseOrContinue(state.playing)
        if (programme.live || state.duration <= 0L || !state.playing) {
            endNudge()
            if (programme.live || state.duration <= 0L) return true
        }
        if (!steer) return true
        // Where the host is now: its position when it answered, plus half the trip and the time
        // since the answer came in (both only while it's playing).
        val elapsed = if (state.playing) tripMs / 2 + (SystemClock.uptimeMillis() - receivedAt) else 0L
        val expected = state.position + elapsed
        val drift = player.currentPosition - expected
        val gap = abs(drift)
        if (gap > MAX_DRIFT_MS) {
            endNudge()
            player.seekTo(expected.coerceAtLeast(0L))
            return false
        }
        if (!state.playing) return true
        if (gap <= IN_STEP_MS) {
            endNudge()
            return true
        }
        // The viewer changed the speed during a catch-up: theirs wins, and becomes the base.
        if (nudgeBase != null && player.playbackParameters.speed != nudgeSpeed) nudgeBase = null
        val base = nudgeBase ?: player.playbackParameters.speed
        // Close the gap over about CATCH_UP_MS, never more than NUDGE_MAX off the viewer's speed.
        val change = (gap.toFloat() / CATCH_UP_MS).coerceIn(NUDGE_MIN, NUDGE_MAX)
        val speed = base * if (drift > 0) 1f - change else 1f + change
        nudgeBase = base
        nudgeSpeed = speed
        playerManager.updateSpeed(speed)
        return true
    }

    /** Puts the viewer's own speed back if a catch-up was running. */
    private fun endNudge() {
        val base = nudgeBase ?: return
        nudgeBase = null
        val player = playerManager.player.value ?: return
        if (player.playbackParameters.speed == nudgeSpeed) playerManager.updateSpeed(base)
    }

    private sealed interface Reply {
        data class State(val state: HostState) : Reply
        data object Gone : Reply
        data object Unreachable : Reply
    }

    private data class HostState(
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val programme: PartyProgramme?,
    )

    private fun fetch(endpoint: String): Reply = try {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            when (connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parse(body)?.let { Reply.State(it) } ?: Reply.Gone
                }
                403, 404 -> Reply.Gone
                else -> Reply.Unreachable
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        Reply.Unreachable
    }

    private fun parse(body: String): HostState? = runCatching {
        val json = Json.parseToJsonElement(body).jsonObject
        if (json["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
        fun text(name: String) = json[name]?.jsonPrimitive?.content
        val title = text("title")
        val url = text("url")
        val programme = if (title != null && url != null) {
            val episodeId = text("episodeId")
            PartyProgramme(
                title = title,
                url = url,
                live = json["live"]?.jsonPrimitive?.booleanOrNull ?: false,
                seriesTitle = text("series"),
                season = text("season"),
                episode = if (episodeId != null) XtreamEpisodeInfo(
                    containerExtension = text("ext"),
                    episodeNum = text("episodeNum"),
                    id = episodeId,
                    title = text("episodeTitle"),
                ) else null,
            )
        } else null
        HostState(
            playing = json["playing"]?.jsonPrimitive?.booleanOrNull ?: false,
            position = json["position"]?.jsonPrimitive?.longOrNull ?: 0L,
            duration = json["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
            programme = programme,
        )
    }.getOrNull()

    private val guestId: String by lazy { random.nextInt(Int.MAX_VALUE).toString(36) }

    private fun localAddress(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }.getOrNull()

    private companion object {
        const val POLL_MS = 2_000L
        const val START_GRACE_MS = 2_500L
        const val MAX_DRIFT_MS = 1_500L
        const val IN_STEP_MS = 150L
        const val CATCH_UP_MS = 10_000f
        const val NUDGE_MIN = 0.02f
        const val NUDGE_MAX = 0.08f
        const val SLOW_TRIP_GRACE_MS = 200L
        const val MAX_MISSES = 10
        const val SNAPSHOT_WAIT_MS = 1_500L
        const val GUEST_GONE_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 1_500
        const val READ_TIMEOUT_MS = 2_000
        const val SEARCH_LIMIT = 12
    }
}

/** The phone-page server's hook for the party. */
interface PartyEndpoint {
    fun state(code: String, guestId: String): PartyAnswer

    /** The page was switched off, so no party can be reached any more. */
    fun onServerStopped()
}

sealed interface PartyAnswer {
    /** No party, or the wrong code. */
    data object NoParty : PartyAnswer
    /** The host couldn't answer in time (its main thread was busy); try again. */
    data object Busy : PartyAnswer
    data class State(val json: String) : PartyAnswer
}

@HiltViewModel
class WatchPartyViewModel @Inject constructor(
    private val party: WatchParty,
) : ViewModel() {
    val hosting: StateFlow<HostedParty?> = party.hosting
    val guest: StateFlow<GuestState> = party.guest
    val events: SharedFlow<PartyEvent> = party.events

    fun startHosting(): String? = party.startHosting()
    fun stopHosting() = party.stopHosting()
    fun join(code: String) = party.join(code)
    fun leave() = party.leave()

    fun updateProgramme(programme: PartyProgramme?) {
        party.hostProgramme = programme
    }

    fun dismissFailure() {
        if (party.guest.value is GuestState.Failed) party.leave()
    }
}
