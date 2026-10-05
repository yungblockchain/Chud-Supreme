package com.m3u.tv

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/* -------------------------------------------------------------------------------------------------
 * The phone page: while it's switched on, the Fire TV serves a small web page on the home
 * network. A phone opens it (QR code on the TV) and types there instead of on the remote: a
 * search, a question for Claude, an Xtream login or M3U link, or API keys. Every change needs
 * the 6-digit PIN shown on the TV; wrong PINs lock it for a minute. Keys go straight into the
 * encrypted store and are never shown back.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class CompanionInfo(val address: String, val pin: String)

sealed interface PhoneMessage {
    data class Search(val query: String) : PhoneMessage
    data class AskClaude(val question: String) : PhoneMessage
    data class XtreamLogin(val server: String, val username: String, val password: String) : PhoneMessage
    data class M3uPlaylist(val url: String, val epgUrl: String) : PhoneMessage
    data class KeySaved(val name: SecretName) : PhoneMessage
    data class SkinApplied(val name: String) : PhoneMessage
    data class JoinParty(val code: String) : PhoneMessage
}

@Singleton
class PhoneCompanion @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    private val skins: SkinStore,
) {
    private val _info = MutableStateFlow<CompanionInfo?>(null)
    val info: StateFlow<CompanionInfo?> = _info.asStateFlow()

    private val _messages = MutableSharedFlow<PhoneMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PhoneMessage> = _messages.asSharedFlow()

    private val random = SecureRandom()
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    @Volatile private var pin: String = ""
    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L

    val running: Boolean get() = server != null

    /** The port the page is on while running. */
    val port: Int? get() = server?.localPort

    /** Answers watch-party requests (set by [WatchParty]); a guest's code is checked there. */
    @Volatile var party: PartyEndpoint? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = PORTS.firstNotNullOfOrNull { port ->
            runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrNull()
        } ?: return
        pin = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
        failures = 0
        server = socket
        val pool = Executors.newFixedThreadPool(WORKERS).also { workers = it }
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // A bad or dropped connection must never take the app down.
                runCatching { pool.execute { runCatching { handle(client) } } }.onFailure { client.close() }
            }
        }, "phone-page").apply { isDaemon = true }.start()
        _info.value = CompanionInfo(address = "http://${localAddress() ?: "fire-tv"}:${socket.localPort}", pin = pin)
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
        _info.value = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    length = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> respond(output, 200, "text/html; charset=utf-8", page())
                // The current skin as a file, to share or edit.
                method == "GET" && path == "/skin.json" ->
                    respond(output, 200, JSON, skins.current.value.toJson().toString())
                // A watch-party guest asking what's playing and where. The code is the key.
                method == "GET" && path == "/party" -> {
                    val form = parseForm(query)
                    val json = party?.state(form["code"].orEmpty().take(16), form["guest"].orEmpty().take(16))
                    if (json == null) respond(output, 404, JSON, """{"ok":false,"error":"no_party"}""")
                    else respond(output, 200, JSON, json)
                }
                method == "POST" && path.startsWith("/api/") -> {
                    if (length !in 0..MAX_BODY) return respond(output, 413, JSON, """{"ok":false,"error":"too_large"}""")
                    val body = readBytes(input, length).toString(Charsets.UTF_8)
                    val (status, json) = api(path.removePrefix("/api/"), parseForm(body))
                    respond(output, status, JSON, json)
                }
                else -> respond(output, 404, "text/plain", "Not found")
            }
        }
    }

    private fun api(action: String, form: Map<String, String>): Pair<Int, String> {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return 429 to """{"ok":false,"error":"locked"}"""
        if (!pinMatches(form["pin"].orEmpty())) {
            failures++
            if (failures >= MAX_FAILURES) {
                lockedUntil = now + LOCK_MS
                failures = 0
            }
            return 403 to """{"ok":false,"error":"pin"}"""
        }
        failures = 0
        fun field(name: String) = form[name].orEmpty().trim().take(MAX_FIELD)
        val message: PhoneMessage = when (action) {
            "search" -> PhoneMessage.Search(field("text").ifEmpty { return 400 to BAD })
            "claude" -> PhoneMessage.AskClaude(field("text").ifEmpty { return 400 to BAD })
            "xtream" -> PhoneMessage.XtreamLogin(
                server = field("server").ifEmpty { return 400 to BAD },
                username = field("username").ifEmpty { return 400 to BAD },
                password = field("password").ifEmpty { return 400 to BAD },
            )
            "m3u" -> PhoneMessage.M3uPlaylist(
                url = field("url").ifEmpty { return 400 to BAD },
                epgUrl = field("epg"),
            )
            "key" -> {
                val name = SecretName.entries.firstOrNull { it.key == field("name") } ?: return 400 to BAD
                val value = field("value").ifEmpty { return 400 to BAD }
                secrets.put(name, value)
                PhoneMessage.KeySaved(name)
            }
            "party" -> PhoneMessage.JoinParty(field("code").ifEmpty { return 400 to BAD }.take(8))
            "skin" -> {
                val raw = form["skin"].orEmpty().trim().take(MAX_SKIN).ifEmpty { return 400 to BAD }
                val skin = skins.importSkin(raw) ?: return 400 to """{"ok":false,"error":"not_a_skin"}"""
                PhoneMessage.SkinApplied(skin.name)
            }
            else -> return 404 to """{"ok":false,"error":"unknown"}"""
        }
        _messages.tryEmit(message)
        return 200 to """{"ok":true}"""
    }

    private fun pinMatches(candidate: String): Boolean =
        pin.isNotEmpty() && MessageDigest.isEqual(candidate.trim().toByteArray(), pin.toByteArray())

    private fun page(): String =
        context.resources.openRawResource(R.raw.phone_page).bufferedReader().use { it.readText() }

    private fun respond(output: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error"
        }
        output.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
            if (buffer.size() > MAX_LINE) return null
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return bytes.copyOf(read)
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            runCatching {
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }.getOrNull()
        }.toMap()

    /** The Fire TV's address on the home network (Wi-Fi or Ethernet). */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        /** Fixed ports first so a party code can name the port in two bits; 0 = any free port. */
        val PORTS = listOf(8765, 8766, 8767, 0)
        private const val WORKERS = 2
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY = 16 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_FIELD = 2_048
        private const val MAX_SKIN = 8_192
        private const val MAX_FAILURES = 5
        private const val LOCK_MS = 60_000L
        private const val JSON = "application/json"
        private const val BAD = """{"ok":false,"error":"missing"}"""
    }
}
