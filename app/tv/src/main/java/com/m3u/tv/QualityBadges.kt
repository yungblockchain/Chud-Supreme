package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/* -------------------------------------------------------------------------------------------------
 * Picture and sound badges read off a title: providers write "4K", "HDR", "DV", "Atmos", "HEVC"
 * into channel and film names. The badges sit on the card so you can tell at a glance.
 * ---------------------------------------------------------------------------------------------- */

private val BADGE_PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("""(?<![\w])(dolby\s*vision|\bdv\b|dovi)(?![\w])""", RegexOption.IGNORE_CASE) to "DV",
    Regex("""(?<![\w])(hdr10\+|hdr10plus)(?![\w])""", RegexOption.IGNORE_CASE) to "HDR10+",
    Regex("""(?<![\w])(hdr10?|hlg)(?![\w])""", RegexOption.IGNORE_CASE) to "HDR",
    Regex("""(?<![\w])(4k|uhd|2160p)(?![\w])""", RegexOption.IGNORE_CASE) to "4K",
    Regex("""(?<![\w])(8k|4320p)(?![\w])""", RegexOption.IGNORE_CASE) to "8K",
    Regex("""(?<![\w])(fhd|1080p|1080i|full\s*hd)(?![\w])""", RegexOption.IGNORE_CASE) to "FHD",
    Regex("""(?<![\w])(atmos)(?![\w])""", RegexOption.IGNORE_CASE) to "Atmos",
    Regex("""(?<![\w])(dts[-:\s]?(hd|x)?)(?![\w])""", RegexOption.IGNORE_CASE) to "DTS",
    Regex("""(?<![\w])(truehd)(?![\w])""", RegexOption.IGNORE_CASE) to "TrueHD",
    Regex("""(?<![\w])(hevc|h\.?265|x265)(?![\w])""", RegexOption.IGNORE_CASE) to "HEVC",
    Regex("""(?<![\w])(av1)(?![\w])""", RegexOption.IGNORE_CASE) to "AV1",
    Regex("""(?<![\w])(60\s?fps)(?![\w])""", RegexOption.IGNORE_CASE) to "60fps",
    Regex("""(?<![\w])(50\s?fps)(?![\w])""", RegexOption.IGNORE_CASE) to "50fps",
)

/** "DV", "4K", "Atmos"... for a title, in a fixed order, at most three. */
fun qualityBadges(title: String): List<String> {
    if (title.isBlank()) return emptyList()
    val found = BADGE_PATTERNS.filter { (pattern, _) -> pattern.containsMatchIn(title) }.map { it.second }
    // HDR10+ or DV already says HDR; 4K already says FHD.
    val trimmed = found.toMutableList()
    if ("DV" in trimmed || "HDR10+" in trimmed) trimmed.remove("HDR")
    if ("4K" in trimmed || "8K" in trimmed) trimmed.remove("FHD")
    return trimmed.take(MAX_BADGES)
}

private const val MAX_BADGES = 3

/** The badges as small dark pills, for a corner of a card. */
@Composable
fun QualityBadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        badges.forEach { badge ->
            Text(
                text = badge,
                color = Color.White,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}
