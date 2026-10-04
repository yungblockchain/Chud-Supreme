package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* -------------------------------------------------------------------------------------------------
 * Settings > Appearance. Pick a skin, or change any part of the look: every change applies at
 * once, and the first change to a built-in skin makes a copy of it ("My Supreme Black") that
 * the changes go into, so the built-in skins are always there to go back to. Skins can be shared
 * as small files: import one from a link or from the phone page, and export the current one.
 * ---------------------------------------------------------------------------------------------- */

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val skins: SkinStore,
) : ViewModel() {
    val current: StateFlow<Skin> = skins.current
    val custom: StateFlow<List<Skin>> = skins.custom
    val builtIn: List<Skin> = Skins.builtIn

    private val _notice = MutableStateFlow<AppearanceNotice?>(null)
    val notice: StateFlow<AppearanceNotice?> = _notice.asStateFlow()

    fun select(skin: Skin) = skins.select(skin)

    fun delete(skin: Skin) = skins.delete(skin)

    fun reset() = skins.select(Skins.SupremeBlack)

    /** Changes the current skin. A built-in skin is copied first, so it stays as it was. */
    fun change(transform: (Skin) -> Skin) {
        val now = skins.current.value
        val target = if (now.builtIn) skins.copyOf(now, "My ${now.name}") else now
        skins.save(transform(target))
    }

    /** The phone page serves the current skin as a file; this just says so. */
    fun export() {
        _notice.value = AppearanceNotice.Exported
    }

    fun importFromUrl(url: String) {
        val address = url.trim()
        if (address.isEmpty()) return
        _notice.value = AppearanceNotice.Importing
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrNull()
            }
            val skin = text?.let(skins::importSkin)
            _notice.value = if (skin != null) AppearanceNotice.Imported(skin.name) else AppearanceNotice.ImportFailed
        }
    }

    fun clearNotice() {
        _notice.value = null
    }
}

sealed interface AppearanceNotice {
    data object Importing : AppearanceNotice
    data class Imported(val name: String) : AppearanceNotice
    data object ImportFailed : AppearanceNotice
    data object Exported : AppearanceNotice
}

@Composable
fun AppearanceScreen(viewModel: AppearanceViewModel = hiltViewModel()) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    val custom by viewModel.custom.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var importUrl by remember { mutableStateOf("") }
    val on = stringResource(R.string.dial_value_on)
    val off = stringResource(R.string.dial_value_off)
    fun onOff(value: Boolean) = if (value) on else off

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { SettingsSection(stringResource(R.string.dial_appearance_skins)) }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
                modifier = Modifier.focusGroup()
            ) {
                items(viewModel.builtIn + custom, key = { it.id }) { skin ->
                    SkinCard(
                        skin = skin,
                        selected = skin.id == current.id,
                        onClick = { viewModel.select(skin) },
                        onLongClick = if (skin.builtIn) null else ({ viewModel.delete(skin) }),
                    )
                }
            }
        }
        item {
            Text(
                text = if (current.builtIn) {
                    stringResource(R.string.dial_appearance_hint_builtin)
                } else {
                    stringResource(R.string.dial_appearance_hint_custom, current.name)
                },
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
            )
        }

        item { SettingsSection(stringResource(R.string.dial_appearance_colours)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_accent),
                value = colourName(current.accent),
                onClick = { viewModel.change { it.withAccent(nextIn(ACCENTS, it.accent, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.withAccent(nextIn(ACCENTS, it.accent, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_background),
                value = colourName(current.background),
                onClick = { viewModel.change { it.withBackground(nextIn(BACKGROUNDS, it.background, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.withBackground(nextIn(BACKGROUNDS, it.background, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_highlight),
                value = colourName(current.highlight),
                onClick = { viewModel.change { it.copy(highlight = nextIn(ACCENTS, it.highlight, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(highlight = nextIn(ACCENTS, it.highlight, step)) } } },
            )
        }
        item {
            HexRow(
                label = stringResource(R.string.dial_appearance_accent_hex),
                colour = current.accent,
                onColour = { colour -> viewModel.change { it.withAccent(colour) } },
            )
        }
        item {
            HexRow(
                label = stringResource(R.string.dial_appearance_background_hex),
                colour = current.background,
                onColour = { colour -> viewModel.change { it.withBackground(colour) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_appearance_type)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_font),
                value = fontName(current.font),
                onClick = { viewModel.change { it.copy(font = next(it.font, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(font = next(it.font, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_display_font),
                value = fontName(current.accentFont),
                onClick = { viewModel.change { it.copy(accentFont = next(it.accentFont, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(accentFont = next(it.accentFont, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_scale),
                value = "${current.scalePercent}%",
                onClick = { viewModel.change { it.copy(scalePercent = nextIn(Skin.SCALE_OPTIONS, it.scalePercent, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(scalePercent = nextIn(Skin.SCALE_OPTIONS, it.scalePercent, step)) } } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_appearance_shape)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_corners),
                value = stringResource(
                    when (current.corners) {
                        SkinCorners.Rounded -> R.string.dial_appearance_corners_rounded
                        SkinCorners.Soft -> R.string.dial_appearance_corners_soft
                        SkinCorners.Square -> R.string.dial_appearance_corners_square
                        SkinCorners.Chamfer -> R.string.dial_appearance_corners_chamfer
                    }
                ),
                onClick = { viewModel.change { it.copy(corners = next(it.corners, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(corners = next(it.corners, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_focus),
                value = stringResource(
                    when (current.focus) {
                        SkinFocus.Fill -> R.string.dial_appearance_focus_fill
                        SkinFocus.Ring -> R.string.dial_appearance_focus_ring
                        SkinFocus.Lift -> R.string.dial_appearance_focus_lift
                    }
                ),
                onClick = { viewModel.change { it.copy(focus = next(it.focus, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(focus = next(it.focus, step)) } } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_glow),
                value = onOff(current.glow),
                onClick = { viewModel.change { it.copy(glow = !it.glow) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_animations),
                value = onOff(current.animations),
                onClick = { viewModel.change { it.copy(animations = !it.animations) } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_appearance_background_section)) }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_artwork),
                value = onOff(current.backdropArtwork),
                onClick = { viewModel.change { it.copy(backdropArtwork = !it.backdropArtwork) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_effects),
                value = onOff(current.backdropEffects),
                onClick = { viewModel.change { it.copy(backdropEffects = !it.backdropEffects) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_translucent),
                value = onOff(current.translucentPanels),
                onClick = { viewModel.change { it.copy(translucentPanels = !it.translucentPanels) } },
            )
        }
        item {
            SettingRow(
                label = stringResource(R.string.dial_appearance_menu),
                value = stringResource(
                    when (current.menu) {
                        SkinMenu.Hidden -> R.string.dial_appearance_menu_hidden
                        SkinMenu.Rail -> R.string.dial_appearance_menu_rail
                    }
                ),
                onClick = { viewModel.change { it.copy(menu = next(it.menu, 1)) } },
                onKey = { event -> stepperKeys(event) { step -> viewModel.change { it.copy(menu = next(it.menu, step)) } } },
            )
        }

        item { SettingsSection(stringResource(R.string.dial_appearance_share)) }
        item {
            Box(Modifier.widthIn(max = 820.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_appearance_import_label),
                    value = importUrl,
                    onValueChange = { importUrl = it },
                    placeholder = "https://…/skin.json",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    onDone = { viewModel.importFromUrl(importUrl) },
                )
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.focusGroup()
            ) {
                TvActionButton(
                    text = stringResource(R.string.dial_appearance_import),
                    icon = Icons.Rounded.Download,
                    enabled = importUrl.isNotBlank(),
                    focusableWhenDisabled = true,
                    onClick = { viewModel.importFromUrl(importUrl) },
                )
                TvActionButton(
                    text = stringResource(R.string.dial_appearance_export),
                    icon = Icons.Rounded.Save,
                    onClick = viewModel::export,
                )
                TvActionButton(
                    text = stringResource(R.string.dial_appearance_reset),
                    icon = Icons.Rounded.RestartAlt,
                    onClick = viewModel::reset,
                )
            }
        }
        item {
            Text(
                text = when (val n = notice) {
                    null -> stringResource(R.string.dial_appearance_share_hint)
                    AppearanceNotice.Importing -> stringResource(R.string.dial_appearance_importing)
                    is AppearanceNotice.Imported -> stringResource(R.string.dial_appearance_imported, n.name)
                    AppearanceNotice.ImportFailed -> stringResource(R.string.dial_appearance_import_failed)
                    AppearanceNotice.Exported -> stringResource(R.string.dial_appearance_exported)
                },
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                maxLines = 3,
            )
        }
    }
}

/** A skin as a card: its name over a strip of its colours, with a tick on the chosen one. */
@Composable
private fun SkinCard(
    skin: Skin,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    FocusFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        selectionState = selected,
        focusedScale = 1.05f,
        focusedFill = skin.surfaceRaised,
        semanticsLabel = skin.name,
        modifier = Modifier
            .width(176.dp)
            .height(104.dp)
    ) { focused ->
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .background(skin.background)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = skin.name,
                    color = skin.text,
                    fontFamily = skin.font.family(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = skin.accent,
                        modifier = Modifier.size(18.dp)
                    )
                } else if (onLongClick != null && focused) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = skin.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(skin.accent, skin.highlight, skin.surfaceRaised, skin.textSecondary).forEach { colour ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(colour)
                            .border(1.dp, skin.text.copy(alpha = 0.25f), CircleShape)
                    )
                }
            }
        }
    }
}

/** A text box for a colour as hex: typing a valid "#RRGGBB" applies it. */
@Composable
private fun HexRow(label: String, colour: Color, onColour: (Color) -> Unit) {
    var text by remember { mutableStateOf(colour.toHex()) }
    // A change from elsewhere (the Accent row, another skin) shows here; typing is left alone.
    val typed = Skin.parseColor(text)
    if (typed != colour && (typed != null || text.isBlank())) text = colour.toHex()
    Box(Modifier.widthIn(max = 820.dp)) {
        DialTextField(
            label = label,
            value = text,
            onValueChange = { value ->
                text = value
                val hex = value.trim().removePrefix("#").removePrefix("0x")
                if (hex.length == 6) Skin.parseColor(hex)?.let(onColour)
            },
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
            readOnly = false,
        )
    }
}

private fun Color.toHex(): String = "#%02X%02X%02X".format(
    (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

/** The accent choices, with the text to draw on each. */
private val ACCENTS: List<Color> = listOf(
    Color(0xFFFFFFFF), Color(0xFF19F0FF), Color(0xFF6FA8FF), Color(0xFF8AB4FF), Color(0xFFB08CFF),
    Color(0xFFFF2BD6), Color(0xFFFF4D8D), Color(0xFFE50914), Color(0xFFFF8A3D), Color(0xFFFFC46B),
    Color(0xFFE3E36A), Color(0xFF7DE3A6), Color(0xFF3DDC84), Color(0xFF2EC4B6), Color(0xFFC9C9C9),
    Color(0xFF1A1A1A),
)

private val BACKGROUNDS: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF141414), Color(0xFF1E1E1E), Color(0xFF0B0A1F),
    Color(0xFF0B1020), Color(0xFF07090F), Color(0xFF0A1410), Color(0xFF120A08), Color(0xFF1A0B14),
    Color(0xFFF4F1EA), Color(0xFFFFFFFF),
)

private fun Skin.withAccent(colour: Color): Skin = copy(
    accent = colour,
    onAccent = if (colour.luma() > 0.5f) Color(0xFF000000) else Color(0xFFFFFFFF),
)

private fun Skin.withBackground(colour: Color): Skin {
    val light = colour.luma() > 0.5f
    return copy(
        background = colour,
        surface = if (light) colour.shade(-0.04f) else colour.shade(0.07f),
        surfaceRaised = if (light) colour.shade(-0.09f) else colour.shade(0.12f),
        text = if (light) Color(0xFF1A1A1A) else Color(0xFFF2F2F2),
        textSecondary = if (light) Color(0xFF5B5750) else Color(0xFFA8A8A8),
        textMuted = if (light) Color(0xFF9A948A) else Color(0xFF6A6A6A),
    )
}

private fun Color.luma() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun Color.shade(amount: Float) = Color(
    red = (red + amount).coerceIn(0f, 1f),
    green = (green + amount).coerceIn(0f, 1f),
    blue = (blue + amount).coerceIn(0f, 1f),
    alpha = 1f,
)

private fun <T> nextIn(options: List<T>, current: T, step: Int): T {
    val index = options.indexOf(current)
    return when {
        index >= 0 -> options[Math.floorMod(index + step, options.size)]
        // Off the list (a typed hex, say): step onto an end of it.
        step < 0 -> options.last()
        else -> options.first()
    }
}

private inline fun <reified E : Enum<E>> next(current: E, step: Int): E {
    val values = enumValues<E>()
    return values[Math.floorMod(current.ordinal + step, values.size)]
}

@Composable
private fun colourName(colour: Color): String {
    val known = ACCENT_NAMES[colour.toHex()] ?: BACKGROUND_NAMES[colour.toHex()]
    return known?.let { stringResource(it) } ?: colour.toHex()
}

private val ACCENT_NAMES: Map<String, Int> = mapOf(
    "#FFFFFF" to R.string.dial_colour_white, "#19F0FF" to R.string.dial_colour_cyan,
    "#6FA8FF" to R.string.dial_colour_sky, "#8AB4FF" to R.string.dial_colour_periwinkle,
    "#B08CFF" to R.string.dial_colour_lavender, "#FF2BD6" to R.string.dial_colour_magenta,
    "#FF4D8D" to R.string.dial_colour_pink, "#E50914" to R.string.dial_colour_red,
    "#FF8A3D" to R.string.dial_colour_orange, "#FFC46B" to R.string.dial_colour_gold,
    "#E3E36A" to R.string.dial_colour_lime, "#7DE3A6" to R.string.dial_colour_mint,
    "#3DDC84" to R.string.dial_colour_green, "#2EC4B6" to R.string.dial_colour_teal,
    "#C9C9C9" to R.string.dial_colour_silver, "#1A1A1A" to R.string.dial_colour_ink,
)

private val BACKGROUND_NAMES: Map<String, Int> = mapOf(
    "#000000" to R.string.dial_colour_black, "#0A0A0A" to R.string.dial_colour_near_black,
    "#141414" to R.string.dial_colour_charcoal, "#1E1E1E" to R.string.dial_colour_graphite,
    "#0B0A1F" to R.string.dial_colour_indigo, "#0B1020" to R.string.dial_colour_navy,
    "#07090F" to R.string.dial_colour_deep_blue, "#0A1410" to R.string.dial_colour_forest,
    "#120A08" to R.string.dial_colour_ember, "#1A0B14" to R.string.dial_colour_plum,
    "#F4F1EA" to R.string.dial_colour_paper, "#FFFFFF" to R.string.dial_colour_white,
)

@Composable
private fun fontName(font: SkinFont): String = stringResource(
    when (font) {
        SkinFont.Hyperlegible -> R.string.dial_font_hyperlegible
        SkinFont.Inter -> R.string.dial_font_inter
        SkinFont.Outfit -> R.string.dial_font_outfit
        SkinFont.SpaceGrotesk -> R.string.dial_font_space_grotesk
        SkinFont.Audiowide -> R.string.dial_font_audiowide
        SkinFont.System -> R.string.dial_font_system
        SkinFont.Serif -> R.string.dial_font_serif
        SkinFont.Mono -> R.string.dial_font_mono
    }
)
