package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel

/* -------------------------------------------------------------------------------------------------
 * The channel list over live TV: Left (with the controls hidden) slides in the channels of the
 * list being zapped through, with what's on each now; Up and Down move, OK switches, Back or
 * Right closes. The picture keeps playing behind it.
 * ---------------------------------------------------------------------------------------------- */

@Composable
fun PlayerChannelList(
    channels: List<Channel>,
    currentId: Int?,
    nowTitles: Map<Int, String>,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val listState = rememberLazyListState()
    val current = remember(channels, currentId) { channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // A few rows above the one playing, so it isn't stuck at the top edge.
        listState.scrollToItem((current - SCROLL_CONTEXT).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxHeight()
            .width(560.dp)
            .background(TvColors.Background.copy(alpha = 0.94f))
            .padding(top = 32.dp, start = 24.dp, end = 24.dp)
            // Right closes; Left has nowhere to go.
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) onClose()
                        true
                    }
                    Key.DirectionLeft -> true
                    else -> false
                }
            },
    ) {
        Text(
            text = stringResource(R.string.dial_player_channels, channels.size),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 48.dp),
            // The player's controls underneath are only faded out: focus stays in the list.
            modifier = Modifier
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                val playing = channel.id == currentId
                FocusFrame(
                    onClick = { onPick(channel) },
                    focusRequester = focus.takeIf { index == current },
                    selected = playing,
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.02f,
                    semanticsLabel = channel.title,
                    modifier = Modifier.fillMaxWidth(),
                ) { focused ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = when {
                                focused -> TvColors.OnFocus
                                playing -> TvColors.Focus
                                else -> TvColors.TextMuted
                            },
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.width(36.dp),
                        )
                        PosterArt(
                            model = channel.cover,
                            modifier = Modifier
                                .size(width = 64.dp, height = 40.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.title,
                                color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                                fontFamily = TvFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            nowTitles[channel.id]?.let { now ->
                                Text(
                                    text = now,
                                    color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                                    fontFamily = TvFonts.Body,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SCROLL_CONTEXT = 3
