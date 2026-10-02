package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield

/*
 * Games tab: three small arcade games played with the Fire TV remote, drawn in the app's neon
 * style. Each is an original take on a classic format: a snake game, a falling-blocks puzzle and
 * an endless vertical jumper. Best scores are kept on the device.
 */

enum class MiniGame { Snake, Blocks, SkyHop, Blackjack, Roulette }

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val store: DialSettingsStore,
) : ViewModel() {
    private val _best = MutableStateFlow(MiniGame.entries.associateWith { store.highScore(it.name) })
    val best: StateFlow<Map<MiniGame, Int>> = _best.asStateFlow()

    fun submit(game: MiniGame, score: Int) {
        if (score > (_best.value[game] ?: 0)) {
            store.saveHighScore(game.name, score)
            _best.update { it + (game to score) }
        }
    }
}

@Composable
fun GamesScreen(viewModel: GamesViewModel = hiltViewModel()) {
    val best by viewModel.best.collectAsStateWithLifecycle()
    var playing by rememberSaveable { mutableStateOf<MiniGame?>(null) }
    var lastPlayed by rememberSaveable { mutableStateOf<MiniGame?>(null) }

    val game = playing
    if (game == null) {
        GamesMenu(
            best = best,
            focusOn = lastPlayed,
            onPlay = {
                lastPlayed = it
                playing = it
            },
        )
    } else {
        BackHandler { playing = null }
        val onGameOver: (Int) -> Unit = { score -> viewModel.submit(game, score) }
        val bestScore = best[game] ?: 0
        when (game) {
            MiniGame.Snake -> SnakeGame(best = bestScore, onGameOver = onGameOver)
            MiniGame.Blocks -> BlocksGame(best = bestScore, onGameOver = onGameOver)
            MiniGame.SkyHop -> SkyHopGame(best = bestScore, onGameOver = onGameOver)
            MiniGame.Blackjack -> BlackjackGame(best = bestScore, onGameOver = onGameOver)
            MiniGame.Roulette -> RouletteGame(best = bestScore, onGameOver = onGameOver)
        }
    }
}

@Composable
private fun GamesMenu(
    best: Map<MiniGame, Int>,
    focusOn: MiniGame?,
    onPlay: (MiniGame) -> Unit,
) {
    val requesters = remember { MiniGame.entries.associateWith { FocusRequester() } }
    LaunchedEffect(focusOn) {
        val target = focusOn ?: return@LaunchedEffect
        yield()
        runCatching { requesters.getValue(target).requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 40.dp, end = 64.dp, bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.dial_games_title),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Accent,
            fontSize = 30.sp,
        )
        Text(
            text = stringResource(R.string.dial_games_subtitle),
            color = TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            MiniGame.entries.forEach { game ->
                FocusFrame(
                    onClick = { onPlay(game) },
                    focusRequester = requesters.getValue(game),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    focusedScale = 1.05f,
                    semanticsLabel = stringResource(game.titleRes()),
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 260.dp)
                ) { focused ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .background(TvColors.Background.copy(alpha = if (focused) 0.9f else 0.6f))
                        ) {
                            drawGameIcon(game)
                        }
                        Text(
                            text = stringResource(game.titleRes()),
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Accent,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = stringResource(game.blurbRes()),
                            color = if (focused) TvColors.OnFocus.copy(alpha = 0.8f) else TvColors.TextSecondary,
                            fontFamily = TvFonts.Body,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            maxLines = 3,
                        )
                        Text(
                            text = stringResource(R.string.dial_games_best, best[game] ?: 0),
                            color = if (focused) TvColors.OnFocus else TvColors.Accent,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shared frame for every game: header with score and best, a focusable play area that takes all
 * remote keys except Back, and a centred message for "press OK to start" and "game over".
 */
@Composable
internal fun GameScaffold(
    title: String,
    score: Int,
    best: Int,
    hint: String,
    message: String?,
    frame: Long,
    playAreaAspect: Float?,
    onKey: (KeyEvent) -> Boolean,
    draw: DrawScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yield()
        runCatching { focus.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 24.dp, end = 40.dp, bottom = 20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                color = TvColors.Focus,
                fontFamily = TvFonts.Accent,
                fontSize = 26.sp,
            )
            Text(
                text = stringResource(R.string.dial_games_score, score),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 18.sp,
            )
            Text(
                text = stringResource(R.string.dial_games_best, maxOf(best, score)),
                color = TvColors.Accent,
                fontFamily = TvFonts.Accent,
                fontSize = 18.sp,
            )
            Text(
                text = hint,
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
                maxLines = 2,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Key handling must sit before the focus target so it sees the keys.
                .onKeyEvent(onKey)
                .focusRequester(focus)
                .focusable()
        ) {
            // Keep the playfield's shape: as tall as the space allows, width to match.
            val area = if (playAreaAspect != null) {
                Modifier.aspectRatio(playAreaAspect, matchHeightConstraintsFirst = true)
            } else {
                Modifier.fillMaxSize()
            }
            Canvas(
                area
                    .background(TvColors.Background)
                    .border(2.dp, TvColors.Focus.copy(alpha = 0.7f), HudShape)
            ) {
                // Reading the frame counter here redraws the canvas every game tick.
                if (frame >= 0L) draw()
            }
            if (message != null) {
                Text(
                    text = message,
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(TvColors.Background.copy(alpha = 0.82f), HudShape)
                        .border(1.dp, TvColors.Accent, HudShape)
                        .padding(horizontal = 28.dp, vertical = 18.dp)
                )
            }
        }
    }
}

private fun MiniGame.titleRes(): Int = when (this) {
    MiniGame.Snake -> R.string.dial_game_snake
    MiniGame.Blocks -> R.string.dial_game_blocks
    MiniGame.SkyHop -> R.string.dial_game_skyhop
    MiniGame.Blackjack -> R.string.dial_game_blackjack
    MiniGame.Roulette -> R.string.dial_game_roulette
}

private fun MiniGame.blurbRes(): Int = when (this) {
    MiniGame.Snake -> R.string.dial_game_snake_blurb
    MiniGame.Blocks -> R.string.dial_game_blocks_blurb
    MiniGame.SkyHop -> R.string.dial_game_skyhop_blurb
    MiniGame.Blackjack -> R.string.dial_game_blackjack_blurb
    MiniGame.Roulette -> R.string.dial_game_roulette_blurb
}

/** Little neon previews on the menu cards. */
private fun DrawScope.drawGameIcon(game: MiniGame) {
    val w = size.width
    val h = size.height
    val cell = h / 8f
    when (game) {
        MiniGame.Snake -> {
            val path = listOf(2 to 5, 3 to 5, 4 to 5, 5 to 5, 5 to 4, 5 to 3, 6 to 3, 7 to 3)
            path.forEachIndexed { i, (x, y) ->
                drawRect(
                    color = if (i == path.lastIndex) TvColors.FocusRing else TvColors.Focus,
                    topLeft = Offset(x * cell + w / 2 - 5 * cell, y * cell),
                    size = Size(cell * 0.9f, cell * 0.9f),
                )
            }
            drawCircle(TvColors.Accent, cell * 0.4f, Offset(w / 2 + 4.5f * cell, 3.45f * cell))
        }
        MiniGame.Blocks -> {
            val cells = listOf(
                Triple(0, 7, 0), Triple(1, 7, 0), Triple(2, 7, 1), Triple(3, 7, 1), Triple(4, 7, 2),
                Triple(0, 6, 3), Triple(1, 6, 1), Triple(3, 6, 2), Triple(4, 6, 2),
                Triple(2, 2, 4), Triple(2, 3, 4), Triple(3, 3, 4), Triple(2, 4, 4),
            )
            val left = w / 2 - 2.5f * cell
            cells.forEach { (x, y, c) ->
                drawRect(
                    color = BLOCK_COLORS[c],
                    topLeft = Offset(left + x * cell, y * cell),
                    size = Size(cell * 0.92f, cell * 0.92f),
                )
            }
        }
        MiniGame.SkyHop -> {
            listOf(0.25f to 0.85f, 0.62f to 0.62f, 0.35f to 0.38f, 0.7f to 0.16f).forEach { (x, y) ->
                drawRect(
                    TvColors.Focus,
                    Offset(w * x - cell * 1.2f, h * y),
                    Size(cell * 2.4f, cell * 0.35f),
                )
            }
            drawHopper(Offset(w * 0.35f, h * 0.38f - cell * 1.1f), cell * 1.1f, facingRight = true)
        }
        MiniGame.Blackjack, MiniGame.Roulette -> {
            drawCircle(TvColors.Focus, h * 0.28f, Offset(w / 2f, h / 2f), style = Stroke(width = 3.dp.toPx()))
            drawCircle(TvColors.Accent, h * 0.08f, Offset(w / 2f, h / 2f))
        }
    }
    drawRect(TvColors.Focus.copy(alpha = 0.25f), style = Stroke(width = 1.dp.toPx()))
}
