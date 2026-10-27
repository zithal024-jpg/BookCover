package com.bookcover.diagnostic.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CoverColorChoicesTest {
    private val cyan = 0xFF25A7B9.toInt()
    private val pink = 0xFFF8D7E3.toInt()
    private fun cover(id: String, seed: Int, title: String = "Book $id") = BookCoverColor(id, title, seed)

    @Test fun arbitraryArtworkColorsRemainExactSourcesBeyondPresets() {
        val colors = listOf(cyan, pink, 0xFF849BCA.toInt(), 0xFF617B25.toInt())
        val choices = coverColorChoices(colors.mapIndexed { index, seed -> cover("$index", seed) }, "automatic")
        assertEquals(colors.toSet(), choices.map { it.seed }.toSet())
        assertTrue(colors.none { seed -> LibraryThemeChoice.entries.any { it.seed == seed } })
        choices.forEach { choice ->
            assertEquals(choice.seed, coverColorSeed(choice.id))
            assertEquals(choice.id, savedLibraryTheme(choice.id))
        }
        // The recognisable source swatch is retained separately from the stronger, accessible accent.
        val pinkChoice = choices.single { it.seed == pink }
        assertNotEquals(pinkChoice.seed, vividAccent(pinkChoice.seed))
        assertEquals(pink, pinkChoice.seed)
    }

    @Test fun nearDuplicateCoversMergeRegardlessOfReadOrImportOrder() {
        val covers = listOf(cover("b", cyan), cover("a", 0xFF28AAB9.toInt()), cover("c", pink))
        val first = coverColorChoices(covers, "automatic")
        assertEquals(2, first.size)
        assertEquals(first, coverColorChoices(covers.reversed(), "automatic"))
        assertTrue(first.any { it.seed == pink })
    }

    @Test fun hueWrapMergesRedNeighborsButKeepsDifferentHuesAndNeutralSeparate() {
        val covers = listOf(cover("a", 0xFFE65058.toInt()), cover("b", 0xFFE65850.toInt()),
            cover("c", 0xFF50E658.toInt()), cover("d", 0xFFEEEEEE.toInt()), cover("e", pink))
        val choices = coverColorChoices(covers, "automatic")
        assertEquals(4, choices.size)
        assertTrue(choices.any { it.seed == pink })
        assertTrue(choices.any { it.seed == 0xFFEEEEEE.toInt() })
    }

    @Test fun similarityDoesNotChainAcrossTheColorWheel() {
        val red = 0xFFE65050.toInt()
        val orangeRed = 0xFFE66950.toInt()
        val orange = 0xFFE68250.toInt()
        // Adjacent pairs are less than 12 degrees apart, but endpoints are distinct choices.
        val choices = coverColorChoices(listOf(cover("a", red), cover("b", orangeRed), cover("c", orange)), "automatic")
        assertEquals(2, choices.size)
    }

    @Test fun selectedSnapshotSurvivesCoverReplacementRemovalAndNearDuplicateMerge() {
        val selected = coverColorThemeId(cyan)
        val replaced = listOf(cover("a", pink), cover("b", 0xFF28AAB9.toInt()))
        val choices = coverColorChoices(replaced, selected)
        assertEquals(2, choices.size)
        assertEquals(selected, choices.first().id)
        assertEquals(cyan, choices.first().seed)
        assertTrue(choices.first().label.startsWith("Selected cover colour"))
        assertEquals(listOf(choices.first()), coverColorChoices(emptyList(), selected))
        assertEquals(selected, savedLibraryTheme(selected))
    }

    @Test fun removingBooksPrunesUnselectedColorsWithoutGrowingHistory() {
        val covers = listOf(cover("a", cyan), cover("b", pink))
        assertEquals(2, coverColorChoices(covers, "automatic").size)
        assertEquals(listOf(pink), coverColorChoices(covers.drop(1), "automatic").map { it.seed })
        assertTrue(coverColorChoices(emptyList(), "automatic").isEmpty())
        assertTrue(coverColorChoices(emptyList(), "rose").isEmpty())
    }

    @Test fun preferenceRoundTripIsStrictAndKeepsEveryExistingPreset() {
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), cyan, pink, 0x00001234).forEach { seed ->
            val id = coverColorThemeId(seed)
            assertEquals(seed or 0xFF000000.toInt(), coverColorSeed(id))
            assertEquals(id, savedLibraryTheme(id))
        }
        listOf(null, "cover:", "cover:12345", "cover:1234567", "cover:gggggg", "cover:ABCDEF", "cover:123456\n", "other").forEach { bad ->
            assertNull(coverColorSeed(bad))
            assertEquals("automatic", savedLibraryTheme(bad))
        }
        LibraryThemeChoice.entries.forEach { assertEquals(it.id, savedLibraryTheme(it.id)) }
    }

    @Test fun explicitCoverOverrideIsIndependentOfAutomaticSourceAndFollowsDarkMode() {
        val selected = coverColorThemeId(cyan)
        for (dark in listOf(false, true)) {
            val fromPinkBook = libraryPalette(selected, pink, dark)
            assertEquals(dark, fromPinkBook.dark)
            assertEquals(fromPinkBook, libraryPalette(selected, 0xFF907020.toInt(), dark))
            assertEquals(libraryPalette("automatic", cyan, dark), fromPinkBook)
            assertEquals(fromPinkBook, readerControlsPalette(selected, pink, dark))
            assertNotEquals(fromPinkBook, libraryPalette("automatic", pink, dark))
        }
        assertNotEquals(libraryPalette(selected, pink, false), libraryPalette(selected, pink, true))
    }

    @Test fun paleAndMulticoloredArtworkStillSuppliesRecognisableHue() {
        val shades = listOf(0xFFF06090.toInt(), 0xFFE0507F.toInt(), 0xFFF070A0.toInt(), 0xFFD84878.toInt())
        val samples = shades.flatMap { shade -> List(110) { shade } } + List(160) { 0xFF763B0B.toInt() } +
            List(400) { 0xFFFFFFFF.toInt() }
        val sources = listOf(extractCoverSeed(samples.toIntArray()),
            extractCoverSeed(IntArray(1000) { if (it < 700) 0xFFFFFFFF.toInt() else pink }))
        sources.forEachIndexed { index, source ->
            val choice = coverColorChoices(listOf(cover("$index", source)), "automatic").single()
            assertEquals(source, choice.seed)
            val expectedHue = if (index == 0) 340.0 else hue(pink)
            for (dark in listOf(false, true)) {
                assertTrue(hueDistance(hue(libraryPalette(choice.id, cyan, dark).primary), expectedHue) <= 15.0)
            }
        }
    }

    @Test fun dynamicPalettesHaveReadableRolesForExtremeAndRandomSources() {
        val random = Random(23)
        val sources = listOf(cyan, pink, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt()) +
            List(256) { random.nextInt() or 0xFF000000.toInt() }
        for (source in sources) for (dark in listOf(false, true)) {
            val palette = libraryPalette(coverColorThemeId(source), pink, dark)
            listOf(palette.onBackground to palette.background, palette.onSurface to palette.surface,
                palette.onSurfaceVariant to palette.surfaceVariant, palette.onPrimary to palette.primary,
                palette.onPrimaryContainer to palette.primaryContainer, palette.primary to palette.background,
                palette.onSecondary to palette.secondary, palette.onSecondaryContainer to palette.secondaryContainer,
                palette.onTertiary to palette.tertiary, palette.onTertiaryContainer to palette.tertiaryContainer,
            ).forEach { (text, background) ->
                assertTrue("source=${Integer.toHexString(source)} dark=$dark", contrastRatio(text, background) >= 4.5)
            }
        }
    }

    @Test fun summariesAndLabelsIdentifySourceWithoutInventingNamedPresets() {
        val choice = coverColorChoices(listOf(cover("a", cyan, "New hue")), "automatic").single()
        assertEquals("New hue · #25A7B9", choice.label)
        assertEquals("Cover colour · #25A7B9", libraryThemeLabel(choice.id))
        assertEquals(LibraryThemeChoice.ROSE.label, libraryThemeLabel("rose"))
    }

    private fun hue(color: Int): Double {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        val high = max(r, max(g, b))
        val delta = (high - min(r, min(g, b))).toDouble()
        if (delta == 0.0) return 0.0
        return ((when (high) {
            r -> 60 * (((g - b) / delta) % 6)
            g -> 60 * ((b - r) / delta + 2)
            else -> 60 * ((r - g) / delta + 4)
        }) + 360) % 360
    }

    private fun hueDistance(a: Double, b: Double): Double = abs(a - b).let { min(it, 360 - it) }
}
