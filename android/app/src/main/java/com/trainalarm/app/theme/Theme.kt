package com.trainalarm.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.trainalarm.app.R
import com.trainalarm.app.ThemeChoice

private fun color(hex: Int) = Color(0xFF000000.toInt() or hex)

/** The palette tokens as Compose colours. Orange (`accent`) and teal are fills only (spec section 5). */
class AppColors(val tokens: PaletteTokens) {
    private val t = tokens
    val bg = color(t.bg)
    val surface = color(t.surface)
    val surface2 = color(t.surface2)
    val text = color(t.text)
    val head = color(t.head)
    val muted = color(t.muted)
    val accent = color(t.accent)
    val onAccent = color(t.onAccent)
    val teal = color(t.teal)
    val onTeal = color(t.onTeal)
    val tealText = color(t.tealText)
    val bad = color(t.bad)
}

val LocalColors = staticCompositionLocalOf { AppColors(Palette.dark) }

val Anton = FontFamily(Font(R.font.anton_regular))

/** The dot-matrix face (Doto, weight 700). Times are drawn with `DotTime`, not plain `Text`. */
@OptIn(ExperimentalTextApi::class)
val Doto = FontFamily(
    Font(R.font.doto_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

@Composable
fun TrainAlarmTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
    }
    val colors = remember(dark) { AppColors(if (dark) Palette.dark else Palette.light) }
    val scheme = if (dark) {
        darkColorScheme(background = colors.bg, surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
            primary = colors.accent, onPrimary = colors.onAccent)
    } else {
        lightColorScheme(background = colors.bg, surface = colors.surface, onBackground = colors.text, onSurface = colors.text,
            primary = colors.accent, onPrimary = colors.onAccent)
    }
    CompositionLocalProvider(LocalColors provides colors) { MaterialTheme(colorScheme = scheme, content = content) }
}
