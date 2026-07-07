package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** In-memory [PluginStorageProvider] — only the JSON/key surface is real. */
    private class FakeStorage : PluginStorageProvider {
        val map = ConcurrentHashMap<String, String>()
        override fun getPluginId() = "test"
        override suspend fun putJson(key: String, jsonValue: String) { map[key] = jsonValue }
        override suspend fun getJson(key: String): String? = map[key]
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun getAllKeys(): Set<String> = map.keys.toSet()
        override suspend fun clear() { map.clear() }
        override suspend fun putString(key: String, value: String) { map[key] = value }
        override suspend fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue
        override suspend fun putInt(key: String, value: Int) {}
        override suspend fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override suspend fun putLong(key: String, value: Long) {}
        override suspend fun getLong(key: String, defaultValue: Long): Long = defaultValue
        override suspend fun putBoolean(key: String, value: Boolean) {}
        override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override suspend fun putFloat(key: String, value: Float) {}
        override suspend fun getFloat(key: String, defaultValue: Float): Float = defaultValue
        override fun observeString(key: String): Flow<String> = emptyFlow()
        override fun observeChanges(): Flow<String> = emptyFlow()
    }

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

    private fun controller(storage: PluginStorageProvider = FakeStorage()) =
        FlowController(context(storage), scope)

    // ---- authoring ----------------------------------------------------------

    @Test
    fun `createFlow seeds a v2 snapshot at graph colon tabId`() = runBlocking {
        val storage = FakeStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow(FlowMeta(name = "Router", description = "test"))
        assertTrue(tabId.startsWith("flow-"))
        assertTrue(storage.map.containsKey("graph:$tabId"))
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
    fun `startRun on an unknown flow fails the job`() = runBlocking {
        val fc = controller()
        val runId = fc.startRun("flow-missing")
        val job = awaitTerminal(fc, runId)
        assertEquals(RunJobState.FAILED, job.state)
        assertNotNull(job.error)
    }

    @Test
    fun `a node error fails the run but reaches a terminal state`() = runBlocking {
        val fc = controller()
        val tabId = fc.createFlow()
        val trig = fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val gone = fc.addNode(tabId, "tool:boss:gone", JsonObject(emptyMap())) // unavailable
        fc.connect(tabId, trig, 0, gone, 0)
        val job = awaitTerminal(fc, fc.startRun(tabId))
        assertEquals(RunJobState.FAILED, job.state)
        assertEquals(RunStatus.ERROR, fc.runResult(job.runId)!![gone]!!.status)
    }

    @Test
    fun `run jobs are persisted to storage`() = runBlocking {
        val storage = FakeStorage()
        val fc = controller(storage)
        val tabId = fc.createFlow()
        fc.addNode(tabId, "TRIGGER", JsonObject(emptyMap()))
        val runId = fc.startRun(tabId)
        awaitTerminal(fc, runId)
        assertTrue(storage.map.keys.any { it == "run:$runId" })
    }
}
