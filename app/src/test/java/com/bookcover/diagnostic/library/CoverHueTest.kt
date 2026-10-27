package com.bookcover.diagnostic.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test

/** BC-21 follow-up: any cover colour, pink included, comes through; more colour themes to pick from. */
class CoverHueTest {
    private val pinkCovers = mapOf(
        "hot pink" to 0xFFFF69B4.toInt(), "light pink" to 0xFFFFB6C1.toInt(),
        "pale pink" to 0xFFF8D7E3.toInt(), "blush" to 0xFFF9E1EA.toInt(), "dusty rose" to 0xFFC08081.toInt(),
    )

    @Test fun paleAndBrightPinkCoversAreCapturedOverWhiteMargins() {
        val white = 0xFFFFFFFF.toInt()
        pinkCovers.forEach { (name, pink) ->
            val samples = IntArray(1000) { if (it < 700) white else pink }
            val seed = extractCoverSeed(samples)
            assertTrue("$name picked ${Integer.toHexString(seed)}", hueDistance(hue(seed), hue(pink)) <= 10.0 && saturation(seed) > 0.05)
        }
    }

    @Test fun pinkSpreadOverManyShadesIsNotOutvotedByOneBrownPatch() {
        // Colour counts measured from the owner's pink manga cover, which picked brown on the phone.
        val shades = listOf(0xFFE51B24.toInt() to 140, 0xFF763B0B.toInt() to 150, 0xFFEC2C4D.toInt() to 134,
            0xFFEB99A7.toInt() to 91, 0xFFDF3655.toInt() to 57)
        val samples = shades.flatMap { (color, n) -> List(n) { color } } + List(2304 - shades.sumOf { it.second }) { 0xFFF0F0F0.toInt() }
        val seed = extractCoverSeed(samples.toIntArray())
        assertTrue("picked ${Integer.toHexString(seed)}", hueDistance(hue(seed), 352.0) <= 15.0)

        val fragmentedPink = listOf(0xFFF06090.toInt(), 0xFFE0507F.toInt(), 0xFFF070A0.toInt(), 0xFFD84878.toInt())
            .flatMap { pink -> List(110) { pink } } + List(160) { 0xFF763B0B.toInt() } + List(400) { 0xFFFFFFFF.toInt() }
        val pick = extractCoverSeed(fragmentedPink.toIntArray())
        assertTrue("picked ${Integer.toHexString(pick)}", hueDistance(hue(pick), 340.0) <= 15.0)
    }

    @Test fun automaticAccentKeepsTheCoverHueAndIsClearlyColoured() {
        val covers = pinkCovers.values + listOf(0xFF2858B8.toInt(), 0xFF287452.toInt(), 0xFFE8D9A0.toInt(), 0xFF6A4C93.toInt())
        for (cover in covers) for (dark in listOf(false, true)) {
            val p = libraryPalette("automatic", cover, systemDark = dark)
            val label = "cover=${Integer.toHexString(cover)} dark=$dark"
            assertTrue("$label primary hue", hueDistance(hue(p.primary), hue(cover)) <= 15.0)
            // Dark mode lightens the accent for contrast, so it is paler there by design.
            assertTrue("$label primary too grey: ${saturation(p.primary)}", saturation(p.primary) >= if (dark) 0.20 else 0.30)
            assertTrue("$label container hue", hueDistance(hue(p.primaryContainer), hue(cover)) <= 15.0)
        }
    }

    @Test fun greyCoversStayNeutral() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt(), 0xFF7A7876.toInt()).forEach { grey ->
            for (dark in listOf(false, true)) {
                assertTrue(saturation(libraryPalette("automatic", grey, systemDark = dark).primary) < 0.10)
            }
        }
    }

    @Test fun colourThemesCoverTheWholeWheelIncludingPink() {
        val fixed = LibraryThemeChoice.entries.mapNotNull { it.seed }
        for (wanted in 0 until 360 step 10) {
            assertTrue("no theme near hue $wanted", fixed.any { saturation(it) >= 0.3 && hueDistance(hue(it), wanted.toDouble()) <= 30.0 })
        }
        val rose = LibraryThemeChoice.fromId("rose")
        assertEquals("rose", rose.id)
        assertTrue(hueDistance(hue(rose.seed!!), 330.0) <= 15.0)
    }

    @Test fun newColourThemesFollowDarkModeWhileTheOriginalFourKeepTheirs() {
        val original = setOf("automatic", "paper", "forest", "midnight", "charcoal")
        val colours = LibraryThemeChoice.entries.filter { it.id !in original }
        assertTrue("expected at least 8 new colour themes", colours.size >= 8)
        colours.forEach { choice ->
            assertNull(choice.id, choice.dark)
            assertFalse(choice.id, choice.isDark(systemDark = false))
            assertTrue(choice.id, choice.isDark(systemDark = true))
            assertEquals(choice.id, savedLibraryTheme(choice.id))
        }
        assertEquals(LibraryThemeChoice.entries.size, LibraryThemeChoice.entries.map { it.id }.toSet().size)
    }

    private fun channels(color: Int) = Triple(color ushr 16 and 255, color ushr 8 and 255, color and 255)

    private fun saturation(color: Int): Double {
        val (r, g, b) = channels(color)
        val high = max(r, max(g, b))
        return if (high == 0) 0.0 else (high - min(r, min(g, b))) / high.toDouble()
    }

    private fun hue(color: Int): Double {
        val (r, g, b) = channels(color)
        val high = max(r, max(g, b))
        val delta = (high - min(r, min(g, b))).toDouble()
        if (delta == 0.0) return 0.0
        val h = when (high) {
            r -> 60 * (((g - b) / delta) % 6)
            g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }
        return (h + 360) % 360
    }

    private fun hueDistance(a: Double, b: Double): Double = abs(a - b).let { min(it, 360 - it) }
}
