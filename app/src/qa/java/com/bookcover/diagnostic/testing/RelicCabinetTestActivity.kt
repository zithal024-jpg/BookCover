package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.ReadiumEngine
import com.bookcover.diagnostic.library.libraryColorScheme
import com.bookcover.diagnostic.rewards.RelicAppearance
import com.bookcover.diagnostic.rewards.RelicCabinet
import com.bookcover.diagnostic.rewards.RelicCollection
import com.bookcover.diagnostic.rewards.RelicStore
import com.bookcover.diagnostic.rewards.MoonlitTier
import com.bookcover.diagnostic.streak.ReadingDay
import com.bookcover.diagnostic.streak.ReadingDays
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Generated books and a private fixture directory only. Never opens the app's shared library. */
class RelicCabinetTestActivity : FragmentActivity() {
    internal val ready = CompletableDeferred<BookRecord>()
    internal lateinit var libraryForTests: BookLibrary
        private set
    internal var showCabinet by mutableStateOf(true)
        private set
    internal var busy by mutableStateOf(false)
        private set
    internal var saveRequests = 0
        private set
    internal var resetRequests = 0
        private set
    internal var dismissRequests = 0
        private set
    internal var lastSavedId: String? = null
        private set
    internal var lastSavedAppearance: RelicAppearance? = null
        private set
    internal var failureForTests: Throwable? = null
        private set
    internal var testingActive by mutableStateOf(false)
        private set
    internal var previewRequests = 0
        private set
    internal var lastPreviewId: String? = null
        private set
    internal var lastPreviewAppearance: RelicAppearance? = null
        private set
    internal var previewClosedRequests = 0
        private set
    internal var uiDensityForTests = 1f
        private set
    private lateinit var testDirectory: File

    override fun attachBaseContext(newBase: Context) {
        val bounds = newBase.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val densityDpiForFixture = (bounds.width().coerceAtLeast(1) / 360f * 160f).roundToInt().coerceAtLeast(1)
        super.attachBaseContext(newBase.createConfigurationContext(Configuration(newBase.resources.configuration).apply {
            fontScale = fontScaleForTests
            densityDpi = densityDpiForFixture
            screenWidthDp = 360
            screenHeightDp = (bounds.height() / (densityDpiForFixture / 160f)).roundToInt()
            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
        }))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        testDirectory = File(noBackupFilesDir, "relic-cabinet-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(this) {
            override fun getFilesDir(): File = testDirectory
        }
        // The dialog inherits the native context configuration above. Both Compose controls and
        // accessibility measurements use this same density; no system setting is changed.
        uiDensityForTests = resources.displayMetrics.density
        lifecycleScope.launch {
            try {
                val library = BookLibrary(isolated, ReadiumEngine(isolated))
                libraryForTests = library
                library.load()
                val book = library.importSample("samples/lantern-keeper.epub").first
                withContext(Dispatchers.IO) {
                    library.relics.reconcile(ReadingDays(mapOf(LocalDate.of(2026, 11, 1) to ReadingDay(300_000, true))))
                }
                setContent {
                    val collection by library.relics.collection.collectAsState()
                    MaterialTheme(colorScheme = libraryColorScheme("paper")) {
                            if (showCabinet) RelicCabinet(collection, listOf(book), library,
                                onSave = { id, appearance ->
                                    if (!busy) {
                                        busy = true
                                        lifecycleScope.launch {
                                            try {
                                                // The UI fixture captures test choices separately; genuine
                                                // reward/appearance persistence stays untouched while testing.
                                                if (!testingActive) withContext(Dispatchers.IO) { library.relics.setAppearance(id, appearance) }
                                                lastSavedId = id
                                                lastSavedAppearance = appearance
                                                saveRequests++
                                                showCabinet = false
                                            } catch (failure: Throwable) { failureForTests = failure }
                                            finally { busy = false }
                                        }
                                    }
                                },
                                onReset = { id ->
                                    if (!busy) {
                                        busy = true
                                        lifecycleScope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { library.relics.resetBookAppearance(id) }
                                                resetRequests++
                                                showCabinet = false
                                            } catch (failure: Throwable) { failureForTests = failure }
                                            finally { busy = false }
                                        }
                                    }
                                },
                                onSeen = { lifecycleScope.launch(Dispatchers.IO) { library.relics.markSeen() } },
                                onDismiss = { if (!busy) { dismissRequests++; showCabinet = false } }, busy = busy,
                                availableTiers = if (testingActive) MoonlitTier.entries.toSet() else collection.unlocked,
                                testingActive = testingActive,
                                testingExpiryLabel = if (testingActive) "Testing access ends in 24 hours" else null,
                                onStartTesting = if (testingControlsForTests) ({ testingActive = true }) else null,
                                onEndTesting = if (testingControlsForTests) ({ testingActive = false }) else null,
                                outsideAspectRatio = outsideAspectRatioForTests,
                                onPreview = { id, appearance ->
                                    lastPreviewId = id
                                    lastPreviewAppearance = appearance
                                    previewRequests++
                                },
                                onPreviewClosed = { previewClosedRequests++ })
                            else Text("Generated relic fixture")
                    }
                }
                ready.complete(book)
            } catch (failure: Throwable) { ready.completeExceptionally(failure) }
        }
    }

    internal fun reopenCabinetForTests() { showCabinet = true }

    /** Blocking; tests call this from their worker thread after the callback has completed. */
    internal fun readStoredCollectionForTests(): RelicCollection =
        RelicStore(File(testDirectory, "library/reading-relics.json")).load()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !busy && ::testDirectory.isInitialized &&
            testDirectory.canonicalFile.parentFile == noBackupFilesDir.canonicalFile && testDirectory.name.startsWith("relic-cabinet-")) {
            testDirectory.deleteRecursively()
        }
    }

    companion object {
        @Volatile internal var fontScaleForTests = 1f
        @Volatile internal var testingControlsForTests = false
        @Volatile internal var outsideAspectRatioForTests = .41f
    }
}
