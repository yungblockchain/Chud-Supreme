package com.m3u.tv

import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Today's public holiday from Nager.Date, or null. Cached for the year. Weather is left as it is. */
internal object PublicHolidays {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, Map<String, String>>()

    suspend fun today(country: String = Locale.getDefault().country.ifBlank { "GB" }): String? = withContext(Dispatchers.IO) {
        val code = country.uppercase(Locale.ROOT)
        if (code.length != 2) return@withContext null
        val year = LocalDate.now().year
        val key = "$code:$year"
        val days = cache[key] ?: load(code, year).also { cache[key] = it }
        days[LocalDate.now().toString()]
    }

    private fun load(country: String, year: Int): Map<String, String> {
        val root = getJson(json, "https://date.nager.at/api/v3/PublicHolidays/$year/$country") as? JsonArray
            ?: return emptyMap()
        return root.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val date = (item["date"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val name = (item["localName"] as? JsonPrimitive)?.contentOrNull
                ?: (item["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            date to name
        }.toMap()
    }
}
