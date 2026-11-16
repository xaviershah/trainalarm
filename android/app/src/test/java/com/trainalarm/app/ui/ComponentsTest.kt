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
    fun leadStepMovesOneMinuteAndClampsAtTheEnds() {
        val c = SettingsStore.LEAD_CHOICES
        assertEquals(16, stepLead(c, 15, up = true))
        assertEquals(14, stepLead(c, 15, up = false))
        assertEquals(30, stepLead(c, 30, up = true))
        assertEquals(1, stepLead(c, 1, up = false))
        assertEquals(2, stepLead(c, null, up = true))
        assertEquals(1, stepLead(c, null, up = false))
    }

    @Test
    fun minutesTextIsSingularForOne() {
        assertEquals("1 minute", minutesText(1))
        assertEquals("2 minutes", minutesText(2))
        assertEquals("30 minutes", minutesText(30))
    }

    @Test
    fun theValueLineNamesTheChosenMinuteOrSaysNoneIsChosen() {
        assertEquals("Not chosen", leadValueLine(null))
        assertEquals("1 minute before arrival", leadValueLine(1))
        assertEquals("7 minutes before arrival", leadValueLine(7))
    }

    @Test
    fun rulerLayoutSpacesEveryChoiceEvenlyBetweenTheInsets() {
        for (width in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = width, inset = 16f)
            assertEquals(16f, layout.x(0), 0.001f)
            assertEquals(width - 16f, layout.x(29), 0.001f)
            val expected = (width - 32f) / 29f
            for (i in 1 until 30) assertEquals(expected, layout.x(i) - layout.x(i - 1), 0.001f)
        }
    }

    @Test
    fun rulerLayoutFindsEveryChoiceFromItsOwnPosition() {
        // The real ruler is the screen width minus 18 dp of padding on each side.
        for (screen in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = screen - 36f, inset = 16f)
            for (i in 0 until 30) assertEquals("screen $screen, tick $i", i, layout.indexAt(layout.x(i)))
            for (i in 1 until 30) assertTrue(layout.x(i) > layout.x(i - 1))  // a tick at every choice, in order
            assertEquals(6, layout.indexAt(layout.x(6) + 3f))  // a little off a tick still picks it
            assertEquals(0, layout.indexAt(-50f))              // beyond either end clamps
            assertEquals(29, layout.indexAt(9999f))
            assertEquals(0, layout.indexAt(Float.NaN))
        }
    }

    @Test
    fun rulerLayoutKeepsAtLeastEightDpBetweenTicksOnNarrowPhones() {
        // Confirm sheet: the ruler is the screen width minus 18 dp of padding on each side (36).
        for (screen in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = screen - 36f, inset = 16f)
            assertTrue("confirm, screen $screen: ${layout.spacing}", layout.spacing >= 8f)
        }
        // Settings: the ruler sits in a card, so it loses 18 (screen) + 14 (card) on each side (64).
        // 320 wide is only an Android possibility; ~7.7 still separates the 1.5-wide ticks.
        for (screen in listOf(320f, 375f, 402f)) {
            val layout = RulerLayout(count = 30, width = screen - 64f, inset = 16f)
            val minimum = if (screen < 375f) 7.5f else 8f
            assertTrue("settings, screen $screen: ${layout.spacing}", layout.spacing >= minimum)
        }
    }

    @Test
    fun onlyMultiplesOfFiveAreMajorTicks() {
        assertEquals(listOf(5, 10, 15, 20, 25, 30), SettingsStore.LEAD_CHOICES.filter(RulerLayout::isMajor))
    }

    @Test
    fun talkBackProgressMapsToTheNearestMinuteAndClamps() {
        val c = SettingsStore.LEAD_CHOICES
        assertEquals(1, leadForProgress(c, 0f))
        assertEquals(30, leadForProgress(c, 29f))
        assertEquals(7, leadForProgress(c, 6.4f))
        assertEquals(1, leadForProgress(c, -3f))
        assertEquals(30, leadForProgress(c, 99f))
    }
}
