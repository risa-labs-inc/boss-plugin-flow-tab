package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A [ToolSource] (scope [ToolScope.BROWSER]) that exposes the browser as a set of
 * *stateless* tools threading a `session_id` (red-team F2/F12): each call names the
 * session it drives and delegates to the stateful [SessionRegistry]. This makes the
 * browser both a tool-node lane AND agent-callable, without any tool holding a
 * [BrowserIntegration] itself.
 *
 * `browser_open` mints (or reuses) a session and returns its `session_id`; the other
 * tools require one. All actions run under the session's fence via
 * [SessionRegistry.withSession]; failures come back as [ToolResult.isError] = true so
 * a tool-node executor turns them into an [ExecError] (F8) and an agent sees the error
 * text rather than a thrown exception. Click/type/extract reuse [BrowserScripts].
 */
class FlowBrowserToolSource(private val sessions: SessionRegistry) : ToolSource {

    override suspend fun list(): List<ToolDescriptor> = TOOLS

    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val args = runCatching { JSON.parseToJsonElement(argsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
        return try {
            when (name) {
                "browser_open" -> browserOpen(args)
                "browser_navigate" -> browserNavigate(args)
                "browser_click" -> browserClick(args)
                "browser_type" -> browserType(args)
                "browser_extract" -> browserExtract(args)
                "browser_close" -> browserClose(args)
                else -> err("Unknown browser tool '$name'")
            }
        } catch (e: Exception) {
            err(e.message ?: e.toString())
        }
    }

    // ---- tool implementations ----------------------------------------------

    private suspend fun browserOpen(args: JsonObject): ToolResult {
        val requested = args.str("session_id")
        val id = if (requested.isNotBlank()) {
            sessions.open(args.bool("headless"), requested)
        } else {
            sessions.open(args.bool("headless"))
        }
        val url = args.str("url")
        if (url.isNotBlank()) sessions.withSession(id) { it.navigate(url) }
        return ok(buildJsonObject { put("session_id", id); if (url.isNotBlank()) put("url", url) })
    }

    private suspend fun browserNavigate(args: JsonObject): ToolResult {
        val id = args.str("session_id")
        val url = args.str("url").ifBlank { return err("browser_navigate needs a 'url'") }
        sessions.withSession(id) { it.navigate(url) }
        return ok(buildJsonObject { put("session_id", id); put("url", url) })
    }

    private suspend fun browserClick(args: JsonObject): ToolResult {
        val id = args.str("session_id")
        val sel = args.str("selector").ifBlank { return err("browser_click needs a 'selector'") }
        val type = args.str("selectorType", "css")
        val matched = sessions.withSession(id) { session ->
            session.awaitElement(type, sel) && session.executeJavaScript(BrowserScripts.clickScript(type, sel)) == true
        }
        return if (matched) ok(buildJsonObject { put("clicked", sel) })
        else err("browser_click: no element matched '$sel'")
    }

    private suspend fun browserType(args: JsonObject): ToolResult {
        val id = args.str("session_id")
        val sel = args.str("selector").ifBlank { return err("browser_type needs a 'selector'") }
        val type = args.str("selectorType", "css")
        val text = args.str("text")
        val matched = sessions.withSession(id) { session ->
            session.awaitElement(type, sel) && session.executeJavaScript(BrowserScripts.inputScript(type, sel, text)) == true
        }
        return if (matched) ok(buildJsonObject { put("typed", sel) })
        else err("browser_type: no element matched '$sel'")
    }

    private suspend fun browserExtract(args: JsonObject): ToolResult {
        val id = args.str("session_id")
        val sel = args.str("selector").ifBlank { return err("browser_extract needs a 'selector'") }
        val type = args.str("selectorType", "css")
        val multiple = args.bool("multiple")
        val script = BrowserScripts.extractScript(
            selectorType = type,
            selector = sel,
            mode = args.str("mode", "text"),
            attr = args.str("attr"),
            multiple = multiple,
        )
        val raw = sessions.withSession(id) { session ->
            session.awaitElement(type, sel)
            session.executeJavaScript(script)
        }
        val str = raw as? String ?: return err("browser_extract returned non-string: $raw")
        val obj = runCatching { JSON.parseToJsonElement(str).jsonObject }.getOrNull()
            ?: return err("browser_extract: bad result '$str'")
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            return err("browser_extract failed: ${obj["error"]?.jsonPrimitive?.content ?: "unknown"}")
        }
        return ok(buildJsonObject { put("value", obj["value"] ?: JsonNull) })
    }

    private suspend fun browserClose(args: JsonObject): ToolResult {
        val id = args.str("session_id")
        sessions.close(id)
        return ok(buildJsonObject { put("closed", id) })
    }

    // ---- helpers ------------------------------------------------------------

    private fun ok(obj: JsonObject) = ToolResult(obj.toString(), false)
    private fun err(msg: String) = ToolResult(msg, true)

    private fun JsonObject.str(key: String, default: String = ""): String {
        val p = this[key] as? JsonPrimitive ?: return default
        if (p is JsonNull) return default
        return p.content
    }

    private fun JsonObject.bool(key: String): Boolean {
        val p = this[key] as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: p.content.equals("true", ignoreCase = true)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun desc(name: String, description: String, schema: String) =
            ToolDescriptor(ToolRef(ToolScope.BROWSER, name), name, description, schema)

        private const val SESSION_PROP = """"session_id":{"type":"string"}"""
        private const val SELECTOR_PROPS =
            """"selector":{"type":"string"},"selectorType":{"type":"string","enum":["css","xpath","text"]}"""

        /** The fixed browser tool set. Stateless: each names the session it drives. */
        val TOOLS: List<ToolDescriptor> = listOf(
            desc(
                "browser_open",
                "Open a browser session (headless or visible) and optionally navigate; returns its session_id.",
                """{"type":"object","properties":{"headless":{"type":"boolean"},"url":{"type":"string"},$SESSION_PROP}}""",
            ),
            desc(
                "browser_navigate",
                "Navigate an open browser session to a URL.",
                """{"type":"object","properties":{$SESSION_PROP,"url":{"type":"string"}},"required":["session_id","url"]}""",
            ),
            desc(
                "browser_click",
                "Click the first element matching a selector in a browser session.",
                """{"type":"object","properties":{$SESSION_PROP,$SELECTOR_PROPS},"required":["session_id","selector"]}""",
            ),
            desc(
                "browser_type",
                "Type text into the first element matching a selector in a browser session.",
                """{"type":"object","properties":{$SESSION_PROP,$SELECTOR_PROPS,"text":{"type":"string"}},"required":["session_id","selector","text"]}""",
            ),
            desc(
                "browser_extract",
                "Extract text/html/attribute from element(s) in a browser session.",
                """{"type":"object","properties":{$SESSION_PROP,$SELECTOR_PROPS,"mode":{"type":"string","enum":["text","html","attr"]},"attr":{"type":"string"},"multiple":{"type":"boolean"}},"required":["session_id","selector"]}""",
            ),
            desc(
                "browser_close",
                "Close an open browser session.",
                """{"type":"object","properties":{$SESSION_PROP},"required":["session_id"]}""",
            ),
        )
    }
}
