package com.bookcover.diagnostic.library

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CoverPaletteTest {
    @Test fun transparentCoverFallsBackAndWhiteMarginsDoNotHideACommonArtworkColor() {
        assertEquals(DEFAULT_COVER_SEED, extractCoverSeed(intArrayOf(0x00FFFFFF, 0x10000000)))
        val green = 0xFF287452.toInt()
        val samples = IntArray(1000) { if (it < 850) 0xFFFFFFFF.toInt() else green }
        assertEquals(green, extractCoverSeed(samples))
        assertEquals(green, extractCoverSeed(samples.reversedArray()))
    }

    @Test fun tinyAccentDoesNotOverrideCommonCoverColorAndMeasuredShadeIsRetained() {
        val cover = 0xFF314152.toInt()
        val samples = IntArray(1000) { cover }.also { it[0] = 0xFFFF0000.toInt() }
        assertEquals(cover, extractCoverSeed(samples))
        assertEquals(0xFF233446.toInt(), extractCoverSeed(intArrayOf(0xFF223344.toInt(), 0xFF243648.toInt())))
    }

    @Test fun manualThemesIgnoreCoverAndAutomaticRespondsToIt() {
        val red = 0xFFB82828.toInt()
        val blue = 0xFF2858B8.toInt()
        LibraryThemeChoice.entries.filter { it != LibraryThemeChoice.AUTOMATIC }.forEach { choice ->
            assertEquals(libraryPalette(choice.id, red), libraryPalette(choice.id, blue))
        }
        assertNotEquals(libraryPalette("automatic", red), libraryPalette("automatic", blue))
        assertEquals(LibraryThemeChoice.AUTOMATIC, LibraryThemeChoice.fromId("unknown"))
    }

    @Test fun allSchemesKeepTextAndControlContrastAcrossExtremeAndRandomCoverColors() {
        val random = Random(614)
        val seeds = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF777777.toInt(), 0xFFFFFF00.toInt()) +
            List(250) { random.nextInt() or (0xFF shl 24) }
        for (seed in seeds) for (choice in LibraryThemeChoice.entries) {
            val p = libraryPalette(choice.id, seed)
            val textPairs = listOf(
                p.onBackground to p.background, p.onSurface to p.surface, p.onSurfaceVariant to p.surfaceVariant,
                p.onPrimary to p.primary, p.onPrimaryContainer to p.primaryContainer,
                p.onSecondary to p.secondary, p.onSecondaryContainer to p.secondaryContainer,
                p.onTertiary to p.tertiary, p.onTertiaryContainer to p.tertiaryContainer,
                p.onError to p.error, p.onErrorContainer to p.errorContainer,
                p.inverseOnSurface to p.inverseSurface, p.inversePrimary to p.inverseSurface,
                p.primary to p.background, p.secondary to p.background,
            )
            textPairs.forEach { (foreground, background) ->
                assertTrue("Text contrast ${choice.id} seed=$seed fg=$foreground bg=$background", contrastRatio(foreground, background) >= 4.5)
            }
            listOf(p.outline, p.outlineVariant).forEach { outline ->
                assertTrue("Border contrast ${choice.id}", contrastRatio(outline, p.surfaceVariant) >= 3.0)
                assertTrue("Background border contrast ${choice.id}", contrastRatio(outline, p.background) >= 3.0)
            }
            listOf(p.surfaceContainerLowest, p.surfaceContainerLow, p.surfaceContainer, p.surfaceContainerHigh, p.surfaceContainerHighest).forEach { surface ->
                assertTrue("Container text contrast ${choice.id}", contrastRatio(p.onSurface, surface) >= 4.5)
            }
        }
    }
}
