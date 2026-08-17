package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.PluginContext
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
import java.time.Duration

/** Thrown by an executor to fail its node with a clear message. */
class ExecError(message: String) : Exception(message)

private val EXEC_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

// Element-wait tuning: a browser action polls for its target to appear before
// acting, so a node firing right after a click/navigation doesn't race the page
// still loading its content. Generic across every browser node.
private const val ELEMENT_WAIT_MS = 20_000
private const val ELEMENT_POLL_MS = 200
// Order matters: longest operators must be checked before their one-character prefixes.
private val CONDITION_OPERATORS = listOf("==", "!=", ">=", "<=", ">", "<")
private val CONDITION_NUMBER = Regex("""[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""")

/**
 * Polls (up to [timeoutMs]) for [selector] to match an element, returning whether
 * it appeared. Lets a node tolerate content that renders a beat after the previous
 * step finished — the single most common cause of "it only worked the second run"
 * flakiness, since [BrowserIntegration.executeJavaScript] is synchronous and can't
 * await the page itself.
 */
internal suspend fun BrowserIntegration.awaitElement(
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
 * Runtime resources shared across a single run. Draws its browser session from a
 * [SessionRegistry] (red-team F2/F12) rather than owning a single session inline:
 * the run holds one **default session** in that registry so native browser nodes
 * (Open/Navigate/Click/Type/Extract/Inject) behave exactly as before, while agents
 * and browser tool-nodes can open and drive additional sessions by id.
 *
 * The default session is a visible Fluck tab in a right split (wrapped by
 * [LoadAwaitingIntegration]) or, headless / when the host can't split, an offscreen
 * [BrowserHandleIntegration] — see [SessionRegistry].
 */
class RunContext(
    val context: PluginContext,
    /** Resolves logical secret names at execution time. Values are never copied into
     * node config, graph snapshots, or logs. */
    val secrets: SecretResolver = SecretResolver.fromSecrets(context),
    /** Reports the visible browser tab id this run opened, so the UI can close it
     *  before the next run (each run opens a fresh tab — see startRun). */
    onVisibleTab: (String?) -> Unit = {},
    /** The N-session store this run draws from. Defaults to a fresh registry so tests
     *  and the ad-hoc executor path work without threading one through. */
    val sessions: SessionRegistry = SessionRegistry(context, onVisibleTab),
    /** Nesting level of this run: 0 for a top-level run, +1 for each enclosing lanager.
     *  A [LanagerNode] uses it to enforce a cross-flow depth limit (plan §08). */
    val depth: Int = 0,
    /** Flow ids currently executing in this call stack, *including this run's own flow*.
     *  A [LanagerNode] refuses to launch a sub-flow already in here — cycle detection so
     *  nested lanagers can't recurse unbounded. */
    val ancestry: Set<String> = emptySet(),
) {
    /** The id of this run's default browser session in [sessions] (native nodes use it). */
    val defaultSessionId: String = sessions.newSessionId()

    /**
     * The default session's [BrowserIntegration], or null before it is opened. Kept as
     * a read-only view so callers observe whatever [SessionRegistry] holds for the run.
     */
    val session: BrowserIntegration? get() = sessions.get(defaultSessionId)

    /** Serializes access to the default browser session across parallel branches (the
     *  "fence"). Backed by [SessionRegistry]'s per-session mutex, so it is stable for
     *  the run even before the session is opened. */
    val sessionMutex: Mutex get() = sessions.mutexFor(defaultSessionId)

    /** Node outputs keyed by node title, for `$node["Title"]` expressions. Thread-safe. */
    val outputsByTitle = ConcurrentHashMap<String, List<Item>>()

    /**
     * Open the run's default browser session. When [headless] is false (default),
     * opens a visible browser tab in a right split; if the host can't (no split
     * support / no tabs provider), falls back to an offscreen headless browser.
     */
    suspend fun openSession(headless: Boolean, log: (String) -> Unit = {}): BrowserIntegration {
        sessions.open(headless, defaultSessionId, log)
        return requireSession()
    }

    fun requireSession(): BrowserIntegration =
        session ?: throw ExecError("No browser session — add an 'Open Browser' node upstream")

    /** Releases every session this run opened (headless handles disposed; visible tabs
     *  left open for inspection and torn down by the host). */
    suspend fun close() {
        sessions.closeAll()
    }
}

/**
 * Resolves `{{ $secret.name }}` references without passing them through
 * [ExpressionEval], whose deliberately small expression language treats unknown
 * roots as empty strings. A resolver instance lives for one HTTP execution, so a
 * repeated name is fetched once and discarded with that request.
 */
internal class SecretTemplateResolver(private val secrets: SecretResolver) {
    private val values = mutableMapOf<String, String>()

    suspend fun resolve(template: String, interpolate: (String) -> String): String {
        val malformed = SECRET_EXPRESSION.findAll(template)
            .firstOrNull { SECRET_REFERENCE.matchEntire(it.value) == null }
        if (malformed != null) {
            throw ExecError("HTTP secret reference is invalid — use {{ \$secret.name }}")
        }

        val out = StringBuilder(template.length)
        var cursor = 0
        for (match in SECRET_REFERENCE.findAll(template)) {
            out.append(interpolate(template.substring(cursor, match.range.first)))
            val name = match.groupValues[1]
            val value = values[name] ?: runCatching { secrets.get(name) }.getOrNull()
                ?: throw ExecError("HTTP secret '$name' was not found")
            values[name] = value
            out.append(value)
            cursor = match.range.last + 1
        }
        out.append(interpolate(template.substring(cursor)))
        return out.toString()
    }

    private companion object {
        val SECRET_REFERENCE = Regex("""\{\{\s*\${'$'}secret\.([A-Za-z0-9][A-Za-z0-9_.-]*)\s*}}""")
        val SECRET_EXPRESSION = Regex("""\{\{\s*\${'$'}secret\b.*?}}""")
    }
}

/** Reads a node's config field, interpolating `{{ }}` against the current item. */
class ConfigReader(
    private val config: JsonObject,
    private val item: Item,
    private val outputsByTitle: Map<String, List<Item>>
) {
    /** Raw scalar template, before current-item expressions are resolved. */
    internal fun raw(key: String): String = (config[key] as? JsonPrimitive)?.content ?: ""

    /** Resolve expressions in an arbitrary fragment against this reader's current item. */
    internal fun interpolate(template: String): String =
        ExpressionEval.interpolate(template, item.json, outputsByTitle)

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

    /** Parse [key] as JSON and recursively resolve `{{ }}` while preserving types. */
    fun jsonTemplate(key: String): JsonElement? {
        val configured = config[key] ?: return null
        val template = if (configured is JsonPrimitive && configured.isString) {
            if (configured.content.isBlank()) return null
            EXEC_JSON.parseToJsonElement(configured.content)
        } else {
            configured
        }
        return ExpressionEval.interpolateJson(template, item.json, outputsByTitle)
    }
}

/** Executes one node. Receives the current item's [cfg] and (for ONCE nodes) all [inputs]. */
fun interface NodeExecutor {
    suspend fun run(ctx: RunContext, cfg: ConfigReader, inputs: List<Item>, log: (String) -> Unit): NodeOutput
}

/**
 * Maps each built-in [NodeType] to its executor. Browser nodes drive
 * `ctx.session` via [BrowserScripts]; HTTP, Set, Code, If, and Merge are pure
 * data/control nodes.
 */
object NodeCatalog {

    fun executor(type: NodeType): NodeExecutor = when (type) {
        NodeType.TRIGGER -> NodeExecutor { _, _, _, _ -> NodeOutput.single(SEED_ITEMS) }

        NodeType.OPEN_BROWSER -> NodeExecutor { ctx, cfg, inputs, log ->
            val session = ctx.openSession(cfg.bool("headless"), log)
            log(if (session is BrowserHandleIntegration) "Browser session ready (headless)" else "Browser session ready (visible)")
            val url = cfg.str("url")
            if (url.isNotBlank()) { ctx.requireSession().navigate(url); log("Navigated to $url") }
            NodeOutput.single(inputs.ifEmpty { SEED_ITEMS })
        }

        NodeType.NAVIGATE -> NodeExecutor { ctx, cfg, inputs, log ->
            val url = cfg.str("url")
            if (url.isBlank()) throw ExecError("Navigate needs a URL")
            ctx.requireSession().navigate(url)
            log("Navigated to $url")
            NodeOutput.single(inputs.ifEmpty { SEED_ITEMS })
        }

        NodeType.CLICK -> NodeExecutor { ctx, cfg, inputs, log ->
            val sel = cfg.str("selector")
            val type = cfg.str("selectorType", "css")
            val session = ctx.requireSession()
            if (!session.awaitElement(type, sel)) throw ExecError("Click: no element matched '$sel'")
            val ok = session.executeJavaScript(BrowserScripts.clickScript(type, sel)) == true
            if (!ok) throw ExecError("Click: no element matched '$sel'")
            log("Clicked '$sel'")
            NodeOutput.single(inputs.ifEmpty { SEED_ITEMS })
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
            NodeOutput.single(inputs.ifEmpty { SEED_ITEMS })
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
            NodeOutput.single(items)
        }

        NodeType.INJECT -> NodeExecutor { ctx, cfg, inputs, log ->
            val script = cfg.str("script")
            if (script.isBlank()) throw ExecError("Inject needs a script")
            ctx.requireSession().executeJavaScript(script)
            log("Ran injected script")
            NodeOutput.single(inputs.ifEmpty { SEED_ITEMS })
        }

        NodeType.HTTP -> NodeExecutor { ctx, cfg, _, log ->
            val secretTemplates = SecretTemplateResolver(ctx.secrets)
            val url = secretTemplates.resolve(cfg.raw("url"), cfg::interpolate)
            if (url.isBlank()) throw ExecError("HTTP needs a URL")
            val method = cfg.str("method", "GET").ifEmpty { "GET" }.uppercase()
            val body = secretTemplates.resolve(cfg.raw("body"), cfg::interpolate)
            val headers = parseHeaders(secretTemplates.resolve(cfg.raw("headers"), cfg::interpolate))
            log("$method HTTP request")
            val resp = try {
                withContext(Dispatchers.IO) {
                    // Bounded so a hung endpoint can't stall the node (and, under MCP flow_run,
                    // the whole run) indefinitely — red-team S3.
                    val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    headers.forEach { (k, v) -> builder.header(k, v) }
                    val pub = if (method == "GET" || method == "DELETE") {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofString(body)
                    }
                    builder.method(method, pub)
                    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                }
            } catch (e: ExecError) {
                throw e
            } catch (_: Exception) {
                // URI/header/client exceptions may embed request values in their messages.
                throw ExecError("HTTP request failed before a response was received")
            }
            val parsedBody = runCatching { EXEC_JSON.parseToJsonElement(resp.body()) }
                .getOrElse { JsonPrimitive(resp.body()) }
            log("→ ${resp.statusCode()}")
            NodeOutput.single(listOf(Item(buildJsonObject {
                put("status", resp.statusCode())
                put("body", parsedBody)
            })))
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
            NodeOutput.single(listOf(Item(merged)))
        }

        NodeType.CODE -> NodeExecutor { _, cfg, _, log ->
            val rendered = runCatching { cfg.jsonTemplate("code") }
                .getOrElse { throw ExecError("Code: 'code' must be valid JSON: ${it.message}") }
                ?: throw ExecError("Code needs an output JSON template")
            val obj = rendered as? JsonObject
                ?: throw ExecError("Code: the JSON template must produce an object")
            log("Transformed item with JSON template")
            NodeOutput.single(listOf(Item(obj)))
        }

        NodeType.IF -> NodeExecutor { _, cfg, inputs, log ->
            val conditionTemplate = cfg.raw("condition")
            if (conditionTemplate.isBlank()) throw ExecError("If needs a condition")
            val matched = evaluateCondition(conditionTemplate, cfg::interpolate)
            log("Condition → ${if (matched) "true" else "false"}")
            NodeOutput.onPort(if (matched) 0 else 1, inputs)
        }

        NodeType.MERGE -> NodeExecutor { _, _, inputs, log ->
            log("Merged ${inputs.size} item(s)")
            NodeOutput.single(inputs)
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            EXEC_JSON.parseToJsonElement(raw).jsonObject.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.content ?: v.toString()
            }
        } catch (_: Exception) {
            // Do not echo raw JSON: it can already contain resolved credentials.
            throw ExecError("HTTP headers must be a valid JSON object")
        }
    }

    /**
     * Small, deterministic predicate language for If. The raw condition template is
     * split before [interpolate] resolves either operand, so newlines and operator-like
     * text in item data cannot alter the predicate grammar. Binary operators require
     * surrounding whitespace. Equality supports strings or numbers. Ordering is numeric
     * when both operands are numbers, lexical when both are text, false when either is
     * blank, and rejected when one is numeric and the other is text. A raw condition
     * without an operator — including an expression-only template — is tested only for
     * truthiness; blank/false/null/undefined/0/no/off are falsy.
     */
    internal fun evaluateCondition(
        raw: String,
        interpolate: (String) -> String,
    ): Boolean {
        val comparison = splitComparison(raw)
        if (comparison != null) {
            val left = interpolate(comparison.left).unquote()
            val op = comparison.operator
            val right = interpolate(comparison.right).unquote()
            val leftNumber = left.conditionNumberOrNull()
            val rightNumber = right.conditionNumberOrNull()
            return when (op) {
                "==" -> if (leftNumber != null && rightNumber != null) leftNumber == rightNumber else left == right
                "!=" -> if (leftNumber != null && rightNumber != null) leftNumber != rightNumber else left != right
                ">", ">=", "<", "<=" -> {
                    if (left.isBlank() || right.isBlank()) return false
                    if ((leftNumber == null) != (rightNumber == null)) {
                        throw ExecError(
                            "If: cannot order-compare '${left.preview()}' with '${right.preview()}' — " +
                                "normalize both values upstream or use ==/!=",
                        )
                    }
                    val order = if (leftNumber != null && rightNumber != null) {
                        leftNumber.compareTo(rightNumber)
                    } else {
                        left.compareTo(right)
                    }
                    when (op) {
                        ">" -> order > 0
                        ">=" -> order >= 0
                        "<" -> order < 0
                        else -> order <= 0
                    }
                }
                else -> false
            }
        }
        return when (val value = interpolate(raw).trim().unquote().lowercase()) {
            "", "false", "null", "undefined", "0", "no", "off" -> false
            else -> value.toDoubleOrNull()?.let { it != 0.0 } ?: true
        }
    }

    private data class Comparison(val left: String, val operator: String, val right: String)

    /** Find a whitespace-delimited operator outside `{{ }}` spans and quoted literals. */
    private fun splitComparison(raw: String): Comparison? {
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("{{", i)) {
                val end = raw.indexOf("}}", startIndex = i + 2)
                i = if (end >= 0) end + 2 else i + 2
                continue
            }
            val quote = raw[i].takeIf {
                (it == '\'' || it == '"') &&
                    (i == 0 || raw[i - 1].isWhitespace() || raw[i - 1] in "=!<>")
            }
            val closingQuote = quote?.let { findClosingQuote(raw, i + 1, it) } ?: -1
            if (closingQuote >= 0) {
                i = closingQuote + 1
                continue
            }
            val operator = CONDITION_OPERATORS.firstOrNull { raw.startsWith(it, i) }
            if (operator != null) {
                val after = i + operator.length
                val delimitedBefore = i == 0 || raw[i - 1].isWhitespace()
                val delimitedAfter = after == raw.length || raw[after].isWhitespace()
                if (delimitedBefore && delimitedAfter) {
                    val left = raw.substring(0, i).trim()
                    val right = raw.substring(after).trim()
                    if (left.isEmpty() || right.isEmpty()) {
                        throw ExecError("If: comparison '$raw' is missing an operand")
                    }
                    return Comparison(left, operator, right)
                }
            }
            i++
        }
        return null
    }

    private fun findClosingQuote(raw: String, start: Int, quote: Char): Int {
        var i = start
        while (i < raw.length) {
            if (raw[i] == '\\') {
                i += 2
            } else if (raw[i] == quote) {
                return i
            } else {
                i++
            }
        }
        return -1
    }

    private fun String.conditionNumberOrNull(): Double? =
        trim().takeIf { CONDITION_NUMBER.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun String.preview(): String = replace("\n", "\\n").take(40)

    private fun String.unquote(): String {
        val s = trim()
        return if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') ||
                (s.first() == '\'' && s.last() == '\''))) s.substring(1, s.length - 1) else s
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
