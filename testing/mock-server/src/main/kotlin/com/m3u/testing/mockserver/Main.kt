package com.m3u.testing.mockserver

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import java.awt.Color as AwtColor

private const val DEFAULT_HOST = "0.0.0.0"
private const val DEFAULT_PORT = 8080
private const val DEFAULT_USERNAME = "m3u"
private const val DEFAULT_PASSWORD = "m3u"
private const val EMBY_ACCESS_TOKEN = "mock-emby-access-token"
private const val REFERENCE_PASSWORD = "reference-password"
private const val REFERENCE_ACCESS_TOKEN = "mock-reference-access-token"
private const val REFERENCE_SERVER_ID = "reference-server-id"
private const val REFERENCE_USER_ID = "reference-user-id"

private val referenceChannelIds = setOf("reference.news", "reference.sports")
private val referenceSessions = ConcurrentHashMap<String, ReferenceSession>()

private val json = Json {
    prettyPrint = true
    explicitNulls = false
}

fun main(args: Array<String>) {
    // Poster images are drawn with java.awt, which needs no display in headless mode.
    System.setProperty("java.awt.headless", "true")
    val options = ServerOptions.parse(args)
    embeddedServer(
        factory = Netty,
        host = options.host,
        port = options.port,
        module = Application::mockServerModule
    ).start(wait = true)
}

internal fun Application.mockServerModule() {
    routing {
        get("/") {
            val baseUrl = call.baseUrl()
            call.respondText(
                text = endpointIndex(baseUrl),
                contentType = ContentType.Application.Json
            )
        }

        get("/health") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        // XMLTV guide for the channels in /playlist/live.m3u (tvg-id mock.news, mock.sports,
        // mock.kids), twelve hours around the current time.
        get("/epg.xml") {
            call.respondText(xmltvGuide(), ContentType.Application.Xml)
        }

        // Logos and posters referenced by the playlists and Xtream streams (news.png, movie.png…).
        get("/images/{name}.png") {
            call.respondBytes(
                bytes = posterPng(call.parameters["name"].orEmpty()),
                contentType = ContentType.Image.PNG
            )
        }

        post("/reference-provider/login") {
            val body = runCatching {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            }.getOrNull()
            if (body == null || body.keys != setOf("username", "password")) {
                call.respond(HttpStatusCode.BadRequest, "invalid reference login payload")
                return@post
            }
            val username = body["username"]?.jsonPrimitive?.content
            val password = body["password"]?.jsonPrimitive?.content
            if (username != DEFAULT_USERNAME || password != REFERENCE_PASSWORD) {
                call.respond(HttpStatusCode.Unauthorized, "invalid reference credentials")
                return@post
            }
            call.respondText(
                text = json.encodeToString(referenceLoginResponse()),
                contentType = ContentType.Application.Json,
            )
        }

        get("/reference-provider/channels") {
            if (
                !call.referenceAuthenticated() ||
                call.request.headers["X-Reference-User"] != REFERENCE_USER_ID
            ) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@get
            }
            call.respondText(
                text = json.encodeToString(referenceChannels()),
                contentType = ContentType.Application.Json,
            )
        }

        get("/reference-provider/playback/{item}") {
            if (!call.referenceAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@get
            }
            val itemId = call.parameters["item"].orEmpty()
            if (itemId !in referenceChannelIds) {
                call.respond(HttpStatusCode.NotFound, "unknown reference channel")
                return@get
            }
            val session = ReferenceSession(
                itemId = itemId,
                playSessionId = "reference-play-session-$itemId",
                liveStreamId = "reference-live-stream-$itemId",
                closed = false,
            )
            referenceSessions[session.playSessionId] = session
            call.respondText(
                text = json.encodeToString(referencePlayback(call.baseUrl(), session)),
                contentType = ContentType.Application.Json,
            )
        }

        post("/reference-provider/sessions/close") {
            if (!call.referenceAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@post
            }
            val body = runCatching {
                Json.parseToJsonElement(call.receiveText()).jsonObject
            }.getOrNull()
            val expectedKeys = setOf(
                "item_id",
                "play_session_id",
                "live_stream_id",
                "reason",
            )
            if (body == null || body.keys != expectedKeys) {
                call.respond(HttpStatusCode.BadRequest, "invalid reference close payload")
                return@post
            }
            val itemId = body["item_id"]?.jsonPrimitive?.content.orEmpty()
            val playSessionId = body["play_session_id"]?.jsonPrimitive?.content.orEmpty()
            val liveStreamId = body["live_stream_id"]?.jsonPrimitive?.content.orEmpty()
            val reason = body["reason"]?.jsonPrimitive?.content.orEmpty()
            val session = referenceSessions[playSessionId]
            if (
                session == null ||
                session.itemId != itemId ||
                session.liveStreamId != liveStreamId ||
                reason.isBlank()
            ) {
                call.respond(HttpStatusCode.NotFound, "reference session was not found")
                return@post
            }
            referenceSessions[playSessionId] = session.copy(closed = true)
            call.respondText(
                text = json.encodeToString(buildJsonObject { put("closed", true) }),
                contentType = ContentType.Application.Json,
            )
        }

        get("/reference-provider/sessions/{session}") {
            if (!call.referenceAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@get
            }
            val playSessionId = call.parameters["session"].orEmpty()
            val session = referenceSessions[playSessionId]
            if (session == null) {
                call.respond(HttpStatusCode.NotFound, "reference session was not found")
                return@get
            }
            call.respondText(
                text = json.encodeToString(referenceSessionState(session)),
                contentType = ContentType.Application.Json,
            )
        }

        get("/reference-provider/stream/{item}/index.m3u8") {
            if (!call.referenceAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@get
            }
            val itemId = call.parameters["item"].orEmpty()
            if (itemId !in referenceChannelIds) {
                call.respond(HttpStatusCode.NotFound, "unknown reference channel")
                return@get
            }
            call.respondText(
                text = hlsPlaylist(itemId),
                contentType = ContentType.parse("application/vnd.apple.mpegurl"),
            )
        }

        get("/reference-provider/stream/{item}/segment-{number}.ts") {
            if (!call.referenceAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing reference provider token")
                return@get
            }
            val itemId = call.parameters["item"].orEmpty()
            val number = call.parameters["number"]?.toIntOrNull()
            if (itemId !in referenceChannelIds || number == null) {
                call.respond(HttpStatusCode.NotFound, "unknown reference stream segment")
                return@get
            }
            call.respondBytes(
                bytes = transportStreamPlaceholder(itemId, number),
                contentType = ContentType.parse("video/mp2t"),
            )
        }

        get("/playlist/live.m3u") {
            call.respondText(
                text = livePlaylist(call.baseUrl()),
                contentType = ContentType.parse("audio/x-mpegurl")
            )
        }

        get("/playlist/mixed.m3u") {
            call.respondText(
                text = mixedPlaylist(call.baseUrl()),
                contentType = ContentType.parse("audio/x-mpegurl")
            )
        }

        get("/hls/{channel}/index.m3u8") {
            val channel = call.parameters["channel"].orEmpty()
            call.respondText(
                text = hlsPlaylist(channel),
                contentType = ContentType.parse("application/vnd.apple.mpegurl")
            )
        }

        get("/hls/{channel}/segment-{number}.ts") {
            val channel = call.parameters["channel"].orEmpty()
            val number = call.parameters["number"]?.toIntOrNull() ?: 0
            call.respondBytes(
                bytes = transportStreamPlaceholder(channel, number),
                contentType = ContentType.parse("video/mp2t")
            )
        }

        get("/System/Info/Public") {
            call.respondText(
                text = json.encodeToString(embySystemInfo()),
                contentType = ContentType.Application.Json
            )
        }

        post("/Users/AuthenticateByName") {
            if (!call.embyIdentityAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "invalid Emby authorization")
                return@post
            }
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val username = body["Username"]?.jsonPrimitive?.content
            val password = body["Pw"]?.jsonPrimitive?.content
            if (username != DEFAULT_USERNAME || password != DEFAULT_PASSWORD) {
                call.respond(HttpStatusCode.Unauthorized, "invalid media server credentials")
                return@post
            }
            call.respondText(
                text = json.encodeToString(embyAuthentication()),
                contentType = ContentType.Application.Json
            )
        }

        get("/LiveTv/Channels") {
            if (!call.embyAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@get
            }
            call.respondText(
                text = json.encodeToString(embyLiveTvChannels()),
                contentType = ContentType.Application.Json
            )
        }

        get("/Items/{item}/PlaybackInfo") {
            if (!call.embyAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@get
            }
            val itemId = call.parameters["item"].orEmpty()
            call.respondText(
                text = json.encodeToString(embyPlaybackInfo(call.baseUrl(), itemId)),
                contentType = ContentType.Application.Json
            )
        }

        get("/emby-stream/{item}/index.m3u8") {
            if (!call.embyAuthenticated() || call.request.headers["X-Mock-Playback"] != "allowed") {
                call.respond(HttpStatusCode.Unauthorized, "missing playback headers")
                return@get
            }
            call.respondText(
                text = hlsPlaylist(call.parameters["item"].orEmpty()),
                contentType = ContentType.parse("application/vnd.apple.mpegurl")
            )
        }

        post("/Sessions/Playing/Stopped") {
            if (!call.embyAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/LiveStreams/Close") {
            if (!call.embyAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/jellyfin/System/Info/Public") {
            call.respondText(
                text = json.encodeToString(jellyfinSystemInfo()),
                contentType = ContentType.Application.Json
            )
        }

        post("/jellyfin/Users/AuthenticateByName") {
            if (!call.jellyfinIdentityAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "deprecated media server authorization")
                return@post
            }
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val username = body["Username"]?.jsonPrimitive?.content
            val password = body["Pw"]?.jsonPrimitive?.content
            if (username != DEFAULT_USERNAME || password != DEFAULT_PASSWORD) {
                call.respond(HttpStatusCode.Unauthorized, "invalid media server credentials")
                return@post
            }
            call.respondText(
                text = json.encodeToString(embyAuthentication()),
                contentType = ContentType.Application.Json
            )
        }

        get("/jellyfin/LiveTv/Channels") {
            if (!call.jellyfinAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@get
            }
            call.respondText(
                text = json.encodeToString(embyLiveTvChannels()),
                contentType = ContentType.Application.Json
            )
        }

        get("/jellyfin/Items/{item}/PlaybackInfo") {
            if (!call.jellyfinAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@get
            }
            val itemId = call.parameters["item"].orEmpty()
            call.respondText(
                text = json.encodeToString(
                    embyPlaybackInfo("${call.baseUrl()}/jellyfin", itemId)
                ),
                contentType = ContentType.Application.Json
            )
        }

        get("/jellyfin/emby-stream/{item}/index.m3u8") {
            if (!call.jellyfinAuthenticated() || call.request.headers["X-Mock-Playback"] != "allowed") {
                call.respond(HttpStatusCode.Unauthorized, "missing playback headers")
                return@get
            }
            call.respondText(
                text = hlsPlaylist(call.parameters["item"].orEmpty()),
                contentType = ContentType.parse("application/vnd.apple.mpegurl")
            )
        }

        post("/jellyfin/Sessions/Playing/Stopped") {
            if (!call.jellyfinAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/jellyfin/LiveStreams/Close") {
            if (!call.jellyfinAuthenticated()) {
                call.respond(HttpStatusCode.Unauthorized, "missing media server token")
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/live/{username}/{password}/{stream}.ts") {
            val auth = call.xtreamAuth()
            if (!auth.valid) {
                call.respond(HttpStatusCode.Unauthorized, "invalid xtream credentials")
                return@get
            }
            call.respondBytes(
                bytes = transportStreamPlaceholder(call.parameters["stream"].orEmpty(), 1),
                contentType = ContentType.parse("video/mp2t")
            )
        }

        get("/movie/{username}/{password}/{stream}.mp4") {
            val auth = call.xtreamAuth()
            if (!auth.valid) {
                call.respond(HttpStatusCode.Unauthorized, "invalid xtream credentials")
                return@get
            }
            call.respondBytes(
                bytes = mp4Placeholder(call.parameters["stream"].orEmpty()),
                contentType = ContentType.Video.MP4
            )
        }

        get("/series/{username}/{password}/{episode}.mp4") {
            val auth = call.xtreamAuth()
            if (!auth.valid) {
                call.respond(HttpStatusCode.Unauthorized, "invalid xtream credentials")
                return@get
            }
            call.respondBytes(
                bytes = mp4Placeholder(call.parameters["episode"].orEmpty()),
                contentType = ContentType.Video.MP4
            )
        }

        get("/player_api.php") {
            val auth = call.xtreamAuth()
            if (!auth.valid) {
                call.respondText(
                    text = json.encodeToString(xtreamUnauthorized()),
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Unauthorized
                )
                return@get
            }

            val baseUrl = call.baseUrl()
            val action = call.request.queryParameters["action"]
            if (auth.big) {
                val jsonType = ContentType.Application.Json
                when (action) {
                    "get_live_categories" -> {
                        call.respondText(BigCatalogue.liveCategories(), jsonType)
                        return@get
                    }
                    "get_vod_categories" -> {
                        call.respondText(BigCatalogue.vodCategories(), jsonType)
                        return@get
                    }
                    "get_series_categories" -> {
                        call.respondText(BigCatalogue.seriesCategories(), jsonType)
                        return@get
                    }
                    "get_live_streams" -> {
                        call.respondTextWriter(jsonType) { BigCatalogue.writeLive(this, baseUrl) }
                        return@get
                    }
                    "get_vod_streams" -> {
                        delay(BigCatalogue.VOD_FIRST_BYTE_DELAY_MS)
                        call.respondTextWriter(jsonType) { BigCatalogue.writeVod(this, baseUrl) }
                        return@get
                    }
                    "get_series" -> {
                        call.respondTextWriter(jsonType) { BigCatalogue.writeSeries(this, baseUrl) }
                        return@get
                    }
                }
            }
            val payload = when (action) {
                null -> xtreamInfo(baseUrl)
                "get_live_categories" -> liveCategories()
                "get_live_streams" -> liveStreams(baseUrl)
                "get_vod_categories" -> vodCategories()
                "get_vod_streams" -> vodStreams(baseUrl)
                "get_series_categories" -> seriesCategories()
                "get_series" -> seriesStreams(baseUrl)
                "get_series_info" -> seriesInfo(
                    seriesId = call.request.queryParameters["series_id"]?.toIntOrNull() ?: 3001
                )
                "get_short_epg" -> epgListings(
                    streamId = call.request.queryParameters["stream_id"]?.toIntOrNull(),
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 4,
                )
                "get_simple_data_table" -> epgListings(
                    streamId = call.request.queryParameters["stream_id"]?.toIntOrNull(),
                    limit = null,
                )
                else -> JsonObject(emptyMap())
            }

            call.respondText(
                text = json.encodeToString(payload),
                contentType = ContentType.Application.Json
            )
        }
    }
}

private data class ServerOptions(
    val host: String,
    val port: Int
) {
    companion object {
        fun parse(args: Array<String>): ServerOptions {
            var host = DEFAULT_HOST
            var port = DEFAULT_PORT

            args.toList().windowed(size = 2, step = 1).forEach { (key, value) ->
                when (key) {
                    "--host" -> host = value
                    "--port" -> port = value.toInt()
                }
            }
            return ServerOptions(host, port)
        }
    }
}

private data class XtreamAuth(val valid: Boolean, val big: Boolean = false)

private data class ReferenceSession(
    val itemId: String,
    val playSessionId: String,
    val liveStreamId: String,
    val closed: Boolean,
)

private fun io.ktor.server.application.ApplicationCall.xtreamAuth(): XtreamAuth {
    val username = parameters["username"] ?: request.queryParameters["username"]
    val password = parameters["password"] ?: request.queryParameters["password"]
    val big = username == BIG_USERNAME && password == BIG_PASSWORD
    return XtreamAuth(
        valid = big || (username == DEFAULT_USERNAME && password == DEFAULT_PASSWORD),
        big = big,
    )
}

private fun io.ktor.server.application.ApplicationCall.baseUrl(): String {
    val forwardedProto = request.headers["X-Forwarded-Proto"]
    val scheme = forwardedProto ?: request.local.scheme
    val host = request.host()
    val port = request.port()
    val includePort = (scheme == "http" && port != 80) || (scheme == "https" && port != 443)
    return if (includePort) "$scheme://$host:$port" else "$scheme://$host"
}

private fun io.ktor.server.application.ApplicationCall.embyIdentityAuthenticated(): Boolean =
    request.headers["Authorization"]?.startsWith("Emby ") == true &&
        request.headers["X-Emby-Authorization"] == null

private fun io.ktor.server.application.ApplicationCall.embyAuthenticated(): Boolean =
    embyIdentityAuthenticated() && request.headers["X-Emby-Token"] == EMBY_ACCESS_TOKEN

private fun io.ktor.server.application.ApplicationCall.jellyfinIdentityAuthenticated(): Boolean =
    request.headers["Authorization"]?.startsWith("MediaBrowser ") == true &&
        request.headers["X-Emby-Authorization"] == null &&
        request.headers["X-Emby-Token"] == null

private fun io.ktor.server.application.ApplicationCall.jellyfinAuthenticated(): Boolean =
    jellyfinIdentityAuthenticated() &&
        "Token=\"$EMBY_ACCESS_TOKEN\"" in request.headers["Authorization"].orEmpty()

private fun io.ktor.server.application.ApplicationCall.referenceAuthenticated(): Boolean =
    request.headers["X-Emby-Token"] == REFERENCE_ACCESS_TOKEN

private fun endpointIndex(baseUrl: String): String = json.encodeToString(
    buildJsonObject {
        put("name", "M3U mock server")
        put("m3u_live", "$baseUrl/playlist/live.m3u")
        put("m3u_mixed", "$baseUrl/playlist/mixed.m3u")
        put("hls_sample", "$baseUrl/hls/news/index.m3u8")
        put("xtream", "$baseUrl/player_api.php?username=$DEFAULT_USERNAME&password=$DEFAULT_PASSWORD")
        put("emby", baseUrl)
        put("jellyfin", "$baseUrl/jellyfin")
        put("reference_provider", "$baseUrl/reference-provider")
    }
)

private fun referenceLoginResponse(): JsonObject = buildJsonObject {
    put("accessToken", REFERENCE_ACCESS_TOKEN)
    put("server_id", REFERENCE_SERVER_ID)
    put("server_name", "M3U Reference Provider")
    put("server_version", "1.0.0")
    put("user_id", REFERENCE_USER_ID)
    put("username", DEFAULT_USERNAME)
}

private fun referenceChannels(): JsonObject = buildJsonObject {
    put("source_id", REFERENCE_SERVER_ID)
    put("source_title", "Reference Live TV")
    put("revision", "1")
    putJsonArray("channels") {
        add(
            buildJsonObject {
                put("id", "reference.news")
                put("title", "Reference News")
                put("category", "News")
                put("epg_reference", "reference.news")
            }
        )
        add(
            buildJsonObject {
                put("id", "reference.sports")
                put("title", "Reference Sports")
                put("category", "Sports")
                put("epg_reference", "reference.sports")
            }
        )
    }
}

private fun referencePlayback(baseUrl: String, session: ReferenceSession): JsonObject =
    buildJsonObject {
        put(
            "url",
            "$baseUrl/reference-provider/stream/${session.itemId}/index.m3u8",
        )
        put("media_source_id", "reference-media-${session.itemId}")
        put("play_session_id", session.playSessionId)
        put("live_stream_id", session.liveStreamId)
    }

private fun referenceSessionState(session: ReferenceSession): JsonObject = buildJsonObject {
    put("item_id", session.itemId)
    put("play_session_id", session.playSessionId)
    put("live_stream_id", session.liveStreamId)
    put("state", if (session.closed) "closed" else "open")
}

private fun embySystemInfo(): JsonObject = buildJsonObject {
    put("Id", "mock-server-id")
    put("ServerName", "M3U Mock Emby")
    put("Version", "4.9.0.0")
    put("ProductName", "Emby Server")
}

private fun jellyfinSystemInfo(): JsonObject = buildJsonObject {
    put("Id", "mock-server-id")
    put("ServerName", "M3U Mock Jellyfin")
    put("Version", "10.11.0")
    put("ProductName", "Jellyfin Server")
}

private fun embyAuthentication(): JsonObject = buildJsonObject {
    put("AccessToken", EMBY_ACCESS_TOKEN)
    put("ServerId", "mock-server-id")
    putJsonObject("User") {
        put("Id", "mock-user-id")
        put("Name", DEFAULT_USERNAME)
    }
}

private fun embyLiveTvChannels(): JsonObject = buildJsonObject {
    putJsonArray("Items") {
        add(
            buildJsonObject {
                put("Id", "mock.news")
                put("Name", "Mock News")
                put("ChannelNumber", "1")
                put("ChannelType", "TV")
                put("MediaType", "Video")
                put("PrimaryImageTag", "news-image")
            }
        )
        add(
            buildJsonObject {
                put("Id", "mock.sports")
                put("Name", "Mock Sports")
                put("ChannelNumber", "2")
                put("ChannelType", "TV")
                put("MediaType", "Video")
                put("PrimaryImageTag", "sports-image")
            }
        )
    }
    put("TotalRecordCount", 2)
}

private fun embyPlaybackInfo(baseUrl: String, itemId: String): JsonObject = buildJsonObject {
    put("PlaySessionId", "mock-play-session-$itemId")
    putJsonArray("MediaSources") {
        add(
            buildJsonObject {
                put("Id", "mock-media-source-$itemId")
                put("Path", "$baseUrl/emby-stream/$itemId/index.m3u8")
                put("LiveStreamId", "mock-live-stream-$itemId")
                putJsonObject("RequiredHttpHeaders") {
                    put("X-Mock-Playback", "allowed")
                }
            }
        )
    }
}

private fun livePlaylist(baseUrl: String): String = """
    #EXTM3U
    #EXTINF:-1 tvg-id="mock.news" tvg-name="Mock News" tvg-logo="$baseUrl/images/news.png" group-title="News",Mock News
    $baseUrl/hls/news/index.m3u8
    #EXTINF:-1 tvg-id="mock.sports" tvg-name="Mock Sports" tvg-logo="$baseUrl/images/sports.png" group-title="Sports",Mock Sports
    $baseUrl/hls/sports/index.m3u8
    #EXTINF:-1 tvg-id="mock.kids" tvg-name="Mock Kids" tvg-logo="$baseUrl/images/kids.png" group-title="Kids",Mock Kids
    $baseUrl/hls/kids/index.m3u8
""".trimIndent()

private fun mixedPlaylist(baseUrl: String): String = """
    #EXTM3U
    #EXTINF:-1 tvg-id="mock.news" tvg-name="Mock News" tvg-logo="$baseUrl/images/news.png" group-title="Live",Mock News
    $baseUrl/hls/news/index.m3u8
    #EXTINF:600 tvg-id="mock.movie" tvg-name="Mock Movie" tvg-logo="$baseUrl/images/movie.png" group-title="VOD",Mock Movie
    $baseUrl/movie/$DEFAULT_USERNAME/$DEFAULT_PASSWORD/2001.mp4
    #EXTINF:1200 tvg-id="mock.episode" tvg-name="Mock Episode" tvg-logo="$baseUrl/images/series.png" group-title="Series",Mock Series S01E01
    $baseUrl/series/$DEFAULT_USERNAME/$DEFAULT_PASSWORD/9001.mp4
""".trimIndent()

private fun hlsPlaylist(channel: String): String = """
    #EXTM3U
    #EXT-X-VERSION:3
    #EXT-X-TARGETDURATION:6
    #EXT-X-MEDIA-SEQUENCE:1
    #EXTINF:6.000,
    segment-1.ts
    #EXTINF:6.000,
    segment-2.ts
    #EXTINF:6.000,
    segment-3.ts
    #EXT-X-DISCONTINUITY
    #EXTINF:6.000,
    segment-4.ts
    #EXT-X-ENDLIST
    # $channel
""".trimIndent()

private fun xtreamInfo(baseUrl: String): JsonObject = buildJsonObject {
    putJsonObject("user_info") {
        put("username", DEFAULT_USERNAME)
        put("password", DEFAULT_PASSWORD)
        put("status", "Active")
        put("auth", 1)
        put("active_cons", "0")
        put("max_connections", "3")
        put("created_at", "1704067200")
        put("is_trial", "0")
        putJsonArray("allowed_output_formats") {
            add(JsonPrimitive("ts"))
            add(JsonPrimitive("m3u8"))
            add(JsonPrimitive("mp4"))
        }
    }
    putJsonObject("server_info") {
        put("url", baseUrl.removePrefix("http://").removePrefix("https://").substringBefore(":"))
        put("port", baseUrl.substringAfterLast(":", "8080"))
        put("server_protocol", baseUrl.substringBefore("://"))
        put("https_port", "8443")
        put("time_now", "2026-05-02 00:00:00")
        put("timestamp_now", "1777651200")
        put("timezone", "Asia/Shanghai")
    }
}

private fun xtreamUnauthorized(): JsonObject = buildJsonObject {
    putJsonObject("user_info") {
        put("auth", 0)
        put("status", "Disabled")
    }
}

private fun liveCategories(): JsonArray = categories(
    10 to "News",
    11 to "Sports",
    12 to "Kids"
)

private fun vodCategories(): JsonArray = categories(
    20 to "Movies",
    21 to "Documentaries"
)

private fun seriesCategories(): JsonArray = categories(
    30 to "Series",
    31 to "Learning"
)

private fun categories(vararg values: Pair<Int, String>): JsonArray = buildJsonArray {
    values.forEach { (id, name) ->
        add(
            buildJsonObject {
                put("category_id", id)
                put("category_name", name)
                put("parent_id", 0)
            }
        )
    }
}

private fun liveStreams(baseUrl: String): JsonArray = JsonArray(
    listOf(
        liveStream(1, 1001, 10, "Mock News", "$baseUrl/images/news.png", "mock.news"),
        liveStream(2, 1002, 11, "Mock Sports", "$baseUrl/images/sports.png", "mock.sports"),
        liveStream(3, 1003, 12, "Mock Kids", "$baseUrl/images/kids.png", "mock.kids")
    )
)

private fun vodStreams(baseUrl: String): JsonArray = JsonArray(
    listOf(
        vodStream(1, 2001, 20, "Mock Movie", "$baseUrl/images/movie.png"),
        vodStream(2, 2002, 21, "Mock Documentary", "$baseUrl/images/documentary.png")
    )
)

private fun seriesStreams(baseUrl: String): JsonArray = JsonArray(
    listOf(
        seriesStream(1, 3001, 30, "Mock Series", "$baseUrl/images/series.png"),
        seriesStream(2, 3002, 31, "Mock Course", "$baseUrl/images/course.png")
    )
)

private fun seriesInfo(seriesId: Int): JsonObject = buildJsonObject {
    putJsonObject("episodes") {
        put(
            "1",
            JsonArray(
                listOf(
                    episode(id = "9001", number = "1", title = "Pilot"),
                    episode(id = "9002", number = "2", title = "Second Source")
                )
            )
        )
        if (seriesId == 3002) {
            put("2", JsonArray(listOf(episode(id = "9010", number = "1", title = "Advanced Playback"))))
        }
    }
}

/** Programme names per live stream id, repeated through the day. */
private val epgTitles = mapOf(
    1001 to listOf("Morning Briefing", "World Desk", "Market Watch", "Evening Headlines", "Late Report"),
    1002 to listOf("Match Day Live", "Goals Extra", "Fight Night Preview", "Track and Field", "Classic Finals"),
    1003 to listOf("Cartoon Club", "Space Pals", "Science Lab", "Story Time", "Puzzle Quest"),
)

/** XMLTV for the M3U playlist's channels, reusing the Xtream schedule's titles and lengths. */
private fun xmltvGuide(): String {
    val channels = listOf(
        Triple("mock.news", "Mock News", 1001),
        Triple("mock.sports", "Mock Sports", 1002),
        Triple("mock.kids", "Mock Kids", 1003),
    )
    val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val nowSeconds = System.currentTimeMillis() / 1000
    val hour = 3600L
    val firstStart = nowSeconds - nowSeconds % hour - 3 * hour
    val lastEnd = firstStart + 12 * hour
    return buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("<tv generator-info-name=\"m3u-mock-server\">\n")
        channels.forEach { (id, name, _) ->
            append("  <channel id=\"").append(id).append("\"><display-name>")
                .append(name).append("</display-name></channel>\n")
        }
        channels.forEach { (id, _, streamId) ->
            val titles = epgTitles.getValue(streamId)
            var start = firstStart
            var index = 0
            while (start < lastEnd) {
                val end = start + epgLengths[(index + streamId) % epgLengths.size] * 60L
                append("  <programme start=\"").append(format.format(Date(start * 1000)))
                    .append("\" stop=\"").append(format.format(Date(end * 1000)))
                    .append("\" channel=\"").append(id).append("\"><title>")
                    .append(titles[index % titles.size]).append("</title><desc>")
                    .append(titles[index % titles.size]).append(" from the mock XMLTV guide.")
                    .append("</desc></programme>\n")
                start = end
                index++
            }
        }
        append("</tv>\n")
    }
}

/** Schedule for the "big" account's channels. */
private val bigEpgTitles = listOf("Breakfast Show", "Headlines", "Live Match", "Film Club", "Late Talk", "Documentary")

/** Programme lengths in minutes, cycled so the guide shows blocks of different widths. */
private val epgLengths = listOf(30, 60, 30, 90, 60)

/**
 * Xtream EPG listings (get_short_epg / get_simple_data_table) built around the current time:
 * from three hours ago to nine hours ahead, with base64 titles as real panels send them. Past
 * programmes are marked as replayable. [limit] keeps only the current and next ones.
 */
private fun epgListings(streamId: Int?, limit: Int?): JsonObject {
    val titles = epgTitles[streamId]
        ?: if (BigCatalogue.isBigLiveStream(streamId)) bigEpgTitles else emptyList()
    val nowSeconds = System.currentTimeMillis() / 1000
    val hour = 3600L
    val firstStart = nowSeconds - nowSeconds % hour - 3 * hour
    val lastEnd = firstStart + 12 * hour
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val encoder = Base64.getEncoder()
    val listings = mutableListOf<JsonObject>()
    var start = firstStart
    var index = 0
    while (titles.isNotEmpty() && start < lastEnd) {
        val end = start + epgLengths[(index + (streamId ?: 0)) % epgLengths.size] * 60L
        val title = titles[index % titles.size]
        if (limit == null || end > nowSeconds) {
            listings += buildJsonObject {
                put("id", "${streamId}-$index")
                put("epg_id", "mock-$streamId")
                put("title", encoder.encodeToString(title.encodeToByteArray()))
                put("lang", "en")
                put("start", format.format(Date(start * 1000)))
                put("end", format.format(Date(end * 1000)))
                put(
                    "description",
                    encoder.encodeToString("$title on the mock server's schedule.".encodeToByteArray()),
                )
                put("channel_id", "mock-$streamId")
                put("start_timestamp", start.toString())
                put("stop_timestamp", end.toString())
                put("now_playing", if (nowSeconds in start until end) 1 else 0)
                put("has_archive", if (end <= nowSeconds) 1 else 0)
            }
        }
        start = end
        index++
    }
    val shown = if (limit != null) listings.take(limit) else listings
    return buildJsonObject {
        putJsonArray("epg_listings") {
            shown.forEach { add(it) }
        }
    }
}

private fun liveStream(
    number: Int,
    streamId: Int,
    categoryId: Int,
    name: String,
    icon: String,
    epgId: String
) = buildJsonObject {
    put("num", number)
    put("name", name)
    put("stream_type", "live")
    put("stream_id", streamId)
    put("stream_icon", icon)
    put("epg_channel_id", epgId)
    put("category_id", categoryId)
    put("tv_archive", 0)
    put("tv_archive_duration", 0)
}

private fun vodStream(
    number: Int,
    streamId: Int,
    categoryId: Int,
    name: String,
    icon: String
) = buildJsonObject {
    put("num", number)
    put("name", name)
    put("stream_type", "movie")
    put("stream_id", streamId)
    put("stream_icon", icon)
    put("category_id", categoryId)
    put("container_extension", "mp4")
    put("rating", "7.8")
}

private fun seriesStream(
    number: Int,
    seriesId: Int,
    categoryId: Int,
    name: String,
    cover: String
) = buildJsonObject {
    put("num", number)
    put("name", name)
    put("series_id", seriesId)
    put("cover", cover)
    put("category_id", categoryId)
    put("episode_run_time", "42")
}

private fun episode(
    id: String,
    number: String,
    title: String
) = buildJsonObject {
    put("id", id)
    put("episode_num", number)
    put("title", title)
    put("container_extension", "mp4")
}

/** Gradient pairs for generated posters, picked by the image name so each one stays the same. */
private val posterPalettes = listOf(
    0x12C2E9 to 0x6A3093,
    0xF64F59 to 0x2B1055,
    0x00F5A0 to 0x00416A,
    0xFDC830 to 0xC0392B,
    0xC471ED to 0x12C2E9,
    0xFF6FD8 to 0x3813C2,
)

/**
 * A 400×600 PNG poster: a diagonal gradient with the image name written large, so screens that
 * show logos and posters have something to show.
 */
private fun posterPng(name: String): ByteArray {
    val width = 400
    val height = 600
    val (top, bottom) = posterPalettes[Math.floorMod(name.hashCode(), posterPalettes.size)]
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        graphics.paint = GradientPaint(
            0f, 0f, AwtColor(top),
            width.toFloat(), height.toFloat(), AwtColor(bottom)
        )
        graphics.fillRect(0, 0, width, height)
        graphics.color = AwtColor(255, 255, 255, 40)
        graphics.fillOval(-120, height - 300, 420, 420)
        graphics.fillOval(width - 180, -90, 300, 300)
        // Fonts can be missing on a bare server; the gradient alone is still a usable poster.
        runCatching {
            val label = name.replace('-', ' ').replace('_', ' ').uppercase()
            graphics.color = AwtColor.WHITE
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 52)
            val metrics = graphics.fontMetrics
            val x = ((width - metrics.stringWidth(label)) / 2).coerceAtLeast(16)
            graphics.drawString(label, x, height / 2 + metrics.ascent / 2)
        }
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { output ->
        ImageIO.write(image, "png", output)
        output.toByteArray()
    }
}

private fun transportStreamPlaceholder(channel: String, number: Int): ByteArray {
    val payload = "M3U mock transport stream: channel=$channel segment=$number\n".encodeToByteArray()
    return ByteArray(188 * 8) { index ->
        when {
            index % 188 == 0 -> 0x47
            index - 4 in payload.indices -> payload[index - 4]
            else -> 0xFF.toByte()
        }
    }
}

/**
 * Films and episodes: a real video file when MOCK_SAMPLE_VIDEO names one (the emulator check makes
 * a short clip with ffmpeg, so playback itself gets tested), otherwise a small placeholder.
 */
private val sampleVideo: ByteArray? by lazy {
    System.getenv("MOCK_SAMPLE_VIDEO")
        ?.let(::File)
        ?.takeIf { it.isFile }
        ?.readBytes()
}

private fun mp4Placeholder(id: String): ByteArray =
    sampleVideo ?: "M3U mock MP4 placeholder: id=$id\n".encodeToByteArray()
