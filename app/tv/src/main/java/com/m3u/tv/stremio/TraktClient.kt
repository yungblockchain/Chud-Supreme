package com.m3u.tv.stremio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/** Device-code Trakt. The person creates their own app at trakt.tv. Nothing is baked in. */
internal object TraktClient {
    private const val BASE = "https://api.trakt.tv"

    data class Device(val deviceCode: String, val userCode: String, val url: String, val intervalSeconds: Int)
    data class Session(val access: String, val refresh: String)

    suspend fun device(clientId: String): Device {
        val root = post("/oauth/device/code", clientId, null, """{"client_id":${json(clientId)}}""")
        return Device(
            deviceCode = root.text("device_code") ?: throw DebridException("Trakt didn't give a device code"),
            userCode = root.text("user_code") ?: throw DebridException("Trakt didn't give a code"),
            url = root.text("verification_url") ?: "https://trakt.tv/activate",
            intervalSeconds = root.int("interval") ?: 5,
        )
    }

    /** Null while the person hasn't approved yet. */
    suspend fun poll(clientId: String, clientSecret: String, deviceCode: String): Session? {
        try {
            val root = post(
                "/oauth/device/token",
                clientId,
                null,
                buildJsonObject {
                    put("code", JsonPrimitive(deviceCode))
                    put("client_id", JsonPrimitive(clientId))
                    put("client_secret", JsonPrimitive(clientSecret))
                }.toString(),
            )
            val access = root.text("access_token") ?: return null
            return Session(access, root.text("refresh_token").orEmpty())
        } catch (error: StremioHttpException) {
            val text = error.message.orEmpty().lowercase()
            if (error.code == 400 && ("pending" in text || "slow" in text)) return null
            if ("expired" in text || "denied" in text) throw DebridException("That Trakt code expired. Connect again.")
            throw error
        }
    }

    suspend fun refresh(clientId: String, clientSecret: String, refresh: String): Session {
        val root = post(
            "/oauth/token",
            clientId,
            null,
            buildJsonObject {
                put("refresh_token", JsonPrimitive(refresh))
                put("client_id", JsonPrimitive(clientId))
                put("client_secret", JsonPrimitive(clientSecret))
                put("grant_type", JsonPrimitive("refresh_token"))
                put("redirect_uri", JsonPrimitive("urn:ietf:wg:oauth:2.0:oob"))
            }.toString(),
        )
        return Session(
            root.text("access_token") ?: throw DebridException("Trakt didn't refresh"),
            root.text("refresh_token") ?: refresh,
        )
    }

    suspend fun username(clientId: String, access: String): String? {
        val root = get("/users/settings", clientId, access)
        return (root["user"] as? JsonObject)?.text("username")
    }

    suspend fun watchlist(clientId: String, access: String): List<CatalogItem> {
        val rows = getArray("/sync/watchlist", clientId, access)
        return rows.mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            titleItem(item, item.text("type"))
        }
    }

    suspend fun playback(clientId: String, access: String): List<CatalogItem> {
        val rows = getArray("/sync/playback", clientId, access)
        return rows.mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            val progress = (item["progress"] as? JsonPrimitive)?.contentOrNull
            titleItem(item, item.text("type"), progress?.let { "${it.substringBefore('.')}%" })
        }
    }

    suspend fun upcoming(clientId: String, access: String, start: String): List<CatalogItem> {
        val rows = getArray("/calendars/my/shows/$start/14", clientId, access)
        return rows.mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            val show = item["show"] as? JsonObject ?: return@mapNotNull null
            val episode = item["episode"] as? JsonObject ?: return@mapNotNull null
            val imdb = (show["ids"] as? JsonObject)?.text("imdb") ?: return@mapNotNull null
            val season = episode.int("season") ?: return@mapNotNull null
            val number = episode.int("number") ?: return@mapNotNull null
            CatalogItem(
                id = "$imdb:$season:$number",
                type = "series",
                name = listOfNotNull(
                    show.text("title"),
                    "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}",
                    episode.text("title"),
                ).joinToString(" · "),
                poster = null,
                background = null,
                posterShape = null,
                releaseInfo = item.text("first_aired")?.take(10),
                imdbRating = null,
                description = episode.text("overview"),
            )
        }
    }

    suspend fun scrobble(clientId: String, access: String, relation: String, start: Boolean) {
        val parts = relation.split(':')
        val imdb = parts.getOrNull(1)?.takeIf { it.startsWith("tt") } ?: return
        val body = if (parts.first() == "movie") {
            """{"movie":{"ids":{"imdb":"$imdb"}},"progress":1,"app_version":"chud-supreme"}"""
        } else {
            val season = parts.getOrNull(2)?.toIntOrNull() ?: return
            val episode = parts.getOrNull(3)?.toIntOrNull() ?: return
            """{"show":{"ids":{"imdb":"$imdb"}},"episode":{"season":$season,"number":$episode},"progress":${if (start) 1 else 90},"app_version":"chud-supreme"}"""
        }
        post(if (start) "/scrobble/start" else "/scrobble/stop", clientId, access, body)
    }

    private fun titleItem(item: JsonObject, type: String?, extra: String? = null): CatalogItem? {
        val node = (item["movie"] ?: item["show"] ?: item["episode"]) as? JsonObject ?: return null
        val show = item["show"] as? JsonObject
        val ids = node["ids"] as? JsonObject
        val imdb = ids?.text("imdb") ?: (show?.get("ids") as? JsonObject)?.text("imdb") ?: return null
        val name = when (type) {
            "episode" -> listOfNotNull(show?.text("title"), node.text("title")).joinToString(" · ")
            else -> node.text("title") ?: return null
        }
        if (name.isBlank()) return null
        val id = if (type == "episode") {
            val season = node.int("season")
            val number = node.int("number")
            if (season != null && number != null) "$imdb:$season:$number" else imdb
        } else {
            imdb
        }
        return CatalogItem(
            id = id,
            type = if (type == "movie") "movie" else "series",
            name = listOfNotNull(name, extra).joinToString(" · "),
            poster = null,
            background = null,
            posterShape = null,
            releaseInfo = node.text("year") ?: item.text("paused_at")?.take(10),
            imdbRating = null,
            description = null,
        )
    }

    private suspend fun get(path: String, clientId: String, access: String): JsonObject =
        StremioHttp.getJson("$BASE$path", headers(clientId, access)).asObject() ?: JsonObject(emptyMap())

    private suspend fun getArray(path: String, clientId: String, access: String): JsonArray =
        StremioHttp.getJson("$BASE$path", headers(clientId, access)) as? JsonArray ?: JsonArray(emptyList())

    private suspend fun post(path: String, clientId: String, access: String?, body: String): JsonObject =
        StremioHttp.postJson("$BASE$path", headers(clientId, access), body).asObject() ?: JsonObject(emptyMap())

    private fun headers(clientId: String, access: String?): Map<String, String> = buildMap {
        put("trakt-api-version", "2")
        put("trakt-api-key", clientId)
        put("Content-Type", "application/json")
        if (!access.isNullOrBlank()) put("Authorization", "Bearer $access")
    }

    private fun json(value: String): String = JsonPrimitive(value).toString()
}
