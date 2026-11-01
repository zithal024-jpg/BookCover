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
import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import android.view.View
import android.view.KeyEvent
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.streak.ReadingCredit
import com.bookcover.diagnostic.streak.ReadingTimeTracker
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

/** Remembered reading pace; reader settings stay in "reader". Test hosts clear this file. */
internal const val PACE_PREFERENCES = "reading-pace"
// v2: paces saved by the first build were averaged as speeds and skewed fast; they are ignored.
private const val USUAL_PACE_KEY = "v2:usualPositionsPerMinute"

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
    // Local only: this book's pace in book fractions, and the usual pace in Readium positions so a
    // newly opened book starts from it instead of relearning from nothing.
    private val paceMemory = activity.getSharedPreferences(PACE_PREFERENCES, 0)
    private var paceRecalled = false
    // The time from opening the book to the first page turn includes loading and finding one's
    // place, so the opening page is never timed.
    private var openingPage = true
    private val chapters = ReadingChapters(publication)
    val contentsEntries get() = chapters.entries
    var chapterStatus by mutableStateOf<ChapterStatus?>(null)
        private set
    private var visibleControls by mutableStateOf(false)
    var controlsVisible: Boolean
        get() = visibleControls
        set(value) {
            if (visibleControls != value) {
                // Glancing at the controls (e.g. to read the time left) holds the page clock rather than
                // discarding the page being timed.
                val now = SystemClock.elapsedRealtime()
                if (value) readingPace.hold(now) else if (resumed()) readingPace.release(now)
            }
            visibleControls = value
        }
    /**
     * The screen shows a panel over the page (controls, Contents, bookmark editor). Set by
     * `EpubReaderScreen`; swipes are never taken over then, or the panel would be in the snapshot (D12).
     */
    var panelsOpen = false
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
    /** PT-1: paper curl. Off (the default): every page turn is Readium's own, exactly as before PT-1. */
    var paperPageTurn by mutableStateOf(readerPreferences.paperPageTurn())
        private set
    /** Text selection toolbar is open: swipes stay with Readium/Chromium selection handling. */
    private var actionModeActive = false
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
    // Reading streak: counts only while this reader is attached and the activity is resumed.
    private val readingTime = ReadingTimeTracker()
    private var readingTimeJob: Job? = null
    private val viewportContinuity = ReadingViewportContinuity<Locator>()
    private var viewportJob: Job? = null
    private var restoringViewport = false
    private val openedLocator = currentLocator
    private var holdsOpenedPassage = openedLocator != null
    private var viewportSettled: (() -> Unit)? = null
    private val readingLifecycle = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) { readingPace.hold(SystemClock.elapsedRealtime()); record(readingTime.pause(SystemClock.elapsedRealtime())) }
        override fun onResume(owner: LifecycleOwner) {
            if (!controlsVisible) readingPace.release(SystemClock.elapsedRealtime())
            if (fragment.isAdded && !closed) readingTime.resume(SystemClock.elapsedRealtime())
        }
    }
    private fun resumed() = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            // Fold, unfold or rotation mid-turn: drop the snapshot; continuity restores the passage.
            pageTurns.abort()
            readingPace.pause()
            chapters.invalidateLayout()
            currentLocator?.let(::refreshChapter)
            // Hold the reopened semantic passage while late system-bar resizes repaginate it.
            if (holdsOpenedPassage && !typographyUpdating && oldRight - oldLeft > 0 && oldBottom - oldTop > 0)
                viewportContinuity.begin(openedLocator)
            if (viewportContinuity.pending != null) restoreViewportWhenSettled()
        }
    }
    private var typographyJob: Job? = null
    private var typographyAnchor: Locator? = null
    private var typographyAnchorInvalidated = false
    /** Names of the CSS checks the last typography update could not confirm; empty when all passed. */
    internal var unconfirmedTypography = ""
        private set
    private var closed = false
    // Not tied to the activity's lifecycle, so a pending position still saves while it is destroyed.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val positions = LatestValueSaver<Locator>(saveScope, quietMillis = 1_500) { locator ->
        try { library.updatePosition(book.id, locator) }
        catch (error: Exception) { DiagnosticLog.record("Reading position save failed: ${error.javaClass.simpleName}") }
    }
    val fragment: EpubNavigatorFragment
    /** PT-1 page-turn engine: every user turn (swipe, edge tap, button, key) goes through [turnPage]. */
    val pageTurns: PageTurnController

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
                    if (!restoringViewport && !reportIsOpenedPassage(holdsOpenedPassage, locator, openedLocator) { DiagnosticLog.record(it) }) {
                        invalidateViewportAnchor()
                        viewportContinuity.remember(locator, viewportContinuity.generation)
                    }
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
        pageTurns = PageTurnController(activity, publication, fragment,
            conditions = ::turnConditions,
            controlsVisible = { controlsVisible || panelsOpen },
            selectionActive = { actionModeActive },
            rightToLeft = { fragment.settings.value.readingProgression == ReadingProgression.RTL },
            pageColor = { readerBackground(themeName).toArgb() },
            onSwipeTurn = { noteUserTurn(PageTurnSource.SWIPE) },
            onBlockedTap = ::blockedTap,
        )
        activity.lifecycle.addObserver(readingLifecycle)
        fragment.addInputListener(object : InputListener {
            override fun onDrag(event: DragEvent): Boolean {
                // Observe intent only. Swipes the PT-1 touch layer takes never reach Readium; the
                // rest (vertical, selection, fixed layout, controls open) stay native as before.
                // A user who navigates during reflow must not be pulled back by an old anchor.
                if (event.type == DragEvent.Type.Start) {
                    readerActive()
                    invalidateViewportAnchor()
                    pendingContentsIndex = null
                    if (typographyUpdating) typographyAnchorInvalidated = true
                }
                return false
            }
            override fun onTap(event: TapEvent): Boolean {
                readerActive()
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
                    ReadingTapAction.PREVIOUS -> { turnPage(PageTurnDirection.BACKWARD, PageTurnSource.EDGE_TAP); DiagnosticLog.record("EPUB edge completed tap: backward") }
                    ReadingTapAction.NEXT -> { turnPage(PageTurnDirection.FORWARD, PageTurnSource.EDGE_TAP); DiagnosticLog.record("EPUB edge completed tap: forward") }
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
        pageTurns.attach(container)
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) readingTime.resume(SystemClock.elapsedRealtime())
        readingTimeJob = activity.lifecycleScope.launch {
            // Save running time now and then, so a crash or a killed process loses at most this much.
            while (true) { delay(30_000); record(readingTime.collect(SystemClock.elapsedRealtime())) }
        }
        locationJob = activity.lifecycleScope.launch {
            fragment.currentLocator.collect { locator ->
                // Automatic repagination/restoration is not a new user page turn.
                if (viewportContinuity.pending == null) readerActive()
                currentLocator = locator
                progress = locator.locations.totalProgression ?: progress
                if (!typographyUpdating && viewportContinuity.pending == null) {
                    val generation = viewportContinuity.generation
                    // Keep the last precise passage during this asynchronous query (including
                    // resume notifications for an unchanged page). Explicit navigation clears it.
                    // The initial locator echo precedes DOM placement; describing that DOM saves the chapter start.
                    val keepOpened = reportIsOpenedPassage(holdsOpenedPassage, locator, openedLocator)
                    val screen = if (keepOpened) null else withTimeoutOrNull(1_000) { fragment.visibleReadingAnchor() }
                    val semantic = passageReportedWhileHolding(
                        holdsOpenedPassage, locator, openedLocator, screen, viewportContinuity.anchor,
                    ) { DiagnosticLog.record(it) }
                    viewportContinuity.remember(semantic, generation)
                    if (generation == viewportContinuity.generation && viewportContinuity.pending == null) {
                        if (semantic.href != openedLocator?.href || semantic.text.highlight != openedLocator?.text?.highlight)
                            holdsOpenedPassage = false
                        positions.submit(semantic)
                    }
                }
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
                if (!paceRecalled) { paceRecalled = true; recallPace() }
                // A swipe's silent turn is provisional until it commits: a cancel undoes it, and the
                // page must not yield a reading-pace sample twice (step 5 review, D9).
                if (!pageTurns.swipeUncommitted) {
                    val eligible = !openingPage && !controlsVisible && resumed()
                    openingPage = false
                    if (readingPace.observe(resolved.globalPoint, SystemClock.elapsedRealtime(), eligible)) {
                        rememberPace()
                        val (millis, distance) = readingPace.lastSample() ?: (0L to 0.0)
                        DiagnosticLog.record("Reading pace sample=${millis / 1_000}s, " +
                            "${"%.5f".format(java.util.Locale.ROOT, distance)} of book; pages until estimate=${readingPace.pagesUntilEstimate()}")
                    }
                }
                chapterStatus = ChapterStatus(resolved.title, resolved.progression,
                    resolved.globalEnd?.let { readingPace.remainingMinutes(resolved.globalPoint, it) }, resolved.currentTocIndex,
                    readingPace.bookRemainingMinutes(resolved.globalPoint), readingPace.pagesUntilEstimate(), readingPace.isRough())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing TOC/geometry must never interrupt reading or position saving. */ }
        }
    }

    private fun recallPace() {
        val perMinute = paceMemory.getFloat(bookPaceKey(), 0f).toDouble().takeIf { it > 0.0 }
            ?: chapters.totalPositions?.let { total ->
                paceMemory.getFloat(USUAL_PACE_KEY, 0f).toDouble().takeIf { it > 0.0 }?.div(total)
            }
        perMinute?.let { readingPace.seed(it / 60_000.0) }
    }

    private fun rememberPace() {
        val perMinute = (readingPace.learnedRatePerMillis() ?: return) * 60_000.0
        paceMemory.edit().apply {
            putFloat(bookPaceKey(), perMinute.toFloat())
            chapters.totalPositions?.let { putFloat(USUAL_PACE_KEY, (perMinute * it).toFloat()) }
        }.apply()
    }

    private fun bookPaceKey() = "v2:book:${book.id}"

    fun goToContents(index: Int) {
        contentsEntries.getOrNull(index)?.let {
            invalidateViewportAnchor()
            readingPace.pause(); preferredContentsIndex = index
            val destination = chapters.locatorForContents(index)
            pendingContentsIndex = if (destination == null) index else null
            if (destination != null) fragment.go(destination, animated = false) else fragment.go(it.link, animated = false)
            preferredContentsIndex = index
            currentLocator?.let(::refreshChapter)
        }
    }

    /** Called before dispatching a display configuration change; never creates another navigator. */
    fun viewportChanging(onSettled: () -> Unit) {
        if (closed || !fragment.isAdded) { onSettled(); return }
        // A turn mid-flight must not leave its snapshot over the resized page, and a swipe the user
        // had not committed is turned back: continuity restores the page from before it (D8).
        val revert = pageTurns.abort()
        viewportSettled = onSettled
        readingPace.pause()
        viewportContinuity.begin(typographyAnchor ?: revert ?: currentLocator)
        restoreViewportWhenSettled()
    }

    private fun invalidateViewportAnchor() {
        holdsOpenedPassage = false
        viewportJob?.cancel()
        viewportContinuity.navigated()
        viewportSettled?.invoke(); viewportSettled = null
    }

    private fun restoreViewportWhenSettled() {
        viewportJob?.cancel()
        val generation = viewportContinuity.generation
        viewportJob = activity.lifecycleScope.launch {
            try {
                delay(200) // Coalesce the new window bounds and Compose's density/layout update.
                activity.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                withTimeoutOrNull(3_000) {
                    var previous: String? = null
                    var stable = 0
                    while (stable < 3 || typographyUpdating) {
                        val geometry = fragment.evaluateJavascript("[innerWidth,innerHeight,document.fonts.status]")
                        stable = if (geometry != null && geometry == previous && !geometry.contains("loading")) stable + 1 else 0
                        previous = geometry
                        delay(60)
                    }
                }
                val anchor = viewportContinuity.pending?.takeIf {
                    generation == viewportContinuity.generation && fragment.currentLocator.value.href == it.href
                } ?: run {
                    if (generation == viewportContinuity.generation) {
                        viewportContinuity.navigated()
                        viewportSettled?.invoke(); viewportSettled = null
                    }
                    return@launch
                }
                restoringViewport = true
                val restored = try { fragment.go(anchor, animated = false) } finally { restoringViewport = false }
                delay(200)
                viewportContinuity.complete(generation)?.let { held ->
                    val settled = viewportContinuity.generation
                    val described = !(held.locations["cssSelector"] as? String).isNullOrBlank() && held.text.highlight != null && held.text.before != null
                    val refined = if (described) null else withTimeoutOrNull(1_000) { fragment.visibleReadingAnchor() }
                        ?.let { it.copy(locations = it.locations.copy(totalProgression = held.locations.totalProgression, position = held.locations.position)) }
                    refined?.let { viewportContinuity.remember(it, settled) }
                    if (settled == viewportContinuity.generation && viewportContinuity.pending == null) positions.submit(refined ?: held)
                }
                currentLocator?.let(::refreshChapter)
                DiagnosticLog.record("EPUB viewport resized in place; semantic passage restored=$restored")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                viewportContinuity.complete(generation)
                DiagnosticLog.record("EPUB viewport restoration failed: ${error.javaClass.simpleName}")
            } finally {
                if (generation == viewportContinuity.generation && viewportContinuity.pending == null) {
                    viewportSettled?.invoke(); viewportSettled = null
                }
            }
        }
    }

    fun goToBookmark(value: String) {
        val locator = runCatching { Locator.fromJSON(JSONObject(value)) }.getOrNull() ?: return
        invalidateViewportAnchor()
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
        invalidateViewportAnchor()
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
        val locator = withTimeoutOrNull(1_000) { fragment.visibleReadingAnchor() }
            ?: viewportContinuity.anchor ?: currentLocator ?: return null
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
        pageTurns.abort() // Reflow repaginates under any snapshot; drop it first.
        typographyJob?.cancel()
        if (!typographyUpdating) typographyAnchorInvalidated = false
        typographyUpdating = true
        typographyJob = activity.lifecycleScope.launch {
            try {
                // Keep the same semantic anchor for the entire adjustment burst, rather than
                // repeatedly capturing a page that has already moved during repagination.
                if (typographyAnchor == null) typographyAnchor = fragment.visibleReadingAnchor() ?: viewportContinuity.anchor ?: currentLocator
                delay(100)
                val requested = preferences()
                val requestedFont = requested.fontFamily?.name.orEmpty()
                val bookStyles = requested.publisherStyles == true
                unconfirmedTypography = "settings"
                fragment.submitPreferences(requested)
                val settled = withTimeoutOrNull(3_000) {
                    fragment.settings.first {
                        it.fontSize == requested.fontSize && it.fontFamily == requested.fontFamily &&
                            it.publisherStyles == bookStyles && it.lineHeight == requested.lineHeight &&
                            it.pageMargins == requested.pageMargins && it.textAlign == requested.textAlign
                    }
                    // A public, read-only query confirms that the asynchronous CSS update and
                    // font loading reached the visible resource. No extra JavaScript bridge.
                    // It returns the names of unmet checks (never book text), so a timeout says why.
                    while (true) {
                        unconfirmedTypography = fragment.evaluateJavascript("""
                            (function(){var s=getComputedStyle(document.documentElement), u=[];
                            function v(n){return s.getPropertyValue(n).trim();}
                            if(!(Math.abs(parseFloat(v('--USER__fontSize'))-${requested.fontSize!! * 100})<0.1)) u.push('size');
                            if(!(${if (bookStyles) "!v('--USER__fontFamily')" else "v('--USER__fontFamily').indexOf('$requestedFont')>=0"})) u.push('family');
                            // With publisher styles Readium may omit the flag entirely; only "on" overrides the book.
                            if(${if (bookStyles) "v('--USER__advancedSettings').indexOf('readium-advanced-on')>=0" else "v('--USER__advancedSettings').indexOf('readium-advanced-on')<0"}) u.push('mode');
                            if(!(Math.abs(parseFloat(v('--USER__pageMargins'))-${requested.pageMargins})<0.01)) u.push('margins');
                            if(!(${if (bookStyles) "!v('--USER__lineHeight')" else "Math.abs(parseFloat(v('--USER__lineHeight'))-${requested.lineHeight})<0.01"})) u.push('lineHeight');
                            if(!(${if (bookStyles) "!v('--USER__textAlign')" else "v('--USER__textAlign')=='${comfort.alignment.css}'"})) u.push('align');
                            if(document.fonts.status!=='loaded') u.push('fonts');
                            return u.join(',');})()
                        """.trimIndent())?.trim('"') ?: "viewport"
                        if (unconfirmedTypography.isEmpty()) break
                        delay(40)
                    }
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
                DiagnosticLog.record("EPUB typography CSS reflow; bookStyles=$bookStyles; size=${ReadingTextSize.percent(fontScale)}%; font=$fontId; lineSpacing=${comfort.lineSpacing}; margins=${comfort.margins.id}; alignment=${comfort.alignment.id}; CSS confirmed=$settled${if (settled) "" else " (unmet: $unconfirmedTypography)"}; semantic anchor restored=$restored")
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

    fun changePaperPageTurn(enabled: Boolean) {
        paperPageTurn = enabled
        readerPreferences.savePaperPageTurn(enabled)
    }

    /** The window's text-selection toolbar opened or closed (see `installReadingKeyHandler`). */
    fun actionModeChanged(active: Boolean) { actionModeActive = active }

    /**
     * PT-1: the single entry point for every user page turn. Side effects live in [noteUserTurn] so
     * they cannot drift between swipes, edge taps, buttons and keys.
     */
    fun turnPage(direction: PageTurnDirection, source: PageTurnSource) {
        if (closed || !fragment.isAdded) return
        noteUserTurn(source)
        pageTurns.turn(direction, source, PageTurnRules.path(turnConditions()))
    }

    /** A tap that landed while a turn's snapshot hid the page underneath (D5): edge taps still turn. */
    private fun blockedTap(x: Float, y: Float) {
        if (closed) return
        val view = fragment.view ?: return
        readerActive()
        when (readingTapAction(x, y, view.width.toFloat(), view.height.toFloat(), view.resources.displayMetrics.density,
            edgeTaps, controlsVisible, typographyUpdating, hasProtectedTarget = false,
            rightToLeft = fragment.settings.value.readingProgression == ReadingProgression.RTL)) {
            ReadingTapAction.PREVIOUS -> { turnPage(PageTurnDirection.BACKWARD, PageTurnSource.EDGE_TAP); DiagnosticLog.record("EPUB edge completed tap: backward") }
            ReadingTapAction.NEXT -> { turnPage(PageTurnDirection.FORWARD, PageTurnSource.EDGE_TAP); DiagnosticLog.record("EPUB edge completed tap: forward") }
            // Links, selection and the controls toggle wait until the page under the finger is the one shown.
            ReadingTapAction.CONTROLS, ReadingTapAction.NONE -> Unit
        }
    }

    private fun noteUserTurn(source: PageTurnSource) {
        readerActive()
        // A user who navigates during reflow must not be pulled back by an old anchor.
        invalidateViewportAnchor()
        pendingContentsIndex = null
        if (typographyUpdating) typographyAnchorInvalidated = true
        // Page buttons live in the controls; close them first so they never appear in the snapshot.
        if (source == PageTurnSource.BUTTON) controlsVisible = false
    }

    private fun turnConditions() = PageTurnConditions(
        paperPageTurn = paperPageTurn,
        reflowable = supportsTypography,
        typographyUpdating = typographyUpdating,
        restoringViewport = restoringViewport || viewportContinuity.pending != null,
        animationsEnabled = ValueAnimator.areAnimatorsEnabled(),
    )

    /** Activity dispatch reaches this before WebView or Android's volume handling. */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.readingPageKey() ?: return false
        readerActive()
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
                        val source = if (key == ReadingPageKey.VOLUME_UP || key == ReadingPageKey.VOLUME_DOWN)
                            PageTurnSource.VOLUME_KEY else PageTurnSource.KEYBOARD
                        when (turn) {
                            ReadingPageTurn.NEXT -> turnPage(PageTurnDirection.FORWARD, source)
                            ReadingPageTurn.PREVIOUS -> turnPage(PageTurnDirection.BACKWARD, source)
                        }
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

    /** Page buttons in the controls. */
    fun next() = turnPage(PageTurnDirection.FORWARD, PageTurnSource.BUTTON)
    fun previous() = turnPage(PageTurnDirection.BACKWARD, PageTurnSource.BUTTON)
    val restorationLocator: Locator? get() = viewportContinuity.pending ?: viewportContinuity.anchor ?: currentLocator
    /** Saves a waiting reading position now, e.g. when the app goes to the background. */
    fun flushPosition() = flushPosition(null)

    /** [revert]: the page from before a dropped, uncommitted swipe turn, saved instead (D8). */
    private fun flushPosition(revert: Locator?) {
        (revert ?: restorationLocator)?.let(positions::submit)
        positions.flush()
    }

    private fun readerActive() = record(readingTime.activity(SystemClock.elapsedRealtime()))

    /** Adds credited time to its local dates, off the main thread; each credit is handed over exactly once. */
    private fun record(credit: ReadingCredit?) {
        if (credit == null || credit.millis <= 0) return
        // Place the monotonic interval on the wall clock as of now, so earlier clock changes cannot shift it.
        val end = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - credit.endElapsed)
        saveScope.launch(Dispatchers.IO) {
            try { library.readingDays.add(end - credit.millis, end) }
            catch (error: Exception) { DiagnosticLog.record("Reading time save failed: ${error.javaClass.simpleName}") }
        }
    }

    fun close() {
        if (closed) return
        record(readingTime.pause(SystemClock.elapsedRealtime()))
        readingTimeJob?.cancel()
        closed = true
        val revert = pageTurns.close()
        search.close()
        locationJob?.cancel()
        chapterJob?.cancel()
        keyTurnJob?.cancel()
        viewportJob?.cancel()
        viewportSettled = null
        activity.lifecycle.removeObserver(readingLifecycle)
        fragment.view?.removeOnLayoutChangeListener(layoutListener)
        flushPosition(revert)
        typographyJob?.cancel()
        if (!activity.supportFragmentManager.isDestroyed && fragment.isAdded) {
            runCatching { activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
        publication.close()
    }

    private fun preferences() = comfort.epubPreferences(fontScale, fontId, themeName)
}
