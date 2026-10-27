package com.bookcover.diagnostic.reader

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse

internal enum class BookSearchStatus { EMPTY, SEARCHING, RESULTS, NO_RESULTS, FAILED }

internal data class BookSearchResult(
    val locator: Locator,
    val chapter: String,
    val before: String,
    val highlight: String,
    val after: String,
    internal val generation: Long = 0,
)

internal data class BookSearchState(
    val query: String = "",
    val visible: Boolean = false,
    val status: BookSearchStatus = BookSearchStatus.EMPTY,
    val results: List<BookSearchResult> = emptyList(),
    val capped: Boolean = false,
    val error: String? = null,
)

internal data class BookSearchBatch(val results: List<BookSearchResult>, val capped: Boolean = false)

internal fun interface BookSearchSource {
    suspend fun find(query: String): BookSearchBatch
}

/**
 * One controller belongs to one open publication. UI calls run on the owning scope's thread.
 * Every input/dismissal invalidates prior generations immediately, even if old IO cannot stop yet.
 * No position is captured or changed by search; only the session's explicit selection navigates.
 */
internal class BookSearchController(
    private val scope: CoroutineScope,
    private val source: BookSearchSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMillis: Long = 200,
) {
    constructor(scope: CoroutineScope, publication: Publication) : this(scope, ReadiumBookSearchSource(publication))

    private val mutableState = MutableStateFlow(BookSearchState())
    val state: StateFlow<BookSearchState> = mutableState.asStateFlow()
    private val worker = Mutex()
    private var generation = 0L
    private var job: Job? = null
    private var closed = false

    fun open() {
        if (closed || state.value.visible) return
        invalidate()
        mutableState.value = BookSearchState(visible = true)
    }

    fun updateQuery(value: String) {
        if (closed || !state.value.visible) return
        val query = value.take(BOOK_SEARCH_QUERY_LIMIT).let {
            // Do not leave half of an emoji or other supplementary Unicode character at the cap.
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
        if (query == state.value.query) return
        start(query)
    }

    fun retry() {
        if (!closed && state.value.visible) start(state.value.query)
    }

    private fun start(query: String) {
        invalidate()
        val requested = generation
        val normalized = normalizedBookSearchQuery(query)
        mutableState.value = BookSearchState(
            query = query, visible = true,
            status = if (normalized.isEmpty()) BookSearchStatus.EMPTY else BookSearchStatus.SEARCHING,
        )
        if (normalized.isEmpty()) return
        job = scope.launch {
            try {
                delay(debounceMillis)
                val batch = withContext(dispatcher) {
                    // A non-cooperative read from an old query cannot fan out parallel book scans.
                    worker.withLock {
                        currentCoroutineContext().ensureActive()
                        source.find(normalized).also { currentCoroutineContext().ensureActive() }
                    }
                }
                if (closed || requested != generation || !state.value.visible) return@launch
                val results = batch.results.take(BOOK_SEARCH_RESULT_LIMIT).map { it.copy(generation = requested) }
                mutableState.value = BookSearchState(
                    query = query, visible = true,
                    status = if (results.isEmpty()) BookSearchStatus.NO_RESULTS else BookSearchStatus.RESULTS,
                    results = results,
                    capped = batch.capped || batch.results.size > BOOK_SEARCH_RESULT_LIMIT,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && requested == generation && state.value.visible) {
                    mutableState.value = BookSearchState(
                        query = query, visible = true, status = BookSearchStatus.FAILED,
                        error = "This book could not be searched. Try again or search for a different phrase. Very large or unreadable chapters may not support search.",
                    )
                }
            }
        }
    }

    fun isCurrent(result: BookSearchResult): Boolean = !closed && state.value.visible &&
        state.value.status == BookSearchStatus.RESULTS && result.generation == generation &&
        state.value.results.any { it === result }

    fun navigationFailed() {
        if (closed || !state.value.visible) return
        invalidate()
        mutableState.value = state.value.copy(
            status = BookSearchStatus.FAILED, results = emptyList(), capped = false,
            error = "This passage could not be opened. Try the search again.",
        )
    }

    fun dismiss() {
        invalidate()
        mutableState.value = BookSearchState()
    }

    fun close() {
        closed = true
        dismiss()
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }
}

/** Whitespace in pasted phrases follows Readium's HTML text extraction; ICU handles accents/case. */
internal fun normalizedBookSearchQuery(value: String): String = Normalizer.normalize(
    value.trim().split(Regex("\\s+|\\p{Z}+")).filter { it.isNotEmpty() }.joinToString(" "),
    Normalizer.Form.NFC,
)

@OptIn(ExperimentalReadiumApi::class)
private class ReadiumBookSearchSource(private val publication: Publication) : BookSearchSource {
    override suspend fun find(query: String): BookSearchBatch {
        val iterator = publication.search(query, SearchService.Options(
            caseSensitive = false, diacriticSensitive = false, wholeWord = false,
        )) ?: error("Publication does not support search")
        val results = ArrayList<BookSearchResult>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = iterator.next().getOrElse { error("Publication search failed") }
                    ?: return BookSearchBatch(results)
                currentCoroutineContext().ensureActive()
                for (locator in page.locators) {
                    if (results.size == BOOK_SEARCH_RESULT_LIMIT) return BookSearchBatch(results, capped = true)
                    val section = publication.readingOrder.indexOfFirst {
                        it.url().removeFragment() == locator.href.removeFragment()
                    }
                    val chapter = locator.title?.takeIf { it.isNotBlank() }
                        ?: publication.readingOrder.getOrNull(section)?.title?.takeIf { it.isNotBlank() }
                        ?: "Section ${section + 1}"
                    results += BookSearchResult(
                        locator = locator, chapter = chapter,
                        before = locator.text.before.orEmpty().takeLast(160),
                        highlight = locator.text.highlight.orEmpty().take(BOOK_SEARCH_QUERY_LIMIT * 2),
                        after = locator.text.after.orEmpty().take(160),
                    )
                }
            }
        } finally {
            iterator.close()
        }
    }
}
