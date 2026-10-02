package com.m3u.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Price chart for the Markets side panel: candles when the source has real open/high/low/close,
 * otherwise a line, over a soft fill. Green when the range ends higher than it started.
 */
@Composable
fun PriceChart(
    candles: List<Candle>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        if (candles.size < 2) {
            Text(
                text = stringResource(
                    if (loading) R.string.dial_markets_chart_loading else R.string.dial_markets_chart_none
                ),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 13.sp,
            )
            return@Box
        }
        val rising = candles.last().close >= candles.first().open
        val lineColor = if (rising) TvColors.Positive else TvColors.Danger
        val hasOhlc = remember(candles) { candles.any { it.high != it.low } }
        val low = remember(candles) { candles.minOf { it.low } }
        val high = remember(candles) { candles.maxOf { it.high } }
        Canvas(Modifier.matchParentSize()) {
            val span = (high - low).takeIf { it > 0 } ?: 1.0
            fun y(price: Double) = (size.height * (1 - (price - low) / span)).toFloat()
            val step = size.width / candles.size
            if (hasOhlc && candles.size <= MAX_CANDLES) {
                val body = (step * 0.6f).coerceAtLeast(1f)
                candles.forEachIndexed { index, candle ->
                    val x = step * index + step / 2
                    val color = if (candle.close >= candle.open) TvColors.Positive else TvColors.Danger
                    drawLine(color, Offset(x, y(candle.high)), Offset(x, y(candle.low)), strokeWidth = 1.5f)
                    val top = y(maxOf(candle.open, candle.close))
                    val bottom = y(minOf(candle.open, candle.close))
                    drawRect(color, Offset(x - body / 2, top), Size(body, (bottom - top).coerceAtLeast(1.5f)))
                }
            } else {
                val line = Path()
                val fill = Path()
                candles.forEachIndexed { index, candle ->
                    val x = step * index + step / 2
                    val point = Offset(x, y(candle.close))
                    if (index == 0) {
                        line.moveTo(point.x, point.y)
                        fill.moveTo(point.x, size.height)
                        fill.lineTo(point.x, point.y)
                    } else {
                        line.lineTo(point.x, point.y)
                        fill.lineTo(point.x, point.y)
                    }
                }
                fill.lineTo(step * (candles.size - 1) + step / 2, size.height)
                fill.close()
                drawPath(
                    fill,
                    Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.35f), lineColor.copy(alpha = 0f))),
                )
                drawPath(line, lineColor, style = Stroke(width = 2.5f))
            }
        }
        Text(
            text = formatPrice(high),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Text(
            text = formatPrice(low),
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

private const val MAX_CANDLES = 120
