package com.m3u.tv

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** One full turn every sixteen seconds. Slow enough to read as a sign, not a spinner. */
private const val SECONDS_PER_TURN = 16f

/** Orange slices behind the face. They spread apart as the coin turns edge-on, which is the thickness. */
private const val COIN_SLICES = 8
private val CoinEdge = Color(0xFFE23B12)

/**
 * The Chud Supreme badge turning slowly around its vertical axis, like a thick coin,
 * with a steady cyan glow behind it that doesn't turn.
 *
 * - Only the draw layer changes each frame (no recomposition), so it costs the Firestick very little.
 * - [spinning] is false while something covers it (the player, a details page, the launch
 *   screen); the badge then holds its angle and carries on from there when it's visible again.
 * - If the device's "Remove animations" accessibility setting is on, the badge stays still.
 */
@Composable
fun SpinningBrandLogo(
    spinning: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    val context = LocalContext.current
    val motionAllowed = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(spinning, motionAllowed) {
        if (!spinning || !motionAllowed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                angle = (angle + seconds * 360f / SECONDS_PER_TURN) % 360f
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(TvColors.Focus.copy(alpha = 0.32f), Color.Transparent),
                        center = center,
                        radius = this.size.minDimension * 0.62f,
                    ),
                    radius = this.size.minDimension * 0.62f,
                )
            }
    ) {
        repeat(COIN_SLICES) { slice ->
            Box(
                Modifier
                    .fillMaxSize(0.9f)
                    .graphicsLayer {
                        val spread = sin(Math.toRadians(angle.toDouble())).toFloat()
                        rotationY = angle
                        // Parent-space shift. Face-on the slices stack; edge-on they open into a rim.
                        translationX = (slice - (COIN_SLICES - 1) / 2f) * 1.15f.dp.toPx() * spread
                        cameraDistance = 8f * density
                        shape = CircleShape
                        clip = true
                    }
                    .background(CoinEdge)
            )
        }
        Image(
            painter = painterResource(R.drawable.brand_mascot),
            // Decorative: the launcher already announces the app name.
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = angle
                    // Closer than a flat billboard so the turn has real perspective.
                    cameraDistance = 8f * density
                    shadowElevation = 10.dp.toPx()
                    shape = CircleShape
                    clip = true
                }
        )
    }
}
