package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
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
        assertEquals("Agent timed out after 100ms", states["a"]?.error)
        assertTrue(states["a"]?.logs.orEmpty().any { it.startsWith("agent stopped: TIMEOUT") })
        assertTrue(states["a"]?.logs.orEmpty().contains("agent partial text: working answer"))
        assertTrue(states["a"]?.output.orEmpty().isEmpty())
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
