package com.bookcover.diagnostic.testing

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library Home screenshots at matching states: folded-width and unfolded-width windows, light and dark,
 * normal and 200% text, plus the empty library. Emulator only, because it overrides the display size;
 * on a real phone every case is skipped. Also writes where Continue and the first book land on screen.
 */
@RunWith(AndroidJUnit4::class)
class LibraryHomeScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<LibraryAccessibilityActivity>? = null
    private val report = StringBuilder()

    @Before fun emulatorOnly() {
        check(target.packageName.endsWith(".qa")) { "Screenshot scenes require the isolated QA app." }
        assumeTrue("Display-size overrides run only on an emulator", Build.HARDWARE == "ranchu" || Build.FINGERPRINT.contains("generic"))
    }

    @After fun restoreDisplay() {
        scenario?.close()
        LibraryAccessibilityActivity.fontScaleForTests = 1f
        shell("wm size reset"); shell("wm density reset")
        folder()?.let { File(it, "library-home-layout.txt").appendText(report.toString()) }
    }

    @Test fun narrowAndWideInLightAndDark() {
        for ((size, width, height) in SIZES) for (night in listOf(false, true)) {
            capture("home", size, width, height, night, 1f)
        }
    }

    @Test fun twoHundredPercentTextAndEmptyLibrary() {
        for ((size, width, height) in SIZES) {
            capture("home", size, width, height, night = false, fontScale = 2f)
            capture("empty", size, width, height, night = false, fontScale = 1f)
        }
    }

    private fun capture(scene: String, size: String, width: Int, height: Int, night: Boolean, fontScale: Float) {
        shell("wm size ${width}x$height"); shell("wm density 420")
        SystemClock.sleep(800)
        LibraryAccessibilityActivity.fontScaleForTests = fontScale
        val intent = Intent(target, LibraryAccessibilityActivity::class.java)
            .putExtra("settingsSession", UUID.randomUUID().toString()).putExtra("scene", scene).putExtra("night", night)
        lateinit var host: LibraryAccessibilityActivity
        scenario?.close()
        scenario = ActivityScenario.launch<LibraryAccessibilityActivity>(intent).also { it.onActivity { activity -> host = activity } }
        runBlocking { withContext(Dispatchers.Main) { withTimeout(20_000) { host.ready.await() } } }
        if (scene == "home") runBlocking { withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
            fun hour(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            host.libraryForTests.readingDays.apply {
                listOf(3L, 2L, 1L).forEach { back -> today.minusDays(back).let { add(hour(it, 12), hour(it, 12) + 6 * 60_000L) } }
                add(hour(today, 0) + 60_000L, hour(today, 0) + 3 * 60_000L)
            }
        } }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(2_500) // Covers decode on a background thread; let them and any entry animation settle.
        val name = "library-$scene-$size-${if (night) "dark" else "light"}-${(fontScale * 100).toInt()}"
        recordLayout(name, runBlocking { withContext(Dispatchers.Main) { host.resources.displayMetrics.density } })
        folder()?.let { dir ->
            automation.takeScreenshot()?.let { bitmap ->
                FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** Where Continue and the first two books sit, in dp from the top of the window, without scrolling. */
    private fun recordLayout(name: String, density: Float) {
        val root = automation.rootInActiveWindow ?: return
        val window = Rect().also(root::getBoundsInScreen)
        val found = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            val label = node.contentDescription?.toString() ?: node.text?.toString()
            if (label != null && (label.startsWith("Continue") || label.startsWith("Open ") || label.startsWith("Resume"))) {
                val bounds = Rect().also(node::getBoundsInScreen)
                found += "${label.take(48)}: top=${(bounds.top / density).toInt()}dp bottom=${(bounds.bottom / density).toInt()}dp " +
                    "visible=${node.isVisibleToUser}"
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        report.append("$name window=${(window.width() / density).toInt()}x${(window.height() / density).toInt()}dp\n")
        found.take(4).forEach { report.append("  $it\n") }
    }

    private fun folder(): String? = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private companion object {
        /** The owner's outer screen (1080×2520 px) and an unfolded inner screen, both at 420 dpi. */
        val SIZES = listOf(Triple("narrow", 1080, 2520), Triple("wide", 1856, 2160))
    }
}
