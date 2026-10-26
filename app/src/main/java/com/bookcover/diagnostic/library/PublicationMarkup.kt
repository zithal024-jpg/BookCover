package com.bookcover.diagnostic.library

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Publication-authored active content is removed before Readium adds its own reading scripts. */
internal fun passivePublicationMarkup(bytes: ByteArray): ByteArray {
    val document = Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser())
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
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}
