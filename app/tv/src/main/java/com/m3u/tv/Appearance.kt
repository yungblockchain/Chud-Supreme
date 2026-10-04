package com.m3u.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/* -------------------------------------------------------------------------------------------------
 * Skins. A skin is every choice about how the app looks: colours, font, corner shape, how focus
 * is shown, the background effects, the side menu's style and the text size. The app ships a set
 * of them; any of them can be copied and changed in Settings > Appearance; and a skin can be
 * saved to or loaded from a small JSON file (the phone page, a link, or the skins folder), so
 * skins can be shared.
 *
 * The chosen skin is applied to [TvColors], [TvFonts] and [TvShapes], which every screen reads,
 * so a change shows everywhere at once.
 * ---------------------------------------------------------------------------------------------- */

enum class SkinFont { Hyperlegible, Inter, Outfit, SpaceGrotesk, Audiowide, System, Serif, Mono }

enum class SkinCorners { Rounded, Soft, Square, Chamfer }

/** How the thing the remote is on is shown. */
enum class SkinFocus {
    /** Filled with the accent colour, text turns dark. */
    Fill,
    /** An outline in the accent colour; the fill stays as it is. */
    Ring,
    /** Lifted: a touch bigger and brighter, thin outline. */
    Lift,
}

enum class SkinMenu {
    /** A thin strip at the edge; Left or the Menu key slides the menu out. */
    Hidden,
    /** A strip of icons that opens with names. */
    Rail,
}

@Immutable
data class Skin(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val background: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF121212),
    val surfaceRaised: Color = Color(0xFF1E1E1E),
    /** The colour of focus, selection and links. */
    val accent: Color = Color(0xFFFFFFFF),
    /** Text drawn on top of the accent. */
    val onAccent: Color = Color(0xFF000000),
    /** A second colour for brand moments: the logo glow, live badges, chart lines. */
    val highlight: Color = Color(0xFF6FA8FF),
    val text: Color = Color(0xFFF2F2F2),
    val textSecondary: Color = Color(0xFFA8A8A8),
    val textMuted: Color = Color(0xFF6A6A6A),
    val danger: Color = Color(0xFFFF4D5E),
    val positive: Color = Color(0xFF3DDC84),
    val font: SkinFont = SkinFont.Inter,
    /** The display face for the wordmark and big numbers. */
    val accentFont: SkinFont = SkinFont.Inter,
    val corners: SkinCorners = SkinCorners.Rounded,
    val focus: SkinFocus = SkinFocus.Ring,
    /** A glow under focused things (costs a little on a Fire TV Stick). */
    val glow: Boolean = false,
    /** The picture of what's playing, dimmed, behind the menus. */
    val backdropArtwork: Boolean = true,
    /** CRT scanlines and the neon grid floor of the original look. */
    val backdropEffects: Boolean = false,
    /** Translucent panels so the artwork shows through. */
    val translucentPanels: Boolean = false,
    val menu: SkinMenu = SkinMenu.Hidden,
    /** Text and spacing scale, 85–120. */
    val scalePercent: Int = 100,
    /** Focus grows and slides; off makes every move instant. */
    val animations: Boolean = true,
) {
    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "format" to JsonPrimitive(FORMAT),
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "background" to JsonPrimitive(background.hex()),
            "surface" to JsonPrimitive(surface.hex()),
            "surfaceRaised" to JsonPrimitive(surfaceRaised.hex()),
            "accent" to JsonPrimitive(accent.hex()),
            "onAccent" to JsonPrimitive(onAccent.hex()),
            "highlight" to JsonPrimitive(highlight.hex()),
            "text" to JsonPrimitive(text.hex()),
            "textSecondary" to JsonPrimitive(textSecondary.hex()),
            "textMuted" to JsonPrimitive(textMuted.hex()),
            "danger" to JsonPrimitive(danger.hex()),
            "positive" to JsonPrimitive(positive.hex()),
            "font" to JsonPrimitive(font.name),
            "accentFont" to JsonPrimitive(accentFont.name),
            "corners" to JsonPrimitive(corners.name),
            "focus" to JsonPrimitive(focus.name),
            "glow" to JsonPrimitive(glow),
            "backdropArtwork" to JsonPrimitive(backdropArtwork),
            "backdropEffects" to JsonPrimitive(backdropEffects),
            "translucentPanels" to JsonPrimitive(translucentPanels),
            "menu" to JsonPrimitive(menu.name),
            "scalePercent" to JsonPrimitive(scalePercent),
            "animations" to JsonPrimitive(animations),
        )
    )

    companion object {
        const val FORMAT = "chud-skin/1"
        const val MAX_NAME = 32

        /** Reads a skin file. Missing fields keep [base]'s values, so partial files work. */
        fun fromJson(raw: String, base: Skin = Skins.SupremeBlack): Skin? = runCatching {
            val item = Json.parseToJsonElement(raw).jsonObject
            fun text(key: String) = item[key]?.jsonPrimitive?.content
            fun colour(key: String, fallback: Color) = text(key)?.let(::parseColor) ?: fallback
            fun flag(key: String, fallback: Boolean) = item[key]?.jsonPrimitive?.booleanOrNull ?: fallback
            val name = text("name")?.trim()?.take(MAX_NAME)?.ifBlank { null } ?: base.name
            Skin(
                // Slugged: it names a file in the skins folder.
                id = slug(text("id")?.takeIf { it.isNotBlank() } ?: name).take(48),
                name = name,
                builtIn = false,
                background = colour("background", base.background),
                surface = colour("surface", base.surface),
                surfaceRaised = colour("surfaceRaised", base.surfaceRaised),
                accent = colour("accent", base.accent),
                onAccent = colour("onAccent", base.onAccent),
                highlight = colour("highlight", base.highlight),
                text = colour("text", base.text),
                textSecondary = colour("textSecondary", base.textSecondary),
                textMuted = colour("textMuted", base.textMuted),
                danger = colour("danger", base.danger),
                positive = colour("positive", base.positive),
                font = enumNamed(text("font"), base.font),
                accentFont = enumNamed(text("accentFont"), base.accentFont),
                corners = enumNamed(text("corners"), base.corners),
                focus = enumNamed(text("focus"), base.focus),
                glow = flag("glow", base.glow),
                backdropArtwork = flag("backdropArtwork", base.backdropArtwork),
                backdropEffects = flag("backdropEffects", base.backdropEffects),
                translucentPanels = flag("translucentPanels", base.translucentPanels),
                menu = enumNamed(text("menu"), base.menu),
                scalePercent = (item["scalePercent"]?.jsonPrimitive?.intOrNull ?: base.scalePercent)
                    .coerceIn(SCALE_MIN, SCALE_MAX),
                animations = flag("animations", base.animations),
            )
        }.getOrNull()

        /** "#RRGGBB" or "#AARRGGBB", with or without the hash. */
        fun parseColor(value: String): Color? {
            val hex = value.trim().removePrefix("#").removePrefix("0x")
            if (hex.length != 6 && hex.length != 8) return null
            val argb = hex.toLongOrNull(16) ?: return null
            return if (hex.length == 6) Color(0xFF000000L or argb) else Color(argb)
        }

        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "skin" }

        const val SCALE_MIN = 85
        const val SCALE_MAX = 120
        val SCALE_OPTIONS = listOf(85, 92, 100, 108, 115, 120)
    }
}

private inline fun <reified E : Enum<E>> enumNamed(value: String?, fallback: E): E =
    value?.let { wanted -> enumValues<E>().firstOrNull { it.name.equals(wanted, ignoreCase = true) } } ?: fallback

private fun Color.hex(): String {
    val argb = (alpha * 255).toInt().shl(24) or (red * 255).toInt().shl(16) or
        (green * 255).toInt().shl(8) or (blue * 255).toInt()
    return "#%08X".format(argb)
}

/** The skins that ship with the app. */
object Skins {
    /** Pure black, one white accent, no decoration: the default. */
    val SupremeBlack = Skin(id = "supreme-black", name = "Supreme Black", builtIn = true)

    val Charcoal = Skin(
        id = "charcoal", name = "Charcoal", builtIn = true,
        background = Color(0xFF141414), surface = Color(0xFF1F1F1F), surfaceRaised = Color(0xFF2A2A2A),
        accent = Color(0xFFE50914), onAccent = Color(0xFFFFFFFF), highlight = Color(0xFFE50914),
        text = Color(0xFFFFFFFF), textSecondary = Color(0xFFB3B3B3), textMuted = Color(0xFF777777),
        font = SkinFont.Outfit, accentFont = SkinFont.Outfit, focus = SkinFocus.Lift,
    )

    val Glass = Skin(
        id = "glass", name = "Glass", builtIn = true,
        background = Color(0xFF07090F), surface = Color(0x66182030), surfaceRaised = Color(0x8C22304A),
        accent = Color(0xFFFFFFFF), onAccent = Color(0xFF0A1220), highlight = Color(0xFF7CC4FF),
        text = Color(0xFFF4F8FF), textSecondary = Color(0xFFB7C3D9), textMuted = Color(0xFF7B879C),
        font = SkinFont.Inter, accentFont = SkinFont.SpaceGrotesk, corners = SkinCorners.Soft,
        focus = SkinFocus.Ring, translucentPanels = true, backdropArtwork = true,
    )

    val Midnight = Skin(
        id = "midnight", name = "Midnight", builtIn = true,
        background = Color(0xFF0B1020), surface = Color(0xFF151C33), surfaceRaised = Color(0xFF1F2A4A),
        accent = Color(0xFF8AB4FF), onAccent = Color(0xFF071028), highlight = Color(0xFFB08CFF),
        text = Color(0xFFEDF2FF), textSecondary = Color(0xFFAAB6D6), textMuted = Color(0xFF6B7797),
        font = SkinFont.Inter, accentFont = SkinFont.SpaceGrotesk, focus = SkinFocus.Fill,
    )

    val Forest = Skin(
        id = "forest", name = "Forest", builtIn = true,
        background = Color(0xFF0A1410), surface = Color(0xFF13211B), surfaceRaised = Color(0xFF1C3027),
        accent = Color(0xFF7DE3A6), onAccent = Color(0xFF06201A), highlight = Color(0xFFE3C97D),
        text = Color(0xFFEFF7F2), textSecondary = Color(0xFFA9C4B4), textMuted = Color(0xFF66807A),
        font = SkinFont.Outfit, accentFont = SkinFont.Outfit, corners = SkinCorners.Soft, focus = SkinFocus.Fill,
    )

    val Ember = Skin(
        id = "ember", name = "Ember", builtIn = true,
        background = Color(0xFF120A08), surface = Color(0xFF221410), surfaceRaised = Color(0xFF311D17),
        accent = Color(0xFFFF8A3D), onAccent = Color(0xFF1E0C04), highlight = Color(0xFFFFC46B),
        text = Color(0xFFFFF4EC), textSecondary = Color(0xFFD9B8A8), textMuted = Color(0xFF8C6A5C),
        font = SkinFont.SpaceGrotesk, accentFont = SkinFont.SpaceGrotesk, focus = SkinFocus.Fill, glow = true,
    )

    /** The original CHUD STREAMS look: neon cyan and magenta over a 90s anime night city. */
    val NeonCity = Skin(
        id = "neon-city", name = "Neon City", builtIn = true,
        background = Color(0xFF0B0A1F), surface = Color(0xFF1B1642), surfaceRaised = Color(0xFF271F5A),
        accent = Color(0xFF19F0FF), onAccent = Color(0xFF03141A), highlight = Color(0xFFFF2BD6),
        text = Color(0xFFEAF6FF), textSecondary = Color(0xFFA9B6E6), textMuted = Color(0xFF6B72A6),
        danger = Color(0xFFFF3B6B), positive = Color(0xFF3DFF9A),
        font = SkinFont.Hyperlegible, accentFont = SkinFont.Audiowide, corners = SkinCorners.Chamfer,
        focus = SkinFocus.Fill, glow = true, backdropEffects = true, menu = SkinMenu.Rail,
    )

    val Paper = Skin(
        id = "paper", name = "Paper", builtIn = true,
        background = Color(0xFFF4F1EA), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFEDE8DD),
        accent = Color(0xFF1A1A1A), onAccent = Color(0xFFFFFFFF), highlight = Color(0xFFC0392B),
        text = Color(0xFF1A1A1A), textSecondary = Color(0xFF5B5750), textMuted = Color(0xFF9A948A),
        danger = Color(0xFFC0392B), positive = Color(0xFF1E8E4F),
        font = SkinFont.Inter, accentFont = SkinFont.Serif, corners = SkinCorners.Soft,
        focus = SkinFocus.Ring, backdropArtwork = false,
    )

    val builtIn: List<Skin> = listOf(SupremeBlack, Charcoal, Glass, Midnight, Forest, Ember, NeonCity, Paper)

    fun byId(id: String): Skin? = builtIn.firstOrNull { it.id == id }
}

/**
 * The chosen skin, the person's own skins, and the skin files in the skins folder
 * (`files/skins/*.json`, where the phone page and a link import put them).
 */
@Singleton
class SkinStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val folder: File get() = File(context.filesDir, "skins").apply { mkdirs() }
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val _custom = MutableStateFlow(readCustom())
    /** Skins the person made or imported, by id. */
    val custom: StateFlow<List<Skin>> = _custom.asStateFlow()

    private val _current = MutableStateFlow(readCurrent())
    val current: StateFlow<Skin> = _current.asStateFlow()

    val all: List<Skin> get() = Skins.builtIn + _custom.value

    fun select(skin: Skin) {
        _current.value = skin
        prefs.edit().putString(KEY_CURRENT, skin.id).apply()
        // The theme is Compose state: set it on the main thread (the phone page calls from a worker).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            TvTheme.apply(skin)
        } else {
            Handler(Looper.getMainLooper()).post { TvTheme.apply(skin) }
        }
    }

    /** Saves a custom skin (new or changed) and applies it. The file is written off the main thread. */
    fun save(skin: Skin) {
        val stored = skin.copy(builtIn = false)
        _custom.value = _custom.value.filterNot { it.id == stored.id } + stored
        select(stored)
        io.execute {
            runCatching { File(folder, "${stored.id}.json").writeText(stored.toJson().toString()) }
        }
    }

    fun delete(skin: Skin) {
        if (skin.builtIn) return
        _custom.value = _custom.value.filterNot { it.id == skin.id }
        if (_current.value.id == skin.id) select(Skins.SupremeBlack)
        io.execute { runCatching { File(folder, "${skin.id}.json").delete() } }
    }

    /** Imports a skin file's text (from the phone page or a link). Null if it isn't a skin. */
    fun import(raw: String): Skin? {
        val skin = Skin.fromJson(raw) ?: return null
        // Never overwrite a built-in id; keep the file's own id otherwise, so re-imports update.
        val safe = if (Skins.byId(skin.id) != null) skin.copy(id = "${skin.id}-custom") else skin
        save(safe)
        return safe
    }

    /** A fresh id for a copy of [base] the person is about to edit. */
    fun copyOf(base: Skin, name: String): Skin {
        val wanted = Skin.slug(name)
        val taken = all.map { it.id }.toSet()
        var id = wanted
        var n = 2
        while (id in taken) id = "$wanted-${n++}"
        return base.copy(id = id, name = name.take(Skin.MAX_NAME), builtIn = false)
    }

    private fun readCustom(): List<Skin> =
        folder.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file -> runCatching { Skin.fromJson(file.readText()) }.getOrNull() }
            .map { it.copy(builtIn = false) }

    private fun readCurrent(): Skin {
        val id = prefs.getString(KEY_CURRENT, null) ?: return Skins.SupremeBlack
        return Skins.byId(id) ?: _custom.value.firstOrNull { it.id == id } ?: Skins.SupremeBlack
    }

    private companion object {
        const val KEY_CURRENT = "skin"
    }
}

/** Applies a skin to the live theme objects every screen reads. */
object TvTheme {
    fun apply(skin: Skin) = Snapshot.withMutableSnapshot {
        TvColors.Background = skin.background
        TvColors.BackgroundSoft = blend(skin.background, skin.surface, 0.5f)
        TvColors.Surface = skin.surface
        TvColors.SurfaceRaised = skin.surfaceRaised
        TvColors.Focus = skin.accent
        TvColors.FocusRing = blend(skin.accent, Color.White, 0.45f)
        TvColors.Accent = skin.highlight
        TvColors.Danger = skin.danger
        TvColors.Positive = skin.positive
        // With a ring or lift, the focused thing keeps its own fill, so its text stays the text colour.
        TvColors.OnFocus = if (skin.focus == SkinFocus.Fill) skin.onAccent else skin.text
        TvColors.TextPrimary = skin.text
        TvColors.TextSecondary = skin.textSecondary
        TvColors.TextMuted = skin.textMuted
        TvFonts.Body = skin.font.family()
        TvFonts.Accent = skin.accentFont.family()
        TvShapes.corners = skin.corners
        TvShapes.focus = skin.focus
        TvShapes.glow = skin.glow
        TvShapes.backdropArtwork = skin.backdropArtwork
        TvShapes.backdropEffects = skin.backdropEffects
        TvShapes.translucent = skin.translucentPanels
        TvShapes.menu = skin.menu
        TvShapes.scale = skin.scalePercent / 100f
        TvShapes.animations = skin.animations
        TvShapes.light = skin.background.luminance() > 0.5f
    }

    private fun blend(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )

    private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

/** The font files behind each [SkinFont]. The variable fonts carry every weight in one file. */
fun SkinFont.family(): FontFamily = when (this) {
    SkinFont.Hyperlegible -> FontFamily(
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Medium),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.SemiBold),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
    )
    SkinFont.Inter -> variable(R.font.inter_variable)
    SkinFont.Outfit -> variable(R.font.outfit_variable)
    SkinFont.SpaceGrotesk -> variable(R.font.space_grotesk_variable)
    SkinFont.Audiowide -> FontFamily(
        Font(R.font.audiowide_regular, FontWeight.Normal),
        Font(R.font.audiowide_regular, FontWeight.Medium),
        Font(R.font.audiowide_regular, FontWeight.SemiBold),
        Font(R.font.audiowide_regular, FontWeight.Bold),
    )
    SkinFont.System -> FontFamily.SansSerif
    SkinFont.Serif -> FontFamily.Serif
    SkinFont.Mono -> FontFamily.Monospace
}

private fun variable(resource: Int): FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            resId = resource,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    }
)
