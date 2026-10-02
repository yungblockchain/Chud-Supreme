package com.m3u.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Six-second launch: green binary columns, then the logo spins once and the screen fades. */
@Composable
fun BrandSplash(onFinished: () -> Unit) {
    val spin = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val currentOnFinished by rememberUpdatedState(onFinished)
    val appName = stringResource(R.string.app_name)
    var shift by remember { mutableFloatStateOf(0f) }
    val columns = remember {
        List(36) {
            SplashColumn(
                x = it / 36f,
                speed = 40f + Random.nextFloat() * 90f,
                glyphs = List(28) { if (Random.nextBoolean()) "1" else "0" },
                phase = Random.nextFloat(),
            )
        }
    }

    LaunchedEffect(Unit) {
        spin.animateTo(360f, animationSpec = tween(durationMillis = 5_400, easing = LinearEasing))
        fade.animateTo(0f, animationSpec = tween(durationMillis = 600))
        currentOnFinished()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            shift += 1f
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .semantics { contentDescription = appName }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black)
            val cell = size.height / 28f
            columns.forEach { column ->
                column.glyphs.forEachIndexed { index, glyph ->
                    val y = ((index + column.phase * 28f + shift * column.speed / 40f) % 28f) * cell
                    val head = (y / size.height) > 0.82f
                    drawRect(
                        color = if (head) Color(0xFFB6FFC8) else Color(0xFF00C853).copy(alpha = 0.35f + (index % 5) * 0.08f),
                        topLeft = Offset(column.x * size.width, y),
                        size = androidx.compose.ui.geometry.Size(
                            if (glyph == "1") 7f else 4f,
                            12f,
                        ),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(
                painter = painterResource(R.drawable.brand_mascot),
                contentDescription = null,
                modifier = Modifier
                    .size(240.dp)
                    .graphicsLayer {
                        rotationY = spin.value
                        cameraDistance = 12f * density
                        shape = CircleShape
                        clip = true
                    }
            )
            Text(
                text = appName,
                color = Color(0xFFB6FFC8),
                fontFamily = TvFonts.Accent,
                fontSize = 42.sp,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.dial_brand_katakana),
                color = Color(0xFF00C853),
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                maxLines = 1,
            )
        }
    }
}

private data class SplashColumn(
    val x: Float,
    val speed: Float,
    val glyphs: List<String>,
    val phase: Float,
)
