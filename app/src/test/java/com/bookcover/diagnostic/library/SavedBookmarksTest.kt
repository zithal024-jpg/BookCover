package com.bookcover.diagnostic.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SavedBookmarksTest {
    private fun locator(progression: Double = 0.25, title: String = "Chapter two", href: String = "chapter2.xhtml") =
        """{"href":"$href","type":"application/xhtml+xml","title":"$title","locations":{"progression":$progression,"position":12,"totalProgression":0.4},"custom":{"keep":"yes"}}"""

    private fun pdfLocator(page: Int, offset: String? = null, position: String = page.toString(), progression: Double = 0.25): String {
        val extra = offset?.let { ",\"${SavedBookmarks.PDF_TEXT_OFFSET}\":$it" }.orEmpty()
        return """{"href":"book.pdf","type":"application/pdf","locations":{"fragments":["page=$page"],"position":$position,"progression":$progression$extra}}"""
    }

    @Test fun createAndRenameKeepLocatorFieldsChapterTitleAndUnknownExtensions() {
        val raw = locator()
        val created = SavedBookmarks.create(raw, "  My place  ", "Current chapter")
        assertEquals("My place", SavedBookmarks.name(created))
        assertEquals("Current chapter", SavedBookmarks.chapterTitle(created))
        val renamed = SavedBookmarks.rename(created, "Another name")
        assertEquals("Another name", SavedBookmarks.name(renamed))
        assertEquals("Current chapter", SavedBookmarks.chapterTitle(renamed))
        val json = JSONObject(renamed)
        assertEquals("yes", json.getJSONObject("custom").getString("keep"))
        assertEquals(0.25, json.getJSONObject("locations").getDouble("progression"), 0.0)
        assertEquals(12, json.getJSONObject("locations").getInt("position"))
        assertEquals("chapter2.xhtml", json.getString("href"))
        assertEquals(120, SavedBookmarks.name(SavedBookmarks.rename(created, "x".repeat(200)))!!.length)
    }

    @Test fun legacyAndMalformedBookmarksRemainReadableAndAreNotErased() {
        val legacy = locator()
        assertNull(SavedBookmarks.name(legacy))
        assertEquals("Chapter two", SavedBookmarks.chapterTitle(legacy))
        val malformed = "{ old broken bookmark"
        assertEquals(malformed, SavedBookmarks.create(malformed, "Name"))
        assertEquals(malformed, SavedBookmarks.rename(malformed, "Name"))
        assertEquals(listOf(malformed, legacy, "other invalid entry"),
            SavedBookmarks.merge(listOf(malformed, legacy), listOf(malformed, "other invalid entry")))
    }

    @Test fun duplicateSaveRelabelsTheOriginalTargetRatherThanAddingOrMovingIt() {
        val original = SavedBookmarks.create(locator(), "Original")
        val incoming = SavedBookmarks.create(locator(title = "Updated chapter title"), "Updated")
        val result = SavedBookmarks.upsert(listOf(original), incoming)
        assertEquals(1, result.size)
        assertEquals("Updated", SavedBookmarks.name(result.single()))
        assertEquals("Chapter two", SavedBookmarks.chapterTitle(result.single()))
        assertEquals(JSONObject(original).getJSONObject("custom").toString(), JSONObject(result.single()).getJSONObject("custom").toString())
        assertEquals(result, SavedBookmarks.upsert(result, SavedBookmarks.create(locator())))
    }

    @Test fun duplicateComparisonIgnoresJsonOrderingNamesAndPresentationMetadata() {
        val first = SavedBookmarks.create(locator(), "A")
        val reordered = """{"title":"Other title","locations":{"totalProgression":0.41,"bookcoverBookmarkName":"B","position":12,"progression":0.2500},"type":"application/xhtml+xml","href":"chapter2.xhtml"}"""
        assertTrue(SavedBookmarks.sameTarget(first, reordered))
        assertFalse(SavedBookmarks.sameTarget(first, locator(0.35)))
        assertFalse(SavedBookmarks.sameTarget(first, locator(href = "other.xhtml")))
    }

    @Test fun backupMergeKeepsLocalNamesButFillsMissingNamesWithoutLosingLegacyEntries() {
        val original = locator()
        val named = SavedBookmarks.create(original, "Local name")
        val backup = SavedBookmarks.create(original, "Backup name")
        val another = locator(0.8)
        val merged = SavedBookmarks.merge(listOf(named), listOf(backup, another))
        assertEquals(2, merged.size)
        assertEquals("Local name", SavedBookmarks.name(merged.first()))
        assertEquals(another, merged.last())
        assertEquals("Backup name", SavedBookmarks.name(SavedBookmarks.merge(listOf(original), listOf(backup)).single()))
        assertEquals(listOf("""{"href":"a.xhtml"}""", """{"href":"b.xhtml"}"""),
            SavedBookmarks.merge(listOf("""{"href":"a.xhtml"}"""), listOf("""{"href":"a.xhtml"}""", """{"href":"b.xhtml"}""")))
    }

    @Test fun coarseGlobalPositionsDoNotCollapseDifferentPlacesInTheSameResource() {
        val first = """{"href":"chapter.xhtml","locations":{"position":12},"title":"One"}"""
        val second = """{"href":"chapter.xhtml","locations":{"position":12},"title":"Two"}"""
        assertFalse(SavedBookmarks.sameTarget(first, second))
        assertEquals(2, SavedBookmarks.merge(listOf(first), listOf(second)).size)
    }

    @Test fun preciseAnchorsSurviveReflowButConflictingQuotesOrSelectorsStaySeparate() {
        val a = """{"href":"chapter.xhtml","locations":{"cssSelector":"#paragraph-5","progression":0.1},"text":{"highlight":"A visible quotation","before":"Before","after":"After"}}"""
        val reflowed = a.replace("0.1", "0.15")
        assertTrue(SavedBookmarks.sameTarget(a, reflowed))
        assertFalse(SavedBookmarks.sameTarget(a, reflowed.replace("#paragraph-5", "#paragraph-6")))
        assertFalse(SavedBookmarks.sameTarget(a, a.replace("A visible quotation", "Another quotation")))
        assertFalse(SavedBookmarks.sameTarget(a, a.replace("Before", "Different context")))
        val fragment = """{"href":"chapter.xhtml","locations":{"fragments":["section-1"]}}"""
        assertTrue(SavedBookmarks.sameTarget(fragment, fragment.replace("fragments\":[\"section-1\"]", "fragment\":\"section-1\"")))
    }

    @Test fun namesAndUnknownExtensionsSurviveTheExistingRecordAndBackupFormats() {
        val named = SavedBookmarks.create(locator(), "A named bookmark")
        val record = BookRecord("a".repeat(64), "Book", "Author", 1L, bookmarks = listOf(named))
        val restored = LibraryBackup.parse(LibraryBackup.export(listOf(record), 2L)).records.single()
        assertEquals(record, restored)
        assertEquals("A named bookmark", SavedBookmarks.name(restored.bookmarks.single()))
        assertEquals("yes", JSONObject(restored.bookmarks.single()).getJSONObject("custom").getString("keep"))
    }

    @Test fun pdfTextOffsetsDistinguishPlacesOnOnePhysicalPage() {
        val physical = SavedBookmarks.create(pdfLocator(5), "Whole page")
        val start = SavedBookmarks.create(pdfLocator(5, "0"), "Start")
        val later = SavedBookmarks.create(pdfLocator(5, "143"), "Later")
        val renamedLater = SavedBookmarks.create(pdfLocator(5, "143", progression = 0.3), "Renamed")
        val otherPage = SavedBookmarks.create(pdfLocator(6, "143"), "Next page")

        assertFalse(SavedBookmarks.sameTarget(physical, start))
        assertFalse(SavedBookmarks.sameTarget(start, later))
        assertTrue(SavedBookmarks.sameTarget(later, renamedLater))
        assertFalse(SavedBookmarks.sameTarget(later, otherPage))

        val saved = listOf(physical, start, later, otherPage).fold(emptyList<String>(), SavedBookmarks::upsert)
        assertEquals(4, saved.size)
        val relabeled = SavedBookmarks.upsert(saved, renamedLater)
        assertEquals(4, relabeled.size)
        assertEquals("Renamed", SavedBookmarks.name(relabeled[2]))
        assertEquals("Later", SavedBookmarks.name(SavedBookmarks.merge(saved, listOf(renamedLater))[2]))
    }

    @Test fun pdfTextOffsetRequiresValidOffsetsAndUnambiguousPageEvidence() {
        val valid = pdfLocator(5, "42")
        for (invalid in listOf("-1", "1.5", "2147483648", "\"42\"", "null", "true", "{}")) {
            val malformed = pdfLocator(5, invalid)
            assertFalse("invalid offset $invalid", SavedBookmarks.sameTarget(valid, malformed))
            assertFalse("invalid offset $invalid", SavedBookmarks.sameTarget(malformed, SavedBookmarks.create(malformed, "Name")))
        }
        assertFalse(SavedBookmarks.sameTarget(valid, pdfLocator(5, "42", position = "6")))
        assertFalse(SavedBookmarks.sameTarget(valid, pdfLocator(5, "42", position = "null")))
        assertFalse(SavedBookmarks.sameTarget(valid, pdfLocator(5, "42", position = "1.5")))
        assertFalse(SavedBookmarks.sameTarget(valid, """{"href":"book.pdf","type":"application/pdf","locations":{"bookcoverPdfTextOffset":42,"progression":0.25}}"""))
        assertFalse(SavedBookmarks.sameTarget(valid, """{"href":"book.pdf","type":"application/pdf","locations":{"fragments":["page=5",6],"position":5,"bookcoverPdfTextOffset":42}}"""))
        val compatibleAliases = """{"href":"book.pdf","type":"application/pdf","locations":{"fragments":["page=5"],"fragment":"page=5","position":5,"bookcoverPdfTextOffset":42}}"""
        val conflictingAliases = compatibleAliases.replace("\"fragment\":\"page=5\"", "\"fragment\":\"page=6\"")
        assertTrue(SavedBookmarks.sameTarget(valid, compatibleAliases))
        assertFalse(SavedBookmarks.sameTarget(valid, conflictingAliases))
    }

    @Test fun pdfTextBookmarkExtensionsSurviveRenameRecordAndBackupRoundTrip() {
        val original = SavedBookmarks.create(pdfLocator(5, "123"), "A place")
        val renamed = SavedBookmarks.rename(original, "Another place")
        val record = BookRecord("a".repeat(64), "Book", "Author", 1L, bookmarks = listOf(renamed), format = BookFormat.PDF)
        val restored = LibraryBackup.parse(LibraryBackup.export(listOf(record), 2L)).records.single()
        val locations = JSONObject(restored.bookmarks.single()).getJSONObject("locations")
        assertEquals(123, locations.getInt(SavedBookmarks.PDF_TEXT_OFFSET))
        assertEquals(5, locations.getInt("position"))
        assertEquals("Another place", SavedBookmarks.name(restored.bookmarks.single()))
    }
}
