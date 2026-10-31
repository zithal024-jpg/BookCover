package com.bookcover.diagnostic.reader

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.streak.ReadingCredit
import com.bookcover.diagnostic.streak.ReadingTimeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import org.readium.adapter.pdfium.navigator.PdfiumSettings
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError

/** A local PDF reader. Positions remain Readium locators so reopening and bookmarks are portable. */
@OptIn(ExperimentalReadiumApi::class)
internal class PdfSession(
    private val activity: FragmentActivity,
    val book: BookRecord,
    val publication: Publication,
    private val library: BookLibrary,
    private val report: (String) -> Unit,
) {
    var controlsVisible by mutableStateOf(false)
    var progress by mutableDoubleStateOf(book.progression)
        private set
    var currentLocator: Locator? = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        private set
    val restorationLocator: Locator? get() = currentLocator
    private var closed = false
    private var locationJob: Job? = null
    private var readingTimeJob: Job? = null
    private val pageKeys = ReadingKeyPolicy()
    private val readingTime = ReadingTimeTracker()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val positions = LatestValueSaver<Locator>(saveScope, quietMillis = 1_500) { locator ->
        try { library.updatePosition(book.id, locator) }
        catch (error: Exception) { DiagnosticLog.record("PDF reading position save failed: ${error.javaClass.simpleName}") }
    }
    private val readingLifecycle = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) { record(readingTime.pause(SystemClock.elapsedRealtime())) }
        override fun onResume(owner: LifecycleOwner) {
            if (fragment.isAdded && !closed) readingTime.resume(SystemClock.elapsedRealtime())
        }
    }
    val fragment: PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>

    init {
        val engine = PdfiumEngineProvider(listener = object : PdfiumEngineProvider.Listener {
            override fun onConfigurePdfView(configurator: PDFView.Configurator) {
                // Readium's PDFium adapter leaves the viewer at its width-fit default, even
                // when its preferences request contain. Fit every page to both dimensions.
                configurator.pageFitPolicy(FitPolicy.BOTH).fitEachPage(true).autoSpacing(true)
                // AndroidPdfViewer otherwise launches external URLs in another app by default.
                configurator.linkHandler { event ->
                    val link = event.link
                    if (!link.uri.isNullOrBlank()) {
                        report("This link points outside the book. BookCover does not open web pages.")
                    } else {
                        link.destPageIdx?.let(::goToPage)
                    }
                }
            }
        })
        val factory = PdfNavigatorFactory(publication, engine).createFragmentFactory(
            initialLocator = currentLocator,
            initialPreferences = PdfiumPreferences(fit = Fit.CONTAIN, scroll = false, scrollAxis = Axis.HORIZONTAL),
            listener = object : PdfNavigatorFragment.Listener {
                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    report("This PDF could not be displayed. Try another copy of the file.")
                    DiagnosticLog.record("PDF resource load failed; resource path omitted")
                }
            },
        )
        @Suppress("UNCHECKED_CAST")
        fragment = factory.instantiate(activity.classLoader, PdfNavigatorFragment::class.java.name)
            as PdfNavigatorFragment<PdfiumSettings, PdfiumPreferences>
        activity.lifecycle.addObserver(readingLifecycle)
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                readerActive()
                controlsVisible = !controlsVisible
                return true
            }
        })
    }

    fun attach(container: FragmentContainerView) {
        if (closed || activity.isDestroyed || fragment.isAdded || activity.supportFragmentManager.isStateSaved) return
        activity.supportFragmentManager.beginTransaction().replace(container.id, fragment, "bookcover-pdf").commitNow()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) readingTime.resume(SystemClock.elapsedRealtime())
        readingTimeJob = activity.lifecycleScope.launch {
            while (true) { delay(30_000); record(readingTime.collect(SystemClock.elapsedRealtime())) }
        }
        locationJob = activity.lifecycleScope.launch {
            fragment.currentLocator.collect { locator ->
                if (currentLocator?.locations?.position != locator.locations.position) readerActive()
                currentLocator = locator
                progress = locator.locations.totalProgression ?: progress
                positions.submit(locator)
            }
        }
    }

    fun next() { readerActive(); if (fragment.isAdded) fragment.goForward(animated = false); controlsVisible = false }
    fun previous() { readerActive(); if (fragment.isAdded) fragment.goBackward(animated = false); controlsVisible = false }
    private fun goToPage(page: Int) {
        val locator = currentLocator ?: return
        if (page >= 0 && fragment.isAdded) {
            readerActive()
            fragment.go(locator.copy(locations = locator.locations.copy(
                fragments = listOf("page=${page + 1}"), position = page + 1)), animated = false)
        }
    }
    fun goToBookmark(value: String) {
        val locator = runCatching { Locator.fromJSON(JSONObject(value)) }.getOrNull() ?: return
        readerActive()
        if (fragment.isAdded) fragment.go(locator, animated = false)
        controlsVisible = false
    }
    suspend fun bookmarkLocator(): Locator? = currentLocator

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.readingPageKey() ?: return false
        readerActive()
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val ready = !closed && fragment.isAdded && !controlsVisible &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()
        return pageKeys.handle(key, down = event.action == KeyEvent.ACTION_DOWN,
            repeated = event.repeatCount > 0, canNavigate = ready && event.hasNoModifiers(),
            volumeEnabled = activity.getSharedPreferences("reader", 0).getBoolean("volumeKeys", false),
            rightToLeft = fragment.isAdded && fragment.settings.value.readingProgression == ReadingProgression.RTL) { turn ->
            when (turn) { ReadingPageTurn.NEXT -> next(); ReadingPageTurn.PREVIOUS -> previous() }
        }
    }

    fun viewportChanging(onSettled: () -> Unit) { onSettled() }
    fun flushPosition() { restorationLocator?.let(positions::submit); positions.flush() }
    private fun readerActive() = record(readingTime.activity(SystemClock.elapsedRealtime()))
    private fun record(credit: ReadingCredit?) {
        if (credit == null || credit.millis <= 0) return
        val end = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - credit.endElapsed)
        saveScope.launch(Dispatchers.IO) {
            try { library.readingDays.add(end - credit.millis, end) }
            catch (error: Exception) { DiagnosticLog.record("Reading time save failed: ${error.javaClass.simpleName}") }
        }
    }
    fun close() {
        if (closed) return
        record(readingTime.pause(SystemClock.elapsedRealtime()))
        closed = true
        readingTimeJob?.cancel(); locationJob?.cancel()
        activity.lifecycle.removeObserver(readingLifecycle)
        flushPosition()
        if (!activity.supportFragmentManager.isDestroyed && fragment.isAdded) {
            runCatching { activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
        publication.close()
    }
}
