package ai.rever.boss.plugin.dynamic.flowtab

internal const val EXTRACT_NO_MATCH_ERROR = "no element matched"

/**
 * JavaScript builders for browser actions, run via [ai.rever.boss.plugin.browser.BrowserHandle.executeJavaScript].
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

    /** Escapes text embedded in a single-quoted JavaScript literal. */
    internal fun escapeSingleQuotedContent(s: String): String = buildString(s.length) {
        for (char in s) {
            when (char) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

    /**
     * JS expression for the document in which an action should run.  The browser
     * bridge evaluates in the top-level frame, so a child frame must be resolved
     * explicitly. Callers probe it first to provide a useful cross-origin error.
     */
    private fun documentExpr(frameSelector: String): String = if (frameSelector.isBlank()) {
        "document"
    } else {
        "document.querySelector('${escapeSingleQuotedContent(frameSelector)}').contentDocument"
    }

    /** JS expression evaluating to the first matching element (or null). */
    fun elementExpr(selectorType: String, selector: String, frameSelector: String = ""): String {
        val v = escapeSingleQuotedContent(selector)
        val document = documentExpr(frameSelector)
        return when (selectorType) {
            "xpath" -> "($document).evaluate('$v', $document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue"
            "text" -> "Array.from(($document).querySelectorAll('*')).find(function(e){return e.textContent && e.textContent.trim()==='$v';})"
            else -> "($document).querySelector('$v')"
        }
    }

    /** JS expression evaluating to an array of all matching elements. */
    private fun allElementsExpr(selectorType: String, selector: String, frameSelector: String): String {
        val v = escapeSingleQuotedContent(selector)
        val document = documentExpr(frameSelector)
        return when (selectorType) {
            "xpath" -> "(function(){var d=$document;var r=d.evaluate('$v',d,null,XPathResult.ORDERED_NODE_SNAPSHOT_TYPE,null);var a=[];for(var i=0;i<r.snapshotLength;i++)a.push(r.snapshotItem(i));return a;})()"
            "text" -> "Array.from(($document).querySelectorAll('*')).filter(function(e){return e.textContent && e.textContent.trim()==='$v';})"
            else -> "Array.from(($document).querySelectorAll('$v'))"
        }
    }

    /** Reports whether an optional frame can be scripted from the top-level page. */
    fun frameProbeScript(frameSelector: String): String {
        if (frameSelector.isBlank()) return "'ok'"
        val selector = escapeSingleQuotedContent(frameSelector)
        return "(function(){var f=document.querySelector('$selector');if(!f)return 'missing';" +
            "try{return f.contentDocument?'ok':'cross-origin';}catch(e){return 'cross-origin';}})()"
    }

    /** Returns true if clicked, false if no element matched. */
    fun clickScript(selectorType: String, selector: String, frameSelector: String = ""): String =
        "(function(){var el=${elementExpr(selectorType, selector, frameSelector)}; if(!el) return false; el.click(); return true;})()"

    /** Sets a field's value + fires input/change events. Returns true/false. */
    fun inputScript(selectorType: String, selector: String, value: String, frameSelector: String = ""): String {
        val tv = escapeSingleQuotedContent(value)
        return "(function(){var el=${elementExpr(selectorType, selector, frameSelector)}; if(!el) return false; " +
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
        multiple: Boolean,
        frameSelector: String = "",
    ): String {
        val a = escapeSingleQuotedContent(attr)
        val readOne = when (mode) {
            "html" -> "function(e){return e.innerHTML;}"
            "attr" -> "function(e){return e.getAttribute('$a');}"
            else -> "function(e){return (e.textContent||'').trim();}"
        }
        return if (multiple) {
            "(function(){try{var els=${allElementsExpr(selectorType, selector, frameSelector)}; var f=$readOne; " +
                "return JSON.stringify({ok:true, value: els.map(f)});}" +
                "catch(e){return JSON.stringify({ok:false, error:String(e)});}})()"
        } else {
            "(function(){try{var el=${elementExpr(selectorType, selector, frameSelector)}; " +
                "if(!el) return JSON.stringify({ok:false, error:'$EXTRACT_NO_MATCH_ERROR'}); var f=$readOne; " +
                "return JSON.stringify({ok:true, value: f(el)});}" +
                "catch(e){return JSON.stringify({ok:false, error:String(e)});}})()"
        }
    }
}
