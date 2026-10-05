package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Radio: the song playing now (from the station's stream titles, "Artist - Song"), and its words
 * from LRCLIB (a free, open lyrics library) beside the station's artwork, scrolling gently.
 * ---------------------------------------------------------------------------------------------- */

/** "Artist - Song" from the stream while it plays; null when the station doesn't say. */
@Composable
fun rememberStreamTitle(player: Player?, stationTitle: String?): String? {
    var title by remember(player) { mutableStateOf<String?>(null) }
    DisposableEffect(player, stationTitle) {
        val target = player ?: return@DisposableEffect onDispose { }
        fun read(metadata: MediaMetadata) {
            val text = listOfNotNull(
                metadata.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                metadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            ).joinToString(" - ")
            // The item's own title is the station's name: only a song title counts.
            title = text.takeIf { it.isNotEmpty() && !it.equals(stationTitle, ignoreCase = true) }
        }
        read(target.mediaMetadata)
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = read(mediaMetadata)
        }
        target.addListener(listener)
        onDispose { target.removeListener(listener) }
    }
    return title
}

object LrcLib {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 40
    }

    /** The words of [song] ("Artist - Song"), or null. */
    suspend fun lyrics(song: String): String? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[song] }?.let { return@withContext it.ifEmpty { null } }
        val artist = song.substringBefore(" - ", "").trim()
        val track = song.substringAfter(" - ").trim()
        fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
        val url = if (artist.isNotEmpty() && track.isNotEmpty()) {
            "https://lrclib.net/api/search?track_name=${enc(track)}&artist_name=${enc(artist)}"
        } else {
            "https://lrclib.net/api/search?q=${enc(song)}"
        }
        var connection: HttpURLConnection? = null
        val found = try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                // LRCLIB asks apps to say who they are.
                setRequestProperty("User-Agent", "Chud Supreme (Android TV; https://github.com/yungblockchain/Chud-Supreme)")
            }
            if (connection.responseCode != 200) return@withContext null
            val results = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as? JsonArray
            results.orEmpty().firstNotNullOfOrNull { element ->
                val item = element as? JsonObject ?: return@firstNotNullOfOrNull null
                if (item["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return@firstNotNullOfOrNull null
                item["plainLyrics"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            return@withContext null
        } finally {
            connection?.disconnect()
        }
        synchronized(cache) { cache[song] = found.orEmpty() }
        found
    }
}

/** The song's words, scrolling slowly from the top; "no lyrics" when there are none. */
@Composable
fun LyricsPanel(song: String, lyrics: String?, loading: Boolean, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(song, lyrics) {
        scroll.scrollTo(0)
        if (lyrics == null) return@LaunchedEffect
        delay(LYRICS_START_DELAY_MS)
        while (scroll.value < scroll.maxValue) {
            scroll.scrollBy(1f)
            delay(LYRICS_SCROLL_STEP_MS)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .width(440.dp)
            .height(440.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Text(
            text = song,
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 2,
        )
        Text(
            text = when {
                lyrics != null -> lyrics
                loading -> stringResource(R.string.dial_lyrics_loading)
                else -> stringResource(R.string.dial_lyrics_none)
            },
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
            lineHeight = 27.sp,
            modifier = Modifier.verticalScroll(scroll, enabled = false),
        )
        Text(
            text = stringResource(R.string.dial_lyrics_credit),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 11.sp,
        )
    }
}

private const val LYRICS_START_DELAY_MS = 8_000L
private const val LYRICS_SCROLL_STEP_MS = 90L
