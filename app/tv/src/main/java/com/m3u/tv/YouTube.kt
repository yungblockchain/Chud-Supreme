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
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

/* -------------------------------------------------------------------------------------------------
 * YouTube without the YouTube app: what's live and trending, music, gaming, trailers and podcasts,
 * search, the channels the person follows and what they've watched here. Pages come from NewPipe's
 * extractor (the open-source engine behind the NewPipe app), so there is no API quota to run out
 * of, and videos play in the app's own player: the extractor hands over the direct streams, which
 * are wrapped in a small DASH manifest served from the Fire TV itself. SponsorBlock's crowd-sourced
 * segments are skipped as the video plays. If YouTube changes something and a video can't be
 * opened here, it falls back to the YouTube app.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class YouTubeVideo(
    val id: String,
    val title: String,
    val channel: String?,
    val channelUrl: String? = null,
    val thumbnail: String?,
    val durationSec: Long = 0L,
    val live: Boolean = false,
    val views: Long = -1L,
    val uploaded: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

@Immutable
data class YouTubeChannel(val id: String, val name: String, val avatar: String?)

/** A stretch of a video SponsorBlock says to jump over. */
@Immutable
data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

/** The sections of the YouTube tab. */
sealed interface YtSection {
    val key: String

    data object Live : YtSection { override val key = "live" }
    data object Trending : YtSection { override val key = "trending" }
    data object Music : YtSection { override val key = "music" }
    data object Gaming : YtSection { override val key = "gaming" }
    data object Trailers : YtSection { override val key = "trailers" }
    data object Podcasts : YtSection { override val key = "podcasts" }
    data object History : YtSection { override val key = "history" }
    data object Search : YtSection { override val key = "search" }
    data object Options : YtSection { override val key = "options" }
    data class Followed(val channel: YouTubeChannel) : YtSection { override val key = "channel:${channel.id}" }
}

val SPONSORBLOCK_CATEGORIES: List<String> =
    listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic", "filler")

@Immutable
data class YouTubePrefs(
    /** Play in the app's own player (else straight in the YouTube app). */
    val playInApp: Boolean = true,
    val sponsorBlock: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    /** The most-watched quality cap: 1080 keeps Fire TV decoders comfortable. */
    val maxHeight: Int = 1080,
)

/* ----------------------------------------------------------------------------------- store */

@Singleton
class YouTubeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(readPrefs())
    val preferences: StateFlow<YouTubePrefs> = _prefs.asStateFlow()

    private val _followed = MutableStateFlow(readChannels())
    val followed: StateFlow<List<YouTubeChannel>> = _followed.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    /** Videos played here, newest first. */
    val history: StateFlow<List<YouTubeVideo>> = _history.asStateFlow()

    fun update(transform: (YouTubePrefs) -> YouTubePrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        prefs.edit()
            .putBoolean(KEY_IN_APP, next.playInApp)
            .putBoolean(KEY_SPONSOR, next.sponsorBlock)
            .putString(KEY_SPONSOR_CATEGORIES, next.sponsorCategories.joinToString(","))
            .putInt(KEY_MAX_HEIGHT, next.maxHeight)
            .apply()
    }

    fun isFollowed(id: String): Boolean = _followed.value.any { it.id == id }

    fun toggleFollow(channel: YouTubeChannel) {
        val current = _followed.value
        val next = if (current.any { it.id == channel.id }) current.filterNot { it.id == channel.id } else (current + channel).take(MAX_FOLLOWED)
        _followed.value = next
        prefs.edit().putString(KEY_FOLLOWED, JsonArray(next.map { it.toJson() }).toString()).apply()
    }

    fun addToHistory(video: YouTubeVideo) {
        val next = (listOf(video) + _history.value.filterNot { it.id == video.id }).take(MAX_HISTORY)
        _history.value = next
        prefs.edit().putString(KEY_HISTORY, JsonArray(next.map { it.toJson() }).toString()).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /** Re-reads everything from disk (after a restore wrote the file directly). */
    fun reload() {
        _prefs.value = readPrefs()
        _followed.value = readChannels()
        _history.value = readHistory()
    }

    private fun readPrefs() = YouTubePrefs(
        playInApp = prefs.getBoolean(KEY_IN_APP, true),
        sponsorBlock = prefs.getBoolean(KEY_SPONSOR, true),
        sponsorCategories = prefs.getString(KEY_SPONSOR_CATEGORIES, null)
            ?.split(',')?.filter { it in SPONSORBLOCK_CATEGORIES }?.toSet()
            ?: YouTubePrefs().sponsorCategories,
        maxHeight = prefs.getInt(KEY_MAX_HEIGHT, 1080),
    )

    private fun readChannels(): List<YouTubeChannel> = runCatching {
        val raw = prefs.getString(KEY_FOLLOWED, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            YouTubeChannel(
                id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                avatar = item["avatar"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrDefault(emptyList())

    private fun readHistory(): List<YouTubeVideo> = runCatching {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonObject.toVideo() }
    }.getOrDefault(emptyList())

    private fun YouTubeChannel.toJson() = JsonObject(
        buildMap {
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            avatar?.let { put("avatar", JsonPrimitive(it)) }
        }
    )

    private companion object {
        const val KEY_IN_APP = "play_in_app"
        const val KEY_SPONSOR = "sponsorblock"
        const val KEY_SPONSOR_CATEGORIES = "sponsor_categories"
        const val KEY_MAX_HEIGHT = "max_height"
        const val KEY_FOLLOWED = "followed"
        const val KEY_HISTORY = "history"
        const val MAX_FOLLOWED = 60
        const val MAX_HISTORY = 40
    }
}

internal fun YouTubeVideo.toJson() = JsonObject(
    buildMap {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        channel?.let { put("channel", JsonPrimitive(it)) }
        channelUrl?.let { put("channelUrl", JsonPrimitive(it)) }
        thumbnail?.let { put("thumb", JsonPrimitive(it)) }
        put("duration", JsonPrimitive(durationSec))
        put("live", JsonPrimitive(live))
        put("views", JsonPrimitive(views))
        uploaded?.let { put("uploaded", JsonPrimitive(it)) }
    }
)

internal fun JsonObject.toVideo(): YouTubeVideo? = YouTubeVideo(
    id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null,
    title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null,
    channel = this["channel"]?.jsonPrimitive?.contentOrNull,
    channelUrl = this["channelUrl"]?.jsonPrimitive?.contentOrNull,
    thumbnail = this["thumb"]?.jsonPrimitive?.contentOrNull,
    durationSec = this["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
    live = this["live"]?.jsonPrimitive?.booleanOrNull ?: false,
    views = this["views"]?.jsonPrimitive?.longOrNull ?: -1L,
    uploaded = this["uploaded"]?.jsonPrimitive?.contentOrNull,
)

/* --------------------------------------------------------------------------------- client */

/** NewPipe's extractor, set up once, plus a small disk cache for the browse pages. */
object YouTubeClient {
    @Volatile private var ready = false
    private var cacheDir: File? = null
    private val lock = Any()

    fun init(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            val locale = Locale.getDefault()
            val language = locale.language.ifBlank { "en" }
            val country = locale.country.ifBlank { "GB" }
            NewPipe.init(
                HttpDownloader,
                Localization(language, country),
                ContentCountry(country),
            )
            cacheDir = File(context.cacheDir, "youtube").apply { mkdirs() }
            ready = true
        }
    }

    private val service get() = ServiceList.YouTube

    /** A kiosk page (live, trending, music…), from the cache when it's fresh. */
    suspend fun kiosk(id: String, maxAgeMs: Long = KIOSK_TTL_MS): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        cached("kiosk-$id", maxAgeMs) {
            val extractor = service.kioskList.getExtractorById(id, null)
            extractor.fetchPage()
            extractor.initialPage.items.mapNotNull { it.toVideo() }.distinctBy { it.id }
        }
    }

    suspend fun search(query: String): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { it.toVideo() }.distinctBy { it.id }
    }

    suspend fun searchChannels(query: String): List<YouTubeChannel> = withContext(Dispatchers.IO) {
        val extractor = service.getSearchExtractor(query, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
        extractor.fetchPage()
        extractor.initialPage.items.mapNotNull { item ->
            val channel = item as? ChannelInfoItem ?: return@mapNotNull null
            val id = runCatching { service.channelLHFactory.fromUrl(channel.url).id }.getOrNull() ?: return@mapNotNull null
            YouTubeChannel(id = id, name = channel.name, avatar = channel.thumbnails.bestUrl())
        }.distinctBy { it.id }
    }

    /** A channel's newest videos (cached for a while: the person opens the same channels often). */
    suspend fun channelVideos(channel: YouTubeChannel, maxAgeMs: Long = CHANNEL_TTL_MS): List<YouTubeVideo> = withContext(Dispatchers.IO) {
        cached("channel-${channel.id}", maxAgeMs) {
            val extractor = service.getChannelTabExtractorFromId(channel.id, ChannelTabs.VIDEOS)
            extractor.fetchPage()
            extractor.initialPage.items.mapNotNull { it.toVideo() }.distinctBy { it.id }
        }
    }

    /** The channel behind a video's channel link, so it can be followed. */
    suspend fun channelOf(video: YouTubeVideo): YouTubeChannel? = withContext(Dispatchers.IO) {
        val url = video.channelUrl ?: return@withContext null
        runCatching {
            val id = service.channelLHFactory.fromUrl(url).id
            YouTubeChannel(id = id, name = video.channel ?: id, avatar = null)
        }.getOrNull()
    }

    /** What plays for a video: the streams, wrapped for the player. */
    suspend fun resolve(videoId: String, maxHeight: Int): ResolvedVideo = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        val plan = if (!live) DashManifests.plan(info, maxHeight) else null
        val muxed = info.videoStreams
            .filter { it.isUrl && !it.isVideoOnly }
            .sortedByDescending { it.height }
            .firstOrNull { it.height <= maxHeight } ?: info.videoStreams.firstOrNull { it.isUrl && !it.isVideoOnly }
        val related = info.relatedItems.mapNotNull { it.toVideo() }
        ResolvedVideo(
            title = info.name,
            durationMs = info.duration * 1_000L,
            live = live,
            hlsUrl = hls,
            manifest = plan?.manifest,
            muxedUrl = muxed?.content,
            userAgent = userAgentFor(plan?.sampleUrl ?: hls ?: muxed?.content.orEmpty()),
            related = related,
        )
    }

    /** The UA YouTube's media servers expect for the client that produced [url]. */
    private fun userAgentFor(url: String): String = when {
        url.contains("c=VISIONOS", ignoreCase = true) ->
            runCatching { YoutubeParsingHelper.getVisionOsUserAgent(null) }
                .getOrDefault(DESKTOP_UA)
        else -> DESKTOP_UA
    }

    private suspend fun cached(name: String, maxAgeMs: Long, fetch: suspend () -> List<YouTubeVideo>): List<YouTubeVideo> {
        val file = cacheDir?.let { File(it, name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }
        if (file != null && file.isFile && System.currentTimeMillis() - file.lastModified() < maxAgeMs) {
            runCatching {
                Json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { it.jsonObject.toVideo() }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = runCatching { fetch() }.getOrElse { error ->
            if (error is CancellationException) throw error
            // Stale beats empty when YouTube isn't answering.
            runCatching { file?.takeIf { it.isFile }?.readText()?.let { text -> Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonObject.toVideo() } } }
                .getOrNull() ?: throw error
        }
        if (file != null && fresh.isNotEmpty()) runCatching { file.writeText(JsonArray(fresh.map { it.toJson() }).toString()) }
        return fresh
    }

    private fun InfoItem.toVideo(): YouTubeVideo? {
        val stream = this as? StreamInfoItem ?: return null
        val id = runCatching { service.streamLHFactory.fromUrl(stream.url).id }.getOrNull() ?: return null
        return YouTubeVideo(
            id = id,
            title = stream.name ?: return null,
            channel = stream.uploaderName,
            channelUrl = stream.uploaderUrl,
            thumbnail = stream.thumbnails.bestUrl(),
            durationSec = stream.duration.coerceAtLeast(0L),
            live = stream.streamType == StreamType.LIVE_STREAM || stream.streamType == StreamType.AUDIO_LIVE_STREAM,
            views = stream.viewCount,
            uploaded = stream.textualUploadDate,
        )
    }

    private fun List<Image>.bestUrl(): String? =
        sortedByDescending { it.width }.firstOrNull { it.width in 240..1300 }?.url ?: firstOrNull()?.url

    const val KIOSK_TTL_MS = 3 * 60 * 60_000L
    const val CHANNEL_TTL_MS = 60 * 60_000L
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
}

@Immutable
data class ResolvedVideo(
    val title: String,
    val durationMs: Long,
    val live: Boolean,
    val hlsUrl: String?,
    /** A DASH manifest built from the direct video and audio streams, when both were found. */
    val manifest: String?,
    val muxedUrl: String?,
    val userAgent: String,
    val related: List<YouTubeVideo>,
)

/** The extractor's HTTP: plain HttpURLConnection, with YouTube's consent and captcha handling. */
private object HttpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val connection = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", YouTubeClient.DESKTOP_UA)
            setRequestProperty("Accept-Language", "en-GB,en;q=0.9")
            // Keeps the EU consent page from standing in for every answer.
            setRequestProperty("Cookie", "CONSENT=PENDING+987; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
            request.headers().forEach { (name, values) -> setRequestProperty(name, values.joinToString(", ")) }
        }
        try {
            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(code, connection.responseMessage, connection.headerFields, body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }
}

/* ----------------------------------------------------------------------------- manifests */

/**
 * Wraps YouTube's separate video and audio files in one DASH manifest. YouTube serves each file as
 * a plain MP4/WebM with an index at the front, which is exactly what DASH "SegmentBase" describes,
 * so the player can seek and buffer them together like any other on-demand stream.
 */
object DashManifests {
    class Plan(val manifest: String, val sampleUrl: String)

    fun plan(info: StreamInfo, maxHeight: Int): Plan? {
        val video = pickVideo(info.videoOnlyStreams, maxHeight) ?: return null
        val audio = pickAudio(info.audioStreams, clientOf(video.content)) ?: return null
        val durationSec = info.duration.coerceAtLeast(1L)
        val manifest = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" mediaPresentationDuration="PT${durationSec}S" minBufferTime="PT1.5S">""").append('\n')
            append("<Period>").append('\n')
            append("""<AdaptationSet mimeType="${video.format?.mimeType ?: "video/mp4"}" subsegmentAlignment="true" startWithSAP="1">""").append('\n')
            append("""<Representation id="video" codecs="${escape(video.codec ?: "avc1.64001F")}" bandwidth="${video.bitrate.coerceAtLeast(1)}" width="${video.width}" height="${video.height}" frameRate="${video.fps.coerceAtLeast(1)}">""").append('\n')
            append("<BaseURL>").append(escape(video.content)).append("</BaseURL>").append('\n')
            append("""<SegmentBase indexRange="${video.indexStart}-${video.indexEnd}"><Initialization range="${video.initStart}-${video.initEnd}"/></SegmentBase>""").append('\n')
            append("</Representation>").append('\n')
            append("</AdaptationSet>").append('\n')
            append("""<AdaptationSet mimeType="${audio.format?.mimeType ?: "audio/mp4"}" subsegmentAlignment="true" startWithSAP="1">""").append('\n')
            append("""<Representation id="audio" codecs="${escape(audio.codec ?: "mp4a.40.2")}" bandwidth="${audio.bitrate.coerceAtLeast(1)}" audioSamplingRate="${audio.itagItem?.sampleRate?.takeIf { it > 0 } ?: 44100}">""").append('\n')
            append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="${audio.itagItem?.audioChannels?.takeIf { it > 0 } ?: 2}"/>""").append('\n')
            append("<BaseURL>").append(escape(audio.content)).append("</BaseURL>").append('\n')
            append("""<SegmentBase indexRange="${audio.indexStart}-${audio.indexEnd}"><Initialization range="${audio.initStart}-${audio.initEnd}"/></SegmentBase>""").append('\n')
            append("</Representation>").append('\n')
            append("</AdaptationSet>").append('\n')
            append("</Period>").append('\n')
            append("</MPD>").append('\n')
        }
        return Plan(manifest, video.content)
    }

    /**
     * H.264 first (every Fire TV decodes it in hardware), the tallest picture under the cap, and
     * audio from the same YouTube client as the picture: each client's files want their own
     * headers, so a pair from one client is the pair that plays.
     */
    private fun pickVideo(streams: List<VideoStream>, maxHeight: Int): VideoStream? {
        val usable = streams.filter { it.isUrl && it.hasRanges() && it.height in 1..maxHeight }
        val preferred = usable.filter { it.isAndroidClient() }.ifEmpty { usable }
        return preferred.filter { it.codec?.startsWith("avc1") == true }.maxByOrNull { it.height * 1000 + it.fps }
            ?: preferred.maxByOrNull { it.height * 1000 + it.fps }
    }

    /** AAC first, the best bitrate; Opus (WebM) if that's all there is. */
    private fun pickAudio(streams: List<AudioStream>, client: String?): AudioStream? {
        val usable = streams.filter { it.isUrl && it.hasRanges() && clientOf(it.content) == client }
        return usable.filter { it.codec?.startsWith("mp4a") == true }.maxByOrNull { it.averageBitrate }
            ?: usable.maxByOrNull { it.averageBitrate }
    }

    /** The "c=" parameter of a stream address: which YouTube client it was given to. */
    fun clientOf(url: String): String? = Regex("[?&]c=([A-Za-z0-9_]+)").find(url)?.groupValues?.get(1)?.uppercase()

    private fun VideoStream.hasRanges() = initEnd > 0 && indexEnd > 0 && indexEnd >= indexStart
    private fun AudioStream.hasRanges() = initEnd > 0 && indexEnd > 0 && indexEnd >= indexStart
    private fun VideoStream.isAndroidClient() = clientOf(content) == "ANDROID"

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

/**
 * A tiny web server on the Fire TV's own loopback address that hands the player the manifests
 * built above. Only this device can reach it, and it only ever serves what was put in it.
 */
@Singleton
class LocalMediaServer @Inject constructor() {
    private val documents = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    /** Puts [body] up and returns its URL. Old documents are dropped so nothing piles up. */
    @Synchronized
    fun publish(body: String, contentType: String, extension: String): String? {
        val socket = server ?: runCatching {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)) }
        }.getOrNull()?.also { socket ->
            server = socket
            Thread({
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { pool.execute { runCatching { serve(client) } } }.onFailure { client.close() }
                }
            }, "local-media").apply { isDaemon = true }.start()
        } ?: return null
        if (documents.size >= MAX_DOCUMENTS) documents.clear()
        val token = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        documents["/$token.$extension"] = contentType to body.toByteArray(Charsets.UTF_8)
        return "http://127.0.0.1:${socket.localPort}/$token.$extension"
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1)?.substringBefore('?') ?: return
            val output = socket.getOutputStream()
            val document = documents[path]
            if (parts.firstOrNull() != "GET" || document == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: ${document.first}\r\nContent-Length: ${document.second.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                )
                output.write(document.second)
            }
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
            if (buffer.size() > 4096) return null
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private companion object {
        const val MAX_DOCUMENTS = 16
    }
}

/* --------------------------------------------------------------------------- sponsorblock */

/** SponsorBlock's segments, asked for by a hash prefix so the server never learns which video. */
object SponsorBlock {
    suspend fun segments(videoId: String, categories: Set<String>): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext emptyList()
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray())
        val prefix = digest.joinToString("") { "%02x".format(it) }.take(4)
        val list = categories.joinToString(",") { "\"$it\"" }
        val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=${URLEncoder.encode("[$list]", "UTF-8")}"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != 200) return@withContext emptyList()
            val root = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonArray
                ?: return@withContext emptyList()
            root.mapNotNull { it as? JsonObject }
                .firstOrNull { it["videoID"]?.jsonPrimitive?.contentOrNull == videoId }
                ?.get("segments")?.jsonArray
                ?.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val range = item["segment"]?.jsonArray ?: return@mapNotNull null
                    val start = range.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val end = range.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    if (end <= start) return@mapNotNull null
                    SkipSegment((start * 1000).toLong(), (end * 1000).toLong(), item["category"]?.jsonPrimitive?.contentOrNull ?: "sponsor")
                }
                ?.sortedBy { it.startMs }
                .orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }
}

/* ------------------------------------------------------------------------------ viewmodel */

@Immutable
data class YouTubeState(
    val section: YtSection = YtSection.Live,
    val items: List<YouTubeVideo> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val query: String = "",
    val channelResults: List<YouTubeChannel> = emptyList(),
    /** Videos related to the one playing (or last played), for the row under it. */
    val related: List<YouTubeVideo> = emptyList(),
    /** Getting the streams for this video. */
    val opening: YouTubeVideo? = null,
)

sealed interface YouTubeEvent {
    /** The app's player couldn't take this one: the YouTube app should open it. */
    data class OpenExternally(val video: YouTubeVideo) : YouTubeEvent
    data class Message(val text: String) : YouTubeEvent
}

@HiltViewModel
class YouTubeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: YouTubeStore,
    private val local: LocalMediaServer,
    private val playerManager: PlayerManager,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {
    private val _state = MutableStateFlow(YouTubeState())
    val state: StateFlow<YouTubeState> = _state.asStateFlow()
    val preferences: StateFlow<YouTubePrefs> = store.preferences
    val followed: StateFlow<List<YouTubeChannel>> = store.followed
    val history: StateFlow<List<YouTubeVideo>> = store.history

    private val _events = MutableSharedFlow<YouTubeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<YouTubeEvent> = _events.asSharedFlow()

    /** SponsorBlock segments for the video playing (empty for anything else). */
    private val _segments = MutableStateFlow<List<SkipSegment>>(emptyList())
    val segments: StateFlow<List<SkipSegment>> = _segments.asStateFlow()

    /** The video playing through the app's player, if any. */
    var playing: YouTubeVideo? = null
        private set
    private var playingChannelId: Int? = null

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var ready = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { YouTubeClient.init(context) }
            ready = true
        }
    }

    fun open(section: YtSection, force: Boolean = false) {
        if (!force && _state.value.section == section && (_state.value.items.isNotEmpty() || _state.value.loading)) return
        searchJob?.cancel()
        loadJob?.cancel()
        _state.update { it.copy(section = section, items = emptyList(), failed = false, loading = section !in STATIC_SECTIONS, channelResults = emptyList()) }
        when (section) {
            YtSection.History -> _state.update { it.copy(items = store.history.value) }
            YtSection.Search -> _state.update { it.copy(items = emptyList()) }
            YtSection.Options -> Unit
            else -> loadJob = viewModelScope.launch(Dispatchers.IO) {
                waitUntilReady()
                val items = runCatching {
                    when (section) {
                        YtSection.Live -> YouTubeClient.kiosk("live", if (force) 0L else LIVE_TTL_MS)
                        YtSection.Trending -> YouTubeClient.kiosk("Trending", if (force) 0L else KIOSK_TTL_MS)
                        YtSection.Music -> YouTubeClient.kiosk("trending_music", if (force) 0L else KIOSK_TTL_MS)
                        YtSection.Gaming -> YouTubeClient.kiosk("trending_gaming", if (force) 0L else KIOSK_TTL_MS)
                        YtSection.Trailers -> YouTubeClient.kiosk("trending_movies_and_shows", if (force) 0L else KIOSK_TTL_MS)
                        YtSection.Podcasts -> YouTubeClient.kiosk("trending_podcasts_episodes", if (force) 0L else KIOSK_TTL_MS)
                        is YtSection.Followed -> YouTubeClient.channelVideos(section.channel, if (force) 0L else CHANNEL_TTL_MS)
                        else -> emptyList()
                    }
                }.onFailure { if (it is CancellationException) throw it }
                _state.update {
                    if (it.section == section) it.copy(items = items.getOrDefault(emptyList()), loading = false, failed = items.isFailure) else it
                }
            }
        }
    }

    /** Reloads the open section, ignoring the cache. */
    fun refresh() {
        val section = _state.value.section
        if (section is YtSection.History) {
            _state.update { it.copy(items = store.history.value) }
            return
        }
        open(section, force = true)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.update { it.copy(items = emptyList(), channelResults = emptyList(), loading = false) }
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { if (it.section == YtSection.Search) it.copy(loading = true, failed = false) else it }
            waitUntilReady()
            val videos = runCatching { YouTubeClient.search(trimmed) }.onFailure { if (it is CancellationException) throw it }
            val channels = runCatching { YouTubeClient.searchChannels(trimmed) }.getOrDefault(emptyList())
            _state.update {
                if (it.section == YtSection.Search && it.query.trim() == trimmed) it.copy(
                    items = videos.getOrDefault(emptyList()),
                    channelResults = channels.take(MAX_CHANNEL_RESULTS),
                    loading = false,
                    failed = videos.isFailure,
                ) else it
            }
        }
    }

    fun toggleFollow(channel: YouTubeChannel) = store.toggleFollow(channel)

    /** Follows (or unfollows) the channel a video belongs to. */
    fun toggleFollowOf(video: YouTubeVideo) {
        viewModelScope.launch {
            val channel = YouTubeClient.channelOf(video) ?: return@launch
            store.toggleFollow(channel)
            _events.tryEmit(
                YouTubeEvent.Message(
                    context.getString(
                        if (store.isFollowed(channel.id)) R.string.dial_youtube_followed else R.string.dial_youtube_unfollowed,
                        channel.name,
                    )
                )
            )
        }
    }

    fun updatePreferences(transform: (YouTubePrefs) -> YouTubePrefs) = store.update(transform)

    fun clearHistory() {
        store.clearHistory()
        if (_state.value.section == YtSection.History) _state.update { it.copy(items = emptyList()) }
    }

    /**
     * Plays [video] in the app's player (the caller switches to it on [onPlaying]); when the
     * streams can't be had, or the person prefers it, the YouTube app gets it instead.
     */
    fun play(video: YouTubeVideo, onPlaying: () -> Unit) {
        if (!store.preferences.value.playInApp) {
            store.addToHistory(video)
            _events.tryEmit(YouTubeEvent.OpenExternally(video))
            return
        }
        playJob?.cancel()
        _state.update { it.copy(opening = video) }
        playJob = viewModelScope.launch {
            try {
                waitUntilReady()
                val prefs = store.preferences.value
                val resolved = runCatching { YouTubeClient.resolve(video.id, prefs.maxHeight) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                val url = resolved?.let { r ->
                    r.manifest?.let { withContext(Dispatchers.IO) { local.publish(it, "application/dash+xml", "mpd") } } ?: r.hlsUrl ?: r.muxedUrl
                }
                if (resolved == null || url == null) {
                    store.addToHistory(video)
                    _events.tryEmit(YouTubeEvent.OpenExternally(video))
                    return@launch
                }
                val channelId = withContext(Dispatchers.IO) { rememberChannel(video, resolved, url) }
                playing = video
                playingChannelId = channelId
                _segments.value = emptyList()
                store.addToHistory(video.copy(live = resolved.live))
                _state.update { it.copy(related = resolved.related) }
                runCatching { playerManager.play(MediaCommand.Common(channelId), applyContinueWatching = false) }
                onPlaying()
                if (prefs.sponsorBlock && !resolved.live) {
                    val segments = SponsorBlock.segments(video.id, prefs.sponsorCategories)
                    if (playing?.id == video.id) _segments.value = segments
                }
            } finally {
                _state.update { if (it.opening?.id == video.id) it.copy(opening = null) else it }
            }
        }
    }

    /**
     * Called as the player's channel changes, so stale segments never apply to another stream.
     * A null (the player clearing between two streams) is left alone: the next id decides.
     */
    fun onPlayingChannel(channelId: Int?) {
        if (channelId != null && channelId != playingChannelId) stopped()
    }

    /** The player closed. */
    fun stopped() {
        playing = null
        playingChannelId = null
        _segments.value = emptyList()
    }

    /**
     * A YouTube row from a list (favourites, recently played): the saved address was a one-off
     * manifest, so the video is looked up afresh and played by its id.
     */
    fun playSaved(channel: Channel, onPlaying: () -> Unit) {
        val id = channel.relationId?.removePrefix("yt:")?.takeIf { it.isNotBlank() } ?: return
        play(YouTubeVideo(id = id, title = channel.title, channel = channel.category, thumbnail = channel.cover), onPlaying)
    }

    /** The playlist stand-in and a channel row per video (the row keeps its id across plays). */
    private suspend fun rememberChannel(video: YouTubeVideo, resolved: ResolvedVideo, url: String): Int {
        val playlist = playlistDao.get(PLAYLIST_URL)
        if (playlist == null || playlist.userAgent != resolved.userAgent) {
            playlistDao.insertOrReplace(
                Playlist(title = PLAYLIST_TITLE, url = PLAYLIST_URL, source = DataSource.M3U, userAgent = resolved.userAgent)
            )
        }
        val relation = "yt:${video.id}"
        val existing = channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = video.channel ?: PLAYLIST_TITLE,
                title = video.title,
                cover = video.thumbnail,
                playlistUrl = PLAYLIST_URL,
                id = existing?.id ?: 0,
                relationId = relation,
                favourite = existing?.favourite ?: false,
            )
        ).toInt()
        if (id != 0) return id
        return channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)?.id ?: 0
    }

    private suspend fun waitUntilReady() {
        while (!ready) delay(50)
    }

    companion object {
        const val PLAYLIST_URL = "youtube://videos"
        const val PLAYLIST_TITLE = "YouTube"
        private const val SEARCH_DEBOUNCE_MS = 500L
        private const val LIVE_TTL_MS = 10 * 60_000L
        private const val MAX_CHANNEL_RESULTS = 8
        private val STATIC_SECTIONS: Set<YtSection> = setOf(YtSection.History, YtSection.Search, YtSection.Options)
    }
}
