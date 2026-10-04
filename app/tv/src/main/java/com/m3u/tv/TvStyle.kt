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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The live palette. Every screen reads these; [TvTheme.apply] sets them from the chosen skin,
 * and because they are Compose state, screens redraw in the new colours at once. The starting
 * values are the default skin ([Skins.SupremeBlack]).
 */
object TvColors {
    var Background by mutableStateOf(Color(0xFF000000))
    var BackgroundSoft by mutableStateOf(Color(0xFF090909))
    var Surface by mutableStateOf(Color(0xFF121212))
    var SurfaceRaised by mutableStateOf(Color(0xFF1E1E1E))
    var Focus by mutableStateOf(Color(0xFFFFFFFF))
    var FocusRing by mutableStateOf(Color(0xFFFFFFFF))
    var Accent by mutableStateOf(Color(0xFF6FA8FF))
    var Danger by mutableStateOf(Color(0xFFFF4D5E))
    var Positive by mutableStateOf(Color(0xFF3DDC84))
    var OnFocus by mutableStateOf(Color(0xFF000000))
    var TextPrimary by mutableStateOf(Color(0xFFF2F2F2))
    var TextSecondary by mutableStateOf(Color(0xFFA8A8A8))
    var TextMuted by mutableStateOf(Color(0xFF6A6A6A))
}

object TvFonts {
    /** The reading face: body text, labels, titles. */
    var Body by mutableStateOf(SkinFont.Inter.family())

    /** The display face: the wordmark and big numbers. */
    var Accent by mutableStateOf(SkinFont.Inter.family())
}

/** Shape, motion and decoration choices from the skin. */
object TvShapes {
    var corners by mutableStateOf(SkinCorners.Rounded)
    var focus by mutableStateOf(SkinFocus.Ring)
    var glow by mutableStateOf(false)
    var backdropArtwork by mutableStateOf(true)
    var backdropEffects by mutableStateOf(false)
    var translucent by mutableStateOf(false)
    var menu by mutableStateOf(SkinMenu.Hidden)
    var scale by mutableStateOf(1f)
    var animations by mutableStateOf(true)
    /** A light background (Paper): some overlays switch from black to white tints. */
    var light by mutableStateOf(false)

    /** The shape of panels, cards and buttons. */
    val panel: Shape
        get() = when (corners) {
            SkinCorners.Rounded -> RoundedCornerShape(12.dp)
            SkinCorners.Soft -> RoundedCornerShape(20.dp)
            SkinCorners.Square -> RoundedCornerShape(2.dp)
            SkinCorners.Chamfer -> CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
        }

    /** The shape of small things: chips, pills, thumbnails. */
    val chip: Shape
        get() = when (corners) {
            SkinCorners.Rounded -> RoundedCornerShape(8.dp)
            SkinCorners.Soft -> RoundedCornerShape(14.dp)
            SkinCorners.Square -> RoundedCornerShape(2.dp)
            SkinCorners.Chamfer -> CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
        }
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
