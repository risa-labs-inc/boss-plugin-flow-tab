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
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * P2: the headless, storage-seated [FlowController]. Pins that authoring writes a
 * [GraphSnapshot] at `graph:<tabId>` (no open tab), that add/connect patch it, and
 * that the async run-job model reaches a terminal state and yields per-node outputs
 * — all against an in-memory fake store, no live boss server (F1 async, F5 storage).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowControllerTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun context(
        storage: PluginStorageProvider,
        tabUpdates: TabUpdateProviderFactory? = null,
    ): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = null
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
        override val tabUpdateProviderFactory: TabUpdateProviderFactory? = tabUpdates
    }

    private fun controller(
        storage: PluginStorageProvider = DesktopStorage(),
        registry: NodeRegistry = builtinNodeRegistry(),
        runTimeoutMs: Long = FlowController.DEFAULT_RUN_TIMEOUT_MS,
        tabUpdates: TabUpdateProviderFactory? = null,
    ) = FlowController(context(storage, tabUpdates), { scope }, registry, runTimeoutMs)

    @Test
    fun `canvas run history publishes live and retains only the newest twenty per flow`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        try {
            repeat(25) { index ->
                fc.publishCanvasRun(
                    RunJob(
                        runId = "run-history-$tabId-${index + 1}",
                        tabId = tabId,
                        state = RunJobState.SUCCEEDED,
                        startedAtMs = (index + 1).toLong(),
                        nodeCount = 3,
                    )
                )
            }

            withTimeout(5_000) {
                val expected = (6..25).mapTo(mutableSetOf()) {
                    "json:${FlowController.RUN_PREFIX}run-history-$tabId-$it"
                }
                while (storage.map.keys.filterTo(mutableSetOf()) {
                        it.startsWith("json:${FlowController.RUN_PREFIX}")
                    } != expected) {
                    delay(10)
                }
            }
            val history = fc.listRuns(tabId)
            assertEquals(20, history.size)
            assertEquals("run-history-$tabId-25", history.first().runId)
            assertEquals("run-history-$tabId-6", history.last().runId)
            assertEquals(3, history.first().nodeCount)
            assertEquals(
                "run-history-$tabId-25",
                FlowPersistenceCoordinator.latestRunUpdate(tabId)?.job?.runId,
            )
        } finally {
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    @Test
    fun `terminal history eviction scans storage once`() = runBlocking {
        val storage = object : DesktopStorage() {
            val scans = AtomicInteger()
            override suspend fun getAllKeys(): Set<String> {
                scans.incrementAndGet()
                return super.getAllKeys()
            }
        }
        val fc = controller(storage)
        val tabId = fc.createFlow()
        storage.scans.set(0)
        try {
            fc.publishCanvasRun(
                RunJob(
                    runId = "run-single-scan-${java.util.UUID.randomUUID()}",
                    tabId = tabId,
                    state = RunJobState.SUCCEEDED,
                    startedAtMs = 1L,
                )
            )
            withTimeout(5_000) {
                while (storage.scans.get() == 0) delay(10)
            }

            assertEquals(1, storage.scans.get())
        } finally {
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    @Test
    fun `a second controller observes a live canvas run without orphaning it`() = runBlocking {
        val storage = DesktopStorage()
        val owner = controller(storage)
        val observer = controller(storage)
        val tabId = owner.createFlow()
        val running = RunJob(
            runId = "run-cross-controller-${java.util.UUID.randomUUID()}",
            tabId = tabId,
            state = RunJobState.RUNNING,
            startedAtMs = 10L,
            nodeCount = 1,
        )
        try {
            owner.publishCanvasRun(running)
            assertEquals(RunJobState.RUNNING, observer.runStatus(running.runId)?.state)

            owner.publishCanvasRun(running.copy(state = RunJobState.SUCCEEDED))
            withTimeout(5_000) {
                while (observer.runStatus(running.runId)?.state != RunJobState.SUCCEEDED) delay(10)
            }
        } finally {
            owner.dispose()
            observer.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    @Test
    fun `live coordinator drops outputs while owner status keeps the full result`() = runBlocking {
        val fc = controller()
        val tabId = "flow-live-lightweight-${java.util.UUID.randomUUID()}"
        val runId = "run-live-lightweight-${java.util.UUID.randomUUID()}"
        val output = buildJsonObject { put("payload", "full-result") }
        val job = RunJob(
            runId = runId,
            tabId = tabId,
            state = RunJobState.RUNNING,
            startedAtMs = 1L,
            nodes = mapOf(
                "node" to NodeRunSnap(status = RunStatus.SUCCESS, output = listOf(output)),
            ),
        )
        try {
            fc.publishCanvasRun(job, persist = false)

            assertTrue(FlowPersistenceCoordinator.runUpdate(runId)!!.job.nodes["node"]!!.output.isEmpty())
            assertEquals(listOf(output), fc.runStatus(runId)!!.nodes["node"]!!.output)
        } finally {
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    @Test
    fun `stored running history is repaired after its in-process owner disappears`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        val runId = "run-stale-history-${java.util.UUID.randomUUID()}"
        storage.putJson(
            "${FlowController.RUN_PREFIX}$runId",
            kotlinx.serialization.json.Json.encodeToString(
                RunJob.serializer(),
                RunJob(runId, tabId, RunJobState.RUNNING, startedAtMs = 1L),
            ),
        )
        FlowPersistenceCoordinator.forgetRun(runId)

        try {
            val summary = fc.listRuns(tabId).single()
            assertEquals(RunJobState.FAILED, summary.state)
            assertEquals(RunJobState.FAILED, fc.runSnapshot(runId)?.state)
        } finally {
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    @Test
    fun `older run updates cannot replace the newest live flow update`() {
        val tabId = "flow-run-order-${java.util.UUID.randomUUID()}"
        val newer = RunJob("run-new", tabId, RunJobState.RUNNING, startedAtMs = 2L)
        val older = RunJob("run-old", tabId, RunJobState.SUCCEEDED, startedAtMs = 1L)

        FlowPersistenceCoordinator.publishRunUpdate(newer)
        FlowPersistenceCoordinator.publishRunUpdate(older)

        assertEquals("run-new", FlowPersistenceCoordinator.latestRunUpdate(tabId)?.job?.runId)
        FlowPersistenceCoordinator.forget(tabId)
    }

    @Test
    fun `late running update cannot overwrite the same run terminal state`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        val running = RunJob(
            "run-terminal-guard-${java.util.UUID.randomUUID()}",
            tabId,
            RunJobState.RUNNING,
            startedAtMs = 10L,
        )
        val terminal = running.copy(state = RunJobState.SUCCEEDED)

        FlowPersistenceCoordinator.publishRunUpdate(running)
        FlowPersistenceCoordinator.publishRunUpdate(terminal)
        FlowPersistenceCoordinator.publishRunUpdate(running.copy(nodeCount = 99))

        assertEquals(RunJobState.SUCCEEDED, fc.runStatus(running.runId)?.state)
        assertEquals(RunJobState.SUCCEEDED, FlowPersistenceCoordinator.latestRunUpdate(tabId)?.job?.state)
        FlowPersistenceCoordinator.forget(tabId)
    }

    @Test
    fun `controller disposal forgets its static run snapshots`() {
        val fc = controller()
        val tabId = "flow-dispose-history-${java.util.UUID.randomUUID()}"
        val job = RunJob(
            runId = "run-dispose-history-${java.util.UUID.randomUUID()}",
            tabId = tabId,
            state = RunJobState.SUCCEEDED,
            startedAtMs = 1L,
        )
        fc.publishCanvasRun(job, persist = false)
        assertNotNull(FlowPersistenceCoordinator.runUpdate(job.runId))

        fc.dispose()

        assertNull(FlowPersistenceCoordinator.runUpdate(job.runId))
        FlowPersistenceCoordinator.forget(tabId)
    }

    @Test
    fun `controller disposal publishes terminal before rejecting late canvas updates`() {
        val fc = controller()
        val tabId = "flow-dispose-running-${java.util.UUID.randomUUID()}"
        val running = RunJob(
            runId = "run-dispose-running-${java.util.UUID.randomUUID()}",
            tabId = tabId,
            state = RunJobState.RUNNING,
            startedAtMs = 1L,
        )
        fc.publishCanvasRun(running, persist = false)

        fc.dispose()
        fc.publishCanvasRun(running, persist = false)

        assertEquals(RunJobState.FAILED, FlowPersistenceCoordinator.latestRunUpdate(tabId)?.job?.state)
        assertNull(FlowPersistenceCoordinator.runUpdate(running.runId))
        FlowPersistenceCoordinator.forget(tabId)
    }

    @Test
    fun `missing graph persistence withdraws a mirrored running canvas update`() = runBlocking {
        val fc = controller(DesktopStorage())
        val tabId = "flow-missing-graph-${java.util.UUID.randomUUID()}"
        val running = RunJob(
            runId = "run-missing-graph-${java.util.UUID.randomUUID()}",
            tabId = tabId,
            state = RunJobState.RUNNING,
            startedAtMs = 1L,
        )
        try {
            fc.publishCanvasRun(running)
            withTimeout(5_000) {
                while (FlowPersistenceCoordinator.latestRunUpdate(tabId) != null) delay(10)
            }

            fc.publishCanvasRun(running.copy(state = RunJobState.SUCCEEDED))
            delay(100)
            assertNull(FlowPersistenceCoordinator.latestRunUpdate(tabId))
            assertNull(FlowPersistenceCoordinator.runUpdate(running.runId))
        } finally {
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
    }

    private fun hangingRegistry(
        kind: String,
        onStart: () -> Unit = {},
    ): NodeRegistry = builtinNodeRegistry().also {
        it.register(
            NodeSpec(
                id = kind,
                label = "Hang",
                inputs = 0,
                outputs = 1,
                accent = 0,
                description = "test only",
                runMode = RunMode.ONCE,
                executor = NodeExecutor { _, _, _, _ ->
                    onStart()
                    awaitCancellation()
                },
            ),
        )
    }

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
    fun `addNode reuses a deleted layout slot instead of colliding by node count`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val first = fc.addNode(tabId, "TRIGGER")
        val deleted = fc.addNode(tabId, "SET")
        val third = fc.addNode(tabId, "SET")
        val beforeDelete = fc.getFlow(tabId)!!
        val deletedPosition = beforeDelete.nodes.single { it.id == deleted }.let { Offset(it.x, it.y) }

        fc.deleteNode(tabId, deleted)
        val replacement = fc.addNode(tabId, "CODE")
        val final = fc.getFlow(tabId)!!

        assertEquals(deletedPosition, final.nodes.single { it.id == replacement }.let { Offset(it.x, it.y) })
        assertEquals(3, final.nodes.size)
        assertEquals(setOf(first, third, replacement), final.nodes.map { it.id }.toSet())
        assertEquals(final.nodes.size, final.nodes.map { it.x to it.y }.toSet().size)
    }

    @Test
    fun `addNode rejects an unregistered kind without changing the flow`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()

        val failure = assertFailsWith<IllegalArgumentException> {
            fc.addNode(tabId, "__invalid_probe_kind__")
        }

        val message = failure.message.orEmpty()
        assertContains(message, "__invalid_probe_kind__")
        assertContains(message, "Valid kinds:")
        assertContains(message, "AWAIT_LOGIN")
        assertContains(message, "CLICK")
        assertContains(message, "CODE")
        assertTrue(fc.getFlow(tabId)!!.nodes.isEmpty())
    }

    @Test
    fun `addNode is case-sensitive and gives dynamic kinds a synchronization hint`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()

        assertFailsWith<IllegalArgumentException> { fc.addNode(tabId, "set") }
        val dynamicFailure = assertFailsWith<IllegalArgumentException> {
            fc.addNode(tabId, "tool:ext:server/missing")
        }

        assertContains(dynamicFailure.message.orEmpty(), "may still be synchronizing")
        assertTrue(fc.getFlow(tabId)!!.nodes.isEmpty())
    }

    @Test
    fun `unknown dynamic kind caps suggestions within its namespace`() = runBlocking {
        val registry = builtinNodeRegistry().also { reg ->
            repeat(40) { index ->
                val id = "tool:ext:server/tool-${index.toString().padStart(2, '0')}"
                reg.register(NodeSpec.unavailable(id))
            }
        }
        val fc = controller(registry = registry)
        val tabId = fc.createFlow()

        val failure = assertFailsWith<IllegalArgumentException> {
            fc.addNode(tabId, "tool:ext:server/missing")
        }

        assertContains(failure.message.orEmpty(), "tool:ext:server/tool-00")
        assertContains(failure.message.orEmpty(), "… and 10 more")
        assertContains(failure.message.orEmpty(), "40 tool kinds currently registered")
        assertTrue("TRIGGER" !in failure.message.orEmpty())
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
    fun `updateNode merges config and preserves kind position and untouched fields`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val nodeId = fc.addNode(
            tabId,
            "HTTP",
            buildJsonObject {
                put("method", "POST")
                put("url", "https://old.example")
            },
        )
        val before = fc.getFlow(tabId)!!.nodes.single()

        val updated = fc.updateNode(
            tabId,
            nodeId,
            title = "Fetch claims",
            configPatch = buildJsonObject { put("url", "https://new.example") },
        )

        assertEquals("Fetch claims", updated.title)
        assertEquals("HTTP", updated.type)
        assertEquals(before.x, updated.x)
        assertEquals(before.y, updated.y)
        assertEquals("POST", updated.config.getValue("method").jsonPrimitive.content)
        assertEquals("https://new.example", updated.config.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `deleteNode removes incident edges and deleteEdge removes only its connection`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val trigger = fc.addNode(tabId, "TRIGGER")
        val first = fc.addNode(tabId, "SET")
        val second = fc.addNode(tabId, "SET")
        val firstEdge = fc.connect(tabId, trigger, 0, first, 0)
        fc.connect(tabId, trigger, 0, second, 0)

        fc.deleteEdge(tabId, firstEdge)
        assertEquals(1, fc.getFlow(tabId)!!.edges.size)
        assertEquals(1, fc.deleteNode(tabId, second))
        val final = fc.getFlow(tabId)!!
        assertEquals(setOf(trigger, first), final.nodes.map { it.id }.toSet())
        assertTrue(final.edges.isEmpty())
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
    fun `listFlowDetails includes metadata and keeps corrupt graphs visible`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "Claims intake", description = "Triage claims"))
        fc.addNode(tabId, "TRIGGER")
        storage.putJson("${FlowController.GRAPH_PREFIX}flow-corrupt", "{not-json")

        val details = fc.listFlowDetails()
        val saved = details.single { it.tabId == tabId }
        val corrupt = details.single { it.tabId == "flow-corrupt" }

        assertEquals("Claims intake", saved.name)
        assertEquals("Triage claims", saved.description)
        assertEquals(1, saved.nodeCount)
        assertTrue(saved.readable)
        assertFalse(corrupt.readable)
    }

    @Test
    fun `getFlow returns null for an unknown tabId`() = runBlocking {
        assertNull(controller().getFlow("flow-does-not-exist"))
    }

    @Test
    fun `renameFlow preserves metadata and graph content`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow(
            FlowMeta(name = "Old name", description = "Keep me", version = 3, inputs = listOf("claimId"))
        )
        val nodeId = fc.addNode(tabId, "TRIGGER")

        val summary = fc.renameFlow(tabId, "  Claims intake  ")
        val renamed = fc.getFlow(tabId)!!

        assertEquals("Claims intake", summary.name)
        assertEquals("Claims intake", renamed.metadata?.name)
        assertEquals("Keep me", renamed.metadata?.description)
        assertEquals(3, renamed.metadata?.version)
        assertEquals(listOf("claimId"), renamed.metadata?.inputs)
        assertEquals(nodeId, renamed.nodes.single().id)
    }

    @Test
    fun `renameOpenFlow persists a live snapshot before autosave creates the flow`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = "flow-new-tab"
        val snapshot = GraphSnapshot(
            nodes = listOf(NodeModel("n1", "TRIGGER", "Trigger", 10f, 20f)),
            metadata = FlowMeta(description = "Keep me", inputs = listOf("claimId")),
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
        )

        val summary = fc.renameOpenFlow(tabId, "  Intake monitor  ", snapshot)
        val renamed = fc.getFlow(tabId)!!

        assertEquals("Intake monitor", summary.name)
        assertEquals("Intake monitor", renamed.metadata?.name)
        assertEquals("Keep me", renamed.metadata?.description)
        assertEquals(listOf("claimId"), renamed.metadata?.inputs)
        assertEquals("n1", renamed.nodes.single().id)
    }

    @Test
    fun `renameOpenFlow validates blank and overlong names`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = "flow-new-tab-validation"

        assertFailsWith<IllegalArgumentException> {
            fc.renameOpenFlow(tabId, "   ", GraphSnapshot())
        }
        assertFailsWith<IllegalArgumentException> {
            fc.renameOpenFlow(
                tabId,
                "x".repeat(FlowController.MAX_FLOW_NAME_LENGTH + 1),
                GraphSnapshot(),
            )
        }
        assertNull(fc.getFlow(tabId))
    }

    @Test
    fun `renameOpenFlow reports unavailable storage instead of false success`() = runBlocking {
        val noStorageContext = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = scope
            override val mcpToolRegistry: McpToolRegistry? = null
            override val pluginStorageFactory: PluginStorageFactory? = null
        }
        val fc = FlowController(noStorageContext, { scope })

        val failure = assertFailsWith<IllegalStateException> {
            fc.renameOpenFlow("flow-no-storage", "New name", GraphSnapshot())
        }
        assertEquals("Flow storage is unavailable", failure.message)
    }

    @Test
    fun `renameOpenFlow finishes its durable write after caller cancellation`() = runBlocking {
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val storage = object : DesktopStorage() {
            override suspend fun putJson(key: String, jsonValue: String) {
                writeStarted.complete(Unit)
                allowWrite.await()
                super.putJson(key, jsonValue)
            }
        }
        val fc = controller(storage)
        val tabId = "flow-close-during-rename"
        val rename = launch {
            fc.renameOpenFlow(tabId, "Durable name", GraphSnapshot())
        }

        writeStarted.await()
        rename.cancel()
        assertFalse(rename.isCompleted)
        allowWrite.complete(Unit)
        rename.join()

        assertEquals("Durable name", fc.getFlow(tabId)?.metadata?.name)
        assertTrue(rename.isCancelled)
    }

    @Test
    fun `renameFlow keeps the graph renamed when the host title refresh fails`() = runBlocking {
        val storage = DesktopStorage()
        val tabId = controller(storage).createFlow(FlowMeta(name = "Old name"))
        val titleFailure = object : TabUpdateProviderFactory {
            override fun createProvider(tabId: String, typeId: TabTypeId): TabUpdateProvider {
                val providerTabId = tabId
                return object : TabUpdateProvider {
                    override val tabId: String = providerTabId
                    override fun updateTitle(title: String) = error("host title update failed")
                    override fun updateFavicon(faviconUrl: String?) = Unit
                    override fun updateUrl(url: String) = Unit
                    override fun closeTab() = Unit
                    override fun openNewTab(url: String): String? = null
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val fc = controller(storage, tabUpdates = titleFailure)
            val summary = fc.renameFlow(tabId, "New name")

            assertEquals("New name", summary.name)
            assertEquals("New name", fc.getFlow(tabId)?.metadata?.name)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `renameFlow does not swallow title refresh cancellation`() = runBlocking {
        val storage = DesktopStorage()
        val tabId = controller(storage).createFlow(FlowMeta(name = "Old name"))
        val cancelledTitle = object : TabUpdateProviderFactory {
            override fun createProvider(tabId: String, typeId: TabTypeId): TabUpdateProvider {
                val providerTabId = tabId
                return object : TabUpdateProvider {
                    override val tabId: String = providerTabId
                    override fun updateTitle(title: String): Unit = throw CancellationException("cancel")
                    override fun updateFavicon(faviconUrl: String?) = Unit
                    override fun updateUrl(url: String) = Unit
                    override fun closeTab() = Unit
                    override fun openNewTab(url: String): String? = null
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val fc = controller(storage, tabUpdates = cancelledTitle)
            assertFailsWith<CancellationException> { fc.renameFlow(tabId, "New name") }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stale open-tab autosave cannot revert a successful rename`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "Old name"))
        val staleOpenTabSnapshot = fc.getFlow(tabId)!!

        fc.renameFlow(tabId, "New name")
        FlowPersistenceCoordinator.persistAutosave(tabId, staleOpenTabSnapshot) { safeAutosave ->
            storage.putJson(
                "${FlowController.GRAPH_PREFIX}$tabId",
                kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), safeAutosave),
            )
        }

        assertEquals("New name", fc.getFlow(tabId)?.metadata?.name)
        assertEquals("New name", FlowPersistenceCoordinator.latestName(tabId))

        val renameUpdate = FlowPersistenceCoordinator.latestGraphUpdate(tabId)!!
        FlowPersistenceCoordinator.persistAutosave(
            tabId,
            fc.getFlow(tabId)!!,
            appliedGraphRevision = renameUpdate.revision,
        ) { convergedAutosave ->
            storage.putJson(
                "${FlowController.GRAPH_PREFIX}$tabId",
                kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), convergedAutosave),
            )
        }
        assertNull(FlowPersistenceCoordinator.latestName(tabId))

        val importedSnapshot = fc.getFlow(tabId)!!.copy(metadata = FlowMeta(name = "Imported name"))
        FlowPersistenceCoordinator.persistAutosave(tabId, importedSnapshot) { importAutosave ->
            storage.putJson(
                "${FlowController.GRAPH_PREFIX}$tabId",
                kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), importAutosave),
            )
        }
        assertEquals("Imported name", fc.getFlow(tabId)?.metadata?.name)
    }

    @Test
    fun `renameFlow reads after a concurrent autosave and preserves its graph changes`() = runBlocking {
        withTimeout(5_000) {
            val storage = DesktopStorage()
            val fc = controller(storage)
            val tabId = fc.createFlow(FlowMeta(name = "Old name"))
            val liveSnapshot = fc.getFlow(tabId)!!.copy(
                nodes = listOf(NodeModel("n-live", "TRIGGER", "Live node", 10f, 20f)),
            )
            val autosaveHasLock = CompletableDeferred<Unit>()
            val releaseAutosave = CompletableDeferred<Unit>()

            val autosave = launch {
                FlowPersistenceCoordinator.persistAutosave(tabId, liveSnapshot) { snapshot ->
                    autosaveHasLock.complete(Unit)
                    releaseAutosave.await()
                    storage.putJson(
                        "${FlowController.GRAPH_PREFIX}$tabId",
                        kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), snapshot),
                    )
                }
            }
            autosaveHasLock.await()
            val rename = async { fc.renameFlow(tabId, "New name") }
            yield()
            assertFalse(rename.isCompleted, "rename must wait for the in-flight autosave")

            releaseAutosave.complete(Unit)
            autosave.join()
            rename.await()

            val saved = fc.getFlow(tabId)!!
            assertEquals("New name", saved.metadata?.name)
            assertEquals(listOf("n-live"), saved.nodes.map { it.id })
        }
    }

    @Test
    fun `controller updates replace stale canvas state and reject its pending autosave`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "MCP-authored"))
        val staleCanvas = fc.getFlow(tabId)!!

        val trigger = fc.addNode(tabId, "TRIGGER")
        val set = fc.addNode(tabId, "SET")
        val edge = fc.connect(tabId, trigger, 0, set, 0)
        val update = FlowPersistenceCoordinator.latestGraphUpdate(tabId)!!

        val canvasState = FlowGraphState()
        assertTrue(canvasState.load(staleCanvas))
        val appliedRevision = canvasState.applyExternalGraphUpdate(update, appliedRevision = 0L)
        assertEquals(update.revision, appliedRevision)
        assertEquals(setOf(trigger, set), canvasState.nodes.map { it.id }.toSet())
        assertEquals(edge, canvasState.edges.single().id)

        var staleWriteAttempted = false
        FlowPersistenceCoordinator.persistAutosave(
            tabId = tabId,
            snapshot = staleCanvas,
            appliedGraphRevision = 0L,
        ) {
            staleWriteAttempted = true
            storage.putJson("${FlowController.GRAPH_PREFIX}$tabId", "should-not-be-written")
        }
        assertFalse(staleWriteAttempted)
        assertEquals(setOf(trigger, set), fc.getFlow(tabId)!!.nodes.map { it.id }.toSet())

        FlowPersistenceCoordinator.persistAutosave(
            tabId = tabId,
            snapshot = canvasState.toSnapshot(),
            appliedGraphRevision = appliedRevision,
        ) { converged ->
            storage.putJson(
                "${FlowController.GRAPH_PREFIX}$tabId",
                kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), converged),
            )
        }
        assertNull(FlowPersistenceCoordinator.latestGraphUpdate(tabId))
    }

    @Test
    fun `controller mutations include debounced canvas edits without losing rapid external writes`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        val localNode = NodeModel("n0", "TRIGGER", "Unsaved local trigger", 10f, 20f)
        val liveSnapshot = fc.getFlow(tabId)!!.copy(nodes = listOf(localNode), nextId = 1L)
        val canvas = object : LiveFlowCanvas {
            override val isInitialized: Boolean = true
            override val appliedGraphRevision: Long = 0L
            override fun snapshot(): GraphSnapshot = liveSnapshot
        }

        Dispatchers.setMain(UnconfinedTestDispatcher())
        FlowPersistenceCoordinator.registerLiveCanvas(tabId, canvas)
        try {
            val firstAddedNode = fc.addNode(tabId, "SET")
            val secondAddedNode = fc.addNode(tabId, "CODE")
            val saved = fc.getFlow(tabId)!!

            assertEquals(setOf("n0", firstAddedNode, secondAddedNode), saved.nodes.map { it.id }.toSet())
            assertEquals(3L, saved.nextId)
        } finally {
            FlowPersistenceCoordinator.unregisterLiveCanvas(tabId, canvas)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `addNode waits for an in-flight canvas autosave and preserves both changes`() = runBlocking {
        withTimeout(5_000) {
            val storage = DesktopStorage()
            val fc = controller(storage)
            val tabId = fc.createFlow()
            val localNode = NodeModel("n0", "TRIGGER", "Local trigger", 10f, 20f)
            val liveCanvas = fc.getFlow(tabId)!!.copy(nodes = listOf(localNode), nextId = 1L)
            val autosaveHasLock = CompletableDeferred<Unit>()
            val releaseAutosave = CompletableDeferred<Unit>()

            val autosave = launch {
                FlowPersistenceCoordinator.persistAutosave(tabId, liveCanvas) { snapshot ->
                    autosaveHasLock.complete(Unit)
                    releaseAutosave.await()
                    storage.putJson(
                        "${FlowController.GRAPH_PREFIX}$tabId",
                        kotlinx.serialization.json.Json.encodeToString(GraphSnapshot.serializer(), snapshot),
                    )
                }
            }
            autosaveHasLock.await()
            val addition = async(start = CoroutineStart.UNDISPATCHED) { fc.addNode(tabId, "SET") }
            assertFalse(addition.isCompleted, "controller mutation must wait for canvas autosave")

            releaseAutosave.complete(Unit)
            autosave.join()
            val addedNode = addition.await()

            val saved = fc.getFlow(tabId)!!
            assertEquals(setOf("n0", addedNode), saved.nodes.map { it.id }.toSet())
            assertEquals(2L, saved.nextId)
        }
    }

    @Test
    fun `renameFlow rejects blank names and unreadable flows`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        storage.putJson("${FlowController.GRAPH_PREFIX}flow-corrupt", "{not-json")

        assertFailsWith<IllegalArgumentException> { fc.renameFlow(tabId, "   ") }
        assertFailsWith<IllegalArgumentException> { fc.renameFlow("flow-corrupt", "New name") }
    }

    @Test
    fun `deleteFlow removes graph persisted UI state and run history`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "Disposable"))
        storage.putJson("$RUN_STATE_PREFIX$tabId", "{}")
        storage.putJson(
            "${FlowController.RUN_PREFIX}run-delete-me",
            kotlinx.serialization.json.Json.encodeToString(
                RunJob.serializer(),
                RunJob("run-delete-me", tabId, RunJobState.SUCCEEDED, startedAtMs = 1L),
            ),
        )
        FlowPersistenceCoordinator.publishRename(tabId, "Disposable renamed")

        assertTrue(fc.deleteFlow(tabId))

        assertNull(storage.getJson("${FlowController.GRAPH_PREFIX}$tabId"))
        assertNull(storage.getJson("$RUN_STATE_PREFIX$tabId"))
        assertNull(storage.getJson("${FlowController.RUN_PREFIX}run-delete-me"))
        assertFalse(tabId in fc.listFlows())
        assertNull(FlowPersistenceCoordinator.latestName(tabId))
        assertNull(FlowPersistenceCoordinator.latestGraphUpdate(tabId))
    }

    @Test
    fun `deleteFlow removes corrupt graphs and returns false for missing ids`() = runBlocking {
        val storage = DesktopStorage()
        val fc = controller(storage)
        storage.putJson("${FlowController.GRAPH_PREFIX}flow-corrupt", "{not-json")

        assertTrue(fc.deleteFlow("flow-corrupt"))
        assertFalse(fc.deleteFlow("flow-missing"))
    }

    @Test
    fun `deleteFlow suppresses a terminal persist queued after deletion`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val nodeFinished = CompletableDeferred<Unit>()
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "DELETE_RACE",
                    label = "Delete race",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        nodeFinished.complete(Unit)
                        NodeOutput.EMPTY
                    },
                )
            )
        }
        val storage = DesktopStorage()
        val fc = controller(storage, registry, runTimeoutMs = 5_000)
        val tabId = fc.createFlow()
        fc.addNode(tabId, "DELETE_RACE")
        val runId = fc.startRun(tabId)
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(fc.deleteFlow(tabId))
            release.complete(Unit)
            withTimeout(5_000) { nodeFinished.await() }
            delay(100)

            assertNull(storage.getJson("${FlowController.RUN_PREFIX}$runId"))
            assertNull(FlowPersistenceCoordinator.runUpdate(runId))
        } finally {
            release.complete(Unit)
            fc.dispose()
            FlowPersistenceCoordinator.forget(tabId)
        }
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
    fun `start after disposal fails without resolving scope or invoking executor`() = runBlocking {
        var scopeResolved = false
        var executorInvoked = false
        val fc = FlowController(
            context = context(DesktopStorage()),
            scopeProvider = {
                scopeResolved = true
                scope
            },
            registry = hangingRegistry("POST_DISPOSE_HANG") { executorInvoked = true },
        )
        val tabId = fc.createFlow()
        fc.addNode(tabId, "POST_DISPOSE_HANG")

        try {
            fc.dispose()
            val runId = fc.startRun(tabId)
            val terminal = fc.runStatus(runId)!!
            assertEquals(RunJobState.FAILED, terminal.state)
            assertEquals("Flow controller disposed", terminal.error)
            assertFalse(scopeResolved, "a disposed controller must not resolve the host execution scope")
            assertFalse(executorInvoked, "a disposed controller must not dispatch host work")
        } finally {
            fc.dispose()
        }
    }

    @Test
    fun `start racing disposal is failed after execution installation`() = runBlocking {
        val storage = DesktopStorage()
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val runScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fc = FlowController(
            context = context(storage),
            scopeProvider = {
                providerEntered.countDown()
                check(releaseProvider.await(5, TimeUnit.SECONDS)) { "test did not release scope provider" }
                runScope
            },
            registry = hangingRegistry("RACING_DISPOSE_HANG"),
        )
        val tabId = fc.createFlow()
        fc.addNode(tabId, "RACING_DISPOSE_HANG")
        val start = async(Dispatchers.Default) { fc.startRun(tabId) }

        try {
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS), "start must reach the scope provider")
            fc.dispose()
            releaseProvider.countDown()

            val runId = withTimeout(5_000) { start.await() }
            val terminal = fc.runStatus(runId)!!
            assertEquals(RunJobState.FAILED, terminal.state)
            assertEquals("Flow controller disposed", terminal.error)
        } finally {
            releaseProvider.countDown()
            fc.dispose()
            runScope.cancel()
        }
    }

    @Test
    fun `scope cancellation uses stable flow cancellation wording`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fc = FlowController(
            context = context(DesktopStorage()),
            scopeProvider = { runScope },
            registry = hangingRegistry("SCOPE_CANCEL_HANG") { entered.complete(Unit) },
        )
        val tabId = fc.createFlow()
        fc.addNode(tabId, "SCOPE_CANCEL_HANG")
        val runId = fc.startRun(tabId)

        try {
            withTimeout(5_000) { entered.await() }
            runScope.cancel(CancellationException("sandbox scope replaced"))

            val terminal = awaitTerminal(fc, runId)
            assertEquals(RunJobState.FAILED, terminal.state)
            assertEquals("Flow run cancelled: sandbox scope replaced", terminal.error)
        } finally {
            fc.dispose()
            runScope.cancel()
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
        try {
            val tabId = fc.createFlow()
            fc.addNode(tabId, "TRIGGER")

            sandboxScope.cancel()
            sandboxScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val job = awaitTerminal(fc, fc.startRun(tabId))
            assertEquals(RunJobState.SUCCEEDED, job.state)
        } finally {
            fc.dispose()
            sandboxScope.cancel()
        }
    }

    @Test
    fun `dispatch into an already cancelled scope fails and persists without raw coroutine text`() = runBlocking {
        val cancelledScope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { it.cancel() }
        val storage = DesktopStorage()
        val fc = FlowController(context(storage), { cancelledScope })
        val tabId = fc.createFlow()
        fc.addNode(tabId, "TRIGGER")

        val runId = fc.startRun(tabId)
        val job = awaitTerminal(fc, runId)
        assertEquals(RunJobState.FAILED, job.state)
        assertEquals("Flow run cancelled before dispatch", job.error)

        val reloaded = FlowController(context(storage), { scope })
        try {
            val persisted = withTimeout(5_000) {
                while (true) {
                    reloaded.runStatus(runId)?.let { return@withTimeout it }
                    delay(10)
                }
                error("unreachable")
            }
            assertEquals(RunJobState.FAILED, persisted.state)
            assertEquals("Flow run cancelled before dispatch", persisted.error)
        } finally {
            reloaded.dispose()
            fc.dispose()
        }
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
        private val _tools = MutableStateFlow(listOf(registeredTool(name)))
        override val tools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val allTools: StateFlow<List<RegisteredMcpTool>> get() = _tools
        override val disabledToolNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult = McpToolResult("ok", false)

        fun replaceWith(name: String) {
            _tools.value = listOf(registeredTool(name))
        }

        companion object {
            private fun registeredTool(name: String) = RegisteredMcpTool(
                "prov",
                McpToolDefinition(name, "d", """{"type":"object"}""", true) { McpToolResult("ok", false) },
            )
        }
    }

    private fun contextWithBossTool(
        storage: PluginStorageProvider,
        bossRegistry: McpToolRegistry,
        scopeProvider: () -> CoroutineScope = { scope },
    ): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope: CoroutineScope get() = scopeProvider()
        override val mcpToolRegistry: McpToolRegistry = bossRegistry
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    @Test
    fun `headless controller resolves boss tool node kinds so MCP flow_run can use them`() = runBlocking {
        val storage = DesktopStorage()
        val ctx = contextWithBossTool(storage, FakeBossRegistry("demo"))
        val controller = buildHeadlessController(ctx, PromptRegistry(storage), external = null, scope = scope)
        try {
            // The tools StateFlow collector registers the kind asynchronously; wait for it.
            withTimeout(2_000) {
                while (controller.registry.resolve("tool:boss:demo").isUnavailable) delay(10)
            }
            val spec = controller.registry.resolve("tool:boss:demo")
            assertTrue(!spec.isUnavailable, "boss tool kind must be registered on the headless registry")
            assertNotNull(spec.executor, "boss tool node must be runnable via flow_run")
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `headless tool sync is idempotent`() = runBlocking {
        val storage = DesktopStorage()
        val ctx = contextWithBossTool(storage, FakeBossRegistry("demo"))
        val controller = FlowController(ctx)
        try {
            val first = controller.startToolRegistrySync(external = null)
            val second = controller.startToolRegistrySync(external = null)

            assertSame(first, second, "restarting sync must return the existing jobs")
            assertEquals(1, first.size, "only one host registry collector should be installed")
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `external-only sync does not block later host registry startup`() = runBlocking {
        val storage = DesktopStorage()
        var bossRegistry: McpToolRegistry? = null
        val ctx = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope: CoroutineScope get() = scope
            override val mcpToolRegistry: McpToolRegistry? get() = bossRegistry
            override val pluginStorageFactory = object : PluginStorageFactory {
                override fun createStorage(pluginId: String): PluginStorageProvider = storage
            }
        }
        val controller = FlowController(ctx)
        val external = ExternalMcpManager(
            storage,
            SecretResolver.constant(null),
            SettingsStore(storage),
        )

        try {
            val externalOnly = controller.startToolRegistrySync(external)
            assertEquals(1, externalOnly.size, "the external job should start without a host registry")

            bossRegistry = FakeBossRegistry("late")
            val both = controller.startToolRegistrySync(external)
            assertEquals(2, both.size, "a later host registry should add its collector")
            assertTrue(externalOnly.single() in both, "the existing external job must not be duplicated")
            assertSame(both, controller.startToolRegistrySync(external), "a stable retry must remain idempotent")
            withTimeout(2_000) {
                while (controller.registry.resolve("tool:boss:late").isUnavailable) delay(10)
            }
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `tool sync startup after controller disposal fails explicitly`() {
        val storage = DesktopStorage()
        val ctx = contextWithBossTool(storage, FakeBossRegistry("demo"))
        val controller = FlowController(ctx)
        controller.dispose()

        val failure = assertFailsWith<IllegalStateException> {
            controller.startToolRegistrySync(external = null)
        }
        assertContains(failure.message.orEmpty(), "after controller disposal")
    }

    @Test
    fun `headless tool sync survives sandbox scope replacement and stops on dispose`() = runBlocking {
        val storage = DesktopStorage()
        val bossRegistry = FakeBossRegistry("before-restart")
        var sandboxScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val ctx = contextWithBossTool(storage, bossRegistry) { sandboxScope }
        val controller = buildHeadlessController(ctx, PromptRegistry(storage), external = null)
        val syncJobs = controller.startToolRegistrySync(external = null)

        try {
            withTimeout(2_000) {
                while (controller.registry.resolve("tool:boss:before-restart").isUnavailable) delay(10)
            }

            sandboxScope.cancel()
            sandboxScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            bossRegistry.replaceWith("after-restart")

            withTimeout(2_000) {
                while (controller.registry.resolve("tool:boss:after-restart").isUnavailable) delay(10)
            }
            assertTrue(
                controller.registry.resolve("tool:boss:before-restart").isUnavailable,
                "a removed pre-restart tool must be unregistered",
            )

            controller.dispose()
            withTimeout(2_000) { syncJobs.forEach { it.join() } }
            assertTrue(syncJobs.all { it.isCancelled }, "dispose must cancel every live sync job")
            bossRegistry.replaceWith("after-dispose")
            assertTrue(
                controller.registry.resolve("tool:boss:after-dispose").isUnavailable,
                "disposed synchronization must not apply later registry updates",
            )
        } finally {
            controller.dispose()
            sandboxScope.cancel()
        }
    }
}
