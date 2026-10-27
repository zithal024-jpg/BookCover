package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.ContextWrapper
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
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
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
    private var timer by mutableIntStateOf(30)
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
                                books = listOf(book), library = library, busy = false, message = "",
                                autoCover = autoCover, timer = timer, onAutoCover = { autoCover = it },
                                onTimer = { timer = it }, onImport = { importRequests++ },
                                onOpen = { openedBookId = it.id }, onMove = { _, _ -> }, onRemove = {},
                                onExportBackup = {}, onRestoreBackup = {}, onDiagnostics = {},
                                libraryTheme = themeId, onLibraryTheme = { themeId = it },
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
        if (::testDirectory.isInitialized && testDirectory.canonicalFile.parentFile == noBackupFilesDir.canonicalFile &&
            testDirectory.name.startsWith("library-accessibility-")) testDirectory.deleteRecursively()
    }

    companion object {
        @Volatile internal var fontScaleForTests = 1f
    }
}
