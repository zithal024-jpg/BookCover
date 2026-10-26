package com.bookcover.diagnostic.testing

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original, generated EPUBs: long reflowable prose and a separate fixed-layout document. */
internal object NativeEpubFixture {
    fun write(directory: File, fixed: Boolean, progression: String? = null, language: String = "en",
              imagePages: Boolean = false, legacyOverlay: Boolean = false): File {
        val direction = progression ?: if (language in listOf("ar", "he")) "rtl" else "ltr"
        val spineDirection = progression?.let { " page-progression-direction=\"$it\"" }.orEmpty()
        val image = ByteArrayOutputStream().use { bytes ->
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 100, 70)) }
                .compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bytes.toByteArray()
        }
        val fixture = File(directory, if (fixed) "fixed.epub" else "reflowable.epub")
        ZipOutputStream(fixture.outputStream()).use { zip ->
            fun entry(name: String, data: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(name).apply {
                    time = 0
                    if (stored) {
                        method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                    }
                }
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
            fun text(name: String, content: String) = entry(name, content.toByteArray(Charsets.UTF_8))
            entry("mimetype", "application/epub+zip".toByteArray(), stored = true)
            text("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val layout = if (fixed) "<meta property=\"rendition:layout\">pre-paginated</meta>" else ""
            text("EPUB/book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:native-test-${if (fixed) "fixed" else "reflow"}</dc:identifier><dc:title>Native renderer fixture</dc:title><dc:creator>BookCover test suite</dc:creator><dc:language>$language</dc:language><meta property="dcterms:modified">2026-10-26T00:00:00Z</meta>$layout</metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="image" href="square.png" media-type="image/png"/><item id="c0" href="chapter0.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest><spine$spineDirection><itemref idref="c0"/><itemref idref="c1"/></spine></package>""")
            text("EPUB/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter0.xhtml">The long chapter</a></li><li><a href="chapter1.xhtml">The linked chapter</a></li></ol></nav></body></html>""")
            entry("EPUB/square.png", image)
            if (fixed) {
                for (index in 0..1) text("EPUB/chapter$index.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixed page $index</title><meta name="viewport" content="width=600,height=800"/></head><body style="margin:0;width:600px;height:800px;background:#f5eddb"><h1 style="position:absolute;left:40px;top:60px;font-size:36px">Fixed page $index</h1><p style="position:absolute;left:40px;top:180px;font-size:20px">Publisher layout remains fixed.</p><img src="square.png" style="position:absolute;left:40px;top:260px;width:64px;height:64px"/></body></html>""")
            } else if (imagePages) {
                for (index in 0..1) {
                    val overlay = if (legacyOverlay) """<a class="app-amzn-magnify" data-app-amzn-magnify="{}" style="position:fixed;inset:0;z-index:2"></a>""" else ""
                    text("EPUB/chapter$index.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" lang="$language" dir="$direction"><head><title>Image page $index</title><style>html,body {margin:0;padding:0;} #graphic {position:fixed;inset:0;width:100vw;height:100vh;} #image-link {position:fixed;left:0;top:25vh;width:10vw;height:10vh;z-index:3;} #image-link img {width:100%;height:100%;}</style></head><body><img id="graphic" src="square.png" alt="Original test graphic $index"/>$overlay<a id="image-link" href="https://example.com/"><img src="square.png" alt="Test link"/></a></body></html>""")
                }
            } else {
                val paragraphs = (0 until 90).joinToString("") { index ->
                    """<p id="paragraph-$index">Paragraph $index. The river carried small reflections beneath the bridge as the keeper walked home. Each familiar stone had a story, and each turning in the path revealed another quiet corner of the valley. Reading these original words provides enough continuous prose to test changing line wraps, semantic position, paragraph spacing, and many page turns without substituting a picture of a page. <em>Emphasized words remain italic.</em> <strong>Strong words remain bold.</strong> The paragraph closes with a distinct marker number $index.</p>"""
                }
                text("EPUB/chapter0.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" lang="$language" dir="$direction"><head><title>The long chapter</title><style>p {font-size:18px;} #sample-image {width:64px;height:64px;} #edge-link {position:fixed;left:2px;top:45%;font-size:16px;z-index:10;} #poem {text-indent:3em;text-align:center;line-height:1.9;font-family:monospace;white-space:pre-line;}</style></head><body><h1 id="heading">The long chapter</h1><p id="poem">A lantern waits beside the sea.
Its light returns a path to me.</p><img id="sample-image" src="square.png" alt="A small square"/><a id="edge-link" href="chapter1.xhtml">Link</a>$paragraphs</body></html>""")
                text("EPUB/chapter1.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" lang="$language" dir="$direction"><head><title>The linked chapter</title></head><body><h1>Linked chapter reached</h1><p id="linked">An internal link followed from the edge must reach this chapter instead of turning to an unrelated page.</p><a href="chapter0.xhtml">Return</a></body></html>""")
            }
        }
        return fixture
    }
}
