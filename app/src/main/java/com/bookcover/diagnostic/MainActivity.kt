package com.bookcover.diagnostic

import android.os.Bundle
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.bookcover.diagnostic.reader.readerBackground
import com.bookcover.diagnostic.reader.installReadingKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The QA-only process-recovery probe subclasses this activity without replacing its reader lifecycle.
open class MainActivity : FragmentActivity() {
    private lateinit var engine: ReadiumEngine
    private lateinit var library: BookLibrary
    private lateinit var exterior: ExteriorCoverController
    private var session by mutableStateOf<EpubSession?>(null)
    // Set while a book that was open before rotation/fold is reopened; the library stays hidden meanwhile.
    private var reopeningBookId by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(true)
    private var message by mutableStateOf("Preparing your library…")
    private var showDiagnostics by mutableStateOf(false)
    private var autoCover by mutableStateOf(true)
    private var timer by mutableIntStateOf(30)
    private var libraryTheme by mutableStateOf("automatic")
    private var coverBookId: String? = null
    private val preferences by lazy { getSharedPreferences("reader", 0) }
    private var restoredCover: Pair<Boolean, Long>? = null
    private var restoredReaderLocator: String? = null

    // BC-27: one or many books; progress, Stop and the final summary while they import one at a time.
    private var importProgress by mutableStateOf<ImportProgress?>(null)
    private var importSummary by mutableStateOf<ImportSummary?>(null)
    private var importCancellation: ImportCancellation? = null
    private val importBook = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importBooks(uris)
    }
    private val selectCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = coverBookId
        coverBookId = null
        if (uri != null && id != null) work(ReaderAction.REPLACE_COVER) {
            library.replaceCover(id, uri)
            library.book(id)?.let { book -> exterior.artwork = withContext(Dispatchers.IO) { CoverArtwork.from(library.coverBitmap(book)) } }
            message = "Cover updated. Start the outside cover again from reading controls."
        }
    }

    private val exportLibrary = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work(ReaderAction.SAVE_BACKUP) {
            val count = library.exportBackup(uri)
            message = "Saved reading positions, bookmarks and collections for $count books. The books themselves are not in the backup."
        }
    }
    private val restoreLibrary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work(ReaderAction.RESTORE_BACKUP) {
            val result = library.restoreBackup(uri)
            message = when {
                result.restored == 0 && result.missing == 0 -> "That backup has no books in it."
                result.missing == 0 -> "Restored ${result.restored} books from the backup."
                else -> "Restored ${result.restored} books. ${result.missing} books in the backup are not in your library: " +
                    "import those EPUBs, then restore again."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Navigator restoration needs an open Publication. Reopen from our durable locator instead
        // of asking Android's fragment restoration to construct a navigator without its factory.
        super.onCreate(null)
        installReadingKeyHandler { session }
        DiagnosticLog.init(this)
        library = BookLibrary.forApp(this)
        engine = library.engine
        exterior = ExteriorCoverController(this, lifecycleScope)
        restoredCover = savedInstanceState?.takeIf { it.getString("openBook") != null && it.containsKey("coverRequested") }?.let {
            it.getBoolean("coverRequested") to it.getLong("coverDeadline")
        }
        autoCover = preferences.getBoolean("autoCover", true)
        timer = preferences.getInt("coverTimer", 30).takeIf { it in listOf(0, 30, 60) } ?: 30
        libraryTheme = savedLibraryTheme(preferences.getString("libraryTheme", null))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        reopeningBookId = savedInstanceState?.getString("openBook")
        restoredReaderLocator = savedInstanceState?.getString("openLocator").takeIf { reopeningBookId != null }
        if (reopeningBookId != null) setReadingWindow(true)
        setContent {
            val books by library.books.collectAsState()
            val recent = books.filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt }
            val coverColors by produceState<List<BookCoverColor>>(emptyList(), books.map { it.id to it.coverRevision }) {
                value = library.bookCoverColors()
            }
            val seed = coverColors.firstOrNull { it.bookId == recent?.id }?.seed ?: DEFAULT_COVER_SEED
            // Automatic follows the system light/dark setting; a chosen theme keeps its own.
            val systemDark = isSystemInDarkTheme()
            val libraryDark = LibraryThemeChoice.fromId(libraryTheme).isDark(systemDark)
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !libraryDark
                    isAppearanceLightNavigationBars = !libraryDark
                }
            }
            MaterialTheme(colorScheme = libraryColorScheme(libraryTheme, seed, systemDark)) {
                val current = session
                if (current == null && reopeningBookId != null)
                    Box(Modifier.fillMaxSize().background(readerBackground(preferences.getString("theme", "Paper"))))
                else if (current == null) LibraryScreen(books, library, busy, message, autoCover, timer,
                    onAutoCover = { autoCover = it; preferences.edit().putBoolean("autoCover", it).apply() },
                    onTimer = { timer = it; preferences.edit().putInt("coverTimer", it).apply() },
                    onImport = { importBook.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip")) },
                    onOpen = ::openBook,
                    onMove = { book, collection -> work(ReaderAction.ORGANIZE) { library.move(book.id, collection); message = "Collection updated." } },
                    onRemove = { book -> work(ReaderAction.ORGANIZE) { library.remove(book.id); message = "Local copy removed." } },
                    onExportBackup = { exportLibrary.launch("BookCover-library-${java.time.LocalDate.now()}.json") },
                    onRestoreBackup = { restoreLibrary.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onDiagnostics = { showDiagnostics = true }, libraryTheme = libraryTheme,
                    onLibraryTheme = { libraryTheme = it; preferences.edit().putString("libraryTheme", it).apply() },
                    importProgress = importProgress, onStopImport = { importCancellation?.cancel() }, coverColors = coverColors)
                else EpubReaderScreen(current, books.firstOrNull { it.id == current.book.id } ?: current.book,
                    exterior.active, exterior.busy || exterior.requested, exterior.stopPending, exterior.available && !busy,
                    message + "\n" + exterior.status,
                    onStartCover = { exterior.start(timer) }, onStopCover = { exterior.stop("reader Stop button") },
                    onHome = ::goHome, onChooseCover = {
                        exterior.stop("selecting cover image")
                        coverBookId = current.book.id
                        selectCover.launch(arrayOf("image/*"))
                    }, onSaveBookmark = { name, existingValue -> work(ReaderAction.BOOKMARK) {
                        if (existingValue != null) library.renameBookmark(current.book.id, existingValue, name)
                        else current.bookmarkLocator()?.let { locator -> library.bookmark(current.book.id, locator, name, current.chapterStatus?.title) }
                        message = "Bookmark saved. Open Contents to return to it."
                    }
                    }, onRemoveBookmark = { value -> work(ReaderAction.BOOKMARK) { library.removeBookmark(current.book.id, value) } },
                    libraryTheme = libraryTheme, coverSeed = seed)
                importSummary?.let { ImportSummaryDialog(it, onDismiss = { importSummary = null }) }
                if (showDiagnostics) AlertDialog(onDismissRequest = { showDiagnostics = false }, title = { Text("Device diagnostics") },
                    text = { Text(exterior.diagnostics() + "\n" + DiagnosticLog.report(), modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                    confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } })
            }
        }
        work(ReaderAction.LOAD_LIBRARY) {
            try {
                val loaded = library.load()
                if (!preferences.getBoolean("samplesInstalled", false)) {
                    library.importSample("samples/lantern-keeper.epub")
                    library.importSample("samples/river-maps.epub")
                    preferences.edit().putBoolean("samplesInstalled", true).apply()
                }
                if (loaded.abandonedImportsRemoved > 0)
                    DiagnosticLog.record("Removed ${loaded.abandonedImportsRemoved} unfinished import copies left by an interrupted import")
                if (loaded.recoveredFromBackup || loaded.skipped > 0)
                    DiagnosticLog.record("Library index recovered: backup=${loaded.recoveredFromBackup}, skipped=${loaded.skipped}")
                message = when {
                    loaded.recoveredFromBackup -> "Your library list was damaged, so BookCover restored its last good copy. Recent changes may be missing."
                    loaded.skipped > 0 -> "${loaded.skipped} damaged library entries could not be read and were set aside. Your other books are safe."
                    else -> "Swipe to turn pages. Tap the middle of a page for controls."
                }
                reopeningBookId?.let { id -> library.book(id)?.let { loadBook(it) } }
            } finally {
                // Whatever happened, never leave the plain reopening screen in place.
                reopeningBookId = null
                restoredCover = null
                restoredReaderLocator = null
                if (session == null) setReadingWindow(false)
            }
        }
    }

    // Hooks for the lifecycle tests in the separate QA app (app/src/androidTest); the app does not use them.
    internal val libraryForTests: BookLibrary get() = library
    internal val sessionForTests: EpubSession? get() = session
    internal val busyForTests: Boolean get() = busy
    internal val showsLibraryForTests: Boolean get() = session == null && reopeningBookId == null
    internal fun openBookForTests(book: BookRecord) = openBook(book)

    internal val importProgressForTests: ImportProgress? get() = importProgress
    internal val importSummaryForTests: ImportSummary? get() = importSummary
    internal fun importBooksForTests(uris: List<android.net.Uri>) = importBooks(uris)
    internal fun stopImportForTests() = importCancellation?.cancel()

    /** Imports the picked books; refused while any other library task, including another import, is running. */
    private fun importBooks(uris: List<android.net.Uri>) {
        if (busy) { message = "BookCover is still busy with your library. Wait for it to finish, then import again."; return }
        val cancellation = ImportCancellation()
        importCancellation = cancellation
        work(ReaderAction.IMPORT) {
            try {
                val summary = library.importAll(uris, cancellation) { importProgress = it; message = importProgressMessage(it) }
                if (summary == null) { message = "An import is already running. Wait for it to finish, then try again."; return@work }
                summary.failed.forEach { DiagnosticLog.record(diagnosticSummary(ReaderAction.IMPORT, it.error) + "; document details omitted") }
                message = importSummaryMessage(summary)
                if (uris.size > 1) importSummary = summary
            } finally {
                importProgress = null
                if (importCancellation === cancellation) importCancellation = null
            }
        }
    }

    private fun openBook(book: BookRecord) {
        if (busy || session != null) return
        work(ReaderAction.OPEN) { loadBook(book) }
    }

    private suspend fun loadBook(book: BookRecord) {
        val publication = withContext(Dispatchers.IO) { engine.open(library.epubFile(book)) }
        try {
            exterior.artwork = withContext(Dispatchers.IO) { CoverArtwork.from(library.coverBitmap(book)) }
            val record = library.book(book.id) ?: book
            // The Bundle is the immediate recreation snapshot; disk saves may still be finishing.
            val restoredRecord = restoredReaderLocator?.takeIf { book.id == reopeningBookId }
                ?.let { record.copy(locator = it) } ?: record
            val current = EpubSession(this, restoredRecord, publication, library,
                preferences.getFloat("fontScale", 1.0f).toDouble(), preferences.getString("theme", "Paper") ?: "Paper",
                report = { message = it }, initialFontId = preferences.getString("fontId", "serif") ?: "serif",
                initialEdgeTaps = preferences.getBoolean("edgeTaps", false))
            library.markOpened(book.id)
            session = current
            setReadingWindow(true)
            message = "Swipe left/right to turn pages. Tap the middle for controls."
            DiagnosticLog.record("Opened local EPUB in host activity; metadata omitted")
            val previousCover = restoredCover
            restoredCover = null
            if (previousCover != null) exterior.restore(previousCover.first, previousCover.second)
            else if (autoCover && exterior.available) exterior.start(timer)
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

    /** Runs [block]; a failure shows a plain message for [action] and logs only technical types (BC-26). */
    private fun work(action: ReaderAction, block: suspend () -> Unit) {
        lifecycleScope.launch {
            busy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = readerMessage(action, error)
                DiagnosticLog.record(diagnosticSummary(action, error) + "; document details omitted")
            } finally { busy = false }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("openBook", session?.book?.id ?: reopeningBookId)
        outState.putString("openLocator", session?.restorationLocator?.toJSON()?.toString() ?: restoredReaderLocator)
        outState.putBoolean("coverRequested", exterior.requested)
        outState.putLong("coverDeadline", exterior.deadline)
        session?.flushPosition()
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Freeze the last visible semantic passage before the navigator receives the new viewport.
        exterior.viewportChanging()
        session?.viewportChanging { exterior.viewportSettled() } ?: exterior.viewportSettled()
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        exterior.hostResumed()
    }

    override fun onStop() {
        // Android may end the process while the app is in the background; save the position first.
        session?.flushPosition()
        super.onStop()
    }

    override fun onDestroy() {
        exterior.close()
        session?.close()
        super.onDestroy()
    }
}
