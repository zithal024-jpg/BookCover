package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bookcover.diagnostic.library.libraryColorScheme
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.BookCoverColor
import com.bookcover.diagnostic.library.LibraryScreen
import com.bookcover.diagnostic.library.ReadiumEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Generated-sample library with local text scaling; never changes system settings or user books. */
class LibraryAccessibilityActivity : FragmentActivity() {
    internal val ready = CompletableDeferred<BookRecord>()
    internal var importRequests = 0
        private set
    internal var openedBookId: String? = null
        private set
    internal var autoCover by mutableStateOf(false)
        private set
    internal var themeId by mutableStateOf("automatic")
        private set
    internal var timer by mutableIntStateOf(30)
        private set
    internal var busy by mutableStateOf(false)
    internal var exportRequests = 0
        private set
    internal var restoreRequests = 0
        private set
    internal lateinit var libraryForTests: BookLibrary
        private set
    internal var diagnosticRequests = 0
        private set
    private lateinit var settingsPreferences: SharedPreferences
    private lateinit var settingsPreferenceName: String
    private lateinit var testDirectory: File

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createConfigurationContext(Configuration(newBase.resources.configuration).apply {
            fontScale = fontScaleForTests
        }))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settingsPreferenceName = "library-settings-test-" + intent.getStringExtra("settingsSession").orEmpty()
        settingsPreferences = getSharedPreferences(settingsPreferenceName, 0)
        autoCover = settingsPreferences.getBoolean("autoCover", false)
        timer = settingsPreferences.getInt("coverTimer", 30)
        themeId = settingsPreferences.getString("libraryTheme", "automatic") ?: "automatic"
        testDirectory = File(noBackupFilesDir, "library-accessibility-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(this) {
            override fun getFilesDir(): File = testDirectory
        }
        lifecycleScope.launch {
            try {
                val library = BookLibrary(isolated, ReadiumEngine(isolated))
                libraryForTests = library
                library.load()
                val book = library.importSample("samples/lantern-keeper.epub").first
                val scene = intent.getStringExtra("scene")
                if (scene != null) { showScene(scene, library, book); ready.complete(book); return@launch }
                setContent {
                    MaterialTheme {
                        // 360 dp exercises the folded-phone layout while the owner keeps the phone open.
                        Box(Modifier.fillMaxHeight().width(360.dp).semantics { testTagsAsResourceId = true }) {
                            LibraryScreen(
                                books = listOf(book), library = library, busy = busy, message = "",
                                autoCover = autoCover, timer = timer, onAutoCover = {
                                    autoCover = it; settingsPreferences.edit { putBoolean("autoCover", it) }
                                },
                                onTimer = { timer = it; settingsPreferences.edit { putInt("coverTimer", it) } }, onImport = { importRequests++ },
                                onOpen = { openedBookId = it.id }, onMove = { _, _ -> }, onRemove = {},
                                onExportBackup = { exportRequests++ }, onRestoreBackup = { restoreRequests++ }, onDiagnostics = { diagnosticRequests++ },
                                libraryTheme = themeId, onLibraryTheme = {
                                    themeId = it; settingsPreferences.edit { putString("libraryTheme", it) }
                                },
                                coverColors = listOf(BookCoverColor(book.id, book.title, TEST_COVER_SEED)),
                            )
                        }
                    }
                }
                ready.complete(book)
            } catch (failure: Throwable) { ready.completeExceptionally(failure) }
        }
    }

    /**
     * Screenshot scenes for the Library Home review: the whole window, the app's own cover-derived palette
     * and generated books only. "home" has a book in progress and a mixed collection; "empty" has no books.
     */
    private suspend fun showScene(scene: String, library: BookLibrary, sample: BookRecord) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = intent.getBooleanExtra("night", false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night; isAppearanceLightNavigationBars = !night
        }
        val now = System.currentTimeMillis()
        val books = if (scene == "empty") emptyList() else {
            val river = library.importSample("samples/river-maps.epub").first
            val locator = """{"href":"chapter-3.xhtml","type":"application/xhtml+xml","title":"The Long Stair",""" +
                """"locations":{"progression":0.4,"totalProgression":0.42,"position":38}}"""
            listOf(sample.copy(lastReadAt = now - 2 * 3_600_000L, progression = 0.42, locator = locator),
                river.copy(lastReadAt = now - 3 * 86_400_000L, progression = 0.87)) +
                GENERATED_TITLES.mapIndexed { index, (title, author, collection) ->
                    BookRecord("%064x".format(index + 1), title, author, now - (index + 5) * 86_400_000L, collection,
                        progression = if (index % 3 == 0) 0.15 * (index % 5) else 0.0)
                }
        }
        val colors = library.bookCoverColors()
        val seed = colors.firstOrNull { it.bookId == sample.id }?.seed ?: TEST_COVER_SEED
        setContent {
            MaterialTheme(colorScheme = libraryColorScheme("automatic", seed, night)) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    LibraryScreen(books = books, library = library, busy = false, message = "",
                        autoCover = false, timer = 30, onAutoCover = {}, onTimer = {}, onImport = { importRequests++ },
                        onOpen = { openedBookId = it.id }, onMove = { _, _ -> }, onRemove = {},
                        onExportBackup = {}, onRestoreBackup = {}, onDiagnostics = {},
                        libraryTheme = "automatic", onLibraryTheme = {}, coverColors = colors)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::settingsPreferenceName.isInitialized) deleteSharedPreferences(settingsPreferenceName)
        if (::testDirectory.isInitialized && testDirectory.canonicalFile.parentFile == noBackupFilesDir.canonicalFile &&
            testDirectory.name.startsWith("library-accessibility-")) testDirectory.deleteRecursively()
    }

    companion object {
        @Volatile internal var fontScaleForTests = 1f
        internal const val TEST_COVER_SEED: Int = -15096111 // #19A6D1, deliberately outside the preset seeds.

        /** Invented books without cover files, so they show the generated typographic cover. */
        private val GENERATED_TITLES = listOf(
            Triple("Salt and Starlight", "Imogen Hale", "Books"),
            Triple("The Cartographer's Daughter and the Extraordinarily Long Road North", "Tobias Wren-Abernathy", "Books"),
            Triple("Quiet Engines", "M. A. Okafor", "Books"),
            Triple("A Field Guide to Small Rivers", "Ana Lucía Ferreira", "Nature"),
            Triple("Winter Orchard", "Sun-hee Park", "Poetry"),
            Triple("The Glass Archive", "Dmitri Volkov", "Books"),
            Triple("Night Ferry", "Claire Duval", "Books"),
            Triple("Paper Lanterns", "Kenji Morimoto", "Poetry"),
            Triple("Under the Linden", "Greta Lindqvist", "Books"),
            Triple("Moss & Stone", "Ruth Adeyemi", "Nature"),
            Triple("The Last Lighthouse Keeper's Almanac", "Henrietta Bloom", "Books"),
            Triple("Echoes", "J. Ortiz", "Books"),
        )
    }
}
