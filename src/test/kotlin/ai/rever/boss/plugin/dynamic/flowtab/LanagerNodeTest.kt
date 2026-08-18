package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P5: the `lanager` node — a nested sub-workflow run as an async job via
 * [FlowController.startRun] (not through the 60s MCP fence). Pins that a lanager node
 * runs its target sub-flow to success and surfaces the runId, and that the cross-flow
 * depth limit + cycle detection trip rather than recursing unbounded (plan §08).
 */
class LanagerNodeTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun context(storage: PluginStorageProvider): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = null
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    /** A controller whose registry also knows the `lanager` kind (executor holds it). */
    private fun controllerWithLanager(
        maxDepth: Int = 3,
        storage: PluginStorageProvider = TestStorage(),
        registry: NodeRegistry = builtinNodeRegistry(),
        runTimeoutMs: Long = FlowController.DEFAULT_RUN_TIMEOUT_MS,
    ): FlowController {
        val fc = FlowController(context(storage), { scope }, registry, runTimeoutMs)
        fc.registry.register(lanagerNodeSpec(fc, maxDepth = maxDepth))
        return fc
    }

    private suspend fun awaitTerminal(fc: FlowController, runId: String): RunJob =
        withTimeout(10_000) {
            while (fc.runStatus(runId)?.state == RunJobState.RUNNING) delay(10)
            fc.runStatus(runId)!!
        }

    private fun blockingRegistry(
        entered: CompletableDeferred<Unit>,
        cancelled: CompletableDeferred<Unit>,
    ): NodeRegistry = builtinNodeRegistry().also {
        it.register(
            NodeSpec(
                id = "BLOCK",
                label = "Block",
                inputs = 0,
                outputs = 1,
                accent = 0,
                description = "test only",
                runMode = RunMode.ONCE,
                executor = NodeExecutor { _, _, _, _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
            ),
        )
    }

    private suspend fun runIdFor(
        fc: FlowController,
        storage: TestStorage,
        tabId: String,
    ): String {
        val matching = mutableListOf<String>()
        for (key in storage.getAllKeys()) {
            if (!key.startsWith(FlowController.RUN_PREFIX)) continue
            val runId = key.removePrefix(FlowController.RUN_PREFIX)
            if (fc.runStatus(runId)?.tabId == tabId) matching += runId
        }
        return matching.single()
    }

    @Test
    fun `a lanager node runs its sub-flow to success`() = runBlocking {
        val fc = controllerWithLanager()

        // Child flow: TRIGGER -> SET greeting=hi
        val child = fc.createFlow(FlowMeta(name = "child"))
        val ct = fc.addNode(child, "TRIGGER", JsonObject(emptyMap()))
        val cs = fc.addNode(child, "SET", buildJsonObject { put("assignments", """{"greeting":"hi"}""") })
        fc.connect(child, ct, 0, cs, 0)

        // Parent flow: TRIGGER -> lanager(child)
        val parent = fc.createFlow(FlowMeta(name = "parent"))
        val pt = fc.addNode(parent, "TRIGGER", JsonObject(emptyMap()))
        val pl = fc.addNode(parent, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, child) })
        fc.connect(parent, pt, 0, pl, 0)

        val job = awaitTerminal(fc, fc.startRun(parent))
        assertEquals(RunJobState.SUCCEEDED, job.state)
        val lanagerOut = fc.runResult(job.runId)!![pl]!!
        assertEquals(RunStatus.SUCCESS, lanagerOut.status)
        // The lanager node reports the sub-run it launched.
        assertEquals(child, lanagerOut.output.single()["subFlow"]!!.jsonPrimitive.content)
        val childRunId = lanagerOut.output.single()["runId"]!!.jsonPrimitive.content
        assertEquals(RunJobState.SUCCEEDED, fc.runStatus(childRunId)!!.state)
    }

    @Test
    fun `stopping a parent run also stops its active sub-flow`() = runBlocking {
        val storage = TestStorage()
        val childEntered = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()
        val registry = blockingRegistry(childEntered, childCancelled)
        val fc = controllerWithLanager(storage = storage, registry = registry)

        val child = fc.createFlow(FlowMeta(name = "child"))
        fc.addNode(child, "BLOCK", JsonObject(emptyMap()))

        val parent = fc.createFlow(FlowMeta(name = "parent"))
        val pt = fc.addNode(parent, "TRIGGER", JsonObject(emptyMap()))
        val pl = fc.addNode(parent, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, child) })
        fc.connect(parent, pt, 0, pl, 0)

        val parentRunId = fc.startRun(parent)
        withTimeout(5_000) { childEntered.await() }
        // TestStorage exposes logical run: keys; child persistence precedes childEntered.
        val childRunId = runIdFor(fc, storage, child)

        fc.stopRun(parentRunId)

        withTimeout(5_000) { childCancelled.await() }
        val parentJob = awaitTerminal(fc, parentRunId)
        val childJob = awaitTerminal(fc, childRunId)
        assertEquals(RunJobState.FAILED, parentJob.state)
        assertEquals(RunJobState.FAILED, childJob.state)
        assertTrue(childJob.error!!.contains("stopped by caller", ignoreCase = true))
    }

    @Test
    fun `parent watchdog timeout stops its active sub-flow`() = runBlocking {
        val storage = TestStorage()
        val childEntered = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()
        val registry = blockingRegistry(childEntered, childCancelled).also {
            it.register(
                NodeSpec(
                    id = "PARENT_DELAY",
                    label = "Parent Delay",
                    inputs = 1,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, inputs, _ ->
                        delay(2_000)
                        NodeOutput.single(inputs)
                    },
                ),
            )
        }
        val fc = controllerWithLanager(
            storage = storage,
            registry = registry,
            runTimeoutMs = 5_000,
        )

        val child = fc.createFlow(FlowMeta(name = "child"))
        fc.addNode(child, "BLOCK", JsonObject(emptyMap()))

        val parent = fc.createFlow(FlowMeta(name = "parent"))
        val pt = fc.addNode(parent, "TRIGGER", JsonObject(emptyMap()))
        val pd = fc.addNode(parent, "PARENT_DELAY", JsonObject(emptyMap()))
        val pl = fc.addNode(parent, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, child) })
        fc.connect(parent, pt, 0, pd, 0)
        fc.connect(parent, pd, 0, pl, 0)

        val parentRunId = fc.startRun(parent)
        withTimeout(5_000) { childEntered.await() }
        val childRunId = runIdFor(fc, storage, child)

        val parentJob = awaitTerminal(fc, parentRunId)
        assertEquals(RunJobState.FAILED, parentJob.state)
        assertTrue(parentJob.error!!.contains("timeout", ignoreCase = true))
        // The child watchdog started about two seconds later; only the parent cascade can stop it here.
        withTimeout(1_000) { childCancelled.await() }
        val childJob = awaitTerminal(fc, childRunId)
        assertEquals(RunJobState.FAILED, childJob.state)
        assertTrue(childJob.error!!.contains("stopped by caller", ignoreCase = true))
    }

    @Test
    fun `stopping a parent cascades through child to grandchild`() = runBlocking {
        val storage = TestStorage()
        val grandchildEntered = CompletableDeferred<Unit>()
        val grandchildCancelled = CompletableDeferred<Unit>()
        val fc = controllerWithLanager(
            storage = storage,
            registry = blockingRegistry(grandchildEntered, grandchildCancelled),
        )

        val grandchild = fc.createFlow(FlowMeta(name = "grandchild"))
        fc.addNode(grandchild, "BLOCK", JsonObject(emptyMap()))

        val child = fc.createFlow(FlowMeta(name = "child"))
        val ct = fc.addNode(child, "TRIGGER", JsonObject(emptyMap()))
        val cl = fc.addNode(child, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, grandchild) })
        fc.connect(child, ct, 0, cl, 0)

        val parent = fc.createFlow(FlowMeta(name = "parent"))
        val pt = fc.addNode(parent, "TRIGGER", JsonObject(emptyMap()))
        val pl = fc.addNode(parent, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, child) })
        fc.connect(parent, pt, 0, pl, 0)

        val parentRunId = fc.startRun(parent)
        withTimeout(5_000) { grandchildEntered.await() }
        val childRunId = runIdFor(fc, storage, child)
        val grandchildRunId = runIdFor(fc, storage, grandchild)

        fc.stopRun(parentRunId)

        withTimeout(5_000) { grandchildCancelled.await() }
        val parentJob = awaitTerminal(fc, parentRunId)
        val childJob = awaitTerminal(fc, childRunId)
        val grandchildJob = awaitTerminal(fc, grandchildRunId)
        assertEquals(RunJobState.FAILED, parentJob.state)
        assertEquals(RunJobState.FAILED, childJob.state)
        assertEquals(RunJobState.FAILED, grandchildJob.state)
        assertTrue(childJob.error!!.contains("stopped by caller", ignoreCase = true))
        assertTrue(grandchildJob.error!!.contains("stopped by caller", ignoreCase = true))
    }

    @Test
    fun `the depth limit trips on nested lanagers`() = runBlocking {
        val fc = controllerWithLanager(maxDepth = 1)

        // Leaf flow that just triggers.
        val leaf = fc.createFlow(FlowMeta(name = "leaf"))
        fc.addNode(leaf, "TRIGGER", JsonObject(emptyMap()))

        // Mid flow: calls leaf via a lanager (this is nesting level 2 when reached).
        val mid = fc.createFlow(FlowMeta(name = "mid"))
        val mt = fc.addNode(mid, "TRIGGER", JsonObject(emptyMap()))
        val ml = fc.addNode(mid, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, leaf) })
        fc.connect(mid, mt, 0, ml, 0)

        // Top flow: calls mid via a lanager (level 1).
        val top = fc.createFlow(FlowMeta(name = "top"))
        val tt = fc.addNode(top, "TRIGGER", JsonObject(emptyMap()))
        val tl = fc.addNode(top, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, mid) })
        fc.connect(top, tt, 0, tl, 0)

        // maxDepth=1 allows exactly one level of nesting; the second trips.
        val job = awaitTerminal(fc, fc.startRun(top))
        assertEquals(RunJobState.FAILED, job.state)
    }

    @Test
    fun `a self-referential lanager is caught as a cycle`() = runBlocking {
        val fc = controllerWithLanager()
        val loop = fc.createFlow(FlowMeta(name = "loop"))
        val lt = fc.addNode(loop, "TRIGGER", JsonObject(emptyMap()))
        val ll = fc.addNode(loop, "lanager", buildJsonObject { put(LanagerNode.FLOW_ID_KEY, loop) })
        fc.connect(loop, lt, 0, ll, 0)

        val job = awaitTerminal(fc, fc.startRun(loop))
        assertEquals(RunJobState.FAILED, job.state)
        assertTrue(fc.runResult(job.runId)!![ll]!!.error!!.contains("cycle", ignoreCase = true))
    }
}
