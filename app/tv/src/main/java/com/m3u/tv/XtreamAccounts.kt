package com.m3u.tv

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamInput
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.worker.SubscriptionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * Dial: native Xtream Codes sign-in and account status for the TV app.
 *
 * Upstream M3UAndroid only lets the TV build receive Xtream accounts pushed from the phone app.
 * On a Fire TV Stick there usually is no phone app, so Dial signs in directly on the TV:
 *   1. check the credentials against player_api.php (fast, clear error messages), then
 *   2. hand them to the existing SubscriptionWorker, which imports live, movies and series.
 * ---------------------------------------------------------------------------------------------- */

data class XtreamCredentials(
    val server: String,
    val username: String,
    val password: String,
)

/** What went wrong when the provider's server couldn't be used, so the app can say so plainly. */
enum class XtreamProblem { Generic, HostNotFound, Refused, Timeout, Secure, Forbidden, HttpStatus, NotXtream }

sealed interface XtreamAccountStatus {
    data object Loading : XtreamAccountStatus
    data class Unreachable(
        val problem: XtreamProblem = XtreamProblem.Generic,
        /** Host and port, for messages ("example.com:8080"). */
        val host: String = "",
        val httpCode: Int? = null,
    ) : XtreamAccountStatus
    data object Rejected : XtreamAccountStatus
    data class Ready(
        val status: String?,
        val expiresAtMillis: Long?,
        val activeConnections: Int?,
        val maxConnections: Int?,
        val trial: Boolean,
    ) : XtreamAccountStatus {
        /** Panels omit `status` now and then; treat a missing value as active. */
        val active: Boolean get() = status == null || status.equals("Active", ignoreCase = true)
    }
}

internal object XtreamClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_BODY_CHARS = 512 * 1024
    private const val LOGIN_CONNECT_TIMEOUT_MS = 30_000
    private const val LOGIN_READ_TIMEOUT_MS = 60_000
    private const val LOGIN_RETRY_WAIT_MS = 2_000L
    private val RETRIED_PROBLEMS = setOf(XtreamProblem.Timeout, XtreamProblem.Refused, XtreamProblem.Generic)

    /** Turns "example.com:8080/", "http://example.com:8080/c/" etc. into "http://example.com:8080". */
    fun normalizeServer(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "http://$trimmed"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }

    /**
     * Providers often send one M3U link instead of three separate fields, e.g.
     * http://host:8080/get.php?username=U&password=P&type=m3u_plus
     * If the server field holds such a link, read the credentials out of it.
     */
    fun credentialsFromLink(input: String): XtreamCredentials? {
        val trimmed = input.trim()
        if (!trimmed.contains("username=") || !trimmed.contains("password=")) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
        val password = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
        val server = normalizeServer(withScheme) ?: return null
        return XtreamCredentials(server, username, password)
    }

    /**
     * Checks the login. Busy panels can take a long time to answer, so the wait is generous, and
     * a timeout or refused connection gets one more try before it counts.
     */
    suspend fun fetchAccount(credentials: XtreamCredentials): XtreamAccountStatus {
        val first = fetchAccountOnce(credentials)
        if (first !is XtreamAccountStatus.Unreachable ||
            first.problem !in RETRIED_PROBLEMS
        ) {
            return first
        }
        delay(LOGIN_RETRY_WAIT_MS)
        return fetchAccountOnce(credentials)
    }

    private suspend fun fetchAccountOnce(credentials: XtreamCredentials): XtreamAccountStatus =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(credentials.server)
                append("/player_api.php?username=")
                append(URLEncoder.encode(credentials.username, Charsets.UTF_8.name()))
                append("&password=")
                append(URLEncoder.encode(credentials.password, Charsets.UTF_8.name()))
            }
            val host = hostLabel(credentials.server)
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = LOGIN_CONNECT_TIMEOUT_MS
                    readTimeout = LOGIN_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
                }
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> return@withContext XtreamAccountStatus.Rejected
                    // 403 usually means the provider is blocking the device, app or network,
                    // not a wrong password (panels answer those with auth = 0).
                    HttpURLConnection.HTTP_FORBIDDEN -> return@withContext XtreamAccountStatus.Unreachable(
                        XtreamProblem.Forbidden, host, code,
                    )
                    in 200..299 -> Unit
                    else -> return@withContext XtreamAccountStatus.Unreachable(
                        XtreamProblem.HttpStatus, host, code,
                    )
                }
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    val out = StringBuilder()
                    while (out.length < MAX_BODY_CHARS) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        out.appendRange(buffer, 0, read)
                    }
                    out.toString()
                }
                parseAccount(body, host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.HostNotFound, host)
            } catch (e: SocketTimeoutException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Timeout, host)
            } catch (e: ConnectException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Refused, host)
            } catch (e: NoRouteToHostException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Refused, host)
            } catch (e: SSLException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Secure, host)
            } catch (e: Exception) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Generic, host)
            } finally {
                connection?.disconnect()
            }
        }

    /** "http://example.com:8080" -> "example.com:8080", for messages. */
    fun hostLabel(server: String): String {
        val uri = runCatching { Uri.parse(server) }.getOrNull() ?: return server
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return server
        return if (uri.port > 0) "$host:${uri.port}" else host
    }

    internal fun parseAccount(body: String, host: String = ""): XtreamAccountStatus {
        // Anything other than a JSON object is usually a web page: a portal, block page or check.
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return XtreamAccountStatus.Unreachable(XtreamProblem.NotXtream, host)
        val user = root["user_info"] as? JsonObject ?: return XtreamAccountStatus.Rejected
        fun field(name: String): String? = (user[name] as? JsonPrimitive)?.contentOrNull
        if (field("auth") == "0") return XtreamAccountStatus.Rejected
        return XtreamAccountStatus.Ready(
            status = field("status"),
            expiresAtMillis = field("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            activeConnections = field("active_cons")?.toIntOrNull(),
            maxConnections = field("max_connections")?.toIntOrNull(),
            trial = field("is_trial") == "1",
        )
    }
}

/** Which kind of source the add-account form is filling in. */
enum class SignInMode { Xtream, M3u }

enum class XtreamSignInError {
    MissingPlaylistUrl,
    MissingServer,
    MissingCredentials,
    Unreachable,
    Rejected,
    AccountInactive,
    ImportFailed,
}

sealed interface XtreamSignInPhase {
    data object Idle : XtreamSignInPhase
    data object Checking : XtreamSignInPhase
    /** Channels, films and series downloaded so far ([count] is 0 until the first batch). */
    data class Importing(val count: Int = 0) : XtreamSignInPhase
    data object Done : XtreamSignInPhase
    data class Failed(
        val error: XtreamSignInError,
        val detail: String? = null,
        val unreachable: XtreamAccountStatus.Unreachable? = null,
    ) : XtreamSignInPhase
}

@Immutable
data class XtreamSignInForm(
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val mode: SignInMode = SignInMode.Xtream,
    /** M3U mode: the playlist link and an optional XMLTV guide link. */
    val playlistUrl: String = "",
    val epgUrl: String = "",
    val phase: XtreamSignInPhase = XtreamSignInPhase.Idle,
) {
    val busy: Boolean
        get() = phase == XtreamSignInPhase.Checking || phase is XtreamSignInPhase.Importing
}

@Immutable
data class XtreamAccount(
    val key: String,
    val title: String,
    val credentials: XtreamCredentials,
    val playlistUrls: List<String>,
)

@HiltViewModel
class XtreamAccountViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val playlistRepository: PlaylistRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val sessionPrefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).also { prefs ->
        // Fresh installs open already signed in. A login the person saved themselves is left alone,
        // and the emulator walkthrough asks to be left signed out (it uses its own test server).
        if (prefs.getString(KEY_SERVER, null).isNullOrBlank() && !prefs.getBoolean(KEY_SKIP_BUNDLED, false)) {
            prefs.edit()
                .putString(KEY_SERVER, BUNDLED_XTREAM.server)
                .putString(KEY_USER, BUNDLED_XTREAM.username)
                .putString(KEY_PASS, BUNDLED_XTREAM.password)
                .putString(KEY_TITLE, BUNDLED_TITLE)
                .apply()
        }
    }

    private val _form = MutableStateFlow(savedForm())
    val form: StateFlow<XtreamSignInForm> = _form.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, XtreamAccountStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, XtreamAccountStatus>> = _statuses.asStateFlow()

    /** True once an Xtream login has succeeded on this device, even if the library is still downloading. */
    private val _hasSession = MutableStateFlow(savedCredentials() != null)
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    val accounts: StateFlow<List<XtreamAccount>> = playlistRepository
        .observeAll()
        .map { playlists -> playlists.toXtreamAccounts() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Plain M3U playlists (not Xtream), for the Accounts page. */
    val m3uPlaylists: StateFlow<List<Playlist>> = playlistRepository
        .observeAll()
        .map { playlists -> playlists.filter { it.source == DataSource.M3U } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var signInJob: Job? = null

    init {
        viewModelScope.launch {
            accounts.collect { list ->
                list.filter { it.key !in _statuses.value }.forEach { refreshStatus(it) }
                if (list.isNotEmpty()) {
                    _hasSession.value = true
                    if (savedCredentials() == null) {
                        val first = list.first()
                        persistSession(first.credentials, first.title)
                    }
                }
            }
        }
        // A saved login with an empty library means the last import never landed (or the
        // database was still opening). Bring the library back instead of showing sign-in again.
        viewModelScope.launch {
            val existing = playlistRepository.observeAll().first()
            if (existing.any { it.source == DataSource.Xtream }) return@launch
            val saved = savedCredentials() ?: return@launch
            _hasSession.value = true
            importAccount(saved, sessionPrefs.getString(KEY_TITLE, null).orEmpty())
        }
    }

    fun updateName(value: String) = _form.update { it.copy(name = value, phase = it.idleUnlessBusy()) }
    fun updateServer(value: String) = _form.update { it.copy(server = value, phase = it.idleUnlessBusy()) }
    fun updateUsername(value: String) = _form.update { it.copy(username = value, phase = it.idleUnlessBusy()) }
    fun updatePassword(value: String) = _form.update { it.copy(password = value, phase = it.idleUnlessBusy()) }

    fun updatePlaylistUrl(value: String) =
        _form.update { it.copy(playlistUrl = value, phase = it.idleUnlessBusy()) }
    fun updateEpgUrl(value: String) =
        _form.update { it.copy(epgUrl = value, phase = it.idleUnlessBusy()) }

    fun setMode(mode: SignInMode) = _form.update {
        if (it.busy || it.mode == mode) it else it.copy(mode = mode, phase = XtreamSignInPhase.Idle)
    }

    fun resetForm() {
        signInJob?.cancel()
        _form.value = XtreamSignInForm(mode = _form.value.mode)
    }

    /** Submits whichever form is showing. */
    fun submit() = when (_form.value.mode) {
        SignInMode.Xtream -> signIn()
        SignInMode.M3u -> addM3u()
    }

    /**
     * Adds a plain M3U playlist, then its XMLTV guide if one was given. An Xtream-style link
     * (get.php?username=...&password=...) signs in as Xtream instead, which adds films, series
     * and catch-up.
     */
    fun addM3u() {
        val current = _form.value
        if (current.busy) return
        val url = current.playlistUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            fail(XtreamSignInError.MissingPlaylistUrl)
            return
        }
        if (XtreamClient.credentialsFromLink(url) != null) {
            _form.update { it.copy(mode = SignInMode.Xtream, server = url) }
            signIn()
            return
        }
        val epgUrl = current.epgUrl.trim().takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val title = current.name.trim().ifEmpty { Uri.parse(url).host ?: url }
        _form.update { it.copy(playlistUrl = url, phase = XtreamSignInPhase.Importing()) }
        signInJob = viewModelScope.launch {
            val workId = try {
                SubscriptionWorker.m3u(workManager, title, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(XtreamSignInError.ImportFailed)
                return@launch
            }
            val finished = awaitImport(workId)
            if (finished != null && finished.state != WorkInfo.State.SUCCEEDED) {
                fail(
                    XtreamSignInError.ImportFailed,
                    detail = finished.outputData.getString(SubscriptionWorker.OUTPUT_STRING_ERROR),
                )
                return@launch
            }
            if (epgUrl != null) {
                // The guide downloads in the background; the TV guide fills in when it's done.
                runCatching {
                    playlistRepository.insertEpgAsPlaylist(
                        title = listOf(title, "EPG").joinToString(" "),
                        epg = epgUrl,
                    )
                    playlistRepository.onUpdateEpgPlaylist(
                        PlaylistRepository.EpgPlaylistUseCase.Check(
                            playlistUrl = url,
                            epgUrl = epgUrl,
                            action = true,
                        )
                    )
                    SubscriptionWorker.epg(workManager, url, ignoreCache = true)
                }.onFailure { if (it is CancellationException) throw it }
            }
            _form.update { XtreamSignInForm(mode = SignInMode.M3u, phase = XtreamSignInPhase.Done) }
        }
    }

    fun refreshPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            runCatching { playlistRepository.refresh(playlist.url) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    fun removePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            runCatching {
                playlist.epgUrls.forEach { epg -> playlistRepository.deleteEpgPlaylistAndProgrammes(epg) }
                playlistRepository.unsubscribe(playlist.url)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** Waits for an import to finish, showing how many entries have loaded meanwhile.
     *  A missing work row is not success: WorkManager emits null before the job is written,
     *  and treating that as "loaded" left Live TV, Films and Series on the login screen. */
    private suspend fun awaitImport(workId: UUID): WorkInfo? = withTimeoutOrNull(IMPORT_TIMEOUT_MS) {
        workManager
            .getWorkInfoByIdFlow(workId)
            .mapNotNull { it }
            .onEach { info ->
                val count = info.progress.getInt(SubscriptionWorker.PROGRESS_INT_COUNT, 0)
                if (count > 0 && !info.state.isFinished) {
                    _form.update { form ->
                        if (form.phase is XtreamSignInPhase.Importing) {
                            form.copy(phase = XtreamSignInPhase.Importing(count))
                        } else {
                            form
                        }
                    }
                }
            }
            .first { info -> info.state.isFinished }
    }

    fun signIn() {
        val current = _form.value
        if (current.busy) return

        val fromLink = XtreamClient.credentialsFromLink(current.server)
        val server = fromLink?.server ?: XtreamClient.normalizeServer(current.server)
        val username = fromLink?.username ?: current.username.trim()
        val password = fromLink?.password ?: current.password.trim()

        if (server == null) {
            fail(XtreamSignInError.MissingServer)
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            _form.update { it.copy(server = server, username = username, password = password) }
            fail(XtreamSignInError.MissingCredentials)
            return
        }
        val credentials = XtreamCredentials(server, username, password)
        _form.update {
            it.copy(
                server = server,
                username = username,
                password = password,
                phase = XtreamSignInPhase.Checking,
            )
        }

        signInJob = viewModelScope.launch {
            when (val status = XtreamClient.fetchAccount(credentials)) {
                is XtreamAccountStatus.Unreachable -> fail(XtreamSignInError.Unreachable, unreachable = status)
                XtreamAccountStatus.Rejected -> fail(XtreamSignInError.Rejected)
                XtreamAccountStatus.Loading -> Unit
                is XtreamAccountStatus.Ready -> {
                    if (!status.active) {
                        fail(XtreamSignInError.AccountInactive, status.status)
                    } else {
                        persistSession(credentials, _form.value.name.trim())
                        importAccount(credentials, _form.value.name.trim(), status)
                    }
                }
            }
        }
    }

    fun retrySaved() {
        val saved = savedCredentials() ?: return
        if (_form.value.busy) return
        viewModelScope.launch {
            importAccount(saved, sessionPrefs.getString(KEY_TITLE, null).orEmpty())
        }
    }

    private suspend fun importAccount(
        credentials: XtreamCredentials,
        name: String,
        status: XtreamAccountStatus.Ready? = null,
    ) {
        persistSession(credentials, name)
        val title = name.ifEmpty { Uri.parse(credentials.server).host ?: credentials.server }
        val workId = try {
            SubscriptionWorker.xtream(
                workManager = workManager,
                title = title,
                url = "",
                basicUrl = credentials.server,
                username = credentials.username,
                password = credentials.password,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(XtreamSignInError.ImportFailed)
            return
        }
        _form.update { it.copy(phase = XtreamSignInPhase.Importing()) }
        if (status != null) {
            _statuses.update { it + (accountKey(credentials) to status) }
        }

        // Big providers take minutes; the form shows how far the download has got.
        val finished = awaitImport(workId)
        if (finished?.state == WorkInfo.State.SUCCEEDED) {
            // Keep the login so a second account on the same panel is quick, and so a
            // restart never asks again.
            _form.update {
                XtreamSignInForm(
                    server = credentials.server,
                    username = credentials.username,
                    password = credentials.password,
                    phase = XtreamSignInPhase.Done,
                )
            }
        } else {
            fail(
                XtreamSignInError.ImportFailed,
                detail = finished?.outputData?.getString(SubscriptionWorker.OUTPUT_STRING_ERROR)
                    ?: if (finished == null) "timed out" else finished.state.name,
            )
        }
    }

    fun refreshStatus(account: XtreamAccount) {
        _statuses.update { it + (account.key to XtreamAccountStatus.Loading) }
        viewModelScope.launch {
            val status = XtreamClient.fetchAccount(account.credentials)
            _statuses.update { it + (account.key to status) }
        }
    }

    fun remove(account: XtreamAccount) {
        val saved = savedCredentials()
        if (saved != null && accountKey(saved) == account.key) clearSession()
        viewModelScope.launch {
            account.playlistUrls.forEach { url ->
                runCatching { playlistRepository.unsubscribe(url) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            _statuses.update { it - account.key }
        }
    }

    private fun fail(
        error: XtreamSignInError,
        detail: String? = null,
        unreachable: XtreamAccountStatus.Unreachable? = null,
    ) {
        _form.update { it.copy(phase = XtreamSignInPhase.Failed(error, detail, unreachable)) }
    }

    private fun XtreamSignInForm.idleUnlessBusy(): XtreamSignInPhase =
        if (busy) phase else XtreamSignInPhase.Idle

    private fun List<Playlist>.toXtreamAccounts(): List<XtreamAccount> =
        filter { it.source == DataSource.Xtream }
            .mapNotNull { playlist ->
                val input = XtreamInput.decodeFromPlaylistUrlOrNull(playlist.url)
                    ?: return@mapNotNull null
                val server = XtreamClient.normalizeServer(input.basicUrl) ?: return@mapNotNull null
                playlist to XtreamCredentials(server, input.username, input.password)
            }
            .groupBy { (_, credentials) -> accountKey(credentials) }
            .map { (key, entries) ->
                XtreamAccount(
                    key = key,
                    title = entries.first().first.title,
                    credentials = entries.first().second,
                    playlistUrls = entries.map { (playlist, _) -> playlist.url },
                )
            }
            .sortedBy { it.title.lowercase() }

    private fun accountKey(credentials: XtreamCredentials): String =
        "${credentials.server.lowercase()}|${credentials.username}"

    private fun savedForm(): XtreamSignInForm {
        val saved = savedCredentials() ?: return XtreamSignInForm()
        return XtreamSignInForm(
            name = sessionPrefs.getString(KEY_TITLE, null).orEmpty(),
            server = saved.server,
            username = saved.username,
            password = saved.password,
        )
    }

    private fun savedCredentials(): XtreamCredentials? {
        val server = sessionPrefs.getString(KEY_SERVER, null) ?: return null
        val username = sessionPrefs.getString(KEY_USER, null) ?: return null
        val password = sessionPrefs.getString(KEY_PASS, null) ?: return null
        if (server.isBlank() || username.isBlank() || password.isBlank()) return null
        return XtreamCredentials(server, username, password)
    }

    private fun persistSession(credentials: XtreamCredentials, title: String) {
        sessionPrefs.edit()
            .putString(KEY_SERVER, credentials.server)
            .putString(KEY_USER, credentials.username)
            .putString(KEY_PASS, credentials.password)
            .putString(KEY_TITLE, title)
            .apply()
        _hasSession.value = true
    }

    private fun clearSession() {
        sessionPrefs.edit().clear().apply()
        _hasSession.value = false
    }

    companion object {
        const val SESSION_PREFS = "xtream_session"
        const val KEY_SKIP_BUNDLED = "skip_bundled_login"
        private const val KEY_SERVER = "server"
        private const val KEY_USER = "username"
        private const val KEY_PASS = "password"
        private const val KEY_TITLE = "title"
        private const val IMPORT_TIMEOUT_MS = 30 * 60_000L
        private val BUNDLED_XTREAM = XtreamCredentials(
            server = "http://www.cool13535.wd.ness-8k-all.online",
            username = "b7850079f070",
            password = "bc69d28478",
        )
        private const val BUNDLED_TITLE = "Ness"
    }
}
