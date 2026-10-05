package com.m3u.tv.stremio

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * How the Infinity page is laid out: which rows show, in what order and under what name, how
 * cards look, and whether the hero follows the focused title. Rows are keyed by addon, type and
 * catalog id, so a re-installed addon keeps its arrangement.
 * ---------------------------------------------------------------------------------------------- */

enum class CardStyle { Poster, Landscape }

@Immutable
data class RowLayout(
    val order: Int = Int.MAX_VALUE,
    val name: String? = null,
    val hidden: Boolean = false,
)

@Immutable
data class InfinityLayout(
    val rows: Map<String, RowLayout> = emptyMap(),
    val cardStyle: CardStyle = CardStyle.Poster,
    val continueStyle: CardStyle = CardStyle.Landscape,
    val hero: Boolean = true,
    val ratingsOnCards: Boolean = true,
) {
    fun of(key: String): RowLayout = rows[key] ?: RowLayout()

    /** [rows] in the saved order (rows never moved keep their catalog place), minus hidden ones. */
    fun arrange(rows: List<CatalogRow>): List<CatalogRow> = rows
        .withIndex()
        .filterNot { of(it.value.key).hidden }
        .sortedWith(compareBy({ placeOf(it.value.key, it.index) }, { it.index }))
        .map { it.value }

    /** A row's saved place, or its catalog position when it was never moved. */
    private fun placeOf(key: String, index: Int): Int = rows[key]?.order?.takeIf { it != Int.MAX_VALUE } ?: index

    fun nameOf(row: CatalogRow): String = of(row.key).name?.takeIf { it.isNotBlank() } ?: row.name
}

/** The key a row is remembered by. */
val CatalogRow.key: String get() = "$addonId:$type:$catalogId"

@Singleton
class InfinityLayoutStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("infinity_layout", Context.MODE_PRIVATE)
    private val _layout = MutableStateFlow(read())
    val layout: StateFlow<InfinityLayout> = _layout.asStateFlow()

    fun update(transform: (InfinityLayout) -> InfinityLayout) {
        val next = transform(_layout.value)
        _layout.value = next
        prefs.edit().putString(KEY, write(next)).apply()
    }

    fun updateRow(key: String, transform: (RowLayout) -> RowLayout) = update { layout ->
        layout.copy(rows = layout.rows + (key to transform(layout.of(key))))
    }

    /**
     * Moves [key] by [delta] places among [shown] (the rows as displayed). Every row, hidden ones
     * included, gets an explicit place, so un-hiding one later puts it back where it was.
     */
    fun move(shown: List<CatalogRow>, hidden: List<CatalogRow>, key: String, delta: Int) {
        val order = shown.map { it.key }.toMutableList()
        val from = order.indexOf(key)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        update { layout ->
            val rows = layout.rows.toMutableMap()
            (order + hidden.map { it.key }).forEachIndexed { index, rowKey ->
                rows[rowKey] = (rows[rowKey] ?: RowLayout()).copy(order = index)
            }
            layout.copy(rows = rows)
        }
    }

    fun reset() = update { InfinityLayout() }

    /** Re-reads the saved layout (after a restore wrote the file directly). */
    fun reload() {
        _layout.value = read()
    }

    private fun read(): InfinityLayout = runCatching {
        val raw = prefs.getString(KEY, null) ?: return InfinityLayout()
        val root = Json.parseToJsonElement(raw).jsonObject
        InfinityLayout(
            rows = (root["rows"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                val item = value.jsonObject
                RowLayout(
                    order = item["order"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE,
                    name = item["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    hidden = item["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            },
            cardStyle = root["cardStyle"]?.jsonPrimitive?.content?.let { name -> CardStyle.entries.firstOrNull { it.name == name } }
                ?: CardStyle.Poster,
            continueStyle = root["continueStyle"]?.jsonPrimitive?.content?.let { name -> CardStyle.entries.firstOrNull { it.name == name } }
                ?: CardStyle.Landscape,
            hero = root["hero"]?.jsonPrimitive?.booleanOrNull ?: true,
            ratingsOnCards = root["ratings"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }.getOrDefault(InfinityLayout())

    private fun write(layout: InfinityLayout): String = JsonObject(
        mapOf(
            "rows" to JsonObject(
                layout.rows.mapValues { (_, row) ->
                    JsonObject(
                        buildMap {
                            put("order", JsonPrimitive(row.order))
                            row.name?.let { put("name", JsonPrimitive(it)) }
                            put("hidden", JsonPrimitive(row.hidden))
                        }
                    )
                }
            ),
            "cardStyle" to JsonPrimitive(layout.cardStyle.name),
            "continueStyle" to JsonPrimitive(layout.continueStyle.name),
            "hero" to JsonPrimitive(layout.hero),
            "ratings" to JsonPrimitive(layout.ratingsOnCards),
        )
    ).toString()

    private companion object {
        const val KEY = "layout"
    }
}
