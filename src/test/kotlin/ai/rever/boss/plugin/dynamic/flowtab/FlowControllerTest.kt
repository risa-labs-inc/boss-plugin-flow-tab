package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2: the headless, storage-seated [FlowController]. Pins that authoring writes a
 * [GraphSnapshot] at `graph:<tabId>` (no open tab), that add/connect patch it, and
 * that the async run-job model reaches a terminal state and yields per-node outputs
 * — all against an in-memory fake store, no live boss server (F1 async, F5 storage).
 */
class FlowControllerTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun context(storage: PluginStorageProvider): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = null
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    private fun controller(
        storage: PluginStorageProvider = DesktopStorage(),
        registry: NodeRegistry = builtinNodeRegistry(),
        runTimeoutMs: Long = FlowController.DEFAULT_RUN_TIMEOUT_MS,
    ) = FlowController(context(storage), { scope }, registry, runTimeoutMs)

    // ---- authoring ----------------------------------------------------------

    @Test
    fun `createFlow seeds a v2 snapshot at graph colon tabId`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "Router", description = "test"))
        assertTrue(tabId.startsWith("flow-"))
        assertTrue(storage.map.containsKey("json:graph:$tabId"))
        val snap = fc.getFlow(tabId)!!
        assertEquals(SUPPORTED_SCHEMA_VERSION, snap.schemaVersion)
        assertEquals("Router", snap.metadata?.name)
        assertTrue(snap.nodes.isEmpty())
    }

    @Test
    fun `addNode appends a node and merges the spec default config`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val nodeId = fc.addNode(tabId, "SET", buildJsonObject { put("assignments", "{}") })
        val snap = fc.getFlow(tabId)!!
        assertEquals(1, snap.nodes.size)
        val node = snap.nodes.single()
        assertEquals(nodeId, node.id)
        assertEquals("SET", node.type)
        assertEquals("{}", node.config["assignments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `addNode assigns unique titles for repeated kinds`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        fc.addNode(tabId, "SET", JsonObject(emptyMap()))
        fc.addNode(tabId, "SET", JsonObject(emptyMap()))
        val titles = fc.getFlow(tabId)!!.nodes.map { it.title }
        assertEquals(titles.toSet().size, titles.size) // all unique (D3)
    }

    @Test
    fun `addNode rejects an unregistered kind without changing the flow`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()

        val failure = assertFailsWith<IllegalArgumentException> {
            fc.addNode(tabId, "__invalid_probe_kind__")
        }

        assertContains(failure.message.orEmpty(), "__invalid_probe_kind__")
        assertContains(failure.message.orEmpty(), "SET")
        assertTrue(fc.getFlow(tabId)!!.nodes.isEmpty())
    }

    @Test
    fun `connect adds an edge between two nodes`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val a = fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val b = fc.addNode(tabId, "SET", JsonObject(emptyMap()))
        val edgeId = fc.connect(tabId, a, 0, b, 0)
        val snap = fc.getFlow(tabId)!!
        assertEquals(1, snap.edges.size)
        assertEquals(edgeId, snap.edges.single().id)
        assertEquals(a, snap.edges.single().fromNode)
        assertEquals(b, snap.edges.single().toNode)
    }

    @Test
    fun `connect rejects self and unknown nodes`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val a = fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        assertTrue(runCatching { fc.connect(tabId, a, 0, a, 0) }.isFailure)     // self
        assertTrue(runCatching { fc.connect(tabId, a, 0, "nope", 0) }.isFailure) // unknown target
    }

    @Test
    fun `listFlows returns every stored flow id`() = runBlocking {
        val fc = controller()
        val a = fc.createFlow()
        val b = fc.createFlow()
        assertEquals(setOf(a, b), fc.listFlows().toSet())
    }

    @Test
    fun `listFlows also accepts providers that enumerate logical graph keys`() = runBlocking {
        val storage = TestStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        assertEquals(listOf(tabId), fc.listFlows())
    }

    @Test
    fun `getFlow returns null for an unknown tabId`() = runBlocking {
        assertNull(controller().getFlow("flow-does-not-exist"))
    }

    // ---- async run job ------------------------------------------------------

    private suspend fun awaitTerminal(fc: FlowController, runId: String): RunJob =
        withTimeout(5_000) {
            while (fc.runStatus(runId)?.state == RunJobState.RUNNING) delay(10)
            fc.runStatus(runId)!!
        }

    @Test
    fun `startRun runs a flow to a terminal state and yields outputs`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val trig = fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val set = fc.addNode(
            tabId, "SET",
            buildJsonObject { put("assignments", """{"greeting":"hi"}""") }
        )
        fc.connect(tabId, trig, 0, set, 0)

        val runId = fc.startRun(tabId)
        assertTrue(runId.startsWith("run-"))
        val job = awaitTerminal(fc, runId)

        assertEquals(RunJobState.SUCCEEDED, job.state)
        val outputs = fc.runResult(runId)!!
        assertEquals(RunStatus.SUCCESS, outputs[set]!!.status)
        assertEquals("hi", outputs[set]!!.output.single()["greeting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `control flow run routes by port and reaches a terminal state`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val trigger = fc.addNode(tabId, "TRIGGER")
        val seed = fc.addNode(
            tabId,
            "SET",
            buildJsonObject { put("assignments", """{"score":"90"}""") },
        )
        val branch = fc.addNode(
            tabId,
            "IF",
            buildJsonObject { put("condition", "{{ \$json.score }} >= 80") },
        )
        val yes = fc.addNode(
            tabId,
            "CODE",
            buildJsonObject { put("code", """{"result":"accepted"}""") },
        )
        val no = fc.addNode(
            tabId,
            "CODE",
            buildJsonObject { put("code", """{"result":"rejected"}""") },
        )
        fc.connect(tabId, trigger, 0, seed, 0)
        fc.connect(tabId, seed, 0, branch, 0)
        fc.connect(tabId, branch, 0, yes, 0)
        fc.connect(tabId, branch, 1, no, 0)

        val job = awaitTerminal(fc, fc.startRun(tabId))
        val result = fc.runResult(job.runId)!!
        assertEquals(RunJobState.SUCCEEDED, job.state)
        assertEquals("accepted", result[yes]!!.output.single()["result"]!!.jsonPrimitive.content)
        assertTrue(result[no]!!.output.isEmpty(), "the unselected false branch must stay empty")
    }

    @Test
    fun `startRun on an unknown flow fails the job`() = runBlocking {
        val fc = controller()
        val runId = fc.startRun("flow-missing")
        val job = awaitTerminal(fc, runId)
        assertEquals(RunJobState.FAILED, job.state)
        assertNotNull(job.error)
    }

    @Test
    fun `a node error fails the run but reaches a terminal state`() = runBlocking {
        val registry = builtinNodeRegistry().also {
            // Model a kind that was registered when authored but is unavailable now;
            // entirely unknown kinds are rejected by addNode.
            it.register(NodeSpec.unavailable("tool:boss:gone"))
        }
        val fc = controller(registry = registry)
        val tabId = fc.createFlow()
        val trig = fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val gone = fc.addNode(tabId, "tool:boss:gone", JsonObject(emptyMap()))
        fc.connect(tabId, trig, 0, gone, 0)
        val job = awaitTerminal(fc, fc.startRun(tabId))
        assertEquals(RunJobState.FAILED, job.state)
        assertEquals(RunStatus.ERROR, fc.runResult(job.runId)!![gone]!!.status)
    }

    @Test
    fun `a registered kind with a null executor fails immediately`() = runBlocking {
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "NULL_EXECUTOR",
                    label = "Null Executor",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    executor = null,
                )
            )
        }
        val fc = controller(registry = registry)
        val tabId = fc.createFlow()
        val nodeId = fc.addNode(tabId, "NULL_EXECUTOR")

        val job = awaitTerminal(fc, fc.startRun(tabId))

        assertEquals(RunJobState.FAILED, job.state)
        assertEquals(RunStatus.ERROR, fc.runResult(job.runId)!![nodeId]!!.status)
        assertTrue(job.error!!.contains("provider isn't loaded", ignoreCase = true))
    }

    @Test
    fun `a host cancellation exception fails the node instead of reporting success`() = runBlocking {
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "HOST_CANCEL",
                    label = "Host Cancel",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        throw CancellationException("host invocation timed out")
                    },
                ),
            )
        }
        val fc = controller(registry = registry)
        val tabId = fc.createFlow()
        val nodeId = fc.addNode(tabId, "HOST_CANCEL")

        val job = awaitTerminal(fc, fc.startRun(tabId))

        assertEquals(RunJobState.FAILED, job.state)
        assertEquals(RunStatus.ERROR, fc.runResult(job.runId)!![nodeId]!!.status)
        assertTrue(job.error!!.contains("host invocation timed out"))
    }

    @Test
    fun `flow_result exposes live node state and the watchdog terminates a hung executor`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "HANG",
                    label = "Hang",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        NodeOutput.EMPTY
                    },
                )
            )
        }
        val fc = controller(registry = registry, runTimeoutMs = 3_000)
        val tabId = fc.createFlow()
        val nodeId = fc.addNode(tabId, "HANG")
        val runId = fc.startRun(tabId)

        try {
            withTimeout(5_000) { entered.await() }
            assertEquals(RunJobState.RUNNING, fc.runStatus(runId)!!.state)
            assertEquals(RunStatus.RUNNING, fc.runResult(runId)!![nodeId]!!.status)

            val job = awaitTerminal(fc, runId)
            assertEquals(RunJobState.FAILED, job.state)
            assertTrue(job.error!!.contains("timeout", ignoreCase = true))
            assertEquals(RunStatus.ERROR, fc.runResult(runId)!![nodeId]!!.status)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `persisted running job becomes failed when loaded after plugin restart`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = DesktopStorage()
        val runScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "RELOAD_HANG",
                    label = "Reload Hang",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        NodeOutput.EMPTY
                    },
                ),
            )
        }
        val beforeReload = FlowController(context(storage), { runScope }, registry, 5_000)
        val tabId = beforeReload.createFlow()
        val nodeId = beforeReload.addNode(tabId, "RELOAD_HANG")
        val runId = beforeReload.startRun(tabId)

        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) {
                while (storage.getJson("${FlowController.RUN_PREFIX}$runId")?.contains(nodeId) != true) {
                    delay(5)
                }
            }
            beforeReload.dispose()
            runScope.cancel()

            val afterReload = FlowController(context(storage), { scope })
            try {
                val loaded = afterReload.runStatus(runId)!!
                assertEquals(RunJobState.FAILED, loaded.state)
                assertTrue(loaded.error!!.contains("plugin reload"))
                assertEquals(RunStatus.ERROR, loaded.nodes.getValue(nodeId).status)
            } finally {
                afterReload.dispose()
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `headless runs use the replacement sandbox scope after watchdog restart`() = runBlocking {
        val storage = DesktopStorage()
        var sandboxScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ctx = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope: CoroutineScope get() = sandboxScope
            override val pluginStorageFactory = object : PluginStorageFactory {
                override fun createStorage(pluginId: String): PluginStorageProvider = storage
            }
        }
        val fc = buildHeadlessController(ctx, PromptRegistry(storage), external = null)
        val tabId = fc.createFlow()
        fc.addNode(tabId, "TRIGGER")

        sandboxScope.cancel()
        sandboxScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val job = awaitTerminal(fc, fc.startRun(tabId))
        assertEquals(RunJobState.SUCCEEDED, job.state)
    }

    @Test
    fun `dispatch into an already cancelled scope fails instead of staying running`() = runBlocking {
        val cancelledScope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { it.cancel() }
        val storage = DesktopStorage()
        val fc = FlowController(context(storage), { cancelledScope })
        val tabId = fc.createFlow()
        fc.addNode(tabId, "TRIGGER")

        val job = awaitTerminal(fc, fc.startRun(tabId))
        assertEquals(RunJobState.FAILED, job.state)
        assertTrue(job.error!!.contains("cancel", ignoreCase = true))
    }

    @Test
    fun `run jobs are persisted to storage`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val runId = fc.startRun(tabId)
        awaitTerminal(fc, runId)
        assertTrue(storage.map.keys.any { it == "json:run:$runId" })
    }

    // ---- S2: run durability across controller instances ---------------------

    @Test
    fun `run status and result survive a fresh controller over the same storage`() = runBlocking {
        val storage = DesktopStorage()
        val fc1 = controller(storage)
        val tabId = fc1.createFlow()
        val trig = fc1.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val set = fc1.addNode(tabId, "SET", buildJsonObject { put("assignments", """{"g":"hi"}""") })
        fc1.connect(tabId, trig, 0, set, 0)
        val runId = fc1.startRun(tabId)
        awaitTerminal(fc1, runId)

        // A new controller (e.g. after a plugin reload) must read the persisted job back,
        // not answer "Unknown runId" from an empty in-memory map (red-team S2).
        val fc2 = controller(storage)
        val job = fc2.runStatus(runId)
        assertNotNull(job)
        assertEquals(RunJobState.SUCCEEDED, job.state)
        assertEquals("hi", fc2.runResult(runId)!![set]!!.output.single()["g"]!!.jsonPrimitive.content)
    }

    // ---- S1: headless controller must wire host (boss) tools -----------------

    /** Minimal fake registry exposing one boss tool so syncBossTools has something to sync. */
    private class FakeBossRegistry(name: String) : McpToolRegistry {
        private val _tools = MutableStateFlow(
            listOf(RegisteredMcpTool("prov", McpToolDefinition(name, "d", """{"type":"object"}""", true) { McpToolResult("ok", false) }))
        )
        override val tools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val allTools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val disabledToolNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult = McpToolResult("ok", false)
    }

    private fun contextWithBossTool(storage: PluginStorageProvider, tool: String): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = FakeBossRegistry(tool)
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    @Test
    fun `headless controller resolves boss tool node kinds so MCP flow_run can use them`() = runBlocking {
        val storage = DesktopStorage()
        val ctx = contextWithBossTool(storage, "demo")
        val controller = buildHeadlessController(ctx, PromptRegistry(storage), external = null, scope = scope)
        // The tools StateFlow collector registers the kind asynchronously; wait for it.
        withTimeout(2_000) {
            while (controller.registry.resolve("tool:boss:demo").isUnavailable) delay(10)
        }
        val spec = controller.registry.resolve("tool:boss:demo")
        assertTrue(!spec.isUnavailable, "boss tool kind must be registered on the headless registry")
        assertNotNull(spec.executor, "boss tool node must be runnable via flow_run")
    }
}
