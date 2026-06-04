package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.browser.BrowserConfig
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

/**
 * Runtime resources shared across a single run. Owns the browser session.
 *
 * The session is a [BrowserSession] over the host's browser stack. A **visible**
 * run (the `headless` flag off — the default) opens a real Fluck browser tab in a
 * right split via [ai.rever.boss.plugin.api.ActiveTabsProvider.createBrowserTabInRightSplit]
 * and drives it through [ai.rever.boss.plugin.api.BrowserIntegration], so the page
 * is watched live beside the canvas. A **headless** run (or a host without split
 * support) uses an offscreen [ai.rever.boss.plugin.browser.BrowserHandle] on a
 * throwaway ([BrowserConfig.ephemeralProfile]) profile.
 */
class RunContext(val context: PluginContext) {
    var session: BrowserSession? = null

    /** Serializes browser-session access across parallel branches (the "fence"). */
    val sessionMutex = Mutex()

    /** Node outputs keyed by node title, for `$node["Title"]` expressions. Thread-safe. */
    val outputsByTitle = ConcurrentHashMap<String, List<Item>>()

    /**
     * Open the run's browser session. When [headless] is false (default), opens a
     * visible browser tab in a right split; if the host can't (no split support /
     * no tabs provider), falls back to an offscreen headless browser.
     */
    suspend fun openSession(headless: Boolean): BrowserSession {
        val service = context.browserService
            ?: throw ExecError("Browser is unavailable in this build (no browserService)")
        if (!service.isAvailable()) throw ExecError("Browser engine is not available")
        val opened = (if (!headless) openVisibleSession() else null) ?: openHeadlessSession(service)
        session = opened
        return opened
    }

    private suspend fun openHeadlessSession(service: ai.rever.boss.plugin.browser.BrowserService): BrowserSession {
        val handle = service.createBrowser(BrowserConfig(ephemeralProfile = true))
            ?: throw ExecError("Failed to open a browser session")
        return HandleSession(handle, service)
    }

    /** Open a visible browser tab in a right split and wait for it to become drivable. */
    private suspend fun openVisibleSession(): BrowserSession? {
        val tabs = context.activeTabsProvider ?: return null
        val tabId = tabs.createBrowserTabInRightSplit("about:blank", "Flow Browser") ?: return null
        // The browser view attaches asynchronously; poll briefly for its integration.
        var waited = 0
        while (waited < VISIBLE_TAB_TIMEOUT_MS) {
            tabs.getBrowserIntegration(tabId)?.let { return TabSession(it, tabId) }
            delay(POLL_INTERVAL_MS.toLong()); waited += POLL_INTERVAL_MS
        }
        return tabs.getBrowserIntegration(tabId)?.let { TabSession(it, tabId) }
    }

    fun requireSession(): BrowserSession =
        session ?: throw ExecError("No browser session — add an 'Open Browser' node upstream")

    suspend fun close() {
        runCatching { session?.close() }
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
            ctx.openSession(cfg.bool("headless"))
            log("Opened browser session")
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
            val ok = ctx.requireSession()
                .executeJavaScript(BrowserScripts.clickScript(cfg.str("selectorType", "css"), sel)) == true
            if (!ok) throw ExecError("Click: no element matched '$sel'")
            log("Clicked '$sel'")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.TYPE -> NodeExecutor { ctx, cfg, inputs, log ->
            val sel = cfg.str("selector")
            val text = cfg.str("text")
            val ok = ctx.requireSession()
                .executeJavaScript(BrowserScripts.inputScript(cfg.str("selectorType", "css"), sel, text)) == true
            if (!ok) throw ExecError("Type: no element matched '$sel'")
            log("Typed into '$sel'")
            inputs.ifEmpty { SEED_ITEMS }
        }

        NodeType.EXTRACT -> NodeExecutor { ctx, cfg, _, log ->
            val sel = cfg.str("selector")
            val multiple = cfg.bool("multiple")
            val script = BrowserScripts.extractScript(
                selectorType = cfg.str("selectorType", "css"),
                selector = sel,
                mode = cfg.str("mode", "text"),
                attr = cfg.str("attr"),
                multiple = multiple
            )
            val raw = ctx.requireSession().executeJavaScript(script)
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
