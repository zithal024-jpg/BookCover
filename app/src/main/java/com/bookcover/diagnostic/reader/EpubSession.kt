package com.bookcover.diagnostic.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
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
    var controlsVisible by mutableStateOf(false)
    var progress by mutableDoubleStateOf(book.progression)
    var fontScale by mutableDoubleStateOf(ReadingTextSize.percent(initialFontScale) / 100.0)
        private set
    var fontId by mutableStateOf(ReadingFont.fromId(initialFontId).id)
        private set
    var edgeTaps by mutableStateOf(initialEdgeTaps)
        private set
    var typographyUpdating by mutableStateOf(false)
        private set
    val supportsTypography = (publication.metadata.layout ?: Layout.REFLOWABLE) == Layout.REFLOWABLE
    var themeName by mutableStateOf(initialTheme)
        private set
    var currentLocator: Locator? = book.locator?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
        private set
    private var locationJob: Job? = null
    private var typographyJob: Job? = null
    private var typographyAnchor: Locator? = null
    private var typographyAnchorInvalidated = false
    private var closed = false
    val fragment: EpubNavigatorFragment

    init {
        val factory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = currentLocator,
            initialPreferences = preferences(),
            configuration = EpubNavigatorFragment.Configuration().apply { useReadiumCssFontSize = true },
            listener = object : EpubNavigatorFragment.Listener {
                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    report("External book links are not opened automatically. Use the library's browser search if needed.")
                }
                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    report("A chapter resource could not be displayed. Other chapters may still work.")
                    DiagnosticLog.record("EPUB resource load failed; resource path omitted")
                }
            },
        )
        fragment = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        fragment.addInputListener(object : InputListener {
            override fun onDrag(event: DragEvent): Boolean {
                // Observe intent only: native Readium/ViewPager remains the sole swipe handler.
                // A user who navigates during reflow must not be pulled back by an old anchor.
                if (event.type == DragEvent.Type.Start && typographyUpdating) typographyAnchorInvalidated = true
                return false
            }
            override fun onTap(event: TapEvent): Boolean {
                val view = fragment.view ?: return false
                // Readium filters interactive links, active selections and drag gestures before
                // this callback. Keep its native touch recognizers and selection handling intact.
                when (readingTapAction(event.point.x, event.point.y, view.width.toFloat(), view.height.toFloat(),
                    view.resources.displayMetrics.density, edgeTaps, controlsVisible, typographyUpdating, event.targetElement != null)) {
                    ReadingTapAction.CONTROLS -> {
                        controlsVisible = !controlsVisible
                        DiagnosticLog.record("EPUB controls ${if (controlsVisible) "revealed" else "hidden"}; same host activity")
                    }
                    ReadingTapAction.PREVIOUS -> { fragment.goBackward(animated = true); DiagnosticLog.record("EPUB left-edge completed tap") }
                    ReadingTapAction.NEXT -> { fragment.goForward(animated = true); DiagnosticLog.record("EPUB right-edge completed tap") }
                    ReadingTapAction.NONE -> return false
                }
                return true
            }
        })
    }

    fun attach(container: FragmentContainerView) {
        if (closed || activity.isDestroyed || fragment.isAdded || activity.supportFragmentManager.isStateSaved) return
        activity.supportFragmentManager.beginTransaction().replace(container.id, fragment, "bookcover-epub").commitNow()
        locationJob = activity.lifecycleScope.launch {
            fragment.currentLocator.collect { locator ->
                currentLocator = locator
                progress = locator.locations.totalProgression ?: progress
                if (!typographyUpdating) library.updatePosition(book.id, locator)
                DiagnosticLog.record("EPUB position=${(progress * 100).toInt()}%; same host activity")
            }
        }
    }

    fun changeFont(value: Double) {
        if (!supportsTypography) return
        fontScale = ReadingTextSize.percent(value) / 100.0
        applyTypography()
    }

    fun changeFontFamily(id: String) {
        if (!supportsTypography) return
        fontId = ReadingFont.fromId(id).id
        applyTypography()
    }

    private fun applyTypography() {
        if (closed || !fragment.isAdded) return
        typographyJob?.cancel()
        if (!typographyUpdating) typographyAnchorInvalidated = false
        typographyUpdating = true
        typographyJob = activity.lifecycleScope.launch {
            try {
                // Keep the same semantic anchor for the entire adjustment burst, rather than
                // repeatedly capturing a page that has already moved during repagination.
                if (typographyAnchor == null) typographyAnchor = fragment.firstVisibleElementLocator() ?: currentLocator
                delay(100)
                val requestedSize = fontScale
                val requestedFont = ReadingFont.fromId(fontId).cssFamily
                activity.getSharedPreferences("reader", 0).edit().putFloat("fontScale", fontScale.toFloat())
                    .putString("fontId", fontId).apply()
                fragment.submitPreferences(preferences())
                val settled = withTimeoutOrNull(3_000) {
                    fragment.settings.first { it.fontSize == requestedSize && it.fontFamily?.name == requestedFont }
                    // A public, read-only query confirms that the asynchronous CSS update and
                    // font loading reached the visible resource. No extra JavaScript bridge.
                    while (fragment.evaluateJavascript("""
                        (function(){var s=getComputedStyle(document.documentElement);
                        return Math.abs(parseFloat(s.getPropertyValue('--USER__fontSize'))-${requestedSize * 100})<0.1
                        && s.getPropertyValue('--USER__fontFamily').indexOf('$requestedFont')>=0
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
                currentLocator?.let { library.updatePosition(book.id, it) }
                if (!settled) report("Text settings saved. This book's styles may limit the selected typography.")
                DiagnosticLog.record("EPUB typography CSS reflow; size=${ReadingTextSize.percent(fontScale)}%; font=$fontId; CSS confirmed=$settled; semantic anchor restored=$restored")
                typographyAnchor = null
                typographyUpdating = false
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

    fun changeTheme(value: String) {
        themeName = value
        activity.getSharedPreferences("reader", 0).edit().putString("theme", themeName).apply()
        fragment.submitPreferences(preferences())
    }

    fun next() { fragment.goForward(animated = true); controlsVisible = false }
    fun previous() { fragment.goBackward(animated = true); controlsVisible = false }
    fun close() {
        if (closed) return
        closed = true
        locationJob?.cancel()
        typographyJob?.cancel()
        if (!activity.supportFragmentManager.isDestroyed && fragment.isAdded) {
            runCatching { activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss() }
        }
        publication.close()
    }

    private fun preferences() = EpubPreferences(
        fontFamily = FontFamily(ReadingFont.fromId(fontId).cssFamily), fontSize = fontScale,
        lineHeight = 1.5, pageMargins = 1.0, paragraphSpacing = 0.6, paragraphIndent = 1.2,
        columnCount = ColumnCount.ONE, scroll = false, publisherStyles = false,
        theme = when (themeName) { "Night" -> Theme.DARK; "Sepia" -> Theme.SEPIA; else -> Theme.LIGHT },
    )
}
