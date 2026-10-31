package com.bookcover.diagnostic.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/**
 * Owns only the three previously absent assets of one import. The synced receipt must exist before
 * any final asset is created. Ordinary exceptions can clean up now; abrupt termination leaves the
 * same evidence for a later load. No receipt means no permission to remove an unindexed book.
 */
internal class ImportFinalization(directory: File) {
    private val directory = directory.canonicalFile
    companion object {
        private val guard = Any()
        private val active = mutableSetOf<String>()
        private val idPattern = Regex("[a-f0-9]{64}")
        private val receiptPattern = Regex("pending-import-([a-f0-9]{64})")
        private val indexPattern = Regex("books(?:\\.last-good|\\.damaged-[0-9]+)?\\.json(?:\\.bak)?(?:\\.(?:tmp|new))?")
    }

    fun <T> commit(id: String, writeAssets: () -> Unit, persist: () -> T): T =
        commit(id, BookFormat.EPUB, writeAssets, persist)

    fun <T> commit(id: String, format: BookFormat, writeAssets: () -> Unit, persist: () -> T): T {
        require(idPattern.matches(id))
        val receipt = File(directory, "pending-import-$id")
        synchronized(guard) {
            if (receipt.exists() || BookFormat.entries.any { format -> assets(id, format).any { it.exists() } }) {
                throw IOException("An earlier copy of this book needs to be preserved")
            }
            // CREATE_NEW prevents replacing even an unexpected receipt. Failure here creates no assets.
            Files.newOutputStream(receipt.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(receiptText(id, format).toByteArray(Charsets.UTF_8))
            }
            FileOutputStream(receipt, true).use { it.fd.sync() }
            active += receipt.absolutePath
        }
        try {
            writeAssets()
            if (assets(id, format).any { !it.isFile || it.length() == 0L }) throw IOException("The imported book files are incomplete")
            val result = persist()
            // The committed index now owns the files; failure to remove a receipt is harmless.
            runCatching { receipt.delete() }
            return result
        } catch (error: Exception) {
            synchronized(guard) {
                active -= receipt.absolutePath
                try { recoverReceipt(receipt, referencedIds()) }
                catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        } finally {
            // Error deliberately does not run rollback: it models residue left by abrupt death.
            synchronized(guard) { active -= receipt.absolutePath }
        }
    }

    /** Called after index loading succeeds. Unknown evidence defers cleanup, never guesses ownership. */
    fun recover(): Int = synchronized(guard) {
        val referenced = referencedIds()
        directory.listFiles().orEmpty().filter { receiptPattern.matches(it.name) && it.absolutePath !in active }
            .sumOf { receipt -> recoverReceipt(receipt, referenced) }
    }

    /** Lazy thumbnail writes must not create/replace an asset after import ownership was proved. */
    fun writeExistingAsset(id: String, write: () -> Unit): Boolean = synchronized(guard) {
        require(idPattern.matches(id))
        if (File(directory, "pending-import-$id").exists()) false else {
            write()
            true
        }
    }

    private fun recoverReceipt(receipt: File, referenced: Set<String>?): Int {
        val id = receiptPattern.matchEntire(receipt.name)?.groupValues?.get(1) ?: return 0
        if (referenced == null || !receipt.isFile || receipt.length() > 200) return 0
        val text = receipt.readText()
        val format = BookFormat.entries.firstOrNull { text == receiptText(id, it) } ?: return 0
        if (id in referenced) {
            // A committed/recoverable index owns these assets, so retire our deletion authority.
            receipt.delete()
            return 0
        }
        val files = assets(id, format)
        val removed = files.count { it.isFile && it.delete() }
        if (files.none { it.exists() }) receipt.delete()
        return removed
    }

    /** Include complete pending writes and damaged snapshots, even records skipped by normal loading. */
    private fun referencedIds(): Set<String>? {
        return try {
            val result = mutableSetOf<String>()
            val candidates = directory.listFiles() ?: return null
            // A directory cannot contain recoverable index bytes. Leave it untouched, but it provides
            // no book references (including directories that caused an index write to fail).
            for (candidate in candidates.filter { indexPattern.matches(it.name) && !it.isDirectory }) {
                val records = JSONObject(candidate.readText()).getJSONArray("books")
                for (position in 0 until records.length()) {
                    val id = records.getJSONObject(position).getString("id")
                    if (!idPattern.matches(id)) return null
                    result += id
                }
            }
            result
        } catch (_: Exception) { null }
    }

    private fun assets(id: String, format: BookFormat) = listOf("$id.${format.extension}", "$id.png", "$id.thumb.png").map { File(directory, it) }
    private fun receiptText(id: String, format: BookFormat) = when (format) {
        BookFormat.EPUB -> "BookCover import 1\n$id\n"
        BookFormat.PDF -> "BookCover import 2\n$id\npdf\n"
    }
}
