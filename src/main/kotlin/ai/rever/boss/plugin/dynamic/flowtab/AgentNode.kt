package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Config keys + constants for the `agent` node. Per red-team F6, an agent's entire
 * configuration lives inside `node.config` (no sidecar map): the prompt (inline or a
 * [PromptRegistry] id), the tool allowlist, optional sampling temperature, and the
 * run budget. `__`-free keys so they render as ordinary inspector fields.
 */
object AgentNode {
    const val KIND = "agent"

    const val PROMPT_ID_KEY = "promptId"
    const val SYSTEM_KEY = "system"
    const val INPUT_KEY = "input"
    const val ALLOWLIST_KEY = "toolAllowlist"
    const val MODEL_KEY = "model"
    const val TEMPERATURE_KEY = "temperature"
    const val MAX_STEPS_KEY = "maxSteps"
    const val TIMEOUT_KEY = "timeoutMs"
    const val MAX_TOKENS_KEY = "maxTokens"
    const val OUTPUT_SCHEMA_KEY = "outputSchema"

    const val ACCENT = 0xFF6D4AFF

    /**
     * Keeps one Agent node, including its 5% hard-stop grace, comfortably below
     * the whole-flow watchdog so timeout state still has time to be published.
     */
    const val MAX_TIMEOUT_MS = FlowController.DEFAULT_RUN_TIMEOUT_MS - 3 * 60 * 1_000L

    val CONFIG_FIELDS: List<ConfigField> = listOf(
        ConfigField(PROMPT_ID_KEY, "Prompt id (optional)", FieldType.TEXT, placeholder = "a saved prompt's id"),
        ConfigField(SYSTEM_KEY, "System prompt (inline)", FieldType.TEXTAREA, placeholder = "You are…"),
        ConfigField(INPUT_KEY, "Input", FieldType.TEXTAREA, placeholder = "task, or {{ \$json.text }}"),
        ConfigField(ALLOWLIST_KEY, "Tool allowlist", FieldType.JSON, placeholder = """["tool_a","tool_b"]"""),
        ConfigField(
            OUTPUT_SCHEMA_KEY,
            "Output JSON Schema (optional)",
            FieldType.JSON,
            placeholder = """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}""",
        ),
        ConfigField(
            "outputSchemaInfo",
            "Structured output notes",
            FieldType.INFO,
            note = "The schema is fixed per node; templates are not resolved. Basic object/property/type/required/enum schemas " +
                "are the most portable across AI providers. The tool name flow_submit_output is reserved.",
        ),
        ConfigField(
            MODEL_KEY,
            "Model",
            FieldType.INFO,
            note = "Uses the active model selected in Settings → AI Providers. " +
                "Any legacy model value in JSON is retained for compatibility and ignored.",
        ),
        ConfigField(
            TEMPERATURE_KEY,
            "Temperature (optional; blank = provider setting)",
            FieldType.NUMBER,
            placeholder = "0–2 (range varies by provider); e.g. 0.2 or {{ \$json.temp }}",
        ),
        ConfigField(MAX_STEPS_KEY, "Max steps", FieldType.NUMBER, default = "8"),
        ConfigField(
            TIMEOUT_KEY,
            "Timeout (ms, max 720000)",
            FieldType.NUMBER,
            default = "120000",
            placeholder = "blank = 120000",
        ),
        ConfigField(
            MAX_TOKENS_KEY,
            "Max tokens",
            FieldType.NUMBER,
            placeholder = "unbounded if blank; otherwise must be greater than 0",
        ),
    )
}

/**
 * Parsed agent configuration for one run: the resolved [input], the tool [allowlist],
 * optional [outputSchema] and [temperature], the [budget], and the prompt source
 * ([promptId] or inline [system]).
 */
data class AgentSettings(
    val promptId: String?,
    val system: String,
    val input: String,
    val allowlist: Set<String>,
    val outputSchema: AgentOutputSchema?,
    val temperature: Float?,
    val budget: AgentBudget,
)

/**
 * Executor for an `agent` node. Resolves the system prompt (a [PromptRegistry] id if
 * given, else the inline field), builds an [AgentRuntime] from the per-node [providerFor]
 * and a [toolSourceFor] (its own tool lane — boss registry + its own browser session in
 * production), runs the bounded loop, and emits the final text as an [Item]. The prompt
 * and allowlist are read straight from `node.config` (F6).
 *
 * [providerFor]/[toolSourceFor] are injected so tests drive a [FakeProvider] + a fake
 * source, while production wires a [GatewayAgentProvider] + a merged, session-backed source.
 */
class AgentNodeExecutor(
    private val prompts: PromptRegistry?,
    private val providerFor: (AgentSettings) -> AgentProvider,
    private val toolSourceFor: (RunContext) -> ToolSource,
) : NodeExecutor {

    override suspend fun run(
        ctx: RunContext,
        cfg: ConfigReader,
        inputs: List<Item>,
        log: (String) -> Unit,
    ): NodeOutput {
        val settings = parse(cfg, inputs)
        val system = resolveSystem(settings)
        val provider = providerFor(settings)
        val source = toolSourceFor(ctx)
        val result = AgentRuntime(provider, source, settings.budget)
            .run(
                system = system,
                input = settings.input,
                allowlist = settings.allowlist,
                outputSchema = settings.outputSchema,
                log = log,
            )
        if (result.stopReason == StopReason.TIMEOUT) {
            if (result.finalText.isNotBlank()) {
                log("agent partial text withheld (${result.finalText.length} chars)")
            }
            throw ExecError(
                "Agent stopped: TIMEOUT after ${result.steps} completed step(s), " +
                    "${result.toolCalls} attempted tool call(s); timeout was ${settings.budget.timeoutMs}ms" +
                    if (settings.outputSchema == null) "" else "; no valid structured output was produced",
            )
        }
        if (settings.outputSchema != null) {
            val structured = result.structuredOutput
            if (structured == null) {
                if (result.finalText.isNotBlank()) {
                    log("agent non-structured final text withheld (${result.finalText.length} chars)")
                }
                throw ExecError(
                    "Agent stopped: ${result.stopReason} after ${result.steps} completed step(s), " +
                        "${result.toolCalls} attempted tool call(s); no valid structured output was produced",
                )
            }
            return NodeOutput.single(listOf(Item(structured)))
        }
        return NodeOutput.single(listOf(
            Item(buildJsonObject {
                put("text", result.finalText)
                put("stopReason", result.stopReason.name)
                put("steps", result.steps)
                put("toolCalls", result.toolCalls)
            })
        ))
    }

    private suspend fun resolveSystem(settings: AgentSettings): String {
        val fromId = settings.promptId?.takeIf { it.isNotBlank() }
            ?.let { prompts?.get(it) }
            ?.let { composeSystemPrompt(it) }
        return fromId ?: settings.system
    }

    private fun parse(cfg: ConfigReader, inputs: List<Item>): AgentSettings {
        val inlineInput = cfg.str(AgentNode.INPUT_KEY)
        val input = inlineInput.ifBlank { inputs.firstOrNull()?.json?.toString() ?: "" }
        val maxSteps = cfg.positiveInt(AgentNode.MAX_STEPS_KEY, 8, "Agent max steps")
        val timeoutMs = cfg.positiveLong(AgentNode.TIMEOUT_KEY, 120_000, "Agent timeout")
            .coerceAtMost(AgentNode.MAX_TIMEOUT_MS)
        val maxTokens = cfg.positiveInt(AgentNode.MAX_TOKENS_KEY, Int.MAX_VALUE, "Agent max tokens")
        return AgentSettings(
            promptId = cfg.str(AgentNode.PROMPT_ID_KEY).ifBlank { null },
            system = cfg.str(AgentNode.SYSTEM_KEY),
            input = input,
            allowlist = parseAllowlist(cfg),
            outputSchema = parseOutputSchema(cfg),
            temperature = cfg.optionalFiniteFloat(AgentNode.TEMPERATURE_KEY, "Agent temperature"),
            budget = AgentBudget(
                maxSteps = maxSteps,
                timeoutMs = timeoutMs,
                maxTokens = maxTokens,
            ),
        )
    }

    private fun parseOutputSchema(cfg: ConfigReader): AgentOutputSchema? {
        val configured = cfg.element(AgentNode.OUTPUT_SCHEMA_KEY) ?: return null
        val raw = when (configured) {
            JsonNull -> return null
            is JsonPrimitive -> configured.content
            is JsonObject -> configured.toString()
            else -> throw ExecError("Agent output schema (outputSchema) must be a JSON object")
        }.trim()
        return raw.takeIf { it.isNotEmpty() }?.let(AgentStructuredOutput::parse)
    }

    private fun ConfigReader.positiveLong(key: String, default: Long, label: String): Long {
        val raw = str(key).trim()
        if (raw.isEmpty()) return default
        val value = raw.toLongOrNull()
            ?: throw ExecError("$label ($key) must be a whole number greater than 0")
        if (value <= 0) throw ExecError("$label ($key) must be greater than 0")
        return value
    }

    private fun ConfigReader.positiveInt(key: String, default: Int, label: String): Int {
        val value = positiveLong(key, default.toLong(), label)
        if (value > Int.MAX_VALUE) throw ExecError("$label ($key) is too large")
        return value.toInt()
    }

    private fun ConfigReader.optionalFiniteFloat(key: String, label: String): Float? {
        val raw = str(key).trim()
        if (raw.isEmpty()) return null
        val value = raw.toFloatOrNull()
            ?: throw ExecError("$label ($key) must be a finite number")
        if (!value.isFinite()) throw ExecError("$label ($key) must be a finite number")
        if (value < 0f) throw ExecError("$label ($key) must be zero or greater")
        if (value > 2f) throw ExecError("$label ($key) must be 2 or less")
        return value
    }

    /**
     * Allowlist as a JSON array of names/tool kind-ids, or a comma/newline-separated
     * string. The inspector stores JSON editor content in a JsonPrimitive, while raw
     * flow JSON may contain a real JsonArray, so both representations are intentional.
     */
    private fun parseAllowlist(cfg: ConfigReader): Set<String> {
        val entries = when (val configured = cfg.element(AgentNode.ALLOWLIST_KEY)) {
            null, JsonNull -> emptyList()
            is JsonArray -> allowlistEntries(configured)
            is JsonPrimitive -> {
                if (!configured.isString) {
                    throw ExecError(
                        "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) must be a JSON array or comma-separated names",
                    )
                }
                val raw = configured.content.trim()
                when {
                    raw.isEmpty() -> emptyList()
                    raw.startsWith("[") || raw.startsWith("{") -> {
                        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrElse { error ->
                            throw ExecError(
                                "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) must be a valid JSON array: " +
                                    safeAllowlistError(error),
                            )
                        }
                        val array = parsed as? JsonArray
                            ?: throw ExecError(
                                "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) must be a JSON array of strings",
                            )
                        allowlistEntries(array)
                    }
                    else -> raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
                }
            }
            else -> throw ExecError(
                "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) must be a JSON array or comma-separated names",
            )
        }
        return entries.toCollection(LinkedHashSet())
    }

    private fun allowlistEntries(array: JsonArray): List<String> = array.mapIndexed { index, element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ExecError(
                "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) entry ${index + 1} must be a string",
            )
        }
        primitive.content.trim().takeIf(String::isNotEmpty)
            ?: throw ExecError(
                "Agent tool allowlist (${AgentNode.ALLOWLIST_KEY}) entry ${index + 1} must not be blank",
            )
    }

    /** Parser diagnostics may include source excerpts; keep node errors bounded and single-line. */
    private fun safeAllowlistError(error: Throwable): String =
        (error.message ?: "invalid JSON")
            .take(MAX_ALLOWLIST_ERROR_DETAIL_CHARS)
            .map { if (it.isISOControl()) ' ' else it }
            .joinToString("")

    private companion object {
        const val MAX_ALLOWLIST_ERROR_DETAIL_CHARS = 240
    }
}

/**
 * Build the `agent` [NodeSpec] with injected dependencies (used by tests and by the
 * production wiring below). [providerFor] resolves an [AgentProvider] from the parsed
 * settings; [toolSourceFor] yields the tool lane for a run (bound to its [RunContext]
 * so an agent can drive its own browser session).
 */
fun agentNodeSpec(
    prompts: PromptRegistry?,
    providerFor: (AgentSettings) -> AgentProvider,
    toolSourceFor: (RunContext) -> ToolSource,
): NodeSpec = NodeSpec(
    id = AgentNode.KIND,
    label = "Agent",
    inputs = 1,
    outputs = 1,
    accent = AgentNode.ACCENT,
    description = "Run an LLM agent: a bounded tool-loop over an allowlist of tools.",
    runMode = RunMode.PER_ITEM,
    // Must stay false: browser tools acquire the default session's non-reentrant
    // mutex per call. Holding it around the whole Agent node would deadlock them.
    usesSession = false,
    hasMetaRow = false,
    configFields = AgentNode.CONFIG_FIELDS,
    executor = AgentNodeExecutor(prompts, providerFor, toolSourceFor),
)

/**
 * Production `agent` spec: an [GatewayAgentProvider] keyed from the shared AI provider config
 * (Settings → AI Providers, owned by the secret-manager plugin), over a [MergedToolSource] of
 * the host registry ([BossRegistryToolSource]) plus a per-run browser lane
 * ([FlowBrowserToolSource] on the run's own [SessionRegistry]). The [AgentRuntime]'s allowlist
 * then narrows that merged set to what the node permits.
 */
fun defaultAgentNodeSpec(
    context: PluginContext,
    prompts: PromptRegistry?,
    // Optional external-MCP lane (P7). When supplied (and its feature flag is on so it has
    // connected servers), the agent can also call `ext:<server>/*` tools. Null keeps the
    // agent on the in-app tool set only.
    external: ExternalMcpManager? = null,
): NodeSpec {
    return agentNodeSpec(
        prompts = prompts,
        // A fresh provider per run, and the gateway resolved per call inside it: neither the
        // gateway nor the active provider exposes a change signal, so a provider changed in
        // Settings has to be picked up by the next run rather than needing the tab reopened.
        // A per-run instance also keeps each run's replayed tool turn to itself.
        providerFor = { settings ->
            GatewayAgentProvider(
                // Wrapped, like every other getPluginAPI call in this plugin: if a host
                // without the gateway throws rather than returning null, the carefully
                // worded NO_GATEWAY_MESSAGE never runs and the node fails with whatever the
                // host threw instead.
                gateway = { runCatching { context.getPluginAPI(AiGatewayAPI::class.java) }.getOrNull() },
                // Shows the dialog naming whichever thing is missing and opens the fix.
                promptAiFix = { feature ->
                    ai.rever.boss.plugin.api.AiAvailability.promptToFix(context, feature)
                    Unit
                },
                temperature = settings.temperature,
                // AgentRuntime applies the decreasing remaining whole-run budget to each
                // turn. Forwarding the watchdog-derived cap relaxes the gateway's shorter
                // default; the runtime remains the authoritative timeout and fires first.
                requestTimeoutMs = settings.budget.timeoutMs,
            )
        },
        toolSourceFor = { ctx -> defaultAgentToolSource(context, external, ctx) },
    )
}

/** Production Agent tool wiring, kept as a testable seam so default-session binding cannot regress. */
internal fun defaultAgentToolSource(
    context: PluginContext,
    external: ExternalMcpManager?,
    ctx: RunContext,
): ToolSource {
    val lanes = buildList {
        context.mcpToolRegistry?.let { add(BossRegistryToolSource(it)) }
        add(FlowBrowserToolSource(ctx.sessions, ctx.defaultSessionId))
        external?.let { add(it) }
    }
    return MergedToolSource(lanes)
}

/**
 * Fan-in over several [ToolSource]s: [list] concatenates (first-wins on a name clash);
 * [invoke] routes by matching the tool name to the owning source. Lets an agent see one
 * flat tool set spanning the host registry and the browser lane.
 */
class MergedToolSource(private val sources: List<ToolSource>) : ToolSource {
    /** name -> owning source, built during [list] so [invoke] is a lookup, not a re-scan
     *  of every source (red-team S6: with external MCP that was a network call per invoke). */
    private var routes: Map<String, ToolSource> = emptyMap()

    override suspend fun list(): List<ToolDescriptor> {
        val seen = HashSet<String>()
        val descriptors = mutableListOf<ToolDescriptor>()
        val table = LinkedHashMap<String, ToolSource>()
        for (s in sources) for (d in s.list()) if (seen.add(d.ref.name)) {
            descriptors += d
            table[d.ref.name] = s
        }
        routes = table
        return descriptors
    }

    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        // Refresh the route table once on a miss (a tool may have appeared since the last list).
        val source = routes[name] ?: run { list(); routes[name] }
        return source?.invoke(name, argsJson)
            ?: ToolResult("no tool '$name' in any source", isError = true)
    }
}
