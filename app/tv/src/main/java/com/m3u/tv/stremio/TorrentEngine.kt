package com.m3u.tv.stremio

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ResolvedPlayback(val url: String, val via: String)

/**
 * Turns a Stremio stream into something ExoPlayer can open.
 * Order: direct HTTP, Real-Debrid, TorBox, TorrServe on the LAN, then the built-in peer engine.
 */
object StreamResolver {
    suspend fun resolve(
        source: StreamSource,
        realDebrid: String?,
        torbox: String?,
        p2pEnabled: Boolean,
        torrServeUrl: String,
        cacheDir: File,
    ): ResolvedPlayback {
        val http = source.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (http != null && source.infoHash == null && !source.isMagnet) {
            if (realDebrid != null && looksRestricted(http)) {
                runCatching { return ResolvedPlayback(RealDebridClient.unrestrict(realDebrid, http), "Real-Debrid") }
            }
            return ResolvedPlayback(http, source.addonName)
        }
        val magnet = source.magnet
            ?: source.infoHash?.let { MagnetLinks.magnet(it, source.name) }
            ?: http?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
        if (magnet != null) {
            var last: Exception? = null
            if (!realDebrid.isNullOrBlank()) {
                runCatching { return ResolvedPlayback(RealDebridClient.resolveMagnet(realDebrid, magnet, source.fileIdx), "Real-Debrid") }
                    .onFailure { last = it as? Exception }
            }
            if (!torbox.isNullOrBlank()) {
                runCatching { return ResolvedPlayback(TorBoxClient.resolveMagnet(torbox, magnet, source.fileIdx), "TorBox") }
                    .onFailure { last = it as? Exception }
            }
            if (p2pEnabled) {
                if (TorrServeClient.alive(torrServeUrl)) {
                    return ResolvedPlayback(
                        TorrServeClient.playUrl(torrServeUrl, magnet, source.fileIdx),
                        "TorrServe",
                    )
                }
                return ResolvedPlayback(
                    BuiltinTorrent.stream(cacheDir, magnet, source.fileIdx),
                    "P2P",
                )
            }
            throw DebridException(
                last?.message
                    ?: "No debrid account for this torrent. Add Real-Debrid or TorBox, or turn on P2P.",
            )
        }
        if (http != null) return ResolvedPlayback(http, source.addonName)
        throw DebridException("This stream has no link.")
    }

    private fun looksRestricted(url: String): Boolean {
        val host = url.lowercase()
        return "real-debrid.com" in host || host.contains("/d/") || "rapidgator" in host ||
            "uploaded." in host || "nitroflare" in host || "1fichier" in host
    }
}

/** YouROK TorrServer / TorrServe, usually at http://127.0.0.1:8090 on the Fire TV. */
object TorrServeClient {
    suspend fun alive(base: String): Boolean {
        val root = base.trim().trimEnd('/')
        if (root.isBlank()) return false
        return StremioHttp.ping("$root/echo") || StremioHttp.ping(root)
    }

    suspend fun playUrl(base: String, magnet: String, fileIdx: Int?): String {
        val root = base.trim().trimEnd('/')
        val body = buildJsonObject {
            put("action", "add")
            put("link", magnet)
            put("save_to_db", false)
        }.toString()
        runCatching { StremioHttp.postJson("$root/torrent/add", emptyMap(), body) }
            .onFailure { Log.w(TAG, "TorrServe add failed: ${it.message}") }
        val hash = MagnetLinks.infoHash(magnet).orEmpty()
        val index = fileIdx ?: 0
        return "$root/stream?link=$hash&index=$index&play"
    }

    private const val TAG = "ChudTorrServe"
}

/**
 * Sequential BitTorrent client for when TorrServe isn't running.
 * It asks public trackers for peers, downloads the selected video in order, and serves it to
 * ExoPlayer at http://127.0.0.1 with HTTP range requests. Webseeds on the magnet are used too.
 */
object BuiltinTorrent {
    private const val TAG = "ChudTorrent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverLock = Any()
    private var port: Int = 0
    private var job: Job? = null

    @Volatile
    private var session: Session? = null

    suspend fun stream(cacheDir: File, magnet: String, fileIdx: Int?): String = withContext(Dispatchers.IO) {
        val hash = MagnetLinks.infoHash(magnet) ?: throw DebridException("That magnet has no info-hash")
        ensureServer()
        job?.cancel()
        session?.close()
        val next = Session(
            hash = hexToBytes(hash),
            hashHex = hash,
            magnet = magnet,
            fileIdx = fileIdx,
            file = File(cacheDir, "stremio-$hash.bin"),
        )
        if (next.file.exists()) next.file.delete()
        next.file.parentFile?.mkdirs()
        RandomAccessFile(next.file, "rw").use { /* create */ }
        session = next
        job = scope.launch { download(next) }
        try {
            withTimeout(32_000) { next.ready.await() }
        } catch (timeout: TimeoutCancellationException) {
            job?.cancel()
            next.close()
            throw DebridException("Peers didn't send the torrent in time. Add Real-Debrid or TorBox, or start TorrServe.")
        }
        "http://127.0.0.1:$port/stream"
    }

    private fun ensureServer() {
        if (port != 0) return
        synchronized(serverLock) {
            if (port != 0) return
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", 0))
            port = server.localPort
            Thread({
                while (true) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread({ handle(socket) }, "chud-torrent-http").apply { isDaemon = true }.start()
                }
            }, "chud-torrent-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "Local stream on 127.0.0.1:$port")
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val current = session
            if (current == null) {
                writeStatus(client.getOutputStream(), "503 No stream")
                return
            }
            val header = runCatching { readHttp(client.getInputStream()) }.getOrNull()
            if (header == null || !header.path.startsWith("/stream")) {
                writeStatus(client.getOutputStream(), "404 Not found")
                return
            }
            if (!current.ready.isCompleted) {
                runCatching { Thread.sleep(28_000) }
            }
            val length = current.fileLength
            if (length <= 0L) {
                writeStatus(client.getOutputStream(), "503 ${current.failure ?: "Torrent metadata never arrived"}")
                return
            }
            val range = parseRange(header.range, length)
            val out = client.getOutputStream()
            val total = range.last - range.first + 1
            writeHeaders(
                out,
                if (header.range != null) "206 Partial Content" else "200 OK",
                listOf(
                    "Content-Type" to mime(current.fileName),
                    "Accept-Ranges" to "bytes",
                    "Content-Length" to total.toString(),
                    "Content-Range" to "bytes ${range.first}-${range.last}/$length",
                    "Connection" to "close",
                ),
            )
            if (header.method == "HEAD") return
            var pos = range.first
            val buf = ByteArray(64 * 1024)
            while (pos <= range.last && current.running.get()) {
                val piece = ((current.fileOffset + pos) / current.pieceLength).toInt()
                if (!current.awaitPiece(piece, 22_000)) break
                val n = minOf(buf.size.toLong(), range.last - pos + 1).toInt()
                val read = synchronized(current.raf) {
                    current.raf.seek(pos)
                    current.raf.read(buf, 0, n)
                }
                if (read <= 0) break
                out.write(buf, 0, read)
                pos += read
            }
            out.flush()
        }
    }

    private suspend fun download(session: Session) {
        try {
            val trackers = (MagnetLinks.queryValues(session.magnet, "tr") + DEFAULT_TRACKERS).distinct()
            val peers = announceAll(trackers, session.hash, session.peerId)
            Log.i(TAG, "Peers from trackers: ${peers.size}")
            if (peers.isEmpty()) throw DebridException("No peers answered the tracker")
            coroutineScope {
                peers.take(36).map { peer ->
                    async {
                        if (!session.running.get() || session.ready.isCompleted) return@async
                        runCatching { talkMetadata(session, peer) }
                            .onFailure { Log.d(TAG, "peer ${peer.host}: ${it.message}") }
                    }
                }.awaitAll()
            }
            if (!session.ready.isCompleted) {
                throw DebridException("No peer sent the torrent metadata. Try Real-Debrid, TorBox, or TorrServe.")
            }
            val webseeds = MagnetLinks.queryValues(session.magnet, "ws")
            coroutineScope {
                if (webseeds.isNotEmpty()) launch { pullWebseeds(session, webseeds) }
                peers.take(16).forEach { peer ->
                    launch { runCatching { downloadFrom(session, peer) } }
                }
            }
        } catch (error: Exception) {
            session.failure = error.message ?: "P2P failed"
            if (!session.ready.isCompleted) session.ready.completeExceptionally(error)
            Log.w(TAG, "download failed: ${error.message}")
        }
    }

    private suspend fun announceAll(trackers: List<String>, hash: ByteArray, peerId: ByteArray): List<Peer> =
        coroutineScope {
            trackers.map { tracker ->
                async {
                    runCatching { announce(tracker, hash, peerId) }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { "${it.host}:${it.port}" }.filter { it.port in 1..65535 }
        }

    private fun announce(tracker: String, hash: ByteArray, peerId: ByteArray): List<Peer> {
        val url = tracker.trim()
        return when {
            url.startsWith("udp://", ignoreCase = true) -> announceUdp(url, hash, peerId)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                announceHttp(url, hash, peerId)
            else -> emptyList()
        }
    }

    private fun announceHttp(tracker: String, hash: ByteArray, peerId: ByteArray): List<Peer> {
        val join = if ('?' in tracker) "&" else "?"
        val url = tracker + join +
            "info_hash=${rawQuery(hash)}&peer_id=${rawQuery(peerId)}" +
            "&port=6881&uploaded=0&downloaded=0&left=1000000000&compact=1&numwant=50&event=started"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ChudStreams/1.1")
        }
        try {
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.readBytes()
            val root = Bencode.decode(body) as? Map<*, *> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            return parsePeers(root as Map<String, Any>)
        } finally {
            connection.disconnect()
        }
    }

    private fun announceUdp(tracker: String, hash: ByteArray, peerId: ByteArray): List<Peer> {
        val rest = tracker.removePrefix("udp://").removePrefix("UDP://").substringBefore("/")
        val host = rest.substringBefore(":")
        val port = rest.substringAfter(":", "80").toIntOrNull() ?: return emptyList()
        DatagramSocket().use { socket ->
            socket.soTimeout = 4_000
            val address = InetAddress.getByName(host)
            val tx = (System.nanoTime() and 0x7fffffff).toInt()
            val connect = ByteBuffer.allocate(16)
            connect.putLong(0x41727101980L)
            connect.putInt(0)
            connect.putInt(tx)
            socket.send(DatagramPacket(connect.array(), 16, address, port))
            val box = ByteArray(4096)
            val reply = DatagramPacket(box, box.size)
            socket.receive(reply)
            if (reply.length < 16) return emptyList()
            val connectionId = readLong(box, 8)
            val announce = ByteBuffer.allocate(98)
            announce.putLong(connectionId)
            announce.putInt(1)
            announce.putInt(tx)
            announce.put(hash)
            announce.put(peerId)
            announce.putLong(0)
            announce.putLong(1_000_000_000L)
            announce.putLong(0)
            announce.putInt(2)
            announce.putInt(0)
            announce.putInt(tx)
            announce.putInt(50)
            announce.putShort(6881)
            socket.send(DatagramPacket(announce.array(), 98, address, port))
            socket.receive(reply)
            if (reply.length < 20) return emptyList()
            val peers = mutableListOf<Peer>()
            var offset = 20
            while (offset + 6 <= reply.length) {
                val ip = "${box[offset].toInt() and 255}.${box[offset + 1].toInt() and 255}." +
                    "${box[offset + 2].toInt() and 255}.${box[offset + 3].toInt() and 255}"
                val peerPort = ((box[offset + 4].toInt() and 255) shl 8) or (box[offset + 5].toInt() and 255)
                if (peerPort > 0) peers += Peer(ip, peerPort)
                offset += 6
            }
            return peers
        }
    }

    private suspend fun talkMetadata(session: Session, peer: Peer) {
        if (session.ready.isCompleted) return
        Socket().use { socket ->
            socket.connect(InetSocketAddress(peer.host, peer.port), 5_000)
            socket.soTimeout = 12_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(handshake(session))
            output.flush()
            val theirs = ByteArray(68)
            input.readFully(theirs)
            if (!theirs.copyOfRange(28, 48).contentEquals(session.hash)) return
            val ext = theirs[25].toInt() and 0x10 != 0
            sendMessage(output, 2, ByteArray(0))
            if (!ext) return
            sendMessage(output, 20, byteArrayOf(0) + EXT_HANDSHAKE)
            var theirMeta = 0
            var metaSize = 0
            val pieces = HashMap<Int, ByteArray>()
            val deadline = System.currentTimeMillis() + 12_000
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline && !session.ready.isCompleted) {
                val message = readMessage(input) ?: continue
                if (message.id != 20 || message.payload.isEmpty()) continue
                val extId = message.payload[0].toInt() and 0xFF
                val body = message.payload.copyOfRange(1, message.payload.size)
                if (extId == 0) {
                    val dict = Bencode.decode(body) as? Map<*, *> ?: continue
                    val m = dict["m"] as? Map<*, *>
                    theirMeta = (m?.get("ut_metadata") as? Long)?.toInt() ?: 0
                    metaSize = (dict["metadata_size"] as? Long)?.toInt() ?: 0
                    if (theirMeta == 0 || metaSize <= 0) return
                    val count = (metaSize + META_PIECE - 1) / META_PIECE
                    for (index in 0 until count) {
                        val ask = "d8:msg_typei0e5:piecei${index}ee".toByteArray()
                        sendMessage(output, 20, byteArrayOf(theirMeta.toByte()) + ask)
                    }
                } else if (metaSize > 0) {
                    val split = splitBencode(body) ?: continue
                    val dict = split.first
                    if ((dict["msg_type"] as? Long) != 1L) continue
                    val index = (dict["piece"] as? Long)?.toInt() ?: continue
                    pieces[index] = split.second
                    if (pieces.size * META_PIECE >= metaSize || pieces.values.sumOf { it.size } >= metaSize) {
                        val blob = ByteArrayOutputStream()
                        pieces.toSortedMap().values.forEach { blob.write(it) }
                        val info = blob.toByteArray().copyOf(metaSize)
                        if (applyMetadata(session, info)) return
                    }
                }
            }
        }
    }

    private fun applyMetadata(session: Session, infoBytes: ByteArray): Boolean {
        val info = Bencode.decode(infoBytes) as? Map<*, *> ?: return false
        @Suppress("UNCHECKED_CAST")
        val map = info as Map<String, Any>
        val pieceLength = (map["piece length"] as? Long)?.toInt() ?: return false
        if (pieceLength <= 0) return false
        if (session.ready.isCompleted) return true
        val files = torrentFiles(map)
        if (files.isEmpty()) return false
        val chosen = chooseFile(files, session.fileIdx) ?: return false
        session.pieceLength = pieceLength
        session.totalLength = files.sumOf { it.length }
        session.fileOffset = chosen.offset
        session.fileLength = chosen.length
        session.fileName = chosen.path.substringAfterLast("/")
        session.raf.setLength(chosen.length)
        val published = session.ready.complete(Unit)
        if (!published) return true
        Log.i(TAG, "Metadata ${session.fileName} ${chosen.length} bytes")
        return true
    }

    private suspend fun downloadFrom(session: Session, peer: Peer) {
        if (!session.running.get()) return
        Socket().use { socket ->
            socket.connect(InetSocketAddress(peer.host, peer.port), 5_000)
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(handshake(session))
            output.flush()
            val theirs = ByteArray(68)
            input.readFully(theirs)
            if (!theirs.copyOfRange(28, 48).contentEquals(session.hash)) return
            sendMessage(output, 2, ByteArray(0))
            while (coroutineContext.isActive && session.running.get() && !session.finished()) {
                val piece = session.claimNext() ?: run {
                    delay(250)
                    if (session.finished()) return
                    continue
                }
                val ok = runCatching { fetchPiece(session, input, output, piece) }.getOrDefault(false)
                if (!ok) session.release(piece)
            }
        }
    }

    private fun fetchPiece(session: Session, input: InputStream, output: OutputStream, piece: Int): Boolean {
        val pieceSize = session.pieceSize(piece)
        if (pieceSize <= 0) {
            session.mark(piece)
            return true
        }
        var begin = 0
        while (begin < pieceSize) {
            val block = minOf(BLOCK, pieceSize - begin)
            val body = ByteBuffer.allocate(12)
            body.putInt(piece)
            body.putInt(begin)
            body.putInt(block)
            sendMessage(output, 6, body.array())
            begin += block
        }
        val data = ByteArray(pieceSize)
        var filled = 0
        val deadline = System.currentTimeMillis() + 20_000
        while (filled < pieceSize && System.currentTimeMillis() < deadline) {
            val message = readMessage(input) ?: continue
            if (message.id == 0) sendMessage(output, 2, ByteArray(0))
            if (message.id != 7 || message.payload.size < 8) continue
            val index = readInt(message.payload, 0)
            val start = readInt(message.payload, 4)
            if (index != piece || start < 0 || start >= pieceSize) continue
            val block = message.payload.copyOfRange(8, message.payload.size)
            val n = minOf(block.size, pieceSize - start)
            block.copyInto(data, start, 0, n)
            filled += n
        }
        if (filled < pieceSize) return false
        session.writePiece(piece, data)
        return true
    }

    private suspend fun pullWebseeds(session: Session, urls: List<String>) {
        val candidates = urls.flatMap { base ->
            val trimmed = base.trimEnd('/')
            listOf(trimmed, "$trimmed/${java.net.URLEncoder.encode(session.fileName, "UTF-8").replace("+", "%20")}")
        }
        for (url in candidates.distinct()) {
            if (session.finished()) return
            runCatching { pullWebseed(session, url) }
        }
    }

    private fun pullWebseed(session: Session, url: String) {
        val first = session.firstPiece()
        val last = session.lastPiece()
        for (piece in first..last) {
            if (!session.running.get() || session.hasPiece(piece)) continue
            val pieceStart = piece.toLong() * session.pieceLength
            val fileStart = (pieceStart - session.fileOffset).coerceAtLeast(0)
            val fileEnd = minOf(session.fileLength, pieceStart + session.pieceLength - session.fileOffset)
            if (fileEnd <= fileStart) {
                session.mark(piece)
                continue
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 20_000
                setRequestProperty("Range", "bytes=$fileStart-${fileEnd - 1}")
                setRequestProperty("User-Agent", "ChudStreams/1.1")
            }
            try {
                if (connection.responseCode !in listOf(200, 206)) return
                val bytes = connection.inputStream.readBytes()
                if (bytes.isEmpty()) return
                synchronized(session.raf) {
                    session.raf.seek(fileStart)
                    session.raf.write(bytes)
                }
                session.mark(piece)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun handshake(session: Session): ByteArray {
        val out = ByteArray(68)
        out[0] = 19
        "BitTorrent protocol".toByteArray().copyInto(out, 1)
        out[25] = 0x10
        session.hash.copyInto(out, 28)
        session.peerId.copyInto(out, 48)
        return out
    }

    private data class HttpHead(val method: String, val path: String, val range: String?)

    private fun readHttp(input: InputStream): HttpHead {
        val line = readLine(input)
        val parts = line.split(" ")
        val headers = HashMap<String, String>()
        while (true) {
            val header = readLine(input)
            if (header.isEmpty()) break
            val cut = header.indexOf(':')
            if (cut > 0) headers[header.substring(0, cut).lowercase()] = header.substring(cut + 1).trim()
        }
        return HttpHead(parts.getOrElse(0) { "GET" }, parts.getOrElse(1) { "/" }, headers["range"])
    }

    private fun readLine(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun parseRange(header: String?, total: Long): LongRange {
        if (header == null || !header.startsWith("bytes=") || total <= 0L) return 0L..(total - 1).coerceAtLeast(0)
        val spec = header.removePrefix("bytes=").substringBefore(",")
        val start = spec.substringBefore("-").toLongOrNull() ?: 0L
        val end = spec.substringAfter("-").toLongOrNull() ?: (total - 1)
        val from = start.coerceIn(0L, total - 1)
        val to = end.coerceIn(from, total - 1)
        return from..to
    }

    private fun writeStatus(out: OutputStream, status: String) {
        writeHeaders(out, status, listOf("Content-Length" to "0", "Connection" to "close"))
    }

    private fun writeHeaders(out: OutputStream, status: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    private data class Wire(val id: Int, val payload: ByteArray)

    private fun readMessage(input: InputStream): Wire? {
        val length = readRawInt(input)
        if (length == 0) return null
        if (length < 1 || length > 4_000_000) throw java.io.IOException("bad message")
        val id = input.read()
        if (id < 0) throw java.io.IOException("closed")
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return Wire(id, payload)
    }

    private fun readRawInt(input: InputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return readInt(b, 0)
    }

    private fun sendMessage(output: OutputStream, id: Int, payload: ByteArray) {
        val head = ByteBuffer.allocate(5)
        head.putInt(1 + payload.size)
        head.put(id.toByte())
        output.write(head.array())
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = read(target, offset, target.size - offset)
            if (n < 0) throw java.io.IOException("closed")
            offset += n
        }
    }

    private fun HttpURLConnection.use(block: (HttpURLConnection) -> Unit) {
        try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private class Session(
        val hash: ByteArray,
        val hashHex: String,
        val magnet: String,
        val fileIdx: Int?,
        val file: File,
    ) {
        val peerId: ByteArray = peerId()
        val running = AtomicBoolean(true)
        val ready = CompletableDeferred<Unit>()
        val raf: RandomAccessFile = RandomAccessFile(file, "rw")
        var pieceLength: Int = 0
        var totalLength: Long = 0
        var fileOffset: Long = 0
        var fileLength: Long = 0
        var fileName: String = "video.mp4"
        var failure: String? = null
        private val have = BitSet()
        private val claimed = HashSet<Int>()
        private val monitor = java.lang.Object()

        fun firstPiece(): Int = if (pieceLength <= 0) 0 else (fileOffset / pieceLength).toInt()
        fun lastPiece(): Int = if (pieceLength <= 0 || fileLength <= 0) 0 else ((fileOffset + fileLength - 1) / pieceLength).toInt()
        fun pieceSize(index: Int): Int {
            if (pieceLength <= 0 || totalLength <= 0) return 0
            val start = index.toLong() * pieceLength
            if (start >= totalLength) return 0
            return minOf(pieceLength.toLong(), totalLength - start).toInt()
        }

        fun claimNext(): Int? = synchronized(monitor) {
            if (pieceLength <= 0 || fileLength <= 0) return null
            for (piece in firstPiece()..lastPiece()) {
                if (!have.get(piece) && piece !in claimed) {
                    claimed += piece
                    return piece
                }
            }
            null
        }

        fun release(piece: Int) = synchronized(monitor) { claimed -= piece }

        fun hasPiece(piece: Int): Boolean = synchronized(monitor) { have.get(piece) }

        fun mark(piece: Int) = synchronized(monitor) {
            have.set(piece)
            claimed -= piece
            monitor.notifyAll()
        }

        fun awaitPiece(piece: Int, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(monitor) {
                while (!have.get(piece)) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return false
                    monitor.wait(left)
                }
                return true
            }
        }

        fun writePiece(piece: Int, data: ByteArray) {
            val start = piece.toLong() * pieceLength
            val from = maxOf(start, fileOffset)
            val to = minOf(start + data.size, fileOffset + fileLength)
            if (to > from) {
                val src = (from - start).toInt()
                synchronized(raf) {
                    raf.seek(from - fileOffset)
                    raf.write(data, src, (to - from).toInt())
                }
            }
            mark(piece)
        }

        fun finished(): Boolean = synchronized(monitor) {
            if (pieceLength <= 0 || fileLength <= 0) return false
            (firstPiece()..lastPiece()).all { have.get(it) }
        }

        fun close() {
            running.set(false)
            synchronized(monitor) { monitor.notifyAll() }
            runCatching { raf.close() }
        }
    }

    private data class TorrentFile(val path: String, val length: Long, val offset: Long)

    private fun torrentFiles(info: Map<String, Any>): List<TorrentFile> {
        val single = info["length"] as? Long
        val name = (info["name"] as? ByteArray)?.toString(Charsets.UTF_8) ?: "video"
        if (single != null) return listOf(TorrentFile(name, single, 0))
        val list = info["files"] as? List<*> ?: return emptyList()
        var offset = 0L
        val out = ArrayList<TorrentFile>()
        list.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val length = map["length"] as? Long ?: return@forEach
            val path = (map["path"] as? List<*>)
                ?.mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
                ?.joinToString("/")
                ?: name
            out += TorrentFile(path, length, offset)
            offset += length
        }
        return out
    }

    private fun chooseFile(files: List<TorrentFile>, fileIdx: Int?): TorrentFile? {
        fileIdx?.let { files.getOrNull(it) }?.let { return it }
        val videos = files.filter { file ->
            val ext = file.path.substringAfterLast('.').lowercase()
            ext in VIDEO_EXT && !file.path.contains("sample", ignoreCase = true)
        }
        return videos.maxByOrNull { it.length } ?: files.maxByOrNull { it.length }
    }

    private fun parsePeers(root: Map<String, Any>): List<Peer> {
        val peers = root["peers"] ?: return emptyList()
        if (peers is ByteArray) {
            val out = ArrayList<Peer>(peers.size / 6)
            var index = 0
            while (index + 6 <= peers.size) {
                val ip = "${peers[index].toInt() and 255}.${peers[index + 1].toInt() and 255}." +
                    "${peers[index + 2].toInt() and 255}.${peers[index + 3].toInt() and 255}"
                val port = ((peers[index + 4].toInt() and 255) shl 8) or (peers[index + 5].toInt() and 255)
                out += Peer(ip, port)
                index += 6
            }
            return out
        }
        val list = peers as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val ip = (map["ip"] as? ByteArray)?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            val port = (map["port"] as? Long)?.toInt() ?: return@mapNotNull null
            Peer(ip, port)
        }
    }

    private fun splitBencode(bytes: ByteArray): Pair<Map<*, *>, ByteArray>? {
        var index = 0
        fun walk(): Boolean {
            if (index >= bytes.size) return false
            when (bytes[index].toInt().toChar()) {
                'i' -> {
                index = bytes.findByte('e'.code.toByte(), index + 1)
                    if (index < 0) return false
                    index++
                }
                'l', 'd' -> {
                    index++
                    while (index < bytes.size && bytes[index] != 'e'.code.toByte()) {
                        if (!walk()) return false
                    }
                    if (index >= bytes.size) return false
                    index++
                }
                in '0'..'9' -> {
                    val colon = bytes.findByte(':'.code.toByte(), index)
                    if (colon < 0) return false
                    val len = String(bytes, index, colon - index).toIntOrNull() ?: return false
                    index = colon + 1 + len
                }
                else -> return false
            }
            return true
        }
        if (!walk()) return null
        val dict = Bencode.decode(bytes.copyOfRange(0, index)) as? Map<*, *> ?: return null
        return dict to bytes.copyOfRange(index, bytes.size)
    }

    private fun ByteArray.findByte(target: Byte, from: Int): Int {
        for (index in from until size) if (this[index] == target) return index
        return -1
    }

    private fun peerId(): ByteArray {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val tail = CharArray(12) { alphabet[java.util.concurrent.ThreadLocalRandom.current().nextInt(alphabet.length)] }
        return ("-CS1100-" + String(tail)).toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun rawQuery(bytes: ByteArray): String = buildString(bytes.size * 3) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            val plain = value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
                value in '0'.code..'9'.code || value == '-'.code || value == '.'.code || value == '_'.code
            if (plain) append(value.toChar()) else append("%%%02X".format(value))
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toLong() and 0xff)
        return value
    }

    private data class Peer(val host: String, val port: Int)

    private val EXT_HANDSHAKE = "d1:md11:ut_metadatai1ee".toByteArray()
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "https://tracker.tamersunion.org:443/announce",
    )
    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "m2ts")
    private const val BLOCK = 16 * 1024
    private const val META_PIECE = 16 * 1024
}

/** Bencode (BitTorrent's encoding) for tracker replies and torrent metadata. */
internal object Bencode {
    fun decode(bytes: ByteArray): Any = Parser(bytes).parse()

    private class Parser(val bytes: ByteArray) {
        var index = 0

        fun parse(): Any {
            if (index >= bytes.size) throw IllegalArgumentException("truncated bencode")
            return when (val marker = bytes[index].toInt().toChar()) {
                'i' -> integer()
                'l' -> list()
                'd' -> dict()
                in '0'..'9' -> raw()
                else -> throw IllegalArgumentException("bad bencode '$marker'")
            }
        }

        private fun integer(): Long {
            index++
            val start = index
            if (index < bytes.size && bytes[index] == '-'.code.toByte()) index++
            while (index < bytes.size && bytes[index] != 'e'.code.toByte()) index++
            val value = String(bytes, start, index - start).toLong()
            index++
            return value
        }

        private fun raw(): ByteArray {
            val start = index
            while (index < bytes.size && bytes[index] != ':'.code.toByte()) index++
            val length = String(bytes, start, index - start).toInt()
            index++
            val out = bytes.copyOfRange(index, index + length)
            index += length
            return out
        }

        private fun list(): List<Any> {
            index++
            val out = mutableListOf<Any>()
            while (index < bytes.size && bytes[index] != 'e'.code.toByte()) out += parse()
            index++
            return out
        }

        private fun dict(): Map<String, Any> {
            index++
            val out = linkedMapOf<String, Any>()
            while (index < bytes.size && bytes[index] != 'e'.code.toByte()) {
                out[raw().toString(Charsets.UTF_8)] = parse()
            }
            index++
            return out
        }
    }
}
