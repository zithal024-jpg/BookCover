package com.bookcover.diagnostic.library

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class ReadingImageMarkupTest {
    @Test fun makesOnlyInertKindlePanelAnchorsPassiveWithoutChangingTheirLayoutOrContent() {
        val original = """<html xmlns="http://www.w3.org/1999/xhtml"><body><img id="page" src="page.png"/><a id="panel" class="app-amzn-magnify" data-app-amzn-magnify="{}" style="width:100%;height:100%"><span>Panel</span></a><a id="real" class="app-amzn-magnify" data-app-amzn-magnify="{}" href="chapter2.xhtml"><img src="thumbnail.png"/></a><a id="named"></a><a id="unrelated" class="other"></a></body></html>"""
        val document = Jsoup.parse(passivePublicationMarkup(original.toByteArray()).toString(Charsets.UTF_8), "", Parser.xmlParser())
        val panel = document.getElementById("panel")!!
        assertEquals("span", panel.tagName())
        assertEquals("app-amzn-magnify", panel.className())
        assertEquals("width:100%;height:100%", panel.attr("style"))
        assertEquals("Panel", panel.text())
        assertEquals("page.png", document.getElementById("page")!!.attr("src"))
        assertEquals("a", document.getElementById("real")!!.tagName())
        assertEquals("chapter2.xhtml", document.getElementById("real")!!.attr("href"))
        assertEquals("thumbnail.png", document.selectFirst("#real img")!!.attr("src"))
        assertEquals("a", document.getElementById("named")!!.tagName())
        assertEquals("a", document.getElementById("unrelated")!!.tagName())
    }

    @Test fun normalizationIsIdempotentAndKeepsExplicitHrefEvenWhenEmpty() {
        val document = Jsoup.parse("""<a id="passive" class="app-amzn-magnify" data-app-amzn-magnify="{}"></a><a id="empty" class="app-amzn-magnify" data-app-amzn-magnify="{}" href=""></a>""", "", Parser.xmlParser())
        normalizePassiveImageOverlays(document)
        val once = document.outerHtml()
        normalizePassiveImageOverlays(document)
        assertEquals(once, document.outerHtml())
        assertEquals("a", document.getElementById("empty")!!.tagName())
        assertTrue(document.getElementById("empty")!!.hasAttr("href"))
    }
}
