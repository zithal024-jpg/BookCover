package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.reader.PdfSession
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

    @Test fun textViewRecoversClippedLinesAndRestoresPageScrollAndBookmark() {
        val book = importFixture()
        onActivity { it.openBookForTests(book) }
        waitForReader(book)
        waitFor("the first PDF page") { onActivity { it.pdfSessionForTests?.textPage == 1 } }
        openControls()
        clickTag("pdf-text-toggle")
        awaitTextPage(1)
        assertTrue("the full first-page generated line is extracted", readyText().contains(SEAM_TEXT))
        assertNotNull("extracted text is presented to the user", waitForNode("pdf-text-content"))

        openControls()
        clickTag("pdf-next")
        awaitTextPage(2)
        assertTrue("the full second-page generated line is extracted", readyText().contains(SEAM_TEXT))
        val scroll = waitForNode("pdf-text-scroll")
        assertTrue("the extracted page is scrollable", scroll.isScrollable)
        assertTrue("scrolling the extracted page succeeds", scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        waitFor("text position advances") { onActivity { (it.pdfSessionForTests?.textOffset ?: 0) > 0 } }

        openControls()
        clickLabel("Bookmarks")
        clickLabel("Add bookmark")
        clickTag("bookmark-save")
        waitFor("the text bookmark is saved") {
            onActivity { it.libraryForTests.book(book.id)?.bookmarks?.isNotEmpty() == true }
        }
        val bookmark = onActivity { it.libraryForTests.book(book.id)!!.bookmarks.single() }
        val bookmarkOffset = JSONObject(bookmark).getJSONObject("locations").getInt(SavedBookmarks.PDF_TEXT_OFFSET)
        assertTrue("the text bookmark stores the scrolled character offset", bookmarkOffset > 0)

        // Move farther down the same page, then return to the bookmark without a page change.
        // That jump must persist even if the user does nothing else before the app is stopped.
        clickLabel("Back to controls")
        clickLabel("Read")
        assertTrue("a second scroll succeeds", waitForNode("pdf-text-scroll").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        waitFor("the same page scrolls past the bookmark") {
            onActivity { (it.pdfSessionForTests?.textOffset ?: 0) > bookmarkOffset }
        }
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
        awaitTextPage(1)
        openControls()
        clickLabel("Bookmarks")
        clickLabel("Bookmark")
        awaitTextPage(2)
        waitFor("the saved passage also restores from another page") {
            onActivity { (it.pdfSessionForTests?.textOffset ?: 0).let { offset -> offset > 0 && abs(offset - bookmarkOffset) <= 80 } }
        }

        // Exercise an interrupted cross-page bookmark jump in one main-thread turn.
        // An explicit subsequent page turn must discard the pending text offset,
        // including if its target later becomes visible.
        openControls()
        clickTag("pdf-previous")
        awaitTextPage(1)
        onActivity { activity ->
            val session = activity.pdfSessionForTests!!
            session.goToBookmark(bookmark)
            session.previous()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500) // Let the navigator deliver any already-queued page notification.
        if (onActivity { it.pdfSessionForTests?.textPage == 1 }) onActivity { it.pdfSessionForTests!!.next() }
        awaitTextPage(2)
        waitFor("an interrupted bookmark does not move the later page to its old offset") {
            onActivity { it.pdfSessionForTests?.textOffset == 0 }
        }

        openControls()
        clickTag("pdf-text-toggle")
        waitFor("original PDF pages return") { onActivity { it.pdfSessionForTests?.let { session -> !session.textMode && session.textPage == 2 } == true } }
        waitFor("the text surface is removed") { findTag("pdf-text-content") == null }
    }

    private fun importFixture(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loading") { !onActivity { it.busyForTests } }
        val file = File(fixtures, "generated-boundary.pdf")
        val document = PdfDocument()
        try {
            // Dense but legible generated lines fit inside the physical PDF page. In the
            // larger text view they wrap to several screens even on an unfolded Fold,
            // so two independent accessibility scrolls can exercise bookmark restoration.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 7f }
            for (pageNumber in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumber).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save()
                page.canvas.clipRect(72f, 72f, 540f, 720f)
                for (line in 0 until 56) {
                    page.canvas.drawText(
                        "Generated page $pageNumber line $line: a calm sample passage with distinct words, punctuation and reading context.",
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

    private fun readyText(): String = onActivity {
        (it.pdfSessionForTests!!.textViewState as PdfTextViewState.Ready).text
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
