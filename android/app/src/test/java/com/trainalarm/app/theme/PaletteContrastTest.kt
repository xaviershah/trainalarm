package com.trainalarm.app.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun assertReadable(name: String, p: PaletteTokens) {
        val pairs = listOf(
            "text on bg" to (p.text to p.bg), "text on surface" to (p.text to p.surface),
            "head on surface" to (p.head to p.surface), "muted on surface" to (p.muted to p.surface),
            "onAccent on accent" to (p.onAccent to p.accent), "onTeal on teal" to (p.onTeal to p.teal),
            "tealText on surface" to (p.tealText to p.surface), "bad on surface" to (p.bad to p.surface),
        )
        for ((label, pair) in pairs) {
            val ratio = Contrast.ratio(pair.first, pair.second)
            assertTrue("$name: $label is $ratio", ratio >= 4.5)
        }
    }

    @Test fun darkThemeMeetsAA() = assertReadable("dark", Palette.dark)
    @Test fun lightThemeMeetsAA() = assertReadable("light", Palette.light)

    @Test fun ratioMatchesKnownValues() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFF, 0x000000), 0.01)
        assertEquals(9.68, Contrast.ratio(0xCFC3B6, 0x201D1C), 0.05)
    }

    @Test fun orangeAndTealAreNotUsableAsTextOnTheWrongGround() {
        assertTrue(Contrast.ratio(0x015D67, 0x201D1C) < 3.0)
        assertTrue(Contrast.ratio(0xF36509, 0xCFC3B6) < 3.0)
    }
}
