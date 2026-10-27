package com.bookcover.diagnostic.library

import org.junit.Assert.*
import org.junit.Test

/** BC-21: Automatic follows the system light/dark setting; chosen themes and cover accents stay put. */
class LibraryThemeModeTest {
    private val red = 0xFFB82828.toInt()

    @Test fun automaticFollowsSystemLightAndDark() {
        assertFalse(LibraryThemeChoice.AUTOMATIC.isDark(systemDark = false))
        assertTrue(LibraryThemeChoice.AUTOMATIC.isDark(systemDark = true))
        assertFalse(libraryPalette("automatic", red, systemDark = false).dark)
        assertTrue(libraryPalette("automatic", red, systemDark = true).dark)
        assertTrue("Dark library needs a dark page",
            relativeLuminance(libraryPalette("automatic", red, systemDark = true).background) < 0.05)
    }

    @Test fun chosenThemesIgnoreTheSystemSetting() {
        mapOf("paper" to false, "forest" to false, "midnight" to true, "charcoal" to true).forEach { (id, dark) ->
            val choice = LibraryThemeChoice.fromId(id)
            assertEquals(id, dark, choice.isDark(systemDark = false))
            assertEquals(id, dark, choice.isDark(systemDark = true))
            assertEquals(id, libraryPalette(id, red, systemDark = false), libraryPalette(id, red, systemDark = true))
        }
    }

    @Test fun automaticKeepsTheCoverAccentInBothModes() {
        val blue = 0xFF2858B8.toInt()
        for (systemDark in listOf(false, true)) {
            assertNotEquals(libraryPalette("automatic", red, systemDark), libraryPalette("automatic", blue, systemDark))
        }
    }

    @Test fun savedThemeIsReadBackUnchangedAndOldValuesStillLoad() {
        listOf("automatic", "paper", "forest", "midnight", "charcoal").forEach { saved ->
            assertEquals(saved, savedLibraryTheme(saved))
        }
        LibraryThemeChoice.entries.forEach { assertEquals(it.id, savedLibraryTheme(it.id)) }
        assertEquals("automatic", savedLibraryTheme(null))
        assertEquals("automatic", savedLibraryTheme("no-such-theme"))
    }

    @Test fun readerControlsShareTheLibraryAccentAndFollowThePageColour() {
        for (id in LibraryThemeChoice.entries.map { it.id }) {
            val seed = LibraryThemeChoice.fromId(id).seed ?: red
            assertTrue(id, readerControlsPalette(id, red, night = true).dark)
            assertFalse(id, readerControlsPalette(id, red, night = false).dark)
            // Same accent as the library would use in that brightness.
            assertEquals(id, libraryPalette("automatic", seed, systemDark = true), readerControlsPalette(id, red, night = true))
            assertEquals(id, libraryPalette("automatic", seed, systemDark = false), readerControlsPalette(id, red, night = false))
        }
    }

    @Test fun everyModeKeepsReadableContrast() {
        val seeds = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF0000FF.toInt(), red)
        for (seed in seeds) for (choice in LibraryThemeChoice.entries) for (dark in listOf(false, true)) {
            listOf(libraryPalette(choice.id, seed, dark), readerControlsPalette(choice.id, seed, dark)).forEach { p ->
                listOf(p.onBackground to p.background, p.onSurfaceVariant to p.surfaceVariant,
                    p.onPrimary to p.primary, p.primary to p.background, p.onSecondaryContainer to p.secondaryContainer,
                ).forEach { (fg, bg) -> assertTrue("${choice.id} dark=$dark seed=$seed", contrastRatio(fg, bg) >= 4.5) }
            }
        }
    }
}
