package com.m3u.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/* -------------------------------------------------------------------------------------------------
 * Shared plumbing
 * ---------------------------------------------------------------------------------------------- */

internal enum class GamePhase { Ready, Playing, GameOver }

internal val BLOCK_COLORS = listOf(
    Color(0xFF19F0FF), // cyan
    Color(0xFFFFE14D), // yellow
    Color(0xFFFF2BD6), // magenta
    Color(0xFF3DFF9A), // green
    Color(0xFFFF3B6B), // red
    Color(0xFF4D7CFF), // blue
    Color(0xFFFF9A3D), // orange
)

/** Calls [onStep] with the seconds since the last frame, every frame, and returns a frame counter. */
@Composable
private fun rememberGameClock(onStep: (Float) -> Unit): Long {
    var frame by remember { mutableLongStateOf(0L) }
    val step by rememberUpdatedState(onStep)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                step(dt)
                frame++
            }
        }
    }
    return frame
}

/** Keys the games use. Everything else (Back, Home) passes through to the app. */
private fun KeyEvent.isGameKey(): Boolean = key in setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
)

private fun KeyEvent.isConfirm(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

@Composable
private fun phaseMessage(phase: GamePhase, score: Int): String? = when (phase) {
    GamePhase.Ready -> stringResource(R.string.dial_games_press_ok)
    GamePhase.GameOver -> stringResource(R.string.dial_games_over, score)
    GamePhase.Playing -> null
}

/** The Sky Hop character: a small neon robot with a magenta visor. */
internal fun DrawScope.drawHopper(center: Offset, size: Float, facingRight: Boolean) {
    val half = size / 2f
    drawRoundRect(
        color = TvColors.Focus.copy(alpha = 0.25f),
        topLeft = Offset(center.x - half * 1.3f, center.y - half * 1.3f),
        size = Size(size * 1.3f, size * 1.3f),
        cornerRadius = CornerRadius(size * 0.35f),
    )
    drawRoundRect(
        color = TvColors.Focus,
        topLeft = Offset(center.x - half, center.y - half),
        size = Size(size, size),
        cornerRadius = CornerRadius(size * 0.25f),
    )
    drawRect(
        color = TvColors.Accent,
        topLeft = Offset(center.x - half * 0.8f, center.y - half * 0.45f),
        size = Size(size * 0.8f, size * 0.26f),
    )
    val look = if (facingRight) size * 0.1f else -size * 0.1f
    listOf(-0.2f, 0.2f).forEach { dx ->
        drawRect(
            color = TvColors.OnFocus,
            topLeft = Offset(center.x + dx * size + look - size * 0.05f, center.y - half * 0.38f),
            size = Size(size * 0.1f, size * 0.12f),
        )
    }
    listOf(-0.22f, 0.22f).forEach { dx ->
        drawLine(
            color = TvColors.FocusRing,
            start = Offset(center.x + dx * size, center.y + half),
            end = Offset(center.x + dx * size, center.y + half * 1.35f),
            strokeWidth = size * 0.1f,
        )
    }
}

private fun DrawScope.drawGlowCell(color: Color, topLeft: Offset, cell: Float) {
    drawRect(color.copy(alpha = 0.22f), Offset(topLeft.x - cell * 0.12f, topLeft.y - cell * 0.12f), Size(cell * 1.24f, cell * 1.24f))
    drawRect(color, Offset(topLeft.x + cell * 0.06f, topLeft.y + cell * 0.06f), Size(cell * 0.88f, cell * 0.88f))
}

/* -------------------------------------------------------------------------------------------------
 * Snake
 * ---------------------------------------------------------------------------------------------- */

private enum class Heading(val dx: Int, val dy: Int) {
    Up(0, -1), Down(0, 1), Left(-1, 0), Right(1, 0);

    fun isOpposite(other: Heading) = dx == -other.dx && dy == -other.dy
}

private class SnakeState {
    val cols = 28
    val rows = 16
    val body = ArrayDeque<Pair<Int, Int>>()
    var food = 0 to 0
    var score by mutableIntStateOf(0)
    var phase by mutableStateOf(GamePhase.Ready)
    private var heading = Heading.Right
    private var queued = Heading.Right
    private var timer = 0f

    fun start() {
        body.clear()
        for (i in 0 until 4) body.addLast((7 - i) to rows / 2)
        heading = Heading.Right
        queued = Heading.Right
        score = 0
        timer = 0f
        placeFood()
        phase = GamePhase.Playing
    }

    fun turn(next: Heading) {
        if (!next.isOpposite(heading)) queued = next
    }

    fun step(dt: Float) {
        if (phase != GamePhase.Playing) return
        timer += dt
        // Speeds up a little with every snack.
        val interval = (0.14f - score * 0.0015f).coerceAtLeast(0.06f)
        while (timer >= interval && phase == GamePhase.Playing) {
            timer -= interval
            advance()
        }
    }

    private fun advance() {
        heading = queued
        val (hx, hy) = body.first()
        val next = (hx + heading.dx) to (hy + heading.dy)
        val grows = next == food
        val hitsWall = next.first !in 0 until cols || next.second !in 0 until rows
        // The tail moves out of the way this step unless the snake is growing.
        val blocked = if (grows) body.toList() else body.toList().dropLast(1)
        if (hitsWall || next in blocked) {
            phase = GamePhase.GameOver
            return
        }
        body.addFirst(next)
        if (grows) {
            score += 10
            placeFood()
        } else {
            body.removeLast()
        }
    }

    private fun placeFood() {
        val taken = body.toSet()
        val free = (0 until cols).flatMap { x -> (0 until rows).map { y -> x to y } }.filter { it !in taken }
        if (free.isEmpty()) phase = GamePhase.GameOver else food = free.random()
    }
}

@Composable
fun SnakeGame(best: Int, onGameOver: (Int) -> Unit) {
    val state = remember { SnakeState() }
    val frame = rememberGameClock(state::step)
    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.GameOver) onGameOver(state.score)
    }
    GameScaffold(
        title = stringResource(R.string.dial_game_snake),
        score = state.score,
        best = best,
        hint = stringResource(R.string.dial_game_snake_hint),
        message = phaseMessage(state.phase, state.score),
        frame = frame,
        playAreaAspect = state.cols.toFloat() / state.rows,
        onKey = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when {
                    event.isConfirm() -> if (state.phase != GamePhase.Playing) state.start()
                    event.key == Key.DirectionUp -> state.turn(Heading.Up)
                    event.key == Key.DirectionDown -> state.turn(Heading.Down)
                    event.key == Key.DirectionLeft -> state.turn(Heading.Left)
                    event.key == Key.DirectionRight -> state.turn(Heading.Right)
                    else -> Unit
                }
            }
            event.isGameKey()
        },
    ) {
        val cell = min(size.width / state.cols, size.height / state.rows)
        val left = (size.width - cell * state.cols) / 2f
        val top = (size.height - cell * state.rows) / 2f
        for (x in 0 until state.cols) {
            for (y in 0 until state.rows) {
                drawCircle(
                    TvColors.Focus.copy(alpha = 0.08f),
                    radius = cell * 0.06f,
                    center = Offset(left + (x + 0.5f) * cell, top + (y + 0.5f) * cell),
                )
            }
        }
        val (fx, fy) = state.food
        if (state.phase != GamePhase.Ready) {
            drawCircle(TvColors.Accent.copy(alpha = 0.3f), cell * 0.6f, Offset(left + (fx + 0.5f) * cell, top + (fy + 0.5f) * cell))
            drawCircle(TvColors.Accent, cell * 0.36f, Offset(left + (fx + 0.5f) * cell, top + (fy + 0.5f) * cell))
        }
        state.body.forEachIndexed { index, (x, y) ->
            drawGlowCell(
                color = if (index == 0) TvColors.FocusRing else TvColors.Focus,
                topLeft = Offset(left + x * cell, top + y * cell),
                cell = cell,
            )
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Blocks: a falling-blocks puzzle
 * ---------------------------------------------------------------------------------------------- */

/** The seven four-square pieces, as cells around a pivot at (0, 0). y grows downwards. */
private val PIECES = listOf(
    listOf(-1 to 0, 0 to 0, 1 to 0, 2 to 0),
    listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
    listOf(-1 to 0, 0 to 0, 1 to 0, 0 to -1),
    listOf(-1 to 0, 0 to 0, 0 to -1, 1 to -1),
    listOf(-1 to -1, 0 to -1, 0 to 0, 1 to 0),
    listOf(-1 to -1, -1 to 0, 0 to 0, 1 to 0),
    listOf(1 to -1, -1 to 0, 0 to 0, 1 to 0),
)
private const val SQUARE_PIECE = 1
private val LINE_SCORES = listOf(0, 100, 300, 500, 800)

private class BlocksState {
    val cols = 10
    val rows = 20
    /** 0 is empty; otherwise the colour index plus one. */
    val board = Array(rows) { IntArray(cols) }
    var cells: List<Pair<Int, Int>> = PIECES[0]
    var kind = 0
    var nextKind = 0
    var px = 4
    var py = 1
    var score by mutableIntStateOf(0)
    var level by mutableIntStateOf(1)
    var phase by mutableStateOf(GamePhase.Ready)
    private var lines = 0
    private var fall = 0f

    fun start() {
        board.forEach { it.fill(0) }
        score = 0
        lines = 0
        level = 1
        fall = 0f
        nextKind = Random.nextInt(PIECES.size)
        phase = GamePhase.Playing
        spawn()
    }

    fun fits(shape: List<Pair<Int, Int>>, x: Int, y: Int): Boolean = shape.all { (dx, dy) ->
        val cx = x + dx
        val cy = y + dy
        cx in 0 until cols && cy < rows && (cy < 0 || board[cy][cx] == 0)
    }

    fun move(dx: Int) {
        if (phase == GamePhase.Playing && fits(cells, px + dx, py)) px += dx
    }

    fun rotate() {
        if (phase != GamePhase.Playing || kind == SQUARE_PIECE) return
        val turned = cells.map { (x, y) -> -y to x }
        // Nudge sideways if the turn would clip a wall or another block.
        for (kick in listOf(0, -1, 1, -2, 2)) {
            if (fits(turned, px + kick, py)) {
                cells = turned
                px += kick
                return
            }
        }
    }

    fun softDrop() {
        if (phase != GamePhase.Playing) return
        if (fits(cells, px, py + 1)) {
            py++
            score += 1
        } else {
            lock()
        }
    }

    fun hardDrop() {
        if (phase != GamePhase.Playing) return
        var rowsDropped = 0
        while (fits(cells, px, py + 1)) {
            py++
            rowsDropped++
        }
        score += rowsDropped * 2
        lock()
    }

    fun ghostY(): Int {
        var y = py
        while (fits(cells, px, y + 1)) y++
        return y
    }

    fun step(dt: Float) {
        if (phase != GamePhase.Playing) return
        fall += dt
        val interval = (0.8f - (level - 1) * 0.07f).coerceAtLeast(0.08f)
        if (fall >= interval) {
            fall = 0f
            if (fits(cells, px, py + 1)) py++ else lock()
        }
    }

    private fun spawn() {
        kind = nextKind
        nextKind = Random.nextInt(PIECES.size)
        cells = PIECES[kind]
        px = 4
        py = 1
        if (!fits(cells, px, py)) phase = GamePhase.GameOver
    }

    private fun lock() {
        for ((dx, dy) in cells) {
            val x = px + dx
            val y = py + dy
            if (y < 0) {
                phase = GamePhase.GameOver
                return
            }
            board[y][x] = kind + 1
        }
        var cleared = 0
        var row = rows - 1
        while (row >= 0) {
            if (board[row].all { it != 0 }) {
                for (r in row downTo 1) board[r] = board[r - 1].copyOf()
                board[0] = IntArray(cols)
                cleared++
            } else {
                row--
            }
        }
        score += LINE_SCORES[cleared] * level
        lines += cleared
        level = 1 + lines / 10
        fall = 0f
        spawn()
    }
}

@Composable
fun BlocksGame(best: Int, onGameOver: (Int) -> Unit) {
    val state = remember { BlocksState() }
    val frame = rememberGameClock(state::step)
    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.GameOver) onGameOver(state.score)
    }
    GameScaffold(
        title = stringResource(R.string.dial_game_blocks),
        score = state.score,
        best = best,
        hint = stringResource(R.string.dial_game_blocks_hint, state.level),
        message = phaseMessage(state.phase, state.score),
        frame = frame,
        // Ten-wide board plus a preview column.
        playAreaAspect = 16f / 20f,
        onKey = { event ->
            if (event.type == KeyEventType.KeyDown) {
                when {
                    event.isConfirm() ->
                        if (state.phase == GamePhase.Playing) state.hardDrop() else state.start()
                    event.key == Key.DirectionLeft -> state.move(-1)
                    event.key == Key.DirectionRight -> state.move(1)
                    event.key == Key.DirectionUp -> state.rotate()
                    event.key == Key.DirectionDown -> state.softDrop()
                    else -> Unit
                }
            }
            event.isGameKey()
        },
    ) {
        val cell = min(size.height / state.rows, size.width / (state.cols + 6))
        val left = (size.width - cell * (state.cols + 6)) / 2f + cell * 0.5f
        val top = (size.height - cell * state.rows) / 2f
        drawRect(
            TvColors.Focus.copy(alpha = 0.35f),
            Offset(left, top),
            Size(cell * state.cols, cell * state.rows),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        for (y in 0 until state.rows) {
            for (x in 0 until state.cols) {
                val value = state.board[y][x]
                if (value != 0) drawGlowCell(BLOCK_COLORS[value - 1], Offset(left + x * cell, top + y * cell), cell)
            }
        }
        if (state.phase == GamePhase.Playing) {
            val color = BLOCK_COLORS[state.kind]
            val ghost = state.ghostY()
            state.cells.forEach { (dx, dy) ->
                val y = ghost + dy
                if (y >= 0) {
                    drawRect(
                        color.copy(alpha = 0.45f),
                        Offset(left + (state.px + dx) * cell + cell * 0.1f, top + y * cell + cell * 0.1f),
                        Size(cell * 0.8f, cell * 0.8f),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            state.cells.forEach { (dx, dy) ->
                val y = state.py + dy
                if (y >= 0) drawGlowCell(color, Offset(left + (state.px + dx) * cell, top + y * cell), cell)
            }
        }
        // Next piece preview.
        val previewLeft = left + cell * (state.cols + 1.5f)
        drawRect(
            TvColors.Accent.copy(alpha = 0.5f),
            Offset(previewLeft - cell * 0.5f, top + cell * 0.5f),
            Size(cell * 4.5f, cell * 4f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        if (state.phase != GamePhase.Ready) {
            PIECES[state.nextKind].forEach { (dx, dy) ->
                drawGlowCell(
                    BLOCK_COLORS[state.nextKind],
                    Offset(previewLeft + (dx + 1) * cell * 0.9f, top + (dy + 2.2f) * cell * 0.9f + cell * 0.4f),
                    cell * 0.9f,
                )
            }
        }
    }
}

/* -------------------------------------------------------------------------------------------------
 * Sky Hop: an endless vertical jumper
 * ---------------------------------------------------------------------------------------------- */

private const val WORLD_W = 100f
private const val WORLD_H = 160f
private const val GRAVITY = -220f
private const val JUMP_SPEED = 150f
private const val RUN_SPEED = 72f
private const val PLATFORM_W = 18f
private const val HOPPER_SIZE = 8f

private class Platform(var x: Float, val y: Float, val moving: Boolean, var direction: Float)

private class HopState {
    var x = WORLD_W / 2f
    var y = 12f
    var vx = 0f
    var vy = 0f
    var camera = 0f
    var facingRight = true
    var holdLeft = false
    var holdRight = false
    val platforms = mutableListOf<Platform>()
    var score by mutableIntStateOf(0)
    var phase by mutableStateOf(GamePhase.Ready)
    private var highest = 0f

    fun start() {
        platforms.clear()
        x = WORLD_W / 2f
        y = 8f
        vx = 0f
        vy = JUMP_SPEED
        camera = 0f
        score = 0
        platforms += Platform(WORLD_W / 2f - PLATFORM_W / 2f, 6f, moving = false, direction = 0f)
        highest = 6f
        addPlatforms()
        phase = GamePhase.Playing
    }

    fun step(dt: Float) {
        if (phase != GamePhase.Playing) return
        val target = when {
            holdLeft && !holdRight -> -RUN_SPEED
            holdRight && !holdLeft -> RUN_SPEED
            else -> 0f
        }
        vx += (target - vx) * min(1f, dt * 10f)
        if (vx > 1f) facingRight = true else if (vx < -1f) facingRight = false
        x += vx * dt
        // Leave one side of the screen and come back on the other.
        if (x < 0f) x += WORLD_W
        if (x >= WORLD_W) x -= WORLD_W

        platforms.forEach { p ->
            if (p.moving) {
                p.x += p.direction * 22f * dt
                if (p.x < 0f || p.x > WORLD_W - PLATFORM_W) p.direction = -p.direction
            }
        }

        val previousY = y
        vy += GRAVITY * dt
        y += vy * dt
        if (vy < 0f) {
            val landing = platforms.firstOrNull { p ->
                previousY >= p.y && y <= p.y && abs(x - (p.x + PLATFORM_W / 2f)) < PLATFORM_W / 2f + HOPPER_SIZE / 2f
            }
            if (landing != null) {
                y = landing.y
                vy = JUMP_SPEED
            }
        }
        score = max(score, (y / 2f).toInt())
        camera = max(camera, y - WORLD_H * 0.45f)
        platforms.removeAll { it.y < camera - 10f }
        addPlatforms()
        if (y < camera - 12f) phase = GamePhase.GameOver
    }

    /** Keeps the screen above the player filled; gaps widen and platforms start moving as you climb. */
    private fun addPlatforms() {
        while (highest < camera + WORLD_H + 30f) {
            val difficulty = min(1f, camera / 2500f)
            highest += 10f + difficulty * 24f + Random.nextFloat() * 12f
            val moving = camera > 250f && Random.nextFloat() < 0.35f * difficulty + 0.05f
            platforms += Platform(
                x = Random.nextFloat() * (WORLD_W - PLATFORM_W),
                y = highest,
                moving = moving,
                direction = if (Random.nextBoolean()) 1f else -1f,
            )
        }
    }
}

@Composable
fun SkyHopGame(best: Int, onGameOver: (Int) -> Unit) {
    val state = remember { HopState() }
    val frame = rememberGameClock(state::step)
    LaunchedEffect(state.phase) {
        if (state.phase == GamePhase.GameOver) onGameOver(state.score)
    }
    GameScaffold(
        title = stringResource(R.string.dial_game_skyhop),
        score = state.score,
        best = best,
        hint = stringResource(R.string.dial_game_skyhop_hint),
        message = phaseMessage(state.phase, state.score),
        frame = frame,
        playAreaAspect = WORLD_W / WORLD_H,
        onKey = { event ->
            val down = event.type == KeyEventType.KeyDown
            when {
                event.key == Key.DirectionLeft -> state.holdLeft = down
                event.key == Key.DirectionRight -> state.holdRight = down
                event.isConfirm() && down && state.phase != GamePhase.Playing -> state.start()
                else -> Unit
            }
            event.isGameKey()
        },
    ) {
        val scale = size.width / WORLD_W
        fun screenY(worldY: Float) = size.height - (worldY - state.camera) * scale
        // Scrolling horizon lines give a sense of climbing.
        val spacing = 20f
        var lineY = floor(state.camera / spacing) * spacing
        while (lineY < state.camera + WORLD_H) {
            val sy = screenY(lineY)
            drawLine(TvColors.Accent.copy(alpha = 0.12f), Offset(0f, sy), Offset(size.width, sy), strokeWidth = 1.dp.toPx())
            lineY += spacing
        }
        state.platforms.forEach { p ->
            val color = if (p.moving) TvColors.Accent else TvColors.Focus
            val top = screenY(p.y)
            drawRect(color.copy(alpha = 0.25f), Offset(p.x * scale - 2f, top - 3f), Size(PLATFORM_W * scale + 4f, 3f * scale + 6f))
            drawRect(color, Offset(p.x * scale, top), Size(PLATFORM_W * scale, 2.5f * scale))
        }
        if (state.phase != GamePhase.Ready) {
            val hopperSize = HOPPER_SIZE * scale
            val center = Offset(state.x * scale, screenY(state.y) - hopperSize * 0.85f)
            drawHopper(center, hopperSize, state.facingRight)
            // Draw a second copy while wrapping across an edge.
            if (state.x * scale < hopperSize) {
                drawHopper(center.copy(x = center.x + size.width), hopperSize, state.facingRight)
            }
            if (state.x * scale > size.width - hopperSize) {
                drawHopper(center.copy(x = center.x - size.width), hopperSize, state.facingRight)
            }
        }
    }
}
