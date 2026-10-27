package com.bookcover.diagnostic.testing

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original deterministic prose, generated inside the isolated QA app; no owner's books are used. */
internal object SearchEpubFixture {
    const val CHAPTERS = 30
    const val PARAGRAPHS_PER_CHAPTER = 50
    const val WORDS_PER_PARAGRAPH = 100
    const val LATE_CHAPTER = 28
    const val LATE_PARAGRAPH = 40
    const val LATE_PHRASE = "violet compass waits beyond the northern waterfall"
    const val EARLY_PHRASE = "amber orchard returns before the winter moon"
    const val UNICODE_PHRASE = "Café naïve façade Ångström"

    data class Generated(val file: File, val bodyWords: Int, val htmlBytes: Long) {
        val zipBytes: Long get() = file.length()
    }

    fun write(directory: File): Generated {
        directory.mkdirs()
        val file = File(directory, "search-500-page-equivalent.epub")
        var bodyWords = 0
        var htmlBytes = 0L
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, content: String, stored: Boolean = false) {
                val bytes = content.toByteArray(Charsets.UTF_8)
                val item = ZipEntry(name).apply {
                    time = 0
                    if (stored) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(item)
                zip.write(bytes)
                zip.closeEntry()
                if (name.endsWith(".xhtml")) htmlBytes += bytes.size
            }
            entry("mimetype", "application/epub+zip", stored = true)
            entry("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val manifest = (0 until CHAPTERS).joinToString("") {
                """<item id="c$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spine = (0 until CHAPTERS).joinToString("") { """<itemref idref="c$it"/>""" }
            entry("EPUB/book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:search-500-pages-v1</dc:identifier><dc:title>The River Survey: search performance fixture</dc:title><dc:creator>BookCover test suite</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-27T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine>$spine</spine></package>""")
            val contents = (0 until CHAPTERS).joinToString("") {
                """<li><a href="chapter$it.xhtml">${chapterTitle(it)}</a></li>"""
            }
            entry("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$contents</ol></nav></body></html>""")
            for (chapter in 0 until CHAPTERS) {
                val title = chapterTitle(chapter)
                bodyWords += title.split(' ').size
                val paragraphs = (0 until PARAGRAPHS_PER_CHAPTER).joinToString("") { paragraph ->
                    val prose = paragraphText(chapter, paragraph)
                    bodyWords += prose.split(' ').size
                    """<p id="search-$chapter-$paragraph">$prose</p>"""
                }
                entry("EPUB/chapter$chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>$title</title></head><body><h1>$title</h1>$paragraphs</body></html>""")
            }
        }
        return Generated(file, bodyWords, htmlBytes)
    }

    fun chapterTitle(chapter: Int): String = "Chapter ${chapter + 1}: River survey"

    private fun paragraphText(chapter: Int, paragraph: Int): String {
        val special = when {
            chapter == LATE_CHAPTER && paragraph == LATE_PARAGRAPH -> LATE_PHRASE
            chapter == 0 && paragraph == 2 -> EARLY_PHRASE
            chapter == 5 && paragraph == 10 -> "$UNICODE_PHRASE. Ελληνικά Καλημέρα. Русский рассвет. العربية صباح."
            else -> "Field notebook ${chapter + 1}, observation ${paragraph + 1}."
        }
        val sentences = listOf(
            "The surveyor followed the river through a valley of quiet farms and recorded each bend in a small paper notebook.",
            "Clouds moved slowly above the ridge while reeds along the bank sheltered insects from the steady afternoon wind.",
            "At the old bridge a carpenter measured the weathered rails and explained how spring floods had changed the crossing.",
            "Every stone carried a different shade of green where moss gathered in its cracks and held the morning dew.",
            "Neighbors brought baskets of apples to the ferry and traded stories about journeys beyond the distant market town.",
            "By evening the lamps shone in kitchen windows and a warm scent of bread reached the path beside the water.",
            "A pair of herons waited near the shallows until the sound of footsteps sent them rising toward the eastern marsh.",
            "The map grew more detailed with every mile although its penciled lines could never capture the changing light."
        )
        val words = special.split(' ').toMutableList()
        var sentence = (chapter * 7 + paragraph * 3) % sentences.size
        while (words.size < WORDS_PER_PARAGRAPH) {
            words += sentences[sentence].split(' ')
            sentence = (sentence + 1) % sentences.size
        }
        return words.take(WORDS_PER_PARAGRAPH).joinToString(" ")
    }
}
