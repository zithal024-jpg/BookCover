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

    @Test fun preservesSvgAndChapterAnchors() {
        val text = """<svg xmlns="http://www.w3.org/2000/svg"><image href="cover.jpg"/><script>bad()</script></svg><a href="chapter2.xhtml#point">Next</a>"""
        val result = passivePublicationMarkup(text.toByteArray()).toString(Charsets.UTF_8)
        assertTrue(result.contains("cover.jpg"))
        assertTrue(result.contains("chapter2.xhtml#point"))
        assertFalse(result.contains("<script"))
    }
}
