package com.bookcover.diagnostic

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Opens a book and prepares its reading session while ensuring that any failure or coroutine
 * cancellation before ownership is handed to [commit] closes the created session (including its
 * lifecycle observer and publication) or, if the session has not been created yet, the opened
 * publication itself.
 */
internal suspend fun <P, A, S> openBookWithCleanup(
    ioContext: CoroutineContext,
    openPublication: suspend () -> P,
    closePublication: (P) -> Unit,
    loadArtwork: suspend () -> A,
    createSession: (P) -> S,
    closeSession: (S) -> Unit,
    markOpened: suspend () -> Unit,
    commit: (S, A) -> Unit,
): S {
    var publication: P? = null
    var session: S? = null
    var transferred = false
    try {
        val opened = withContext(ioContext) {
            val result = openPublication()
            publication = result
            currentCoroutineContext().ensureActive()
            result
        }
        val artwork = withContext(ioContext) { loadArtwork() }
        currentCoroutineContext().ensureActive()
        val created = createSession(opened)
        session = created
        markOpened()
        currentCoroutineContext().ensureActive()
        transferred = true
        commit(created, artwork)
        return created
    } catch (failure: Throwable) {
        if (!transferred) {
            val created = session
            val opened = publication
            if (created != null) {
                try {
                    closeSession(created)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            } else if (opened != null) {
                try {
                    closePublication(opened)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        throw failure
    }
}
