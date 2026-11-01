package com.bookcover.diagnostic.rewards

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.cover.CoverArtwork
import com.bookcover.diagnostic.testing.RelicRenderTestActivity
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native rendering on generated pixels only, in the isolated QA package. */
@RunWith(AndroidJUnit4::class)
class RelicCoverViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<RelicRenderTestActivity>? = null
    private lateinit var host: RelicRenderTestActivity
    private lateinit var view: RelicCoverView
    private lateinit var source: Bitmap

    @After fun close() {
        if (::view.isInitialized) instrumentation.runOnMainSync { view.release() }
        scenario?.close()
        if (::source.isInitialized) source.recycle()
    }

    private fun launch() {
        check(instrumentation.targetContext.packageName == "com.bookcover.diagnostic.qa")
        scenario = ActivityScenario.launch(RelicRenderTestActivity::class.java).also {
            it.onActivity { activity -> host = activity }
        }
        source = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this); val paint = Paint()
            for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
                paint.color = color
                val x = (index % 2) * 120f; val y = (index / 2) * 180f
                canvas.drawRect(x, y, x + 120, y + 180, paint)
            }
        }
        instrumentation.runOnMainSync {
            view = host.coverViewForTests
        }
        await { view.width > 0 && view.height > 0 && view.isAttachedToWindow }
    }

    private fun bind(appearance: RelicAppearance, artwork: CoverArtwork? = CoverArtwork.from(source)) {
        instrumentation.runOnMainSync { view.bind(artwork, appearance, "Generated Moonlit Fixture", "BookCover QA") }
        instrumentation.waitForIdleSync()
    }

    private fun narrowViewport() {
        instrumentation.runOnMainSync {
            view.layoutParams = view.layoutParams.apply { width = 360; height = 880 }
        }
        await { view.width == 360 && view.height == 880 }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun snapshot(name: String): Bitmap {
        lateinit var rendered: Bitmap
        instrumentation.runOnMainSync {
            rendered = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(rendered))
        }
        saveImage(name, rendered)
        return rendered
    }

    private fun saveImage(name: String, bitmap: Bitmap) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
            File(it, "$name.png").outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        }
    }

    @Test fun marginKeepsAllFourImageCornersAndNeverChangesSourcePixels() {
        launch()
        val before = pixels(source)
        val artwork = CoverArtwork.from(source)
        val appearance = RelicAppearance(enabled = true, mode = RelicMode.MARGIN, edgeTier = MoonlitTier.LEGENDARY,
            bookplate = RelicBookplate.STANDARD, seal = MoonlitTier.COMMON, motion = false)
        bind(appearance, artwork)
        val preparations = view.preparationCountForTests
        bind(appearance, artwork)
        assertEquals("Unchanged binds reuse the prepared cover", preparations, view.preparationCountForTests)
        val rect = view.imageBoundsForTests
        assertEquals(source.width.toFloat() / source.height, rect.width() / rect.height(), .001f)
        snapshot("moonlit-margin-generated").apply {
            try {
                listOf(.1f to .1f, .9f to .1f, .1f to .9f, .9f to .9f).forEach { (x, y) ->
                    assertEquals("The entire original image remains untinted inside its margin",
                        source.getPixel((x * source.width).toInt(), (y * source.height).toInt()),
                        getPixel((rect.left + x * rect.width()).toInt(), (rect.top + y * rect.height()).toInt()))
                }
            } finally { recycle() }
        }
        assertArrayEquals(before, pixels(source))
        assertFalse(view.motionRunningForTests)
    }

    @Test fun overlayPreservesImageFitAndStandaloneWorksWithoutArtwork() {
        launch()
        val before = pixels(source)
        bind(RelicAppearance())
        val originalFit = view.imageBoundsForTests
        bind(RelicAppearance(enabled = true, mode = RelicMode.OVERLAY, edgeTier = MoonlitTier.EPIC,
            bookplate = RelicBookplate.STANDARD, motion = false))
        assertEquals(originalFit, view.imageBoundsForTests)
        assertTrue(originalFit.contains(view.frameBoundsForTests))
        snapshot("moonlit-overlay-generated").recycle()
        bind(RelicAppearance(enabled = true, mode = RelicMode.STANDALONE, edgeTier = MoonlitTier.MYTHIC,
            bookplate = RelicBookplate.PRESTIGE, seal = MoonlitTier.MYTHIC, motion = false,
            inscription = "QA\nBook\u0000plate"), artwork = null)
        assertTrue(view.contentDescription.contains("Standalone book cover: Generated Moonlit Fixture"))
        assertFalse("Removed Bookplates are not announced", view.contentDescription.contains("Bookplate"))
        snapshot("moonlit-standalone-generated").apply {
            try { assertEquals("Standalone includes drawn glyphs and text", 21, pixels(this).asSequence().distinct().take(21).count()) }
            finally { recycle() }
        }
        assertArrayEquals(before, pixels(source))
    }

    @Test fun narrowOutsideCompositionKeepsBothEdgeRailsVisibleAndEveryImageCorner() {
        launch(); narrowViewport()
        val sourceBefore = pixels(source)
        val appearance = RelicAppearance(enabled = true, mode = RelicMode.MARGIN,
            bookplate = RelicBookplate.PRESTIGE, seal = MoonlitTier.MYTHIC, motion = false)
        val artwork = CoverArtwork.from(source)
        bind(appearance, artwork)
        val baseline = snapshot("moonlit-narrow-margin-without-edges")
        bind(appearance.copy(edgeTier = MoonlitTier.MYTHIC), artwork)
        val image = view.imageBoundsForTests
        val frame = view.frameBoundsForTests
        assertTrue("The composition surrounds the book rather than the tall screen", frame.height() < view.height * .85f)
        assertTrue(image.left - frame.left > view.width * .05f)
        assertTrue(frame.right - image.right > view.width * .05f)
        snapshot("moonlit-narrow-margin-generated").apply {
            try {
                fun changesIn(left: Int, right: Int): Int {
                    var changed = 0
                    for (y in 0 until height) for (x in left.coerceAtLeast(0) until right.coerceAtMost(width))
                        if (getPixel(x, y) != baseline.getPixel(x, y)) changed++
                    return changed
                }
                assertTrue("Mythic edges draw visible pixels beside the left side of the art", changesIn(0, image.left.toInt()) > 100)
                assertTrue("Mythic edges draw visible pixels beside the right side of the art", changesIn(image.right.toInt() + 1, width) > 100)
                listOf(.1f to .1f, .9f to .1f, .1f to .9f, .9f to .9f).forEach { (x, y) ->
                    assertEquals("Narrow-screen framing keeps the full original cover",
                        source.getPixel((x * source.width).toInt(), (y * source.height).toInt()),
                        getPixel((image.left + x * image.width()).toInt(), (image.top + y * image.height()).toInt()))
                }
            } finally { recycle(); baseline.recycle() }
        }
        assertArrayEquals(sourceBefore, pixels(source))
    }

    @Test fun narrowOverlayOrnamentsStayOnArtworkAndStandaloneRemainsBookShaped() {
        launch(); narrowViewport()
        bind(RelicAppearance())
        val plainFit = RectF(view.imageBoundsForTests)
        val appearance = RelicAppearance(enabled = true, mode = RelicMode.OVERLAY,
            bookplate = RelicBookplate.PRESTIGE, motion = false)
        val artwork = CoverArtwork.from(source)
        bind(appearance, artwork)
        val baseline = snapshot("moonlit-narrow-overlay-without-edges")
        bind(appearance.copy(edgeTier = MoonlitTier.MYTHIC), artwork)
        assertEquals(plainFit, view.imageBoundsForTests)
        snapshot("moonlit-narrow-overlay-generated").apply {
            try {
                var changedOnArtwork = 0
                for (y in 0 until height) for (x in 0 until width) {
                    if (getPixel(x, y) != baseline.getPixel(x, y)) {
                        assertTrue("Overlay decoration must not be clipped at the cover boundary", plainFit.contains(x.toFloat(), y.toFloat()))
                        changedOnArtwork++
                    }
                }
                assertTrue("The narrow overlay visibly draws Mythic edges", changedOnArtwork > 300)
            } finally { recycle(); baseline.recycle() }
        }
        bind(appearance.copy(mode = RelicMode.STANDALONE, edgeTier = MoonlitTier.MYTHIC,
            seal = MoonlitTier.MYTHIC), artwork = null)
        val frame = view.frameBoundsForTests
        assertTrue(frame.height() < frame.width() * 1.9f)
        assertTrue(frame.top > 0f && frame.bottom < view.height)
        snapshot("moonlit-narrow-standalone-generated").recycle()
    }

    @Test fun eachFamilyHasDistinctEdgesInAllNarrowModesWithoutRenderingLegacyBookplates() {
        launch(); narrowViewport()
        val sourceBefore = pixels(source)
        val artwork = CoverArtwork.from(source)
        for (mode in RelicMode.entries) {
            val familyPixels = ArrayList<IntArray>()
            for (set in RelicSet.entries) {
                // Hold the color constant so distinct results prove the shapes differ.
                val appearance = RelicAppearance(enabled = true, mode = mode, colorMode = RelicColorMode.CUSTOM,
                    customAccent = 0xff506b68.toInt(), edgeTier = MoonlitTier.MYTHIC, edgeSet = set, motion = false)
                bind(appearance, artwork)
                val image = RectF(view.imageBoundsForTests)
                val frame = RectF(view.frameBoundsForTests)
                val current = snapshot("relic-${set.name.lowercase()}-${mode.name.lowercase()}-narrow")
                val expected = pixels(current)
                familyPixels += expected
                if (mode == RelicMode.MARGIN) {
                    listOf(.1f to .1f, .9f to .1f, .1f to .9f, .9f to .9f).forEach { (x, y) ->
                        assertEquals("Every family's margin keeps the entire source image",
                            source.getPixel((x * source.width).toInt(), (y * source.height).toInt()),
                            current.getPixel((image.left + x * image.width()).toInt(), (image.top + y * image.height()).toInt()))
                    }
                }
                current.recycle()
                if (mode != RelicMode.STANDALONE) {
                    bind(appearance.copy(edgeTier = null), artwork)
                    snapshot("relic-${set.name.lowercase()}-${mode.name.lowercase()}-without-edges").apply {
                        try {
                            var leftRail = 0; var rightRail = 0; var changed = 0
                            for (y in 0 until height) for (x in 0 until width) {
                                if (getPixel(x, y) != expected[y * width + x]) {
                                    changed++
                                    if (mode == RelicMode.OVERLAY)
                                        assertTrue("$set overlay ornaments stay inside the original artwork", image.contains(x.toFloat(), y.toFloat()))
                                    if (x < image.left) leftRail++
                                    if (x > image.right) rightRail++
                                }
                            }
                            assertTrue("$set must visibly draw its $mode edges", changed > 300)
                            if (mode == RelicMode.MARGIN) {
                                assertTrue("$set left rail remains visible", leftRail > 100)
                                assertTrue("$set right rail remains visible", rightRail > 100)
                            }
                        } finally { recycle() }
                    }
                }
                for (legacy in listOf(RelicBookplate.STANDARD, RelicBookplate.PRESTIGE)) {
                    bind(appearance.copy(bookplate = legacy, inscription = "Legacy label must never appear"), artwork)
                    assertEquals(image, view.imageBoundsForTests)
                    assertEquals(frame, view.frameBoundsForTests)
                    snapshot("relic-${set.name.lowercase()}-${mode.name.lowercase()}-legacy-${legacy.name.lowercase()}").apply {
                        try { assertArrayEquals("Legacy Bookplate fields must change no rendered pixels", expected, pixels(this)) }
                        finally { recycle() }
                    }
                }
            }
            for (first in familyPixels.indices) for (second in first + 1 until familyPixels.size)
                assertFalse("Each set needs distinct edge geometry in $mode", familyPixels[first].contentEquals(familyPixels[second]))
        }
        assertArrayEquals(sourceBefore, pixels(source))
    }

    @Test fun allEighteenRelicGlyphsHaveDistinctMinimalSilhouettes() {
        check(instrumentation.targetContext.packageName == "com.bookcover.diagnostic.qa")
        val silhouettes = ArrayList<IntArray>()
        val sheet = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet).apply { drawColor(0xfff8f4e9.toInt()) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff263c3b.toInt(); textSize = 12f }
        try {
            for ((row, set) in RelicSet.entries.withIndex()) for ((column, tier) in MoonlitTier.entries.withIndex()) {
                val glyph = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                try {
                    drawRelicGlyph(Canvas(glyph), set, tier, RectF(10f, 10f, 110f, 110f), Color.BLACK)
                    val rendered = pixels(glyph)
                    assertTrue("Each collectible must draw a recognizable silhouette", rendered.count { it ushr 24 != 0 } > 100)
                    // Black pixels differ only in alpha, so a 32-bit IntArray hash collapses
                    // these fixed-size silhouettes to 256 possibilities. Compare every pixel.
                    for (previous in silhouettes)
                        assertFalse("Duplicate glyph for $set / $tier", previous.contentEquals(rendered))
                    silhouettes += rendered
                    if (set == RelicSet.MOONLIT) {
                        val compatible = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                        try {
                            drawMoonlitGlyph(Canvas(compatible), tier, RectF(10f, 10f, 110f, 110f), Color.BLACK)
                            assertArrayEquals("Moonlit wrapper preserves its original glyph", rendered, pixels(compatible))
                        } finally { compatible.recycle() }
                    }
                } finally { glyph.recycle() }
                val x = column * 150f; val y = row * 200f
                drawRelicGlyph(sheetCanvas, set, tier, RectF(x + 25f, y + 20f, x + 125f, y + 120f), label.color)
                sheetCanvas.drawText(set.name.lowercase(), x + 15f, y + 150f, label)
                sheetCanvas.drawText(tier.rarity, x + 15f, y + 170f, label)
            }
            assertEquals(18, silhouettes.size)
            saveImage("relic-glyphs-all-sets", sheet)
        } finally { sheet.recycle() }
    }

    @Test fun everyFamilyHasRealMythicMotionWithCacheReuseAndTerminalCleanup() {
        launch()
        val artwork = CoverArtwork.from(source)
        val evidence = StringBuilder()
        for ((index, set) in RelicSet.entries.withIndex()) {
            if (index > 0) {
                instrumentation.runOnMainSync { view = RelicCoverView(host); host.setContentView(view) }
                await { view.width > 0 && view.height > 0 && view.isAttachedToWindow }
            }
            narrowViewport()
            val mythic = RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC, edgeSet = set)
            bind(mythic, artwork)
            if (!ValueAnimator.areAnimatorsEnabled()) {
                assertFalse(view.motionRunningForTests)
                instrumentation.runOnMainSync { view.release() }
                evidence.append("$set: systemAnimations=false; staticFallback=true; releaseStopped=true\n")
                continue
            }
            val preparations = view.preparationCountForTests
            val first = snapshot("relic-${set.name.lowercase()}-mythic-first")
            val start = view.motionPhaseForTests
            await { view.motionRunningForTests && (view.motionPhaseForTests - start + 1f) % 1f > .025f }
            snapshot("relic-${set.name.lowercase()}-mythic-later").apply {
                try { assertFalse("$set Mythic must change actual pixels", pixels(first).contentEquals(pixels(this))) }
                finally { recycle(); first.recycle() }
            }
            assertEquals("$set animation must reuse its prepared cover", preparations, view.preparationCountForTests)
            instrumentation.runOnMainSync { view.setPresentationVisible(false) }
            val frozen = view.motionPhaseForTests
            SystemClock.sleep(80)
            assertFalse(view.motionRunningForTests)
            assertEquals(frozen, view.motionPhaseForTests, 0f)
            instrumentation.runOnMainSync { view.setPresentationVisible(true) }
            await { view.motionRunningForTests }
            bind(mythic.copy(motion = false), artwork)
            assertFalse(view.motionRunningForTests)
            bind(mythic, artwork)
            await { view.motionRunningForTests }
            instrumentation.runOnMainSync { view.release() }
            bind(mythic, artwork)
            assertFalse("$set release remains terminal", view.motionRunningForTests)
            evidence.append("$set: renderedPixelsChanged=true; staticCoverReused=true; hiddenPauseHeld=true; releaseStopped=true\n")
        }
        motionEvidence(evidence.toString(), "relic-three-family-motion")
    }

    @Test fun mythicTraceStopsWhenHiddenClippedDisabledDetachedAndReleased() {
        launch(); narrowViewport()
        val mythic = RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC)
        bind(mythic)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            assertFalse("System reduced motion keeps Mythic static", view.motionRunningForTests)
            motionEvidence("systemAnimations=false\nstaticFallback=true\n")
            return
        }
        val preparations = view.preparationCountForTests
        val first = snapshot("moonlit-mythic-trace-first")
        val initialPhase = view.motionPhaseForTests
        await { view.motionRunningForTests && view.motionPhaseForTests > initialPhase + .025f }
        snapshot("moonlit-mythic-trace-later").apply {
            try { assertFalse("The native Mythic trace actually changes rendered pixels", pixels(first).contentEquals(pixels(this))) }
            finally { recycle(); first.recycle() }
        }
        assertEquals("Animation reuses the prepared cover", preparations, view.preparationCountForTests)
        instrumentation.runOnMainSync { view.setPresentationVisible(false) }
        val frozen = view.motionPhaseForTests
        SystemClock.sleep(100)
        assertFalse(view.motionRunningForTests)
        assertEquals(frozen, view.motionPhaseForTests, 0f)
        instrumentation.runOnMainSync { view.setPresentationVisible(true) }
        await { view.motionRunningForTests }
        bind(mythic.copy(motion = false)); assertFalse(view.motionRunningForTests)
        bind(mythic.copy(enabled = false)); assertFalse(view.motionRunningForTests)
        bind(mythic); await { view.motionRunningForTests }
        instrumentation.runOnMainSync { view.visibility = View.INVISIBLE }
        assertFalse(view.motionRunningForTests)
        instrumentation.runOnMainSync { view.visibility = View.VISIBLE }
        await { view.motionRunningForTests }

        lateinit var scroll: ScrollView
        var spacerHeight = 0
        instrumentation.runOnMainSync {
            val viewportHeight = host.window.decorView.height
            assertTrue("Clipping requires a measured host viewport", viewportHeight > 0)
            spacerHeight = maxOf(view.height * 2, viewportHeight + 1)
            // Detach first, then exercise real clipping inside a scrolling native parent.
            host.setContentView(View(host))
            assertFalse(view.motionRunningForTests)
            scroll = ScrollView(host)
            val column = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            column.addView(View(host), LinearLayout.LayoutParams(-1, spacerHeight))
            column.addView(view, LinearLayout.LayoutParams(-1, 800))
            scroll.addView(column); host.setContentView(scroll)
        }
        instrumentation.waitForIdleSync()
        await { view.isLaidOut && !view.getGlobalVisibleRect(Rect()) }
        await { !view.motionRunningForTests }
        instrumentation.runOnMainSync { scroll.scrollTo(0, spacerHeight) }
        await { view.getGlobalVisibleRect(Rect()) && view.motionRunningForTests }
        instrumentation.runOnMainSync { view.release() }
        SystemClock.sleep(100)
        assertFalse(view.motionRunningForTests)
        bind(mythic)
        assertFalse("Release is terminal", view.motionRunningForTests)
        motionEvidence("systemAnimations=true\nnarrowViewport=360x880\ninitialPhase=$initialPhase\npausedPhase=$frozen\nrenderedPixelsChanged=true\n" +
            "staticCoverReused=true\nhiddenPauseHeld=true\nclippedPauseAndResume=true\nreleaseStopped=true\n")
    }

    private fun motionEvidence(value: String, name: String = "moonlit-native-motion") {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let {
            File(it).mkdirs(); File(it, "$name.txt").writeText(value)
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            instrumentation.runOnMainSync { matched = predicate() }
            if (matched) return
            SystemClock.sleep(40)
        }
        var details = ""
        instrumentation.runOnMainSync {
            details = "attached=${view.isAttachedToWindow},size=${view.width}x${view.height}," +
                "shown=${view.isShown},window=${view.windowVisibility},motion=${view.motionRunningForTests}," +
                "phase=${view.motionPhaseForTests},parent=${view.parent?.javaClass?.simpleName}"
        }
        fail("Native relic view did not reach the expected layout/motion state: $details")
    }
}
