package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Thrown by an executor to fail its node with a clear message. */
class ExecError(message: String) : Exception(message)

private val EXEC_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

// Element-wait tuning: a browser action polls for its target to appear before
// acting, so a node firing right after a click/navigation doesn't race the page
// still loading its content. Generic across every browser node.
private const val ELEMENT_WAIT_MS = 20_000
private const val ELEMENT_POLL_MS = 200

/**
 * Polls (up to [timeoutMs]) for [selector] to match an element, returning whether
 * it appeared. Lets a node tolerate content that renders a beat after the previous
 * step finished — the single most common cause of "it only worked the second run"
 * flakiness, since [BrowserIntegration.executeJavaScript] is synchronous and can't
 * await the page itself.
 */
private suspend fun BrowserIntegration.awaitElement(
    selectorType: String,
    selector: String,
    timeoutMs: Int = ELEMENT_WAIT_MS,
    pollMs: Int = ELEMENT_POLL_MS,
): Boolean {
    val existsScript =
        "(function(){try{return !!(${BrowserScripts.elementExpr(selectorType, selector)});}catch(e){return false;}})()"
    var waited = 0
    while (true) {
        if (executeJavaScript(existsScript) == true) return true
        if (waited >= timeoutMs) return false
        delay(pollMs.toLong()); waited += pollMs
    }
}

/**
 * Runtime resources shared across a single run. Owns the browser session.
 *
 * The session is the host's [BrowserIntegration] driving interface. A **visible**
 * run (the `headless` flag off — the default) opens a real Fluck browser tab in a
 * right split via [ai.rever.boss.plugin.api.ActiveTabsProvider.createBrowserTabInRightSplit]
 * and drives the host's own integration for it (wrapped by [LoadAwaitingIntegration]
 * so navigation blocks until the page is loaded). A **headless** run (or a host
 * without split support) uses a [BrowserHandleIntegration] over an offscreen
 * [ai.rever.boss.plugin.browser.BrowserHandle] on a throwaway
 * ([BrowserConfig.ephemeralProfile]) profile.
 */
class RunContext(
    val context: PluginContext,
    /** Reports the visible browser tab id this run opened, so the UI can close it
     *  before the next run (each run opens a fresh tab — see startRun). */
    private val onVisibleTab: (String?) -> Unit = {},
) {
    var session: BrowserIntegration? = null

    /** Releases the session on [close] (disposes a headless handle; visible tabs are
     *  left open for inspection and torn down by the host). */
    private var closer: (suspend () -> Unit)? = null

    /** Serializes browser-session access across parallel branches (the "fence"). */
    val sessionMutex = Mutex()

    /** Node outputs keyed by node title, for `$node["Title"]` expressions. Thread-safe. */
    val outputsByTitle = ConcurrentHashMap<String, List<Item>>()

    /**
     * Open the run's browser session. When [headless] is false (default), opens a
     * visible browser tab in a right split; if the host can't (no split support /
     * no tabs provider), falls back to an offscreen headless browser.
     */
    suspend fun openSession(headless: Boolean, log: (String) -> Unit = {}): BrowserIntegration {
        val service = context.browserService
            ?: throw ExecError("Browser is unavailable in this build (no browserService)")
        if (!service.isAvailable()) throw ExecError("Browser engine is not available")
        val opened = if (headless) {
            openHeadlessSession(service)
        } else {
            openVisibleSession(log) ?: run {
                log("Visible browser unavailable — running headless (offscreen)")
                openHeadlessSession(service)
            }
        }
        session = opened
        return opened
    }

    private suspend fun openHeadlessSession(service: BrowserService): BrowserIntegration {
        val handle = service.createBrowser(BrowserConfig().apply { ephemeralProfile = true })
            ?: throw ExecError("Failed to open a browser session")
        closer = { service.disposeBrowser(handle) }
        return BrowserHandleIntegration(handle)
    }

    /**
     * Open a visible browser tab in a right split and wait for it to become
     * drivable. Split-view/tab creation must happen on the UI thread, so the host
     * calls are marshalled to [Dispatchers.Main]. Returns null (with a logged
     * reason) if the host can't open one, so the caller can fall back to headless.
     */
    private suspend fun openVisibleSession(log: (String) -> Unit): BrowserIntegration? {
        val tabs = context.activeTabsProvider ?: run {
            log("No activeTabsProvider in this context")
            return null
        }
        val tabId = try {
            withContext(Dispatchers.Main) { tabs.createBrowserTabInRightSplit("about:blank", "Browser") }
        } catch (e: Exception) {
            log("Right-split open threw: ${e.message ?: e.toString()}")
            null
        }
        if (tabId == null) {
            log("createBrowserTabInRightSplit returned null (host has no split support?)")
            return null
        }
        log("Opened browser tab in right split ($tabId); waiting for it to attach…")
        // The browser view attaches asynchronously; poll briefly for its integration.
        var waited = 0
        while (waited < VISIBLE_TAB_TIMEOUT_MS) {
            val integration = withContext(Dispatchers.Main) { tabs.getBrowserIntegration(tabId) }
            if (integration != null) { onVisibleTab(tabId); return LoadAwaitingIntegration(integration) }
            delay(POLL_INTERVAL_MS.toLong()); waited += POLL_INTERVAL_MS
        }
        log("Browser tab never became drivable after ${VISIBLE_TAB_TIMEOUT_MS}ms")
        return null
    }

    fun requireSession(): BrowserIntegration =
        session ?: throw ExecError("No browser session — add an 'Open Browser' node upstream")

    suspend fun close() {
        runCatching { closer?.invoke() }
        closer = null
        session = null
    }

    private companion object {
        const val VISIBLE_TAB_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 100
    }
}

/** Reads a node's config field, interpolating `{{ }}` against the current item. */
class ConfigReader(
    private val config: JsonObject,
    private val item: Item,
    private val outputsByTitle: Map<String, List<Item>>
) {
    private fun raw(key: String): String = (config[key] as? JsonPrimitive)?.content ?: ""

    /** Field value with `{{ }}` resolved. */
    fun str(key: String, default: String = ""): String {
        val template = raw(key).ifEmpty { return default }
        return ExpressionEval.interpolate(template, item.json, outputsByTitle)
    }

    fun bool(key: String): Boolean = raw(key).equals("true", ignoreCase = true)

    /** [key] parsed as an integer, or [default] if absent/blank/non-numeric (a NUMBER field). */
    fun int(key: String, default: Int = 0): Int = str(key).trim().toIntOrNull() ?: default

    /** [key] parsed as a double, or [default] if absent/blank/non-numeric (a NUMBER field). */
    fun double(key: String, default: Double = 0.0): Double = str(key).trim().toDoubleOrNull() ?: default

    /**
     * The raw config value for [key] as a JSON element, or null if absent. Nested
     * objects/arrays are preserved exactly (unlike [str], which only reads scalars) —
     * this is how a JSON-typed field's structured content reaches an executor (P1).
     */
    fun element(key: String): JsonElement? = config[key]

    /**
     * [key] as JSON **text**, ready to feed to a tool `argsJson`:
     *  - a nested object/array is serialized to its JSON string,
     *  - a JSON string field is returned unquoted with `{{ }}` interpolated (so a
     *    JSON blob typed into a text/JSON field round-trips),
     *  - a scalar is returned as its literal text.
     * Returns [default] when the key is absent.
     */
    fun jsonText(key: String, default: String = ""): String {
        val el = config[key] ?: return default
        return when {
            el is JsonPrimitive && el.isString ->
                ExpressionEval.interpolate(el.content, item.json, outputsByTitle)
            el is JsonPrimitive -> el.content
            else -> el.toString()
        }
    }
}

/** Executes one node. Receives the current item's [cfg] and (for ONCE nodes) all [inputs]. */
fun interface NodeExecutor {
    suspend fun run(ctx: RunContext, cfg: ConfigReader, inputs: List<Item>, log: (String) -> Unit): List<Item>
}

/**
 * Maps each [NodeType] to its executor. `null` = not runnable yet (the engine
 * marks the node as an error). Browser nodes drive `ctx.session` via
 * [BrowserScripts]; HTTP/SET are pure data nodes.
 */
object NodeCatalog {

    fun executor(type: NodeType): NodeExecutor? = when (type) {
        NodeType.TRIGGER -> NodeExecutor { _, _, _, _ -> SEED_ITEMS }

        NodeType.OPEN_BROWSER -> NodeExecutor { ctx, cfg, inputs, log ->
            val session = ctx.openSession(cfg.bool("headless"), log)
            log(if (session is BrowserHandleIntegration) "Browser session ready (headless)" else "Browser session ready (visible)")
            val url = cfg.str("url")
            if (url.isNotBlank()) { ctx.requireSession().navigate(url); log("Navigated to $url") }
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.NAVIGATE -> NodeExecutor { ctx, cfg, inputs, log ->
            val url = cfg.str("url")
            if (url.isBlank()) throw ExecError("Navigate needs a URL")
            ctx.requireSession().navigate(url)
            log("Navigated to $url")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.CLICK -> NodeExecutor { ctx, cfg, inputs, log ->
            val sel = cfg.str("selector")
            val type = cfg.str("selectorType", "css")
            val session = ctx.requireSession()
            if (!session.awaitElement(type, sel)) throw ExecError("Click: no element matched '$sel'")
            val ok = session.executeJavaScript(BrowserScripts.clickScript(type, sel)) == true
            if (!ok) throw ExecError("Click: no element matched '$sel'")
            log("Clicked '$sel'")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.TYPE -> NodeExecutor { ctx, cfg, inputs, log ->
            val sel = cfg.str("selector")
            val type = cfg.str("selectorType", "css")
            val text = cfg.str("text")
            val session = ctx.requireSession()
            if (!session.awaitElement(type, sel)) throw ExecError("Type: no element matched '$sel'")
            val ok = session.executeJavaScript(BrowserScripts.inputScript(type, sel, text)) == true
            if (!ok) throw ExecError("Type: no element matched '$sel'")
            log("Typed into '$sel'")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.EXTRACT -> NodeExecutor { ctx, cfg, _, log ->
            val sel = cfg.str("selector")
            val type = cfg.str("selectorType", "css")
            val multiple = cfg.bool("multiple")
            val session = ctx.requireSession()
            // Best-effort wait so extraction doesn't race a still-loading page;
            // for `multiple` an empty result is still valid, so we proceed regardless.
            session.awaitElement(type, sel)
            val script = BrowserScripts.extractScript(
                selectorType = type,
                selector = sel,
                mode = cfg.str("mode", "text"),
                attr = cfg.str("attr"),
                multiple = multiple
            )
            val raw = session.executeJavaScript(script)
            val str = raw as? String ?: throw ExecError("Extract returned non-string: $raw")
            val obj = EXEC_JSON.parseToJsonElement(str).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw ExecError("Extract failed: ${obj["error"]?.jsonPrimitive?.content ?: "unknown"}")
            }
            val field = cfg.str("field", "value").ifEmpty { "value" }
            val value = obj["value"] ?: JsonNull
            val items = if (multiple && value is JsonArray) {
                value.map { Item(buildJsonObject { put(field, it) }) }
            } else {
                listOf(Item(buildJsonObject { put(field, value) }))
            }
            log("Extracted ${items.size} item(s) from '$sel'")
            items
        }

        NodeType.INJECT -> NodeExecutor { ctx, cfg, inputs, log ->
            val script = cfg.str("script")
            if (script.isBlank()) throw ExecError("Inject needs a script")
            ctx.requireSession().executeJavaScript(script)
            log("Ran injected script")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.HTTP -> NodeExecutor { _, cfg, _, log ->
            val url = cfg.str("url")
            if (url.isBlank()) throw ExecError("HTTP needs a URL")
            val method = cfg.str("method", "GET").ifEmpty { "GET" }.uppercase()
            val body = cfg.str("body")
            val headers = parseHeaders(cfg.str("headers"))
            log("$method $url")
            val resp = withContext(Dispatchers.IO) {
                val builder = HttpRequest.newBuilder(URI.create(url))
                headers.forEach { (k, v) -> builder.header(k, v) }
                val pub = if (method == "GET" || method == "DELETE") {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofString(body)
                }
                builder.method(method, pub)
                HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            val parsedBody = runCatching { EXEC_JSON.parseToJsonElement(resp.body()) }
                .getOrElse { JsonPrimitive(resp.body()) }
            log("→ ${resp.statusCode()}")
            listOf(Item(buildJsonObject {
                put("status", resp.statusCode())
                put("body", parsedBody)
            }))
        }

        NodeType.SET -> NodeExecutor { _, cfg, inputs, _ ->
            val current = inputs.firstOrNull()?.json ?: JsonObject(emptyMap())
            val assignments = runCatching {
                EXEC_JSON.parseToJsonElement(cfg.str("assignments").ifEmpty { "{}" }).jsonObject
            }.getOrElse { throw ExecError("Set: 'assignments' must be a JSON object") }
            val merged = buildJsonObject {
                current.forEach { (k, v) -> put(k, v) }
                // assignment values are templates resolved against the current item
                assignments.forEach { (k, v) ->
                    val template = (v as? JsonPrimitive)?.content ?: v.toString()
                    put(k, ExpressionEval.interpolate(template, current, emptyMap()))
                }
            }
            listOf(Item(merged))
        }

        // Not yet runnable in Phase 1.
        NodeType.CODE, NodeType.IF, NodeType.MERGE -> null
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            EXEC_JSON.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.content ?: v.toString()
            }
        }.getOrDefault(emptyMap())
    }
}

/** Convert a raw `executeJavaScript` value into a JSON element (boundary guard). */
fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
