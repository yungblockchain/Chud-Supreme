package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel

/* -------------------------------------------------------------------------------------------------
 * Duplicate channels. Providers list the same channel several times (SD, HD, FHD, 4K, "backup",
 * a second server). With merging on, one card stands for the lot: the preferred quality plays,
 * and the other copies are a hold-OK away. Nothing is changed in the database; this is a view.
 * ---------------------------------------------------------------------------------------------- */

/** Extra marks on a channel card: a live score, and how many copies hide behind it. */
@Immutable
data class ChannelBadge(val live: String? = null, val variants: Int = 0)

/** Badges by channel id, for the cards in the lists. */
val LocalChannelBadges = compositionLocalOf<Map<Int, ChannelBadge>> { emptyMap() }

object ChannelVariants {
    /** Quality words that make copies of one channel differ, and how good each is. */
    private val QUALITY_RANK: List<Pair<Regex, Int>> = listOf(
        Regex("""(?i)\b(4k|uhd|2160p?)\b""") to 4,
        Regex("""(?i)\b(fhd|1080p?|full ?hd)\b""") to 3,
        Regex("""(?i)(\bhd\b|ᴴᴰ|720p?)""") to 2,
        Regex("""(?i)\b(sd|480p?|576p?|lq|low)\b""") to 1,
    )
    private val NOISE = Regex(
        """(?i)(\b(4k|uhd|2160p?|fhd|1080p?|full ?hd|hd|720p?|sd|480p?|576p?|lq|low|hevc|h\.?265|h\.?264|50 ?fps|60 ?fps|raw|backup|bk|b|alt|alternative|server ?\d*|src ?\d*|link ?\d*|feed ?\d*|option ?\d*)\b|ᴴᴰ|[\[(][^\])]*[\])]|[|•·:_\-–—]+)""",
    )
    private val SPACES = Regex("""\s+""")

    /** The name with quality and server marks removed: copies of one channel share it. */
    fun key(title: String): String {
        val stripped = NOISE.replace(title, " ")
        return SPACES.replace(stripped, " ").trim().lowercase()
    }

    fun rank(title: String): Int = QUALITY_RANK.firstOrNull { it.first.containsMatchIn(title) }?.second ?: 2

    /** The copy that plays for [preferred]: the closest quality at or below it, else the best. */
    fun pick(copies: List<Channel>, preferred: PreferredQuality): Channel {
        val wanted = when (preferred) {
            PreferredQuality.Best -> 4
            PreferredQuality.Fhd -> 3
            PreferredQuality.Hd -> 2
            PreferredQuality.Sd -> 1
        }
        val ranked = copies.map { it to rank(it.title) }
        return ranked.filter { it.second <= wanted }.maxByOrNull { it.second }?.first
            ?: ranked.maxByOrNull { it.second }?.first
            ?: copies.first()
    }

    /**
     * Folds [channels] (one category, in order) into one entry per name. Returns the list to
     * show and, for each shown channel, every copy including itself (only where there are two
     * or more).
     */
    fun merge(channels: List<Channel>, preferred: PreferredQuality): Pair<List<Channel>, Map<Int, List<Channel>>> {
        if (channels.size < 2) return channels to emptyMap()
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (channel in channels) {
            val key = "${channel.category}\u0000${key(channel.title)}"
            if (key.endsWith("\u0000")) {
                // A name that was nothing but quality marks: leave it alone.
                groups[channel.id.toString()] = mutableListOf(channel)
            } else {
                groups.getOrPut(key) { mutableListOf() } += channel
            }
        }
        val shown = ArrayList<Channel>(groups.size)
        val variants = HashMap<Int, List<Channel>>()
        for (copies in groups.values) {
            val chosen = if (copies.size == 1) copies.first() else pick(copies, preferred)
            shown += chosen
            if (copies.size > 1) variants[chosen.id] = copies.sortedByDescending { rank(it.title) }
        }
        return shown to variants
    }
}

/** The live score and the "×3" copies mark in a card's corner. */
@Composable
fun ChannelBadgeMarks(badge: ChannelBadge?, modifier: Modifier = Modifier) {
    if (badge == null || (badge.live == null && badge.variants < 2)) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        badge.live?.let { live ->
            Text(
                text = live,
                color = Color.White,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(TvColors.Danger)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (badge.variants >= 2) {
            Text(
                text = "×${badge.variants}",
                color = Color.White,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
