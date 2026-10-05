package com.m3u.tv

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Guide data from epg.pw, only for a channel whose own guide is empty. Names match exactly, and
 * only when that name belongs to one channel in this country's list. Anything unsure is skipped.
 */
internal object EpgPw {
    private val lock = Mutex()
    private var country: String? = null
    private var names: Map<String, String> = emptyMap()
    private val link = Regex("""href="/last/(\d+)\.html[^"]*"[^>]*>([^<]+)<""")
    private val dayFormat = DateTimeFormatter.BASIC_ISO_DATE

    suspend fun programmes(title: String, from: Long, to: Long): List<GuideProgramme> = withContext(Dispatchers.IO) {
        val wanted = OpenSubtitles.cleanTitle(title).lowercase(Locale.ROOT)
        if (wanted.length < 2) return@withContext emptyList()
        val id = channelId(wanted) ?: return@withContext emptyList()
        val startDay = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), ZoneOffset.UTC).toLocalDate()
        val endDay = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(to), ZoneOffset.UTC).toLocalDate()
        val days = ArrayList<LocalDate>(2)
        days += startDay
        if (endDay != startDay && days.size < 2) days += endDay
        val found = ArrayList<GuideProgramme>()
        for (day in days) {
            found += dayProgrammes(id, day)
        }
        found.filter { it.endMillis > from && it.startMillis < to }.distinctBy { it.startMillis to it.title }
    }

    private suspend fun channelId(wanted: String): String? {
        val code = Locale.getDefault().country.ifBlank { "GB" }.lowercase(Locale.ROOT)
        lock.withLock {
            if (country != code) {
                country = code
                names = loadNames(code)
            }
            return names[wanted]
        }
    }

    private fun loadNames(code: String): Map<String, String> {
        val html = readText("https://epg.pw/areas/$code.html?lang=en") ?: return emptyMap()
        val grouped = HashMap<String, MutableSet<String>>()
        for (match in link.findAll(html)) {
            val name = OpenSubtitles.cleanTitle(match.groupValues[2]).lowercase(Locale.ROOT)
            if (name.length < 2) continue
            grouped.getOrPut(name) { LinkedHashSet() }.add(match.groupValues[1])
        }
        return grouped.mapNotNull { (name, ids) -> if (ids.size == 1) name to ids.first() else null }.toMap()
    }

    private fun dayProgrammes(id: String, day: LocalDate): List<GuideProgramme> {
        val root = getJson(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true },
            "https://epg.pw/api/epg.json?channel_id=$id&date=${day.format(dayFormat)}",
        ) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val list = root["epg_list"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val rows = list.mapNotNull { element ->
            val item = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val title = (item["title"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val start = (item["start_date"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            val description = (item["desc"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
            start to (title to description)
        }.sortedBy { it.first }
        return rows.mapIndexed { index, (start, text) ->
            val end = rows.getOrNull(index + 1)?.first ?: (start + 30 * 60_000L)
            GuideProgramme(
                title = text.first,
                description = text.second,
                startMillis = start,
                endMillis = end,
                serverStart = null,
                hasArchive = false,
            )
        }
    }

    private fun readText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 12_000
                readTimeout = 20_000
                setRequestProperty("Accept", "text/html")
                setRequestProperty("User-Agent", "ChudSupreme/1.0 (Android TV)")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
