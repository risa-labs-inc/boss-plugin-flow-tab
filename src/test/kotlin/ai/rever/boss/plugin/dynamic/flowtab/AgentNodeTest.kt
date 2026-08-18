package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P5: the `agent` node — config lives inside `node.config` (F6). Pins that an agent
 * node runs the [AgentRuntime] with a system prompt (inline or from the [PromptRegistry]),
 * a tool-allowlist drawn from config, and emits its final text as an [Item]; and that
 * the allowlist genuinely gates which tools the run can reach.
 */
class AgentNodeTest {

    private class RecordingSource(private val names: List<String>) : ToolSource {
        val invoked = ConcurrentHashMap.newKeySet<String>()
        override suspend fun list() = names.map { ToolDescriptor(ToolRef(ToolScope.BOSS, it), it, "d", "{}") }
        override suspend fun invoke(name: String, argsJson: String): ToolResult {
            invoked.add(name); return ToolResult("""{"ok":true}""", false)
        }
    }

    private fun minimalContext(): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(Dispatchers.Default)
        override val mcpToolRegistry: McpToolRegistry? = null
    }

    private fun runFlow(reg: NodeRegistry, nodes: List<PlanNode>, edges: List<EdgeModel>): Map<String, NodeRun> {
        val states = ConcurrentHashMap<String, NodeRun>()
        runBlocking(Dispatchers.Default) { FlowExecutor(minimalContext(), reg).run(nodes, edges) { id, r -> states[id] = r } }
        return states
    }

    @Test
    fun `agent node runs the loop and emits its final text`() {
        val source = RecordingSource(listOf("lookup"))
        val provider = FakeProvider.scripted(
            AssistantTurn(toolCalls = listOf(ToolCall("1", "lookup", "{}"))),
            AssistantTurn(text = "final answer"),
        )
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        assertFalse(spec.usesSession)
        val reg = builtinNodeRegistry().also { it.register(spec) }

        val cfg = buildJsonObject {
            put("system", "be helpful")
            put("input", "do the thing")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("lookup")) })
        }
        val states = runFlow(
            reg,
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())), PlanNode("a", "agent", "Agent", cfg)),
            listOf(EdgeModel("e", "t", 0, "a", 0)),
        )
        assertEquals(RunStatus.SUCCESS, states["a"]!!.status)
        assertEquals("final answer", states["a"]!!.output.single().json["text"]!!.jsonPrimitive.content)
        assertTrue(source.invoked.contains("lookup"))
    }

    @Test
    fun `structured mode advertises the schema tool and emits only its validated object`() {
        val source = RecordingSource(listOf("lookup"))
        var seenSystem = ""
        var seenTools = emptyList<ToolDescriptor>()
        val provider = FakeProvider { _, system, _, tools ->
            seenSystem = system
            seenTools = tools
            AssistantTurn(
                text = "this text must not become output",
                toolCalls = listOf(
                    ToolCall("out-1", AgentStructuredOutput.TOOL_NAME, """{"selector":"#main","found":true}"""),
                ),
            )
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("selector", buildJsonObject { put("type", "string") })
                put("found", buildJsonObject { put("type", "boolean") })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("selector"))
                add(JsonPrimitive("found"))
            })
            put("additionalProperties", false)
        }
        val cfg = buildJsonObject {
            put(AgentNode.SYSTEM_KEY, "Use the page evidence.")
            put(AgentNode.INPUT_KEY, "locate the section")
            put(AgentNode.OUTPUT_SCHEMA_KEY, schema)
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray { add(JsonPrimitive("lookup")) })
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        val output = state.output.single().json
        assertEquals("#main", output.getValue("selector").jsonPrimitive.content)
        assertTrue(output.getValue("found").jsonPrimitive.boolean)
        assertEquals(setOf("selector", "found"), output.keys)
        assertTrue(AgentStructuredOutput.SYSTEM_INSTRUCTION in seenSystem)
        assertEquals(schema.toString(), seenTools.single { it.name == AgentStructuredOutput.TOOL_NAME }.inputSchema)
        assertTrue(source.invoked.isEmpty())
        assertTrue(state.logs.contains("agent structured output submission: accepted"))
        assertTrue(state.logs.contains("agent non-structured text withheld (32 chars)"))
        assertFalse(state.logs.joinToString("\n").contains("#main"))
        assertFalse(state.logs.joinToString("\n").contains("this text must not become output"))
    }

    @Test
    fun `invalid structured submission is returned to the model for a corrected retry`() {
        var sawValidationFeedback = false
        val provider = FakeProvider { step, _, messages, _ ->
            if (step == 0) {
                AssistantTurn(
                    toolCalls = listOf(
                        ToolCall("bad", AgentStructuredOutput.TOOL_NAME, """{"count":"secret-invalid-value"}"""),
                    ),
                )
            } else {
                val feedback = (messages.last() as ToolResultsMsg).outcomes.single()
                sawValidationFeedback = feedback.isError && feedback.content == "$.count must be integer"
                AssistantTurn(
                    toolCalls = listOf(ToolCall("good", AgentStructuredOutput.TOOL_NAME, """{"count":2}""")),
                )
            }
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "count")
            put(
                AgentNode.OUTPUT_SCHEMA_KEY,
                """{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}""",
            )
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertEquals("2", state.output.single().json.getValue("count").jsonPrimitive.content)
        assertTrue(sawValidationFeedback)
        assertTrue(state.logs.any { it == "agent structured output submission: rejected ($.count must be integer)" })
        assertTrue(state.logs.contains("agent structured output submission: accepted"))
        assertFalse(state.logs.joinToString("\n").contains("secret-invalid-value"))
    }

    @Test
    fun `mixed submission is rejected while an allowlisted tool runs without spending a correction`() {
        val source = RecordingSource(listOf("write"))
        var sawMixedOutcomes = false
        val provider = FakeProvider { step, _, messages, _ ->
            when (step) {
                0 -> AssistantTurn(
                    toolCalls = listOf(
                        ToolCall("out", AgentStructuredOutput.TOOL_NAME, """{"ok":true}"""),
                        ToolCall("write", "write", """{"value":"run-once"}"""),
                    ),
                )
                1 -> {
                    val feedback = (messages.last() as ToolResultsMsg).outcomes
                    sawMixedOutcomes = feedback.size == 2 && feedback.single { it.id == "out" }.isError &&
                        !feedback.single { it.id == "write" }.isError
                    AssistantTurn(
                        toolCalls = listOf(ToolCall("invalid-1", AgentStructuredOutput.TOOL_NAME, """{"ok":"yes"}""")),
                    )
                }
                2 -> AssistantTurn(
                    toolCalls = listOf(ToolCall("invalid-2", AgentStructuredOutput.TOOL_NAME, """{"ok":"still"}""")),
                )
                else -> AssistantTurn(
                    toolCalls = listOf(ToolCall("corrected", AgentStructuredOutput.TOOL_NAME, """{"ok":true}""")),
                )
            }
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "finish")
            put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"object","properties":{"ok":{"type":"boolean"}}}""")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray { add(JsonPrimitive("write")) })
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertTrue(sawMixedOutcomes)
        assertTrue(source.invoked.contains("write"))
        assertFalse(state.logs.joinToString("\n").contains("run-once"))
    }

    @Test
    fun `reserved output tool conflicts only when the real tool is allowlisted`() {
        val providerCalls = AtomicInteger()
        val source = RecordingSource(listOf(AgentStructuredOutput.TOOL_NAME))
        val provider = FakeProvider { _, _, _, _ ->
            providerCalls.incrementAndGet()
            AssistantTurn(
                toolCalls = listOf(ToolCall("out", AgentStructuredOutput.TOOL_NAME, """{"ok":true}""")),
            )
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val schema = """{"type":"object","properties":{"ok":{"type":"boolean"}}}"""

        fun run(allowReserved: Boolean): NodeRun {
            val config = buildJsonObject {
                put(AgentNode.OUTPUT_SCHEMA_KEY, schema)
                if (allowReserved) {
                    put(
                        AgentNode.ALLOWLIST_KEY,
                        buildJsonArray { add(JsonPrimitive(AgentStructuredOutput.TOOL_NAME)) },
                    )
                }
            }
            val registry = builtinNodeRegistry().also { it.register(spec) }
            return runFlow(
                registry,
                listOf(PlanNode("a", AgentNode.KIND, "Agent", config)),
                emptyList(),
            ).getValue("a")
        }

        val conflict = run(allowReserved = true)
        assertEquals(RunStatus.ERROR, conflict.status)
        assertContains(conflict.error.orEmpty(), "conflicts with an allowlisted tool")
        assertEquals(0, providerCalls.get())

        val notAllowlisted = run(allowReserved = false)
        assertEquals(RunStatus.SUCCESS, notAllowlisted.status)
        assertEquals(1, providerCalls.get())
        assertTrue(source.invoked.isEmpty())
    }

    @Test
    fun `structured mode can use a real tool before submitting its result`() {
        val source = RecordingSource(listOf("lookup"))
        var sawToolOutcome = false
        val provider = FakeProvider { step, _, messages, _ ->
            if (step == 0) {
                AssistantTurn(toolCalls = listOf(ToolCall("lookup-1", "lookup", "{}")))
            } else {
                sawToolOutcome = (messages.last() as ToolResultsMsg).outcomes.single().name == "lookup"
                AssistantTurn(
                    toolCalls = listOf(
                        ToolCall("output-1", AgentStructuredOutput.TOOL_NAME, """{"answer":"found"}"""),
                    ),
                )
            }
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "look it up")
            put(
                AgentNode.OUTPUT_SCHEMA_KEY,
                """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"]}""",
            )
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray { add(JsonPrimitive("lookup")) })
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertEquals("found", state.output.single().json.getValue("answer").jsonPrimitive.content)
        assertTrue(sawToolOutcome)
        assertTrue(source.invoked.contains("lookup"))
    }

    @Test
    fun `plain text is retried and fails closed when the step budget ends`() {
        var sawCorrection = false
        val provider = FakeProvider { _, _, messages, _ ->
            sawCorrection = messages.any {
                it is UserMsg && it.text == AgentStructuredOutput.MISSING_SUBMISSION_MESSAGE
            }
            AssistantTurn(text = "secret prose instead of json")
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "answer")
            put(AgentNode.MAX_STEPS_KEY, "2")
            put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"object","properties":{"answer":{"type":"string"}}}""")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.ERROR, state.status)
        assertEquals(
            "Agent stopped: MAX_STEPS after 2 completed step(s), 0 attempted tool call(s); " +
                "no valid structured output was produced",
            state.error,
        )
        assertTrue(sawCorrection)
        assertTrue(state.output.isEmpty())
        assertTrue(state.logs.contains("agent non-structured final text withheld (28 chars)"))
        assertFalse(state.logs.joinToString("\n").contains("secret prose instead of json"))
    }

    @Test
    fun `structured correction attempts have their own cap`() {
        val providerCalls = AtomicInteger()
        val provider = FakeProvider { _, _, _, _ ->
            providerCalls.incrementAndGet()
            AssistantTurn(text = "still prose")
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "answer")
            put(AgentNode.MAX_STEPS_KEY, "8")
            put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"object","properties":{"answer":{"type":"string"}}}""")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.ERROR, state.status)
        assertContains(state.error.orEmpty(), "did not produce valid structured output after 3 attempts")
        assertEquals(3, providerCalls.get())
        assertTrue(state.output.isEmpty())
    }

    @Test
    fun `blank structured response fails without creating a correction turn`() {
        val providerCalls = AtomicInteger()
        val provider = FakeProvider { _, _, _, _ ->
            providerCalls.incrementAndGet()
            AssistantTurn(text = null)
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"object","properties":{"answer":{"type":"string"}}}""")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.ERROR, state.status)
        assertContains(state.error.orEmpty(), "returned an empty response instead of required structured output")
        assertEquals(1, providerCalls.get())
        assertTrue(state.output.isEmpty())
    }

    @Test
    fun `invalid output schema fails before provider work starts`() {
        val providerCalls = AtomicInteger()
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = {
                FakeProvider { _, _, _, _ ->
                    providerCalls.incrementAndGet()
                    AssistantTurn(text = "should not run")
                }
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"array"}""")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.ERROR, state.status)
        assertEquals("Agent output schema (outputSchema) must describe an object", state.error)
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun `explicit null output schema disables structured mode`() {
        val provider = FakeProvider.scripted(AssistantTurn(text = "ordinary answer"))
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "answer")
            put(AgentNode.OUTPUT_SCHEMA_KEY, JsonNull)
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertEquals("ordinary answer", state.output.single().json.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `structured mode reports token budget and timeout without emitting prose`() {
        fun specFor(provider: AgentProvider) = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val schema = """{"type":"object","properties":{"answer":{"type":"string"}}}"""

        val tokenRegistry = builtinNodeRegistry().also {
            it.register(
                specFor(
                    FakeProvider.scripted(
                        AssistantTurn(text = "token-limited prose", usage = TokenUsage(input = 1, output = 1)),
                    ),
                ),
            )
        }
        val tokenConfig = buildJsonObject {
            put(AgentNode.OUTPUT_SCHEMA_KEY, schema)
            put(AgentNode.MAX_TOKENS_KEY, "1")
        }
        val tokenState = runFlow(
            tokenRegistry,
            listOf(PlanNode("token", AgentNode.KIND, "Agent", tokenConfig)),
            emptyList(),
        ).getValue("token")

        assertEquals(RunStatus.ERROR, tokenState.status)
        assertContains(tokenState.error.orEmpty(), "Agent stopped: TOKEN_BUDGET")
        assertTrue(tokenState.output.isEmpty())
        assertFalse(tokenState.logs.joinToString("\n").contains("token-limited prose"))

        val timeoutRegistry = builtinNodeRegistry().also {
            it.register(
                specFor(
                    FakeProvider { _, _, _, _ ->
                        delay(10_000)
                        AssistantTurn(text = "timeout prose")
                    },
                ),
            )
        }
        val timeoutConfig = buildJsonObject {
            put(AgentNode.OUTPUT_SCHEMA_KEY, schema)
            put(AgentNode.TIMEOUT_KEY, "100")
        }
        val timeoutState = runFlow(
            timeoutRegistry,
            listOf(PlanNode("timeout", AgentNode.KIND, "Agent", timeoutConfig)),
            emptyList(),
        ).getValue("timeout")

        assertEquals(RunStatus.ERROR, timeoutState.status)
        assertEquals(
            "Agent stopped: TIMEOUT after 0 completed step(s), 0 attempted tool call(s); " +
                "timeout was 100ms; no valid structured output was produced",
            timeoutState.error,
        )
        assertTrue(timeoutState.output.isEmpty())
        assertFalse(timeoutState.logs.joinToString("\n").contains("timeout prose"))
    }

    @Test
    fun `agent node enforces its allowlist`() {
        val source = RecordingSource(listOf("safe", "danger"))
        val provider = FakeProvider.scripted(
            AssistantTurn(toolCalls = listOf(ToolCall("1", "danger", "{}"))),
            AssistantTurn(text = "done"),
        )
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put("input", "go")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("safe")) })
        }
        val states = runFlow(
            reg,
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())), PlanNode("a", "agent", "Agent", cfg)),
            listOf(EdgeModel("e", "t", 0, "a", 0)),
        )
        assertEquals(RunStatus.SUCCESS, states["a"]!!.status)
        assertTrue(source.invoked.isEmpty()) // 'danger' was gated, 'safe' never called
    }

    @Test
    fun `model is informational while temperature is parsed as a real request setting`() {
        lateinit var settings: AgentSettings
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { parsed ->
                settings = parsed
                FakeProvider.scripted(AssistantTurn(text = "done"))
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            // Old saved flows keep this raw key, but it must not become a request override.
            put(AgentNode.MODEL_KEY, "legacy-model-that-must-not-run")
            put(AgentNode.TEMPERATURE_KEY, "0.25")
            put(AgentNode.TIMEOUT_KEY, "12345")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertEquals(0.25f, settings.temperature)
        assertEquals(12_345L, settings.budget.timeoutMs)
        val modelField = AgentNode.CONFIG_FIELDS.single { it.key == AgentNode.MODEL_KEY }
        assertEquals(FieldType.INFO, modelField.type)
        assertTrue(modelField.note.contains("Settings"))
        assertTrue(modelField.note.contains("ignored"))
        assertTrue(modelField.default.isEmpty())
        assertTrue(modelField.placeholder.isEmpty())

        val snapshot = GraphSnapshot(
            nodes = listOf(NodeModel("a", AgentNode.KIND, "Agent", 0f, 0f, cfg)),
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
        )
        val encoded = Json.encodeToString(GraphSnapshot.serializer(), snapshot)
        val restored = Json.decodeFromString(GraphSnapshot.serializer(), encoded)
        assertEquals(
            "legacy-model-that-must-not-run",
            restored.nodes.single().config[AgentNode.MODEL_KEY]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `absent blank and JSON number temperatures follow the config path`() {
        val seen = mutableListOf<Float?>()
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { settings ->
                seen += settings.temperature
                FakeProvider.scripted(AssistantTurn(text = "done"))
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val configs = listOf(
            buildJsonObject { put(AgentNode.INPUT_KEY, "absent") },
            buildJsonObject {
                put(AgentNode.INPUT_KEY, "blank")
                put(AgentNode.TEMPERATURE_KEY, "   ")
            },
            buildJsonObject {
                put(AgentNode.INPUT_KEY, "number")
                put(AgentNode.TEMPERATURE_KEY, 0.25)
            },
        )

        configs.forEachIndexed { index, config ->
            val state = runFlow(
                reg,
                listOf(PlanNode("a$index", AgentNode.KIND, "Agent", config)),
                emptyList(),
            ).getValue("a$index")
            assertEquals(RunStatus.SUCCESS, state.status)
        }

        assertEquals(listOf(null, null, 0.25f), seen)
    }

    @Test
    fun `temperature must be finite and non-negative`() {
        val providerCalls = AtomicInteger()
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = {
                providerCalls.incrementAndGet()
                FakeProvider.scripted(AssistantTurn(text = "must not run"))
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }

        fun stateFor(id: String, value: String): NodeRun {
            val cfg = buildJsonObject {
                put(AgentNode.INPUT_KEY, "go")
                put(AgentNode.TEMPERATURE_KEY, value)
            }
            return runFlow(reg, listOf(PlanNode(id, AgentNode.KIND, "Agent", cfg)), emptyList()).getValue(id)
        }

        val nonFinite = stateFor("nan", "NaN")
        val negative = stateFor("negative", "-0.1")
        val tooHigh = stateFor("high", "20")

        assertEquals(RunStatus.ERROR, nonFinite.status)
        assertEquals("Agent temperature (temperature) must be a finite number", nonFinite.error)
        assertEquals(RunStatus.ERROR, negative.status)
        assertEquals("Agent temperature (temperature) must be zero or greater", negative.error)
        assertEquals(RunStatus.ERROR, tooHigh.status)
        assertEquals("Agent temperature (temperature) must be 2 or less", tooHigh.error)
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun `agent timeout fails the node instead of emitting a successful result`() {
        val provider = FakeProvider { step, _, _, _ ->
            if (step == 0) {
                AssistantTurn(
                    text = "working answer",
                    toolCalls = listOf(ToolCall("1", "step", "{}")),
                )
            } else {
                delay(10_000)
                AssistantTurn(text = "too late")
            }
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(listOf("step")) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.TIMEOUT_KEY, "100")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("step"))
            })
        }

        val states = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList())

        assertEquals(RunStatus.ERROR, states["a"]?.status)
        assertEquals(
            "Agent stopped: TIMEOUT after 1 completed step(s), 1 attempted tool call(s); timeout was 100ms",
            states["a"]?.error,
        )
        assertTrue(states["a"]?.logs.orEmpty().any { it.startsWith("agent stopped: TIMEOUT") })
        assertTrue(states["a"]?.logs.orEmpty().contains("agent partial text withheld (14 chars)"))
        assertFalse(states["a"]?.logs.orEmpty().any { "working answer" in it })
        assertTrue(states["a"]?.output.orEmpty().isEmpty())
    }

    @Test
    fun `provider failure persists a sanitized terminal diagnostic`() {
        val provider = FakeProvider { _, _, _, _ ->
            throw ExecError("The provider does not offer the selected model")
        }
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.SYSTEM_KEY, "SYSTEM-PROMPT-SECRET")
            put(AgentNode.INPUT_KEY, "INPUT-SECRET")
        }

        val state = runFlow(
            reg,
            listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)),
            emptyList(),
        ).getValue("a")

        assertEquals(RunStatus.ERROR, state.status)
        assertEquals(
            "Agent stopped: FAILED after 0 completed step(s), 0 attempted tool call(s): " +
                "The provider does not offer the selected model",
            state.error,
        )
        assertEquals(
            listOf(
                "agent step 1: requesting model",
                "agent stopped: FAILED (0 completed step(s), 0 attempted tool call(s))",
            ),
            state.logs,
        )
        val report = state.logs.joinToString("\n")
        assertFalse("SYSTEM-PROMPT-SECRET" in report)
        assertFalse("INPUT-SECRET" in report)
    }

    @Test
    fun `negative timeout is rejected before provider work starts`() {
        val providerCalls = AtomicInteger()
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = {
                FakeProvider { _, _, _, _ ->
                    providerCalls.incrementAndGet()
                    AssistantTurn(text = "should not run")
                }
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.TIMEOUT_KEY, "-5")
        }

        val states = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList())

        assertEquals(RunStatus.ERROR, states["a"]?.status)
        assertEquals("Agent timeout (timeoutMs) must be greater than 0", states["a"]?.error)
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun `malformed timeout and non-positive max steps are configuration errors`() {
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { FakeProvider.scripted(AssistantTurn(text = "should not run")) },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val malformedTimeout = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.TIMEOUT_KEY, "1e3")
        }
        val zeroSteps = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.MAX_STEPS_KEY, "0")
        }

        val timeoutState = runFlow(
            reg,
            listOf(PlanNode("timeout", AgentNode.KIND, "Agent", malformedTimeout)),
            emptyList(),
        )["timeout"]
        val stepsState = runFlow(
            reg,
            listOf(PlanNode("steps", AgentNode.KIND, "Agent", zeroSteps)),
            emptyList(),
        )["steps"]

        assertEquals(RunStatus.ERROR, timeoutState?.status)
        assertEquals(
            "Agent timeout (timeoutMs) must be a whole number greater than 0",
            timeoutState?.error,
        )
        assertEquals(RunStatus.ERROR, stepsState?.status)
        assertEquals("Agent max steps (maxSteps) must be greater than 0", stepsState?.error)
    }

    @Test
    fun `agent timeout is capped below the flow watchdog`() {
        lateinit var settings: AgentSettings
        val spec = agentNodeSpec(
            prompts = null,
            providerFor = { parsed ->
                settings = parsed
                FakeProvider.scripted(AssistantTurn(text = "done"))
            },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.TIMEOUT_KEY, "1800000")
        }

        val state = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList()).getValue("a")

        assertEquals(RunStatus.SUCCESS, state.status)
        assertEquals(AgentNode.MAX_TIMEOUT_MS, settings.budget.timeoutMs)
        val hardStopMs = settings.budget.timeoutMs + maxOf(500L, settings.budget.timeoutMs / 20)
        assertTrue(hardStopMs <= FlowController.DEFAULT_RUN_TIMEOUT_MS - 60_000L)
    }

    @Test
    fun `max steps remains a successful bounded result`() {
        val source = RecordingSource(listOf("spin"))
        val provider = FakeProvider { _, _, _, _ ->
            AssistantTurn(toolCalls = listOf(ToolCall("1", "spin", "{}")))
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.MAX_STEPS_KEY, "1")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("spin"))
            })
        }

        val states = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList())

        assertEquals(RunStatus.SUCCESS, states["a"]?.status)
        assertEquals("MAX_STEPS", states["a"]?.output?.single()?.json?.get("stopReason")?.jsonPrimitive?.content)
    }

    @Test
    fun `token budget remains a successful bounded result`() {
        val source = RecordingSource(listOf("spin"))
        val provider = FakeProvider { _, _, _, _ ->
            AssistantTurn(
                toolCalls = listOf(ToolCall("1", "spin", "{}")),
                usage = TokenUsage(input = 5, output = 5),
            )
        }
        val spec = agentNodeSpec(prompts = null, providerFor = { provider }, toolSourceFor = { source })
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject {
            put(AgentNode.INPUT_KEY, "go")
            put(AgentNode.MAX_TOKENS_KEY, "1")
            put(AgentNode.ALLOWLIST_KEY, buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("spin"))
            })
        }

        val states = runFlow(reg, listOf(PlanNode("a", AgentNode.KIND, "Agent", cfg)), emptyList())

        assertEquals(RunStatus.SUCCESS, states["a"]?.status)
        assertEquals("TOKEN_BUDGET", states["a"]?.output?.single()?.json?.get("stopReason")?.jsonPrimitive?.content)
    }

    @Test
    fun `agent node composes its system prompt from a promptId`() = runBlocking {
        val prompts = PromptRegistry(TestStorage())
        prompts.upsert(Prompt(id = "p1", name = "Helper", base = "You are helpful.", rules = listOf("Be terse")))
        var seenSystem = ""
        // Capture the system prompt the runtime was handed.
        val provider = FakeProvider { _, system, _, _ -> seenSystem = system; AssistantTurn(text = "ok") }
        val spec = agentNodeSpec(
            prompts = prompts,
            providerFor = { provider },
            toolSourceFor = { RecordingSource(emptyList()) },
        )
        val reg = builtinNodeRegistry().also { it.register(spec) }
        val cfg = buildJsonObject { put(AgentNode.PROMPT_ID_KEY, "p1"); put("input", "hi") }
        val states = ConcurrentHashMap<String, NodeRun>()
        FlowExecutor(minimalContext(), reg).run(
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())), PlanNode("a", "agent", "Agent", cfg)),
            listOf(EdgeModel("e", "t", 0, "a", 0)),
        ) { id, r -> states[id] = r }
        assertEquals(RunStatus.SUCCESS, states["a"]!!.status)
        assertTrue(seenSystem.contains("You are helpful."))
        assertTrue(seenSystem.contains("Be terse"))
    }
}
