package com.m3u.tv.stremio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Stremio addon protocol client: manifest, catalogs, meta and streams.
 * https://github.com/Stremio/stremio-addon-sdk
 */
object StremioClient {

    suspend fun fetchManifest(manifestUrl: String): InstalledAddon {
        val url = if (manifestUrl.contains("manifest.json")) {
            manifestUrl.trim()
        } else {
            "${manifestUrl.trim().stremioBaseUrl()}/manifest.json"
        }
        val root = StremioHttp.getJson(url).asObject()
            ?: throw StremioHttpException(null, "Addon did not return a manifest")
        return parseManifest(url, root)
    }

    suspend fun catalog(
        addon: InstalledAddon,
        type: String,
        catalogId: String,
        extras: Map<String, String> = emptyMap(),
    ): List<CatalogItem> {
        val extraPath = extras.entries
            .filter { it.value.isNotBlank() }
            .joinToString("&") { (key, value) ->
                "$key=${java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
            }
            .takeIf { it.isNotEmpty() }
            ?.let { "/$it" }
            .orEmpty()
        val url = "${addon.manifestUrl.stremioBaseUrl()}/catalog/${enc(type)}/${enc(catalogId)}$extraPath.json"
        val root = StremioHttp.getJson(url).asObject() ?: return emptyList()
        val metas = root["metas"] as? JsonArray ?: return emptyList()
        return metas.mapNotNull { it.asObject()?.toCatalogItem(type) }
    }

    suspend fun search(addon: InstalledAddon, query: String, type: String? = null): List<CatalogItem> {
        val catalogs = addon.catalogs.filter { catalog ->
            (type == null || catalog.type == type) &&
                (catalog.extra.contains("search") || "catalog" in addon.resources)
        }
        if (catalogs.isEmpty()) return emptyList()
        val results = mutableListOf<CatalogItem>()
        for (catalog in catalogs.take(4)) {
            runCatching {
                results += catalog(addon, catalog.type, catalog.id, mapOf("search" to query))
            }
        }
        return results.distinctBy { it.type to it.id }
    }

    suspend fun meta(addon: InstalledAddon, type: String, id: String): MetaDetails? {
        if ("meta" !in addon.resources) return null
        val url = "${addon.manifestUrl.stremioBaseUrl()}/meta/${enc(type)}/${enc(id)}.json"
        val root = StremioHttp.getJson(url).asObject() ?: return null
        val meta = root["meta"] as? JsonObject ?: return null
        return meta.toDetails(type)
    }

    suspend fun streams(addon: InstalledAddon, type: String, id: String): List<StreamSource> {
        if ("stream" !in addon.resources) return emptyList()
        val url = "${addon.manifestUrl.stremioBaseUrl()}/stream/${enc(type)}/${enc(id)}.json"
        val root = StremioHttp.getJson(url).asObject() ?: return emptyList()
        val streams = root["streams"] as? JsonArray ?: return emptyList()
        return streams.mapNotNull { it.asObject()?.toStream(addon) }
    }

    fun parseManifest(manifestUrl: String, root: JsonObject): InstalledAddon {
        val resources = parseResources(root["resources"])
        val catalogs = (root["catalogs"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            val extraNames = ((item["extra"] as? JsonArray) ?: (item["extraSupported"] as? JsonArray))
                .orEmpty()
                .mapNotNull { extra ->
                    when (extra) {
                        is JsonPrimitive -> extra.contentOrNull
                        is JsonObject -> extra.text("name")
                        else -> null
                    }
                }
            AddonCatalog(
                type = item.text("type") ?: return@mapNotNull null,
                id = item.text("id") ?: return@mapNotNull null,
                name = item.text("name") ?: item.text("id") ?: return@mapNotNull null,
                extra = extraNames,
            )
        }
        val types = (root["types"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
            .ifEmpty { catalogs.map { it.type }.distinct() }
        return InstalledAddon(
            id = root.text("id") ?: manifestUrl,
            name = root.text("name") ?: "Addon",
            version = root.text("version").orEmpty(),
            description = root.text("description").orEmpty(),
            manifestUrl = manifestUrl,
            logo = root.text("logo") ?: root.text("background"),
            types = types,
            catalogs = catalogs,
            resources = resources,
        )
    }

    private fun parseResources(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.text("name")
                else -> null
            }
        }
    }

    private fun JsonObject.toCatalogItem(fallbackType: String): CatalogItem? {
        val id = text("id") ?: return null
        val name = text("name") ?: text("title") ?: return null
        return CatalogItem(
            id = id,
            type = text("type") ?: fallbackType,
            name = name,
            poster = text("poster"),
            background = text("background") ?: text("poster"),
            posterShape = text("posterShape"),
            releaseInfo = text("releaseInfo") ?: text("year"),
            imdbRating = text("imdbRating"),
            description = text("description"),
        )
    }

    private fun JsonObject.toDetails(fallbackType: String): MetaDetails {
        val videos = (this["videos"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element.asObject() ?: return@mapNotNull null
            MetaVideo(
                id = item.text("id") ?: return@mapNotNull null,
                title = item.text("title") ?: item.text("name") ?: "Episode",
                season = item.int("season"),
                episode = item.int("episode"),
                released = item.text("released"),
                thumbnail = item.text("thumbnail"),
                overview = item.text("overview"),
            )
        }
        val imdb = text("imdb_id") ?: text("imdbId") ?: text("id")?.takeIf { it.startsWith("tt") }
        return MetaDetails(
            id = text("id") ?: imdb.orEmpty(),
            type = text("type") ?: fallbackType,
            name = text("name") ?: "Title",
            poster = text("poster"),
            background = text("background"),
            logo = text("logo"),
            description = text("description"),
            releaseInfo = text("releaseInfo") ?: text("year"),
            imdbRating = text("imdbRating"),
            genres = stringList("genres"),
            runtime = text("runtime"),
            director = stringList("director"),
            cast = stringList("cast"),
            imdbId = imdb,
            videos = videos,
        )
    }

    private fun JsonObject.toStream(addon: InstalledAddon): StreamSource? {
        val url = text("url")
        val infoHash = text("infoHash") ?: url?.let { MagnetLinks.infoHash(it) }
        val magnet = url?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            ?: infoHash?.let { MagnetLinks.magnet(it, text("name") ?: text("title")) }
        if (url == null && infoHash == null) return null
        val title = text("title") ?: text("description")
        val name = text("name") ?: addon.name
        return StreamSource(
            addonId = addon.id,
            addonName = addon.name,
            name = name.replace('\n', ' ').trim(),
            title = title,
            url = url,
            infoHash = infoHash,
            fileIdx = int("fileIdx"),
            magnet = magnet,
            quality = MagnetLinks.qualityFrom(name, title),
            size = SIZE.find(title.orEmpty())?.groupValues?.getOrNull(1),
            seeders = SEEDERS.find(title.orEmpty())?.groupValues?.getOrNull(1),
            bingeGroup = (this["behaviorHints"] as? JsonObject)?.text("bingeGroup"),
        )
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val value = this[name] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> value.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            else -> emptyList()
        }
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private val SIZE = Regex("""💾\s*([0-9.]+\s*[KMGT]B)""", RegexOption.IGNORE_CASE)
    private val SEEDERS = Regex("👤\\s*([0-9]+)")
}
