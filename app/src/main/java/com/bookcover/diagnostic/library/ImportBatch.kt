package com.bookcover.diagnostic.library

import com.bookcover.diagnostic.FailureReason
import com.bookcover.diagnostic.ReaderAction
import com.bookcover.diagnostic.failureReason
import com.bookcover.diagnostic.readerMessage
import com.bookcover.diagnostic.shortFailureReason
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** How one book of a batch went; a failure is an exception instead. */
internal sealed interface ImportOutcome {
    val title: String
    data class Added(override val title: String) : ImportOutcome
    data class Duplicate(override val title: String) : ImportOutcome
}

internal data class ImportFailure(val name: String, val error: Throwable) {
    val reason: FailureReason get() = failureReason(error)
}

/** [done] books of [total] have finished; the next one is starting. */
internal data class ImportProgress(val done: Int, val total: Int)

internal data class ImportSummary(
    val total: Int,
    val imported: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val failed: List<ImportFailure> = emptyList(),
    val cancelled: Boolean = false,
    val libraryProtected: Boolean = false,
) {
    val notAttempted: Int get() = total - imported.size - duplicates.size - failed.size
}

/** The reader's Stop button: no further book starts, and the book being copied is abandoned and its copy deleted. */
internal class ImportCancellation {
    @Volatile var requested = false
        private set
    @Volatile private var current: Job? = null

    fun cancel() { requested = true; current?.cancel() }

    internal fun attach(job: Job) { current = job; if (requested) job.cancel() }
    internal fun detach() { current = null }
}

/** Lets only one import batch run at a time; a second request while one runs is refused, not queued. */
internal class ImportGate {
    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /** Runs [block], or returns null at once if another import is running. */
    suspend fun <R : Any> runExclusive(block: suspend () -> R): R? {
        if (!running.compareAndSet(false, true)) return null
        try { return block() } finally { running.set(false) }
    }
}

/**
 * Imports [items] one after another. Each book succeeds or fails on its own: a failure is recorded and
 * the next book starts, so earlier imports are never undone. An unreadable library list stops the batch,
 * since every later book would fail the same way. Closing the app (cancelling the caller) still cancels it all.
 */
internal suspend fun <T> runImportBatch(
    items: List<T>, name: (T) -> String, cancellation: ImportCancellation = ImportCancellation(),
    onProgress: (ImportProgress) -> Unit = {}, importOne: suspend (T) -> ImportOutcome,
): ImportSummary {
    val imported = mutableListOf<String>()
    val duplicates = mutableListOf<String>()
    val failed = mutableListOf<ImportFailure>()
    var cancelled = false
    var libraryProtected = false
    for ((index, item) in items.withIndex()) {
        onProgress(ImportProgress(index, items.size))
        if (cancellation.requested) { cancelled = true; break }
        // Set as soon as the book is saved, so Stop pressed during its last instant still counts it.
        var finished: ImportOutcome? = null
        fun record(outcome: ImportOutcome) = when (outcome) {
            is ImportOutcome.Added -> imported += outcome.title
            is ImportOutcome.Duplicate -> duplicates += outcome.title
        }
        try {
            coroutineScope {
                val job = async { importOne(item).also { finished = it } }
                cancellation.attach(job)
                try { job.await() } finally { cancellation.detach() }
            }
            finished?.let(::record)
        } catch (stopped: CancellationException) {
            finished?.let(::record)
            if (!cancellation.requested || !currentCoroutineContext().isActive) throw stopped
            cancelled = true; break
        } catch (error: Exception) {
            val failure = ImportFailure(name(item), error)
            failed += failure
            if (failure.reason == FailureReason.LIBRARY_PROTECTED) { libraryProtected = true; break }
        }
    }
    return ImportSummary(items.size, imported, duplicates, failed, cancelled, libraryProtected)
}

internal fun importProgressMessage(progress: ImportProgress): String =
    "Importing book ${progress.done + 1} of ${progress.total}…"

/** The final message: what was imported, already there, or failed (with file names), and whether anything changed. */
internal fun importSummaryMessage(summary: ImportSummary): String {
    summary.failed.firstOrNull { it.reason == FailureReason.LIBRARY_PROTECTED }?.let { return readerMessage(ReaderAction.IMPORT, it.error) }
    if (summary.total == 1 && !summary.cancelled) {
        summary.imported.firstOrNull()?.let { return "Imported $it. Tap its cover to read." }
        summary.duplicates.firstOrNull()?.let { return "$it is already in your library." }
        summary.failed.firstOrNull()?.let { return readerMessage(ReaderAction.IMPORT, it.error) }
    }
    if (summary.cancelled) {
        if (summary.imported.isEmpty()) return "Import stopped. No books were added; your library is unchanged."
        return "Import stopped. Imported ${summary.imported.size} of ${books(summary.total)}; ${summary.notAttempted} " +
            "${if (summary.notAttempted == 1) "was" else "were"} not imported."
    }
    val parts = mutableListOf(
        if (summary.imported.size == summary.total) "Imported ${books(summary.total)}."
        else "Imported ${summary.imported.size} of ${books(summary.total)}."
    )
    if (summary.duplicates.isNotEmpty())
        parts += "${summary.duplicates.size} ${if (summary.duplicates.size == 1) "was" else "were"} already in your library."
    if (summary.failed.isNotEmpty()) {
        val named = summary.failed.take(3).joinToString(", ") { "${it.name} (${shortFailureReason(it.reason)})" }
        val more = if (summary.failed.size > 3) " and ${summary.failed.size - 3} more" else ""
        parts += "${summary.failed.size} couldn't be imported: $named$more."
        parts += if (summary.imported.isEmpty()) "Your library is unchanged." else "Your other books are safe."
    } else if (summary.imported.isNotEmpty()) parts += "Tap a cover to read."
    return parts.joinToString(" ")
}

private fun books(count: Int) = if (count == 1) "1 book" else "$count books"

private val importTempName = Regex("""import--?\d+\.(?:epub|pdf)""")

/** A copy made by [BookLibrary] while importing: `import-` plus the random number File.createTempFile adds. */
internal fun isImportTempName(name: String): Boolean = importTempName.matches(name)

/** Import copies that no running import owns: left behind when the app was closed or killed mid-import. */
internal fun abandonedImportTemps(names: Collection<String>, active: Set<String>): List<String> =
    names.filter { isImportTempName(it) && it !in active }
