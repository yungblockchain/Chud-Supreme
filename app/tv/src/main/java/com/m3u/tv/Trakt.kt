package com.m3u.tv

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/* -------------------------------------------------------------------------------------------------
 * Trakt, signed in once for the whole app (Settings > Services): the device-code sign-in, token
 * refresh, scrobbling, ratings, comments, and the person's lists for Home rows. The client id and
 * secret are the person's own (trakt.tv/oauth/applications); tokens live in the encrypted store.
 * ---------------------------------------------------------------------------------------------- */

class TraktException(val code: Int?, message: String? = null) : IOException(message ?: "Trakt $code")

/** A film or an episode, as Trakt needs to hear about it. */
@Immutable
sealed interface TraktItem {
    val title: String
    val year: Int?

    data class Movie(override val title: String, override val year: Int?, val tmdbId: Int? = null) : TraktItem
    /** A whole series (rated and commented on as one; never scrobbled). */
    data class Show(override val title: String, override val year: Int?, val tmdbId: Int? = null) : TraktItem
    data class Episode(
        override val title: String,
        override val year: Int?,
        val showTmdbId: Int?,
        val season: Int,
        val number: Int,
    ) : TraktItem

    val key: String
        get() = when (this) {
            is Movie -> "movie:${tmdbId ?: title.lowercase()}:${year ?: ""}"
            is Show -> "show:${tmdbId ?: title.lowercase()}:${year ?: ""}"
            is Episode -> "episode:${showTmdbId ?: title.lowercase()}:$season:$number"
        }
}

@Immutable
data class TraktAccount(val username: String)

/** The built-in Trakt rows; a custom list is [Custom] with its own name. */
enum class TraktRowKind { Playback, Watchlist, UpNext, RecommendedFilms, RecommendedSeries, Custom }

/** A Trakt list ready for a Home row; the titles carry TMDB ids and Trakt's own artwork. */
@Immutable
data class TraktRow(val id: String, val kind: TraktRowKind, val listName: String?, val titles: List<TmdbTitle>)

sealed interface TraktSignIn {
    data object Idle : TraktSignIn
    data class Code(val userCode: String, val url: String) : TraktSignIn
    data class Failed(val message: String) : TraktSignIn
}

@Immutable
data class TraktRatings(val rating: Double, val votes: Int)

@Singleton
class TraktService @Inject constructor(
    private val secrets: SecretStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshLock = Mutex()

    private val _account = MutableStateFlow<TraktAccount?>(null)
    val account: StateFlow<TraktAccount?> = _account.asStateFlow()

    private val _signIn = MutableStateFlow<TraktSignIn>(TraktSignIn.Idle)
    val signIn: StateFlow<TraktSignIn> = _signIn.asStateFlow()

    private var signInJob: Job? = null
    private val idCache = HashMap<String, JsonObject?>()

    val hasClient: Boolean get() = secrets.has(SecretName.TraktClientId) && secrets.has(SecretName.TraktClientSecret)
    val signedIn: Boolean get() = secrets.has(SecretName.TraktAccess)

    init {
        scope.launch { secrets.saved.collect { refreshAccount() } }
    }

    private suspend fun refreshAccount() {
        if (!signedIn || !hasClient) {
            _account.value = null
            return
        }
        if (_account.value != null) return
        val name = runCatching { username() }.getOrNull() ?: return
        _account.value = TraktAccount(name)
    }

    /* ------------------------------------------------------------------------------ sign in */

    fun startSignIn() {
        val clientId = secrets.get(SecretName.TraktClientId)
        val secret = secrets.get(SecretName.TraktClientSecret)
        if (clientId.isNullOrBlank() || secret.isNullOrBlank()) {
            _signIn.value = TraktSignIn.Failed("needs_keys")
            return
        }
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                val device = post("/oauth/device/code", buildJsonObject { put("client_id", clientId) }.toString(), auth = false)
                val deviceCode = device.text("device_code") ?: throw TraktException(null, "no_code")
                _signIn.value = TraktSignIn.Code(
                    userCode = device.text("user_code") ?: throw TraktException(null, "no_code"),
                    url = device.text("verification_url") ?: "https://trakt.tv/activate",
                )
                val interval = (device.int("interval") ?: 5).coerceIn(5, 15) * 1_000L
                val deadline = System.currentTimeMillis() + (device.int("expires_in") ?: 600) * 1_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    val token = try {
                        post(
                            "/oauth/device/token",
                            buildJsonObject {
                                put("code", deviceCode)
                                put("client_id", clientId)
                                put("client_secret", secret)
                            }.toString(),
                            auth = false,
                        )
                    } catch (e: TraktException) {
                        when (e.code) {
                            400, 429 -> continue // not approved yet / slow down
                            404, 409, 410, 418 -> throw TraktException(e.code, "expired")
                            else -> throw e
                        }
                    }
                    val access = token.text("access_token") ?: continue
                    secrets.put(SecretName.TraktAccess, access)
                    token.text("refresh_token")?.let { secrets.put(SecretName.TraktRefresh, it) }
                    _signIn.value = TraktSignIn.Idle
                    _account.value = runCatching { username() }.getOrNull()?.let(::TraktAccount) ?: TraktAccount("Trakt")
                    return@launch
                }
                _signIn.value = TraktSignIn.Failed("expired")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _signIn.value = TraktSignIn.Failed(e.message ?: "failed")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _signIn.value = TraktSignIn.Idle
    }

    fun signOut() {
        signInJob?.cancel()
        secrets.remove(SecretName.TraktAccess)
        secrets.remove(SecretName.TraktRefresh)
        _account.value = null
        _signIn.value = TraktSignIn.Idle
        idCache.clear()
    }

    private suspend fun username(): String? =
        (get("/users/settings")["user"] as? JsonObject)?.text("username")

    /* ----------------------------------------------------------------------------- scrobble */

    enum class ScrobbleAction(val path: String) { Start("start"), Pause("pause"), Stop("stop") }

    /** Tells Trakt what's playing. [progress] is 0..100. Quietly does nothing when signed out. */
    suspend fun scrobble(action: ScrobbleAction, item: TraktItem, progress: Float) {
        if (!signedIn || item is TraktItem.Show) return
        val body = itemBody(item) ?: return
        body.put("progress", JsonPrimitive(progress.coerceIn(0f, 100f)))
        body.put("app_version", JsonPrimitive("chud-supreme"))
        runCatching { post("/scrobble/${action.path}", JsonObject(body).toString()) }
    }

    /** Films and single episodes only: a whole show would mark every episode at once. */
    suspend fun markWatched(item: TraktItem): Boolean {
        if (item is TraktItem.Show) return false
        val body = itemBody(item) ?: return false
        val wrapped = buildJsonObject {
            put(if (item is TraktItem.Movie) "movies" else "episodes", JsonArray(listOf(JsonObject(body))))
        }
        return runCatching { post("/sync/history", wrapped.toString()) }.isSuccess
    }

    /* -------------------------------------------------------------------- ratings, comments */

    /** Rates 1..10; 0 removes the rating. */
    suspend fun rate(item: TraktItem, rating: Int): Boolean {
        val body = itemBody(item) ?: return false
        if (rating in 1..10) body.put("rating", JsonPrimitive(rating))
        val listName = when (item) {
            is TraktItem.Movie -> "movies"
            is TraktItem.Show -> "shows"
            is TraktItem.Episode -> "episodes"
        }
        val wrapped = buildJsonObject { put(listName, JsonArray(listOf(JsonObject(body)))) }
        val path = if (rating in 1..10) "/sync/ratings" else "/sync/ratings/remove"
        return runCatching { post(path, wrapped.toString()) }.isSuccess
    }

    /** The person's own rating for a film or show (by TMDB id), or null. */
    suspend fun myRating(kind: MediaKind, tmdbId: Int): Int? {
        if (!signedIn) return null
        val type = if (kind == MediaKind.Movie) "movies" else "shows"
        val rows = getArray("/sync/ratings/$type")
        return rows.firstNotNullOfOrNull { element ->
            val row = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val node = row[if (kind == MediaKind.Movie) "movie" else "show"] as? JsonObject
            val id = (node?.get("ids") as? JsonObject)?.int("tmdb")
            if (id == tmdbId) row.int("rating") else null
        }
    }

    /** Posts a comment (Trakt wants at least five words). */
    suspend fun comment(item: TraktItem, text: String, spoiler: Boolean): Boolean {
        val body = itemBody(item) ?: return false
        body.put("comment", JsonPrimitive(text))
        body.put("spoiler", JsonPrimitive(spoiler))
        return runCatching { post("/comments", JsonObject(body).toString()) }.isSuccess
    }

    /** Trakt's community rating for a film or show, or null. */
    suspend fun ratings(kind: MediaKind, tmdbId: Int): TraktRatings? {
        val ids = lookup(kind, tmdbId) ?: return null
        val slug = ids.text("slug") ?: ids.int("trakt")?.toString() ?: return null
        val type = if (kind == MediaKind.Movie) "movies" else "shows"
        val root = runCatching { get("/$type/$slug/ratings", auth = false) }.getOrNull() ?: return null
        val rating = (root["rating"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return TraktRatings(rating = rating, votes = root.int("votes") ?: 0)
    }

    /* ---------------------------------------------------------------------------- Home rows */

    /** The rows Home can show: continue watching, watchlist, up next, recommendations, lists. */
    suspend fun homeRows(): List<TraktRow> {
        if (!signedIn) return emptyList()
        val rows = mutableListOf<TraktRow>()
        suspend fun add(id: String, kind: TraktRowKind, listName: String? = null, block: suspend () -> List<TmdbTitle>) {
            val titles = runCatching { block() }.getOrDefault(emptyList())
            if (titles.isNotEmpty()) rows += TraktRow(id, kind, listName, titles)
        }
        add("playback", TraktRowKind.Playback) {
            getArray("/sync/playback?extended=full,images").mapNotNull { titleOf(it as? JsonObject) }.distinctBy { it.kind to it.id }
        }
        add("watchlist", TraktRowKind.Watchlist) {
            getArray("/sync/watchlist?extended=full,images").mapNotNull { titleOf(it as? JsonObject) }
        }
        add("upnext", TraktRowKind.UpNext) {
            val start = LocalDate.now().toString()
            getArray("/calendars/my/shows/$start/7?extended=full,images").mapNotNull { element ->
                val row = element as? JsonObject
                val show = titleOf(row?.get("show") as? JsonObject, MediaKind.Tv) ?: return@mapNotNull null
                val episode = row?.get("episode") as? JsonObject
                val label = episode?.let { "S${it.int("season")}E${it.int("number")}" }
                show.copy(overview = listOfNotNull(label, episode?.text("title")).joinToString(" · ").ifBlank { show.overview })
            }.distinctBy { it.id }
        }
        add("recommended-movies", TraktRowKind.RecommendedFilms) {
            getArray("/recommendations/movies?extended=full,images&limit=20").mapNotNull { titleOf(it as? JsonObject, MediaKind.Movie) }
        }
        add("recommended-shows", TraktRowKind.RecommendedSeries) {
            getArray("/recommendations/shows?extended=full,images&limit=20").mapNotNull { titleOf(it as? JsonObject, MediaKind.Tv) }
        }
        val lists = runCatching { getArray("/users/me/lists") }.getOrDefault(JsonArray(emptyList()))
        for (element in lists.take(MAX_LISTS)) {
            val list = element as? JsonObject ?: continue
            val name = list.text("name") ?: continue
            val ids = list["ids"] as? JsonObject ?: continue
            val id = ids.text("slug") ?: ids.int("trakt")?.toString() ?: continue
            add("list-$id", TraktRowKind.Custom, name) {
                getArray("/users/me/lists/$id/items?extended=full,images&limit=30").mapNotNull { titleOf(it as? JsonObject) }
            }
        }
        return rows
    }

    /** Trending on Trakt (no sign-in needed, only the client id). */
    suspend fun trending(kind: MediaKind): List<TmdbTitle> {
        val type = if (kind == MediaKind.Movie) "movies" else "shows"
        return getArray("/$type/trending?extended=full,images&limit=20", auth = false)
            .mapNotNull { titleOf(it as? JsonObject, kind) }
    }

    /* ----------------------------------------------------------------------------- internals */

    /** The "movie" / "show"+"episode" part of a scrobble, rating or comment body. */
    private suspend fun itemBody(item: TraktItem): MutableMap<String, JsonElement>? {
        val body = mutableMapOf<String, JsonElement>()
        when (item) {
            is TraktItem.Movie -> {
                val ids = item.tmdbId?.let { lookup(MediaKind.Movie, it) } ?: search("movie", item.title, item.year) ?: return null
                body["movie"] = buildJsonObject { put("ids", ids) }
            }
            is TraktItem.Show -> {
                val ids = item.tmdbId?.let { lookup(MediaKind.Tv, it) } ?: search("show", item.title, item.year) ?: return null
                body["show"] = buildJsonObject { put("ids", ids) }
            }
            is TraktItem.Episode -> {
                val ids = item.showTmdbId?.let { lookup(MediaKind.Tv, it) } ?: search("show", item.title, item.year) ?: return null
                body["show"] = buildJsonObject { put("ids", ids) }
                body["episode"] = buildJsonObject {
                    put("season", item.season)
                    put("number", item.number)
                }
            }
        }
        return body
    }

    /** Trakt's ids for a TMDB id, cached. */
    private suspend fun lookup(kind: MediaKind, tmdbId: Int): JsonObject? {
        val type = if (kind == MediaKind.Movie) "movie" else "show"
        val cacheKey = "$type:$tmdbId"
        synchronized(idCache) { if (idCache.containsKey(cacheKey)) return idCache[cacheKey] }
        val found = runCatching {
            getArray("/search/tmdb/$tmdbId?type=$type", auth = false)
                .firstNotNullOfOrNull { ((it as? JsonObject)?.get(type) as? JsonObject)?.get("ids") as? JsonObject }
        }.getOrNull()
        synchronized(idCache) { idCache[cacheKey] = found }
        return found
    }

    private suspend fun search(type: String, title: String, year: Int?): JsonObject? = runCatching {
        val query = URLEncoder.encode(title, "UTF-8")
        val years = year?.let { "&years=$it" }.orEmpty()
        getArray("/search/$type?query=$query$years&limit=1", auth = false)
            .firstNotNullOfOrNull { ((it as? JsonObject)?.get(type) as? JsonObject)?.get("ids") as? JsonObject }
    }.getOrNull()

    /** A row from Trakt (a list item, a playback entry, a plain movie/show) as a title for a card. */
    private fun titleOf(row: JsonObject?, hint: MediaKind? = null): TmdbTitle? {
        if (row == null) return null
        val kind = when {
            row["movie"] != null -> MediaKind.Movie
            row["show"] != null -> MediaKind.Tv
            hint != null -> hint
            else -> return null
        }
        val node = (row["movie"] ?: row["show"]) as? JsonObject ?: row
        val ids = node["ids"] as? JsonObject
        val tmdb = ids?.int("tmdb") ?: return null
        val images = node["images"] as? JsonObject
        fun image(name: String): String? =
            ((images?.get(name) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { "https://$it" }
        return TmdbTitle(
            id = tmdb,
            kind = kind,
            title = node.text("title") ?: return null,
            overview = node.text("overview"),
            poster = image("poster"),
            backdrop = image("fanart"),
            year = node.int("year")?.toString(),
            rating = (node["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
        )
    }

    private suspend fun get(path: String, auth: Boolean = true): JsonObject =
        request("GET", path, null, auth) as? JsonObject ?: JsonObject(emptyMap())

    private suspend fun getArray(path: String, auth: Boolean = true): JsonArray =
        request("GET", path, null, auth) as? JsonArray ?: JsonArray(emptyList())

    private suspend fun post(path: String, body: String, auth: Boolean = true): JsonObject =
        request("POST", path, body, auth) as? JsonObject ?: JsonObject(emptyMap())

    private suspend fun request(method: String, path: String, body: String?, auth: Boolean): JsonElement? =
        try {
            send(method, path, body, auth)
        } catch (e: TraktException) {
            // An expired token: refresh once and try again.
            if (auth && e.code == 401 && refreshTokens()) send(method, path, body, auth) else throw e
        }

    private suspend fun send(method: String, path: String, body: String?, auth: Boolean): JsonElement? {
        val clientId = secrets.get(SecretName.TraktClientId) ?: throw TraktException(null, "needs_keys")
        val access = if (auth) secrets.get(SecretName.TraktAccess) else null
        if (auth && access == null) throw TraktException(401, "signed_out")
        val result = withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("trakt-api-version", "2")
                    setRequestProperty("trakt-api-key", clientId)
                    setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
                    if (access != null) setRequestProperty("Authorization", "Bearer $access")
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw TraktException(code)
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TraktException) {
                throw e
            } catch (e: Exception) {
                throw TraktException(null, e.message)
            } finally {
                connection?.disconnect()
            }
        }
        return result
    }

    /** On a 401 the refresh token buys a new access token once; then the call is retried. */
    private suspend fun refreshTokens(): Boolean = refreshLock.withLock {
        val clientId = secrets.get(SecretName.TraktClientId) ?: return false
        val secret = secrets.get(SecretName.TraktClientSecret) ?: return false
        val refresh = secrets.get(SecretName.TraktRefresh) ?: return false
        val root = runCatching {
            post(
                "/oauth/token",
                buildJsonObject {
                    put("refresh_token", refresh)
                    put("client_id", clientId)
                    put("client_secret", secret)
                    put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                    put("grant_type", "refresh_token")
                }.toString(),
                auth = false,
            )
        }.getOrNull() ?: return false
        val access = root.text("access_token") ?: return false
        secrets.put(SecretName.TraktAccess, access)
        root.text("refresh_token")?.let { secrets.put(SecretName.TraktRefresh, it) }
        return true
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private companion object {
        const val BASE = "https://api.trakt.tv"
        const val MAX_LISTS = 6
    }
}
