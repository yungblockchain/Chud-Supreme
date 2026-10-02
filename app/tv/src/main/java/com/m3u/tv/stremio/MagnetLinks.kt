package com.m3u.tv.stremio

import java.util.Locale

/** Magnet / info-hash helpers used by debrid and the P2P engine. */
object MagnetLinks {
    private val HEX = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Z2-7]{32}$")
    private val XT = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)

    fun infoHash(magnetOrHash: String): String? {
        val raw = magnetOrHash.trim()
        if (raw.startsWith("magnet:", ignoreCase = true)) {
            val xt = XT.find(raw)?.groupValues?.getOrNull(1) ?: return null
            return normalizeHash(xt)
        }
        return normalizeHash(raw)
    }

    fun magnet(hashOrMagnet: String, displayName: String? = null, trackers: List<String> = emptyList()): String {
        val existing = hashOrMagnet.trim()
        if (existing.startsWith("magnet:", ignoreCase = true)) {
            return existing
        }
        val hash = infoHash(existing) ?: existing
        val parts = mutableListOf("magnet:?xt=urn:btih:$hash")
        if (!displayName.isNullOrBlank()) {
            parts += "dn=" + java.net.URLEncoder.encode(displayName, "UTF-8")
        }
        trackers.forEach { tracker ->
            parts += "tr=" + java.net.URLEncoder.encode(tracker, "UTF-8")
        }
        return parts.joinToString("&")
    }

    fun queryValues(magnetOrUrl: String, key: String): List<String> {
        val query = magnetOrUrl.substringAfter("?", "")
        if (query.isEmpty()) return emptyList()
        return query.split("&").mapNotNull { part ->
            val name = part.substringBefore("=")
            if (!name.equals(key, ignoreCase = true)) return@mapNotNull null
            val raw = part.substringAfter("=", "")
            runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                .takeIf { it.isNotBlank() }
        }
    }

    fun qualityFrom(name: String?, title: String?): String? {
        val blob = listOfNotNull(name, title).joinToString(" ")
        val match = QUALITY.find(blob) ?: return null
        return match.value.uppercase(Locale.US).replace("P", "p")
    }

    private fun normalizeHash(value: String): String? {
        val trimmed = value.trim()
        if (HEX.matches(trimmed)) return trimmed.lowercase(Locale.US)
        if (BASE32.matches(trimmed.uppercase(Locale.US))) {
            return base32ToHex(trimmed.uppercase(Locale.US))
        }
        return null
    }

    /** 32-character BitTorrent base32 info-hashes become the 40-character hex form. */
    private fun base32ToHex(input: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val out = ArrayList<Int>(20)
        for (char in input) {
            val value = alphabet.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += (buffer shr bits) and 0xFF
            }
        }
        if (out.size < 20) return null
        return out.take(20).joinToString("") { "%02x".format(it) }
    }

    private val QUALITY = Regex("(?:2160|1080|720|480|360)p", RegexOption.IGNORE_CASE)
}
