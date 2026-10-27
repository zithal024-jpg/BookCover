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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AtomicFile
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.coverFitting

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
    }

    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val index = LibraryIndex(directory)
    private val lock = Mutex()
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
        lock.withLock { index.load().also { mutableBooks.value = it.records } }
    }

    suspend fun import(uri: Uri): Pair<BookRecord, Boolean> = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
        input.use { importStream(it, "Books") }
    }

    suspend fun importSample(assetName: String): Pair<BookRecord, Boolean> = withContext(Dispatchers.IO) {
        context.assets.open(assetName).use { importStream(it, "Samples") }
    }

    private suspend fun importStream(input: InputStream, collection: String): Pair<BookRecord, Boolean> {
        val temporary = File.createTempFile("import-", ".epub", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 200L * 1024 * 1024) { "This prototype imports EPUBs up to 200 MB" }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                }
            }
            ZipFile(temporary).use { zip ->
                require(zip.getEntry("META-INF/container.xml") != null) { "Choose a valid EPUB file" }
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 20_000 && entries.all { it.size in 0..(100L * 1024 * 1024) } &&
                    entries.sumOf { it.size } <= 500L * 1024 * 1024) { "This EPUB's expanded content exceeds the prototype's import limit" }
            }
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
        } finally { temporary.delete() }
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
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val ratio = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        lock.withLock {
            val record = book(id) ?: return@withLock
            writePng(coverFile(record), bitmap)
            writePng(thumbnailFile(record), shrink(bitmap))
            forgetThumbnails(id)
            persist(mutableBooks.value.map { if (it.id == id) it.copy(coverRevision = System.currentTimeMillis()) else it })
        }
    }

    /** Writes reading positions, bookmarks and collections to [uri]. Returns how many books were saved. */
    suspend fun exportBackup(uri: Uri): Int = withContext(Dispatchers.IO) {
        val records = mutableBooks.value
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write the backup file")
        output.use { it.write(LibraryBackup.export(records, System.currentTimeMillis()).toByteArray()) }
        records.size
    }

    suspend fun restoreBackup(uri: Uri): LibraryBackup.MergeResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
        val text = input.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                bytes.write(buffer, 0, read)
                require(bytes.size() <= 20 * 1024 * 1024) { "This file is too large to be a BookCover library backup." }
            }
            bytes.toString("UTF-8")
        }
        val backup = LibraryBackup.parse(text)
        lock.withLock {
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
