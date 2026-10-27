package com.bookcover.diagnostic.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import android.os.SystemClock
import android.view.View
import android.view.KeyEvent
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError

@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
internal class EpubSession(
    private val activity: FragmentActivity,
    val book: BookRecord,
    val publication: Publication,
    private val library: BookLibrary,
    initialFontScale: Double,
    initialTheme: String,
    private val report: (String) -> Unit,
    initialFontId: String = "serif",
    initialEdgeTaps: Boolean = false,
) {
    val search = BookSearchController(activity.lifecycleScope, publication)
    private val readingPace = ReadingPace()
    private val chapters = ReadingChapters(publication)
    val contentsEntries get() = chapters.entries
    var chapterStatus by mutableStateOf<ChapterStatus?>(null)
        private set
    private var visibleControls by mutableStateOf(false)
    var controlsVisible: Boolean
        get() = visibleControls
        set(value) {
            if (visibleControls != value) readingPace.pause()
            visibleControls = value
        }
    var progress by mutableDoubleStateOf(book.progression)
    var fontScale by mutableDoubleStateOf(ReadingTextSize.percent(initialFontScale) / 100.0)
        private set
    var fontId by mutableStateOf(ReadingFont.fromId(initialFontId).id)
        private set
    var edgeTaps by mutableStateOf(initialEdgeTaps)
        private set
    var typographyUpdating by mutableStateOf(false)
        private set
    private val readerPreferences = activity.getSharedPreferences("reader", 0)
    var volumeKeys by mutableStateOf(readerPreferences.getBoolean("volumeKeys", false))
        private set
    private val pageKeys = ReadingKeyPolicy()
    private var keyTurnJob: Job? = null
    var comfort by mutableStateOf(readerPreferences.readingComfort())
        private set
    val supportsTypography = (publication.metadata.layout ?: Layout.REFLOWABLE) == Layout.REFLOWABLE
    var themeName by mutableStateOf(initialTheme)
        private set
    var currentLocator: Locator? = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        private set
    private var locationJob: Job? = null
    private var chapterJob: Job? = null
    private var chapterResource: Url? = null
    private var preferredContentsIndex: Int? = null
    private var pendingContentsIndex: Int? = null
    private val readingLifecycle = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) { readingPace.pause(); search.dismiss() }
        override fun onResume(owner: LifecycleOwner) { readingPace.pause() }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            readingPace.pause()
            chapters.invalidateLayout()
            currentLocator?.let(::refreshChapter)
        }
    }
    private var typographyJob: Job? = null
    private var typographyAnchor: Locator? = null
    private var typographyAnchorInvalidated = false
    private var closed = false
    // Not tied to the activity's lifecycle, so a pending position still saves while it is destroyed.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val positions = LatestValueSaver<Locator>(saveScope, quietMillis = 1_500) { locator ->
        try { library.updatePosition(book.id, locator) }
        catch (error: Exception) { DiagnosticLog.record("Reading position save failed: ${error.javaClass.simpleName}") }
    }
    val fragment: EpubNavigatorFragment

    init {
        val factory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = currentLocator,
            initialPreferences = preferences(),
            configuration = EpubNavigatorFragment.Configuration().apply {
                useReadiumCssFontSize = true
                // Readium caps lines at 40rem, which grows with the text size and narrows the margins on
                // the wide inner screen. A pixel cap keeps the margins fixed so text reflows. 800px is wider
                // than Readium's 640px default at 100%, at the owner's request for narrower side margins.
                // (RsProperties.maxLineLength accepts only rem, so the value goes in as an override.)
                readiumCssRsProperties = RsProperties(overrides = mapOf("--RS__maxLineLength" to "800px"))
            },
            listener = object : EpubNavigatorFragment.Listener {
                override fun onJumpToLocator(locator: Locator) {
                    readingPace.pause()
                    val fragments = locator.locations.fragments
                    preferredContentsIndex = contentsEntries.indexOfFirst { entry ->
                        entry.link.url().removeFragment() == locator.href.removeFragment() &&
                            (entry.link.url().fragment?.let { it in fragments } == true ||
                                (fragments.isEmpty() && entry.title == locator.title))
                    }.takeIf { it >= 0 }
                    currentLocator?.let(::refreshChapter)
                }
                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    report("This link points to a website. BookCover does not open web pages.")
                }
                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    report("A chapter resource could not be displayed. Other chapters may still work.")
                    DiagnosticLog.record("EPUB resource load failed; resource path omitted")
                }
            },
        )
        fragment = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        activity.lifecycle.addObserver(readingLifecycle)
        fragment.addInputListener(object : InputListener {
            override fun onDrag(event: DragEvent): Boolean {
                // Observe intent only: native Readium/ViewPager remains the sole swipe handler.
                // A user who navigates during reflow must not be pulled back by an old anchor.
                if (event.type == DragEvent.Type.Start) {
                    pendingContentsIndex = null
                    if (typographyUpdating) typographyAnchorInvalidated = true
                }
                return false
            }
            override fun onTap(event: TapEvent): Boolean {
                val view = fragment.view ?: return false
                // Readium filters interactive links, active selections and drag gestures before
                // this callback. Keep its native touch recognizers and selection handling intact.
                // Passive artwork must accept edge taps too: manga pages may contain no prose.
                val target = event.targetElement?.content
                val protectedTarget = target != null && target !is Content.ImageElement && target !is Content.SvgElement
                when (readingTapAction(event.point.x, event.point.y, view.width.toFloat(), view.height.toFloat(),
                    view.resources.displayMetrics.density, edgeTaps, controlsVisible, typographyUpdating, protectedTarget,
                    // Use the same resolved direction as Readium's native navigation, including
                    // language inference when the publication omits page-progression-direction.
                    rightToLeft = fragment.settings.value.readingProgression == ReadingProgression.RTL)) {
                    ReadingTapAction.CONTROLS -> {
                        controlsVisible = !controlsVisible
                        DiagnosticLog.record("EPUB controls ${if (controlsVisible) "revealed" else "hidden"}; same host activity")
                    }
                    ReadingTapAction.PREVIOUS -> { pendingContentsIndex = null; fragment.goBackward(animated = true); DiagnosticLog.record("EPUB edge completed tap: backward") }
                    ReadingTapAction.NEXT -> { pendingContentsIndex = null; fragment.goForward(animated = true); DiagnosticLog.record("EPUB edge completed tap: forward") }
                    ReadingTapAction.NONE -> return false
                }
                return true
            }
        })
    }

    fun attach(container: FragmentContainerView) {
        if (closed || activity.isDestroyed || fragment.isAdded || activity.supportFragmentManager.isStateSaved) return
        activity.supportFragmentManager.beginTransaction().replace(container.id, fragment, "bookcover-epub").commitNow()
        fragment.view?.addOnLayoutChangeListener(layoutListener)
        locationJob = activity.lifecycleScope.launch {
            fragment.currentLocator.collect { locator ->
                currentLocator = locator
                progress = locator.locations.totalProgression ?: progress
                if (!typographyUpdating) positions.submit(locator)
                refreshChapter(locator)
                DiagnosticLog.record("EPUB position=${(progress * 100).toInt()}%; same host activity")
            }
        }
    }

    private fun refreshChapter(locator: Locator) {
        if (closed) return
        if (chapterResource != locator.href) {
            chapterResource = locator.href
            chapterStatus = null // Never leave another resource's chapter marker visible on failure.
        }
        chapterJob?.cancel()
        chapterJob = activity.lifecycleScope.launch {
            try {
                delay(80) // Coalesce layout/location notifications; never delay native navigation.
                if (typographyUpdating || !fragment.isAdded) return@launch
                val resolved = withTimeoutOrNull(1_500) { chapters.resolve(locator, fragment, supportsTypography, preferredContentsIndex) } ?: return@launch
                if (closed || currentLocator != locator || typographyUpdating) return@launch
                val pending = pendingContentsIndex
                if (pending != null && contentsEntries[pending].link.url().removeFragment() == locator.href.removeFragment()) {
                    pendingContentsIndex = null
                    chapters.locatorForContents(pending)?.let { destination ->
                        // A new resource must render before its subsection geometry is available.
                        // Refine that explicit jump once, through Readium's supported locator API.
                        fragment.go(destination, animated = false)
                        preferredContentsIndex = pending
                        currentLocator?.let(::refreshChapter)
                        return@launch
                    }
                }
                readingPace.observe(resolved.globalPoint, SystemClock.elapsedRealtime(),
                    !controlsVisible && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                chapterStatus = ChapterStatus(resolved.title, resolved.progression,
                    resolved.globalEnd?.let { readingPace.remainingMinutes(resolved.globalPoint, it) }, resolved.currentTocIndex)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing TOC/geometry must never interrupt reading or position saving. */ }
        }
    }

    fun goToContents(index: Int) {
        contentsEntries.getOrNull(index)?.let {
            readingPace.pause(); preferredContentsIndex = index
            val destination = chapters.locatorForContents(index)
            pendingContentsIndex = if (destination == null) index else null
            if (destination != null) fragment.go(destination, animated = false) else fragment.go(it.link, animated = false)
            preferredContentsIndex = index
            currentLocator?.let(::refreshChapter)
        }
    }

    fun goToBookmark(value: String) {
        val locator = runCatching { Locator.fromJSON(JSONObject(value)) }.getOrNull() ?: return
        readingPace.pause()
        pendingContentsIndex = null
        fragment.go(locator, animated = false)
    }

    fun openSearch() {
        if (closed || typographyUpdating) return
        controlsVisible = true
        search.open()
    }

    /** Keep Readium's text locator intact; normal currentLocator collection saves the destination. */
    fun goToSearchResult(result: BookSearchResult): Boolean {
        if (closed || !search.isCurrent(result)) return false
        if (typographyUpdating || !fragment.isAdded) {
            search.navigationFailed()
            return false
        }
        readingPace.pause()
        pendingContentsIndex = null
        preferredContentsIndex = null
        val navigated = try { fragment.go(result.locator, animated = false) }
        catch (_: Exception) { false }
        if (navigated) {
            search.dismiss()
            controlsVisible = false
        } else search.navigationFailed()
        return navigated
    }

    suspend fun bookmarkLocator(): Locator? {
        val locator = withTimeoutOrNull(1_000) { fragment.visibleReadingAnchor() } ?: currentLocator ?: return null
        return locator.copy(title = chapterStatus?.title ?: locator.title)
    }

    /** Text size is independent of publisher styles; the book's own formatting stays as chosen. */
    fun changeFont(value: Double) {
        if (!supportsTypography) return
        fontScale = ReadingTextSize.percent(value) / 100.0
        applyTypography()
    }

    fun changeFontFamily(id: String) {
        if (!supportsTypography) return
        comfort = comfort.copy(publisherStyles = false)
        fontId = ReadingFont.fromId(id).id
        applyTypography()
    }

    fun changePublisherStyles(enabled: Boolean) {
        if (!supportsTypography) return
        comfort = comfort.copy(publisherStyles = enabled)
        applyTypography()
    }

    fun changeLineSpacing(value: Double) {
        if (!supportsTypography) return
        comfort = comfort.copy(publisherStyles = false, lineSpacing = ReadingComfort.normalizedLineSpacing(value))
        applyTypography()
    }

    fun changePageMargins(id: String) {
        if (!supportsTypography) return
        comfort = comfort.copy(publisherStyles = false, margins = ReadingMargins.fromId(id))
        applyTypography()
    }

    fun changeTextAlignment(id: String) {
        if (!supportsTypography) return
        comfort = comfort.copy(publisherStyles = false, alignment = ReadingAlignment.fromId(id))
        applyTypography()
    }

    private fun applyTypography() {
        if (closed || !fragment.isAdded) return
        readingPace.pause()
        chapters.invalidateLayout()
        // Persist the selection immediately, even if the reader leaves before a debounced reflow.
        readerPreferences.saveReadingTypography(comfort, fontScale, fontId)
        typographyJob?.cancel()
        if (!typographyUpdating) typographyAnchorInvalidated = false
        typographyUpdating = true
        typographyJob = activity.lifecycleScope.launch {
            try {
                // Keep the same semantic anchor for the entire adjustment burst, rather than
                // repeatedly capturing a page that has already moved during repagination.
                if (typographyAnchor == null) typographyAnchor = fragment.visibleReadingAnchor() ?: currentLocator
                delay(100)
                val requested = preferences()
                val requestedFont = requested.fontFamily?.name.orEmpty()
                val bookStyles = requested.publisherStyles == true
                fragment.submitPreferences(requested)
                val settled = withTimeoutOrNull(3_000) {
                    fragment.settings.first {
                        it.fontSize == requested.fontSize && it.fontFamily == requested.fontFamily &&
                            it.publisherStyles == bookStyles && it.lineHeight == requested.lineHeight &&
                            it.pageMargins == requested.pageMargins && it.textAlign == requested.textAlign
                    }
                    // A public, read-only query confirms that the asynchronous CSS update and
                    // font loading reached the visible resource. No extra JavaScript bridge.
                    while (fragment.evaluateJavascript("""
                        (function(){var s=getComputedStyle(document.documentElement);
                        return Math.abs(parseFloat(s.getPropertyValue('--USER__fontSize'))-${requested.fontSize!! * 100})<0.1
                        && ${if (bookStyles) "!s.getPropertyValue('--USER__fontFamily').trim()" else "s.getPropertyValue('--USER__fontFamily').indexOf('$requestedFont')>=0"}
                        && s.getPropertyValue('--USER__advancedSettings').indexOf('readium-advanced-${if (bookStyles) "off" else "on"}')>=0
                        && Math.abs(parseFloat(s.getPropertyValue('--USER__pageMargins'))-${requested.pageMargins})<0.01
                        && ${if (bookStyles) "!s.getPropertyValue('--USER__lineHeight').trim() && !s.getPropertyValue('--USER__textAlign').trim()" else "Math.abs(parseFloat(s.getPropertyValue('--USER__lineHeight'))-${requested.lineHeight})<0.01 && s.getPropertyValue('--USER__textAlign').trim()=='${comfort.alignment.css}'"}
                        && document.fonts.status==='loaded';})()
                    """.trimIndent()) != "true") delay(40)
                    delay(160) // Let Readium's resize/pagination observer finish before restoring the block.
                    true
                } ?: false
                val restored = typographyAnchor?.takeIf {
                    !typographyAnchorInvalidated && fragment.currentLocator.value.href == it.href
                }?.let { fragment.go(it, animated = false) } ?: false
                delay(180)
                currentLocator = fragment.currentLocator.value
                currentLocator?.let { positions.submit(it) }
                if (!settled) report("Text settings saved. This book's styles may limit the selected typography.")
                DiagnosticLog.record("EPUB typography CSS reflow; bookStyles=$bookStyles; size=${ReadingTextSize.percent(fontScale)}%; font=$fontId; lineSpacing=${comfort.lineSpacing}; margins=${comfort.margins.id}; alignment=${comfort.alignment.id}; CSS confirmed=$settled; semantic anchor restored=$restored")
                typographyAnchor = null
                typographyUpdating = false
                currentLocator?.let(::refreshChapter)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                typographyAnchor = null; typographyUpdating = false
                report("Text settings could not finish applying. Try again from reading controls.")
                DiagnosticLog.record("Typography update failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun changeEdgeTaps(enabled: Boolean) {
        edgeTaps = enabled
        activity.getSharedPreferences("reader", 0).edit().putBoolean("edgeTaps", enabled).apply()
    }

    fun changeVolumeKeys(enabled: Boolean) {
        volumeKeys = enabled
        readerPreferences.edit().putBoolean("volumeKeys", enabled).apply()
    }

    /** Activity dispatch reaches this before WebView or Android's volume handling. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.readingPageKey() ?: return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        fun ready() = !closed && fragment.isAdded && !controlsVisible && !typographyUpdating &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && activity.hasWindowFocus()
        return pageKeys.handle(key, down = event.action == KeyEvent.ACTION_DOWN,
            repeated = event.repeatCount > 0, canNavigate = ready() && event.hasNoModifiers(),
            volumeEnabled = volumeKeys,
            rightToLeft = fragment.settings.value.readingProgression == ReadingProgression.RTL) { turn ->
            if (keyTurnJob?.isActive != true) keyTurnJob = activity.lifecycleScope.launch {
                // Text selection belongs to the reader's selection toolbar, not page navigation.
                try {
                    if (fragment.currentSelection() == null && ready()) {
                        when (turn) { ReadingPageTurn.NEXT -> next(); ReadingPageTurn.PREVIOUS -> previous() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    DiagnosticLog.record("EPUB key turn unavailable: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    fun changeTheme(value: String) {
        themeName = value
        activity.getSharedPreferences("reader", 0).edit().putString("theme", themeName).apply()
        fragment.submitPreferences(preferences())
    }

    fun next() { pendingContentsIndex = null; fragment.goForward(animated = true); controlsVisible = false }
    fun previous() { pendingContentsIndex = null; fragment.goBackward(animated = true); controlsVisible = false }
    /** Saves a waiting reading position now, e.g. when the app goes to the background. */
    fun flushPosition() = positions.flush()

    fun close() {
        if (closed) return
        closed = true
        search.close()
        locationJob?.cancel()
        chapterJob?.cancel()
        keyTurnJob?.cancel()
        activity.lifecycle.removeObserver(readingLifecycle)
        fragment.view?.removeOnLayoutChangeListener(layoutListener)
        positions.flush()
        typographyJob?.cancel()
        if (!activity.supportFragmentManager.isDestroyed && fragment.isAdded) {
            runCatching { activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
        publication.close()
    }

    private fun preferences() = comfort.epubPreferences(fontScale, fontId, themeName)
}
