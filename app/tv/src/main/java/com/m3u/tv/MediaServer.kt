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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/* -------------------------------------------------------------------------------------------------
 * Jellyfin and Emby (they share one API): sign in with a server address, user and password, then
 * browse the libraries, continue where you left off, and play straight from the server. Played
 * items are remembered as channels under a "Media server" playlist so resume and history work
 * like everything else. Progress is reported back so the server's own apps agree.
 * ---------------------------------------------------------------------------------------------- */

enum class ServerKind { Jellyfin, Emby }

@Immutable
data class ServerSession(
    val kind: ServerKind,
    val baseUrl: String,
    val userId: String,
    val token: String,
    val username: String,
    val serverName: String?,
)

@Immutable
data class ServerItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String?,
    val year: Int?,
    val runtimeMinutes: Int?,
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    val progressPercent: Int?,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val resumeMs: Long = 0L,
) {
    val isSeries: Boolean get() = type == "Series"
    val isPlayable: Boolean get() = type == "Movie" || type == "Episode" || type == "Video"
}

@Immutable
data class ServerLibrary(val id: String, val name: String, val collectionType: String?)

@Immutable
data class ServerRow(val id: String, val name: String, val items: List<ServerItem>)

@Immutable
data class ServerSeason(val id: String, val name: String, val number: Int?)

class MediaServerException(val code: Int?, message: String? = null) : IOException(message ?: "Server $code")

@Singleton
class MediaServerStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences("media_server", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<ServerSession?> = _session.asStateFlow()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }

    fun save(session: ServerSession) {
        prefs.edit()
            .putString(KEY_KIND, session.kind.name)
            .putString(KEY_URL, session.baseUrl)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_SERVER_NAME, session.serverName)
            .apply()
        secrets.put(SecretName.MediaServerToken, session.token)
        _session.value = session
    }

    fun clear() {
        prefs.edit().remove(KEY_KIND).remove(KEY_URL).remove(KEY_USER_ID).remove(KEY_USERNAME).remove(KEY_SERVER_NAME).apply()
        secrets.remove(SecretName.MediaServerToken)
        _session.value = null
    }

    private fun read(): ServerSession? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val token = secrets.get(SecretName.MediaServerToken) ?: return null
        return ServerSession(
            kind = prefs.getString(KEY_KIND, null)?.let { name -> ServerKind.entries.firstOrNull { it.name == name } } ?: ServerKind.Jellyfin,
            baseUrl = url,
            userId = userId,
            token = token,
            username = prefs.getString(KEY_USERNAME, null).orEmpty(),
            serverName = prefs.getString(KEY_SERVER_NAME, null),
        )
    }

    private companion object {
        const val KEY_KIND = "kind"
        const val KEY_URL = "url"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_SERVER_NAME = "server_name"
        const val KEY_DEVICE = "device_id"
    }
}

object MediaServerClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val CLIENT = "Chud Supreme"
    private const val VERSION = "1.0"

    fun normaliseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    suspend fun signIn(kind: ServerKind, baseUrl: String, username: String, password: String, deviceId: String): ServerSession {
        val body = JsonObject(mapOf("Username" to JsonPrimitive(username), "Pw" to JsonPrimitive(password))).toString()
        val root = request(kind, "$baseUrl/Users/AuthenticateByName", token = null, deviceId = deviceId, body = body) as? JsonObject
            ?: throw MediaServerException(null, "no_reply")
        val token = root.text("AccessToken") ?: throw MediaServerException(401, "no_token")
        val user = root["User"] as? JsonObject
        val userId = user?.text("Id") ?: throw MediaServerException(null, "no_user")
        val serverName = (root["ServerInfo"] as? JsonObject)?.text("ServerName")
            ?: runCatching { (request(kind, "$baseUrl/System/Info/Public", null, deviceId) as? JsonObject)?.text("ServerName") }.getOrNull()
        return ServerSession(kind, baseUrl, userId, token, user.text("Name") ?: username, serverName)
    }

    suspend fun libraries(session: ServerSession, deviceId: String): List<ServerLibrary> {
        val root = get(session, deviceId, "/Users/${session.userId}/Views") as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            ServerLibrary(
                id = item.text("Id") ?: return@mapNotNull null,
                name = item.text("Name") ?: return@mapNotNull null,
                collectionType = item.text("CollectionType"),
            )
        }.filter { it.collectionType == null || it.collectionType in VIDEO_LIBRARIES }
    }

    suspend fun resume(session: ServerSession, deviceId: String): List<ServerItem> =
        items(session, deviceId, "/Users/${session.userId}/Items/Resume?Limit=20&MediaTypes=Video&$FIELDS")

    suspend fun latest(session: ServerSession, deviceId: String, libraryId: String): List<ServerItem> =
        (get(session, deviceId, "/Users/${session.userId}/Items/Latest?ParentId=$libraryId&Limit=24&$FIELDS") as? JsonArray)
            .orEmpty().mapNotNull { toItem(session, it as? JsonObject) }

    suspend fun library(session: ServerSession, deviceId: String, libraryId: String, start: Int = 0, limit: Int = 60): List<ServerItem> =
        items(
            session,
            deviceId,
            "/Users/${session.userId}/Items?ParentId=$libraryId&Recursive=true&IncludeItemTypes=Movie,Series" +
                "&SortBy=SortName&SortOrder=Ascending&StartIndex=$start&Limit=$limit&$FIELDS",
        )

    suspend fun search(session: ServerSession, deviceId: String, query: String): List<ServerItem> =
        items(
            session,
            deviceId,
            "/Users/${session.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series,Episode" +
                "&SearchTerm=${URLEncoder.encode(query, "UTF-8")}&Limit=40&$FIELDS",
        )

    suspend fun seasons(session: ServerSession, deviceId: String, seriesId: String): List<ServerSeason> {
        val root = get(session, deviceId, "/Shows/$seriesId/Seasons?UserId=${session.userId}") as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            ServerSeason(
                id = item.text("Id") ?: return@mapNotNull null,
                name = item.text("Name") ?: return@mapNotNull null,
                number = item.int("IndexNumber"),
            )
        }
    }

    suspend fun episodes(session: ServerSession, deviceId: String, seriesId: String, seasonId: String): List<ServerItem> =
        items(session, deviceId, "/Shows/$seriesId/Episodes?UserId=${session.userId}&SeasonId=$seasonId&$FIELDS")

    /** Direct play: the server hands the file over as it is; the player sorts out the rest. */
    fun streamUrl(session: ServerSession, itemId: String): String =
        "${session.baseUrl}/Videos/$itemId/stream?static=true&api_key=${session.token}"

    suspend fun reportProgress(session: ServerSession, deviceId: String, itemId: String, positionMs: Long, paused: Boolean) {
        val body = JsonObject(
            mapOf(
                "ItemId" to JsonPrimitive(itemId),
                "PositionTicks" to JsonPrimitive(positionMs * TICKS_PER_MS),
                "IsPaused" to JsonPrimitive(paused),
                "PlayMethod" to JsonPrimitive("DirectPlay"),
            )
        ).toString()
        runCatching { request(session.kind, "${session.baseUrl}/Sessions/Playing/Progress", session.token, deviceId, body) }
    }

    suspend fun reportStopped(session: ServerSession, deviceId: String, itemId: String, positionMs: Long) {
        val body = JsonObject(
            mapOf("ItemId" to JsonPrimitive(itemId), "PositionTicks" to JsonPrimitive(positionMs * TICKS_PER_MS))
        ).toString()
        runCatching { request(session.kind, "${session.baseUrl}/Sessions/Playing/Stopped", session.token, deviceId, body) }
    }

    private suspend fun items(session: ServerSession, deviceId: String, path: String): List<ServerItem> {
        val root = get(session, deviceId, path) as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray).orEmpty().mapNotNull { toItem(session, it as? JsonObject) }
    }

    private fun toItem(session: ServerSession, item: JsonObject?): ServerItem? {
        if (item == null) return null
        val id = item.text("Id") ?: return null
        val type = item.text("Type") ?: return null
        val images = item["ImageTags"] as? JsonObject
        val primaryTag = images?.text("Primary")
        val backdropTags = item["BackdropImageTags"] as? JsonArray
        val backdropTag = (backdropTags?.firstOrNull() as? JsonPrimitive)?.contentOrNull
        val parentBackdrop = item.text("ParentBackdropItemId")
        val userData = item["UserData"] as? JsonObject
        val posterId = if (type == "Episode") item.text("SeriesId") ?: id else id
        val posterTag = if (type == "Episode") item.text("SeriesPrimaryImageTag") ?: primaryTag else primaryTag
        return ServerItem(
            id = id,
            name = item.text("Name") ?: return null,
            type = type,
            overview = item.text("Overview"),
            year = item.int("ProductionYear"),
            runtimeMinutes = item.long("RunTimeTicks")?.let { (it / TICKS_PER_MS / 60_000L).toInt() }?.takeIf { it > 0 },
            rating = (item["CommunityRating"] as? JsonPrimitive)?.doubleOrNull,
            poster = if (type == "Episode" && primaryTag != null) image(session, id, "Primary", primaryTag, 480)
            else posterTag?.let { image(session, posterId, "Primary", it, 400) },
            backdrop = when {
                backdropTag != null -> image(session, id, "Backdrop", backdropTag, 1280)
                parentBackdrop != null -> image(session, parentBackdrop, "Backdrop", null, 1280)
                else -> null
            },
            progressPercent = (userData?.get("PlayedPercentage") as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it in 1..99 },
            seriesName = item.text("SeriesName"),
            season = item.int("ParentIndexNumber"),
            episode = item.int("IndexNumber"),
            resumeMs = (userData?.long("PlaybackPositionTicks") ?: 0L) / TICKS_PER_MS,
        )
    }

    private fun image(session: ServerSession, itemId: String, kind: String, tag: String?, maxWidth: Int): String =
        "${session.baseUrl}/Items/$itemId/Images/$kind?maxWidth=$maxWidth" + (tag?.let { "&tag=$it" }.orEmpty())

    private suspend fun get(session: ServerSession, deviceId: String, path: String): JsonElement? =
        request(session.kind, "${session.baseUrl}$path", session.token, deviceId)

    private suspend fun request(kind: ServerKind, url: String, token: String?, deviceId: String, body: String? = null): JsonElement? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (body != null) "POST" else "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    val auth = "MediaBrowser Client=\"$CLIENT\", Device=\"Fire TV\", DeviceId=\"$deviceId\", Version=\"$VERSION\"" +
                        (token?.let { ", Token=\"$it\"" }.orEmpty())
                    // Jellyfin reads the new header name, Emby the old; sending both suits either.
                    setRequestProperty("Authorization", auth)
                    setRequestProperty("X-Emby-Authorization", auth)
                    if (token != null) setRequestProperty("X-Emby-Token", token)
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw MediaServerException(code)
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) null else json.parseToJsonElement(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaServerException) {
                throw e
            } catch (e: Exception) {
                throw MediaServerException(null, e.message)
            } finally {
                connection?.disconnect()
            }
        }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    @Suppress("unused")
    private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

    private const val TICKS_PER_MS = 10_000L
    private const val FIELDS = "Fields=Overview,ProductionYear,CommunityRating,RunTimeTicks,ParentBackdropItemId,SeriesPrimaryImageTag"
    private val VIDEO_LIBRARIES = setOf("movies", "tvshows", "homevideos", "mixed")
}

/* ------------------------------------------------------------------------------------- state */

sealed interface ServerPage {
    data object Browse : ServerPage
    data class Library(val library: ServerLibrary) : ServerPage
    data class Series(val series: ServerItem) : ServerPage
}

@Immutable
data class MediaServerState(
    val session: ServerSession? = null,
    val signingIn: Boolean = false,
    val error: ServerError? = null,
    val loading: Boolean = false,
    val rows: List<ServerRow> = emptyList(),
    val libraries: List<ServerLibrary> = emptyList(),
    val page: ServerPage = ServerPage.Browse,
    val libraryItems: List<ServerItem> = emptyList(),
    val seasons: List<ServerSeason> = emptyList(),
    val selectedSeason: ServerSeason? = null,
    val episodes: List<ServerItem> = emptyList(),
)

enum class ServerError { Unreachable, WrongLogin, Other }

@HiltViewModel
class MediaServerViewModel @Inject constructor(
    private val store: MediaServerStore,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val playerManager: PlayerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(MediaServerState(session = store.session.value))
    val state: StateFlow<MediaServerState> = _state.asStateFlow()
    private var loadJob: Job? = null

    /** The item playing now (for progress reports), with its server. */
    @Volatile private var playing: Pair<ServerSession, ServerItem>? = null

    init {
        viewModelScope.launch {
            store.session.collect { session ->
                _state.update { it.copy(session = session, page = ServerPage.Browse) }
                if (session != null) refresh() else _state.update { it.copy(rows = emptyList(), libraries = emptyList()) }
            }
        }
    }

    fun signIn(kind: ServerKind, server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(signingIn = true, error = null) }
            val result = runCatching {
                MediaServerClient.signIn(kind, MediaServerClient.normaliseUrl(server), username.trim(), password, store.deviceId)
            }
            result.onSuccess { session ->
                store.save(session)
                _state.update { it.copy(signingIn = false) }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                val error = when ((failure as? MediaServerException)?.code) {
                    401, 403 -> ServerError.WrongLogin
                    null -> ServerError.Unreachable
                    else -> ServerError.Other
                }
                _state.update { it.copy(signingIn = false, error = error) }
            }
        }
    }

    fun signOut() {
        store.clear()
        _state.update { MediaServerState() }
    }

    fun refresh() {
        val session = store.session.value ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, error = null) }
            val device = store.deviceId
            val libraries = runCatching { MediaServerClient.libraries(session, device) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrElse {
                    _state.update { state -> state.copy(loading = false, error = ServerError.Unreachable) }
                    return@launch
                }
            _state.update { it.copy(libraries = libraries) }
            val rows = mutableListOf<ServerRow>()
            runCatching { MediaServerClient.resume(session, device) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }?.let { rows += ServerRow("resume", "", it) }
            _state.update { it.copy(rows = rows.toList()) }
            for (library in libraries) {
                val latest = runCatching { MediaServerClient.latest(session, device, library.id) }.getOrDefault(emptyList())
                if (latest.isNotEmpty()) rows += ServerRow("latest-${library.id}", library.name, latest)
                _state.update { it.copy(rows = rows.toList()) }
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun openLibrary(library: ServerLibrary) {
        val session = store.session.value ?: return
        _state.update { it.copy(page = ServerPage.Library(library), libraryItems = emptyList(), loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching { MediaServerClient.library(session, store.deviceId, library.id) }.getOrDefault(emptyList())
            _state.update { if (it.page == ServerPage.Library(library)) it.copy(libraryItems = items, loading = false) else it }
        }
    }

    fun openSeries(series: ServerItem) {
        val session = store.session.value ?: return
        _state.update { it.copy(page = ServerPage.Series(series), seasons = emptyList(), episodes = emptyList(), selectedSeason = null, loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val seasons = runCatching { MediaServerClient.seasons(session, store.deviceId, series.id) }.getOrDefault(emptyList())
            _state.update { it.copy(seasons = seasons, loading = false) }
            seasons.firstOrNull()?.let { selectSeason(it) }
        }
    }

    fun selectSeason(season: ServerSeason) {
        val session = store.session.value ?: return
        val series = (_state.value.page as? ServerPage.Series)?.series ?: return
        _state.update { it.copy(selectedSeason = season, episodes = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val episodes = runCatching { MediaServerClient.episodes(session, store.deviceId, series.id, season.id) }.getOrDefault(emptyList())
            _state.update { if (it.selectedSeason == season) it.copy(episodes = episodes) else it }
        }
    }

    /** True when Back stayed inside the tab. */
    fun back(): Boolean {
        if (_state.value.page == ServerPage.Browse) return false
        _state.update { it.copy(page = ServerPage.Browse) }
        return true
    }

    /** Plays a film or episode; the caller switches to the player on true. */
    fun play(item: ServerItem, onPlaying: () -> Unit) {
        val session = store.session.value ?: return
        if (!item.isPlayable) {
            if (item.isSeries) openSeries(item)
            return
        }
        viewModelScope.launch {
            val url = MediaServerClient.streamUrl(session, item.id)
            val channelId = rememberChannel(session, item, url)
            playing = session to item
            runCatching { playerManager.play(MediaCommand.Common(channelId), applyContinueWatching = true) }
            onPlaying()
        }
    }

    /** The player's position, every so often and on pause/stop, for the server's own history. */
    fun reportProgress(positionMs: Long, paused: Boolean, stopped: Boolean) {
        val (session, item) = playing ?: return
        // By the time the player closes its position is gone; the last report stands in for it.
        val position = if (stopped && positionMs <= 0L) lastPositionMs else positionMs
        if (!stopped && positionMs > 0L) lastPositionMs = positionMs
        viewModelScope.launch(Dispatchers.IO) {
            if (stopped) {
                MediaServerClient.reportStopped(session, store.deviceId, item.id, position)
                playing = null
                lastPositionMs = 0L
            } else {
                MediaServerClient.reportProgress(session, store.deviceId, item.id, position, paused)
            }
        }
    }

    @Volatile private var lastPositionMs = 0L

    val playingItemId: String? get() = playing?.second?.id

    private suspend fun rememberChannel(session: ServerSession, item: ServerItem, url: String): Int {
        if (playlistDao.get(PLAYLIST_URL) == null) {
            playlistDao.insertOrReplace(Playlist(title = PLAYLIST_TITLE, url = PLAYLIST_URL, source = DataSource.M3U))
        }
        val relation = "${session.baseUrl}#${item.id}"
        val existing = channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)
        val title = listOfNotNull(
            item.seriesName,
            if (item.season != null && item.episode != null) "S${item.season}E${item.episode}" else null,
            item.name,
        ).joinToString(" · ")
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = session.serverName ?: session.kind.name,
                title = title,
                cover = item.poster,
                playlistUrl = PLAYLIST_URL,
                id = existing?.id ?: 0,
                relationId = relation,
            )
        ).toInt()
        if (id != 0) return id
        return channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)?.id ?: 0
    }

    companion object {
        const val PLAYLIST_URL = "mediaserver://library"
        const val PLAYLIST_TITLE = "Media server"
    }
}
