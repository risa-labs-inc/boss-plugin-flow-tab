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
fun toolNodeSpec(desc: ToolDescriptor, source: ToolSource): NodeSpec = NodeSpec(
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

/**
 * Keeps a [NodeRegistry]'s tool specs in sync with a [ToolSource]: registers a spec
 * per descriptor and unregisters ones that have disappeared, leaving built-ins and
 * other kinds untouched. Not thread-safe by itself; drive it from one scope.
 */
class ToolNodeSync(
    private val source: ToolSource,
    private val registry: NodeRegistry,
    private val buildSpec: (ToolDescriptor) -> NodeSpec = { toolNodeSpec(it, source) },
) {
    private var current: Set<String> = emptySet()

    fun apply(descriptors: List<ToolDescriptor>) {
        // Prepare every spec before mutating the registry. If one descriptor is bad,
        // current and the registry stay aligned for the next update.
        val specs = descriptors.map(buildSpec)
        val next = specs.map { it.id }.toSet()
        (current - next).forEach { registry.unregister(it) }
        specs.forEach(registry::register)
        current = next
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
        collectBossToolUpdates(reg.tools, sync::apply)
    }
}

/** Keep a bad host-tool update from permanently terminating the live registry collector. */
internal suspend fun collectBossToolUpdates(
    updates: Flow<List<RegisteredMcpTool>>,
    apply: (List<ToolDescriptor>) -> Unit,
    convert: (RegisteredMcpTool) -> ToolDescriptor = { it.toDescriptor() },
    reportFailure: (Exception) -> Unit = { failure ->
        println("[flow-tab] failed to synchronize host tools: ${toolSyncFailureMessage(failure)}")
    },
) {
    updates.collect { tools ->
        val descriptors = tools.mapNotNull { tool ->
            try {
                convert(tool)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportFailure(failure)
                null
            }
        }
        try {
            apply(descriptors)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportFailure(failure)
        }
    }
}

internal const val MAX_TOOL_SYNC_FAILURE_MESSAGE_LENGTH = 200

internal fun toolSyncFailureMessage(failure: Exception): String =
    (failure.message ?: "unknown error")
        .take(MAX_TOOL_SYNC_FAILURE_MESSAGE_LENGTH)
        .replace('\n', ' ')
        .replace('\r', ' ')
