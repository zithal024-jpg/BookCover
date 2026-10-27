package com.bookcover.diagnostic.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSearchControllerTest {
    @Test fun emptyAndUnicodeWhitespaceNeverStartBookWork() = runTest {
        val queries = mutableListOf<String>()
        val controller = BookSearchController(backgroundScope,
            BookSearchSource { queries += it; BookSearchBatch(emptyList()) }, StandardTestDispatcher(testScheduler))
        controller.open()
        controller.updateQuery(" \n\t\u00a0\u2003 ")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(BookSearchStatus.EMPTY, controller.state.value.status)
        assertTrue(queries.isEmpty())
    }

    @Test fun rapidlyChangedQueryDebouncesAndRunsOnlyLatestPhrase() = runTest {
        val queries = mutableListOf<String>()
        val controller = BookSearchController(backgroundScope,
            BookSearchSource { queries += it; BookSearchBatch(emptyList()) }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("first")
        advanceTimeBy(100)
        controller.updateQuery("second")
        advanceTimeBy(100)
        controller.updateQuery("last phrase")
        assertEquals(BookSearchStatus.SEARCHING, controller.state.value.status)
        advanceTimeBy(201); runCurrent()
        assertEquals(listOf("last phrase"), queries)
        assertEquals(BookSearchStatus.NO_RESULTS, controller.state.value.status)
    }

    @Test fun clearingAnActiveSearchImmediatelyRemovesItsState() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            started.complete(Unit); finish.await(); BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("working")
        advanceTimeBy(201); runCurrent(); assertTrue(started.isCompleted)
        controller.updateQuery("")
        assertEquals(BookSearchState(visible = true), controller.state.value)
        finish.complete(Unit); runCurrent()
        assertEquals(BookSearchStatus.EMPTY, controller.state.value.status)
    }

    @Test fun dismissalInvalidatesEvenNonCooperativeOldFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            withContext(NonCancellable) { started.complete(Unit); finish.await() }
            error("old failed read")
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("old")
        advanceTimeBy(201); runCurrent(); assertTrue(started.isCompleted)
        controller.dismiss()
        assertEquals(BookSearchState(), controller.state.value)
        finish.complete(Unit); runCurrent()
        assertEquals(BookSearchState(), controller.state.value)
    }

    @Test fun obsoleteNonCooperativeReadCannotOverwriteNewerQueryAndScansAreSerial() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val queries = mutableListOf<String>()
        val controller = BookSearchController(backgroundScope, BookSearchSource { query ->
            queries += query
            if (query == "old") {
                withContext(NonCancellable) { firstStarted.complete(Unit); finishFirst.await() }
            }
            BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("old")
        advanceTimeBy(201); runCurrent(); assertTrue(firstStarted.isCompleted)
        controller.updateQuery("new"); advanceTimeBy(201); runCurrent()
        assertEquals(listOf("old"), queries)
        assertEquals("new", controller.state.value.query)
        assertEquals(BookSearchStatus.SEARCHING, controller.state.value.status)
        finishFirst.complete(Unit); runCurrent()
        assertEquals(listOf("old", "new"), queries)
        assertEquals("new", controller.state.value.query)
        assertEquals(BookSearchStatus.NO_RESULTS, controller.state.value.status)
    }

    @Test fun failureIsFriendlyAndRetryCanRecover() = runTest {
        var attempts = 0
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            if (attempts++ == 0) error("sensitive book filename")
            BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("phrase")
        advanceTimeBy(201); runCurrent()
        assertEquals(BookSearchStatus.FAILED, controller.state.value.status)
        assertFalse(controller.state.value.error.orEmpty().contains("sensitive"))
        assertTrue(controller.state.value.results.isEmpty())
        controller.retry()
        assertEquals(BookSearchStatus.SEARCHING, controller.state.value.status)
        advanceTimeBy(201); runCurrent()
        assertEquals(2, attempts)
        assertEquals(BookSearchStatus.NO_RESULTS, controller.state.value.status)
        assertNull(controller.state.value.error)
    }

    @Test fun searchExecutesOnWorkerDispatcher() = runTest {
        val worker = StandardTestDispatcher(testScheduler, "search worker")
        var actual: Any? = null
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            actual = currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor]
            BookSearchBatch(emptyList())
        }, worker)
        controller.open(); controller.updateQuery("phrase")
        advanceTimeBy(201); runCurrent()
        assertSame(worker, actual)
    }

    @Test fun closePreventsNewWorkAndReopening() = runTest {
        var calls = 0
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            calls++; BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("pending"); controller.close()
        controller.open(); controller.updateQuery("ignored"); controller.retry()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(BookSearchState(), controller.state.value)
        assertEquals(0, calls)
    }

    @Test fun queryLengthIsBoundedBeforeBookWork() = runTest {
        var actual = ""
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            actual = it; BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("a".repeat(1_000))
        assertEquals(BOOK_SEARCH_QUERY_LIMIT, controller.state.value.query.length)
        advanceTimeBy(201); runCurrent()
        assertEquals(BOOK_SEARCH_QUERY_LIMIT, actual.length)
    }

    @Test fun queryCapKeepsSupplementaryUnicodeCharactersWhole() = runTest {
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("a".repeat(255) + "\uD83D\uDE00more")
        assertEquals("a".repeat(255), controller.state.value.query)
        controller.updateQuery("a".repeat(254) + "\uD83D\uDE00more")
        assertEquals("a".repeat(254) + "\uD83D\uDE00", controller.state.value.query)
        controller.close()
    }

    @Test fun newBookControllerCannotReceiveOldBooksCompletion() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val oldBook = BookSearchController(backgroundScope, BookSearchSource {
            withContext(NonCancellable) { started.complete(Unit); finish.await() }
            BookSearchBatch(emptyList())
        }, dispatcher)
        val newBook = BookSearchController(backgroundScope, BookSearchSource {
            BookSearchBatch(emptyList())
        }, dispatcher)
        oldBook.open(); oldBook.updateQuery("old book")
        advanceTimeBy(201); runCurrent(); assertTrue(started.isCompleted)
        oldBook.close(); newBook.open(); newBook.updateQuery("new book")
        advanceTimeBy(201); runCurrent()
        finish.complete(Unit); runCurrent()
        assertEquals(BookSearchState(), oldBook.state.value)
        assertEquals("new book", newBook.state.value.query)
        assertEquals(BookSearchStatus.NO_RESULTS, newBook.state.value.status)
    }

    @Test fun normalizationComposesAccentsAndPastedUnicodeSpaces() {
        assertEquals("Café phrase next", normalizedBookSearchQuery(" \tCafe\u0301\u00a0phrase\nnext\u2003"))
        assertEquals("", normalizedBookSearchQuery("\u00a0\u2003\n"))
        assertEquals("العربية 中文", normalizedBookSearchQuery("العربية\u2003中文"))
        assertEquals("literal [phrase]*", normalizedBookSearchQuery("literal [phrase]*"))
    }

    @Test fun navigationFailureCanBeRetriedWithoutMovingAnyPosition() = runTest {
        val controller = BookSearchController(backgroundScope, BookSearchSource {
            BookSearchBatch(emptyList())
        }, StandardTestDispatcher(testScheduler))
        controller.open(); controller.updateQuery("passage")
        advanceTimeBy(201); runCurrent(); controller.navigationFailed()
        assertEquals(BookSearchStatus.FAILED, controller.state.value.status)
        assertEquals("passage", controller.state.value.query)
        assertTrue(controller.state.value.error.orEmpty().contains("could not be opened"))
        controller.retry(); advanceTimeBy(201); runCurrent()
        assertEquals(BookSearchStatus.NO_RESULTS, controller.state.value.status)
    }
}
