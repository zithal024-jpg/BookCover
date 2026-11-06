package com.bookcover.diagnostic.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilterTest {
    private fun book(id: Char, title: String, collection: String, read: Long = 0L) =
        BookRecord(id.toString().repeat(64), title, "Author $id", addedAt = 1L, collection = collection, lastReadAt = read)

    private val books = listOf(
        book('a', "Alpha", "All", read = 30), book('b', "Bravo", "Books", read = 20), book('c', "Charlie", "Poetry", read = 10),
    )

    @Test fun aCollectionNamedAllIsAnOrdinaryCollection() {
        assertEquals(listOf("All", "Books", "Poetry"), libraryCollections(books))
        assertEquals(listOf("Alpha"), visibleBooks(books, "All", "", "Recent").map { it.title })
    }

    @Test fun noSelectionShowsEveryCollection() {
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), visibleBooks(books, null, "", "Recent").map { it.title })
    }

    @Test fun searchAndSortStillApply() {
        assertEquals(listOf("Charlie"), visibleBooks(books, null, "char", "Title").map { it.title })
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), visibleBooks(books.reversed(), null, "", "Title").map { it.title })
        assertEquals(listOf("Alpha"), visibleBooks(books, null, "author a", "Author").map { it.title })
    }
}
