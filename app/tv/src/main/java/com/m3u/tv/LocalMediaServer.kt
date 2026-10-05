package com.m3u.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/* -------------------------------------------------------------------------------------------------
 * A tiny web server on the Fire TV's own loopback address, so the player can take things that
 * aren't plain web addresses: YouTube's DASH manifests (built on the stick), and files on network
 * shares (SMB, WebDAV with a password), streamed with byte ranges so seeking works. Only this
 * device can reach it, and it only serves what was put in it.
 *
 * It asks for the same port each time, so a file's address stays the same between app starts
 * (and "continue watching" finds its place again).
 * ---------------------------------------------------------------------------------------------- */

/** Opens a remote file at a byte offset. */
fun interface RangeOpener {
    fun open(offset: Long): InputStream
}

@Singleton
class LocalMediaServer @Inject constructor() {
    private class Document(val type: String, val bytes: ByteArray)
    private class Stream(val type: String, val length: Long, val opener: RangeOpener)

    // Least recently used goes first when full, so whatever is playing now stays served.
    private val documents: MutableMap<String, Document> = Collections.synchronizedMap(lru(MAX_DOCUMENTS))
    private val streams: MutableMap<String, Stream> = Collections.synchronizedMap(lru(MAX_STREAMS))
    private val random = SecureRandom()
    private var server: ServerSocket? = null

    // A thread for each request (players open several at once while seeking), up to a cap.
    private val pool = ThreadPoolExecutor(0, MAX_WORKERS, 60L, TimeUnit.SECONDS, SynchronousQueue())

    private fun <V> lru(capacity: Int) = object : LinkedHashMap<String, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > capacity
    }

    /** Puts [body] up and returns its address. Old documents are dropped so nothing piles up. */
    @Synchronized
    fun publish(body: String, contentType: String, extension: String): String? {
        val socket = ensureServer() ?: return null
        val token = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        documents["/$token.$extension"] = Document(contentType, body.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:${socket.localPort}/$token.$extension"
    }

    /**
     * Serves a remote file at a stable address made from [key] (the same key gives the same
     * address while the port is the usual one), with byte ranges for seeking.
     */
    @Synchronized
    fun publishStream(key: String, extension: String, contentType: String, length: Long, opener: RangeOpener): String? {
        val socket = ensureServer() ?: return null
        val path = "/f/${Integer.toHexString(key.hashCode())}${key.length.toString(16)}.$extension"
        streams[path] = Stream(contentType, length, opener)
        return "http://127.0.0.1:${socket.localPort}$path"
    }

    private fun ensureServer(): ServerSocket? {
        server?.let { if (!it.isClosed) return it }
        val socket = listOf(PREFERRED_PORT, 0).firstNotNullOfOrNull { port ->
            runCatching {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) }
            }.getOrNull()
        } ?: return null
        server = socket
        Thread({
            try {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching { pool.execute { runCatching { serve(client) } } }.onFailure { runCatching { client.close() } }
                }
            } finally {
                // A broken listener is closed, so the next publish starts a fresh one.
                runCatching { socket.close() }
            }
        }, "local-media").apply { isDaemon = true }.start()
        return socket
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            var range: String? = null
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            val parts = requestLine.split(' ')
            val method = parts.firstOrNull()
            val path = parts.getOrNull(1)?.substringBefore('?') ?: return
            val output = socket.getOutputStream()
            if (method != "GET" && method != "HEAD") return status(output, 405)
            documents[path]?.let { document ->
                head(output, 200, document.type, document.bytes.size.toLong(), extra = "")
                if (method == "GET") output.write(document.bytes)
                output.flush()
                return
            }
            val stream = streams[path] ?: return status(output, 404)
            serveStream(output, stream, range, head = method == "HEAD")
        }
    }

    private fun serveStream(output: OutputStream, stream: Stream, range: String?, head: Boolean) {
        val length = stream.length
        var start = 0L
        var end = length - 1
        val partial = range != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                // "bytes=-500": the last 500 bytes.
                val suffix = to.toLongOrNull() ?: 0L
                start = (length - suffix).coerceAtLeast(0L)
            } else {
                start = from.toLongOrNull() ?: 0L
                if (to.isNotEmpty()) end = (to.toLongOrNull() ?: end).coerceAtMost(length - 1)
            }
            if (start >= length || end < start) {
                output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
        }
        val count = end - start + 1
        // Open the file before answering, so a share that's gone gives an error, not an empty 200.
        val source = if (head) null else try {
            stream.opener.open(start)
        } catch (e: Exception) {
            return status(output, 502)
        }
        head(
            output,
            if (partial) 206 else 200,
            stream.type,
            count,
            extra = "Accept-Ranges: bytes\r\n" + (if (partial) "Content-Range: bytes $start-$end/$length\r\n" else ""),
        )
        if (source == null) {
            output.flush()
            return
        }
        source.use {
            val buffer = ByteArray(BUFFER)
            var left = count
            try {
                while (left > 0) {
                    val read = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
                output.flush()
            } catch (_: SocketException) {
                // The player let go (it seeks by opening a new request): nothing to do.
            }
        }
    }

    private fun head(output: OutputStream, code: Int, type: String, length: Long, extra: String) {
        val reason = if (code == 206) "Partial Content" else "OK"
        output.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\n$extra" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
    }

    private fun status(output: OutputStream, code: Int) {
        output.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        output.flush()
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
        const val PREFERRED_PORT = 8790
        const val MAX_WORKERS = 16
        const val MAX_DOCUMENTS = 16
        const val MAX_STREAMS = 64
        const val BUFFER = 64 * 1024
    }
}
