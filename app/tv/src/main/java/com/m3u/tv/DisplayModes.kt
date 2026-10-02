package com.m3u.tv

import android.app.Activity
import android.view.Display

/*
 * Refresh rate for the menus: the fastest mode the Fire TV offers at the current resolution.
 * Compose draws animations at whatever rate the display runs, so this is what makes them 120 Hz
 * on a device and TV that can do 120 Hz (the Fire TV decides which modes it lists).
 */
internal object DisplayModes {

    /** The fastest mode at the current resolution, or null when there's nothing faster. */
    @Suppress("DEPRECATION")
    fun fastestMode(activity: Activity): Display.Mode? {
        val display = activity.windowManager.defaultDisplay ?: return null
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight
            }
            .maxByOrNull { it.refreshRate }
    }

    /** A mode at the current resolution of at least [hz], or null if the TV doesn't list one. */
    @Suppress("DEPRECATION")
    fun modeAtLeast(activity: Activity, hz: Float): Display.Mode? {
        val display = activity.windowManager.defaultDisplay ?: return null
        val current = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight &&
                    it.refreshRate >= hz
            }
            .minByOrNull { it.refreshRate }
    }

    /** Highest refresh rate at the current resolution, rounded, e.g. 60 or 120. */
    fun fastestRefreshRate(activity: Activity): Int? =
        fastestMode(activity)?.refreshRate?.let { Math.round(it) }

    /** Asks for the fastest mode (or the TV's own choice when [enabled] is off). */
    fun applyMenuMode(activity: Activity, enabled: Boolean) {
        val window = activity.window ?: return
        val target = if (enabled) fastestMode(activity)?.modeId ?: 0 else 0
        val params = window.attributes
        if (params.preferredDisplayModeId != target) {
            params.preferredDisplayModeId = target
            window.attributes = params
        }
    }
}
