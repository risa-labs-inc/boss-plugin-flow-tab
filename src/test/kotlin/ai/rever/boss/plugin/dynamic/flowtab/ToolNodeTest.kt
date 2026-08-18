package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.api.TabRegistry
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1: host-registry tools surfaced as workflow nodes. Uses an in-memory fake
 * [McpToolRegistry] so we can pin: a tool becomes a node, args are marshalled from
 * config, isError → ExecError (feeds the DAG failure model, F8), an absent tool is a
 * first-class "unavailable" node (F4), and the tool list re-derives on change.
 */
class ToolNodeTest {

    // ---- test doubles -------------------------------------------------------

    private fun noopHandler() = object : McpToolHandler {
        override suspend fun call(args: McpToolArgs): McpToolResult = McpToolResult("", false)
    }

    private fun tool(name: String, schema: String, desc: String = "d"): RegisteredMcpTool =
        RegisteredMcpTool("prov", McpToolDefinition(name, desc, schema, true, noopHandler()))

    private class FakeRegistry(
        initial: List<RegisteredMcpTool>,
        val onInvoke: suspend (String, String) -> McpToolResult,
    ) : McpToolRegistry {
        private val _tools = MutableStateFlow(initial)
        override val tools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val allTools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val disabledToolNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult = onInvoke(toolName, arguments)
        fun setTools(list: List<RegisteredMcpTool>) { _tools.value = list }
    }

    private fun minimalContext(reg: McpToolRegistry?): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(Dispatchers.Default)
        override val mcpToolRegistry: McpToolRegistry? = reg
    }

    // ---- list / descriptor mapping -----------------------------------------

    @Test
    fun `boss source lists registry tools as boss-scoped descriptors`() = runBlocking {
        val reg = FakeRegistry(listOf(tool("search", "{}"))) { _, _ -> McpToolResult("", false) }
        val d = BossRegistryToolSource(reg).list().single()
        assertEquals(ToolScope.BOSS, d.ref.scope)
        assertEquals("search", d.ref.name)
        assertEquals("search", d.name)
        assertEquals("tool:boss:search", d.ref.kindId)
    }

    // ---- spec generation ----------------------------------------------------

    @Test
    fun `a descriptor becomes a single-in single-out per-item tool node spec`() {
        val src = BossRegistryToolSource(FakeRegistry(emptyList()) { _, _ -> McpToolResult("", false) })
        val desc = ToolDescriptor(ToolRef(ToolScope.BOSS, "grep"), "grep", "find text",
            """{"type":"object","properties":{"pattern":{"type":"string"}}}""")
        val spec = toolNodeSpec(desc, src)
        assertEquals("tool:boss:grep", spec.id)
        assertEquals(1, spec.inputs)
        assertEquals(1, spec.outputs)
        assertEquals(RunMode.PER_ITEM, spec.runMode)
        assertEquals(listOf("pattern"), spec.configFields.map { it.key })
        assertNotNull(spec.executor)
        // schema + ref cached in default config so a saved node renders when the tool
        // is absent at load (F4).
        assertEquals("tool:boss:grep", (spec.defaultConfig[ToolNode.REF_KEY] as? JsonPrimitive)?.content)
        assertTrue((spec.defaultConfig[ToolNode.SCHEMA_KEY] as? JsonPrimitive)?.content!!.contains("pattern"))
    }

    @Test
    fun `spawning a tool node seeds the cached schema snapshot into its config`() {
        val src = BossRegistryToolSource(FakeRegistry(emptyList()) { _, _ -> McpToolResult("", false) })
        val desc = ToolDescriptor(ToolRef(ToolScope.BOSS, "grep"), "grep", "",
            """{"type":"object","properties":{"pattern":{"type":"string"}}}""")
        val reg = builtinNodeRegistry().also { it.register(toolNodeSpec(desc, src)) }
        val state = FlowGraphState(reg)
        val node = state.addNode("tool:boss:grep", Offset.Zero)
        assertEquals("tool:boss:grep", (node.config[ToolNode.REF_KEY] as? JsonPrimitive)?.content)
    }

    // ---- executor: arg marshalling + result --------------------------------

    private fun runFlow(reg: NodeRegistry, ctx: PluginContext, nodes: List<PlanNode>, edges: List<EdgeModel>): Map<String, NodeRun> {
        val states = ConcurrentHashMap<String, NodeRun>()
        runBlocking(Dispatchers.Default) {
            FlowExecutor(ctx, reg).run(nodes, edges) { id, r -> states[id] = r }
        }
        return states
    }

    @Test
    fun `executor marshals typed config into argsJson and emits the result`() {
        var captured: String? = null
        val fake = FakeRegistry(emptyList()) { _, args ->
            captured = args
            McpToolResult("""{"hits":3}""", false)
        }
        val src = BossRegistryToolSource(fake)
        val desc = ToolDescriptor(ToolRef(ToolScope.BOSS, "grep"), "grep", "",
            """{"type":"object","properties":{"pattern":{"type":"string"},"max":{"type":"integer"},"deep":{"type":"boolean"}}}""")
        val reg = builtinNodeRegistry().also { it.register(toolNodeSpec(desc, src)) }
        val ctx = minimalContext(fake)

        val toolCfg = buildJsonObject {
            put("pattern", "todo")
            put("max", "5")
            put("deep", "true")
        }
        val states = runFlow(
            reg, ctx,
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())),
                   PlanNode("g", "tool:boss:grep", "grep", toolCfg)),
            listOf(EdgeModel("e", "t", 0, "g", 0)),
        )
        assertEquals(RunStatus.SUCCESS, states["g"]!!.status)
        val args = captured!!.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        assertEquals("todo", args["pattern"]!!.jsonPrimitive.content)
        assertEquals("5", args["max"]!!.jsonPrimitive.content)      // number, unquoted in JSON
        assertEquals("true", args["deep"]!!.jsonPrimitive.content)  // boolean
        // result JSON object flows through as the item
        assertEquals("3", states["g"]!!.output.single().json["hits"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a tool error becomes an ExecError that fails the node`() {
        val fake = FakeRegistry(emptyList()) { _, _ -> McpToolResult("boom", true) }
        val src = BossRegistryToolSource(fake)
        val desc = ToolDescriptor(ToolRef(ToolScope.BOSS, "x"), "x", "", "{}")
        val reg = builtinNodeRegistry().also { it.register(toolNodeSpec(desc, src)) }
        val states = runFlow(
            reg, minimalContext(fake),
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())),
                   PlanNode("x", "tool:boss:x", "x", JsonObject(emptyMap()))),
            listOf(EdgeModel("e", "t", 0, "x", 0)),
        )
        assertEquals(RunStatus.ERROR, states["x"]!!.status)
        assertTrue(states["x"]!!.error!!.contains("boom"))
    }

    @Test
    fun `an absent tool is a first-class unavailable node, not a crash`() {
        val reg = builtinNodeRegistry() // no tool registered
        val states = runFlow(
            reg, minimalContext(null),
            listOf(PlanNode("t", "TRIGGER", "T", JsonObject(emptyMap())),
                   PlanNode("z", "tool:boss:gone", "gone", JsonObject(emptyMap()))),
            listOf(EdgeModel("e", "t", 0, "z", 0)),
        )
        // Whole run doesn't throw; only the tool node errors.
        assertEquals(RunStatus.SUCCESS, states["t"]!!.status)
        assertEquals(RunStatus.ERROR, states["z"]!!.status)
        assertTrue(reg.resolve("tool:boss:gone").isUnavailable)
    }

    // ---- sync ---------------------------------------------------------------

    @Test
    fun `sync registers tool specs and drops ones that vanish`() {
        val fake = FakeRegistry(listOf(tool("a", "{}"), tool("b", "{}"))) { _, _ -> McpToolResult("", false) }
        val src = BossRegistryToolSource(fake)
        val reg = builtinNodeRegistry()
        val sync = ToolNodeSync(src, reg, sourceLabel = "host")
        runBlocking {
            sync.apply(src.list())
            assertNotNull(reg["tool:boss:a"])
            assertNotNull(reg["tool:boss:b"])
            fake.setTools(listOf(tool("a", "{}")))
            sync.apply(src.list())
        }
        assertNotNull(reg["tool:boss:a"])
        assertNull(reg["tool:boss:b"]) // b vanished → unregistered
        // built-ins untouched
        assertNotNull(reg["HTTP"])
    }

    @Test
    fun `sync preserves a failed descriptor while applying healthy peers`() {
        val fake = FakeRegistry(emptyList()) { _, _ -> McpToolResult("", false) }
        val source = BossRegistryToolSource(fake)
        val registry = builtinNodeRegistry()
        val diagnostics = mutableListOf<String>()
        val sync = ToolNodeSync(source, registry, "host", diagnostics::add)
        fun descriptor(ref: String, label: String = ref) =
            ToolDescriptor(ToolRef(ToolScope.BOSS, ref), label, "", "{}")

        sync.apply(listOf(descriptor("flaky"), descriptor("removed")))
        sync.apply(listOf(descriptor("flaky", label = ""), descriptor("healthy")))

        assertEquals("flaky", registry["tool:boss:flaky"]?.label, "failed updates keep the last good spec")
        assertNotNull(registry["tool:boss:healthy"], "healthy peers in the same update must still register")
        assertNull(registry["tool:boss:removed"], "absent healthy descriptors must still unregister")
        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.single(), "tool:boss:flaky")

        sync.apply(listOf(descriptor("flaky", label = ""), descriptor("healthy")))
        assertEquals(1, diagnostics.size, "an unchanged malformed descriptor must not spam every emission")

        sync.apply(listOf(descriptor("flaky"), descriptor("healthy")))
        sync.apply(listOf(descriptor("flaky", label = ""), descriptor("healthy")))
        assertEquals(2, diagnostics.size, "a failure recurring after recovery must be reported again")
    }

    @Test
    fun `sync retains the complete registry when every spec is malformed`() {
        val fake = FakeRegistry(emptyList()) { _, _ -> McpToolResult("", false) }
        val source = BossRegistryToolSource(fake)
        val registry = builtinNodeRegistry()
        val diagnostics = mutableListOf<String>()
        val sync = ToolNodeSync(source, registry, "external MCP", diagnostics::add)
        fun descriptor(ref: String, label: String = ref) =
            ToolDescriptor(ToolRef(ToolScope.EXT, ref), label, "", "{}")

        sync.apply(listOf(descriptor("last-good")))
        sync.apply(listOf(descriptor("bad\nid", label = "")))

        assertNotNull(registry["tool:ext:last-good"], "an all-failed transient update keeps all last-good specs")
        assertEquals(1, diagnostics.size)
        assertContains(diagnostics.single(), "external MCP tool 'tool:ext:bad id'")
        assertFalse('\n' in diagnostics.single())

        sync.apply(emptyList())
        assertNull(registry["tool:ext:last-good"], "a valid empty update still removes vanished tools")
    }

    @Test
    fun `syncBossTools degrades to null when the host has no registry`() {
        val job = syncBossTools(minimalContext(null), builtinNodeRegistry(), CoroutineScope(Dispatchers.Default))
        assertNull(job)
    }

    @Test
    fun `boss tool collector applies live host updates`() = runBlocking {
        val updates = MutableSharedFlow<List<RegisteredMcpTool>>()
        val fake = FakeRegistry(emptyList()) { _, _ -> McpToolResult("", false) }
        val registry = builtinNodeRegistry()
        val sync = ToolNodeSync(BossRegistryToolSource(fake), registry, sourceLabel = "host")
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            collectBossToolUpdates(updates, sync)
        }

        try {
            updates.emit(listOf(tool("healthy", "{}")))

            withTimeout(2_000) {
                while (registry["tool:boss:healthy"] == null) kotlinx.coroutines.yield()
            }
            assertTrue(job.isActive)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `tool sync failure messages are single line and bounded`() {
        val bounded = toolSyncFailureMessage(IllegalStateException("x".repeat(250) + "\nsecret"))
        val sanitized = toolSyncFailureMessage(IllegalStateException("a\nb\rc\u0000d\u0085e\u2028f\u2029g"))
        val identifier = toolSyncIdentifier("tool:boss:a\nb" + "x".repeat(200))

        assertEquals(MAX_TOOL_SYNC_FAILURE_MESSAGE_LENGTH, bounded.length)
        assertFalse("secret" in bounded)
        assertEquals("a b c d e f g", sanitized)
        assertTrue(sanitized.none(Char::isISOControl))
        assertEquals(MAX_TOOL_SYNC_IDENTIFIER_LENGTH, identifier.length)
        assertFalse('\n' in identifier)
    }
}
