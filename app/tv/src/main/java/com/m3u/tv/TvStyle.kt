package com.m3u.tv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * CHUD STREAMS palette: 90s cyberpunk anime. A midnight-indigo city at night, neon cyan for
 * anything the remote can land on (with a matching glow), and hot magenta for brand moments and
 * highlights, like the signage in an Akira or Bubblegum Crisis street scene.
 */
object TvColors {
    val Background = Color(0xFF0B0A1F)
    val BackgroundSoft = Color(0xFF120F2E)
    val Surface = Color(0xFF1B1642)
    val SurfaceRaised = Color(0xFF271F5A)
    val Focus = Color(0xFF19F0FF)
    val FocusRing = Color(0xFFAAFAFF)
    val Accent = Color(0xFFFF2BD6)
    val Danger = Color(0xFFFF3B6B)
    val Positive = Color(0xFF3DFF9A)
    val OnFocus = Color(0xFF03141A)
    val TextPrimary = Color(0xFFEAF6FF)
    val TextSecondary = Color(0xFFA9B6E6)
    val TextMuted = Color(0xFF6B72A6)
}

object TvFonts {
    /**
     * Atkinson Hyperlegible was drawn by the Braille Institute for low-vision
     * readers; its open letterforms stay distinct from across a room.
     * It ships in two weights, so Medium/SemiBold map onto the nearest file.
     */
    val Body = FontFamily(
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Medium),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.SemiBold),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold)
    )

    /** Audiowide: a wide techno display face, used only for the wordmark and numbers. */
    val Accent = FontFamily(
        Font(R.font.audiowide_regular, FontWeight.Normal),
        Font(R.font.audiowide_regular, FontWeight.Medium),
        Font(R.font.audiowide_regular, FontWeight.SemiBold),
        Font(R.font.audiowide_regular, FontWeight.Bold)
    )
}

/** The side menu's entries, top to bottom. */
enum class TvDestination(
    val icon: ImageVector
) {
    Search(Icons.Rounded.Search),
    Home(Icons.Rounded.Home),
    Live(Icons.Rounded.LiveTv),
    Films(Icons.Rounded.Movie),
    Series(Icons.Rounded.VideoLibrary),
    /** Debrid and addon catalogs (Stremio). Managing which addons are installed lives in Settings. */
    Infinite(Icons.Rounded.AllInclusive),
    Guide(Icons.Rounded.DateRange),
    /** Live scores, fixtures, lineups. */
    MatchCentre(Icons.Rounded.SportsSoccer),
    /** RSS and Atom desks. Categories and sources are saved on the device. */
    News(Icons.Rounded.Newspaper),
    Favorites(Icons.Rounded.Favorite),
    /** Saved films and series, and what's half-watched. */
    MyLibrary(Icons.Rounded.Bookmarks),
    Markets(Icons.AutoMirrored.Rounded.TrendingUp),
    Claude(Icons.Rounded.AutoAwesome),
    Games(Icons.Rounded.SportsEsports),
    Account(Icons.Rounded.AccountCircle),
    Status(Icons.Rounded.Settings)
}

enum class TvSurface {
    Browse,
    Player,
    /** Video in a corner while browsing. */
    Mini,
    /** Up to four live channels at once. */
    Multiview,
}
