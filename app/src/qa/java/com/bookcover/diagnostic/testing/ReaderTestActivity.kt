package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.ReadiumEngine
import com.bookcover.diagnostic.reader.EpubReaderScreen
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.installReadingKeyHandler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Non-exported host in the separate QA application for real renderer and control tests. */
class ReaderTestActivity : FragmentActivity() {
    internal val ready = CompletableDeferred<EpubSession>()
    internal var reader: EpubSession? = null
        private set
    internal val reports = mutableListOf<String>()
    internal lateinit var library: BookLibrary
        private set
    private lateinit var testDirectory: File

    // EpubSession persists through its FragmentActivity. Redirect only this host's reader settings;
    // instrumentation never changes the user's actual typography, gestures, or book collection.
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        super.getSharedPreferences(if (name == "reader") TEST_PREFERENCES else name, mode)

    override fun attachBaseContext(newBase: Context) {
        val scale = fontScaleForTests
        super.attachBaseContext(if (scale > 0f) newBase.createConfigurationContext(Configuration(newBase.resources.configuration).apply {
            fontScale = scale
        }) else newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        installReadingKeyHandler { reader }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        testDirectory = File(noBackupFilesDir, "native-reader-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(this) {
            override fun getFilesDir(): File = testDirectory
        }
        lifecycleScope.launch {
            try {
                val fixed = intent.getBooleanExtra(EXTRA_FIXED_LAYOUT, false)
                val fixture = withContext(Dispatchers.IO) {
                    if (intent.getBooleanExtra(EXTRA_SEARCH_FIXTURE, false)) SearchEpubFixture.write(testDirectory).file
                    else NativeEpubFixture.write(testDirectory, fixed,
                        progression = intent.getStringExtra(EXTRA_READING_PROGRESSION),
                        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en",
                        imagePages = intent.getBooleanExtra(EXTRA_IMAGE_PAGES, false),
                        legacyOverlay = intent.getBooleanExtra(EXTRA_LEGACY_OVERLAY, false),
                        detailedContents = intent.getBooleanExtra(EXTRA_DETAILED_CONTENTS, false))
                }
                val engine = ReadiumEngine(isolatedContext)
                library = BookLibrary(isolatedContext, engine)
                library.load() // The library refuses to save until it has loaded (BC-02).
                val (book, _) = library.import(android.net.Uri.fromFile(fixture))
                val publication = withContext(Dispatchers.IO) { engine.open(library.epubFile(book)) }
                val saved = getSharedPreferences("reader", Context.MODE_PRIVATE)
                val session = EpubSession(
                    activity = this@ReaderTestActivity, book = book, publication = publication,
                    library = library, initialFontScale = saved.getFloat("fontScale", 1.0f).toDouble(),
                    initialTheme = "Paper", report = { reports += it },
                    initialFontId = saved.getString("fontId", "serif") ?: "serif",
                    initialEdgeTaps = saved.getBoolean("edgeTaps", false),
                )
                reader = session
                setContent {
                    val savedBooks by library.books.collectAsState()
                    MaterialTheme {
                        EpubReaderScreen(
                            session = session, record = savedBooks.firstOrNull { it.id == book.id } ?: book, coverActive = false, coverBusy = false,
                            stopPending = false, canStartCover = false, message = "Native renderer test",
                            onStartCover = {}, onStopCover = {}, onHome = { finish() },
                            onChooseCover = {}, onSaveBookmark = { name, existing -> lifecycleScope.launch {
                                if (existing != null) library.renameBookmark(book.id, existing, name)
                                else session.bookmarkLocator()?.let { library.bookmark(book.id, it, name, session.chapterStatus?.title) }
                            } }, onRemoveBookmark = { value -> lifecycleScope.launch { library.removeBookmark(book.id, value) } },
                        )
                    }
                }
                ready.complete(session)
            } catch (failure: Throwable) {
                ready.completeExceptionally(failure)
            }
        }
    }

    override fun onDestroy() {
        reader?.close()
        super.onDestroy()
        if (::testDirectory.isInitialized && testDirectory.canonicalFile.parentFile == noBackupFilesDir.canonicalFile &&
            testDirectory.name.startsWith("native-reader-test-")) testDirectory.deleteRecursively()
    }

    companion object {
        const val EXTRA_FIXED_LAYOUT = "fixedLayout"
        const val EXTRA_SEARCH_FIXTURE = "searchFixture"
        @Volatile internal var fontScaleForTests = 0f
        const val EXTRA_READING_PROGRESSION = "readingProgression"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_IMAGE_PAGES = "imagePages"
        const val EXTRA_LEGACY_OVERLAY = "legacyOverlay"
        const val EXTRA_DETAILED_CONTENTS = "detailedContents"
        const val TEST_PREFERENCES = "reader-native-instrumentation-test"
    }
}
