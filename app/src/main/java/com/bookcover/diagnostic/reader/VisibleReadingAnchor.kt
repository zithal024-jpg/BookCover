package com.bookcover.diagnostic.reader

import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * A block may start on the previous page. Restoring its whole text moves back to that page.
 * Refine Readium's public block locator with a quote from the part actually visible now.
 * This only reads DOM ranges: it never changes selection, scroll position, or gesture handling.
 */
@OptIn(ExperimentalReadiumApi::class)
internal suspend fun EpubNavigatorFragment.visibleReadingAnchor(): Locator? {
    val block = firstVisibleElementLocator() ?: return null
    val selector = block.locations["cssSelector"] as? String ?: return block
    val result = evaluateJavascript("""
        (function(){try {
            var block=document.querySelector(${JSONObject.quote(selector)});
            if(!block || !document.caretRangeFromPoint) return null;
            var walker=document.createTreeWalker(block,NodeFilter.SHOW_TEXT), node, count=0;
            while((node=walker.nextNode()) && count++<128) {
                if(!node.data.trim()) continue;
                var range=document.createRange(); range.selectNodeContents(node);
                var rects=range.getClientRects();
                for(var i=0;i<rects.length && i<512;i++) {
                    var r=rects[i];
                    if(r.width<=0 || r.height<=0 || r.right<=1 || r.left>=innerWidth-1 || r.bottom<=1 || r.top>=innerHeight-1) continue;
                    var x=Math.max(1,r.left)+Math.min(2,(Math.min(innerWidth-1,r.right)-Math.max(1,r.left))/2);
                    var y=Math.max(1,r.top)+Math.min(r.height/2,(Math.min(innerHeight-1,r.bottom)-Math.max(1,r.top))/2);
                    var caret=document.caretRangeFromPoint(x,y);
                    if(!caret || caret.startContainer.nodeType!==Node.TEXT_NODE || !block.contains(caret.startContainer)) continue;
                    var text=caret.startContainer.data, start=caret.startOffset;
                    while(start<text.length && /\s/.test(text.charAt(start))) start++;
                    var quote=text.slice(start,start+64).trimEnd();
                    if(!quote) continue;
                    return {before:text.slice(Math.max(0,start-64),start),highlight:quote,after:text.slice(start+quote.length,start+quote.length+64)};
                }
            }
            return null;
        } catch(e) {return null;}})()
    """.trimIndent()) ?: return block
    return runCatching {
        val json = JSONObject(result)
        val quote = json.getString("highlight").takeIf { it.isNotBlank() } ?: return block
        block.copy(text = Locator.Text(before = json.optString("before"), highlight = quote, after = json.optString("after")))
    }.getOrDefault(block)
}
