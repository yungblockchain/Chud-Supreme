@file:OptIn(UnstableApi::class)

package com.m3u.data.service.internal

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment

/**
 * Brightness, contrast and colour for one channel group. Normal adds nothing, so the direct
 * picture (including HDR) stays as it is. The others only run when picture controls are on.
 * Auto measures the picture as it plays and lifts dark scenes (see [AutoBrightnessEffect]).
 */
internal object PicturePresets {
    const val NORMAL = "normal"
    const val CINEMA = "cinema"
    const val BRIGHT = "bright"
    const val SOFT = "soft"
    const val AUTO = "auto"

    val order = listOf(NORMAL, CINEMA, BRIGHT, SOFT, AUTO)

    fun next(current: String): String {
        val index = order.indexOf(current).coerceAtLeast(0)
        return order[(index + 1) % order.size]
    }

    fun forGroup(category: String?, stored: String): String {
        val key = groupKey(category)
        return decode(stored)[key] ?: NORMAL
    }

    fun groupKey(category: String?): String = category?.trim()?.take(80)?.ifBlank { null } ?: "Ungrouped"

    fun decode(raw: String): Map<String, String> = raw.lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val name = line.substring(tab + 1).takeIf { it in order && it != NORMAL } ?: return@mapNotNull null
        line.substring(0, tab) to name
    }.toMap()

    fun encode(presets: Map<String, String>): String = presets.entries
        .filter { it.value in order && it.value != NORMAL }
        .joinToString("\n") { "${it.key}\t${it.value}" }

    fun effects(name: String): List<Effect> = when (name) {
        CINEMA -> listOf(
            Contrast(0.12f),
            rgb(0.92f, 0.90f, 0.96f),
        )
        BRIGHT -> listOf(rgb(1.12f, 1.12f, 1.12f))
        SOFT -> listOf(
            Contrast(-0.18f),
            rgb(0.94f, 0.94f, 0.94f),
        )
        AUTO -> listOf(AutoBrightnessEffect())
        else -> emptyList()
    }

    private fun rgb(red: Float, green: Float, blue: Float): RgbAdjustment =
        RgbAdjustment.Builder().setRedScale(red).setGreenScale(green).setBlueScale(blue).build()
}
