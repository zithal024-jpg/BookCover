package com.bookcover.diagnostic.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AtomicFile
import android.util.LruCache
import android.util.Size
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.coverFitting

internal class MissingCoverBookException : Exception("Cover target removed")

internal class BookLibrary(private val context: Context, val engine: ReadiumEngine) {
    companion object {
        @Volatile private var shared: BookLibrary? = null

        /**
         * The app's single library. Activity recreation (rotation, fold, unfold) must reuse it: a second
         * instance would have its own lock and could read or write `books.json` during the first's save.
         */
        fun forApp(context: Context): BookLibrary = shared ?: synchronized(this) {
            shared ?: context.applicationContext.let { app -> BookLibrary(app, ReadiumEngine(app)) }.also { shared = it }
        }

        /** Import copies being written now, by absolute path, across every instance in this process. Guarded by itself. */
        private val activeImports = mutableSetOf<String>()
    }

    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val index = LibraryIndex(directory)
    private val coverColors = CoverColorCache(File(directory, "cover-colors.json"))
    /** Reading-streak days (dated totals only); shared by the reader and the Library card. */
    val readingDays = ReadingDayStore(File(directory, "reading-days.json"))
    private var loaded = false
    private val lock = Mutex()
    private val access = LibraryAccess(lock)
    private val mutableBooks = MutableStateFlow<List<BookRecord>>(emptyList())
    val books = mutableBooks.asStateFlow()
    fun book(id: String): BookRecord? = mutableBooks.value.firstOrNull { it.id == id }
    fun epubFile(book: BookRecord): File = File(directory, "${book.id}.epub")
    fun coverFile(book: BookRecord): File = File(directory, "${book.id}.png")
    fun thumbnailFile(book: BookRecord): File = File(directory, "${book.id}.thumb.png")
    // Recently shown library tiles, keyed by book and cover revision so a replaced cover is never stale.
    private val thumbnails = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    ) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    suspend fun load(): LibraryIndex.LoadResult = withContext(Dispatchers.IO) {
        access.load {
            loaded = false
            val removed = runCatching { cleanAbandonedImports() }.getOrDefault(0)
            index.load().also {
                coverColors.colors(it.records, ::extractColor)
                currentCoroutineContext().ensureActive()
                loaded = true
                mutableBooks.value = it.records
            }.copy(abandonedImportsRemoved = removed)
        }
    }

    /** Current covers only; cached across settings openings, activity recreation and app restarts. */
    suspend fun bookCoverColors(): List<BookCoverColor> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) emptyList() else coverColors.colors(mutableBooks.value, ::extractColor)
        }
    }

    private fun extractColor(book: BookRecord): Int = coverThemeSeed(thumbnailBitmap(book))

    /**
     * Deletes `import-*.epub` copies that no running import owns, left behind when the app was killed
     * mid-import (BC-27). Books, covers, the library list and any other file are never touched.
     */
    internal fun cleanAbandonedImports(): Int = synchronized(activeImports) {
        val active = activeImports.mapNotNull { path -> File(path).takeIf { it.parentFile?.absolutePath == directory.absolutePath }?.name }.toSet()
        abandonedImportTemps(directory.list().orEmpty().toList(), active).count { name ->
            File(directory, name).let { it.isFile && it.delete() }
        }
    }

    /** Creates and registers an import copy in one step, so cleanup can never see it unregistered. */
    internal fun newImportTemp(): File = synchronized(activeImports) {
        File.createTempFile("import-", ".epub", directory).also { activeImports += it.absolutePath }
    }

    internal fun finishImportTemp(file: File) = synchronized(activeImports) {
        file.delete()
        activeImports -= file.absolutePath
    }

    private val importGate = ImportGate()

    /**
     * Imports several books one at a time (BC-27): each succeeds or fails on its own and is saved as soon as it
     * is added, so a bad book never undoes the others. Returns null, doing nothing, if another batch is running.
     */
    suspend fun importAll(uris: List<Uri>, cancellation: ImportCancellation = ImportCancellation(),
                          onProgress: (ImportProgress) -> Unit = {}): ImportSummary? = importGate.runExclusive {
        val names = withContext(Dispatchers.IO) { uris.mapIndexed { position, uri -> displayName(uri) ?: "Book ${position + 1}" } }
        val positions = uris.indices.toList()
        runImportBatch(positions, { names[it] }, cancellation, onProgress) { position ->
            val (book, added) = import(uris[position])
            if (added) ImportOutcome.Added(book.title) else ImportOutcome.Duplicate(book.title)
        }
    }

    /** The file name the picker shows, for the summary only; never logged. */
    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun import(uri: Uri): Pair<BookRecord, Boolean> = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
        input.use { importStream(it, "Books") }
    }

    suspend fun importSample(assetName: String): Pair<BookRecord, Boolean> = withContext(Dispatchers.IO) {
        context.assets.open(assetName).use { importStream(it, "Samples") }
    }

    private suspend fun importStream(input: InputStream, collection: String): Pair<BookRecord, Boolean> {
        val temporary = newImportTemp()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive() // Stop importing: abandon the copy promptly.
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 200L * 1024 * 1024) { "This prototype imports EPUBs up to 200 MB" }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                }
            }
            checkEpubArchive(temporary)
            currentCoroutineContext().ensureActive()
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            book(id)?.let { return it to false }
            val publication = engine.open(temporary)
            try {
                val now = System.currentTimeMillis()
                val record = BookRecord(id, publication.metadata.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                    publication.metadata.authors.map { it.name }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Unknown author" },
                    now, collection, coverRevision = now)
                val cover = runCatching { publication.coverFitting(Size(1080, 1600)) }.getOrNull() ?: placeholder(record)
                lock.withLock {
                    book(id)?.let { return it to false }
                    val destination = epubFile(record)
                    if (!temporary.renameTo(destination)) temporary.copyTo(destination, overwrite = true)
                    coverFile(record).outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    writePng(thumbnailFile(record), shrink(cover))
                    persist(mutableBooks.value + record)
                }
                return record to true
            } finally { publication.close() }
        } finally { finishImportTemp(temporary) }
    }

    suspend fun updatePosition(id: String, locator: Locator) = change(id) {
        copy(locator = locator.toJSON().toString(), progression = (locator.locations.totalProgression ?: progression).coerceIn(0.0, 1.0),
            lastReadAt = System.currentTimeMillis())
    }

    suspend fun markOpened(id: String) = change(id) { copy(lastReadAt = System.currentTimeMillis()) }
    suspend fun bookmark(id: String, locator: Locator, name: String? = null, chapterTitle: String? = null) = change(id) {
        val value = SavedBookmarks.create(locator.toJSON().toString(), name, chapterTitle)
        copy(bookmarks = SavedBookmarks.upsert(bookmarks, value))
    }
    suspend fun renameBookmark(id: String, value: String, name: String) = change(id) {
        copy(bookmarks = bookmarks.map { if (it == value) SavedBookmarks.rename(it, name) else it })
    }
    suspend fun removeBookmark(id: String, value: String) = change(id) { copy(bookmarks = bookmarks - value) }
    suspend fun move(id: String, collection: String) = change(id) {
        copy(collection = collection.trim().take(60).ifBlank { "Books" })
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = book(id) ?: return@withLock
            persist(mutableBooks.value.filterNot { it.id == id })
            epubFile(record).delete(); coverFile(record).delete(); thumbnailFile(record).delete()
            forgetThumbnails(id)
        }
    }

    suspend fun replaceCover(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        access.withReadyLibrary { if (book(id) == null) throw MissingCoverBookException() }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        access.withReadyLibrary {
            val record = book(id) ?: throw MissingCoverBookException()
            writePng(coverFile(record), bitmap)
            writePng(thumbnailFile(record), shrink(bitmap))
            forgetThumbnails(id)
            persist(mutableBooks.value.map { if (it.id == id) it.copy(coverRevision = maxOf(System.currentTimeMillis(), record.coverRevision + 1)) else it })
        }
    }

    /** Prepares a loaded snapshot before opening [uri]. Provider writes themselves are not atomic. */
    suspend fun exportBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        access.export(prepare = {
            val records = mutableBooks.value
            PreparedBackup(records.size, LibraryBackup.export(records, System.currentTimeMillis()).toByteArray(Charsets.UTF_8))
        }, openDestination = {
            context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write the backup file")
        })
    }

    suspend fun restoreBackup(uri: Uri): LibraryBackup.MergeResult = withContext(Dispatchers.IO) {
        access.withReadyLibrary { }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
        val text = input.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                bytes.write(buffer, 0, read)
                require(bytes.size() <= 20 * 1024 * 1024) { "This file is too large to be a BookCover library backup." }
            }
            bytes.toString("UTF-8")
        }
        val backup = LibraryBackup.parse(text)
        access.withReadyLibrary {
            val result = LibraryBackup.merge(mutableBooks.value, backup.records)
            if (result.records != mutableBooks.value) persist(result.records)
            result
        }
    }

    /** The full-size cover, for the outside screen. Library tiles use [thumbnailBitmap]. */
    fun coverBitmap(record: BookRecord): Bitmap = BitmapFactory.decodeFile(coverFile(record).absolutePath) ?: placeholder(record)

    /**
     * A small cover (at most 400×600) for library tiles. Comes from memory, then the saved thumbnail;
     * books imported before thumbnails existed get one made from their cover the first time. Blocking: call off the main thread.
     */
    fun thumbnailBitmap(record: BookRecord): Bitmap {
        val key = "${record.id}:${record.coverRevision}"
        thumbnails.get(key)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(thumbnailFile(record).absolutePath)
            ?: (decodeSmallCover(record) ?: shrink(placeholder(record))).also { made ->
                runCatching { writePng(thumbnailFile(record), made) }
            }
        thumbnails.put(key, bitmap)
        return bitmap
    }

    /** Decodes the saved cover skipping pixels it does not need, then scales it to thumbnail size. */
    private fun decodeSmallCover(record: BookRecord): Bitmap? {
        val path = coverFile(record).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = CoverThumbnail.sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeFile(path, options)?.let(::shrink)
    }

    private fun shrink(bitmap: Bitmap): Bitmap {
        val (width, height) = CoverThumbnail.fit(bitmap.width, bitmap.height)
        return if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun forgetThumbnails(id: String) {
        thumbnails.snapshot().keys.filter { it.startsWith("$id:") }.forEach { thumbnails.remove(it) }
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    private suspend fun change(id: String, transform: BookRecord.() -> BookRecord) = withContext(Dispatchers.IO) {
        lock.withLock { persist(mutableBooks.value.map { if (it.id == id) transform(it) else it }) }
    }

    private fun persist(records: List<BookRecord>) {
        index.save(records)
        mutableBooks.value = records
    }

    private fun placeholder(record: BookRecord): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = intArrayOf(0xFF244C46.toInt(), 0xFF4C4B68.toInt(), 0xFF6A483C.toInt(), 0xFF405B71.toInt())
        canvas.drawColor(palette[(record.id.first().code and Int.MAX_VALUE) % palette.size])
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(249, 240, 217); textSize = 64f; typeface = Typeface.create("serif", Typeface.NORMAL) }
        canvas.save(); canvas.translate(65f, 210f)
        StaticLayout.Builder.obtain(record.title, 0, record.title.length, paint, 590)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(7).setLineSpacing(10f, 1f).build().draw(canvas)
        canvas.restore()
        paint.textSize = 30f; canvas.save(); canvas.translate(65f, 900f)
        StaticLayout.Builder.obtain(record.author, 0, record.author.length, paint, 590)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build().draw(canvas)
        canvas.restore()
        return bitmap
    }
}
