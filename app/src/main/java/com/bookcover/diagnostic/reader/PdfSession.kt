package com.bookcover.diagnostic.reader

import android.os.SystemClock
import android.os.Build
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.streak.ReadingCredit
import com.bookcover.diagnostic.streak.ReadingTimeTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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

internal sealed interface PdfTextViewState {
    data object Loading : PdfTextViewState
    data object Unavailable : PdfTextViewState
    data object Empty : PdfTextViewState
    data object Failed : PdfTextViewState
    data class Ready(val page: Int, val text: String) : PdfTextViewState
}

/** Readium resolves the PDF page fragment before the position when both are present. */
private fun pdfPage(locator: Locator?): Int? {
    if (locator == null) return null
    return locator.locations.fragments.asSequence()
        .flatMap { it.removePrefix("#").split('&').asSequence() }
        .firstNotNullOfOrNull { part -> part.takeIf { it.startsWith("page=") }
            ?.substringAfter('=')?.toIntOrNull()?.takeIf { it > 0 } }
        ?: locator.locations.position?.takeIf { it > 0 }
}

private fun pdfTextOffset(locator: Locator?): Int? {
    val value = (locator?.locations?.otherLocations?.get(SavedBookmarks.PDF_TEXT_OFFSET) as? Number)
        ?.toDouble() ?: return null
    return value.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
}

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
    private val readerPreferences = activity.getSharedPreferences("reader", 0)
    private val textModeKey = "pdfTextMode:${book.id}"
    var textMode by mutableStateOf(readerPreferences.getBoolean(textModeKey, false))
        private set
    var textScale by mutableDoubleStateOf(ReadingTextSize.scale(
        ReadingTextSize.percent(readerPreferences.getFloat("fontScale", 1f).toDouble())))
        private set
    var textViewState: PdfTextViewState by mutableStateOf(PdfTextViewState.Loading)
        private set
    var textOffset by mutableIntStateOf(pdfTextOffset(currentLocator) ?: 0)
        private set
    var textAnchorRevision by mutableIntStateOf(0)
        private set
    private var textAnchorPage: Int? = pdfPage(currentLocator)
    private var hasTextAnchor = pdfTextOffset(currentLocator) != null
    private var pendingTextAnchor: Pair<Int, Int>? = null
    private var textSource: PdfTextPageSource? = null
    private var textJob: Job? = null
    private var textRequest = 0
    private var textRequestedPage: Int? = null
    val textPage: Int? get() = pdfPage(currentLocator)
    val textRightToLeft: Boolean get() = fragment.isAdded &&
        fragment.settings.value.readingProgression == ReadingProgression.RTL
    val restorationLocator: Locator? get() = currentLocator?.let { locator ->
        val extras = locator.locations.otherLocations - SavedBookmarks.PDF_TEXT_OFFSET
        val locations = if (hasTextAnchor && textAnchorPage == pdfPage(locator))
            extras + (SavedBookmarks.PDF_TEXT_OFFSET to textOffset) else extras
        locator.copy(locations = locator.locations.copy(otherLocations = locations))
    }
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
                val previousPage = pdfPage(currentLocator)
                val page = pdfPage(locator)
                if (previousPage != page) {
                    readerActive()
                    val pending = pendingTextAnchor?.takeIf { it.first == page }
                    textOffset = pending?.second ?: 0
                    hasTextAnchor = pending != null || textMode
                    if (pending != null) pendingTextAnchor = null
                    textAnchorPage = page
                    textAnchorRevision++
                }
                currentLocator = locator
                progress = locator.locations.totalProgression ?: progress
                restorationLocator?.let(positions::submit)
                if (textMode && page != null && (previousPage != page || textRequestedPage != page))
                    loadTextPage(page)
            }
        }
    }

    fun next() { pendingTextAnchor = null; readerActive(); if (fragment.isAdded) fragment.goForward(animated = false); controlsVisible = false }
    fun previous() { pendingTextAnchor = null; readerActive(); if (fragment.isAdded) fragment.goBackward(animated = false); controlsVisible = false }
    private fun goToPage(page: Int) {
        val locator = currentLocator ?: return
        if (page >= 0 && fragment.isAdded) {
            pendingTextAnchor = null
            readerActive()
            fragment.go(locator.copy(locations = locator.locations.copy(
                fragments = listOf("page=${page + 1}"), position = page + 1)), animated = false)
        }
    }
    fun goToBookmark(value: String) {
        val locator = runCatching { Locator.fromJSON(JSONObject(value)) }.getOrNull() ?: return
        readerActive()
        if (!fragment.isAdded) return
        val target = pdfPage(locator)
        val offset = pdfTextOffset(locator)
        if (!fragment.go(locator, animated = false)) return
        pendingTextAnchor = null
        if (target != null && offset != null) {
            if (target == textPage) {
                textAnchorPage = target
                textOffset = offset
                hasTextAnchor = true
                textAnchorRevision++
            } else pendingTextAnchor = target to offset
            if (!textMode) changeTextMode(true)
            if (target == textPage) restorationLocator?.let(positions::submit)
        } else if (target == textPage) {
            textOffset = 0
            hasTextAnchor = textMode
            textAnchorRevision++
            restorationLocator?.let(positions::submit)
        }
        controlsVisible = false
    }
    suspend fun bookmarkLocator(): Locator? {
        val locator = restorationLocator ?: return null
        if (textMode) return locator
        return locator.copy(locations = locator.locations.copy(
            otherLocations = locator.locations.otherLocations - SavedBookmarks.PDF_TEXT_OFFSET))
    }

    fun changeTextMode(enabled: Boolean) {
        if (closed || textMode == enabled) return
        textMode = enabled
        readerPreferences.edit().putBoolean(textModeKey, enabled).apply()
        controlsVisible = false
        if (enabled) textPage?.let { page ->
            if (textAnchorPage != page) textOffset = 0
            textAnchorPage = page
            hasTextAnchor = true
            loadTextPage(page)
        } else {
            pendingTextAnchor = null
            textRequest++
            textRequestedPage = null
            textJob?.cancel()
        }
        restorationLocator?.let(positions::submit)
    }

    fun changeTextScale(value: Double) {
        val scale = ReadingTextSize.scale(ReadingTextSize.percent(value))
        if (scale == textScale) return
        textAnchorRevision++ // Remap the same character after text reflows.
        textScale = scale
        readerPreferences.edit().putFloat("fontScale", scale.toFloat()).apply()
    }

    fun updateTextOffset(page: Int, offset: Int) {
        if (!textMode || page != textPage || offset < 0 ||
            (textViewState as? PdfTextViewState.Ready)?.page != page) return
        readerActive()
        textAnchorPage = page
        hasTextAnchor = true
        if (textOffset != offset) {
            textOffset = offset
            restorationLocator?.let(positions::submit)
        }
    }

    fun textActivity() = readerActive()

    private fun loadTextPage(page: Int) {
        if (closed || !textMode || page < 1) return
        val request = ++textRequest
        textRequestedPage = page
        textJob?.cancel()
        textViewState = PdfTextViewState.Loading
        textJob = activity.lifecycleScope.launch {
            try {
                if (Build.VERSION.SDK_INT < 35) {
                    textViewState = PdfTextViewState.Unavailable
                    return@launch
                }
                val source = textSource ?: PdfTextPageSource.open(library.bookFile(book))?.also {
                    if (closed) it.close() else textSource = it
                }
                if (closed || request != textRequest || !textMode) return@launch
                if (source == null) { textViewState = PdfTextViewState.Unavailable; return@launch }
                if (page > source.pageCount) { textViewState = PdfTextViewState.Failed; return@launch }
                val content = source.readPage(page - 1)
                if (closed || request != textRequest || !textMode || textPage != page) return@launch
                val text = content.blocks.joinToString("") { it.text }
                textViewState = if (text.isBlank()) PdfTextViewState.Empty else PdfTextViewState.Ready(page, text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && request == textRequest && textMode) textViewState = PdfTextViewState.Failed
            }
        }
    }

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
        textRequest++; textJob?.cancel(); textSource?.close(); textSource = null
        activity.lifecycle.removeObserver(readingLifecycle)
        flushPosition()
        if (!activity.supportFragmentManager.isDestroyed && fragment.isAdded) {
            runCatching { activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
        publication.close()
    }
}
