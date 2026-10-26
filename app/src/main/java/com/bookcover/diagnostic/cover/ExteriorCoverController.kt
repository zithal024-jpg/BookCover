package com.bookcover.diagnostic.cover

import android.app.Activity
import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.window.WindowSdkExtensions
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import com.bookcover.diagnostic.DiagnosticLog
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
    var artwork: CoverArtwork? = null
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
                areas = list
                available = area() != null
                capability = list.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    ?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status?.toString()
                    ?: "UNSUPPORTED"
                DiagnosticLog.record("Exterior capability=$capability")
            }
        }
    }

    private fun area() = areas.firstOrNull {
        it.type == WindowAreaInfo.Type.TYPE_REAR_FACING &&
            it.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA).status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE
    }

    fun start(timerMinutes: Int) {
        if (busy || destroyed || artwork == null) return
        val area = area() ?: run { status = "Outside display is unavailable in the current phone state."; return }
        val token = ++request
        busy = true; active = false; stopPending = false
        status = "Opening the outside cover. Accept Android's display dialog if offered."
        if (timerMinutes > 0) timeout = Runnable { stop("$timerMinutes-minute cover timer") }.also { handler.postDelayed(it, timerMinutes * 60_000L) }
        DiagnosticLog.record("Exterior start request=$token; timerMinutes=$timerMinutes; same reader activity")
        try {
            controller.presentContentOnWindowArea(area.token, activity, ContextCompat.getMainExecutor(activity), object : WindowAreaPresentationSessionCallback {
                override fun onSessionStarted(presenter: WindowAreaSessionPresenter) {
                    if (token != request || destroyed) { presenter.close(); return }
                    session = presenter
                    if (stopPending) { presenter.close(); return }
                    try {
                        val art = artwork ?: error("No cover artwork")
                        presenter.setContentView(ImageView(presenter.context).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setImageBitmap(art.bitmap); setBackgroundColor(art.background)
                            contentDescription = "Current book cover"
                            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                                if (r - l > 0 && b - t > 0 && (r - l != or - ol || b - t != ob - ot))
                                    DiagnosticLog.record("Exterior cover view=${r - l}x${b - t}; matching background; normal brightness")
                            }
                        })
                        presenter.window?.let { window ->
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            WindowCompat.setDecorFitsSystemWindows(window, false)
                            WindowInsetsControllerCompat(window, window.decorView).apply {
                                hide(WindowInsetsCompat.Type.systemBars())
                                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        }
                        active = true
                        status = if (timerMinutes > 0) "Outside cover on · $timerMinutes-minute timer" else "Outside cover on · stop from reading controls"
                        DiagnosticLog.record("Exterior onSessionStarted request=$token; EPUB reading stays inside same activity")
                    } catch (error: Exception) { DiagnosticLog.record("Exterior render failed: ${error.javaClass.simpleName}"); stop("render error") }
                }
                override fun onSessionEnded(t: Throwable?) {
                    DiagnosticLog.record("Exterior onSessionEnded request=$token: ${t?.javaClass?.simpleName ?: "no detailed system reason"}")
                    if (token == request) {
                        timeout?.let(handler::removeCallbacks); timeout = null; session = null
                        busy = false; active = false; stopPending = false
                        status = "Outside cover is off. Reading can continue; restart it from controls if needed."
                    }
                }
                override fun onContainerVisibilityChanged(isVisible: Boolean) { DiagnosticLog.record("Exterior visible=$isVisible") }
            })
        } catch (error: Exception) {
            timeout?.let(handler::removeCallbacks); timeout = null
            busy = false; active = false; stopPending = false
            status = "Outside cover could not start: ${error.javaClass.simpleName}"
            DiagnosticLog.record(status)
        }
    }

    fun stop(reason: String) {
        if (!busy || stopPending) return
        stopPending = true; active = false
        timeout?.let(handler::removeCallbacks); timeout = null
        status = "Stopping outside cover…"
        DiagnosticLog.record("Exterior app stop requested: $reason")
        runCatching { session?.close() }.onFailure { DiagnosticLog.record("Exterior close failed: ${it.javaClass.simpleName}") }
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
