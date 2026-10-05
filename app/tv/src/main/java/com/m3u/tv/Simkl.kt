package com.m3u.tv

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Simkl as a second watch tracker. The client id is typed in Settings. Sign-in is a code on
 * simkl.com. Scrobbles and the watchlist fail quietly.
 */
@Singleton
class SimklService @Inject constructor(
    private val secrets: SecretStore,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signInJob: Job? = null

    private val _signIn = MutableStateFlow<SimklSignIn>(SimklSignIn.Idle)
    val signIn: StateFlow<SimklSignIn> = _signIn.asStateFlow()

    private val _watchlist = MutableStateFlow<List<String>>(emptyList())
    val watchlist: StateFlow<List<String>> = _watchlist.asStateFlow()

    val signedIn: Boolean get() = secrets.has(SecretName.SimklAccess)

    init {
        refreshWatchlist()
    }

    fun startSignIn() {
        val clientId = secrets.get(SecretName.SimklClientId)
        if (clientId.isNullOrBlank()) {
            _signIn.value = SimklSignIn.Failed("needs_key")
            return
        }
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                val pin = request("POST", "/oauth/pin?client_id=${encode(clientId)}", null, clientId, auth = false)
                val userCode = pin.text("user_code") ?: throw IllegalStateException("no_code")
                _signIn.value = SimklSignIn.Code(
                    userCode = userCode,
                    url = pin.text("verification_url") ?: "https://simkl.com/pin/$userCode",
                )
                val interval = (pin.int("interval") ?: 5).coerceIn(5, 15) * 1_000L
                val deadline = System.currentTimeMillis() + (pin.int("expires_in") ?: 900) * 1_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    val token = request("GET", "/oauth/pin/${encode(userCode)}?client_id=${encode(clientId)}", null, clientId, auth = false)
                    val access = token.text("access_token") ?: continue
                    secrets.put(SecretName.SimklAccess, access)
                    _signIn.value = SimklSignIn.Idle
                    refreshWatchlist()
                    return@launch
                }
                _signIn.value = SimklSignIn.Failed("expired")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _signIn.value = SimklSignIn.Failed("failed")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _signIn.value = SimklSignIn.Idle
    }

    fun signOut() {
        signInJob?.cancel()
        secrets.remove(SecretName.SimklAccess)
        _watchlist.value = emptyList()
        _signIn.value = SimklSignIn.Idle
    }

    fun refreshWatchlist() {
        if (!signedIn) {
            _watchlist.value = emptyList()
            return
        }
        scope.launch {
            val clientId = secrets.get(SecretName.SimklClientId) ?: return@launch
            val root = runCatching {
                request("GET", "/sync/all-items/movies/plantowatch?extended=title", null, clientId, auth = true)
            }.getOrNull()
            val titles = (root?.get("movies") as? JsonArray).orEmpty().mapNotNull { element ->
                val movie = (element as? JsonObject)?.get("movie") as? JsonObject ?: element as? JsonObject
                movie?.text("title")
            }.distinct().take(8)
            _watchlist.value = titles
        }
    }

    suspend fun scrobble(action: String, item: TraktItem, progress: Float) {
        if (!signedIn || item is TraktItem.Show) return
        val clientId = secrets.get(SecretName.SimklClientId) ?: return
        val body = buildJsonObject {
            when (item) {
                is TraktItem.Movie -> put("movie", buildJsonObject {
                    put("title", item.title)
                    item.year?.let { put("year", it) }
                    item.tmdbId?.let { put("ids", buildJsonObject { put("tmdb", it) }) }
                })
                is TraktItem.Episode -> {
                    put("show", buildJsonObject {
                        put("title", item.title)
                        item.year?.let { put("year", it) }
                        item.showTmdbId?.let { put("ids", buildJsonObject { put("tmdb", it) }) }
                    })
                    put("episode", buildJsonObject {
                        put("season", item.season)
                        put("number", item.number)
                    })
                }
                is TraktItem.Show -> return
            }
            put("progress", progress.coerceIn(0f, 100f))
        }
        runCatching { request("POST", "/scrobble/$action", body.toString(), clientId, auth = true) }
    }

    private fun request(method: String, path: String, body: String?, clientId: String, auth: Boolean): JsonObject {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = (java.net.URL("https://api.simkl.com$path").openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("simkl-api-key", clientId)
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
                if (auth) secrets.get(SecretName.SimklAccess)?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
}

sealed interface SimklSignIn {
    data object Idle : SimklSignIn
    data class Code(val userCode: String, val url: String) : SimklSignIn
    data class Failed(val reason: String) : SimklSignIn
}
