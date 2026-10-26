package com.bookcover.diagnostic.testing

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generated test EPUBs, one per thing real books do (BC-13). All text and images are original, made
 * here at test time; nothing is downloaded or copyrighted. [marker] is a sentence from the first
 * chapter that must appear, readable, in the rendered page.
 */
internal object EpubCorpus {
    enum class Expect { READS, REJECTED }

    /** [knownIssue]: a BC issue this book is expected to fail until fixed; reported as skipped, not passed. */
    class Book(val name: String, val expect: Expect, val marker: String = "", val fixedLayout: Boolean = false,
               val knownIssue: String? = null, val write: (File) -> Unit) {
        override fun toString() = name
    }

    val books: List<Book> = listOf(
        Book("epub3-basic", Expect.READS, "The basic book opens") { standard(it, "The basic book opens") },
        Book("epub2-ncx-only", Expect.READS, "An EPUB 2 book with an NCX") { epub2(it) },
        Book("rtl-arabic", Expect.READS, "مرحبا بالقارئ") {
            standard(it, "مرحبا بالقارئ", lang = "ar", dir = "rtl", progression = "rtl", paragraphs = 120)
        },
        Book("rtl-hebrew", Expect.READS, "שלום לקורא") {
            standard(it, "שלום לקורא", lang = "he", dir = "rtl", progression = "rtl", paragraphs = 120)
        },
        Book("cjk-japanese-horizontal", Expect.READS, "読書の始まり") {
            standard(it, "読書の始まり", lang = "ja", paragraphs = 120, filler = "静かな川のほとりで本を読む。")
        },
        Book("cjk-japanese-vertical", Expect.READS, "縦書きの本") {
            standard(it, "縦書きの本", lang = "ja", progression = "rtl", paragraphs = 120, filler = "静かな川のほとりで本を読む。",
                css = "html { writing-mode: vertical-rl; -epub-writing-mode: vertical-rl; }")
        },
        Book("tables", Expect.READS, "A chapter with tables") {
            val rows = (1..40).joinToString("") { "<tr><td>Row $it</td><td>${it * 3}</td><td>Note $it</td></tr>" }
            standard(it, "A chapter with tables", extraBody = "<table><thead><tr><th>Name</th><th>Value</th><th>Note</th></tr></thead><tbody>$rows</tbody></table>")
        },
        Book("footnotes", Expect.READS, "A chapter with footnotes") {
            val notes = (1..10).joinToString("") { "<aside epub:type=\"footnote\" id=\"n$it\"><p>Footnote $it explains a detail.</p></aside>" }
            val refs = (1..10).joinToString(" ") { "Claim $it<a epub:type=\"noteref\" href=\"#n$it\">$it</a>." }
            standard(it, "A chapter with footnotes", extraBody = "<p>$refs</p>$notes")
        },
        Book("embedded-font", Expect.READS, "A chapter in an embedded font") { embeddedFont(it) },
        Book("mathml", Expect.READS, "A chapter with mathematics") {
            standard(it, "A chapter with mathematics", properties = "mathml",
                extraBody = "<math xmlns=\"http://www.w3.org/1998/Math/MathML\"><mi>x</mi><mo>=</mo><mfrac><mn>1</mn><mn>2</mn></mfrac></math>")
        },
        Book("images-and-svg", Expect.READS, "A chapter with pictures") { pictures(it) },
        Book("scripts-and-forms", Expect.READS, "Scripts are removed") {
            standard(it, "Scripts are removed", properties = "scripted",
                extraBody = "<script>document.title='hacked'</script><form action=\"x\"><input name=\"q\"/></form><p onclick=\"alert(1)\">Click handlers are removed.</p>")
        },
        Book("remote-resources", Expect.READS, "Web links stay offline") {
            standard(it, "Web links stay offline", properties = "remote-resources",
                extraBody = "<img src=\"https://example.com/picture.png\" alt=\"remote\"/><p><a href=\"https://example.com\">A web link</a></p>")
        },
        Book("many-chapters-200", Expect.READS, "Chapter one of two hundred") { standard(it, "Chapter one of two hundred", chapters = 200, paragraphs = 3) },
        Book("one-huge-chapter", Expect.READS, "A very long chapter") { standard(it, "A very long chapter", paragraphs = 3000) },
        Book("unicode-and-emoji", Expect.READS, "Café naïve résumé 📚") { standard(it, "Café naïve résumé 📚 — “quotes” and ½") },
        Book("odd-file-names", Expect.READS, "File names with spaces") { oddNames(it) },
        Book("epub2-meta-cover", Expect.READS, "A cover named in metadata") { epub2(it, marker = "A cover named in metadata", metaCover = true) },
        Book("fixed-layout", Expect.READS, fixedLayout = true) { fixed(it) },
        Book("missing-chapter-file", Expect.READS, "The first chapter still opens") { standard(it, "The first chapter still opens", chapters = 2, omitChapter = 1) },
        // The script-stripping step reads every chapter as UTF-8, so UTF-16 text comes out garbled.
        Book("utf16-chapter", Expect.READS, "A chapter saved as UTF-16", knownIssue = "BC-18") { standard(it, "A chapter saved as UTF-16", utf16 = true) },
        Book("html-tag-soup", Expect.READS, "Loose HTML still reads") { tagSoup(it) },
        Book("large-150mb", Expect.READS, "A very large book") { large(it) },
        Book("not-a-zip", Expect.REJECTED) { it.writeText("This is a text file pretending to be a book.") },
        Book("zip-without-container", Expect.REJECTED) { zip(it, listOf("EPUB/chapter0.xhtml" to xhtml("No container", "<p>No container</p>").toByteArray())) },
        Book("container-points-nowhere", Expect.REJECTED) { zip(it, listOf("META-INF/container.xml" to container("EPUB/missing.opf"))) },
        Book("no-readable-chapters", Expect.REJECTED) {
            zip(it, listOf("META-INF/container.xml" to container(), "EPUB/book.opf" to opf("empty", "Empty", items = "", spine = "")))
        },
        Book("drm-encrypted", Expect.REJECTED) { standard(it, "Encrypted", encrypted = true) },
    )

    private fun standard(file: File, marker: String, lang: String = "en", dir: String = "ltr", progression: String? = null,
                         chapters: Int = 1, paragraphs: Int = 40, filler: String = defaultFiller, css: String = "",
                         extraBody: String = "", properties: String? = null, omitChapter: Int? = null,
                         utf16: Boolean = false, encrypted: Boolean = false) {
        val entries = mutableListOf("META-INF/container.xml" to container())
        val items = StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
        val spine = StringBuilder()
        for (index in 0 until chapters) {
            val props = if (index == 0 && properties != null) " properties=\"$properties\"" else ""
            items.append("<item id=\"c$index\" href=\"chapter$index.xhtml\" media-type=\"application/xhtml+xml\"$props/>")
            spine.append("<itemref idref=\"c$index\"/>")
            if (index == omitChapter) continue
            val lead = if (index == 0) marker else "Chapter ${index + 1}"
            val body = "<h1>$lead</h1><p id=\"marker\">$lead</p>" + (if (index == 0) extraBody else "") +
                (0 until paragraphs).joinToString("") { "<p>$filler ${it + 1}.</p>" }
            val page = xhtml(lead, body, lang, dir)
            entries += "EPUB/chapter$index.xhtml" to if (utf16) page.replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"").toByteArray(Charsets.UTF_16)
                else page.toByteArray()
        }
        entries += "EPUB/nav.xhtml" to nav((0 until chapters).map { "chapter$it.xhtml" to "Chapter ${it + 1}" }, lang)
        entries += "EPUB/style.css" to "body { line-height: 1.5; } $css".toByteArray()
        entries += "EPUB/book.opf" to opf(file.nameWithoutExtension, marker, lang, items.toString(), spine.toString(), progression)
        if (encrypted) entries += "META-INF/encryption.xml" to ("""<?xml version="1.0"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/><enc:CipherData><enc:CipherReference URI="EPUB/chapter0.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>""").toByteArray()
        zip(file, entries)
    }

    private fun epub2(file: File, marker: String = "An EPUB 2 book with an NCX", metaCover: Boolean = false) {
        val cover = if (metaCover) "<item id=\"cover-image\" href=\"cover.png\" media-type=\"image/png\"/>" else ""
        val opf = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:corpus:${file.nameWithoutExtension}</dc:identifier><dc:title>$marker</dc:title><dc:creator>BookCover test corpus</dc:creator><dc:language>en</dc:language>${if (metaCover) "<meta name=\"cover\" content=\"cover-image\"/>" else ""}</metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="c0" href="chapter0.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>$cover</manifest><spine toc="ncx"><itemref idref="c0"/><itemref idref="c1"/></spine></package>"""
        val ncx = """<?xml version="1.0" encoding="UTF-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="urn:bookcover:corpus"/></head><docTitle><text>$marker</text></docTitle><navMap><navPoint id="p0" playOrder="1"><navLabel><text>One</text></navLabel><content src="chapter0.xhtml"/></navPoint><navPoint id="p1" playOrder="2"><navLabel><text>Two</text></navLabel><content src="chapter1.xhtml"/></navPoint></navMap></ncx>"""
        val entries = mutableListOf("META-INF/container.xml" to container(), "EPUB/book.opf" to opf.toByteArray(), "EPUB/toc.ncx" to ncx.toByteArray(),
            "EPUB/chapter0.xhtml" to xhtml(marker, "<h1>$marker</h1><p id=\"marker\">$marker</p>" + paragraphs(60)).toByteArray(),
            "EPUB/chapter1.xhtml" to xhtml("Two", "<h1>Two</h1>" + paragraphs(60)).toByteArray())
        if (metaCover) entries += "EPUB/cover.png" to png(600, 900, Color.rgb(120, 40, 60))
        zip(file, entries)
    }

    private fun embeddedFont(file: File) {
        val font = File("/system/fonts").listFiles { f -> f.name.endsWith(".ttf") }.orEmpty().minByOrNull { it.length() }
            ?: error("No system font to embed")
        val marker = "A chapter in an embedded font"
        val css = "@font-face { font-family: 'CorpusFont'; src: url('fonts/corpus.ttf'); } body { font-family: 'CorpusFont', serif; }"
        zip(file, listOf("META-INF/container.xml" to container(),
            "EPUB/book.opf" to opf(file.nameWithoutExtension, marker, items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"css\" href=\"style.css\" media-type=\"text/css\"/><item id=\"font\" href=\"fonts/corpus.ttf\" media-type=\"font/ttf\"/><item id=\"c0\" href=\"chapter0.xhtml\" media-type=\"application/xhtml+xml\"/>", spine = "<itemref idref=\"c0\"/>"),
            "EPUB/nav.xhtml" to nav(listOf("chapter0.xhtml" to "One")), "EPUB/style.css" to css.toByteArray(), "EPUB/fonts/corpus.ttf" to font.readBytes(),
            "EPUB/chapter0.xhtml" to xhtml(marker, "<h1>$marker</h1><p id=\"marker\">$marker</p>" + paragraphs(40)).toByteArray()))
    }

    private fun pictures(file: File) {
        val marker = "A chapter with pictures"
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="300" height="200" fill="#2a6"/><circle cx="150" cy="100" r="60" fill="#fd4"/></svg>"""
        zip(file, listOf("META-INF/container.xml" to container(),
            "EPUB/book.opf" to opf(file.nameWithoutExtension, marker, items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"cover\" href=\"images/cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/><item id=\"big\" href=\"images/big.png\" media-type=\"image/png\"/><item id=\"svg\" href=\"images/shape.svg\" media-type=\"image/svg+xml\"/><item id=\"c0\" href=\"chapter0.xhtml\" media-type=\"application/xhtml+xml\"/>", spine = "<itemref idref=\"c0\"/>"),
            "EPUB/nav.xhtml" to nav(listOf("chapter0.xhtml" to "One")),
            "EPUB/images/cover.jpg" to jpeg(1600, 2400, Color.rgb(30, 60, 120)), "EPUB/images/big.png" to png(3000, 2000, Color.rgb(200, 120, 40)),
            "EPUB/images/shape.svg" to svg.toByteArray(),
            "EPUB/chapter0.xhtml" to xhtml(marker, "<h1>$marker</h1><p id=\"marker\">$marker</p><img src=\"images/big.png\" alt=\"wide picture\" style=\"max-width:100%\"/><img src=\"images/shape.svg\" alt=\"shape\"/>" + paragraphs(20)).toByteArray()))
    }

    private fun oddNames(file: File) {
        val marker = "File names with spaces"
        zip(file, listOf("META-INF/container.xml" to container("OEBPS/content folder/book.opf"),
            "OEBPS/content folder/book.opf" to opf(file.nameWithoutExtension, marker, items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"c0\" href=\"Text/Chapter%20One%20%C3%A9.xhtml\" media-type=\"application/xhtml+xml\"/>", spine = "<itemref idref=\"c0\"/>"),
            "OEBPS/content folder/nav.xhtml" to nav(listOf("Text/Chapter%20One%20%C3%A9.xhtml" to "One")),
            "OEBPS/content folder/Text/Chapter One é.xhtml" to xhtml(marker, "<h1>$marker</h1><p id=\"marker\">$marker</p>" + paragraphs(40)).toByteArray()))
    }

    private fun fixed(file: File) {
        val pages = (0..1).map { "EPUB/page$it.xhtml" to """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page $it</title><meta name="viewport" content="width=600,height=800"/></head><body style="margin:0;width:600px;height:800px;background:#f5eddb"><h1 style="position:absolute;left:40px;top:60px">Fixed page $it</h1></body></html>""".toByteArray() }
        zip(file, listOf("META-INF/container.xml" to container(),
            "EPUB/book.opf" to opf(file.nameWithoutExtension, "Fixed layout", items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"p0\" href=\"page0.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"p1\" href=\"page1.xhtml\" media-type=\"application/xhtml+xml\"/>", spine = "<itemref idref=\"p0\"/><itemref idref=\"p1\"/>", extraMeta = "<meta property=\"rendition:layout\">pre-paginated</meta>"),
            "EPUB/nav.xhtml" to nav(listOf("page0.xhtml" to "One", "page1.xhtml" to "Two"))) + pages)
    }

    private fun tagSoup(file: File) {
        val marker = "Loose HTML still reads"
        val soup = "<html><head><title>Soup</title></head><body><h1>$marker<p id=\"marker\">$marker<p>Unclosed paragraph&nbsp;with an HTML entity &copy; and <br> a bare break" +
            (1..40).joinToString("") { "<p>$defaultFiller $it" } + "</body></html>"
        zip(file, listOf("META-INF/container.xml" to container(),
            "EPUB/book.opf" to opf(file.nameWithoutExtension, marker, items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"c0\" href=\"chapter0.html\" media-type=\"text/html\"/>", spine = "<itemref idref=\"c0\"/>"),
            "EPUB/nav.xhtml" to nav(listOf("chapter0.html" to "One")), "EPUB/chapter0.html" to soup.toByteArray()))
    }

    /** About 150 MB: two incompressible 75 MB resources (each under the importer's 100 MB entry limit). */
    private fun large(file: File) {
        val marker = "A very large book"
        val random = Random(13)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun entry(name: String, data: ByteArray, stored: Boolean = false) = putEntry(zip, name, data, stored)
            entry("mimetype", "application/epub+zip".toByteArray(), stored = true)
            entry("META-INF/container.xml", container())
            entry("EPUB/book.opf", opf(file.nameWithoutExtension, marker, items = "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"a\" href=\"data/a.bin\" media-type=\"application/octet-stream\"/><item id=\"b\" href=\"data/b.bin\" media-type=\"application/octet-stream\"/><item id=\"c0\" href=\"chapter0.xhtml\" media-type=\"application/xhtml+xml\"/>", spine = "<itemref idref=\"c0\"/>"))
            entry("EPUB/nav.xhtml", nav(listOf("chapter0.xhtml" to "One")))
            entry("EPUB/chapter0.xhtml", xhtml(marker, "<h1>$marker</h1><p id=\"marker\">$marker</p>" + paragraphs(40)).toByteArray())
            for (name in listOf("a", "b")) entry("EPUB/data/$name.bin", ByteArray(75 * 1024 * 1024).also(random::nextBytes), stored = true)
        }
    }

    private const val defaultFiller = "The keeper walked along the river and counted the stones that marked each turning of the path."
    private fun paragraphs(count: Int) = (1..count).joinToString("") { "<p>$defaultFiller $it.</p>" }

    private fun container(opfPath: String = "EPUB/book.opf") =
        """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()

    private fun opf(id: String, title: String, lang: String = "en", items: String, spine: String, progression: String? = null, extraMeta: String = "") =
        ("""<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:corpus:$id</dc:identifier><dc:title>${title.replace("&", "&amp;")}</dc:title><dc:creator>BookCover test corpus</dc:creator><dc:language>$lang</dc:language><meta property="dcterms:modified">2026-10-26T00:00:00Z</meta>$extraMeta</metadata><manifest>$items</manifest><spine${progression?.let { " page-progression-direction=\"$it\"" } ?: ""}>$spine</spine></package>""").toByteArray()

    private fun nav(entries: List<Pair<String, String>>, lang: String = "en") =
        ("""<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>""" +
            entries.joinToString("") { (href, label) -> "<li><a href=\"$href\">$label</a></li>" } + "</ol></nav></body></html>").toByteArray()

    private fun xhtml(title: String, body: String, lang: String = "en", dir: String = "ltr") =
        """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$lang" lang="$lang" dir="$dir"><head><title>$title</title><link rel="stylesheet" href="style.css"/></head><body>$body</body></html>"""

    private fun png(width: Int, height: Int, color: Int) = image(width, height, color, Bitmap.CompressFormat.PNG)
    private fun jpeg(width: Int, height: Int, color: Int) = image(width, height, color, Bitmap.CompressFormat.JPEG)
    private fun image(width: Int, height: Int, color: Int, format: Bitmap.CompressFormat) = ByteArrayOutputStream().use { bytes ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(format, 90, bytes)
        bytes.toByteArray()
    }

    private fun zip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            putEntry(zip, "mimetype", "application/epub+zip".toByteArray(), stored = true)
            entries.forEach { (name, data) -> putEntry(zip, name, data) }
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, data: ByteArray, stored: Boolean = false) {
        val entry = ZipEntry(name).apply {
            time = 0
            if (stored) { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; crc = CRC32().apply { update(data) }.value }
        }
        zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
    }
}
