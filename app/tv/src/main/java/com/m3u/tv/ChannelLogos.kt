package com.m3u.tv

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Logos for channels that come without one, from the tv-logo/tv-logos collection on GitHub (free,
 * thousands of channels by country). The list of files is fetched once a week and kept on the
 * stick; a channel gets a logo only when its name matches a file's name exactly (after quality
 * marks and country prefixes are set aside), so a wrong logo is unlikely.
 * ---------------------------------------------------------------------------------------------- */

@Singleton
class ChannelLogos @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheFile: File get() = File(context.filesDir, CACHE_NAME)

    /** Matching name to file path in the collection; empty until loaded. */
    private val _index = MutableStateFlow<Map<String, String>>(emptyMap())
    val index: StateFlow<Map<String, String>> = _index.asStateFlow()
    private val looked = ConcurrentHashMap<String, String>()

    init {
        scope.launch { load() }
    }

    /** The logo address for [title], or null. */
    fun logoFor(title: String): String? {
        val index = _index.value
        if (index.isEmpty()) return null
        val key = looked.getOrPut(title) { nameKey(title) }
        return index[key]?.let { RAW + it }
    }

    private fun load() {
        val cached = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
        val fresh = cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < REFRESH_MS
        cached?.let { text -> _index.value = build(text.lineSequence()) }
        if (fresh && cached != null) return
        val paths = fetchPaths() ?: return
        runCatching { cacheFile.writeText(paths.joinToString("\n")) }
        _index.value = build(paths.asSequence())
    }

    /** Every logo file's path in the collection (one request to GitHub's tree API). */
    private fun fetchPaths(): List<String>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(TREE).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ChudSupreme")
            }
            if (connection.responseCode != 200) return null
            val root = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonObject
                ?: return null
            (root["tree"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val path = item["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                path.takeIf { it.startsWith("countries/") && it.endsWith(".png", ignoreCase = true) }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Name key to path; the person's own country wins when two countries share a name. */
    private fun build(paths: Sequence<String>): Map<String, String> {
        val home = Locale.getDefault().displayCountry.lowercase(Locale.ROOT).replace(' ', '-')
        val out = HashMap<String, String>()
        paths.filter { it.isNotBlank() }.forEach { path ->
            val parts = path.split('/')
            if (parts.size < 3) return@forEach
            val country = parts[1]
            val file = parts.last().substringBeforeLast('.')
            // "bbc-one-uk": the last part is the country's code.
            val name = file.substringBeforeLast('-', file).takeIf { file.substringAfterLast('-').length in 2..3 } ?: file
            val key = compact(name)
            if (key.length < MIN_KEY) return@forEach
            if (!out.containsKey(key) || country == home) out[key] = path
        }
        return out
    }

    private fun nameKey(title: String): String {
        val base = ChannelVariants.key(title)
        return compact(COUNTRY_PREFIX.replace(base, ""))
    }

    private fun compact(text: String): String = text.lowercase(Locale.ROOT).replace(NOT_WORD, "").replace("&", "and")

    private companion object {
        const val TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
        const val RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"
        const val CACHE_NAME = "tv-logos-index.txt"
        const val REFRESH_MS = 7 * 24 * 60 * 60_000L
        const val MIN_KEY = 3
        val NOT_WORD = Regex("[^\\p{L}\\p{N}&]+")
        /** "uk ", "us ", "de " and the like at the start of a provider's channel name. */
        val COUNTRY_PREFIX = Regex("^(uk|gb|us|usa|ca|au|nz|ie|de|at|ch|fr|be|nl|es|pt|it|gr|tr|pl|ro|se|no|dk|fi|in|pk|br|mx|ar|za|ae|sa)\\s+")
    }
}
