package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.reader.PdfTextViewState
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end text reading with a generated, internally clipped PDF in the isolated QA app. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class PdfTextModeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File
    private var priorAccessibilityFlags = 0

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "PDF text tests require the isolated QA app." }
        File(target.filesDir, "library").listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        fixtures = File(target.cacheDir, "generated-pdf-text-ui-${UUID.randomUUID()}").apply { mkdirs() }
        val service = automation.serviceInfo
        priorAccessibilityFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
    }

    @After fun cleanup() {
        scenario?.close()
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
        automation.serviceInfo = automation.serviceInfo.apply { flags = priorAccessibilityFlags }
    }

    @Test fun textViewFlowsAcrossPdfPagesAndRestoresScreenfulAndBookmark() {
        val book = importFixture()
        onActivity { it.openBookForTests(book) }
        waitForReader(book)
        waitFor("the first PDF page") { onActivity { it.pdfSessionForTests?.textPage == 1 } }
        openControls()
        clickTag("pdf-text-toggle")
        awaitTextPage(1)
        waitFor("short PDF pages flow into the same screenful") {
            visibleText().let { "Generated page 1 line 2" in it && SEAM_TEXT in it && "Generated page 2 line 0" in it }
        }
        val first = textAnchor()
        assertEquals("the first screen starts on the first physical page", 1, first.first)

        // Up/down drags must not reveal a small amount of hidden vertical content.
        dragText(0.5f, 0.5f, 0.75f, 0.25f)
        assertEquals("a vertical drag does not shift the passage", first, textAnchor())

        dragText(0.8f, 0.2f, 0.6f, 0.6f)
        waitFor("a left swipe advances a screenful") { textAnchor() != first }
        val second = textAnchor()
        assertEquals("the screenful advances onto the next physical page", 2, second.first)
        dragText(0.2f, 0.8f, 0.6f, 0.6f)
        waitFor("a right swipe returns across the physical page boundary") { textAnchor() == first }
        openControls()
        clickTag("pdf-next")
        waitFor("the Next button advances to the same screenful as a swipe") { textAnchor() == second }

        openControls()
        clickLabel("Bookmarks")
        clickLabel("Add bookmark")
        clickTag("bookmark-save")
        waitFor("the text bookmark is saved") {
            onActivity { it.libraryForTests.book(book.id)?.bookmarks?.isNotEmpty() == true }
        }
        val bookmark = onActivity { it.libraryForTests.book(book.id)!!.bookmarks.single() }
        val bookmarkOffset = JSONObject(bookmark).getJSONObject("locations").getInt(SavedBookmarks.PDF_TEXT_OFFSET)
        assertEquals("the text bookmark stores the screenful's character offset", second.second, bookmarkOffset)

        // Move farther down the same PDF page, then return without a physical page change.
        // That jump must persist even if the user does nothing else before the app is stopped.
        clickLabel("Back to controls")
        clickLabel("Read")
        dragText(0.8f, 0.2f, 0.6f, 0.6f)
        waitFor("the next screenful advances past the bookmark") { textAnchor().let { it.first == 2 && it.second > bookmarkOffset } }
        openControls()
        clickLabel("Bookmarks")
        clickLabel("Bookmark")
        awaitTextPage(2)
        waitFor("the same-page bookmark restores its passage") {
            onActivity { (it.pdfSessionForTests?.textOffset ?: 0).let { offset -> offset > 0 && abs(offset - bookmarkOffset) <= 80 } }
        }
        waitFor("the same-page bookmark is saved as the reading position") {
            onActivity { activity ->
                val value = activity.libraryForTests.book(book.id)?.locator ?: return@onActivity false
                val offset = runCatching { JSONObject(value).getJSONObject("locations").getInt(SavedBookmarks.PDF_TEXT_OFFSET) }.getOrNull()
                offset != null && offset > 0 && abs(offset - bookmarkOffset) <= 80
            }
        }

        scenario!!.recreate()
        waitForReader(book)
        awaitTextPage(2)
        waitFor("the same-page bookmarked passage survives recreation") {
            onActivity { (it.pdfSessionForTests?.textOffset ?: 0).let { offset -> offset > 0 && abs(offset - bookmarkOffset) <= 80 } }
        }
        openControls()
        clickTag("pdf-previous")
        waitFor("Previous returns to the first screenful") { textAnchor() == first }
        openControls()
        clickLabel("Bookmarks")
        clickLabel("Bookmark")
        waitFor("the bookmark restores from another physical page") {
            textAnchor().let { it.first == second.first && abs(it.second - bookmarkOffset) <= 80 }
        }

        val previousScale = onActivity { it.pdfSessionForTests!!.textScale }
        openControls()
        clickTag("pdf-text-size-increase")
        waitFor("resizing retains the bookmarked passage") {
            onActivity { it.pdfSessionForTests!!.textScale > previousScale } &&
                textAnchor().let { it.first == second.first && abs(it.second - bookmarkOffset) <= 120 }
        }

        openControls()
        clickTag("pdf-text-toggle")
        waitFor("original PDF pages return") { onActivity { it.pdfSessionForTests?.let { session -> !session.textMode && session.textPage == 2 } == true } }
        waitFor("the text surface is removed") { findTag("pdf-text-content") == null }
    }

    @Test fun imageOnlyPageStopsTextFlowAndOffersOriginalPages() {
        val book = importFixture(imageBoundary = true)
        onActivity { it.openBookForTests(book) }
        waitForReader(book)
        openControls()
        clickTag("pdf-text-toggle")
        awaitTextPage(1)
        waitFor("the first short PDF page") { "Generated page 1 line 2" in visibleText() }
        val first = textAnchor()
        val firstText = visibleText()
        assertFalse("the page after the image-only page is not silently included", "Generated page 3 line 0" in visibleText())
        openControls()
        clickTag("pdf-next")
        waitFor("the image-only page offers original pages") {
            onActivity { it.pdfSessionForTests?.textPage == 2 } &&
                findNode { it.text?.toString() == "Original pages" } != null
        }
        assertFalse("the later text page was not skipped to", "Generated page 3 line 0" in visibleText())
        openControls()
        clickTag("pdf-previous")
        awaitTextPage(1)
        waitFor("Previous returns to the same screenful before the image-only page") {
            textAnchor() == first && visibleText() == firstText
        }
        openControls()
        clickTag("pdf-next")
        waitFor("Next returns to the image-only page") {
            onActivity { it.pdfSessionForTests?.textPage == 2 } &&
                findNode { it.text?.toString() == "Original pages" } != null
        }
    }

    @Test fun everyGeneratedLineIsReachedBySwipingThroughScreenfuls() {
        val book = importFixture()
        onActivity { it.openBookForTests(book) }
        waitForReader(book)
        openControls()
        clickTag("pdf-text-toggle")
        awaitTextPage(1)
        waitFor("the first generated line") { "P1L00" in visibleText() }
        val seen = mutableSetOf<String>()
        val marker = Regex("(?:P[12]L|Z[12]E)[0-9]{2}")
        repeat(40) {
            val before = visibleText()
            seen += marker.findAll(before).map { it.value }.toList()
            if (seen.contains("Z2E55")) return@repeat
            val anchor = textAnchor()
            dragText(0.8f, 0.2f, 0.6f, 0.6f)
            waitFor("the next visible screenful") { textAnchor() != anchor && visibleText() != before }
        }
        val expected = (0..2).flatMap { listOf("P1L%02d".format(it), "Z1E%02d".format(it)) } +
            (0..55).flatMap { listOf("P2L%02d".format(it), "Z2E%02d".format(it)) }
        assertTrue("every generated line and its end are reached", seen.containsAll(expected))
    }

    private fun importFixture(imageBoundary: Boolean = false): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loading") { !onActivity { it.busyForTests } }
        val file = File(fixtures, "generated-boundary.pdf")
        val document = PdfDocument()
        try {
            // A short first page and long second page exercise a single screenful that
            // crosses the physical PDF boundary, then several turns on the second page.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 7f }
            for (pageNumber in 1..if (imageBoundary) 3 else 2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumber).create())
                page.canvas.drawColor(Color.WHITE)
                if (imageBoundary && pageNumber == 2) {
                    page.canvas.drawRect(100f, 100f, 400f, 400f, paint)
                    document.finishPage(page)
                    continue
                }
                page.canvas.save()
                page.canvas.clipRect(72f, 72f, 540f, 720f)
                for (line in 0 until if (pageNumber == 1) 3 else 56) {
                    page.canvas.drawText(
                        "P${pageNumber}L${line.toString().padStart(2, '0')} Generated page $pageNumber line $line: a calm sample passage with distinct words, punctuation and reading context. Z${pageNumber}E${line.toString().padStart(2, '0')}",
                        88f, 105f + line * 10f, paint,
                    )
                }
                paint.textSize = 24f
                page.canvas.drawText(SEAM_TEXT, 90f, if (pageNumber == 1) 724f else 76f, paint)
                paint.textSize = 7f
                page.canvas.restore()
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return runBlocking { onActivity { it.libraryForTests }.import(Uri.fromFile(file)).first }
    }

    private fun waitForReader(book: BookRecord) {
        waitFor("PDF reader attaches") {
            onActivity { it.pdfSessionForTests?.let { session -> session.book.id == book.id && session.fragment.isAdded } == true }
        }
    }

    private fun awaitTextPage(page: Int) {
        waitFor("text view page $page") {
            onActivity { it.pdfSessionForTests?.let { session ->
                session.textMode && session.textPage == page && (session.textViewState as? PdfTextViewState.Ready)?.page == page
            } == true }
        }
    }

    private fun textAnchor(): Pair<Int?, Int> = onActivity {
        val session = it.pdfSessionForTests!!
        session.textPage to session.textOffset
    }

    private fun visibleText(): String = findTag("pdf-text-content")?.text?.toString().orEmpty()

    /** Send real gestures over the text surface; never touch the owner's main app. */
    private fun dragText(fromX: Float, toX: Float, fromY: Float, toY: Float) {
        val bounds = Rect().also { waitForNode("pdf-text-content").getBoundsInScreen(it) }
        fun send(down: Long, action: Int, fraction: Float) {
            val x = bounds.left + bounds.width() * (fromX + (toX - fromX) * fraction)
            val y = bounds.top + bounds.height() * (fromY + (toY - fromY) * fraction)
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }.also { event -> try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
        }
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) { SystemClock.sleep(20); send(down, MotionEvent.ACTION_MOVE, step / 12f) }
        send(down, MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    private fun openControls() {
        if (!onActivity { it.pdfSessionForTests!!.controlsVisible }) clickTag("pdf-show-controls")
        waitForNode("pdf-text-toggle")
    }

    private fun clickTag(tag: String) = clickNode(waitForNode(tag), tag)
    private fun clickLabel(label: String) {
        waitFor("control $label") { findNode { it.text?.toString() == label } != null }
        clickNode(findNode { it.text?.toString() == label }!!, label)
    }
    private fun clickNode(node: AccessibilityNodeInfo, description: String) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync(); return
            }
            current = current.parent
        }
        throw AssertionError("Could not click $description")
    }
    private fun waitForNode(tag: String): AccessibilityNodeInfo {
        waitFor("control $tag") { findTag(tag) != null }
        return findTag(tag)!!
    }
    private fun findTag(tag: String): AccessibilityNodeInfo? = findNode {
        it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith("/$tag") == true
    }
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        val roots = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .onEach { it.refresh() }.filter { it.packageName?.toString() == target.packageName }
        fun descend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { descend(it)?.let { found -> return found } }
            return null
        }
        return roots.firstNotNullOfOrNull(::descend)
    }
    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }
    private fun waitFor(description: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { failure = error }
            SystemClock.sleep(80)
        }
        throw AssertionError("Timed out waiting for $description", failure)
    }

    private companion object { const val SEAM_TEXT = "Generated line across pages" }
}
