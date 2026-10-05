package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.m3u.core.foundation.util.basic.title
import com.m3u.data.database.model.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/* -------------------------------------------------------------------------------------------------
 * Hold OK on a channel, film, series or category: a short menu of what can be done with it,
 * like Nuvio's long-press sheet. Up/Down to choose, OK to do it, Back to close.
 * ---------------------------------------------------------------------------------------------- */

enum class MenuItemKind { Live, Film, Series }

@Immutable
data class MenuEntry(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val checked: Boolean? = null,
)

/** What the channel menu can do; each is null when it doesn't apply. */
class ChannelMenuActions(
    val play: () -> Unit,
    val playFromStart: (() -> Unit)? = null,
    val details: (() -> Unit)? = null,
    val toggleFavourite: () -> Unit,
    val playExternally: (() -> Unit)? = null,
    val hide: (() -> Unit)? = null,
    val askClaude: (() -> Unit)? = null,
    val addToMultiview: (() -> Unit)? = null,
    val toggleGroup: (String) -> Unit = {},
    val createGroup: (String) -> Unit = {},
    /** The channel's refresh-rate rule, and what OK does to it. */
    val frameRate: FrameRateMode? = null,
    val cycleFrameRate: (() -> Unit)? = null,
)

@Composable
fun ChannelMenu(
    channel: Channel,
    kind: MenuItemKind,
    favourite: Boolean,
    groups: List<FavouriteGroup>,
    actions: ChannelMenuActions,
    onDismiss: () -> Unit,
) {
    var groupsPage by remember(channel.id) { mutableStateOf(false) }
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }

    if (groupsPage) {
        FavouriteGroupsPage(
            channel = channel,
            groups = groups,
            onToggle = actions.toggleGroup,
            onCreate = actions.createGroup,
            onBack = { groupsPage = false },
        )
        return
    }

    val entries = buildList {
        add(
            MenuEntry(
                label = stringResource(
                    when (kind) {
                        MenuItemKind.Series -> R.string.dial_menu_open
                        else -> R.string.dial_menu_play
                    }
                ),
                icon = Icons.Rounded.PlayArrow,
                onClick = run(actions.play),
            )
        )
        actions.playFromStart?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_play_from_start), Icons.Rounded.Replay, run(it)))
        }
        actions.details?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_details), Icons.Rounded.Info, run(it)))
        }
        add(
            when (kind) {
                MenuItemKind.Live -> MenuEntry(
                    label = stringResource(
                        if (favourite) R.string.dial_menu_unfavourite else R.string.dial_menu_favourite
                    ),
                    icon = if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = run(actions.toggleFavourite),
                )
                else -> MenuEntry(
                    label = stringResource(
                        if (favourite) R.string.dial_menu_library_remove else R.string.dial_menu_library_add
                    ),
                    icon = if (favourite) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    onClick = run(actions.toggleFavourite),
                )
            }
        )
        if (kind == MenuItemKind.Live) {
            add(
                MenuEntry(
                    label = stringResource(R.string.dial_menu_groups),
                    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                    onClick = { groupsPage = true },
                )
            )
        }
        actions.addToMultiview?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_multiview), Icons.Rounded.GridView, run(it)))
        }
        actions.playExternally?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_play_externally), Icons.AutoMirrored.Rounded.OpenInNew, run(it)))
        }
        actions.askClaude?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_ask_claude), Icons.Rounded.AutoAwesome, run(it)))
        }
        val frameRate = actions.frameRate
        val cycleFrameRate = actions.cycleFrameRate
        if (frameRate != null && cycleFrameRate != null) {
            add(
                MenuEntry(
                    label = stringResource(
                        R.string.dial_menu_frame_rate,
                        stringResource(
                            when (frameRate) {
                                FrameRateMode.Default -> R.string.dial_frame_rate_default
                                FrameRateMode.Match -> R.string.dial_frame_rate_match
                                FrameRateMode.Hz60 -> R.string.dial_frame_rate_60
                                FrameRateMode.Off -> R.string.dial_frame_rate_off
                            }
                        ),
                    ),
                    icon = Icons.Rounded.Speed,
                    // Stays open: the label shows the new rule straight away.
                    onClick = cycleFrameRate,
                )
            )
        }
        actions.hide?.let {
            add(MenuEntry(stringResource(R.string.dial_menu_hide_channel), Icons.Rounded.VisibilityOff, run(it)))
        }
    }

    MenuPanel(
        title = channel.title.title(),
        subtitle = channel.category.takeIf { it.isNotBlank() },
        entries = entries,
        onDismiss = onDismiss,
    )
}

@Composable
private fun FavouriteGroupsPage(
    channel: Channel,
    groups: List<FavouriteGroup>,
    onToggle: (String) -> Unit,
    onCreate: (String) -> Unit,
    onBack: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val entries = groups.map { group ->
        val inGroup = channel.id in group.channelIds
        MenuEntry(
            label = group.name,
            icon = if (inGroup) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
            onClick = { onToggle(group.id) },
            checked = inGroup,
        )
    } + MenuEntry(
        label = stringResource(R.string.dial_menu_new_group),
        icon = Icons.Rounded.Add,
        onClick = { naming = true },
    )
    MenuPanel(
        title = stringResource(R.string.dial_menu_groups),
        subtitle = channel.title.title(),
        entries = entries,
        onDismiss = { if (naming) naming = false else onBack() },
        footer = if (naming) {
            {
                DialTextField(
                    label = stringResource(R.string.dial_menu_group_name),
                    value = name,
                    onValueChange = { name = it },
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    placeholder = stringResource(R.string.dial_menu_group_name_hint),
                    onDone = {
                        if (name.isNotBlank()) {
                            onCreate(name)
                            name = ""
                            naming = false
                        }
                    },
                )
                TvActionButton(
                    text = stringResource(R.string.dial_menu_create_group),
                    icon = Icons.Rounded.Add,
                    enabled = name.isNotBlank(),
                    onClick = {
                        onCreate(name)
                        name = ""
                        naming = false
                    },
                )
            }
        } else null,
    )
}

@Composable
fun CategoryMenu(
    name: String,
    index: Int,
    count: Int,
    hiddenCount: Int,
    onMoveToFront: () -> Unit,
    onMove: (Int) -> Unit,
    onHide: () -> Unit,
    onShowAll: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    val entries = buildList {
        if (index > 0) {
            add(MenuEntry(stringResource(R.string.dial_menu_category_front), Icons.Rounded.VerticalAlignTop, run(onMoveToFront)))
            add(MenuEntry(stringResource(R.string.dial_menu_category_earlier), Icons.AutoMirrored.Rounded.ArrowBack, { onMove(-1) }))
        }
        if (index in 0 until count - 1) {
            add(MenuEntry(stringResource(R.string.dial_menu_category_later), Icons.AutoMirrored.Rounded.ArrowForward, { onMove(1) }))
        }
        if (count > 1) {
            add(MenuEntry(stringResource(R.string.dial_menu_category_hide), Icons.Rounded.VisibilityOff, run(onHide)))
        }
        if (hiddenCount > 0) {
            add(
                MenuEntry(
                    stringResource(R.string.dial_menu_category_show_all, hiddenCount),
                    Icons.Rounded.Visibility,
                    run(onShowAll),
                )
            )
        }
        add(MenuEntry(stringResource(R.string.dial_menu_category_reset), Icons.Rounded.RestartAlt, run(onReset)))
    }
    MenuPanel(
        title = name.ifBlank { stringResource(R.string.dial_category_uncategorised) },
        subtitle = stringResource(R.string.dial_menu_category_subtitle),
        entries = entries,
        onDismiss = onDismiss,
    )
}

/** Dimmed screen with a centred list of actions. Back closes it. */
@Composable
fun MenuPanel(
    title: String,
    subtitle: String?,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val firstEntry = remember { FocusRequester() }
    // The menu opens while OK is still held (a long press); its repeats and release must not
    // pick the first entry. OK counts again after a fresh press.
    var okArmed by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(title, entries.size) {
        for (attempt in 0 until MENU_FOCUS_ATTEMPTS) {
            yield()
            if (runCatching { firstEntry.requestFocus() }.isSuccess) break
            delay(MENU_FOCUS_RETRY_MS)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .onPreviewKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                when {
                    !confirm || okArmed -> false
                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                        okArmed = true
                        false
                    }
                    else -> true
                }
            }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                // Keep the remote inside the menu until it's closed.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .width(520.dp)
                .heightIn(max = 520.dp)
                .background(TvColors.BackgroundSoft, HudShape)
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                itemsIndexed(entries) { index, entry ->
                    MenuRow(entry, focusRequester = firstEntry.takeIf { index == 0 })
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun MenuRow(entry: MenuEntry, focusRequester: FocusRequester?) {
    FocusFrame(
        onClick = entry.onClick,
        selected = entry.checked == true,
        selectionState = entry.checked,
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.02f,
        focusRequester = focusRequester,
        semanticsLabel = entry.label,
        modifier = Modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val active = focused || entry.checked == true
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = if (active) TvColors.OnFocus else TvColors.Focus,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = entry.label,
                color = if (active) TvColors.OnFocus else TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MENU_FOCUS_ATTEMPTS = 10
private const val MENU_FOCUS_RETRY_MS = 32L
