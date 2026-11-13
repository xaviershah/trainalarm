package com.trainalarm.app.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour tokens as 0xRRGGBB. Same names and values on iOS. See spec section 5. */
data class PaletteTokens(
    val bg: Int, val surface: Int, val surface2: Int, val text: Int, val head: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val teal: Int, val onTeal: Int, val tealText: Int, val bad: Int,
)

object Palette {
    val dark = PaletteTokens(
        bg = 0x201D1C, surface = 0x2C2724, surface2 = 0x3A322E, text = 0xCFC3B6, head = 0xF3ECE3, muted = 0xB7AA9C,
        accent = 0xF36509, onAccent = 0x201D1C, teal = 0x015D67, onTeal = 0xF3ECE3, tealText = 0x2FA3B1, bad = 0xFF8F7A,
    )
    val light = PaletteTokens(
        bg = 0xCFC3B6, surface = 0xE4DCD2, surface2 = 0xF1EBE3, text = 0x201D1C, head = 0x201D1C, muted = 0x6C584C,
        accent = 0xF36509, onAccent = 0x201D1C, teal = 0x015D67, onTeal = 0xF3ECE3, tealText = 0x015D67, bad = 0xA32C1B,
    )
}

/** WCAG 2.x contrast ratio between two 0xRRGGBB colours. */
object Contrast {
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(hex: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((hex shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
