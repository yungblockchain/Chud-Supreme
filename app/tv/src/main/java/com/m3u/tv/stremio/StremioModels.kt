package com.m3u.tv.stremio

import androidx.compose.runtime.Immutable

/** A Stremio addon the person installed (manifest URL plus the last fetched name). */
@Immutable
data class InstalledAddon(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestUrl: String,
    val logo: String?,
    val types: List<String>,
    val catalogs: List<AddonCatalog>,
    val resources: List<String>,
    val enabled: Boolean = true,
)

@Immutable
data class AddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<String> = emptyList(),
)

@Immutable
data class CatalogItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val posterShape: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val description: String?,
)

@Immutable
data class MetaDetails(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: String?,
    val genres: List<String>,
    val runtime: String?,
    val director: List<String>,
    val cast: List<String>,
    val imdbId: String?,
    val videos: List<MetaVideo>,
)

@Immutable
data class MetaVideo(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
)

@Immutable
data class StreamSource(
    val addonId: String,
    val addonName: String,
    val name: String,
    val title: String?,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val magnet: String?,
    val quality: String?,
    val size: String?,
    val seeders: String?,
    val bingeGroup: String?,
    val behaviorHints: Map<String, String> = emptyMap(),
) {
    val playableUrl: String?
        get() = url?.takeIf { it.startsWith("http") }
            ?: magnet
            ?: infoHash?.let { hash -> "magnet:?xt=urn:btih:$hash" }

    val isMagnet: Boolean
        get() = !url.orEmpty().startsWith("http") && (magnet != null || infoHash != null)

    val isDebrid: Boolean
        get() {
            val host = url.orEmpty().lowercase()
            return "real-debrid" in host || "rdcdn" in host || "torbox" in host ||
                name.contains("RD", ignoreCase = true) ||
                name.contains("TorBox", ignoreCase = true) ||
                name.contains("TB", ignoreCase = true) && name.length < 24
        }
}

@Immutable
data class CatalogRow(
    val addonId: String,
    val addonName: String,
    val type: String,
    val catalogId: String,
    val name: String,
    val items: List<CatalogItem>,
)

/** Well-known Stremio addons offered on the Addons tab for one-tap install. */
object AddonCatalogPresets {
    val all: List<PresetAddon> = listOf(
        PresetAddon(
            id = "com.linvo.cinemeta",
            name = "Cinemeta",
            description = "Movie and series posters, plots and cast (Stremio's default metadata).",
            manifestUrl = "https://v3-cinemeta.strem.io/manifest.json",
            kind = PresetKind.Metadata,
        ),
        PresetAddon(
            id = "tmdb-addon",
            name = "The Movie Database",
            description = "TMDB catalogs: trending, popular, now playing, and genre rows.",
            manifestUrl = "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json",
            kind = PresetKind.Metadata,
        ),
        PresetAddon(
            id = "com.stremio.torrentio",
            name = "Torrentio",
            description = "Torrent and debrid streams. Add Real-Debrid or TorBox keys to prefer cached links.",
            manifestUrl = "https://torrentio.strem.fun/manifest.json",
            kind = PresetKind.Stream,
        ),
        PresetAddon(
            id = "aiostreams",
            name = "AIOStreams",
            description = "All-in-one stream addon. Install, then paste the manifest URL from your AIOStreams config.",
            manifestUrl = "",
            kind = PresetKind.Stream,
        ),
        PresetAddon(
            id = "org.stremio.opensubtitlesv3",
            name = "OpenSubtitles v3",
            description = "Subtitle search for films and episodes.",
            manifestUrl = "https://opensubtitles-v3.strem.io/manifest.json",
            kind = PresetKind.Subtitles,
        ),
    )
}

@Immutable
data class PresetAddon(
    val id: String,
    val name: String,
    val description: String,
    val manifestUrl: String,
    val kind: PresetKind,
)

enum class PresetKind { Metadata, Stream, Subtitles }

internal object StremioIds {
    const val PLAYLIST_URL = "stremio://library"
    const val PLAYLIST_TITLE = "Addons"
}

internal fun String.stremioBaseUrl(): String =
    trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
