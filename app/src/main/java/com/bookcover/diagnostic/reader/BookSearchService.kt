package com.bookcover.diagnostic.reader

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.SearchIterator
import android.icu.text.StringSearch
import com.bookcover.diagnostic.library.passivePublicationMarkup
import java.io.ByteArrayOutputStream
import java.text.StringCharacterIterator
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.StringSearchService
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.content.DefaultResourceContentExtractorFactory
import org.readium.r2.shared.util.resource.content.ResourceContentExtractor

internal const val BOOK_SEARCH_RESULT_LIMIT = 200
internal const val BOOK_SEARCH_QUERY_LIMIT = 256
private const val SEARCH_SNIPPET_LENGTH = 80
private const val MAX_SEARCH_RESOURCE_BYTES = 8 * 1024 * 1024L
private const val MAX_SEARCH_TEXT_CHARS = 2_000_000
private const val MAX_SNIPPET_CONTEXT = 1_024

/**
 * Keep Readium's EPUB extraction, locator generation and chapter titles. Its supported Algorithm
 * extension supplies the same ICU primary-strength literal matching as 3.4.0's IcuAlgorithm, with
 * cooperative cancellation and a bounded range list. The extra match tells the UI to show a cap.
 * Matching uses the publication's first language (device locale as fallback), literal substrings,
 * and the original text for navigation. Queries are NFC-normalized with pasted whitespace folded
 * to spaces by the controller. There is no stemming, fuzzy matching, regex or whole-word filter.
 * Phrases can cross paragraphs within one reading-order HTML resource, but never cross resources;
 * image-only pages/SVG have no extracted prose and are not searched with OCR.
 * Search holds one chapter at a time and the first 200 results. Chapters over 8 MiB before/after
 * cleanup, text over two million UTF-16 characters, or unusually long unbroken snippet context
 * fail with the recoverable search error. No persistent index is created; dismissal clears the
 * query/results and cancels the scan.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun bookSearchServiceFactory(rawContainer: Container<Resource>): (Publication.Service.Context) -> StringSearchService {
    val factory = StringSearchService.createDefaultFactory(
        snippetLength = SEARCH_SNIPPET_LENGTH,
        searchAlgorithm = BookSearchAlgorithm(),
        extractorFactory = BoundedSearchExtractorFactory(),
    )
    return { context ->
        val searchContainer = object : Container<Resource> by rawContainer {
            override fun get(url: Url): Resource? = rawContainer[url]?.let(::BoundedSearchMarkupResource)
            override fun close() = Unit // The publication, rather than search, owns the archive.
        }
        factory(Publication.Service.Context(context.manifest, searchContainer, context.services))
    }
}

/**
 * Search must bound reads before the navigator's TransformingResource: its read(range) first
 * sanitizes the entire entry, then allocates a boxed list of every requested byte index.
 */
internal class BoundedSearchMarkupResource(private val raw: Resource) : Resource by raw {
    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
        val bytes = ByteArrayOutputStream()
        var offset = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + 65_535, MAX_SEARCH_RESOURCE_BYTES)
            val requested = end - offset + 1
            val chunk = raw.read(offset..end).getOrElse { return Try.failure(it) }
            currentCoroutineContext().ensureActive()
            if (offset + chunk.size > MAX_SEARCH_RESOURCE_BYTES) {
                return Try.failure(ReadError.UnsupportedOperation("Search section exceeds the byte limit"))
            }
            bytes.write(chunk)
            offset += chunk.size
            if (chunk.size.toLong() < requested) break
        }
        val passive = passivePublicationMarkup(bytes.toByteArray())
        currentCoroutineContext().ensureActive()
        if (passive.size > MAX_SEARCH_RESOURCE_BYTES) {
            return Try.failure(ReadError.UnsupportedOperation("Search section exceeds the byte limit"))
        }
        return Try.success(if (range == null) passive else {
            val start = range.first.coerceIn(0, passive.size.toLong()).toInt()
            val end = (range.last + 1).coerceIn(start.toLong(), passive.size.toLong()).toInt()
            passive.copyOfRange(start, end)
        })
    }

    override fun close() = raw.close()
}

@OptIn(ExperimentalReadiumApi::class)
internal class BookSearchAlgorithm : StringSearchService.Algorithm {
    override val options = SearchService.Options(
        caseSensitive = false, diacriticSensitive = false, wholeWord = false,
    )

    override suspend fun findRanges(
        query: String, options: SearchService.Options, text: String, locale: Locale,
    ): List<IntRange> {
        currentCoroutineContext().ensureActive()
        if (query.isBlank() || text.isEmpty()) return emptyList()
        require(text.length <= MAX_SEARCH_TEXT_CHARS) { "Search section exceeds the text limit" }
        // This private service deliberately supports only the app's case/accent-insensitive mode.
        val collator = (Collator.getInstance(locale) as RuleBasedCollator).apply {
            strength = Collator.PRIMARY
        }
        val search = StringSearch(query, StringCharacterIterator(text), collator, null)
        val ranges = ArrayList<IntRange>()
        var start = search.first()
        while (start != SearchIterator.DONE) {
            currentCoroutineContext().ensureActive()
            val end = start + search.matchLength
            if (end > start) {
                // Readium extends its snippets to a word boundary. Refuse pathological unbroken
                // tokens before it allocates hundreds of enormous copies of the same chapter.
                require(boundedSnippet(text, start, end)) { "Search context exceeds the safe limit" }
                ranges += start until end
                if (ranges.size > BOOK_SEARCH_RESULT_LIMIT) break
            }
            start = search.next()
        }
        return ranges
    }

    private fun boundedSnippet(text: String, start: Int, end: Int): Boolean {
        var before = start - 1
        while (before >= 0 && (start - before <= SEARCH_SNIPPET_LENGTH + 1 || !text[before].isWhitespace())) {
            if (start - before > MAX_SNIPPET_CONTEXT) return false
            before--
        }
        var after = end
        while (after < text.length && (after - end <= SEARCH_SNIPPET_LENGTH || !text[after].isWhitespace())) {
            if (after - end > MAX_SNIPPET_CONTEXT) return false
            after++
        }
        return end - start <= MAX_SNIPPET_CONTEXT
    }
}

/** Limits search extraction only; the publication and navigator keep their normal resources. */
@OptIn(ExperimentalReadiumApi::class)
private class BoundedSearchExtractorFactory : ResourceContentExtractor.Factory {
    private val delegate = DefaultResourceContentExtractorFactory()

    override suspend fun createExtractor(resource: Resource, mediaType: MediaType): ResourceContentExtractor? {
        val extractor = delegate.createExtractor(resource, mediaType) ?: return null
        return object : ResourceContentExtractor {
            override suspend fun extractText(resource: Resource): Try<String, ReadError> {
                currentCoroutineContext().ensureActive()
                try {
                    val text = extractor.extractText(resource).getOrElse { return Try.failure(it) }
                    currentCoroutineContext().ensureActive()
                    if (text.length > MAX_SEARCH_TEXT_CHARS) {
                        return Try.failure(ReadError.UnsupportedOperation("Search section exceeds the text limit"))
                    }
                    return Try.success(text)
                } finally {
                    // FileZipContainer entries retain a range stream. Release each searched entry.
                    resource.close()
                }
            }
        }
    }
}
