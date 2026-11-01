package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.library.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RelicCoverPaletteTest {
    @Test fun standaloneTextAndMatteEdgeContrastAcrossEveryFamilyAndColorMode() {
        val random = Random(614)
        val colors = listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff777777.toInt(),
            0xfff2cbd6.toInt(), 0xff102a4a.toInt(), 0xffffe600.toInt()) + List(100) { random.nextInt() or (0xff shl 24) }
        for (cover in colors) for (mode in RelicColorMode.entries) for (set in RelicSet.entries) {
            val result = relicCoverPalette(IntArray(32) { cover }, IntArray(24) { cover }, cover, mode, cover, set)
            assertTrue("Standalone text lacks contrast for $set / $mode", contrastRatio(result.plateText, result.plate) >= 4.5 - 1e-6)
            assertTrue("Edge lacks contrast for $mode", contrastRatio(result.ink, result.background) >= 3.0 - 1e-6)
            assertEquals(255, result.plate ushr 24)
            assertEquals(255, result.plateText ushr 24)
        }
    }

    @Test fun originalSetPalettesAreDistinctWhileAutomaticKeepsMatchingTheSameCover() {
        val global = IntArray(32) { 0xff718c53.toInt() }
        val local = IntArray(32) { 0xffeee9dc.toInt() }
        val originals = RelicSet.entries.map { relicCoverPalette(global, local, 0, RelicColorMode.SET, 0, it) }
        assertEquals(3, originals.map { it.ink }.toSet().size)
        assertEquals(3, originals.map { it.plate }.toSet().size)
        val automatic = RelicSet.entries.map { relicCoverPalette(global, local, 0, RelicColorMode.AUTO, 0, it) }
        assertEquals("Family shape must not impose its original hue on adaptive cover colors", 1, automatic.toSet().size)
    }

    @Test fun automaticMonochromeCoversStayNeutral() {
        for (grey in listOf(0, 24, 90, 127, 200, 255)) {
            val color = (0xff shl 24) or (grey shl 16) or (grey shl 8) or grey
            val result = relicCoverPalette(IntArray(16) { color }, IntArray(16) { color }, color, RelicColorMode.AUTO, 0)
            val channels = listOf(result.ink ushr 16 and 255, result.ink ushr 8 and 255, result.ink and 255)
            assertTrue(channels.max() - channels.min() <= 1)
        }
    }

    @Test fun busyAutomaticEdgesUseNeutralKeylineWithoutChangingSourceSamples() {
        val global = intArrayOf(0xff184c72.toInt(), 0xff184c72.toInt())
        val local = IntArray(128) { if (it % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        val original = local.copyOf()
        val result = relicCoverPalette(global, local, 0, RelicColorMode.AUTO, 0)
        assertTrue(local.contentEquals(original))
        for (pixel in local) assertTrue(maxOf(contrastRatio(result.ink, pixel), contrastRatio(result.keyline, pixel)) >= 4.5)
    }

    @Test fun transparencyAndEmptySamplesHaveOpaqueReadableFallbacks() {
        val transparent = intArrayOf(0x00112233, 0x55123456)
        val result = relicCoverPalette(transparent, transparent, 0x00123456, RelicColorMode.AUTO, 0x00334455)
        assertEquals(255, result.background ushr 24)
        assertTrue(contrastRatio(result.plateText, result.plate) >= 4.5)
    }
}
