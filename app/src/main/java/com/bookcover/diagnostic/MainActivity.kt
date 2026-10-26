package com.bookcover.diagnostic

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.cover.CoverArtwork
import com.bookcover.diagnostic.cover.ExteriorCoverController
import com.bookcover.diagnostic.library.*
import com.bookcover.diagnostic.reader.EpubReaderScreen
import com.bookcover.diagnostic.reader.EpubSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private lateinit var engine: ReadiumEngine
    private lateinit var library: BookLibrary
    private lateinit var exterior: ExteriorCoverController
    private var session by mutableStateOf<EpubSession?>(null)
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf("Preparing your library…")
    private var showDiagnostics by mutableStateOf(false)
    private var autoCover by mutableStateOf(true)
    private var timer by mutableIntStateOf(30)
    private var libraryTheme by mutableStateOf("automatic")
    private var coverBookId: String? = null
    private val preferences by lazy { getSharedPreferences("reader", 0) }

    private val importBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val (book, added) = library.import(uri)
            message = if (added) "Imported ${book.title}. Tap its cover to read." else "${book.title} is already in your library."
        }
    }
    private val selectCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverBookId
        coverBookId = null
        if (uri != null && id != null) work {
            library.replaceCover(id, uri)
            library.book(id)?.let { book -> exterior.artwork = withContext(Dispatchers.IO) { CoverArtwork.from(library.coverBitmap(book)) } }
            message = "Cover updated. Start the outside cover again from reading controls."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Navigator restoration needs an open Publication. Reopen from our durable locator instead
        // of asking Android's fragment restoration to construct a navigator without its factory.
        super.onCreate(null)
        DiagnosticLog.init(this)
        engine = ReadiumEngine(this)
        library = BookLibrary(this, engine)
        exterior = ExteriorCoverController(this, lifecycleScope)
        autoCover = preferences.getBoolean("autoCover", true)
        timer = preferences.getInt("coverTimer", 30).takeIf { it in listOf(0, 30, 60) } ?: 30
        libraryTheme = LibraryThemeChoice.fromId(preferences.getString("libraryTheme", "automatic") ?: "automatic").id
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val books by library.books.collectAsState()
            val recent = books.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }
            val seed by produceState(DEFAULT_COVER_SEED, recent?.id, recent?.coverRevision) {
                value = recent?.let { book -> withContext(Dispatchers.IO) { coverThemeSeed(library.coverBitmap(book)) } } ?: DEFAULT_COVER_SEED
            }
            MaterialTheme(colorScheme = libraryColorScheme(libraryTheme, seed)) {
                val current = session
                if (current == null) LibraryScreen(books, library, busy, message, autoCover, timer,
                    onAutoCover = { autoCover = it; preferences.edit().putBoolean("autoCover", it).apply() },
                    onTimer = { timer = it; preferences.edit().putInt("coverTimer", it).apply() },
                    onImport = { importBook.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip")) },
                    onOpen = ::openBook,
                    onMove = { book, collection -> work { library.move(book.id, collection); message = "Collection updated." } },
                    onRemove = { book -> work { library.remove(book.id); message = "Local copy removed." } },
                    onDiagnostics = { showDiagnostics = true }, libraryTheme = libraryTheme,
                    onLibraryTheme = { libraryTheme = it; preferences.edit().putString("libraryTheme", it).apply() })
                else EpubReaderScreen(current, books.firstOrNull { it.id == current.book.id } ?: current.book,
                    exterior.active, exterior.busy, exterior.stopPending, exterior.available && !busy,
                    message + "\n" + exterior.status,
                    onStartCover = { exterior.start(timer) }, onStopCover = { exterior.stop("reader Stop button") },
                    onHome = ::goHome, onChooseCover = {
                        exterior.stop("selecting cover image")
                        coverBookId = current.book.id
                        selectCover.launch(arrayOf("image/*"))
                    }, onBookmark = {
                        current.currentLocator?.let { locator -> work {
                            library.bookmark(current.book.id, locator); message = "Bookmark saved. Open Contents to return to it."
                        } }
                    }, onRemoveBookmark = { value -> work { library.removeBookmark(current.book.id, value) } })
                if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text("Device diagnostics") },
                    text = { Text(exterior.diagnostics() + "\n" + DiagnosticLog.report(), modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                    confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } })
            }
        }
        work {
            val loaded = library.load()
            if (!preferences.getBoolean("samplesInstalled", false)) {
                library.importSample("samples/lantern-keeper.epub")
                library.importSample("samples/river-maps.epub")
                preferences.edit().putBoolean("samplesInstalled", true).apply()
            }
            if (loaded.recoveredFromBackup || loaded.skipped > 0)
                DiagnosticLog.record("Library index recovered: backup=${loaded.recoveredFromBackup}, skipped=${loaded.skipped}")
            message = when {
                loaded.recoveredFromBackup -> "Your library list was damaged, so BookCover restored its last good copy. Recent changes may be missing."
                loaded.skipped > 0 -> "${loaded.skipped} damaged library entries could not be read and were set aside. Your other books are safe."
                else -> "Swipe to turn pages. Tap the middle of a page for controls."
            }
            savedInstanceState?.getString("openBook")?.let { id -> library.book(id)?.let { loadBook(it) } }
        }
    }

    private fun openBook(book: BookRecord) {
        if (busy || session != null) return
        work { loadBook(book) }
    }

    private suspend fun loadBook(book: BookRecord) {
        val publication = withContext(Dispatchers.IO) { engine.open(library.epubFile(book)) }
        try {
            exterior.artwork = withContext(Dispatchers.IO) { CoverArtwork.from(library.coverBitmap(book)) }
            val current = EpubSession(this, library.book(book.id) ?: book, publication, library,
                preferences.getFloat("fontScale", 1.0f).toDouble(), preferences.getString("theme", "Paper") ?: "Paper",
                report = { message = it }, initialFontId = preferences.getString("fontId", "serif") ?: "serif",
                initialEdgeTaps = preferences.getBoolean("edgeTaps", false))
            library.markOpened(book.id)
            session = current
            setReadingWindow(true)
            message = "Swipe left/right to turn pages. Tap the middle for controls."
            DiagnosticLog.record("Opened local EPUB in host activity; metadata omitted")
            if (autoCover && exterior.available) exterior.start(timer)
        } catch (error: Exception) { publication.close(); throw error }
    }

    private fun goHome() {
        exterior.stop("returning to library")
        session?.close(); session = null
        setReadingWindow(false)
        message = "Your reading position is saved."
    }

    private fun setReadingWindow(reading: Boolean) {
        if (reading) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (reading) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun work(action: suspend () -> Unit) {
        lifecycleScope.launch {
            busy = true
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = error.message?.take(180) ?: "This operation could not be completed."
                DiagnosticLog.record("EPUB operation failed: ${error.javaClass.simpleName}; document details omitted")
            } finally { busy = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("openBook", session?.book?.id)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        exterior.close()
        session?.close()
        super.onDestroy()
    }
}
