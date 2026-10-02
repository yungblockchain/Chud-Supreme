package com.m3u.tv

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlin.random.Random

/**
 * Blackjack and roulette played on the device. The rules follow a single deck and a
 * European wheel. No remote casino server is called.
 */

private val REDS = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)
private val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
private val SUITS = listOf("♠", "♥", "♦", "♣")

private data class Card(val rank: String, val suit: String) {
    val value: Int = when (rank) {
        "A" -> 11
        "K", "Q", "J" -> 10
        else -> rank.toInt()
    }
    val red: Boolean get() = suit == "♥" || suit == "♦"
    override fun toString(): String = "$rank$suit"
}

private fun handTotal(cards: List<Card>): Int {
    var total = cards.sumOf { it.value }
    var aces = cards.count { it.rank == "A" }
    while (total > 21 && aces > 0) {
        total -= 10
        aces -= 1
    }
    return total
}

@Composable
internal fun BlackjackGame(best: Int, onGameOver: (Int) -> Unit) {
    val shoe = remember { shuffledShoe() }
    var index by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf(listOf(shoe[0], shoe[2])) }
    var dealer by remember { mutableStateOf(listOf(shoe[1], shoe[3])) }
    var cursor by remember { mutableIntStateOf(4) }
    var bank by remember { mutableIntStateOf(100) }
    var peak by remember { mutableIntStateOf(best.coerceAtLeast(100)) }
    var message by remember { mutableStateOf("Hit or stand. Bet is 10.") }
    var done by remember { mutableStateOf(false) }
    val bob by rememberInfiniteTransition(label = "dealer").animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "bob",
    )

    fun deal(): Card {
        val card = shoe[cursor % shoe.size]
        cursor += 1
        if (cursor >= shoe.size - 1) {
            shoe.shuffle()
            cursor = 0
        }
        return card
    }

    fun finish(nextPlayer: List<Card>, nextDealer: List<Card>) {
        var house = nextDealer
        while (handTotal(house) < 17) house = house + deal()
        dealer = house
        val p = handTotal(nextPlayer)
        val d = handTotal(house)
        val win = when {
            p > 21 -> false
            d > 21 || p > d -> true
            p == d -> null
            else -> false
        }
        bank = when (win) {
            true -> bank + 10
            false -> bank - 10
            null -> bank
        }
        if (bank > peak) peak = bank
        onGameOver(peak)
        message = when {
            bank <= 0 -> "Broke. Press New."
            win == true -> "You win. Bank $bank"
            win == null -> "Push. Bank $bank"
            else -> "Dealer wins. Bank $bank"
        }
        done = true
        index += 1
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Image(
                painter = painterResource(R.drawable.brand_mascot),
                contentDescription = "Dealer",
                modifier = Modifier.size(150.dp).offset(y = bob.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Blackjack", color = TvColors.TextPrimary, fontFamily = TvFonts.Accent, fontSize = 28.sp)
                Text(message, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 16.sp)
                Text(
                    "Dealer ${if (done) handTotal(dealer) else "??"}   ·   You ${handTotal(player)}   ·   Bank $bank",
                    color = TvColors.Focus,
                    fontFamily = TvFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
            }
        }
        Text(
            "Dealer  " + dealer.mapIndexed { i, card -> if (!done && i == 1) "??" else card.toString() }.joinToString("  "),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 22.sp,
        )
        Text(
            "You  " + player.joinToString("  "),
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontSize = 22.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(text = "Hit", icon = Icons.Rounded.SportsEsports, onClick = {
                if (done || bank <= 0) return@TvActionButton
                val next = player + deal()
                player = next
                if (handTotal(next) > 21) finish(next, dealer)
            })
            TvActionButton(text = "Stand", icon = Icons.Rounded.SportsEsports, onClick = {
                if (!done && bank > 0) finish(player, dealer)
            })
            TvActionButton(text = "New", icon = Icons.Rounded.SportsEsports, onClick = {
                if (bank <= 0) bank = 100
                val a = deal(); val b = deal(); val c = deal(); val d = deal()
                player = listOf(a, c)
                dealer = listOf(b, d)
                done = false
                message = "Hit or stand. Bet is 10."
            })
        }
    }
}

@Composable
internal fun RouletteGame(best: Int, onGameOver: (Int) -> Unit) {
    var bank by remember { mutableIntStateOf(100) }
    var peak by remember { mutableIntStateOf(best.coerceAtLeast(100)) }
    var angle by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf("Pick red, black, even or odd, then spin. Bet is 10.") }

    fun spin(pick: String) {
        if (bank < 10) {
            message = "Broke. Bank reset to 100."
            bank = 100
        }
        val pocket = Random.nextInt(0, 37)
        angle += 720f + pocket * (360f / 37f)
        result = pocket
        val red = pocket in REDS
        val win = when (pick) {
            "red" -> red
            "black" -> pocket != 0 && !red
            "even" -> pocket != 0 && pocket % 2 == 0
            else -> pocket % 2 == 1
        }
        bank += if (win) 10 else -10
        if (bank > peak) peak = bank
        onGameOver(peak)
        val colour = when {
            pocket == 0 -> "green"
            red -> "red"
            else -> "black"
        }
        message = "$pocket $colour. " + if (win) "You win. Bank $bank" else "House wins. Bank $bank"
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 48.dp),
    ) {
        Text("Roulette", color = TvColors.TextPrimary, fontFamily = TvFonts.Accent, fontSize = 28.sp)
        Text(message, color = TvColors.TextSecondary, fontFamily = TvFonts.Body, fontSize = 16.sp)
        Canvas(
            Modifier
                .size(220.dp)
                .rotate(angle)
                .background(Color(0xFF101010))
        ) {
            val pocket = 360f / 37f
            repeat(37) { n ->
                val colour = when {
                    n == 0 -> Color(0xFF1B7A32)
                    n in REDS -> Color(0xFFB4232A)
                    else -> Color(0xFF1A1A1A)
                }
                drawArc(
                    colour,
                    startAngle = n * pocket - 90f,
                    sweepAngle = pocket - 0.4f,
                    useCenter = true,
                    size = Size(size.width, size.height),
                )
            }
            drawCircle(Color.White, radius = size.minDimension * 0.08f, center = center)
            drawCircle(TvColors.Focus, radius = size.minDimension * 0.45f, style = Stroke(4f))
        }
        Text(
            "Last number ${result ?: "—"}   ·   Bank $bank",
            color = TvColors.Focus,
            fontFamily = TvFonts.Body,
            fontSize = 18.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("red", "black", "even", "odd").forEach { pick ->
                TvActionButton(
                    text = pick.replaceFirstChar { it.uppercase() },
                    icon = Icons.Rounded.SportsEsports,
                    onClick = { spin(pick) },
                )
            }
        }
    }
}

private fun shuffledShoe(): MutableList<Card> =
    SUITS.flatMap { suit -> RANKS.map { rank -> Card(rank, suit) } }.shuffled().toMutableList()
