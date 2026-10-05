package com.m3u.tv

import android.content.Context
import android.util.Xml
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

/* -------------------------------------------------------------------------------------------------
 * The Files tab: films and shows on a NAS or a computer on the home network, over SMB (Windows
 * file sharing; Synology, QNAP, a Mac's or PC's shared folders) or WebDAV (Nextcloud, most NAS
 * boxes). Folders are browsed on the TV; a video plays through the stick's loopback server, which
 * reads the file in pieces so seeking works. Passwords live in the encrypted key store.
 * ---------------------------------------------------------------------------------------------- */

enum class ShareKind { Smb, WebDav }

@Immutable
data class FileShare(
    val id: String,
    val kind: ShareKind,
    val name: String,
    /** SMB: the host name or address. WebDAV: the full address of the folder. */
    val address: String,
    /** SMB only: the share's name on the host. */
    val share: String = "",
    val user: String = "",
    val domain: String = "",
)

@Immutable
data class FileEntry(val name: String, val path: String, val folder: Boolean, val size: Long) {
    val playable: Boolean get() = !folder && name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS
}

val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "mpg", "mpeg", "flv", "3gp", "mp3", "flac", "m4a", "aac", "ogg", "wav")

@Singleton
class FilesStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences("file_shares", Context.MODE_PRIVATE)
    private val _shares = MutableStateFlow(read())
    val shares: StateFlow<List<FileShare>> = _shares.asStateFlow()

    fun add(share: FileShare, password: String) {
        save(_shares.value.filterNot { it.id == share.id } + share)
        setPassword(share.id, password)
    }

    fun remove(share: FileShare) {
        save(_shares.value.filterNot { it.id == share.id })
        setPassword(share.id, null)
    }

    fun password(id: String): String = passwords()[id].orEmpty()

    fun reload() {
        _shares.value = read()
    }

    private fun passwords(): Map<String, String> = runCatching {
        val raw = secrets.get(SecretName.SharePasswords) ?: return emptyMap()
        Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
    }.getOrDefault(emptyMap())

    private fun setPassword(id: String, password: String?) {
        val next = passwords().toMutableMap()
        if (password.isNullOrEmpty()) next.remove(id) else next[id] = password
        secrets.put(SecretName.SharePasswords, JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun save(shares: List<FileShare>) {
        _shares.value = shares
        prefs.edit().putString(KEY, JsonArray(shares.map { share ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(share.id),
                    "kind" to JsonPrimitive(share.kind.name),
                    "name" to JsonPrimitive(share.name),
                    "address" to JsonPrimitive(share.address),
                    "share" to JsonPrimitive(share.share),
                    "user" to JsonPrimitive(share.user),
                    "domain" to JsonPrimitive(share.domain),
                )
            )
        }).toString()).apply()
    }

    private fun read(): List<FileShare> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            fun text(name: String) = item[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            FileShare(
                id = text("id").ifEmpty { return@mapNotNull null },
                kind = ShareKind.entries.firstOrNull { it.name == text("kind") } ?: return@mapNotNull null,
                name = text("name"),
                address = text("address"),
                share = text("share"),
                user = text("user"),
                domain = text("domain"),
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "shares"
    }
}

/* ------------------------------------------------------------------------------------ SMB */

object SmbShares {
    // No socket read timeout: smbj's reader thread would drop an idle connection with it.
    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .build()
        )
    }
    private val open = ConcurrentHashMap<String, DiskShare>()

    @Synchronized
    private fun connect(share: FileShare, password: String): DiskShare {
        open[share.id]?.takeIf { it.isConnected && it.treeConnect.session.connection.isConnected }?.let { return it }
        open.remove(share.id)?.let { stale -> runCatching { stale.close() } }
        val connection = client.connect(share.address)
        val auth = if (share.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(share.user, password.toCharArray(), share.domain.ifBlank { null })
        val session = connection.authenticate(auth)
        val disk = session.connectShare(share.share) as DiskShare
        open[share.id] = disk
        return disk
    }

    /** Runs [block] on the share, reconnecting once if the kept connection had gone stale. */
    private fun <T> withShare(share: FileShare, password: String, block: (DiskShare) -> T): T = try {
        block(connect(share, password))
    } catch (e: Exception) {
        open.remove(share.id)?.let { stale -> runCatching { stale.close() } }
        block(connect(share, password))
    }

    fun list(share: FileShare, password: String, path: String): List<FileEntry> = withShare(share, password) { disk ->
        disk.list(path.replace('/', '\\')).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            val folder = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            FileEntry(name = name, path = if (path.isEmpty()) name else "$path/$name", folder = folder, size = info.endOfFile)
        }
    }

    fun length(share: FileShare, password: String, path: String): Long = withShare(share, password) { disk ->
        disk.openFile(
            path.replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.getFileInformation(FileStandardInformation::class.java).endOfFile }
    }

    /** The file from [offset] on, read ahead in large pieces (one round trip per megabyte, not per 8 KB). */
    fun open(share: FileShare, password: String, path: String, offset: Long): InputStream {
        val file = withShare(share, password) { disk ->
            disk.openFile(
                path.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
            )
        }
        return object : InputStream() {
            private var position = offset
            private val chunk = ByteArray(READ_AHEAD)
            private var chunkStart = 0
            private var chunkEnd = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (chunkStart >= chunkEnd) {
                    val count = file.read(chunk, position, 0, chunk.size)
                    if (count <= 0) return -1
                    position += count
                    chunkStart = 0
                    chunkEnd = count
                }
                val count = minOf(len, chunkEnd - chunkStart)
                System.arraycopy(chunk, chunkStart, buffer, off, count)
                chunkStart += count
                return count
            }

            override fun close() {
                runCatching { file.close() }
            }
        }
    }

    private const val READ_AHEAD = 1 shl 20
}

/* --------------------------------------------------------------------------------- WebDAV */

object WebDav {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun Request.Builder.auth(share: FileShare, password: String): Request.Builder = apply {
        if (share.user.isNotBlank()) header("Authorization", Credentials.basic(share.user, password))
    }

    private fun urlFor(share: FileShare, path: String): HttpUrl {
        val builder = share.address.trimEnd('/').toHttpUrl().newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        // Folders end in a slash; some servers redirect (and drop the PROPFIND body) without it.
        if (path.isEmpty()) builder.addPathSegment("")
        return builder.build()
    }

    fun list(share: FileShare, password: String, path: String): List<FileEntry> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:displayname/></d:prop></d:propfind>"""
            .toRequestBody("application/xml".toMediaType())
        val url = urlFor(share, path)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .auth(share, password)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("WebDAV answered ${response.code}")
            val self = url.pathSegments.filter { it.isNotEmpty() }
            return parse(response.body.byteStream()).mapNotNull { (href, folder, size) ->
                // Hrefs may be absolute paths or full addresses, and are percent-encoded.
                val segments = response.request.url.resolve(href)?.pathSegments?.filter { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (segments.isEmpty() || segments == self) return@mapNotNull null
                val name = segments.last()
                if (name.startsWith(".")) return@mapNotNull null
                FileEntry(name = name, path = if (path.isEmpty()) name else "$path/$name", folder = folder, size = size)
            }
        }
    }

    fun length(share: FileShare, password: String, path: String): Long {
        val request = Request.Builder().url(urlFor(share, path)).head().auth(share, password).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("WebDAV answered ${response.code}")
            response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    fun open(share: FileShare, password: String, path: String, offset: Long): InputStream {
        val request = Request.Builder()
            .url(urlFor(share, path))
            .auth(share, password)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            error("WebDAV answered ${response.code}")
        }
        val stream = response.body.byteStream()
        // A server that ignores Range sends the whole file: skip to where the player asked.
        if (offset > 0 && response.code != 206) {
            try {
                var left = offset
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped <= 0) {
                        if (stream.read() < 0) error("WebDAV file ended early")
                        left--
                    } else {
                        left -= skipped
                    }
                }
            } catch (e: Exception) {
                response.close()
                throw e
            }
        }
        return object : InputStream() {
            override fun read(): Int = stream.read()
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = stream.read(buffer, off, len)
            override fun close() = response.close()
        }
    }

    private fun parse(input: InputStream): List<Triple<String, Boolean, Long>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        val out = mutableListOf<Triple<String, Boolean, Long>>()
        var href: String? = null
        var folder = false
        var size = 0L
        var text = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    when (parser.name) {
                        "response" -> { href = null; folder = false; size = 0L }
                        "collection" -> folder = true
                    }
                }
                XmlPullParser.TEXT -> text = parser.text.orEmpty()
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.trim()
                    "getcontentlength" -> size = text.trim().toLongOrNull() ?: 0L
                    "response" -> href?.let { out += Triple(it, folder, size) }
                }
            }
            parser.next()
        }
        return out
    }
}

/* ------------------------------------------------------------------------------ viewmodel */

@Immutable
data class FilesState(
    val share: FileShare? = null,
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val adding: Boolean = false,
    /** The row to focus when the list arrives: the folder (or share) just stepped out of. */
    val focusPath: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: FilesStore,
    private val local: LocalMediaServer,
    private val playerManager: PlayerManager,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
) : ViewModel() {
    val shares: StateFlow<List<FileShare>> = store.shares
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()
    private var job: Job? = null
    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Why a file didn't play (shown as a message wherever the person started it from). */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    fun startAdding() = _state.update { it.copy(adding = true, error = null) }

    fun cancelAdding() = _state.update { it.copy(adding = false) }

    fun add(kind: ShareKind, name: String, address: String, share: String, user: String, domain: String, password: String) {
        val cleanAddress = address.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/', '\\')
        val entry = FileShare(
            id = System.currentTimeMillis().toString(36),
            kind = kind,
            name = name.trim().ifEmpty { if (kind == ShareKind.Smb) "$cleanAddress/$share" else cleanAddress },
            address = if (kind == ShareKind.WebDav && !cleanAddress.startsWith("http")) "http://$cleanAddress" else cleanAddress,
            share = share.trim().trim('/', '\\'),
            user = user.trim(),
            domain = domain.trim(),
        )
        store.add(entry, password)
        _state.update { it.copy(adding = false) }
        open(entry)
    }

    fun remove(share: FileShare) {
        store.remove(share)
        if (_state.value.share?.id == share.id) _state.value = FilesState()
    }

    fun open(share: FileShare, path: String = "", focus: String? = null) {
        job?.cancel()
        // The old rows stay until the new ones arrive, so focus has somewhere to be meanwhile.
        _state.update {
            it.copy(
                share = share,
                path = path,
                entries = if (it.share?.id == share.id) it.entries else emptyList(),
                loading = true,
                error = null,
                focusPath = focus,
            )
        }
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val password = store.password(share.id)
                    when (share.kind) {
                        ShareKind.Smb -> SmbShares.list(share, password, path)
                        ShareKind.WebDav -> WebDav.list(share, password, path)
                    }
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _state.update {
                if (it.share?.id != share.id || it.path != path) it
                else it.copy(
                    entries = result.getOrDefault(emptyList()).sortedWith(compareBy<FileEntry> { !it.folder }.thenBy { it.name.lowercase(Locale.ROOT) }),
                    loading = false,
                    error = result.exceptionOrNull()?.let { error -> error.message ?: error.javaClass.simpleName },
                )
            }
        }
    }

    /** True when Back stayed inside the tab (up a folder, or out of a share). */
    fun back(): Boolean {
        val current = _state.value
        if (current.adding) {
            cancelAdding()
            return true
        }
        val share = current.share ?: return false
        if (current.path.isEmpty()) {
            job?.cancel()
            _state.value = FilesState(focusPath = share.id)
            return true
        }
        open(share, current.path.substringBeforeLast('/', ""), focus = current.path)
        return true
    }

    fun play(entry: FileEntry, onPlaying: () -> Unit) {
        val share = _state.value.share ?: return
        viewModelScope.launch { start(share, entry.path, entry.name, entry.size, onPlaying) }
    }

    /** A file from a list (recently played, favourites): served again, then played. */
    fun playSaved(channel: Channel, onPlaying: () -> Unit) {
        val relation = channel.relationId?.removePrefix(RELATION_PREFIX) ?: return
        val shareId = relation.substringBefore(':')
        val path = relation.substringAfter(':')
        val share = store.shares.value.firstOrNull { it.id == shareId }
        if (share == null) {
            _failures.tryEmit(context.getString(R.string.dial_files_share_missing))
            return
        }
        viewModelScope.launch { start(share, path, channel.title, -1L, onPlaying) }
    }

    private suspend fun start(share: FileShare, path: String, title: String, size: Long, onPlaying: () -> Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { prepare(share, path, title, size) } }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val channelId = result.getOrNull()
        if (channelId == null) {
            val reason = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                ?: context.getString(R.string.dial_files_unreadable)
            _failures.tryEmit(reason)
            return
        }
        runCatching { playerManager.play(MediaCommand.Common(channelId), applyContinueWatching = true) }
        onPlaying()
    }

    private suspend fun prepare(share: FileShare, path: String, title: String, knownSize: Long): Int? {
        val password = store.password(share.id)
        val length = knownSize.takeIf { it > 0 } ?: when (share.kind) {
            ShareKind.Smb -> SmbShares.length(share, password, path)
            ShareKind.WebDav -> WebDav.length(share, password, path)
        }
        if (length <= 0) return null
        val extension = path.substringAfterLast('.', "bin").lowercase(Locale.ROOT)
        val url = local.publishStream(
            key = "${share.id}:$path",
            extension = extension,
            contentType = mimeFor(extension),
            length = length,
        ) { offset ->
            when (share.kind) {
                ShareKind.Smb -> SmbShares.open(share, password, path, offset)
                ShareKind.WebDav -> WebDav.open(share, password, path, offset)
            }
        } ?: return null
        if (playlistDao.get(PLAYLIST_URL) == null) {
            playlistDao.insertOrReplace(Playlist(title = PLAYLIST_TITLE, url = PLAYLIST_URL, source = DataSource.M3U))
        }
        val relation = "$RELATION_PREFIX${share.id}:$path"
        val existing = channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = share.name,
                title = title.substringBeforeLast('.').replace('.', ' ').replace('_', ' '),
                playlistUrl = PLAYLIST_URL,
                id = existing?.id ?: 0,
                relationId = relation,
                favourite = existing?.favourite ?: false,
            )
        ).toInt()
        return if (id != 0) id else channelDao.getByPlaylistUrlAndRelationId(PLAYLIST_URL, relation)?.id
    }

    private fun mimeFor(extension: String): String = when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    companion object {
        const val PLAYLIST_URL = "files://shares"
        const val PLAYLIST_TITLE = "Files"
        private const val RELATION_PREFIX = "file:"
    }
}
