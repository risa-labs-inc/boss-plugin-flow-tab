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
 * tools threading a `session_id` (red-team F2/F12): each call names the session it
 * drives and delegates to the stateful [SessionRegistry]. A run-bound source may
 * supply [defaultSessionId], making that argument optional for agents while explicit
 * ids still select another session. This makes the browser both a tool-node lane AND
 * agent-callable, without any tool holding a [BrowserIntegration] itself.
 *
 * `browser_open` mints (or reuses) a session and returns its `session_id`; the other
 * tools require one unless this source is run-bound. All actions run under the session's fence via
 * [SessionRegistry.withSession]; failures come back as [ToolResult.isError] = true so
 * a tool-node executor turns them into an [ExecError] (F8) and an agent sees the error
 * text rather than a thrown exception. Click/type/extract reuse [BrowserScripts].
 */
class FlowBrowserToolSource(
    private val sessions: SessionRegistry,
    private val defaultSessionId: String? = null,
) : ToolSource {
    init {
        require(defaultSessionId == null || defaultSessionId.isNotBlank()) {
            "defaultSessionId must be non-blank when supplied"
        }
    }

    override suspend fun list(): List<ToolDescriptor> =
        if (defaultSessionId == null) TOOLS else RUN_BOUND_TOOLS

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
        val boundDefault = defaultSessionId
        val id = when {
            requested.isNotBlank() -> sessions.open(args.bool("headless"), requested)
            boundDefault == null -> sessions.open(args.bool("headless"))
            sessions.get(boundDefault) != null -> boundDefault
            else -> sessions.open(args.bool("headless"), boundDefault)
        }
        val url = args.str("url")
        if (url.isNotBlank()) sessions.withSession(id) { it.navigate(url) }
        return ok(buildJsonObject { put("session_id", id); if (url.isNotBlank()) put("url", url) })
    }

    private suspend fun browserNavigate(args: JsonObject): ToolResult {
        val id = args.sessionId("browser_navigate")
        val url = args.str("url").ifBlank { return err("browser_navigate needs a 'url'") }
        sessions.withSession(id) { it.navigate(url) }
        return ok(buildJsonObject { put("session_id", id); put("url", url) })
    }

    private suspend fun browserClick(args: JsonObject): ToolResult {
        val id = args.sessionId("browser_click")
        val sel = args.str("selector").ifBlank { return err("browser_click needs a 'selector'") }
        val type = args.str("selectorType", "css")
        val matched = sessions.withSession(id) { session ->
            session.awaitElement(type, sel) && session.executeJavaScript(BrowserScripts.clickScript(type, sel)) == true
        }
        return if (matched) ok(buildJsonObject { put("clicked", sel) })
        else err("browser_click: no element matched '$sel'")
    }

    private suspend fun browserType(args: JsonObject): ToolResult {
        val id = args.sessionId("browser_type")
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
        val id = args.sessionId("browser_extract")
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
        val id = args.sessionId("browser_close")
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

    private fun JsonObject.sessionId(tool: String): String =
        str("session_id").ifBlank {
            defaultSessionId ?: throw ExecError("$tool needs a 'session_id'")
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun desc(name: String, description: String, schema: String) =
            ToolDescriptor(ToolRef(ToolScope.BROWSER, name), name, description, schema)

        private const val SESSION_PROP = """"session_id":{"type":"string"}"""
        private const val SELECTOR_PROPS =
            """"selector":{"type":"string"},"selectorType":{"type":"string","enum":["css","xpath","text"]}"""

        private fun schema(properties: String, required: List<String>): String {
            val requiredJson = if (required.isEmpty()) {
                ""
            } else {
                required.joinToString(prefix = ",\"required\":[", postfix = "]") { "\"$it\"" }
            }
            return "{\"type\":\"object\",\"properties\":{$properties}$requiredJson}"
        }

        private fun tools(sessionRequired: Boolean): List<ToolDescriptor> {
            val sessionHint = if (sessionRequired) {
                ""
            } else {
                " Omit session_id to use this flow run's browser session."
            }
            val session = if (sessionRequired) listOf("session_id") else emptyList()
            return listOf(
                desc(
                    "browser_open",
                    "Open or reuse a browser session (headless or visible) and optionally navigate; " +
                        "returns its session_id.$sessionHint",
                    schema(
                        "\"headless\":{\"type\":\"boolean\"},\"url\":{\"type\":\"string\"},$SESSION_PROP",
                        emptyList(),
                    ),
                ),
                desc(
                    "browser_navigate",
                    "Navigate an open browser session to a URL.$sessionHint",
                    schema("$SESSION_PROP,\"url\":{\"type\":\"string\"}", session + "url"),
                ),
                desc(
                    "browser_click",
                    "Click the first element matching a selector in a browser session.$sessionHint",
                    schema("$SESSION_PROP,$SELECTOR_PROPS", session + "selector"),
                ),
                desc(
                    "browser_type",
                    "Type text into the first element matching a selector in a browser session.$sessionHint",
                    schema(
                        "$SESSION_PROP,$SELECTOR_PROPS,\"text\":{\"type\":\"string\"}",
                        session + listOf("selector", "text"),
                    ),
                ),
                desc(
                    "browser_extract",
                    "Extract text/html/attribute from element(s) in a browser session.$sessionHint",
                    schema(
                        "$SESSION_PROP,$SELECTOR_PROPS," +
                            "\"mode\":{\"type\":\"string\",\"enum\":[\"text\",\"html\",\"attr\"]}," +
                            "\"attr\":{\"type\":\"string\"},\"multiple\":{\"type\":\"boolean\"}",
                        session + "selector",
                    ),
                ),
                desc(
                    "browser_close",
                    "Close an open browser session.$sessionHint",
                    schema(SESSION_PROP, session),
                ),
            )
        }

        /** Browser tools for callers that must name every session explicitly. */
        val TOOLS: List<ToolDescriptor> = tools(sessionRequired = true)

        /** Browser tools presented to an agent bound to its run's default session. */
        private val RUN_BOUND_TOOLS: List<ToolDescriptor> = tools(sessionRequired = false)
    }
}
