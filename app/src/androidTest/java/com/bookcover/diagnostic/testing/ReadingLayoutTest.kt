package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.reader.EpubSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Page layout on the wide inner screen, where Readium's line-length cap decides the side margins.
 * Run with the phone physically unfolded; on the narrow cover screen the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class ReadingLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var reader: EpubSession

    @Before fun cleanPreferences() {
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun changingTextSizeReflowsTextInsideTheSameMargins() {
        launch()
        val before = layout()
        // Only a wide screen (the phone physically unfolded) shows the cap; a narrow one is skipped, not passed.
        assumeTrue("Unfold the phone: this screen is too narrow for the line-length cap ($before)",
            before.getDouble("cap") < before.getDouble("viewport"))
        main { reader.changeFont(1.6) }
        waitFor("the larger text is applied") { !main { reader.typographyUpdating } && layout().getDouble("font") > before.getDouble("font") * 1.45 }
        val after = layout()
        assertEquals("The text column keeps its width: before=$before after=$after",
            before.getDouble("column"), after.getDouble("column"), 1.0)
        assertTrue("Larger text wraps onto more lines instead", after.getInt("lines") > before.getInt("lines"))
    }

    /** The text column is the body's capped width minus its side padding, in CSS pixels. */
    private fun layout(): JSONObject = JSONObject(js("""(function(){
        var b=getComputedStyle(document.body), p=document.getElementById('paragraph-0'), r=document.createRange();
        r.selectNode(p.firstChild);
        var viewport=window.innerWidth, cap=parseFloat(b.maxWidth), pad=parseFloat(b.paddingLeft)+parseFloat(b.paddingRight);
        return {viewport:viewport, cap:cap, padding:pad, column:Math.min(viewport, cap)-pad,
            font:parseFloat(getComputedStyle(p).fontSize), lines:r.getClientRects().length};
        })()"""))

    private fun launch() {
        scenario = ActivityScenario.launch<ReaderTestActivity>(Intent(target, ReaderTestActivity::class.java))
        var host: ReaderTestActivity? = null
        scenario!!.onActivity { host = it }
        reader = main { withTimeout(20_000) { host!!.ready.await() } }
        waitFor("the navigator is attached") { main { reader.fragment.isAdded && reader.fragment.view?.width != 0 } }
        waitFor("the chapter renders") {
            runCatching { js("Boolean(document.getElementById('paragraph-0') && window.readium)") == "true" }.getOrDefault(false)
        }
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
