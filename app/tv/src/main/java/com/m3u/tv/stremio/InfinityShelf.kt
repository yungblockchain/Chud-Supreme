package com.m3u.tv.stremio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Local library and watched marks. Trakt merges into the same rows when it is connected. */
@Singleton
class InfinityShelf @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("infinity_shelf", Context.MODE_PRIVATE)

    fun library(): List<CatalogItem> = read(KEY_LIBRARY)

    fun contains(type: String, id: String): Boolean =
        library().any { it.type == type && it.id == id }

    /** @return true when the title is in the library after the toggle. */
    fun toggle(item: CatalogItem): Boolean {
        val current = library().filterNot { it.type == item.type && it.id == item.id }
        val added = current.size == library().size
        save(KEY_LIBRARY, if (added) listOf(item) + current else current)
        return added
    }

    fun watched(): Set<String> =
        prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty()

    fun markWatched(key: String) {
        if (key.isBlank()) return
        prefs.edit().putStringSet(KEY_WATCHED, watched() + key).apply()
    }

    private fun read(key: String): List<CatalogItem> = runCatching {
        val raw = prefs.getString(key, null) ?: return emptyList()
        StremioHttp.json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            CatalogItem(
                id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                type = item["type"]?.jsonPrimitive?.content ?: "movie",
                name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                poster = item["poster"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                background = item["background"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                posterShape = null,
                releaseInfo = item["releaseInfo"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                imdbRating = item["imdbRating"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                description = item["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, items: List<CatalogItem>) {
        val json = buildJsonArray {
            items.take(80).forEach { item ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(item.id))
                        put("type", JsonPrimitive(item.type))
                        put("name", JsonPrimitive(item.name))
                        put("poster", JsonPrimitive(item.poster.orEmpty()))
                        put("background", JsonPrimitive(item.background.orEmpty()))
                        put("releaseInfo", JsonPrimitive(item.releaseInfo.orEmpty()))
                        put("imdbRating", JsonPrimitive(item.imdbRating.orEmpty()))
                        put("description", JsonPrimitive(item.description.orEmpty()))
                    },
                )
            }
        }
        prefs.edit().putString(key, json.toString()).apply()
    }

    private companion object {
        const val KEY_LIBRARY = "library"
        const val KEY_WATCHED = "watched"
    }
}
