package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.visibleReadingAnchor
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.ReadingProgression

/** Real Android WebView/Readium integration tests, isolated from the user's library/preferences. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class NativeReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var host: ReaderTestActivity
    private lateinit var reader: EpubSession

    @Before fun cleanTestPreferences() {
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("publisherStyles", false).commit() // Existing reflow probes deliberately use custom typography.
    }

    @After fun cleanUp() {
        scenario?.close()
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun launch(fixed: Boolean = false, progression: String? = null, language: String = "en",
                       imagePages: Boolean = false, legacyOverlay: Boolean = false) {
        val intent = Intent(target, ReaderTestActivity::class.java).putExtra(ReaderTestActivity.EXTRA_FIXED_LAYOUT, fixed)
            .putExtra(ReaderTestActivity.EXTRA_READING_PROGRESSION, progression)
            .putExtra(ReaderTestActivity.EXTRA_LANGUAGE, language)
            .putExtra(ReaderTestActivity.EXTRA_IMAGE_PAGES, imagePages)
            .putExtra(ReaderTestActivity.EXTRA_LEGACY_OVERLAY, legacyOverlay)
        scenario = ActivityScenario.launch<ReaderTestActivity>(intent).also { it.onActivity { activity -> host = activity } }
        reader = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("navigator is attached") { main { reader.fragment.isAdded && reader.fragment.view?.width != 0 } }
        if (!fixed) waitFor("reflowable chapter is rendered") {
            val marker = if (imagePages) "graphic" else "paragraph-0"
            runCatching { js("Boolean(document.getElementById('$marker') && window.readium)") == "true" }.getOrDefault(false)
        }
    }

    @Test fun fontSizeChangesGlyphsAndWrappingWithoutMagnifyingViewportOrImages() {
        launch()
        assertTrue(main { reader.supportsTypography })
        val before = metrics()
        assertEquals("The CSS font-size route leaves Android WebSettings.textZoom at 100", 100, main { visibleWebView().settings.textZoom })
        js("readium.scrollToId('paragraph-35'); true")
        val anchorBefore = firstVisibleParagraph()
        assertTrue("The test is midway through the chapter", anchorBefore >= 30)
        main { reader.changeFont(1.6) }
        waitFor("CSS typography update completes") { !main { reader.typographyUpdating } && metrics().getDouble("font") > before.getDouble("font") * 1.45 }
        val after = metrics()
        assertTrue("Actual paragraph glyphs increase in size", after.getDouble("font") > before.getDouble("font") * 1.45)
        assertTrue("The prose creates more line fragments after reflow", after.getInt("lines") > before.getInt("lines"))
        assertEquals("Viewport width stays fixed", before.getDouble("viewport"), after.getDouble("viewport"), 0.5)
        assertEquals("Images are not magnified by font controls", before.getDouble("imageWidth"), after.getDouble("imageWidth"), 0.5)
        assertEquals("Visual viewport scale stays fixed", before.getDouble("scale"), after.getDouble("scale"), 0.01)
        assertEquals(100, main { visibleWebView().settings.textZoom })
        assertTrue("Reading remains near the same paragraph", abs(firstVisibleParagraph() - anchorBefore) <= 2)
        assertEquals("An emphasized span keeps its italic style", "italic", after.getString("emStyle"))
        assertTrue("Strong text keeps a heavier weight", after.getInt("strongWeight") > after.getInt("normalWeight"))
        assertTrue("Paragraph spacing is positive", after.getDouble("paragraphSpace") > 0)
    }

    @Test fun fontFamilyAndSizePersistWhenOpeningANewSession() {
        launch()
        main { reader.changeFontFamily("sans"); reader.changeFont(1.4) }
        waitFor("font family and size are applied") {
            !main { reader.typographyUpdating } && metrics().getString("family").contains("sans", ignoreCase = true)
        }
        val preference = target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE)
        assertEquals("sans", preference.getString("fontId", null))
        assertEquals(1.4f, preference.getFloat("fontScale", 0f), 0.001f)
        scenario!!.close(); scenario = null
        launch()
        assertEquals("sans", main { reader.fontId })
        assertEquals(1.4, main { reader.fontScale }, 0.001)
        waitFor("saved typography appears in the new native renderer") {
            metrics().getString("family").contains("sans", ignoreCase = true) && metrics().getDouble("font") > 20.0
        }
        main { reader.changeFontFamily("dyslexic") }
        waitFor("the bundled OpenDyslexic font is loaded and used") {
            !main { reader.typographyUpdating } &&
                metrics().getString("family").contains("OpenDyslexic", ignoreCase = true) &&
                js("document.fonts.check('16px \"OpenDyslexic\"') && document.fonts.status === 'loaded'") == "true"
        }
        assertEquals("An actual declared OpenDyslexic FontFace loaded, rather than merely a fallback family name",
            "true", js("Array.from(document.fonts).some(function(font){return font.family.indexOf('OpenDyslexic') >= 0 && font.status === 'loaded';})"))
        assertEquals("dyslexic", preference.getString("fontId", null))
    }

    @Test fun optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages() {
        launch()
        assertFalse("Swipes are the default; edge taps start disabled", main { reader.edgeTaps })
        val start = settledPageOffset()
        val pageWidth = js("window.innerWidth").toDouble()
        tapViewport(0.98f, 0.60f)
        SystemClock.sleep(400)
        assertEquals("Disabled edge tap does not navigate", start, pageOffset(), 1.0)
        swipeViewport(0.80f, 0.20f, 0.60f)
        waitFor("a normal swipe advances a page") { pageOffset() > start + 50 }
        val afterSwipe = settledPageOffset()
        main { reader.changeEdgeTaps(true) }
        tapViewport(0.98f, 0.60f)
        waitFor("enabled right-edge tap advances") { pageOffset() > afterSwipe + 50 }
        val afterRightTap = settledPageOffset()
        assertEquals("A right-edge tap advances exactly one page", pageWidth, afterRightTap - afterSwipe, 2.0)
        tapViewport(0.02f, 0.60f)
        waitFor("enabled left-edge tap goes back") { pageOffset() < afterRightTap - 50 }
        val afterLeftTap = settledPageOffset()
        assertEquals("A left-edge tap returns exactly one page", pageWidth, afterRightTap - afterLeftTap, 2.0)
        // This point is below the top toolbar and above the controls panel in either layout.
        tapViewport(0.50f, 0.16f)
        waitFor("the existing center tap reveals reading controls") { main { reader.controlsVisible } }
        tapViewport(0.98f, 0.16f)
        SystemClock.sleep(400)
        assertEquals("An edge tap does not navigate while reading controls are visible", afterLeftTap, pageOffset(), 1.0)
        main { reader.controlsVisible = false }
        main { reader.changeEdgeTaps(false) }
        val beforeDisabledTap = afterLeftTap
        tapViewport(0.98f, 0.60f)
        SystemClock.sleep(400)
        assertEquals(beforeDisabledTap, pageOffset(), 1.0)
        swipeViewport(0.80f, 0.20f, 0.60f)
        waitFor("swipes still work after toggling edge taps") { pageOffset() > beforeDisabledTap + 50 }
        settledPageOffset()

        // Return to the chapter start and tap a real edge-positioned HTML hyperlink. Readium's
        // interactive-element path gets first refusal; the app's edge listener must not consume it.
        js("readium.scrollToStart(); true")
        settledPageOffset()
        main { reader.changeEdgeTaps(true) }
        tapElement("edge-link")
        waitFor("the edge hyperlink opens its target chapter") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") }
        }
        assertTrue("The destination chapter rendered", js("Boolean(document.getElementById('linked'))") == "true")
    }

    @Test fun explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions() {
        launch(progression = "rtl", language = "ar")
        checkDirectionalEdgeTurns(rightToLeft = true)
    }

    @Test fun languageInferredRtlUsesTheSameEdgeDirectionAsReadium() {
        launch(language = "he") // No spine direction: Readium infers RTL from Hebrew metadata.
        assertNull(main { reader.publication.metadata.readingProgression })
        checkDirectionalEdgeTurns(rightToLeft = true)
    }

    @Test fun explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns() {
        launch(progression = "ltr", language = "ar")
        checkDirectionalEdgeTurns(rightToLeft = false)
    }

    private fun checkDirectionalEdgeTurns(rightToLeft: Boolean) {
        assertEquals(if (rightToLeft) ReadingProgression.RTL else ReadingProgression.LTR,
            main { reader.fragment.settings.value.readingProgression })
        val forwardEdge = if (rightToLeft) 0.02f else 0.98f
        val backwardEdge = 1f - forwardEdge
        val sign = if (rightToLeft) -1 else 1
        val start = settledPageOffset()
        val pageWidth = js("window.innerWidth").toDouble()
        assertFalse(main { reader.edgeTaps })
        tapViewport(forwardEdge, 0.60f)
        SystemClock.sleep(400)
        assertEquals("Edges remain disabled by default in either direction", start, pageOffset(), 1.0)

        swipeViewport(if (rightToLeft) 0.20f else 0.80f, if (rightToLeft) 0.80f else 0.20f, 0.60f)
        waitFor("native swipe advances in book direction") { (pageOffset() - start) * sign > 50 }
        val afterSwipe = settledPageOffset()
        main { reader.changeEdgeTaps(true) }
        tapViewport(forwardEdge, 0.60f)
        waitFor("forward edge advances in book direction") { (pageOffset() - afterSwipe) * sign > 50 }
        val afterForwardTap = settledPageOffset()
        assertEquals("Forward edge advances exactly one page", pageWidth, (afterForwardTap - afterSwipe) * sign, 2.0)
        tapViewport(backwardEdge, 0.60f)
        waitFor("opposite edge returns in book direction") { (afterForwardTap - pageOffset()) * sign > 50 }
        assertEquals("Backward edge returns exactly one page", afterSwipe, settledPageOffset(), 2.0)

        main { reader.controlsVisible = true }
        tapViewport(forwardEdge, 0.60f)
        SystemClock.sleep(400)
        assertEquals("Visible controls still suppress edge turns", afterSwipe, pageOffset(), 1.0)
        main { reader.controlsVisible = false }
        assertEquals("true", js("(function(){var r=document.createRange();r.selectNodeContents(document.getElementById('paragraph-0'));var s=window.getSelection();s.removeAllRanges();s.addRange(r);return !s.isCollapsed;})()"))
        tapViewport(forwardEdge, 0.60f)
        SystemClock.sleep(400)
        assertEquals("Dismissing a selection must not turn an RTL or LTR page", afterSwipe, pageOffset(), 1.0)

        // Check spine transitions as well as scrolling within the long resource.
        js("window.getSelection().removeAllRanges(); readium.scrollToEnd(); true")
        settledPageOffset()
        tapViewport(forwardEdge, 0.60f)
        waitFor("forward edge crosses to the next chapter") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") } &&
                js("Boolean(document.getElementById('linked'))") == "true"
        }
        settledPageOffset()
        tapViewport(backwardEdge, 0.60f)
        waitFor("backward edge returns to the previous chapter") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter0.xhtml") } &&
                js("Boolean(document.getElementById('paragraph-0'))") == "true"
        }
        settledPageOffset()
        js("readium.scrollToStart(); true")
        settledPageOffset()
        tapElement("edge-link")
        waitFor("edge-positioned hyperlink still wins over page navigation") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") } &&
                js("Boolean(document.getElementById('linked'))") == "true"
        }
    }

    @Test fun rtlImagePagesAcceptEdgeTapsOnArtwork() = checkImagePageEdgeTurns(legacyOverlay = false)

    @Test fun rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps() = checkImagePageEdgeTurns(legacyOverlay = true)

    private fun checkImagePageEdgeTurns(legacyOverlay: Boolean) {
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("publisherStyles", true).commit()
        launch(progression = "rtl", imagePages = true, legacyOverlay = legacyOverlay)
        assertEquals(ReadingProgression.RTL, main { reader.fragment.settings.value.readingProgression })
        settledPageOffset()
        if (legacyOverlay) assertEquals("Kindle-only href-less overlays must be passive in the rendered DOM", "\"span\"",
            js("document.querySelector('[data-app-amzn-magnify]').tagName.toLowerCase()"))
        val imageLayout = js("JSON.stringify({x:scrollX,w:innerWidth,h:innerHeight,tag:document.elementFromPoint(innerWidth*0.02,innerHeight*0.60).tagName,rect:document.getElementById('graphic').getBoundingClientRect().toJSON(),style:getComputedStyle(document.getElementById('graphic')).position})")
        assertEquals("The edge lies on artwork or its inert panel overlay, not an empty margin: $imageLayout", "true",
            js("['IMG','SPAN','A'].includes(document.elementFromPoint(innerWidth*0.02,innerHeight*0.60).tagName.toUpperCase())"))
        tapViewport(0.02f, 0.60f)
        SystemClock.sleep(400)
        assertTrue("Image edges stay off by default", main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter0.xhtml") })
        main { reader.changeEdgeTaps(true) }
        tapViewport(0.02f, 0.60f)
        waitFor("left-edge artwork tap advances to the next RTL image page") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") }
        }
        settledPageOffset()
        tapViewport(0.98f, 0.60f)
        waitFor("right-edge artwork tap returns to the previous RTL image page") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter0.xhtml") }
        }
        settledPageOffset()
        tapViewport(0.02f, 0.30f)
        waitFor("a real image hyperlink still uses the existing link handler") { main { host.reports.any { it.contains("website") } } }
        assertTrue("An image link is not consumed as a page turn", main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter0.xhtml") })
        swipeViewport(0.20f, 0.80f, 0.60f)
        waitFor("native swipe still advances an RTL image page") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") }
        }
    }

    @Test fun selectionDismissalDoesNotTriggerAnEdgePageTurn() {
        launch()
        main { reader.changeEdgeTaps(true) }
        val offset = pageOffset()
        // A real DOM text selection exercises Readium's selection guard deterministically. The
        // separate human-device checklist also covers native long-press selection and handles.
        assertEquals("true", js("(function(){var r=document.createRange();r.selectNodeContents(document.getElementById('paragraph-0'));var s=window.getSelection();s.removeAllRanges();s.addRange(r);return !s.isCollapsed;})()"))
        tapViewport(0.98f, 0.60f)
        SystemClock.sleep(400)
        assertEquals("A tap dismissing selection is not a page turn", offset, pageOffset(), 1.0)
    }

    @Test fun fixedLayoutDisablesTypographyAndKeepsPublisherLayout() {
        launch(fixed = true)
        assertFalse("Fixed-layout EPUBs cannot be reflowed by typography controls", main { reader.supportsTypography })
        val size = main { reader.fontScale }
        val family = main { reader.fontId }
        val comfort = main { reader.comfort }
        main {
            reader.changeFont(1.8); reader.changeFontFamily("sans"); reader.changePublisherStyles(true)
            reader.changeLineSpacing(2.0); reader.changePageMargins("wide"); reader.changeTextAlignment("justified")
        }
        assertEquals(size, main { reader.fontScale }, 0.0)
        assertEquals(family, main { reader.fontId })
        assertEquals(comfort, main { reader.comfort })
        assertNull("Fixed layout has no reflowable JavaScript viewport", main { reader.fragment.evaluateJavascript("document.documentElement.clientWidth") })
        assertFalse("Unsupported changes do not start a typography operation", main { reader.typographyUpdating })
    }

    @Test fun publisherStylesDefaultOnAndRestorePoetryWithoutLosingCustomPreferences() {
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        assertTrue("A fresh reader respects publisher styles", main { reader.comfort.publisherStyles })
        val authored = comfortMetrics()
        assertEquals("center", authored.getString("poemAlign"))
        assertEquals(3.0, authored.getDouble("poemIndent") / authored.getDouble("poemFont"), 0.05)
        assertTrue(authored.getString("poemFamily").contains("monospace"))
        main { reader.changePublisherStyles(false) }
        waitFor("custom paragraph settings replace publisher overrides") {
            !main { reader.typographyUpdating } && comfortMetrics().getString("poemAlign") == "start"
        }
        assertTrue(comfortMetrics().getDouble("poemIndent") < authored.getDouble("poemIndent"))
        main { reader.changeLineSpacing(1.8); reader.changePageMargins("wide"); reader.changeTextAlignment("justified") }
        waitFor("custom choices are applied") { !main { reader.typographyUpdating } && comfortMetrics().getString("alignment") == "justify" }
        val custom = main { reader.comfort }
        main { reader.changePublisherStyles(true) }
        waitFor("publisher poetry formatting is restored") {
            !main { reader.typographyUpdating } && comfortMetrics().getString("poemAlign") == "center"
        }
        val restored = comfortMetrics()
        assertEquals(authored.getDouble("poemIndent"), restored.getDouble("poemIndent"), 0.5)
        assertEquals(authored.getString("poemFamily"), restored.getString("poemFamily"))
        assertEquals(custom.copy(publisherStyles = true), main { reader.comfort })
        scenario!!.close(); scenario = null; launch()
        assertEquals(custom.copy(publisherStyles = true), main { reader.comfort })
        main { reader.changePublisherStyles(false) }
        waitFor("saved custom settings return") { !main { reader.typographyUpdating } && comfortMetrics().getString("alignment") == "justify" }
        assertEquals(custom, main { reader.comfort })
        assertTrue("CSS application succeeded, rather than only saving controls", host.reports.isEmpty())
    }

    /** C1: a fresh reader can change text size without giving up the book's own formatting. */
    @Test fun textSizeChangesWithPublisherStylesAndKeepsPoetryFormatting() {
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        assertTrue("A fresh reader respects publisher styles", main { reader.comfort.publisherStyles })
        val authored = comfortMetrics()
        // The fixture sets every paragraph, the poem included, to 18px; publisher styles keep such book
        // sizes, so growth is measured on the heading, whose size follows the reader's text size.
        main { reader.changeFont(1.5) }
        waitFor("150% text applies with publisher styles on") {
            !main { reader.typographyUpdating } && comfortMetrics().getDouble("headingFont") > authored.getDouble("headingFont") * 1.45
        }
        assertTrue("Changing size does not turn publisher styles off", main { reader.comfort.publisherStyles })
        assertEquals("Every typography check confirms with publisher styles on", "", main { reader.unconfirmedTypography })
        val large = comfortMetrics()
        assertEquals("Poetry stays centred", "center", large.getString("poemAlign"))
        assertEquals("The poem keeps its 3em indent", 3.0, large.getDouble("poemIndent") / large.getDouble("poemFont"), 0.05)
        assertEquals("The book's poem font is kept", authored.getString("poemFamily"), large.getString("poemFamily"))

        js("readium.scrollToId('paragraph-35'); true")
        settledPageOffset()
        val anchorBefore = firstVisibleParagraph()
        main { reader.changeFont(1.4) }
        waitFor("140% text applies") { !main { reader.typographyUpdating } &&
            abs(comfortMetrics().getDouble("headingFont") / authored.getDouble("headingFont") - 1.4) < 0.05 }
        assertTrue("Reading stays near the same paragraph", abs(firstVisibleParagraph() - anchorBefore) <= 2)
        main { reader.changePublisherStyles(false) }
        waitFor("custom formatting applies") { !main { reader.typographyUpdating } && comfortMetrics().getString("poemAlign") == "start" }
        assertEquals("Turning publisher styles off keeps the saved size", 1.4, main { reader.fontScale }, 0.001)
        main { reader.changePublisherStyles(true) }
        waitFor("publisher formatting returns") { !main { reader.typographyUpdating } && comfortMetrics().getString("poemAlign") == "center" }
        assertEquals(1.4, main { reader.fontScale }, 0.001)
        assertEquals(1.4, comfortMetrics().getDouble("headingFont") / authored.getDouble("headingFont"), 0.05)
        scenario!!.close(); scenario = null; launch()
        assertEquals("140% survives reopening", 1.4, main { reader.fontScale }, 0.001)
        assertTrue(main { reader.comfort.publisherStyles })
        waitFor("the reopened book shows 140% text") {
            abs(comfortMetrics().getDouble("headingFont") / authored.getDouble("headingFont") - 1.4) < 0.05
        }
        assertEquals("center", comfortMetrics().getString("poemAlign"))
        assertTrue("CSS application succeeded, rather than only saving controls", host.reports.isEmpty())
    }

    @Test fun comfortControlsChangeRealLayoutAndPreserveMidChapterPosition() {
        launch()
        js("readium.scrollToId('paragraph-35'); true")
        settledPageOffset()
        val before = comfortMetrics()
        fun nearAnchor(control: String, action: () -> Unit) {
            val paragraph = firstVisibleParagraph()
            val anchor = main { reader.fragment.visibleReadingAnchor() } ?: error("No rendered reading anchor")
            val selector = anchor.locations["cssSelector"] as? String ?: error("No generated paragraph selector")
            val quote = anchor.text.highlight?.takeIf { it.isNotBlank() } ?: error("No rendered passage")
            fun passageVisible(): Boolean = js("""(function(){
                var e=document.querySelector(${JSONObject.quote(selector)}), quote=${JSONObject.quote(quote)};
                if(!e) return false;
                var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT), node;
                while((node=walker.nextNode())) {
                    var start=node.data.indexOf(quote); if(start<0) continue;
                    var range=document.createRange(); range.setStart(node,start); range.setEnd(node,start+1);
                    return Array.from(range.getClientRects()).some(function(r){
                        return r.width>0 && r.height>0 && r.right>1 && r.left<innerWidth-1 && r.bottom>1 && r.top<innerHeight-1;
                    });
                }
                return false;
            })()""") == "true"
            assertTrue("$control starts with its saved passage actually on screen", passageVisible())
            main { action() }
            waitFor("comfort layout settles") { !main { reader.typographyUpdating } }
            val after = firstVisibleParagraph()
            // A new page height or spacing can fit more earlier paragraphs above the saved word.
            // Verify that word itself stays visible rather than counting the page's earlier blocks.
            if (!passageVisible()) {
                val geometry = js("""(function(){var e=document.querySelector(${JSONObject.quote(selector)});
                    return {scrollX:scrollX,width:innerWidth,height:innerHeight,
                    rects:e ? Array.from(e.getClientRects()).slice(0,8).map(function(r){return {left:r.left,top:r.top,right:r.right,bottom:r.bottom};}) : []};})()""")
                fail("$control keeps the exact saved passage visible: before=$paragraph, after=$after; anchor=${anchor.toJSON()}; generatedGeometry=$geometry")
            }
        }
        nearAnchor("Line spacing") { reader.changeLineSpacing(2.0) }
        assertTrue(comfortMetrics().getDouble("lineHeight") > before.getDouble("lineHeight") * 1.2)
        nearAnchor("Side margins") { reader.changePageMargins("wide") }
        val wide = comfortMetrics()
        assertTrue(wide.getDouble("padding") > before.getDouble("padding") * 1.8)
        assertTrue(wide.getDouble("textWidth") < before.getDouble("textWidth"))
        nearAnchor("Alignment") { reader.changeTextAlignment("justified") }
        assertEquals("justify", comfortMetrics().getString("alignment"))
        nearAnchor("Font size") { reader.changeFont(1.6) }
        assertEquals("Changing font size must not change selected side margins", wide.getDouble("padding"), comfortMetrics().getDouble("padding"), 0.5)
        assertEquals("The owner's fixed text-column cap is retained", "800px", comfortMetrics().getString("maxLine"))
        scenario!!.close(); scenario = null; launch()
        assertEquals(2.0, main { reader.comfort.lineSpacing }, 0.0)
        assertEquals("wide", main { reader.comfort.margins.id })
        assertEquals("justified", main { reader.comfort.alignment.id })
        assertFalse(main { reader.comfort.publisherStyles })
        assertTrue(host.reports.isEmpty())
    }

    private fun comfortMetrics(): JSONObject = JSONObject(js("""(function(){
        var p=document.getElementById('paragraph-0'), s=getComputedStyle(p), b=getComputedStyle(document.body);
        var v=getComputedStyle(document.getElementById('poem'));
        return {lineHeight:parseFloat(s.lineHeight),alignment:s.textAlign,padding:parseFloat(b.paddingLeft),
            textWidth:p.getBoundingClientRect().width,poemIndent:parseFloat(v.textIndent),poemFont:parseFloat(v.fontSize),
            headingFont:parseFloat(getComputedStyle(document.getElementById('heading')).fontSize),
            poemAlign:v.textAlign,poemFamily:v.fontFamily,maxLine:getComputedStyle(document.documentElement).getPropertyValue('--RS__maxLineLength').trim()};})()"""))

    private fun metrics(): JSONObject = JSONObject(js("""(function(){
        var p=document.getElementById('paragraph-0'), s=getComputedStyle(p), r=document.createRange();
        r.selectNode(p.firstChild);
        var image=document.getElementById('sample-image');
        return {font:parseFloat(s.fontSize),family:s.fontFamily,lines:r.getClientRects().length,
            viewport:window.innerWidth,imageWidth:image.getBoundingClientRect().width,
            scale:window.visualViewport ? window.visualViewport.scale : 1,
            emStyle:getComputedStyle(p.querySelector('em')).fontStyle,
            strongWeight:parseInt(getComputedStyle(p.querySelector('strong')).fontWeight),
            normalWeight:parseInt(s.fontWeight),paragraphSpace:parseFloat(s.marginBottom)};
        })()"""))

    private fun firstVisibleParagraph(): Int {
        val locator = main { reader.fragment.firstVisibleElementLocator() } ?: error("No visible text locator")
        return Regex("Paragraph (\\d+)").find(locator.text.highlight.orEmpty())?.groupValues?.get(1)?.toInt()
            ?: error("Expected a paragraph anchor; received ${locator.locations.otherLocations}")
    }

    private fun pageOffset(): Double = js("window.scrollX").toDouble()

    /** A turn emits intermediate offsets during Readium's animation; assertions use its endpoint. */
    private fun settledPageOffset(): Double {
        var previous = pageOffset()
        var stableSince = SystemClock.uptimeMillis()
        waitFor("the native page animation reaches a stable endpoint", timeout = 5_000) {
            val current = pageOffset()
            if (abs(current - previous) > 0.5) stableSince = SystemClock.uptimeMillis()
            previous = current
            SystemClock.uptimeMillis() - stableSince >= 240
        }
        return previous
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }

    /** Public Android View inspection is confined to this test; production uses Readium APIs. */
    private fun visibleWebView(): WebView {
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        return descendants(reader.fragment.requireView()).filterIsInstance<WebView>().maxByOrNull {
            val visible = Rect(); if (it.getGlobalVisibleRect(visible)) visible.width() * visible.height() else 0
        } ?: error("No native WebView")
    }

    private fun webViewFrame(): Rect = main {
        val view = visibleWebView(); val point = IntArray(2); view.getLocationOnScreen(point)
        Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }

    private fun tapViewport(x: Float, y: Float) {
        val frame = webViewFrame()
        tap(frame.left + frame.width() * x, frame.top + frame.height() * y)
    }

    private fun tapElement(id: String) {
        val point = JSONObject(js("""(function(){var r=document.getElementById('$id').getBoundingClientRect();return {x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio};})()"""))
        val frame = webViewFrame()
        tap(frame.left + point.getDouble("x").toFloat(), frame.top + point.getDouble("y").toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(40)
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
    }

    private fun swipeViewport(start: Float, end: Float, y: Float) {
        val frame = webViewFrame()
        val down = SystemClock.uptimeMillis()
        val height = frame.top + frame.height() * y
        send(down, down, MotionEvent.ACTION_DOWN, frame.left + frame.width() * start, height)
        for (step in 1..12) {
            SystemClock.sleep(20)
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                frame.left + frame.width() * (start + (end - start) * step / 12), height)
        }
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, frame.left + frame.width() * end, height)
        instrumentation.waitForIdleSync()
    }

    private fun send(down: Long, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            .also { instrumentation.sendPointerSync(it); it.recycle() }
    }
}
