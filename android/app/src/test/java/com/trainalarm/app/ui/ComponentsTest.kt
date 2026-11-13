package com.trainalarm.app.ui

import com.trainalarm.app.SettingsStore
import com.trainalarm.app.theme.Contrast
import com.trainalarm.app.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsTest {
    @Test
    fun noPillStyleUsesOrangeOrTealAsTextAndPairsAreReadable() {
        for (t in listOf(Palette.dark, Palette.light)) {
            for (style in PillStyle.values()) {
                val (foreground, fill) = pillTokens(style, t)
                assertNotEquals("$style: orange is a fill only", t.accent, foreground)
                assertNotEquals("$style: teal is a fill only", t.teal, foreground)
                // A pill with no fill sits on a card, so it is read against the surface.
                val ratio = Contrast.ratio(foreground, fill ?: t.surface)
                assertTrue("$style contrast $ratio", ratio >= 4.5)
            }
        }
        assertEquals(Palette.dark.onTeal to Palette.dark.teal, pillTokens(PillStyle.ON_TEAL, Palette.dark))
    }

    @Test
    fun leadStepMovesOneChoiceAndClampsAtTheEnds() {
        val c = SettingsStore.LEAD_CHOICES
        assertEquals(20, stepLead(c, 15, up = true))
        assertEquals(10, stepLead(c, 15, up = false))
        assertEquals(30, stepLead(c, 30, up = true))
        assertEquals(5, stepLead(c, 5, up = false))
        assertEquals(10, stepLead(c, null, up = true))
        assertEquals(5, stepLead(c, null, up = false))
    }
}
