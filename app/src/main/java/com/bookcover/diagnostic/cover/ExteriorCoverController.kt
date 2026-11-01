package com.bookcover.diagnostic.cover

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.window.WindowSdkExtensions
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.rewards.RelicAppearance
import com.bookcover.diagnostic.rewards.RelicCoverView
import com.bookcover.diagnostic.rewards.RelicMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class CoverArtwork(val bitmap: Bitmap, val background: Int) {
    companion object {
        fun from(bitmap: Bitmap): CoverArtwork {
            val samples = IntArray(192)
            val ix = ((bitmap.width - 1) * 0.02).toInt()
            val iy = ((bitmap.height - 1) * 0.02).toInt()
            for (index in 0 until 48) {
                val x = index * (bitmap.width - 1) / 47
                val y = index * (bitmap.height - 1) / 47
                samples[index * 4] = bitmap.getPixel(x, iy)
                samples[index * 4 + 1] = bitmap.getPixel(x, bitmap.height - 1 - iy)
                samples[index * 4 + 2] = bitmap.getPixel(ix, y)
                samples[index * 4 + 3] = bitmap.getPixel(bitmap.width - 1 - ix, y)
            }
            return CoverArtwork(bitmap, matchingCoverColor(samples))
        }
    }
}

/** Public same-activity display session; brightness overrides and AOD are intentionally absent. */
@OptIn(ExperimentalWindowApi::class)
internal class ExteriorCoverController(private val activity: Activity, scope: CoroutineScope) {
    private val handler = Handler(Looper.getMainLooper())
    private val controller = WindowAreaController.getOrCreate()
    private var areas: List<WindowAreaInfo> = emptyList()
    private var session: WindowAreaSessionPresenter? = null
    private var request = 0
    private var timeout: Runnable? = null
    private val continuations = CoverPreviewContinuation()
    private val continuation get() = continuations.current
    private var renderedView: RelicCoverView? = null
    private var renderedContainer: FrameLayout? = null
    private var appearance = RelicAppearance()
    private var coverTitle = ""
    private var coverAuthor = ""
    private var containerVisible = true
    private var viewportInProgress = false
    private var hostVisible = activity.lifecycleIsStarted()
    private data class Preview(val artwork: CoverArtwork?, val appearance: RelicAppearance, val title: String, val author: String)
    private var preview: Preview? = null
    private var resumeAfterClose = false
    // Recreation persists the reader's request, never a temporary editor display.
    val requested get() = continuations.reading.requested
    val deadline get() = continuations.reading.deadline
    val previewRequested get() = continuations.previewing && continuation.requested
    var outsideAspectRatio by mutableFloatStateOf(0.41f); private set
    var artwork: CoverArtwork? = null
        set(value) {
            field = value
            bindDisplayedCover()
        }
    var active by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var stopPending by mutableStateOf(false); private set
    var available by mutableStateOf(false); private set
    var status by mutableStateOf("Outside cover is off."); private set
    var capability by mutableStateOf("Checking exterior display…"); private set
    private var destroyed = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stop("screen off/locking") }
    }

    init {
        ContextCompat.registerReceiver(activity, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        DiagnosticLog.record("Exterior controller; WindowManager extension=${WindowSdkExtensions.getInstance().extensionVersion}; brightness/AOD shelved")
        scope.launch {
            controller.windowAreaInfos.collect { list ->
                val wasAvailable = available
                areas = list
                list.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }?.metrics?.bounds?.let { bounds ->
                    if (bounds.width() > 0 && bounds.height() > 0)
                        outsideAspectRatio = bounds.width().toFloat() / bounds.height()
                }
                available = area() != null
                capability = list.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status?.toString()
                    ?: "UNSUPPORTED"
                DiagnosticLog.record("Exterior capability=$capability")
                if (available && !wasAvailable && continuation.resumePending) resumeCover()
            }
        }
    }

    private fun area() = areas.firstOrNull {
        it.type == WindowAreaInfo.Type.TYPE_REAR_FACING &&
            it.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA).status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
    }

    fun start(timerMinutes: Int) {
        if (continuations.previewing) {
            if (!destroyed && (artwork != null || (appearance.enabled && appearance.mode == RelicMode.STANDALONE)))
                continuations.reading.start(timerMinutes, SystemClock.elapsedRealtime())
            return
        }
        if (busy || destroyed || !hasContent()) return
        continuations.reading.start(timerMinutes, SystemClock.elapsedRealtime())
        openSession()
    }

    fun restore(wasRequested: Boolean, savedDeadline: Long) {
        continuations.reading.restore(wasRequested, savedDeadline, SystemClock.elapsedRealtime())
        if (!continuations.previewing) resumeCover()
    }

    /** Appearance changes update the active view without renewing its session or countdown. */
    fun setAppearance(appearance: RelicAppearance, title: String, author: String) {
        this.appearance = appearance
        coverTitle = title
        coverAuthor = author
        bindDisplayedCover()
    }

    /** Editing borrows an existing presentation, or opens one owned only by this preview. */
    fun preview(artwork: CoverArtwork?, appearance: RelicAppearance, title: String, author: String) {
        if (destroyed) return
        preview = Preview(artwork, appearance, title, author)
        if (continuations.begin(SystemClock.elapsedRealtime())) {
            timeout?.let(handler::removeCallbacks); timeout = null
        }
        bindDisplayedCover()
        if (stopPending) resumeAfterClose = true
        else if (!busy) openSession()
        if (active) status = "Live outside preview is on. Changes appear as you edit."
    }

    fun retryPreview() {
        if (preview == null || destroyed || busy) return
        // This is an explicit owner action; failed platform requests are never retried in a loop.
        if (!continuation.requested) { continuations.end(); continuations.begin(SystemClock.elapsedRealtime()) }
        openSession()
    }

    fun endPreview() {
        if (preview == null) return
        preview = null
        continuations.end()
        bindDisplayedCover()
        val now = SystemClock.elapsedRealtime()
        if (!continuation.requested || continuation.remaining(now) == 0L) {
            continuation.stop()
            resumeAfterClose = false
            timeout?.let(handler::removeCallbacks); timeout = null
            closeSession("appearance preview closed")
        } else if (stopPending) resumeAfterClose = true
        else if (busy) {
            continuation.presenting()
            scheduleTimeout()
            status = if (deadline > 0L) "Outside cover on · timer running" else "Outside cover on · stop from reading controls"
        } else resumeCover()
    }

    private fun bindDisplayedCover() {
        val current = preview
        if (current != null) renderedView?.bind(current.artwork, current.appearance, current.title, current.author)
        else renderedView?.bind(artwork, appearance, coverTitle, coverAuthor)
        renderedView?.let { renderedContainer?.setBackgroundColor(it.backingColor) }
    }

    private fun hasContent(): Boolean {
        val current = preview
        val shownArtwork = current?.artwork ?: if (current == null) artwork else null
        val shownAppearance = current?.appearance ?: appearance
        return shownArtwork != null || (shownAppearance.enabled && shownAppearance.mode == RelicMode.STANDALONE)
    }
    private fun presentationIsVisible() = hostVisible && containerVisible && !viewportInProgress

    fun viewportChanging() {
        viewportInProgress = true
        renderedView?.setPresentationVisible(false)
        continuation.displayChanging(suspended = !busy)
    }
    fun viewportSettled() {
        viewportInProgress = false
        renderedView?.setPresentationVisible(presentationIsVisible())
        continuation.displaySettled()
        if (continuation.resumePending) resumeCover()
    }
    /** Pausing the animation does not end the display session or renew its original deadline. */
    fun hostStopped() {
        hostVisible = false
        renderedView?.setPresentationVisible(false)
    }
    fun hostResumed() {
        hostVisible = true
        renderedView?.setPresentationVisible(presentationIsVisible())
        if (continuation.resumePending) resumeCover()
    }

    private fun resumeCover() {
        if (!continuation.requested || destroyed) return
        if (continuation.remaining(SystemClock.elapsedRealtime()) == 0L) { stop("cover timer"); return }
        openSession()
    }

    private fun openSession() {
        if (busy || destroyed || !hostVisible || !continuation.requested || !hasContent() || !activity.lifecycleIsStarted()) return
        val area = area() ?: run { status = "The outside screen isn't available right now. Keep the phone unfolded and try again."; return }
        val token = ++request
        containerVisible = true
        continuation.presenting()
        busy = true; active = false; stopPending = false
        status = "Opening the outside cover. Accept Android's display dialog if offered."
        scheduleTimeout()
        DiagnosticLog.record("Exterior start request=$token; original cover deadline retained; same reader activity")
        try {
            controller.presentContentOnWindowArea(area.token, activity, ContextCompat.getMainExecutor(activity), object : WindowAreaPresentationSessionCallback {
                override fun onSessionStarted(presenter: WindowAreaSessionPresenter) {
                    if (token != request || destroyed) { presenter.close(); return }
                    session = presenter
                    if (stopPending) { presenter.close(); return }
                    try {
                        releaseRenderedView()
                        val view = RelicCoverView(presenter.context).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setPresentationVisible(presentationIsVisible())
                            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                                if (r - l > 0 && b - t > 0 && (r - l != or - ol || b - t != ob - ot)) {
                                    outsideAspectRatio = (r - l).toFloat() / (b - t)
                                    DiagnosticLog.record("Exterior cover view=${r - l}x${b - t}; matching background; normal brightness")
                                }
                            }
                        }
                        renderedView = view
                        bindDisplayedCover()
                        val safeContent = FrameLayout(presenter.context).apply {
                            setBackgroundColor(view.backingColor)
                            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                        renderedContainer = safeContent
                        ViewCompat.setOnApplyWindowInsetsListener(safeContent) { container, insets ->
                            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                            container.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                            insets
                        }
                        presenter.setContentView(safeContent)
                        ViewCompat.requestApplyInsets(safeContent)
                        presenter.window?.let { window ->
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            WindowCompat.setDecorFitsSystemWindows(window, false)
                            WindowInsetsControllerCompat(window, window.decorView).apply {
                                hide(WindowInsetsCompat.Type.systemBars())
                                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }
                        active = true
                        status = if (preview != null) "Live outside preview is on. Changes appear as you edit."
                            else if (continuation.deadline > 0L) "Outside cover on · timer running" else "Outside cover on · stop from reading controls"
                        DiagnosticLog.record("Exterior onSessionStarted request=$token; EPUB reading stays inside same activity")
                    } catch (error: Exception) {
                        DiagnosticLog.record("Exterior render failed: ${error.javaClass.simpleName}")
                        continuation.stop()
                        timeout?.let(handler::removeCallbacks); timeout = null
                        resumeAfterClose = false
                        closeSession("render error")
                    }
                }
                override fun onSessionEnded(t: Throwable?) {
                    DiagnosticLog.record("Exterior onSessionEnded request=$token: ${t?.javaClass?.simpleName ?: "no detailed system reason"}")
                    if (token == request) {
                        releaseRenderedView()
                        session = null
                        busy = false; active = false; stopPending = false
                        if (resumeAfterClose && continuation.requested) {
                            resumeAfterClose = false
                            resumeCover()
                            return
                        }
                        status = "Outside cover is off. Reading can continue; restart it from controls if needed."
                        if (continuation.systemEnded(t != null)) resumeCover()
                        else if (!continuation.requested) { timeout?.let(handler::removeCallbacks); timeout = null }
                        if (continuation.requested && !busy)
                            status = "Outside cover paused. It will resume after the display changes; you can stop it from controls."
                    }
                }
                override fun onContainerVisibilityChanged(isVisible: Boolean) {
                    DiagnosticLog.record("Exterior visible=$isVisible")
                    if (token == request) {
                        containerVisible = isVisible
                        renderedView?.setPresentationVisible(presentationIsVisible())
                    }
                }
            })
        } catch (error: Exception) {
            releaseRenderedView()
            timeout?.let(handler::removeCallbacks); timeout = null
            continuation.stop()
            busy = false; active = false; stopPending = false
            status = "The outside cover couldn't start. Reading is unaffected; try again from reading controls."
            DiagnosticLog.record("Outside cover could not start: ${error.javaClass.simpleName}")
        }
    }

    fun stop(reason: String) {
        continuations.stop()
        preview = null
        resumeAfterClose = false
        timeout?.let(handler::removeCallbacks); timeout = null
        closeSession(reason)
    }

    private fun scheduleTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = continuation.remaining(SystemClock.elapsedRealtime())?.let { remaining ->
            Runnable { stop("cover timer") }.also { handler.postDelayed(it, remaining) }
        }
    }

    private fun closeSession(reason: String) {
        releaseRenderedView()
        if (!busy || stopPending) return
        stopPending = true; active = false
        status = "Stopping outside cover…"
        DiagnosticLog.record("Exterior app stop requested: $reason")
        runCatching { session?.close() }.onFailure { DiagnosticLog.record("Exterior close failed: ${it.javaClass.simpleName}") }
    }

    private fun releaseRenderedView() {
        renderedView?.release()
        renderedView = null
        renderedContainer = null
    }

    fun diagnostics(): String = buildString {
        appendLine("Exterior capability: $capability")
        appendLine("Requested=$busy; active=$active; stopPending=$stopPending")
        activity.getSystemService(DisplayManager::class.java).displays.forEach {
            appendLine("id=${it.displayId}; state=${it.state}; ${it.mode.physicalWidth}x${it.mode.physicalHeight}; flags=0x${it.flags.toString(16)}")
        }
    }

    fun close() {
        stop("host activity destroyed/recreated"); destroyed = true; ++request
        runCatching { activity.unregisterReceiver(screenOff) }
    }
}

private fun Activity.lifecycleIsStarted() =
    (this as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true
