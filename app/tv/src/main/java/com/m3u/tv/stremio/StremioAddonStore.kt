package com.m3u.tv.stremio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class StremioAddonStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("stremio_addons", Context.MODE_PRIVATE)

    private val _addons = MutableStateFlow(readAddons())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    var p2pEnabled: Boolean
        get() = prefs.getBoolean(KEY_P2P, true)
        set(value) { prefs.edit().putBoolean(KEY_P2P, value).apply() }

    var torrServeUrl: String
        get() = prefs.getString(KEY_TORRSERVE, DEFAULT_TORRSERVE).orEmpty().ifBlank { DEFAULT_TORRSERVE }
        set(value) {
            prefs.edit().putString(KEY_TORRSERVE, value.trim().ifBlank { DEFAULT_TORRSERVE }).apply()
        }

    fun upsert(addon: InstalledAddon) {
        val without = _addons.value.filterNot { it.id.equals(addon.id, ignoreCase = true) || it.manifestUrl == addon.manifestUrl }
        save(without + addon)
    }

    fun remove(id: String) = save(_addons.value.filterNot { it.id == id })

    fun setEnabled(id: String, enabled: Boolean) = save(
        _addons.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    )

    fun enabled(): List<InstalledAddon> = _addons.value.filter { it.enabled }

    fun replaceTorrentio(manifestUrl: String) {
        val current = _addons.value.firstOrNull { it.id.contains("torrentio", ignoreCase = true) }
            ?: _addons.value.firstOrNull { it.manifestUrl.contains("torrentio.strem.fun") }
        if (current != null) {
            save(_addons.value.map {
                if (it.id == current.id) it.copy(manifestUrl = manifestUrl) else it
            })
        }
    }

    private fun save(addons: List<InstalledAddon>) {
        _addons.value = addons
        val json = buildJsonArray {
            addons.forEach { addon ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(addon.id))
                        put("name", JsonPrimitive(addon.name))
                        put("version", JsonPrimitive(addon.version))
                        put("description", JsonPrimitive(addon.description))
                        put("manifestUrl", JsonPrimitive(addon.manifestUrl))
                        put("logo", JsonPrimitive(addon.logo ?: ""))
                        put("types", JsonArray(addon.types.map(::JsonPrimitive)))
                        put("resources", JsonArray(addon.resources.map(::JsonPrimitive)))
                        put("enabled", JsonPrimitive(addon.enabled))
                        put(
                            "catalogs",
                            buildJsonArray {
                                addon.catalogs.forEach { catalog ->
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive(catalog.type))
                                            put("id", JsonPrimitive(catalog.id))
                                            put("name", JsonPrimitive(catalog.name))
                                            put("extra", JsonArray(catalog.extra.map(::JsonPrimitive)))
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
            }
        }
        prefs.edit().putString(KEY_ADDONS, json.toString()).apply()
    }

    private fun readAddons(): List<InstalledAddon> = runCatching {
        val raw = prefs.getString(KEY_ADDONS, null) ?: return emptyList()
        StremioHttp.json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val item = element.jsonObject
            InstalledAddon(
                id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = item["name"]?.jsonPrimitive?.content ?: "Addon",
                version = item["version"]?.jsonPrimitive?.content.orEmpty(),
                description = item["description"]?.jsonPrimitive?.content.orEmpty(),
                manifestUrl = item["manifestUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                logo = item["logo"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                types = item["types"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                catalogs = item["catalogs"]?.jsonArray?.mapNotNull { catalog ->
                    val obj = catalog.jsonObject
                    AddonCatalog(
                        type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        extra = obj["extra"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    )
                }.orEmpty(),
                resources = item["resources"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                enabled = item["enabled"]?.jsonPrimitive?.content != "false",
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_ADDONS = "addons"
        const val KEY_P2P = "p2p_enabled"
        const val KEY_TORRSERVE = "torrserve_url"
        const val DEFAULT_TORRSERVE = "http://127.0.0.1:8090"
    }
}
