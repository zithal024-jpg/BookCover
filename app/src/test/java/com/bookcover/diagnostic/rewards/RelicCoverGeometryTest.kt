package com.bookcover.diagnostic.rewards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RelicCoverGeometryTest {
    @Test fun inscriptionStripsActualControlsAndFormatCharactersButKeepsOrdinaryLetters() {
        assertEquals("My library", relicInscription("\n\t\u0000\u202E"))
        assertEquals("Frank’s shelf", relicInscription("Frank’s\n shelf\u202E"))
        assertEquals("control p Cc Cf", relicInscription("control p Cc Cf"))
        assertEquals(80, relicInscription("a".repeat(100)).length)
    }

    @Test fun undecoratedAndOverlayKeepOriginalFullFitBounds() {
        for ((w, h) in listOf(900 to 1800, 1800 to 900, 1 to 1, 0 to 0)) {
            for ((iw, ih) in listOf(600 to 900, 900 to 600, 1 to 10000, 10000 to 1)) {
                val plain = relicCoverLayout(w, h, iw, ih, RelicAppearance())
                val overlay = relicCoverLayout(w, h, iw, ih,
                    RelicAppearance(enabled = true, mode = RelicMode.OVERLAY, bookplate = RelicBookplate.STANDARD))
                assertEquals(plain.image, overlay.image)
                contained(plain.image, plain.viewport)
                contained(overlay.frame, overlay.image)
            }
        }
    }

    @Test fun marginFitsWholeImageAndKeepsPlateAndSealOutsideArtwork() {
        for ((w, h) in listOf(900 to 1800, 1800 to 900, 1 to 1, 0 to 900)) {
            for ((iw, ih) in listOf(600 to 900, 900 to 600, 1 to 10000, 10000 to 1, Int.MAX_VALUE to 1)) {
                val result = relicCoverLayout(w, h, iw, ih, RelicAppearance(enabled = true,
                    mode = RelicMode.MARGIN, bookplate = RelicBookplate.PRESTIGE, seal = MoonlitTier.MYTHIC))
                contained(result.frame, result.viewport); contained(result.image, result.frame)
                result.plate?.let { contained(it, result.frame); assertTrue(result.image.bottom <= it.top + .001f) }
                assertTrue(result.seal.right <= result.image.left + .001f)
                if (result.image.width > .001f && result.image.height > .001f) {
                    val difference = abs(result.image.width.toDouble() * ih - result.image.height.toDouble() * iw)
                    assertTrue("Full-image aspect ratio changed", difference <= maxOf(1.0, result.image.width.toDouble() * ih) * .001)
                }
            }
        }
    }

    @Test fun standaloneAndMissingArtworkHaveBoundedContent() {
        for (mode in RelicMode.entries) for ((w, h) in listOf(320 to 640, 640 to 320, 0 to 0)) {
            val result = relicCoverLayout(w, h, 0, 0,
                RelicAppearance(enabled = true, mode = mode, bookplate = RelicBookplate.STANDARD))
            contained(result.frame, result.viewport); contained(result.image, result.frame)
            result.plate?.let { contained(it, result.frame) }
        }
    }

    @Test fun narrowOutsideDisplayFramesTheBookWithVisibleRailsInsteadOfTheScreenExtremes() {
        for ((w, h) in listOf(1080 to 2520, 360 to 880, 720 to 1680)) {
            for (tier in MoonlitTier.entries.filter { it.ordinal >= MoonlitTier.RARE.ordinal }) {
                for (plate in RelicBookplate.entries) {
                    val result = relicCoverLayout(w, h, 600, 900, RelicAppearance(enabled = true,
                        mode = RelicMode.MARGIN, edgeTier = tier, bookplate = plate, seal = tier))
                    contained(result.frame, result.viewport)
                    contained(result.image, result.frame)
                    contained(result.seal, result.frame)
                    assertEquals(2f / 3f, result.image.width / result.image.height, .001f)
                    assertTrue("Left rail needs visible space beside the original art", result.image.left - result.frame.left >= w * .05f)
                    assertTrue("Right rail needs visible space beside the original art", result.frame.right - result.image.right >= w * .05f)
                    assertTrue("Corners belong near the book, not at the tall screen's top", result.image.top - result.frame.top <= w * .10f)
                    assertTrue("The frame remains a book-shaped composition", result.frame.height < result.frame.width * 1.9f)
                    assertTrue("Top decorations stay inside the safe viewport", result.frame.top > w * .045f)
                    assertTrue("Bottom decorations stay inside the safe viewport", result.frame.bottom < h - w * .045f)
                }
            }
        }
    }

    @Test fun unusualArtAspectDoesNotCollapseTheMarginFrameOrBookplate() {
        for ((iw, ih) in listOf(1 to 100_000, 100_000 to 1, 900 to 600)) {
            val result = relicCoverLayout(360, 880, iw, ih, RelicAppearance(enabled = true,
                mode = RelicMode.MARGIN, edgeTier = MoonlitTier.MYTHIC,
                bookplate = RelicBookplate.PRESTIGE, seal = MoonlitTier.MYTHIC))
            contained(result.frame, result.viewport)
            contained(result.image, result.frame)
            assertTrue("Extreme artwork must not shrink the ornaments to a hairline", result.frame.width > 360 * .7f)
            assertTrue(result.frame.height > 360 * .7f)
            assertTrue(result.plate!!.height > 360 * .1f)
            assertTrue(result.seal.width > 360 * .03f)
            assertTrue(result.seal.right < result.image.left)
        }
    }

    @Test fun motionNeedsEverySessionAndAccessibilityGate() {
        val mythic = RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC, motion = true)
        fun allowed(a: RelicAppearance = mythic, attached: Boolean = true, visible: Boolean = true,
            presentation: Boolean = true, system: Boolean = true, released: Boolean = false) =
            relicMotionAllowed(a, attached, visible, presentation, system, released)
        assertTrue(allowed())
        assertFalse(allowed(mythic.copy(enabled = false)))
        assertFalse(allowed(mythic.copy(edgeTier = MoonlitTier.LEGENDARY)))
        assertFalse(allowed(mythic.copy(motion = false)))
        assertFalse(allowed(attached = false)); assertFalse(allowed(visible = false))
        assertFalse(allowed(presentation = false)); assertFalse(allowed(system = false)); assertFalse(allowed(released = true))
    }

    private fun contained(inner: RelicRect, outer: RelicRect) {
        assertTrue(inner.left.isFinite() && inner.top.isFinite() && inner.right.isFinite() && inner.bottom.isFinite())
        assertTrue(inner.width >= 0f && inner.height >= 0f)
        assertTrue("$inner outside $outer", inner.left >= outer.left - .001f && inner.top >= outer.top - .001f &&
            inner.right <= outer.right + .001f && inner.bottom <= outer.bottom + .001f)
    }
}
