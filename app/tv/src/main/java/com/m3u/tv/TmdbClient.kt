package com.m3u.tv

import androidx.compose.runtime.Immutable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/* -------------------------------------------------------------------------------------------------
 * TMDB (themoviedb.org) with the person's own key: trending titles, cast, people and their films,
 * IMDb ids. Trakt for reviews/comments with their own client id. Nothing is built into the app.
 * ---------------------------------------------------------------------------------------------- */

enum class MediaKind(val path: String) { Movie("movie"), Tv("tv") }

@Immutable
data class TmdbTitle(
    val id: Int,
    val kind: MediaKind,
    val title: String,
    val overview: String?,
    val poster: String?,
    val backdrop: String?,
    val year: String?,
    val rating: Double?,
)

@Immutable
data class CastMember(
    val id: Int,
    val name: String,
    val character: String?,
    val photo: String?,
)

@Immutable
data class TitleExtras(
    val tmdbId: Int,
    val kind: MediaKind,
    val overview: String?,
    val tagline: String?,
    val cast: List<CastMember>,
    val imdbId: String?,
    val rating: Double?,
    /** The title's logo artwork (transparent PNG), to draw instead of the name. */
    val logo: String? = null,
    val votes: Int = 0,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val certification: String? = null,
)

@Immutable
data class PersonDetails(
    val id: Int,
    val name: String,
    val biography: String?,
    val born: String?,
    val birthplace: String?,
    val photo: String?,
    val imdbId: String?,
    val knownFor: List<TmdbTitle>,
)

@Immutable
data class TraktComment(
    val user: String,
    val text: String,
    val rating: Int?,
    val likes: Int,
    val spoiler: Boolean,
)

class TmdbException(val code: Int?) : IOException("TMDB $code")

internal object TmdbClient {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun poster(path: String?): String? = path?.let { "$IMAGES/w342$it" }
    fun backdrop(path: String?): String? = path?.let { "$IMAGES/w780$it" }
    fun profile(path: String?): String? = path?.let { "$IMAGES/w185$it" }
    fun logo(path: String?): String? = path?.let { "$IMAGES/w500$it" }

    suspend fun trending(key: String, kind: MediaKind): List<TmdbTitle> {
        val root = get(key, "/trending/${kind.path}/week") as? JsonObject ?: return emptyList()
        return (root["results"] as? JsonArray).orEmpty().mapNotNull { it.toTitle(kind) }
    }

    /** Finds a title by name (and year), for films the provider sends without a TMDB id. */
    suspend fun search(key: String, kind: MediaKind, query: String, year: String?): TmdbTitle? {
        val params = buildList {
            add("query" to query)
            if (year != null) add((if (kind == MediaKind.Movie) "year" else "first_air_date_year") to year)
        }
        val root = get(key, "/search/${kind.path}", params) as? JsonObject ?: return null
        return (root["results"] as? JsonArray)?.firstNotNullOfOrNull { it.toTitle(kind) }
    }

    suspend fun extras(key: String, kind: MediaKind, id: Int): TitleExtras? {
        val credits = if (kind == MediaKind.Tv) "aggregate_credits" else "credits"
        val root = get(
            key,
            "/${kind.path}/$id",
            listOf(
                "append_to_response" to "$credits,external_ids,images,release_dates,content_ratings",
                "include_image_language" to "${Locale.getDefault().language},en,null",
            ),
        ) as? JsonObject ?: return null
        // The first logo in the person's language, else English, else any.
        val logos = ((root["images"] as? JsonObject)?.get("logos") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        val language = Locale.getDefault().language
        val logoPath = (logos.firstOrNull { it.text("iso_639_1") == language }
            ?: logos.firstOrNull { it.text("iso_639_1") == "en" }
            ?: logos.firstOrNull())?.text("file_path")
        val certification = if (kind == MediaKind.Movie) {
            ((root["release_dates"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .sortedBy { if (it.text("iso_3166_1") == Locale.getDefault().country) 0 else 1 }
                .firstNotNullOfOrNull { country ->
                    ((country["release_dates"] as? JsonArray).orEmpty())
                        .mapNotNull { (it as? JsonObject)?.text("certification") }
                        .firstOrNull()
                }
        } else {
            ((root["content_ratings"] as? JsonObject)?.get("results") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .sortedBy { if (it.text("iso_3166_1") == Locale.getDefault().country) 0 else 1 }
                .firstNotNullOfOrNull { it.text("rating") }
        }
        val castArray = (root[credits] as? JsonObject)?.get("cast") as? JsonArray
        val cast = castArray.orEmpty().mapNotNull { element ->
            val person = element as? JsonObject ?: return@mapNotNull null
            val personId = person.int("id") ?: return@mapNotNull null
            val character = person.text("character")
                ?: ((person["roles"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("character")
            CastMember(
                id = personId,
                name = person.text("name") ?: return@mapNotNull null,
                character = character,
                photo = profile(person.text("profile_path")),
            )
        }.take(MAX_CAST)
        val imdb = root.text("imdb_id") ?: (root["external_ids"] as? JsonObject)?.text("imdb_id")
        return TitleExtras(
            tmdbId = id,
            kind = kind,
            overview = root.text("overview"),
            tagline = root.text("tagline"),
            cast = cast,
            imdbId = imdb,
            rating = (root["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
            logo = logo(logoPath),
            votes = root.int("vote_count") ?: 0,
            genres = (root["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") },
            runtimeMinutes = root.int("runtime")
                ?: ((root["episode_run_time"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.intOrNull,
            certification = certification,
        )
    }

    suspend fun person(key: String, id: Int): PersonDetails? {
        val root = get(key, "/person/$id", listOf("append_to_response" to "combined_credits,external_ids"))
            as? JsonObject ?: return null
        val credits = ((root["combined_credits"] as? JsonObject)?.get("cast") as? JsonArray).orEmpty()
        val knownFor = credits.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val kind = when (item.text("media_type")) {
                "movie" -> MediaKind.Movie
                "tv" -> MediaKind.Tv
                else -> return@mapNotNull null
            }
            item.toTitle(kind)?.let { it to ((item["popularity"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
        }
            .distinctBy { it.first.kind to it.first.id }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(MAX_KNOWN_FOR)
        return PersonDetails(
            id = id,
            name = root.text("name") ?: return null,
            biography = root.text("biography"),
            born = root.text("birthday"),
            birthplace = root.text("place_of_birth"),
            photo = profile(root.text("profile_path")),
            imdbId = root.text("imdb_id") ?: (root["external_ids"] as? JsonObject)?.text("imdb_id"),
            knownFor = knownFor,
        )
    }

    /** Trakt comments for a title, most liked first. */
    suspend fun traktComments(clientId: String, kind: MediaKind, tmdbId: Int): List<TraktComment> {
        val type = if (kind == MediaKind.Movie) "movie" else "show"
        val search = request(
            "https://api.trakt.tv/search/tmdb/$tmdbId?type=$type",
            traktHeaders(clientId),
        ) as? JsonArray ?: return emptyList()
        val entry = (search.firstOrNull() as? JsonObject)?.get(type) as? JsonObject ?: return emptyList()
        val traktId = ((entry["ids"] as? JsonObject)?.get("trakt") as? JsonPrimitive)?.intOrNull
            ?: return emptyList()
        val path = if (kind == MediaKind.Movie) "movies" else "shows"
        val comments = request(
            "https://api.trakt.tv/$path/$traktId/comments/likes?limit=$MAX_COMMENTS",
            traktHeaders(clientId),
        ) as? JsonArray ?: return emptyList()
        return comments.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            TraktComment(
                user = ((item["user"] as? JsonObject)?.text("username")) ?: "Trakt",
                text = item.text("comment") ?: return@mapNotNull null,
                rating = ((item["user_stats"] as? JsonObject)?.get("rating") as? JsonPrimitive)?.intOrNull,
                likes = item.int("likes") ?: 0,
                spoiler = (item["spoiler"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }

    private fun traktHeaders(clientId: String) = mapOf(
        "trakt-api-version" to "2",
        "trakt-api-key" to clientId,
        "Content-Type" to "application/json",
    )

    private fun JsonElement.toTitle(kind: MediaKind): TmdbTitle? {
        val item = this as? JsonObject ?: return null
        val id = item.int("id") ?: return null
        val title = item.text("title") ?: item.text("name") ?: return null
        val date = item.text("release_date") ?: item.text("first_air_date")
        return TmdbTitle(
            id = id,
            kind = kind,
            title = title,
            overview = item.text("overview"),
            poster = poster(item.text("poster_path")),
            backdrop = backdrop(item.text("backdrop_path")),
            year = date?.take(4),
            rating = (item["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 },
        )
    }

    private suspend fun get(key: String, path: String, params: List<Pair<String, String>> = emptyList()): JsonElement? {
        // A v4 "read access token" is a JWT (starts eyJ); a v3 API key is 32 hex characters.
        val bearer = key.startsWith("eyJ")
        val query = buildList {
            addAll(params)
            add("language" to Locale.getDefault().toLanguageTag())
            if (!bearer) add("api_key" to key)
        }.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        return request("$BASE$path?$query", headers)
    }

    private suspend fun request(url: String, headers: Map<String, String>): JsonElement? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                }
                val code = connection.responseCode
                if (code !in 200..299) throw TmdbException(code)
                json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: TmdbException) {
                throw e
            } catch (e: Exception) {
                throw TmdbException(null)
            } finally {
                connection?.disconnect()
            }
        }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private const val MAX_CAST = 24
    private const val MAX_KNOWN_FOR = 30
    private const val MAX_COMMENTS = 10
}
