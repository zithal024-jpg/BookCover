package com.bookcover.diagnostic.library

import java.nio.charset.Charset
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser

/** Publication-authored active content is removed before Readium adds its own reading scripts. */
internal fun passivePublicationMarkup(bytes: ByteArray): ByteArray {
    val document = Jsoup.parse(decodeMarkup(bytes), "", Parser.xmlParser())
    document.outputSettings().prettyPrint(false)
    document.select("script, iframe, object, embed, form").remove()
    document.select("meta[http-equiv]").filter {
        it.attr("http-equiv").equals("refresh", true) || it.attr("http-equiv").equals("content-security-policy", true)
    }.forEach { it.remove() }
    document.getAllElements().forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val name = attribute.key.lowercase()
            val value = attribute.value.filterNot { it.isWhitespace() || it.code < 32 }.lowercase()
            if (name.startsWith("on") || name == "srcdoc" ||
                ((name == "href" || name == "src" || name == "xlink:href" || name == "action") &&
                    (value.startsWith("javascript:") || value.startsWith("data:text/html")))) {
                element.removeAttr(attribute.key)
            }
        }
    }
    declareUtf8(document)
    normalizePassiveImageOverlays(document)
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}

/**
 * Reads markup in its own encoding (BC-18): a byte-order mark first, then the byte pattern of UTF-16
 * without one, then the XML declaration or a meta charset. Anything unknown is read as UTF-8.
 */
internal fun decodeMarkup(bytes: ByteArray): String {
    fun at(index: Int) = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
    val (charset, skip) = when {
        at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8 to 3
        at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
        at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
        at(0) == 0x00 && at(1) == 0x3C -> Charsets.UTF_16BE to 0
        at(0) == 0x3C && at(1) == 0x00 -> Charsets.UTF_16LE to 0
        else -> (declaredCharset(bytes) ?: Charsets.UTF_8) to 0
    }
    return String(bytes, skip, bytes.size - skip, charset)
}

private val xmlEncoding = Regex("""<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""")
private val metaCharset = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)

private fun declaredCharset(bytes: ByteArray): Charset? {
    // Declarations are plain ASCII, which Latin-1 reads correctly whatever the document's real encoding.
    val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
    val name = xmlEncoding.find(head)?.groupValues?.get(1) ?: metaCharset.find(head)?.groupValues?.get(1) ?: return null
    // A two- or four-byte encoding would already have been recognised from the bytes; a label that says so is wrong.
    return runCatching { Charset.forName(name) }.getOrNull()?.takeUnless { it.name().startsWith("UTF-16") || it.name().startsWith("UTF-32") }
}

/** The output is UTF-8, so every encoding label in the document must say so. */
private fun declareUtf8(document: Document) {
    document.childNodes().filterIsInstance<XmlDeclaration>().filter { it.hasAttr("encoding") }.forEach { it.attr("encoding", "UTF-8") }
    document.select("meta[charset]").attr("charset", "UTF-8")
    document.select("meta[http-equiv]").filter { it.attr("http-equiv").equals("content-type", true) }
        .forEach { it.attr("content", "text/html; charset=UTF-8") }
}
