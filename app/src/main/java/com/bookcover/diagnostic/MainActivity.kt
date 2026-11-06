package com.bookcover.diagnostic

import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.bookcover.diagnostic.reader.InnerScreenAwake
import com.bookcover.diagnostic.reader.InnerScreenIdle
import com.bookcover.diagnostic.reader.INNER_SCREEN_IDLE_KEY
import com.bookcover.diagnostic.reader.innerScreenIdle
import com.bookcover.diagnostic.reader.PdfSession
import com.bookcover.diagnostic.reader.PdfReaderScreen
import com.bookcover.diagnostic.reader.readerBackground
import com.bookcover.diagnostic.reader.installReadingKeyHandler
import com.bookcover.diagnostic.rewards.RelicCabinet
import com.bookcover.diagnostic.rewards.RelicAppearance
import com.bookcover.diagnostic.rewards.RelicMode
import com.bookcover.diagnostic.rewards.RelicTestAccess
import com.bookcover.diagnostic.rewards.RelicCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The QA-only process-recovery probe subclasses this activity without replacing its reader lifecycle.
open class MainActivity : FragmentActivity() {
    private lateinit var engine: ReadiumEngine
    private lateinit var library: BookLibrary
    private lateinit var exterior: ExteriorCoverController
    private lateinit var relicTesting: RelicTestAccess
    private var session by mutableStateOf<EpubSession?>(null)
    private var pdfSession by mutableStateOf<PdfSession?>(null)
    // Set while a book that was open before rotation/fold is reopened; the library stays hidden meanwhile.
    private var reopeningBookId by mutableStateOf<String?>(null)
    private val operations = OperationState()
    private val busy: Boolean get() = operations.busy
    private var message: String
        get() = operations.message
        set(value) { operations.message = value }
    private var showDiagnostics by mutableStateOf(false)
    private var showRelicCabinet by mutableStateOf(false)
    private var relicSaveError by mutableStateOf<String?>(null)
    private var pendingRelicTesting by mutableStateOf(false)
    private data class RelicPreviewDraft(val bookId: String?, val appearance: RelicAppearance)
    private var relicPreviewDraft by mutableStateOf<RelicPreviewDraft?>(null)
    private var previewResumeVersion by mutableIntStateOf(0)
    private var autoCover by mutableStateOf(true)
    private var timer by mutableIntStateOf(30)
    private var libraryTheme by mutableStateOf("automatic")
    private var coverBookId: String? = null
    private val preferences by lazy { getSharedPreferences("reader", 0) }
    private var restoredCover: Pair<Boolean, Long>? = null
    private var restoredReaderLocator: String? = null
    private var screenIdle by mutableStateOf(InnerScreenIdle.DEFAULT)
    private val screenAwake by lazy { InnerScreenAwake(screenIdle) }
    private val awakeHandler = Handler(Looper.getMainLooper())
    private var awakeCheck: Runnable? = null

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
            if (session?.book?.id == id || pdfSession?.book?.id == id) library.book(id)?.let { book ->
                exterior.artwork = withContext(Dispatchers.IO) { CoverArtwork.from(library.coverBitmap(book)) }
            }
            message = "Cover updated. Start the outside cover again from reading controls."
        }
    }

    private val exportLibrary = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work(ReaderAction.SAVE_BACKUP) {
            val count = library.exportBackup(uri)
            message = "Saved positions, bookmarks and collections for $count books, plus reading history, relics and appearance choices. The books themselves are not in the backup."
        }
    }
    private val restoreLibrary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work(ReaderAction.RESTORE_BACKUP) {
            val result = library.restoreBackup(uri)
            val booksMessage = when {
                result.restored == 0 && result.missing == 0 && result.readingRewardsRestored -> "Restored reading history, relics and appearance choices."
                result.restored == 0 && result.missing == 0 -> "That backup has no books in it."
                result.missing == 0 -> "Restored ${result.restored} books from the backup."
                else -> "Restored ${result.restored} books. ${result.missing} books in the backup are not in your library: " +
                    "import those books, then restore again."
            }
            message = booksMessage + if (result.readingRewardsRestored && (result.restored > 0 || result.missing > 0))
                " Reading history, relics and appearance choices were also restored." else ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Navigator restoration needs an open Publication. Reopen from our durable locator instead
        // of asking Android's fragment restoration to construct a navigator without its factory.
        super.onCreate(null)
        // Keep picker routing/pending results even though navigator fragments cannot be restored.
        coverBookId = savedInstanceState?.getString("pendingCoverBook")
        showRelicCabinet = savedInstanceState?.getBoolean("relicCabinet", false) ?: false
        savedInstanceState?.getBundle("documentPickerResults")?.let(activityResultRegistry::onRestoreInstanceState)
        installReadingKeyHandler(pdfReader = { pdfSession }, onInput = ::readerInput) { session }
        DiagnosticLog.init(this)
        library = BookLibrary.forApp(this)
        relicTesting = RelicTestAccess(this)
        pendingRelicTesting = savedInstanceState?.getBoolean("pendingRelicTesting", false) ?: false
        acceptRelicTestingRequest(intent)
        engine = library.engine
        exterior = ExteriorCoverController(this, lifecycleScope)
        restoredCover = savedInstanceState?.takeIf { it.getString("openBook") != null && it.containsKey("coverRequested") }?.let {
            it.getBoolean("coverRequested") to it.getLong("coverDeadline")
        }
        autoCover = preferences.getBoolean("autoCover", true)
        timer = preferences.getInt("coverTimer", 30).takeIf { it in listOf(0, 30, 60) } ?: 30
        libraryTheme = savedLibraryTheme(preferences.getString("libraryTheme", null))
        screenIdle = preferences.innerScreenIdle()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        reopeningBookId = savedInstanceState?.getString("openBook")
        restoredReaderLocator = savedInstanceState?.getString("openLocator").takeIf { reopeningBookId != null }
        if (reopeningBookId != null) {
            setReadingWindow(true)
            applyAwake(screenAwake.restored(savedInstanceState?.getLong("screenLastInput", -1L)?.takeIf { it >= 0 }, SystemClock.elapsedRealtime()))
        }
        setContent {
            val books by library.books.collectAsState()
            val relicCollection by library.relics.collection.collectAsState()
            val relicError by library.relicError.collectAsState()
            val relicsLoaded by library.relicsLoaded.collectAsState()
            val testingState by relicTesting.state.collectAsState()
            val testingActive = relicTesting.supported && testingState.isActive(System.currentTimeMillis())
            val editingCollection = if (testingActive) relicCollection.copy(
                defaultAppearance = testingState.defaultAppearance, bookAppearances = testingState.bookAppearances,
            ) else relicCollection
            val availableRelics = if (testingActive) RelicCatalog.all.toSet() else relicCollection.unlockedRelics
            LaunchedEffect(testingState.expiresAtMillis) {
                while (relicTesting.state.value.startedAtMillis != 0L) {
                    delay((relicTesting.state.value.expiresAtMillis - System.currentTimeMillis()).coerceIn(1L, 60_000L))
                    withContext(Dispatchers.IO) { relicTesting.refresh() }
                }
            }
            LaunchedEffect(pendingRelicTesting, relicsLoaded, busy) {
                if (pendingRelicTesting && relicsLoaded && !busy) {
                    pendingRelicTesting = false
                    work(ReaderAction.ORGANIZE) {
                        saveRelicChange { relicTesting.begin(library.relics.collection.value) }
                        showRelicCabinet = true
                        message = "All relic sets are available for temporary testing. Your earned rewards are unchanged."
                    }
                }
            }
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
                val pdf = pdfSession
                val activeBook = current?.book ?: pdf?.book
                val activeAppearance = activeBook?.let { editingCollection.appearanceForRelics(it.id, availableRelics) }
                LaunchedEffect(activeBook?.id, activeAppearance) {
                    if (activeBook != null && activeAppearance != null)
                        exterior.setAppearance(activeAppearance, activeBook.title, activeBook.author)
                }
                val previewDraft = relicPreviewDraft.takeIf { showRelicCabinet }
                val previewBook = previewDraft?.let { draft ->
                    books.firstOrNull { it.id == draft.bookId }
                        ?: books.maxByOrNull { maxOf(it.lastReadAt, it.addedAt) }
                }
                // Decode once per book/revision. Color, family and tier edits reuse the prepared art.
                val previewArtwork by produceState<CoverArtwork?>(null, previewBook?.id, previewBook?.coverRevision) {
                    value = null
                    previewBook?.let { book -> value = withContext(Dispatchers.IO) {
                        runCatching { CoverArtwork.from(library.coverBitmap(book)) }.getOrNull()
                    } }
                }
                LaunchedEffect(showRelicCabinet, previewDraft, previewArtwork, previewBook, previewResumeVersion) {
                    if (previewDraft != null) {
                        val shown = if (previewArtwork == null) previewDraft.appearance.copy(enabled = true, mode = RelicMode.STANDALONE)
                            else previewDraft.appearance
                        exterior.preview(previewArtwork, shown, previewBook?.title ?: "Reading Relics",
                            previewBook?.author ?: "BookCover")
                    } else exterior.endPreview()
                }
                if (current == null && pdf == null && reopeningBookId != null)
                    Box(Modifier.fillMaxSize().background(readerBackground(preferences.getString("theme", "Paper"))))
                else if (current == null && pdf == null) LibraryScreen(books, library, busy, message, autoCover, timer,
                    onAutoCover = { autoCover = it; preferences.edit().putBoolean("autoCover", it).apply() },
                    onTimer = { timer = it; preferences.edit().putInt("coverTimer", it).apply() },
                    onImport = { importBook.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream", "application/zip")) },
                    onOpen = ::openBook,
                    onMove = { book, collection -> work(ReaderAction.ORGANIZE) { library.move(book.id, collection); message = "Collection updated." } },
                    onRemove = { book -> work(ReaderAction.ORGANIZE) { library.remove(book.id); message = "Local copy removed." } },
                    onExportBackup = { exportLibrary.launch("BookCover-library-${java.time.LocalDate.now()}.json") },
                    onRestoreBackup = { restoreLibrary.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onDiagnostics = { showDiagnostics = true }, libraryTheme = libraryTheme,
                    onLibraryTheme = { libraryTheme = it; preferences.edit().putString("libraryTheme", it).apply() },
                    importProgress = importProgress, onStopImport = { importCancellation?.cancel() }, coverColors = coverColors,
                    onOpenRelics = { if (!busy) { relicSaveError = null; showRelicCabinet = true } },
                    relicSummary = when (relicCollection.unseenRelics.size) {
                        0 -> "${relicCollection.unlockedRelics.size} of ${RelicCatalog.all.size} collected"
                        1 -> "1 new relic"
                        else -> "${relicCollection.unseenRelics.size} new relics"
                    },
                    onDismissMessage = { message = "" })
                else if (pdf != null) PdfReaderScreen(pdf, books.firstOrNull { it.id == pdf.book.id } ?: pdf.book,
                    exterior.active, exterior.busy || exterior.requested, exterior.stopPending, exterior.available && !busy,
                    message + "\n" + exterior.status,
                    onStartCover = { exterior.start(timer) }, onStopCover = { exterior.stop("reader Stop button") },
                    onHome = ::goHome, onChooseCover = {
                        exterior.stop("selecting cover image")
                        coverBookId = pdf.book.id
                        selectCover.launch(arrayOf("image/*"))
                    }, onSaveBookmark = { name, existingValue -> work(ReaderAction.BOOKMARK) {
                        if (existingValue != null) library.renameBookmark(pdf.book.id, existingValue, name)
                        else pdf.bookmarkLocator()?.let { library.bookmark(pdf.book.id, it, name, null) }
                        message = "Bookmark saved. Open Contents to return to it."
                    } }, onRemoveBookmark = { value -> work(ReaderAction.BOOKMARK) { library.removeBookmark(pdf.book.id, value) } },
                    libraryTheme = libraryTheme, coverSeed = seed)
                else EpubReaderScreen(current!!, books.firstOrNull { it.id == current.book.id } ?: current.book,
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
                    libraryTheme = libraryTheme, coverSeed = seed, screenIdle = screenIdle, onScreenIdle = ::chooseScreenIdle)
                importSummary?.let { ImportSummaryDialog(it, onDismiss = { importSummary = null }) }
                if (showRelicCabinet && relicsLoaded) {
                    if (relicError != null) AlertDialog(onDismissRequest = { showRelicCabinet = false },
                        title = { Text("Relic cabinet unavailable") }, text = { Text(relicError!!) },
                        confirmButton = { TextButton(onClick = { showRelicCabinet = false }) { Text("Close") } })
                    else key(testingState.startedAtMillis, testingState.expiresAtMillis) {
                    RelicCabinet(editingCollection, books, library,
                        onSave = { id, appearance -> if (!busy) work(ReaderAction.ORGANIZE) {
                            saveRelicChange {
                                if (testingActive) relicTesting.saveAppearance(id, appearance)
                                else library.relics.setAppearance(id, appearance)
                            }
                            showRelicCabinet = false
                            message = if (testingActive) "Test appearance saved. Your earned settings return when testing ends."
                                else "Outside appearance saved${if (id == null) " as your default" else " for this book"}."
                        } },
                        onReset = { id -> if (!busy) work(ReaderAction.ORGANIZE) {
                            saveRelicChange {
                                if (testingActive) relicTesting.resetBookAppearance(id)
                                else library.relics.resetBookAppearance(id)
                            }
                            showRelicCabinet = false; message = "This book now uses your default outside appearance."
                        } },
                        onSeen = { lifecycleScope.launch(Dispatchers.IO) {
                            runCatching { library.relics.markSeen() }.onFailure {
                                DiagnosticLog.record("Relic acknowledgement failed: ${it.javaClass.simpleName}")
                            }
                        } }, onDismiss = { if (!busy) showRelicCabinet = false }, busy = busy,
                        availableRelics = availableRelics, testingActive = testingActive,
                        testingExpiryLabel = if (testingActive) "Testing ends " + java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(testingState.expiresAtMillis)) else null,
                        onStartTesting = if (relicTesting.supported) ({ if (!busy) work(ReaderAction.ORGANIZE) {
                            saveRelicChange { relicTesting.begin(library.relics.collection.value) }
                        } }) else null,
                        onEndTesting = if (testingActive) ({ if (!busy) work(ReaderAction.ORGANIZE) {
                            saveRelicChange { relicTesting.end() }
                            message = "Testing ended. Your earned outside appearance is restored."
                        } }) else null,
                        outsidePreviewStatus = exterior.status,
                        outsideAspectRatio = exterior.outsideAspectRatio,
                        onRetryOutsidePreview = if (!exterior.active && !exterior.busy) ({
                            previewDraft?.let { draft ->
                                val shown = if (previewArtwork == null) draft.appearance.copy(enabled = true, mode = RelicMode.STANDALONE)
                                    else draft.appearance
                                exterior.preview(previewArtwork, shown, previewBook?.title ?: "Reading Relics", previewBook?.author ?: "BookCover")
                                exterior.retryPreview()
                            }
                        }) else null,
                        onPreview = { id, appearance -> relicPreviewDraft = RelicPreviewDraft(id, appearance) },
                        onPreviewClosed = { relicPreviewDraft = null; exterior.endPreview() })
                    }
                }
                relicSaveError?.let { error -> AlertDialog(onDismissRequest = { relicSaveError = null },
                    title = { Text("Appearance not saved") }, text = { Text(error) },
                    confirmButton = { TextButton(onClick = { relicSaveError = null }) { Text("Close") } }) }
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
                when {
                    loaded.recoveredFromBackup -> message = "Your library list was damaged, so BookCover restored its last good copy. Recent changes may be missing."
                    loaded.skipped > 0 -> message = "${loaded.skipped} damaged library entries could not be read and were set aside. Your other books are safe."
                    else -> operations.publishInitialMessage("Swipe to turn pages. Tap the middle of a page for controls.")
                }
                reopeningBookId?.let { id -> library.book(id)?.let { loadBook(it) } }
            } finally {
                // Whatever happened, never leave the plain reopening screen in place.
                reopeningBookId = null
                restoredCover = null
                restoredReaderLocator = null
                if (session == null && pdfSession == null) setReadingWindow(false)
            }
        }
        // Both reader formats update this existing dated clock; awards never depend on page flips.
        lifecycleScope.launch(Dispatchers.IO) {
            library.readingDays.days.collect { library.reconcileRelics(it) }
        }
    }

    // Hooks for the lifecycle tests in the separate QA app (app/src/androidTest); the app does not use them.
    internal val libraryForTests: BookLibrary get() = library
    internal val sessionForTests: EpubSession? get() = session
    internal val pdfSessionForTests: PdfSession? get() = pdfSession
    internal val busyForTests: Boolean get() = busy
    internal val messageForTests: String get() = message
    internal val showsLibraryForTests: Boolean get() = session == null && pdfSession == null && reopeningBookId == null
    internal val relicTestingForTests: RelicTestAccess get() = relicTesting
    internal val relicCabinetShownForTests: Boolean get() = showRelicCabinet
    internal val relicPreviewRequestedForTests: Boolean get() = exterior.previewRequested
    internal fun closeRelicCabinetForTests() { showRelicCabinet = false }
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
        if (busy || session != null || pdfSession != null) return
        work(ReaderAction.OPEN) { loadBook(book) }
    }

    private suspend fun loadBook(book: BookRecord) {
        if (book.format == BookFormat.PDF) { loadPdfBook(book); return }
        openBookWithCleanup(
            ioContext = Dispatchers.IO,
            openPublication = { engine.open(library.bookFile(book)) },
            closePublication = { it.close() },
            loadArtwork = { CoverArtwork.from(library.coverBitmap(book)) },
            createSession = { publication ->
                val record = library.book(book.id) ?: book
                // The Bundle is the immediate recreation snapshot; disk saves may still be finishing.
                val restoredRecord = restoredReaderLocator?.takeIf { book.id == reopeningBookId }
                    ?.let { record.copy(locator = it) } ?: record
                EpubSession(this, restoredRecord, publication, library,
                    preferences.getFloat("fontScale", 1.0f).toDouble(), preferences.getString("theme", "Paper") ?: "Paper",
                    report = { message = it }, initialFontId = preferences.getString("fontId", "serif") ?: "serif",
                    initialEdgeTaps = preferences.getBoolean("edgeTaps", false))
            },
            closeSession = { it.close() },
            markOpened = { library.markOpened(book.id) },
            commit = { current, artwork ->
                // Take ownership first so a later failure here leaves the session owned and closable.
                session = current
                exterior.artwork = artwork
                exterior.setAppearance(relicTesting.appearanceFor(library.relics.collection.value, book.id), book.title, book.author)
                setReadingWindow(true)
                if (reopeningBookId == null) applyAwake(screenAwake.opened(SystemClock.elapsedRealtime()))
                if (reopeningBookId != null) operations.publishInitialMessage("Swipe left/right to turn pages. Tap the middle for controls.")
                else message = "Swipe left/right to turn pages. Tap the middle for controls."
                DiagnosticLog.record("Opened local EPUB in host activity; metadata omitted")
                val previousCover = restoredCover
                restoredCover = null
                if (previousCover != null) exterior.restore(previousCover.first, previousCover.second)
                else if (autoCover && exterior.available) exterior.start(timer)
            },
        )
    }

    private suspend fun loadPdfBook(book: BookRecord) {
        openBookWithCleanup(
            ioContext = Dispatchers.IO,
            openPublication = { engine.open(library.bookFile(book)) },
            closePublication = { it.close() },
            loadArtwork = { CoverArtwork.from(library.coverBitmap(book)) },
            createSession = { publication ->
                val record = library.book(book.id) ?: book
                val restoredRecord = restoredReaderLocator?.takeIf { book.id == reopeningBookId }
                    ?.let { record.copy(locator = it) } ?: record
                PdfSession(this, restoredRecord, publication, library, report = { message = it })
            },
            closeSession = { it.close() },
            markOpened = { library.markOpened(book.id) },
            commit = { current, artwork ->
                pdfSession = current
                exterior.artwork = artwork
                exterior.setAppearance(relicTesting.appearanceFor(library.relics.collection.value, book.id), book.title, book.author)
                setReadingWindow(true)
                if (reopeningBookId == null) applyAwake(screenAwake.opened(SystemClock.elapsedRealtime()))
                message = "Swipe to turn PDF pages."
                val previousCover = restoredCover
                restoredCover = null
                if (previousCover != null) exterior.restore(previousCover.first, previousCover.second)
                else if (autoCover && exterior.available) exterior.start(timer)
            },
        )
    }

    private fun goHome() {
        exterior.stop("returning to library")
        session?.close(); session = null
        pdfSession?.close(); pdfSession = null
        setReadingWindow(false)
        message = "Your reading position is saved."
    }

    private fun chooseScreenIdle(choice: InnerScreenIdle) {
        screenIdle = choice
        preferences.edit().putString(INNER_SCREEN_IDLE_KEY, choice.id).apply()
        applyAwake(screenAwake.choose(choice, SystemClock.elapsedRealtime()))
    }

    /**
     * Holds or releases only this window's screen-on request. It never locks the phone or changes the
     * system timeout, and replaces any earlier scheduled check so a stale one cannot extend the deadline.
     */
    private fun applyAwake(decision: InnerScreenAwake.Decision?) {
        decision ?: return
        awakeCheck?.let(awakeHandler::removeCallbacks); awakeCheck = null
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        // Unchanged flags are skipped: each change relayouts the window, and touches arrive often.
        if ((window.attributes.flags and flag != 0) != decision.keepScreenOn) {
            if (decision.keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
        }
        decision.checkAfterMillis?.let { delay ->
            awakeCheck = Runnable { applyAwake(screenAwake.check(decision.token, SystemClock.elapsedRealtime())) }
                .also { awakeHandler.postDelayed(it, delay) }
        }
    }

    /** Touch-downs and keys dispatched to this window, including consumed page keys; never consumes them. */
    private fun readerInput() = applyAwake(screenAwake.input(SystemClock.elapsedRealtime()))

    // Hooks for the BC-22 lifecycle tests in the QA app.
    internal val screenAwakeForTests: InnerScreenAwake get() = screenAwake
    internal val keepsScreenOnForTests: Boolean
        get() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
    internal fun checkScreenAwakeForTests(now: Long) = applyAwake(screenAwake.check(screenAwake.token, now))
    internal fun chooseScreenIdleForTests(choice: InnerScreenIdle) = chooseScreenIdle(choice)

    private fun setReadingWindow(reading: Boolean) {
        if (!reading) applyAwake(screenAwake.closed(SystemClock.elapsedRealtime()))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (reading) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private suspend fun saveRelicChange(change: () -> Unit) {
        relicSaveError = null
        try { withContext(Dispatchers.IO) { change() } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            relicSaveError = readerMessage(ReaderAction.ORGANIZE, error)
            throw error
        }
    }

    /** Runs [block]; a failure shows a plain message for [action] and logs only technical types (BC-26). */
    private fun work(action: ReaderAction, block: suspend () -> Unit) {
        // Enter synchronously, before a second picker result can observe an idle library.
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            operations.run {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    message = readerMessage(action, error)
                    DiagnosticLog.record(diagnosticSummary(action, error) + "; document details omitted")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("relicCabinet", showRelicCabinet)
        outState.putBoolean("pendingRelicTesting", pendingRelicTesting)
        outState.putString("pendingCoverBook", coverBookId)
        outState.putBundle("documentPickerResults", Bundle().also(activityResultRegistry::onSaveInstanceState))
        outState.putString("openBook", session?.book?.id ?: pdfSession?.book?.id ?: reopeningBookId)
        outState.putString("openLocator", session?.restorationLocator?.toJSON()?.toString() ?: pdfSession?.restorationLocator?.toJSON()?.toString() ?: restoredReaderLocator)
        outState.putBoolean("coverRequested", exterior.requested)
        outState.putLong("coverDeadline", exterior.deadline)
        outState.putLong("screenLastInput", screenAwake.lastInput ?: -1L)
        session?.flushPosition()
        pdfSession?.flushPosition()
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Freeze the last visible semantic passage before the navigator receives the new viewport.
        exterior.viewportChanging()
        if (session != null) session?.viewportChanging { exterior.viewportSettled() }
        else if (pdfSession != null) pdfSession?.viewportChanging { exterior.viewportSettled() }
        else exterior.viewportSettled()
        super.onConfigurationChanged(newConfig)
    }

    override fun onResume() {
        super.onResume()
        exterior.hostResumed()
        previewResumeVersion++
        lifecycleScope.launch(Dispatchers.IO) { relicTesting.refresh() }
        applyAwake(screenAwake.resumed(SystemClock.elapsedRealtime()))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptRelicTestingRequest(intent)
    }

    /** Explicit debug-owner setup only. Never grants an earned reward or changes the reading clock. */
    private fun acceptRelicTestingRequest(incoming: Intent) {
        if (relicTesting.supported && incoming.getBooleanExtra(EXTRA_MOONLIT_TESTING, false)) {
            incoming.removeExtra(EXTRA_MOONLIT_TESTING)
            pendingRelicTesting = true
        }
    }

    override fun onPause() {
        applyAwake(screenAwake.paused(SystemClock.elapsedRealtime()))
        super.onPause()
    }

    override fun onStop() {
        exterior.hostStopped()
        // Android may end the process while the app is in the background; save the position first.
        session?.flushPosition()
        pdfSession?.flushPosition()
        super.onStop()
    }

    override fun onDestroy() {
        awakeCheck?.let(awakeHandler::removeCallbacks); awakeCheck = null
        exterior.close()
        session?.close()
        pdfSession?.close()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_MOONLIT_TESTING = "com.bookcover.diagnostic.MOONLIT_TESTING"
    }
}
