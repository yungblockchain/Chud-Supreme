package com.m3u.tv

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Tv
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string
import java.util.Locale
import kotlin.math.abs

@Composable
fun TvBackdrop(channel: Channel?) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Its own layer: focus moving over the screens doesn't redraw the picture, grid and scanlines.
    val artwork = TvShapes.backdropArtwork
    val effects = TvShapes.backdropEffects
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .background(TvColors.Background)
    ) {
        if (artwork) {
            AsyncImage(
                model = channel?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TvColors.Background.copy(alpha = 0.82f))
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            *tvLeadingGradientColorStops(
                                isRtl = isRtl,
                                leading = TvColors.Background,
                                middle = TvColors.Background.copy(alpha = 0.92f),
                                trailing = TvColors.Background.copy(alpha = 0.72f),
                                middlePosition = 0.58f,
                            ).toTypedArray()
                        )
                    )
            )
        }
        // The Neon City skin: CRT scanlines, a faint retro grid floor and a magenta horizon glow.
        if (effects) Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val spacing = 3.dp.toPx()
                    val line = 1.dp.toPx()
                    val horizon = size.height * 0.68f
                    val gridColor = TvColors.Accent.copy(alpha = 0.10f)
                    onDrawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.62f to Color.Transparent,
                                1f to TvColors.Accent.copy(alpha = 0.14f),
                            )
                        )
                        val centre = size.width / 2f
                        for (i in -12..12) {
                            drawLine(
                                color = gridColor,
                                start = Offset(centre + i * size.width * 0.02f, horizon),
                                end = Offset(centre + i * size.width * 0.18f, size.height),
                                strokeWidth = line,
                            )
                        }
                        for (k in 1..8) {
                            val t = k / 8f
                            val y = horizon + (size.height - horizon) * t * t
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
                        }
                        var y = 0f
                        while (y < size.height) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.16f),
                                topLeft = Offset(0f, y),
                                size = Size(size.width, line),
                            )
                            y += spacing
                        }
                    }
                }
        )
    }
}

/**
 * The side menu. Folded it's a column of icons at the screen's edge; as soon as the remote moves
 * onto it, it slides open over the screen with each entry's name, and folds again when the remote
 * leaves. The entries scroll only when the one in focus would be cut off (never on every press).
 * OK opens an entry; coming back to the menu lands on the open one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvNavigationRail(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** Asking this for focus opens the menu on the open tab (the Menu key). */
    focusRequester: FocusRequester? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val hidden = TvShapes.menu == SkinMenu.Hidden
    val width by animateDpAsState(
        targetValue = if (expanded) RAIL_EXPANDED_WIDTH else railCollapsedWidth(),
        animationSpec = tween(
            durationMillis = if (TvShapes.animations) RAIL_SLIDE_MS else 0,
            easing = FastOutSlowInEasing,
        ),
        label = "tv-rail-width",
    )
    val requesters = remember { TvDestination.entries.associateWith { FocusRequester() } }
    // Which entry has focus, for the key handler (a plain holder: nothing redraws for it).
    val focusedEntry = remember { arrayOfNulls<TvDestination>(1) }
    val menuScroll = rememberScrollState()
    Box(
        modifier = modifier
            .zIndex(8f)
            .fillMaxHeight()
            .width(width)
            .clipToBounds()
            // Opaque, so the screen behind doesn't show through and jitter while the menu scrolls.
            .background(
                if (TvShapes.translucent && expanded) TvColors.Background.copy(alpha = 0.9f)
                else TvColors.Background
            )
            .onFocusChanged { expanded = it.hasFocus }
    ) {
        // A hairline between the menu and the screen (the Neon City skin: cyan into magenta).
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(
                    if (TvShapes.backdropEffects) {
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.3f to TvColors.Focus.copy(alpha = 0.55f),
                            0.7f to TvColors.Accent.copy(alpha = 0.55f),
                            1f to Color.Transparent,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to TvColors.TextPrimary.copy(alpha = if (hidden && !expanded) 0.18f else 0.08f),
                            1f to Color.Transparent,
                        )
                    }
                )
        )
        // Laid out at the open width and clipped to the folded width, so opening the menu
        // only moves the clip and never remeasures the labels (that remeasure was the scroll jump).
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // Laid out at the open width even while the box is narrower (unbounded), so the
                // folded strip still draws its marks and the labels never re-measure.
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .width(RAIL_EXPANDED_WIDTH)
        ) {
            val remoteBusy by LocalTvRemoteBusy.current
            SpinningBrandLogo(
                spinning = LocalTvFocusEnabled.current && !remoteBusy && !expanded,
                size = 48.dp,
                modifier = Modifier
                    .padding(start = 16.dp, top = 10.dp, bottom = 8.dp)
                    .clipToBounds()
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides RailScrollSpec) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(menuScroll)
                        .padding(bottom = 16.dp)
                        // Up from the first entry and Down from the last stay in the menu (rather
                        // than jumping into the screen behind it).
                        .onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && when (event.key) {
                                Key.DirectionUp -> focusedEntry[0] == TvDestination.entries.first()
                                Key.DirectionDown -> focusedEntry[0] == TvDestination.entries.last()
                                else -> false
                            }
                        }
                        .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .focusRestorer(requesters.getValue(selected))
                        .focusGroup()
                ) {
                    TvDestination.entries.forEach { destination ->
                        RailItem(
                            destination = destination,
                            selected = destination == selected,
                            focusRequester = requesters.getValue(destination),
                            onFocus = { focusedEntry[0] = destination },
                            onClick = { onSelect(destination) },
                            // Folded and hidden: only a small mark for the open tab is left.
                            markOnly = hidden && !expanded,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(
    destination: TvDestination,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    markOnly: Boolean = false,
) {
    val label = destination.label()
    FocusFrame(
        onClick = onClick,
        onFocus = onFocus,
        focusRequester = focusRequester,
        selected = selected,
        selectionState = selected,
        semanticsLabel = label,
        focusedScale = 1f,
        focusedBorderWidth = 2.dp,
        semanticRole = Role.Tab,
        drawGlow = false,
        raiseOnFocus = false,
        transparent = markOnly,
        outlined = !markOnly,
        modifier = Modifier
            .padding(horizontal = RAIL_ITEM_INSET)
            .fillMaxWidth()
            .height(RAIL_ITEM_HEIGHT)
    ) { focused ->
        val active = selected || focused
        if (markOnly) {
            // The hidden menu's strip: a short bar marks the open tab.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .width(4.dp)
                    .height(if (selected) 22.dp else 6.dp)
                    .background(
                        if (selected) TvColors.Focus else TvColors.TextMuted.copy(alpha = 0.5f),
                        TvShapes.chip,
                    )
            )
            return@FocusFrame
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = RAIL_ICON_START, end = 12.dp)
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                tint = if (active) TvColors.OnFocus else TvColors.TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                color = if (active) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TvDestination.label(): String = when (this) {
    TvDestination.Search -> stringResource(R.string.dial_nav_search)
    TvDestination.Home -> stringResource(R.string.dial_nav_home)
    TvDestination.Live -> stringResource(R.string.dial_nav_live)
    TvDestination.Films -> stringResource(R.string.dial_nav_films)
    TvDestination.Series -> stringResource(R.string.dial_nav_series)
    TvDestination.Infinite -> stringResource(R.string.dial_nav_infinite)
    TvDestination.Guide -> stringResource(R.string.dial_nav_guide)
    TvDestination.MatchCentre -> stringResource(R.string.dial_nav_match)
    TvDestination.News -> stringResource(R.string.dial_nav_news)
    TvDestination.Favorites -> stringResource(string.tv_favorites_title)
    TvDestination.MyLibrary -> stringResource(R.string.dial_nav_my_library)
    TvDestination.Markets -> stringResource(R.string.dial_nav_markets)
    TvDestination.Games -> stringResource(R.string.dial_nav_games)
    TvDestination.Claude -> stringResource(R.string.dial_nav_claude)
    TvDestination.Account -> stringResource(R.string.dial_nav_account)
    TvDestination.Status -> stringResource(string.tv_settings_title)
}

/** The menu's width folded; screens start after it. */
fun railCollapsedWidth(): Dp = if (TvShapes.menu == SkinMenu.Hidden) RAIL_HIDDEN_WIDTH else RAIL_COLLAPSED_WIDTH
private val RAIL_COLLAPSED_WIDTH = 80.dp
private val RAIL_HIDDEN_WIDTH = 20.dp
private val RAIL_EXPANDED_WIDTH = 248.dp
private val RAIL_ITEM_INSET = 12.dp
private val RAIL_ITEM_HEIGHT = 42.dp
/** Centres the icon in the folded menu: (80 - 2 x 12 - 22) / 2. */
private val RAIL_ICON_START = 17.dp
private const val RAIL_SLIDE_MS = 170

/** Scrolls the menu only as far as needed to show the entry in focus, with a little air so the
 *  row above and below isn't sliced in half. */
@OptIn(ExperimentalFoundationApi::class)
private val RailScrollSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = 12f
        val leading = offset - margin
        val trailing = offset + size + margin
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f -> leading
            else -> trailing - containerSize
        }
    }
}

/**
 * False for a tab on its way out (it fades while the next one comes in). Screens that switch the
 * shared playlist selection only do so while their tab is the one being shown, or two tabs
 * would keep switching it back and forth for the length of the fade.
 */
val LocalTvTabActive = compositionLocalOf { true }

/** One tab of the browse pane: remembers where focus was inside it. */
@Composable
fun TvTab(active: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTvTabActive provides active) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRestorer()
                .focusGroup()
        ) {
            content()
        }
    }
}

/**
 * True while the remote is in use (a key pressed in the last few seconds). Decorative motion
 * waits for idle moments so scrolling keeps the whole frame budget. A state, so only readers
 * recompose when it flips.
 */
val LocalTvRemoteBusy = staticCompositionLocalOf<State<Boolean>> { NeverBusy }

private val NeverBusy: State<Boolean> = mutableStateOf(false)

/**
 * Dial: false while a full-screen overlay (player, details page) covers the browse screen, so the
 * remote can't move focus onto hidden cards or the nav rail underneath it.
 */
val LocalTvFocusEnabled = compositionLocalOf { true }

/**
 * Opens the hold-OK menu for a channel, film or series; null where there's no menu. The focus
 * requester is the card's, so focus goes back to it when the menu closes.
 */
val LocalChannelMenu = compositionLocalOf<((Channel, FocusRequester) -> Unit)?> { null }

/** Opens the hold-OK menu for a category chip of the selected playlist. */
val LocalCategoryMenu = compositionLocalOf<((String, FocusRequester) -> Unit)?> { null }

/** The skin's panel shape (rounded by default; the Neon City skin cuts two corners). */
val HudShape: Shape get() = TvShapes.panel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionState: Boolean? = null,
    enabled: Boolean = true,
    focusableWhenDisabled: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    focusRequester: FocusRequester? = null,
    focusedScale: Float = 1.08f,
    focusedBorderWidth: Dp = 4.dp,
    focusedBorderColor: Color = TvColors.FocusRing,
    semanticRole: Role? = Role.Button,
    semanticsLabel: String? = null,
    semanticsError: String? = null,
    toggleState: ToggleableState? = null,
    onFocus: () -> Unit = {},
    onKey: (KeyEvent) -> Boolean = { false },
    /** Holding OK: the item's menu. */
    onLongClick: (() -> Unit)? = null,
    /** No fill, only the focus ring (for frames over video). */
    transparent: Boolean = false,
    /** The fill while focused; pictures that don't cover the frame (logos) want a dark one. */
    focusedFill: Color = TvColors.Focus,
    /** Side-menu rows stay flat. A glow or raised layer paints over the screen while scrolling. */
    drawGlow: Boolean = true,
    raiseOnFocus: Boolean = true,
    /** The hairline around an unfocused frame (off for the hidden menu's marks). */
    outlined: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusAllowed = LocalTvFocusEnabled.current
    // Wide things (rows, panels) grow by a few dp at most, so they don't lurch over their
    // neighbours; cards and buttons grow by the full amount. The width is noted at layout time.
    val widthPx = remember { FloatArray(1) }
    val maxGrowPx = with(LocalDensity.current) { MAX_FOCUS_GROW.toPx() }
    val grownScale = widthPx[0].takeIf { it > 0f }
        ?.let { width -> minOf(focusedScale, 1f + maxGrowPx / width) }
        ?: focusedScale
    // A short, even grow: quick enough to keep up with a held-down arrow key.
    val scale = animateFloatAsState(
        targetValue = if (focused && enabled) grownScale.coerceAtLeast(1f) else 1f,
        animationSpec = tween(
            durationMillis = if (TvShapes.animations) FOCUS_SCALE_MS else 0,
            easing = FastOutSlowInEasing,
        ),
        label = "tv-focus-scale"
    )
    val glow = TvShapes.glow && drawGlow && focused && enabled && !transparent
    // The skin decides the shape of every focusable surface, how focus is shown (a fill, a ring
    // or a lift) and whether it glows; the [shape] callers pass is only a fallback.
    val hud = HudShape
    val focusStyle = TvShapes.focus
    val focusedBackground = when (focusStyle) {
        SkinFocus.Fill -> focusedFill
        SkinFocus.Ring -> TvColors.SurfaceRaised
        SkinFocus.Lift -> TvColors.SurfaceRaised
    }
    val selectedBackground = when (focusStyle) {
        SkinFocus.Fill -> TvColors.Focus.copy(alpha = 0.72f)
        else -> TvColors.Focus.copy(alpha = 0.16f)
    }
    val ringWidth = when (focusStyle) {
        SkinFocus.Fill -> focusedBorderWidth
        SkinFocus.Ring -> 3.dp
        SkinFocus.Lift -> 2.dp
    }
    val ringColor = when (focusStyle) {
        SkinFocus.Fill -> focusedBorderColor
        else -> TvColors.Focus
    }
    Box(
        modifier = modifier
            .onSizeChanged { widthPx[0] = it.width.toFloat() }
            .zIndex(if (raiseOnFocus && focused) 1f else 0f)
            // One layer for the grow, the glow and the chamfered clip. The size is read in the
            // layer itself, so the grow animation redraws the layer without recomposing.
            .graphicsLayer {
                val grown = scale.value
                scaleX = grown
                scaleY = grown
                shadowElevation = if (glow) FOCUS_GLOW.toPx() else 0f
                // "this.": FocusFrame's own [shape] parameter would shadow the layer's.
                this.shape = hud
                clip = true
                ambientShadowColor = TvColors.Focus
                spotShadowColor = TvColors.Focus
            }
            .background(
                when {
                    transparent -> Color.Transparent
                    focused && enabled -> focusedBackground
                    selected && enabled -> selectedBackground
                    else -> TvColors.Surface.copy(alpha = if (TvShapes.translucent) 0.62f else 0.86f)
                }
            )
            .border(
                BorderStroke(
                    width = if (focused) ringWidth else 1.dp,
                    color = when {
                        focused -> ringColor
                        selected -> TvColors.Focus.copy(alpha = if (focusStyle == SkinFocus.Fill) 1f else 0.6f)
                        !outlined -> Color.Transparent
                        else -> TvColors.TextPrimary.copy(alpha = 0.08f)
                    }
                ),
                shape = hud
            )
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(
                if (enabled && focusAllowed) {
                    Modifier
                        .onKeyEvent { event ->
                            when {
                                onKey(event) -> true
                                // With a long-press action the clickable below tells a tap
                                // from a hold, so it handles OK itself.
                                onLongClick == null &&
                                    event.type == KeyEventType.KeyUp &&
                                    event.key.isDpadConfirmKey() -> {
                                    onClick()
                                    true
                                }
                                else -> false
                            }
                        }
                        .then(
                            if (onLongClick != null) {
                                Modifier.combinedClickable(
                                    role = null,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                )
                            } else {
                                Modifier.clickable(
                                    role = null,
                                    onClick = onClick,
                                )
                            }
                        )
                        .focusable()
                } else if (focusableWhenDisabled && focusAllowed) {
                    Modifier.focusable()
                } else {
                    Modifier
                }
            )
            .then(
                if (semanticRole != null && semanticsLabel != null) {
                    Modifier.clearAndSetSemantics {
                        contentDescription = semanticsLabel
                        selectionState?.let { isSelected ->
                            this.selected = isSelected
                        }
                        toggleState?.let { state ->
                            toggleableState = state
                        }
                        semanticsError?.let { message ->
                            error(message)
                        }
                        role = semanticRole
                        if (enabled) {
                            semanticsOnClick {
                                onClick()
                                true
                            }
                        } else {
                            disabled()
                        }
                    }
                } else if (semanticRole != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        selectionState?.let { isSelected ->
                            this.selected = isSelected
                        }
                        toggleState?.let { state ->
                            toggleableState = state
                        }
                        semanticsError?.let { message ->
                            error(message)
                        }
                        role = semanticRole
                        if (!enabled) {
                            disabled()
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        content(focused)
    }
}

@Composable
fun TvIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    /** Sees every key while focused (holding OK to fast-forward, say). True means handled. */
    onKey: (KeyEvent) -> Boolean = { false },
) {
    FocusFrame(
        onClick = onClick,
        onKey = onKey,
        shape = RoundedCornerShape(28.dp),
        focusRequester = focusRequester,
        semanticsLabel = contentDescription,
        modifier = modifier.size(56.dp)
    ) { focused ->
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(28.dp)
        )
    }
}

@Composable
fun TvActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusableWhenDisabled: Boolean = false,
    focusRequester: FocusRequester? = null,
    showTextWhenUnfocused: Boolean = true,
    selected: Boolean? = null,
    checked: Boolean? = null,
    semanticRole: Role = Role.Button,
    semanticsLabel: String? = null,
    semanticsError: String? = null,
    supportingText: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    FocusFrame(
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected == true || checked == true,
        selectionState = selected.takeIf { checked == null },
        enabled = enabled,
        focusableWhenDisabled = focusableWhenDisabled,
        shape = RoundedCornerShape(24.dp),
        focusRequester = focusRequester,
        focusedScale = 1.04f,
        semanticRole = semanticRole,
        semanticsLabel = semanticsLabel ?: listOfNotNull(text, supportingText)
            .joinToString(separator = ". "),
        semanticsError = semanticsError,
        toggleState = checked?.let { isChecked ->
            if (isChecked) ToggleableState.On else ToggleableState.Off
        },
        modifier = modifier.heightIn(min = 48.dp),
    ) { focused ->
        val showText = focused || showTextWhenUnfocused
        val active = focused || selected == true || checked == true
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    horizontal = if (showText) 16.dp else 12.dp,
                    vertical = 8.dp,
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> TvColors.TextMuted
                    active -> TvColors.OnFocus
                    else -> TvColors.TextPrimary
                },
                modifier = Modifier.size(24.dp)
            )
            if (showText) {
                val primaryColor = when {
                    !enabled -> TvColors.TextMuted
                    active -> TvColors.OnFocus
                    else -> TvColors.TextPrimary
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = text,
                        color = primaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = TvFonts.Body,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    supportingText?.let { supportingLabel ->
                        Text(
                            text = supportingLabel,
                            color = when {
                                !enabled -> TvColors.TextMuted
                                active -> TvColors.OnFocus.copy(alpha = 0.78f)
                                else -> TvColors.TextSecondary
                            },
                            fontSize = 12.sp,
                            fontFamily = TvFonts.Body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Text(
            text = title,
            color = TvColors.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = TvFonts.Body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = TvColors.TextSecondary,
            fontSize = 14.sp,
            fontFamily = TvFonts.Body,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    compact: Boolean = false
) {
    val openMenu = LocalChannelMenu.current
    val requester = focusRequester ?: remember { FocusRequester() }
    FocusFrame(
        onClick = onPlay,
        onLongClick = openMenu?.let { menu -> { menu(channel, requester) } },
        modifier = modifier,
        focusRequester = requester,
        onFocus = onFocused,
        shape = RoundedCornerShape(12.dp)
    ) { focused ->
        val primaryTextColor = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondaryTextColor = if (focused) TvColors.OnFocus.copy(alpha = 0.78f) else TvColors.TextSecondary
        if (compact) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    PosterArt(
                        model = channel.cover,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                ) {
                    Text(
                        text = channel.title.title(),
                        color = primaryTextColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = TvFonts.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Box {
                    PosterArt(
                        model = channel.cover,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Text(
                    text = channel.title.title(),
                    color = primaryTextColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = TvFonts.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channel.category.ifBlank { stringResource(string.feat_playlist_scheme_unknown) },
                    color = secondaryTextColor,
                    fontSize = 12.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PlaylistCard(
    playlist: Playlist,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val largeTextLayout = tvLargeTextLayout(LocalDensity.current.fontScale)
    FocusFrame(
        onClick = onClick,
        selected = selected,
        selectionState = selected,
        modifier = modifier.heightIn(min = largeTextLayout.playlistCardMinHeightDp.dp),
        focusRequester = focusRequester,
        shape = RoundedCornerShape(12.dp)
    ) { focused ->
        val active = selected || focused
        val primaryTextColor = if (active) TvColors.OnFocus else TvColors.TextPrimary
        val secondaryTextColor = if (active) TvColors.OnFocus.copy(alpha = 0.72f) else TvColors.TextSecondary
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) TvColors.OnFocus else TvColors.Focus)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    contentDescription = null,
                    tint = if (active) TvColors.Focus else TvColors.OnFocus,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = playlist.title.title(),
                    color = primaryTextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = TvFonts.Body,
                    maxLines = if (largeTextLayout.stackEmptyLibrary) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = playlistLabel(playlist, count),
                    color = secondaryTextColor,
                    fontSize = 12.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = if (largeTextLayout.stackEmptyLibrary) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    FocusFrame(
        onClick = {},
        enabled = false,
        semanticRole = null,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TvColors.Focus,
                modifier = Modifier.size(32.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = value,
                    color = TvColors.TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = TvFonts.Accent,
                    maxLines = 1
                )
                Text(
                    text = title,
                    color = TvColors.TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = TvFonts.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun PosterArt(
    model: String?,
    modifier: Modifier = Modifier,
    /** Off for backdrops, which always fill their frame. */
    fitLogos: Boolean = true,
) {
    // Posters fill the frame; logos and wide pictures are shown whole, never cut off.
    var shape by remember(model) { mutableStateOf(ArtShape.Unknown) }
    // The frame's shape, noted at layout time (a plain holder: reading it never recomposes, which
    // keeps long grids of these light to scroll).
    val frame = remember { FrameShape() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(TvColors.SurfaceRaised)
            .onSizeChanged { size ->
                if (size.height > 0) frame.ratio = size.width.toFloat() / size.height
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = if (shape == ArtShape.Logo) ContentScale.Fit else ContentScale.Crop,
            onSuccess = onSuccess@{ success ->
                if (!fitLogos) return@onSuccess
                val drawable = success.result.drawable
                val ratio = if (drawable.intrinsicHeight > 0) {
                    drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                } else 0f
                val frameRatio = frame.ratio
                shape = if (ratio > 0f && abs(ratio - frameRatio) > LOGO_RATIO_TOLERANCE * frameRatio) {
                    ArtShape.Logo
                } else {
                    ArtShape.Poster
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .then(if (shape == ArtShape.Logo) Modifier.padding(12.dp) else Modifier)
        )
        if (model.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = TvColors.TextMuted,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

private enum class ArtShape { Unknown, Poster, Logo }

private class FrameShape {
    var ratio: Float = 1f
}

private const val FOCUS_SCALE_MS = 130

/** The neon glow under a focused frame. */
private val FOCUS_GLOW = 18.dp

/** The most a focused frame grows across its width (half on each side). */
private val MAX_FOCUS_GROW = 28.dp

/** A picture more than this far from the frame's shape is treated as a logo and shown whole. */
private const val LOGO_RATIO_TOLERANCE = 0.35f

@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 40.dp
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = TvColors.TextSecondary,
            fontSize = 13.sp,
            fontFamily = TvFonts.Body,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun Key.isDpadConfirmKey(): Boolean = this == Key.DirectionCenter ||
    this == Key.Enter ||
    this == Key.NumPadEnter

@Composable
fun playlistLabel(playlist: Playlist, count: Int): String {
    val type = when {
        playlist.isSeries -> stringResource(string.tv_playlist_type_series)
        playlist.isVod -> stringResource(string.tv_playlist_type_vod)
        else -> playlist.source.value.uppercase(Locale.ROOT)
    }
    return pluralStringResource(plurals.tv_playlist_label, count, type, count)
}
