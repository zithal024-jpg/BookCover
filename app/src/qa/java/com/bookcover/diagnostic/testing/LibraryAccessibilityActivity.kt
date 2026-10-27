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
                library.load()
                val book = library.importSample("samples/lantern-keeper.epub").first
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

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && ::settingsPreferenceName.isInitialized) deleteSharedPreferences(settingsPreferenceName)
        if (::testDirectory.isInitialized && testDirectory.canonicalFile.parentFile == noBackupFilesDir.canonicalFile &&
            testDirectory.name.startsWith("library-accessibility-")) testDirectory.deleteRecursively()
    }

    companion object {
        @Volatile internal var fontScaleForTests = 1f
        internal const val TEST_COVER_SEED: Int = -15096111 // #19A6D1, deliberately outside the preset seeds.
    }
}
