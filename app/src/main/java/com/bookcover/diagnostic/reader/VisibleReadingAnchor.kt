package com.bookcover.diagnostic.reader

import org.json.JSONObject
import kotlinx.coroutines.delay
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
    repeat(3) {
        val anchor = visibleReadingAnchorAttempt()
        if (anchor != null && (anchor.locations["cssSelector"] == null ||
                anchor.text.highlight.isNullOrBlank() || anchor.text.before != null)) return anchor
        // Repagination/resume can move the viewport between the two public DOM queries.
        // Never publish an unrefined text block whose beginning may be on the previous page.
        delay(40)
    }
    return null
}

@OptIn(ExperimentalReadiumApi::class)
private suspend fun EpubNavigatorFragment.visibleReadingAnchorAttempt(): Locator? {
    val block = firstVisibleElementLocator() ?: return null
    val selector = block.locations["cssSelector"] as? String ?: return block
    val result = evaluateJavascript("""
        (function(){try {
            var block=document.querySelector(${JSONObject.quote(selector)});
            if(!block) return null;
            function visible(range) {
                var rects=range.getClientRects();
                for(var i=0;i<rects.length;i++) {
                    var r=rects[i];
                    if(r.width>0 && r.height>0 && r.right>1 && r.left<innerWidth-1 && r.bottom>1 && r.top<innerHeight-1) return true;
                }
                return false;
            }
            var walker=document.createTreeWalker(block,NodeFilter.SHOW_TEXT), node, count=0;
            while((node=walker.nextNode()) && count++<128) {
                if(!node.data.trim()) continue;
                var range=document.createRange(); range.selectNodeContents(node);
                if(!visible(range)) continue;
                // Prefix visibility is monotonic. Locate the first visible character without
                // caret hit-testing, which can select another column or fail on clipped text/RTL.
                var text=node.data, low=0, high=text.length-1;
                while(low<high) {
                    var mid=Math.floor((low+high)/2);
                    range.setStart(node,0); range.setEnd(node,mid+1);
                    if(visible(range)) high=mid; else low=mid+1;
                }
                var start=low;
                while(start<text.length && /\s/.test(text.charAt(start))) start++;
                if(start>=text.length) continue;
                range.setStart(node,start); range.setEnd(node,start+1);
                if(!visible(range)) continue;
                var quote=text.slice(start,start+64).trimEnd();
                if(!quote) continue;
                return {before:text.slice(Math.max(0,start-64),start),highlight:quote,after:text.slice(start+quote.length,start+quote.length+64)};
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
