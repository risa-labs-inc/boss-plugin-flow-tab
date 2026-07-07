package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Terminal-or-running state of an async [FlowController] run job. */
@Serializable
enum class RunJobState { RUNNING, SUCCEEDED, FAILED }

/**
 * A persisted async run of a flow. [startRun] returns a [runId] immediately (the
 * host's `invoke` is fenced at 60s and a browser DAG can exceed that — red-team F1),
 * and the caller polls [FlowController.runStatus] / [FlowController.runResult]. [nodes]
 * is the per-node status/output snapshot, reusing the same [NodeRunSnap] the UI saves.
 */
@Serializable
data class RunJob(
    val runId: String,
    val tabId: String,
    val state: RunJobState,
    val error: String? = null,
    val nodes: Map<String, NodeRunSnap> = emptyMap(),
) {
    val isTerminal: Boolean get() = state != RunJobState.RUNNING
}

/**
 * Headless, UI-independent authoring + running of flows, seated entirely at the
 * **storage** layer (red-team F5): it reads and patches the `graph:<tabId>`
 * [GraphSnapshot] JSON that a live [FlowTabComponent] loads from — never the private,
 * UI-thread Compose [FlowGraphState]. So an agent (over MCP) can build and run a flow
 * with no tab open; a live tab, if any, re-reads storage on the Main thread.
 *
 * Runs are asynchronous: [startRun] returns a runId and executes the flow on
 * [scope] via the UI-free [FlowExecutor]. Jobs are held in memory and persisted at
 * `run:<runId>` so status/result survive a reload.
 */
class FlowController(
    private val context: PluginContext,
    private val scope: CoroutineScope = context.pluginScope,
    /** Kind-id → spec map used to lay out new nodes and dispatch runs. Threading the
     *  same instance the tab uses keeps tool/agent kinds resolvable. */
    val registry: NodeRegistry = builtinNodeRegistry(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val storage = runCatching {
        context.pluginStorageFactory?.createStorage(STORAGE_NAMESPACE)
    }.getOrNull()
    private val jobs = ConcurrentHashMap<String, RunJob>()

    // ---- authoring (storage-seated) -----------------------------------------

    /** Create an empty flow, persist it at `graph:<tabId>`, and return the new tabId. */
    suspend fun createFlow(meta: FlowMeta? = null): String {
        val tabId = "flow-${UUID.randomUUID()}"
        write(tabId, GraphSnapshot(schemaVersion = SUPPORTED_SCHEMA_VERSION, metadata = meta))
        return tabId
    }

    /**
     * Append a node of [kind] to flow [tabId] and return its id. The kind's
     * [NodeSpec.defaultConfig] is seeded first (so tool nodes keep their cached
     * ref/schema — F4) and [config] merged over it. Title is de-duplicated so
     * title-based `{{ }}` refs stay unambiguous (D3). Throws if the flow is absent.
     */
    suspend fun addNode(tabId: String, kind: String, config: JsonObject = JsonObject(emptyMap())): String {
        val snap = getFlow(tabId) ?: throw IllegalArgumentException("No flow '$tabId'")
        val spec = registry.resolve(kind)
        val nodeId = "n${snap.nextId}"
        val title = uniqueTitle(spec.label, snap.nodes.map { it.title }.toSet())
        val idx = snap.nodes.size
        val node = NodeModel(
            id = nodeId,
            type = spec.id,
            title = title,
            x = 320f + idx * (nodeOuterWidth() + 120f),
            y = 200f,
            config = JsonObject(spec.defaultConfig + config),
        )
        write(tabId, snap.copy(nodes = snap.nodes + node, nextId = snap.nextId + 1))
        return nodeId
    }

    /**
     * Wire output [fromPort] of node [from] into input [toPort] of node [to], returning
     * the new edge id. Rejects self-connections, unknown endpoints, and exact
     * duplicates (mirroring [FlowGraphState.connect]). Throws if the flow is absent.
     */
    suspend fun connect(tabId: String, from: String, fromPort: Int, to: String, toPort: Int): String {
        val snap = getFlow(tabId) ?: throw IllegalArgumentException("No flow '$tabId'")
        require(from != to) { "cannot connect a node to itself" }
        val ids = snap.nodes.map { it.id }.toSet()
        require(from in ids) { "unknown source node '$from'" }
        require(to in ids) { "unknown target node '$to'" }
        require(snap.edges.none { it.fromNode == from && it.fromPort == fromPort && it.toNode == to && it.toPort == toPort }) {
            "duplicate edge"
        }
        val edgeId = "e${snap.nextId}"
        write(tabId, snap.copy(edges = snap.edges + EdgeModel(edgeId, from, fromPort, to, toPort), nextId = snap.nextId + 1))
        return edgeId
    }

    /** The [GraphSnapshot] for [tabId], or null if absent/corrupt. */
    suspend fun getFlow(tabId: String): GraphSnapshot? {
        val raw = storage?.getJson(graphKey(tabId)) ?: return null
        return runCatching { json.decodeFromString(GraphSnapshot.serializer(), raw) }.getOrNull()
    }

    /** Ids of every flow persisted in storage (UI- or controller-authored). */
    suspend fun listFlows(): List<String> =
        storage?.getAllKeys().orEmpty()
            .filter { it.startsWith(GRAPH_PREFIX) }
            .map { it.removePrefix(GRAPH_PREFIX) }
            .sorted()

    // ---- async run jobs (F1) ------------------------------------------------

    /**
     * Launch flow [tabId] on [scope] and return a runId immediately. The run drives
     * the headless [FlowExecutor]; poll [runStatus]/[runResult] for progress. A missing
     * flow or an executor throw becomes a [RunJobState.FAILED] job (never a crash); a
     * run in which any node errors is FAILED too, but always reaches a terminal state.
     */
    fun startRun(tabId: String): String {
        val runId = "run-${UUID.randomUUID()}"
        jobs[runId] = RunJob(runId, tabId, RunJobState.RUNNING)
        scope.launch(Dispatchers.Default) {
            val job = runCatching {
                val snap = getFlow(tabId) ?: throw IllegalStateException("No flow '$tabId'")
                val plan = snap.nodes.map { PlanNode(it.id, it.type, it.title, it.config) }
                val states = ConcurrentHashMap<String, NodeRun>()
                FlowExecutor(context, registry).run(plan, snap.edges) { id, r -> states[id] = r }
                val firstError = states.values.firstOrNull { it.status == RunStatus.ERROR }
                RunJob(
                    runId = runId,
                    tabId = tabId,
                    state = if (firstError != null) RunJobState.FAILED else RunJobState.SUCCEEDED,
                    error = firstError?.error,
                    nodes = states.toRunSnapshot().states,
                )
            }.getOrElse { RunJob(runId, tabId, RunJobState.FAILED, it.message ?: it.toString()) }
            jobs[runId] = job
            runCatching { storage?.putJson(runKey(runId), json.encodeToString(RunJob.serializer(), job)) }
        }
        return runId
    }

    /** Current job for [runId] (state + error), or null if unknown. */
    fun runStatus(runId: String): RunJob? = jobs[runId]

    /** Per-node outputs for [runId], or null if unknown. */
    fun runResult(runId: String): Map<String, NodeRunSnap>? = jobs[runId]?.nodes

    // ---- internals ----------------------------------------------------------

    private suspend fun write(tabId: String, snapshot: GraphSnapshot) {
        storage?.putJson(graphKey(tabId), json.encodeToString(GraphSnapshot.serializer(), snapshot))
    }

    private fun uniqueTitle(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    private fun graphKey(tabId: String) = "$GRAPH_PREFIX$tabId"
    private fun runKey(runId: String) = "$RUN_PREFIX$runId"

    companion object {
        const val STORAGE_NAMESPACE = "ai.rever.boss.plugin.dynamic.flowtab"
        const val GRAPH_PREFIX = "graph:"
        const val RUN_PREFIX = "run:"
    }
}
