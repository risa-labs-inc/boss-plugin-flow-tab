package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared constants + helpers for tool-backed nodes. */
object ToolNode {
    /** Config key caching the tool's kind-id at author time, so a saved node keeps
     *  its identity even when the backing tool is absent at load (F4). `__`-prefixed
     *  so it never collides with a schema property. */
    const val REF_KEY = "__toolRef"

    /** Config key caching the tool's `inputSchema` snapshot at author time, so a saved
     *  node still renders its fields (and marshals args) when the tool is absent (F4). */
    const val SCHEMA_KEY = "__schema"

    /** Accent for tool nodes in the palette + canvas (distinct from the built-ins). */
    const val ACCENT = 0xFF7E57C2

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Assemble the `argsJson` for a tool call from the node's [cfg], driven by its
     * [schema]. Scalar fields become JSON scalars (numbers/bools unquoted); JSON
     * fields are parsed and embedded structurally; the single raw-JSON fallback
     * field passes its body through verbatim (nested/array/loose schemas — F3).
     * Empty fields are omitted so a tool sees only what the user supplied.
     */
    fun buildArgs(schema: String, cfg: ConfigReader): String {
        val fields = JsonSchemaToConfig.convert(schema)
        val rawFallback = fields.singleOrNull()?.takeIf { it.key == JsonSchemaToConfig.RAW_ARGS_KEY }
        if (rawFallback != null) {
            return cfg.jsonText(JsonSchemaToConfig.RAW_ARGS_KEY, "").trim().ifEmpty { "{}" }
        }
        val obj = buildJsonObject {
            for (f in fields) {
                when (f.type) {
                    FieldType.BOOL -> {
                        val raw = cfg.str(f.key)
                        if (raw.isNotEmpty()) put(f.key, cfg.bool(f.key))
                    }
                    FieldType.NUMBER -> {
                        val raw = cfg.str(f.key).trim()
                        if (raw.isNotEmpty()) {
                            val n = raw.toLongOrNull() ?: raw.toDoubleOrNull()
                            if (n != null) put(f.key, n) else put(f.key, raw)
                        }
                    }
                    FieldType.JSON -> {
                        val text = cfg.jsonText(f.key, "").trim()
                        if (text.isNotEmpty()) {
                            val el = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
                            if (el != null) put(f.key, el) else put(f.key, text)
                        }
                    }
                    else -> {
                        val s = cfg.str(f.key)
                        if (s.isNotEmpty()) put(f.key, s)
                    }
                }
            }
        }
        return obj.toString()
    }

    /** Wrap a tool's result text as an [Item]: a JSON object flows through as-is, any
     *  other JSON is wrapped under `value`, and non-JSON text under `text`. */
    fun resultItem(text: String): Item {
        val parsed = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
        return when (parsed) {
            is JsonObject -> Item(parsed)
            null -> Item(buildJsonObject { put("text", text) })
            else -> Item(buildJsonObject { put("value", parsed) })
        }
    }
}

/**
 * Executor for a tool-backed node. Marshals the node config into `argsJson`
 * (schema-driven), invokes the [source], throws [ExecError] on a tool error so the
 * DAG's failure model handles it (F8), and emits the result as an [Item].
 *
 * The schema is read from the node's cached [ToolNode.SCHEMA_KEY] when present
 * (author-time snapshot), else the [fallbackSchema] captured at spec-generation.
 */
class ToolNodeExecutor(
    private val source: ToolSource,
    private val ref: ToolRef,
    private val fallbackSchema: String,
) : NodeExecutor {
    override suspend fun run(
        ctx: RunContext,
        cfg: ConfigReader,
        inputs: List<Item>,
        log: (String) -> Unit,
    ): NodeOutput {
        val schema = (cfg.element(ToolNode.SCHEMA_KEY) as? JsonPrimitive)?.content ?: fallbackSchema
        val argsJson = ToolNode.buildArgs(schema, cfg)
        log("→ ${ref.name} $argsJson")
        val result = source.invoke(ref.name, argsJson)
        if (result.isError) throw ExecError("Tool '${ref.name}' failed: ${result.text}")
        log("← ${ref.name} ok")
        return NodeOutput.single(listOf(ToolNode.resultItem(result.text)))
    }
}

/**
 * Build a runnable [NodeSpec] for a tool [desc], backed by [source]. Config fields
 * are derived from the tool's `inputSchema`; the ref + schema snapshot are seeded
 * into [NodeSpec.defaultConfig] so a spawned node keeps them for the absent-tool
 * case (F4).
 */
fun toolNodeSpec(desc: ToolDescriptor, source: ToolSource): NodeSpec {
    require(desc.ref.name.isNotBlank()) { "Tool reference name must not be blank" }
    require(desc.name.isNotBlank()) { "Tool display name must not be blank" }
    return NodeSpec(
        id = desc.ref.kindId,
        label = desc.name,
        inputs = 1,
        outputs = 1,
        accent = ToolNode.ACCENT,
        description = desc.description.ifBlank { "Tool: ${desc.name}" },
        runMode = RunMode.PER_ITEM,
        configFields = JsonSchemaToConfig.convert(desc.inputSchema),
        executor = ToolNodeExecutor(source, desc.ref, desc.inputSchema),
        defaultConfig = buildJsonObject {
            put(ToolNode.REF_KEY, desc.ref.kindId)
            put(ToolNode.SCHEMA_KEY, desc.inputSchema)
        },
    )
}

/**
 * Keeps a [NodeRegistry]'s tool specs in sync with a [ToolSource]: registers a spec
 * per descriptor and unregisters ones that have disappeared, leaving built-ins and
 * other kinds untouched. A malformed descriptor retains its last-known-good spec; an
 * all-malformed update retains the full prior set until a valid emission can distinguish
 * stale tools from a transient provider failure. A valid empty emission remains authoritative
 * and removes every previously synchronized tool. Not thread-safe; drive it from one scope.
 */
internal class ToolNodeSync(
    private val source: ToolSource,
    private val registry: NodeRegistry,
    private val reportFailure: (String) -> Unit = ::println,
) {
    private data class Failure(val toolId: String, val exception: Exception)

    private var current: Set<String> = emptySet()
    private var previousFailureKeys: Set<String> = emptySet()

    fun apply(descriptors: List<ToolDescriptor>) {
        val failures = mutableListOf<Failure>()
        val specs = descriptors.mapNotNull { descriptor ->
            try {
                toolNodeSpec(descriptor, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += Failure(descriptor.ref.kindId, failure)
                null
            }
        }
        reportNewFailures(failures)

        // If the entire non-empty update is malformed, treat it as transient and retain
        // the complete last-known-good registry. With partial success, preserve only the
        // previous entries whose incoming descriptors failed while applying healthy peers.
        if (specs.isEmpty() && failures.isNotEmpty()) return
        val failedIds = failures.mapTo(mutableSetOf()) { it.toolId }
        val next = specs.mapTo(mutableSetOf()) { it.id }.apply {
            addAll(current.intersect(failedIds))
        }
        specs.forEach(registry::register)
        (current - next).forEach(registry::unregister)
        current = next
    }

    private fun reportNewFailures(failures: List<Failure>) {
        val keyed = failures.associateBy { failure ->
            val id = toolSyncIdentifier(failure.toolId)
            "$id:${failure.exception.javaClass.name}:${toolSyncFailureMessage(failure.exception)}"
        }
        keyed
            .filterKeys { it !in previousFailureKeys }
            .values
            .forEach { failure ->
                reportFailure(
                    "[flow-tab] failed to synchronize tool '${toolSyncIdentifier(failure.toolId)}': " +
                        toolSyncFailureMessage(failure.exception),
                )
            }
        previousFailureKeys = keyed.keys.toSet()
    }
}

/**
 * Wire the host registry's tools into [registry] as live nodes: collect the
 * RBAC-filtered `tools` flow on [scope] and re-derive specs on every change. Returns
 * the collecting [Job], or null when the host exposes no [McpToolRegistry] (older/
 * sandboxed hosts) — the caller degrades to built-ins only.
 */
fun syncBossTools(context: PluginContext, registry: NodeRegistry, scope: CoroutineScope): Job? {
    val reg = context.mcpToolRegistry ?: return null
    val source = BossRegistryToolSource(reg)
    val sync = ToolNodeSync(source, registry)
    return scope.launch {
        collectBossToolUpdates(reg.tools, sync)
    }
}

/** Keep a bad host-tool update from permanently terminating the live registry collector. */
internal suspend fun collectBossToolUpdates(
    updates: Flow<List<RegisteredMcpTool>>,
    sync: ToolNodeSync,
) {
    updates.collect { tools ->
        try {
            sync.apply(tools.map { it.toDescriptor() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            println("[flow-tab] failed to synchronize host tools: ${toolSyncFailureMessage(failure)}")
        }
    }
}

internal const val MAX_TOOL_SYNC_FAILURE_MESSAGE_LENGTH = 200
internal const val MAX_TOOL_SYNC_IDENTIFIER_LENGTH = 100

internal fun toolSyncIdentifier(identifier: String): String =
    sanitizeToolSyncText(identifier, MAX_TOOL_SYNC_IDENTIFIER_LENGTH)

internal fun toolSyncFailureMessage(failure: Exception): String =
    sanitizeToolSyncText(failure.message ?: "unknown error", MAX_TOOL_SYNC_FAILURE_MESSAGE_LENGTH)

private fun sanitizeToolSyncText(value: String, maxLength: Int): String =
    value
        .take(maxLength)
        .map { char ->
            if (char.isISOControl() || char == '\u2028' || char == '\u2029') ' ' else char
        }
        .joinToString("")
