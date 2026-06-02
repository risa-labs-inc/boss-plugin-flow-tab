package ai.rever.boss.plugin.dynamic.flowtab

/**
 * JavaScript builders for browser actions, run via [RpaBrowserSession.executeJavaScript].
 *
 * NOTE: the eng review's plan was to promote these from rpaengine into the shared
 * `boss-plugin-api`. That package is parent-first / host-provided (like the
 * `openTab` API), so anything we put there must also ship in the host's
 * plugin-api-core, which needs a host rebuild. To keep Phase 1 self-contained we
 * inline them here, in the plugin's own (child-first) package. Consolidating
 * rpaengine + flow-tab onto one shared copy is a follow-up that requires a host
 * change. The logic mirrors rpaengine's RpaRunnerImpl.
 *
 * Extract returns `JSON.stringify({ ok, value })` so structured data crosses the
 * JxBrowser bridge as a parseable string, never an untyped `Any?` (see review).
 */
object BrowserScripts {

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    /** JS expression evaluating to the first matching element (or null). */
    fun elementExpr(selectorType: String, selector: String): String {
        val v = esc(selector)
        return when (selectorType) {
            "xpath" -> "document.evaluate('$v', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue"
            "text" -> "Array.from(document.querySelectorAll('*')).find(function(e){return e.textContent && e.textContent.trim()==='$v';})"
            else -> "document.querySelector('$v')"
        }
    }

    /** JS expression evaluating to an array of all matching elements. */
    private fun allElementsExpr(selectorType: String, selector: String): String {
        val v = esc(selector)
        return when (selectorType) {
            "xpath" -> "(function(){var r=document.evaluate('$v',document,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);var a=[];for(var i=0;i<r.snapshotLength;i++)a.push(r.snapshotItem(i));return a;})()"
            "text" -> "Array.from(document.querySelectorAll('*')).filter(function(e){return e.textContent && e.textContent.trim()==='$v';})"
            else -> "Array.from(document.querySelectorAll('$v'))"
        }
    }

    /** Returns true if clicked, false if no element matched. */
    fun clickScript(selectorType: String, selector: String): String =
        "(function(){var el=${elementExpr(selectorType, selector)}; if(!el) return false; el.click(); return true;})()"

    /** Sets a field's value + fires input/change events. Returns true/false. */
    fun inputScript(selectorType: String, selector: String, value: String): String {
        val tv = esc(value)
        return "(function(){var el=${elementExpr(selectorType, selector)}; if(!el) return false; " +
            "el.focus(); el.value='$tv'; " +
            "el.dispatchEvent(new Event('input',{bubbles:true})); " +
            "el.dispatchEvent(new Event('change',{bubbles:true})); return true;})()"
    }

    /**
     * Returns a JSON string `{ok:true, value:<string|array>}` or
     * `{ok:false, error:<msg>}`. [mode] = text | html | attr.
     */
    fun extractScript(
        selectorType: String,
        selector: String,
        mode: String,
        attr: String,
        multiple: Boolean
    ): String {
        val a = esc(attr)
        val readOne = when (mode) {
            "html" -> "function(e){return e.innerHTML;}"
            "attr" -> "function(e){return e.getAttribute('$a');}"
            else -> "function(e){return (e.textContent||'').trim();}"
        }
        return if (multiple) {
            "(function(){try{var els=${allElementsExpr(selectorType, selector)}; var f=$readOne; " +
                "return JSON.stringify({ok:true, value: els.map(f)});}" +
                "catch(e){return JSON.stringify({ok:false, error:String(e)});}})()"
        } else {
            "(function(){try{var el=${elementExpr(selectorType, selector)}; " +
                "if(!el) return JSON.stringify({ok:false, error:'no element matched'}); var f=$readOne; " +
                "return JSON.stringify({ok:true, value: f(el)});}" +
                "catch(e){return JSON.stringify({ok:false, error:String(e)});}})()"
        }
    }
}
