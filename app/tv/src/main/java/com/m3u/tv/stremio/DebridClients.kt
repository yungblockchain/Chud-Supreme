package com.m3u.tv.stremio

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class DebridAccount(
    val provider: DebridProvider,
    val username: String,
    val premium: Boolean,
    val expires: String?,
)

enum class DebridProvider(val label: String) {
    RealDebrid("Real-Debrid"),
    TorBox("TorBox"),
}

class DebridException(message: String) : Exception(message)

object RealDebridClient {
    private const val BASE = "https://api.real-debrid.com/rest/1.0"

    suspend fun user(token: String): DebridAccount {
        val root = get(token, "/user").asObject() ?: throw DebridException("Real-Debrid did not return an account")
        val type = root.text("type")
        return DebridAccount(
            provider = DebridProvider.RealDebrid,
            username = root.text("username") ?: "Real-Debrid",
            premium = type.equals("premium", ignoreCase = true),
            expires = root.text("expiration"),
        )
    }

    suspend fun unrestrict(token: String, link: String): String {
        val root = StremioHttp.postForm(
            "$BASE/unrestrict/link",
            auth(token),
            mapOf("link" to link),
        ).asObject() ?: throw DebridException("Real-Debrid did not return a link")
        return root.text("download") ?: root.text("link")
            ?: throw DebridException("Real-Debrid had no download URL")
    }

    suspend fun resolveMagnet(token: String, magnet: String, fileIdx: Int?): String {
        val added = StremioHttp.postForm(
            "$BASE/torrents/addMagnet",
            auth(token),
            mapOf("magnet" to magnet),
        ).asObject() ?: throw DebridException("Real-Debrid rejected the magnet")
        val id = added.text("id") ?: throw DebridException("Real-Debrid did not return a torrent id")
        runCatching {
            StremioHttp.postForm(
                "$BASE/torrents/selectFiles/$id",
                auth(token),
                mapOf("files" to "all"),
            )
        }
        repeat(40) {
            val info = get(token, "/torrents/info/$id").asObject()
                ?: throw DebridException("Lost the Real-Debrid torrent")
            val status = info.text("status").orEmpty()
            if (status == "downloaded") {
                val links = (info["links"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                val files = info["files"] as? JsonArray ?: JsonArray(emptyList())
                val chosen = pickFileIndex(files, fileIdx)
                val link = links.getOrNull(chosen) ?: links.firstOrNull()
                    ?: throw DebridException("Real-Debrid torrent has no files")
                return unrestrict(token, link)
            }
            if (status in listOf("magnet_error", "error", "virus", "dead")) {
                throw DebridException("Real-Debrid: $status")
            }
            delay(1_500)
        }
        throw DebridException("Real-Debrid is still downloading that torrent")
    }

    private suspend fun get(token: String, path: String) = StremioHttp.getJson("$BASE$path", auth(token))

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun pickFileIndex(files: JsonArray, fileIdx: Int?): Int {
        if (fileIdx != null && fileIdx in files.indices) return fileIdx
        var best = 0
        var bestSize = -1L
        files.forEachIndexed { index, element ->
            val size = ((element as? JsonObject)?.get("bytes") as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            if (size > bestSize) {
                bestSize = size
                best = index
            }
        }
        return best
    }
}

object TorBoxClient {
    private const val BASE = "https://api.torbox.app/v1/api"

    suspend fun user(token: String): DebridAccount {
        val root = StremioHttp.getJson("$BASE/user/me", auth(token)).asObject()
            ?: throw DebridException("TorBox did not return an account")
        val data = root["data"] as? JsonObject ?: root
        val premium = data["premium"]?.jsonPrimitive?.contentOrNull == "true" ||
            data.int("plan")?.let { it > 0 } == true
        return DebridAccount(
            provider = DebridProvider.TorBox,
            username = data.text("email") ?: data.text("user") ?: "TorBox",
            premium = premium,
            expires = data.text("premium_expires_at") ?: data.text("expiry"),
        )
    }

    suspend fun resolveMagnet(token: String, magnet: String, fileIdx: Int?): String {
        val hash = MagnetLinks.infoHash(magnet)
        if (hash != null) {
            cachedLink(token, hash, fileIdx)?.let { return it }
        }
        StremioHttp.postForm(
            "$BASE/torrents/createtorrent",
            auth(token),
            mapOf("magnet" to magnet, "seed" to "1", "allow_zip" to "false"),
        )
        repeat(30) {
            delay(1_200)
            val list = StremioHttp.getJson("$BASE/torrents/mylist", auth(token)).asObject()
            val items = (list?.get("data") as? JsonArray) ?: return@repeat
            val torrent = items.mapNotNull { it.asObject() }.firstOrNull { item ->
                hash != null && item.text("hash")?.equals(hash, ignoreCase = true) == true ||
                    item.text("magnet") == magnet
            } ?: items.mapNotNull { it.asObject() }.lastOrNull() ?: return@repeat
            val id = torrent.int("id") ?: torrent.text("id")?.toIntOrNull() ?: return@repeat
            val files = torrent["files"] as? JsonArray ?: JsonArray(emptyList())
            val downloadReady = torrent.text("download_state") in listOf("completed", "cached") ||
                torrent["cached"]?.jsonPrimitive?.contentOrNull == "true"
            if (!downloadReady && files.isEmpty()) return@repeat
            val file = pickTorboxFile(files, fileIdx)
            val fileId = file?.int("id") ?: file?.text("id")?.toIntOrNull()
            val url = requestDl(token, id, fileId)
            if (url != null) return url
        }
        throw DebridException("TorBox is still caching that torrent")
    }

    private suspend fun cachedLink(token: String, hash: String, fileIdx: Int?): String? {
        val checked = runCatching {
            StremioHttp.getJson(
                "$BASE/torrents/checkcached?hash=$hash&format=object",
                auth(token),
            ).asObject()
        }.getOrNull() ?: return null
        val data = checked["data"] as? JsonObject ?: return null
        val entry = data[hash] as? JsonObject ?: data[hash.lowercase()] as? JsonObject ?: return null
        if (entry["cached"]?.jsonPrimitive?.contentOrNull != "true" && entry.keys.isEmpty()) return null
        StremioHttp.postForm(
            "$BASE/torrents/createtorrent",
            auth(token),
            mapOf("magnet" to MagnetLinks.magnet(hash), "seed" to "1"),
        )
        delay(800)
        val list = StremioHttp.getJson("$BASE/torrents/mylist", auth(token)).asObject()
        val items = (list?.get("data") as? JsonArray) ?: return null
        val torrent = items.mapNotNull { it.asObject() }.firstOrNull {
            it.text("hash")?.equals(hash, ignoreCase = true) == true
        } ?: return null
        val id = torrent.int("id") ?: torrent.text("id")?.toIntOrNull() ?: return null
        val files = torrent["files"] as? JsonArray ?: JsonArray(emptyList())
        val file = pickTorboxFile(files, fileIdx)
        return requestDl(token, id, file?.int("id") ?: file?.text("id")?.toIntOrNull())
    }

    private suspend fun requestDl(token: String, torrentId: Int, fileId: Int?): String? {
        val query = buildString {
            append("token=").append(java.net.URLEncoder.encode(token, "UTF-8"))
            append("&torrent_id=").append(torrentId)
            append("&zip_link=false")
            if (fileId != null) append("&file_id=").append(fileId)
        }
        val root = StremioHttp.getJson("$BASE/torrents/requestdl?$query", auth(token)).asObject()
        return root?.text("data") ?: (root?.get("data") as? JsonObject)?.text("url")
    }

    private fun pickTorboxFile(files: JsonArray, fileIdx: Int?): JsonObject? {
        val objects = files.mapNotNull { it.asObject() }
        if (fileIdx != null) objects.getOrNull(fileIdx)?.let { return it }
        return objects.maxByOrNull { it.text("size")?.toLongOrNull() ?: it.int("size")?.toLong() ?: 0L }
    }

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")
}

object TorrentioConfig {
    fun manifestUrl(realDebrid: String?, torbox: String?): String {
        val options = buildList {
            realDebrid?.takeIf { it.isNotBlank() }?.let { add("realdebrid=$it") }
            torbox?.takeIf { it.isNotBlank() }?.let { add("torbox=$it") }
        }
        return if (options.isEmpty()) {
            "https://torrentio.strem.fun/manifest.json"
        } else {
            "https://torrentio.strem.fun/${options.joinToString("|")}/manifest.json"
        }
    }
}
