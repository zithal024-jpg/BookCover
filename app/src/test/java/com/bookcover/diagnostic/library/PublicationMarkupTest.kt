package com.bookcover.diagnostic.library

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class PublicationMarkupTest {
    @Test fun removesPublicationScriptsAndActiveEmbeddingWithoutLosingReadingContent() {
        val original = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Book</title><link href="style.css" rel="stylesheet"/><meta http-equiv="refresh" content="0;url=https://example.com"/><script>alert('x')</script></head><body onload="run()"><h1>A chapter</h1><p id="anchor">A readable page &amp; a picture.</p><img src="images/cover.png"/><iframe src="https://example.com"/><object data="bad"/><a href="java&#x09;script:run()" onclick="run()">link</a></body></html>"""
        val parsed = Jsoup.parse(passivePublicationMarkup(original.toByteArray()).toString(Charsets.UTF_8), "", Parser.xmlParser())
        assertEquals("A chapter", parsed.selectFirst("h1")!!.text())
        assertEquals("A readable page & a picture.", parsed.selectFirst("p#anchor")!!.text())
        assertEquals("images/cover.png", parsed.selectFirst("img")!!.attr("src"))
        assertEquals("style.css", parsed.selectFirst("link")!!.attr("href"))
        assertTrue(parsed.select("script,iframe,object,meta[http-equiv]").isEmpty())
        assertFalse(parsed.selectFirst("body")!!.hasAttr("onload"))
        assertFalse(parsed.selectFirst("a")!!.hasAttr("href"))
        assertFalse(parsed.selectFirst("a")!!.hasAttr("onclick"))
    }

    // BC-18: chapters are decoded by their declared encoding and always come out as UTF-8 that says so.
    private val chapter = """<?xml version="1.0" encoding="ENCODING"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body><p id="text">Café — “naïve” 読書 עברית</p><script>bad()</script></body></html>"""
    private fun sanitized(bytes: ByteArray) = passivePublicationMarkup(bytes).let { output ->
        assertFalse("No byte-order mark in the output", output.size >= 3 && output[0] == 0xEF.toByte() && output[1] == 0xBB.toByte())
        output.toString(Charsets.UTF_8)
    }
    private fun assertReadableUtf8(result: String) {
        val parsed = Jsoup.parse(result, "", Parser.xmlParser())
        assertEquals("Café — “naïve” 読書 עברית", parsed.selectFirst("p#text")!!.text())
        assertTrue("Scripts are still removed", parsed.select("script").isEmpty())
        assertFalse("No declaration may claim a non-UTF-8 encoding: $result", Regex("(?i)encoding=\"(?!utf-8)").containsMatchIn(result))
    }

    @Test fun utf16WithByteOrderMarkIsDecoded() {
        assertReadableUtf8(sanitized(chapter.replace("ENCODING", "UTF-16").toByteArray(Charsets.UTF_16)))
        assertReadableUtf8(sanitized(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chapter.replace("ENCODING", "UTF-16").toByteArray(Charsets.UTF_16LE)))
    }

    @Test fun utf16WithoutByteOrderMarkIsRecognised() {
        assertReadableUtf8(sanitized(chapter.replace("ENCODING", "UTF-16").toByteArray(Charsets.UTF_16LE)))
        assertReadableUtf8(sanitized(chapter.replace("ENCODING", "UTF-16").toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun declaredSingleByteEncodingIsHonoured() {
        val latin = """<?xml version="1.0" encoding="ISO-8859-1"?><html xmlns="http://www.w3.org/1999/xhtml"><body><p id="text">Café naïve</p></body></html>"""
        val result = sanitized(latin.toByteArray(Charsets.ISO_8859_1))
        assertEquals("Café naïve", Jsoup.parse(result, "", Parser.xmlParser()).selectFirst("p#text")!!.text())
        assertTrue(result.contains("encoding=\"UTF-8\"", ignoreCase = true))
    }

    @Test fun htmlMetaCharsetIsHonouredAndRewritten() {
        val html = """<html><head><meta charset="windows-1252"/><title>T</title></head><body><p id="text">Café “quoted”</p></body></html>"""
        val result = sanitized(html.toByteArray(charset("windows-1252")))
        val parsed = Jsoup.parse(result, "", Parser.xmlParser())
        assertEquals("Café “quoted”", parsed.selectFirst("p#text")!!.text())
        assertEquals("UTF-8", parsed.selectFirst("meta[charset]")!!.attr("charset"))
    }

    @Test fun utf8WithByteOrderMarkLosesTheMark() {
        assertReadableUtf8(sanitized(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + chapter.replace("ENCODING", "UTF-8").toByteArray()))
    }

    @Test fun anUnknownDeclaredEncodingFallsBackToUtf8() {
        assertReadableUtf8(sanitized(chapter.replace("ENCODING", "x-made-up").toByteArray()))
    }

    @Test fun preservesSvgAndChapterAnchors() {
        val text = """<svg xmlns="http://www.w3.org/2000/svg"><image href="cover.jpg"/><script>bad()</script></svg><a href="chapter2.xhtml#point">Next</a>"""
        val result = passivePublicationMarkup(text.toByteArray()).toString(Charsets.UTF_8)
        assertTrue(result.contains("cover.jpg"))
        assertTrue(result.contains("chapter2.xhtml#point"))
        assertFalse(result.contains("<script"))
    }
}
