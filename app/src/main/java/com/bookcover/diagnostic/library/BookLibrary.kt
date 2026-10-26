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
import android.util.Size
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
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.coverFitting

internal class BookLibrary(private val context: Context, private val engine: ReadiumEngine) {
    private val directory = File(context.filesDir, "library").apply { mkdirs() }
    private val index = AtomicFile(File(directory, "books.json"))
    private val lock = Mutex()
    private val mutableBooks = MutableStateFlow<List<BookRecord>>(emptyList())
    val books = mutableBooks.asStateFlow()
    fun book(id: String): BookRecord? = mutableBooks.value.firstOrNull { it.id == id }
    fun epubFile(book: BookRecord): File = File(directory, "${book.id}.epub")
    fun coverFile(book: BookRecord): File = File(directory, "${book.id}.png")

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (index.baseFile.exists() || File(directory, "books.json.bak").exists()) {
                val json = JSONObject(index.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                val array = json.getJSONArray("books")
                mutableBooks.value = (0 until array.length()).map { BookRecord.fromJson(array.getJSONObject(it)) }
            }
        }
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
    suspend fun bookmark(id: String, locator: Locator) = change(id) {
        val value = locator.toJSON().toString()
        copy(bookmarks = (bookmarks + value).distinct())
    }
    suspend fun removeBookmark(id: String, value: String) = change(id) { copy(bookmarks = bookmarks - value) }
    suspend fun move(id: String, collection: String) = change(id) {
        copy(collection = collection.trim().take(60).ifBlank { "Books" })
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = book(id) ?: return@withLock
            persist(mutableBooks.value.filterNot { it.id == id })
            epubFile(record).delete(); coverFile(record).delete()
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
            val atomicCover = AtomicFile(coverFile(record))
            val output = atomicCover.startWrite()
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); atomicCover.finishWrite(output) }
            catch (error: Exception) { atomicCover.failWrite(output); throw error }
            persist(mutableBooks.value.map { if (it.id == id) it.copy(coverRevision = System.currentTimeMillis()) else it })
        }
    }

    fun coverBitmap(record: BookRecord): Bitmap = BitmapFactory.decodeFile(coverFile(record).absolutePath) ?: placeholder(record)

    private suspend fun change(id: String, transform: BookRecord.() -> BookRecord) = withContext(Dispatchers.IO) {
        lock.withLock { persist(mutableBooks.value.map { if (it.id == id) transform(it) else it }) }
    }

    private fun persist(records: List<BookRecord>) {
        val json = JSONObject().put("version", 1).put("books", JSONArray().apply { records.forEach { put(it.toJson()) } })
        val output = index.startWrite()
        try { output.write(json.toString().toByteArray()); index.finishWrite(output) }
        catch (error: Exception) { index.failWrite(output); throw error }
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
