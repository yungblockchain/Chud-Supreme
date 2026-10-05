package com.m3u.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/* -------------------------------------------------------------------------------------------------
 * The ambient screensaver: after the menus have sat untouched for a while, big pictures of what's
 * trending fade in and out with the time in the corner. Any key brings the menus back.
 * ---------------------------------------------------------------------------------------------- */

@Immutable
data class AmbientSlide(val image: String, val title: String, val line: String?)

@Composable
fun AmbientScreensaver(slides: List<AmbientSlide>, modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())) }
    var holiday by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(slides.size) {
        while (true) {
            delay(SLIDE_MS)
            if (slides.isNotEmpty()) index = (index + 1) % slides.size
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            holiday = runCatching { holidayToday() }.getOrNull()
            delay(15_000L)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val slide = slides.getOrNull(index)
        Crossfade(targetState = slide, animationSpec = tween(1_400), label = "ambient") { current ->
            if (current != null) {
                AsyncImage(
                    model = current.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 64.dp, bottom = 48.dp),
        ) {
            Text(
                text = clock,
                color = Color.White,
                fontFamily = TvFonts.Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 64.sp,
            )
            holiday?.let { name ->
                Text(
                    text = name,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = TvFonts.Body,
                    fontSize = 18.sp,
                )
            }
            slide?.let {
                Text(
                    text = it.title,
                    color = Color.White.copy(alpha = 0.92f),
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                )
                it.line?.let { line ->
                    Text(
                        text = line,
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = TvFonts.Body,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

private const val SLIDE_MS = 12_000L
