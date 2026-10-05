package com.m3u.tv

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A Back press that was held: its release must not count as another Back. */
    private var backHeld = false

    /** Game controller buttons arrive as the remote keys they stand for (see [Gamepad]). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val translated = Gamepad.translate(event)
        val key = translated ?: event
        if (key.keyCode == KeyEvent.KEYCODE_BACK) {
            if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount >= 1) backHeld = true
            if (key.action == KeyEvent.ACTION_UP && backHeld) {
                backHeld = false
                return true
            }
        }
        // A translated button is used up either way, so the system's own fallback for that
        // controller button (some fall back to OK) never fires as well.
        if (translated != null) {
            super.dispatchKeyEvent(translated)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialDestination = tvDestinationFromExtra(intent?.getStringExtra(EXTRA_DESTINATION))
        // The emulator walkthrough: no bundled provider login, so the test server is all it sees.
        if (intent?.getBooleanExtra(EXTRA_NO_BUNDLED_LOGIN, false) == true) {
            getSharedPreferences(XtreamAccountViewModel.SESSION_PREFS, MODE_PRIVATE).edit()
                .putBoolean(XtreamAccountViewModel.KEY_SKIP_BUNDLED, true)
                .apply()
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = TvColors.Focus,
                    onPrimary = TvColors.OnFocus,
                    secondary = TvColors.Accent,
                    background = TvColors.Background,
                    onBackground = TvColors.TextPrimary,
                    surface = TvColors.Surface,
                    onSurface = TvColors.TextPrimary,
                    surfaceVariant = TvColors.SurfaceRaised,
                    onSurfaceVariant = TvColors.TextSecondary
                )
            ) {
                // The skin's text-and-spacing size scales everything through the density.
                val density = LocalDensity.current
                val scale = TvShapes.scale
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density * scale, density.fontScale)
                ) {
                    Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                        App(initialDestination = initialDestination)
                    }
                }
            }
        }
    }
}
