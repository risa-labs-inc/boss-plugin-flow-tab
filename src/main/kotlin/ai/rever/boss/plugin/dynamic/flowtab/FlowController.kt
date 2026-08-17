package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** Lightweight discovery record used by the launcher and detailed MCP listing. */
@Serializable
data class FlowSummary(
    val tabId: String,
    val name: String = "",
    val description: String = "",
    val nodeCount: Int = 0,
    val readable: Boolean = true,
)

/**
 * Headless, UI-independent authoring + running of flows, seated entirely at the
 * **storage** layer (red-team F5): it reads and patches the `graph:<tabId>`
 * [GraphSnapshot] JSON that a live [FlowTabComponent] loads from — never the private,
 * UI-thread Compose [FlowGraphState]. So an agent (over MCP) can build and run a flow
 * with no tab open; a live tab, if any, re-reads storage on the Main thread.
 *
 * Runs are asynchronous: [startRun] returns a runId and executes the flow on
 * [scopeProvider] via the UI-free [FlowExecutor]. Jobs are held in memory and persisted at
 * `run:<runId>` so status/result survive a reload.
 */
class FlowController(
    private val context: PluginContext,
    /** Resolve the scope at dispatch time. A sandbox watchdog restart replaces
     * [PluginContext.pluginScope], while the UI supplies its stable tab scope. */
    private val scopeProvider: () -> CoroutineScope = { context.pluginScope },
    /** Kind-id → spec map used to lay out new nodes and dispatch runs. Threading the
     *  same instance the tab uses keeps tool/agent kinds resolvable. */
    val registry: NodeRegistry = builtinNodeRegistry(),
    /** Hard ceiling for a headless run. An independent monitor publishes FAILED at
     *  this deadline even when a node is stuck in a non-cooperative host call. */
    private val runTimeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val storage = runCatching {
        context.pluginStorageFactory?.createStorage(STORAGE_NAMESPACE)
    }.getOrNull()
    private val jobs = ConcurrentHashMap<String, RunJob>()
    private val persistMutex = Mutex()
    /** Independent from pluginScope so it can observe that scope being replaced, but
     * still explicitly owned by this controller and cancelled from [dispose]. */
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * title-based `{{ }}` refs stay unambiguous (D3). Throws if the flow is absent
     * or [kind] is not currently registered. Dynamic `tool:*` kinds synchronize
     * asynchronously, so their rejection message tells callers that retrying may help.
     */
    suspend fun addNode(tabId: String, kind: String, config: JsonObject = JsonObject(emptyMap())): String {
        val snap = getFlow(tabId) ?: throw IllegalArgumentException("No flow '$tabId'")
        // Saved graphs still resolve missing kinds to placeholders so they round-trip,
        // but new authoring requests must not create nodes that can never execute.
        val spec = requireNotNull(registry[kind]) { unknownKindMessage(kind) }
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
            // Desktop storage enumerates the physical key written by putJson
            // (`json:graph:<tabId>`), while other providers may expose the logical
            // key (`graph:<tabId>`). Normalize both forms before filtering.
            .map { it.removePrefix(JSON_STORAGE_PREFIX) }
            .filter { it.startsWith(GRAPH_PREFIX) }
            .map { it.removePrefix(GRAPH_PREFIX) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    /**
     * Metadata for every discovered graph key. A corrupt graph remains represented
     * with [FlowSummary.readable] false so the launcher does not silently hide data.
     */
    // Discovery intentionally reads each graph: storage has no secondary summary index yet.
    suspend fun listFlowDetails(): List<FlowSummary> = listFlows().map { tabId ->
        val snapshot = getFlow(tabId)
        FlowSummary(
            tabId = tabId,
            name = snapshot?.metadata?.name.orEmpty(),
            description = snapshot?.metadata?.description.orEmpty(),
            nodeCount = snapshot?.nodes?.size ?: 0,
            readable = snapshot != null,
        )
    }

    /** Rename a readable flow without changing any other metadata or graph content. */
    suspend fun renameFlow(tabId: String, name: String): FlowSummary {
        val snapshot = getFlow(tabId) ?: throw IllegalArgumentException("No readable flow '$tabId'")
        return persistRenamedFlow(tabId, name, snapshot)
    }

    /**
     * Persist (and, when necessary, create) the currently open graph from its live
     * canvas snapshot with a new name. This is deliberately internal to the tab UI;
     * storage-seated callers must use [renameFlow] so a stale supplied snapshot cannot
     * replace a saved graph.
     */
    internal suspend fun renameOpenFlow(tabId: String, name: String, snapshot: GraphSnapshot): FlowSummary =
        persistRenamedFlow(tabId, name, snapshot)

    private suspend fun persistRenamedFlow(
        tabId: String,
        name: String,
        snapshot: GraphSnapshot,
    ): FlowSummary {
        check(storage != null) { "Flow storage is unavailable" }
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Flow name cannot be blank" }
        require(normalizedName.length <= MAX_FLOW_NAME_LENGTH) {
            "Flow name cannot exceed $MAX_FLOW_NAME_LENGTH characters"
        }
        val metadata = (snapshot.metadata ?: FlowMeta()).copy(name = normalizedName)
        // Once the user confirms, tab close/split recomposition must not cancel the
        // durable write. Cancellation is observed again by the title-refresh step.
        withContext(NonCancellable + Dispatchers.IO) {
            FlowRenameCoordinator.withFlowLock(tabId) {
                write(tabId, snapshot.copy(metadata = metadata))
                // Publish only after the storage write succeeds. Open tabs replay this name
                // into their live state, and their autosave consults it under the same lock.
                FlowRenameCoordinator.publish(tabId, normalizedName)
            }
        }

        context.tabUpdateProviderFactory?.let { factory ->
            try {
                withContext(Dispatchers.Main.immediate) {
                    factory.createProvider(tabId, FlowTabType.typeId)?.updateTitle(normalizedName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // The graph has already been renamed successfully. A host tab-title
                // refresh failure must not report the whole rename as failed and make
                // the live metadata diverge from the persisted snapshot.
                println("[flow-tab] renamed '$tabId', but its tab title could not be refreshed: ${failure.message}")
            }
        }
        return FlowSummary(
            tabId = tabId,
            name = normalizedName,
            description = metadata.description,
            nodeCount = snapshot.nodes.size,
        )
    }

    /**
     * Permanently delete [tabId], including its UI run-state snapshot. Corrupt graphs
     * are deletable because existence is checked without decoding the snapshot. Any
     * live tabs with the same id are closed first so their autosave cannot recreate
     * the graph after deletion. Returns false when the graph key does not exist.
     */
    suspend fun deleteFlow(tabId: String): Boolean {
        val store = storage ?: throw IllegalStateException("Flow storage is unavailable")
        if (store.getJson(graphKey(tabId)) == null) return false

        closeOpenFlowTabs(tabId)
        withContext(NonCancellable) {
            store.removeJsonValue(graphKey(tabId))
            store.removeJsonValue("$RUN_STATE_PREFIX$tabId")
        }
        FlowRenameCoordinator.forget(tabId)
        return true
    }

    // ---- async run jobs (F1) ------------------------------------------------

    /**
     * Launch flow [tabId] on [scopeProvider] and return a runId immediately. The run drives
     * the headless [FlowExecutor]; poll [runStatus]/[runResult] for progress. A missing
     * flow or an executor throw becomes a [RunJobState.FAILED] job (never a crash); a
     * run in which any node errors is FAILED too, but always reaches a terminal state.
     */
    fun startRun(tabId: String, depth: Int = 0, ancestry: Set<String> = emptySet()): String {
        val runId = "run-${UUID.randomUUID()}"
        jobs[runId] = RunJob(runId, tabId, RunJobState.RUNNING)
        val states = ConcurrentHashMap<String, NodeRun>()
        val execution = scopeProvider().launch(Dispatchers.Default) {
            // Persist RUNNING before executing so a reload can still diagnose an in-flight run.
            persistRun(jobs[runId] ?: RunJob(runId, tabId, RunJobState.RUNNING))
            val candidate = try {
                val snap = getFlow(tabId) ?: throw IllegalStateException("No flow '$tabId'")
                val plan = snap.nodes.map { PlanNode(it.id, it.type, it.title, it.config) }
                // This flow is now on the call stack: a nested lanager pointing back at it
                // is a cycle. Depth is threaded so the nesting bound can be enforced.
                FlowExecutor(context, registry).run(
                    plan, snap.edges, depth = depth, ancestry = ancestry + tabId,
                ) { id, r ->
                    states[id] = r
                    // flow_result must be a non-blocking snapshot even while the
                    // run is active. Do not let late output overwrite a watchdog result.
                    jobs.computeIfPresent(runId) { _, current ->
                        if (current.state == RunJobState.RUNNING) {
                            current.copy(nodes = states.toRunSnapshot().states)
                        } else {
                            current
                        }
                    }
                    // Serialize storage writes through persistRun so a delayed live
                    // snapshot can never overwrite a terminal watchdog verdict.
                    monitorScope.launch { jobs[runId]?.let { persistRun(it) } }
                }
                val firstError = states.values.firstOrNull { it.status == RunStatus.ERROR }
                RunJob(
                    runId = runId,
                    tabId = tabId,
                    state = if (firstError != null) RunJobState.FAILED else RunJobState.SUCCEEDED,
                    error = firstError?.error,
                    nodes = states.toRunSnapshot().states,
                )
            } catch (cancelled: CancellationException) {
                val message = cancelled.message?.let { "Flow run cancelled: $it" }
                    ?: "Flow run cancelled before completion"
                failedRun(runId, tabId, states, message)
            } catch (t: Throwable) {
                failedRun(runId, tabId, states, t.message ?: t.toString())
            }
            val published = publishTerminalIfRunning(runId, candidate)
            persistRun(published)
        }

        // This monitor is deliberately not a child of execution. join() is cancellable,
        // so its timeout fires even if execution is stuck in a non-suspending host call.
        monitorScope.launch {
            val completed = withTimeoutOrNull(runTimeoutMs) {
                execution.join()
                true
            } == true
            if (!completed) {
                val message = "Flow exceeded its ${runTimeoutMs}ms run timeout"
                transitionToFailed(runId, tabId, states, message)?.let { persistRun(it) }
                execution.cancel(CancellationException(message))
            } else {
                // Covers launch() against an already-cancelled scope and any unexpected
                // throw that escaped execution before it could publish a terminal result.
                val message = if (execution.isCancelled) {
                    "Flow run cancelled before dispatch"
                } else {
                    "Flow run ended without publishing a result"
                }
                transitionToFailed(runId, tabId, states, message)?.let { persistRun(it) }
            }
        }
        return runId
    }

    /** Release independent run monitors when the owning tab/plugin is destroyed. */
    fun dispose() {
        monitorScope.cancel()
    }

    private fun publishTerminalIfRunning(runId: String, candidate: RunJob): RunJob =
        jobs.computeIfPresent(runId) { _, current ->
            if (current.state == RunJobState.RUNNING) candidate else current
        } ?: candidate

    private fun transitionToFailed(
        runId: String,
        tabId: String,
        states: ConcurrentHashMap<String, NodeRun>,
        message: String,
    ): RunJob? {
        var transitioned: RunJob? = null
        jobs.computeIfPresent(runId) { _, current ->
            if (current.state == RunJobState.RUNNING) {
                failedRun(runId, tabId, states, message).also { transitioned = it }
            } else {
                current
            }
        }
        return transitioned
    }

    private suspend fun persistRun(job: RunJob) {
        withContext(NonCancellable) {
            persistMutex.withLock {
                runCatching {
                    // Always serialize the newest in-memory snapshot. Coroutine
                    // scheduling may otherwise let an older live write run last.
                    val safeJob = jobs[job.runId] ?: job
                    storage?.putJson(runKey(job.runId), json.encodeToString(RunJob.serializer(), safeJob))
                }
            }
        }
    }

    /**
     * Current job for [runId], or null if unknown. Falls back to the persisted
     * `run:<runId>` blob when the in-memory map has no entry (e.g. after a plugin
     * reload), so advertised durability is real (red-team S2), then re-caches it.
     */
    suspend fun runStatus(runId: String): RunJob? =
        jobs[runId] ?: loadJob(runId)?.also { jobs[runId] = it }

    /** Per-node outputs for [runId] (in-memory or read back from storage), or null. */
    suspend fun runResult(runId: String): Map<String, NodeRunSnap>? = runStatus(runId)?.nodes

    private suspend fun loadJob(runId: String): RunJob? {
        val raw = storage?.getJson(runKey(runId)) ?: return null
        val loaded = runCatching { json.decodeFromString(RunJob.serializer(), raw) }.getOrNull()
            ?: return null
        if (loaded.state != RunJobState.RUNNING) return loaded

        // An in-memory monitor is the only owner capable of completing a RUNNING job.
        // Reaching storage fallback means that owner was lost during a plugin reload.
        val message = "Flow run did not survive plugin reload"
        val savedNodes = loaded.nodes.mapValues { (_, node) ->
            if (node.status == RunStatus.RUNNING) {
                node.copy(status = RunStatus.ERROR, error = message)
            } else {
                node
            }
        }
        val notStarted = "Skipped — run ended during plugin reload"
        val graphNodes = getFlow(loaded.tabId)?.nodes.orEmpty().associate { node ->
            node.id to (
                savedNodes[node.id] ?: NodeRunSnap(
                    status = RunStatus.SKIPPED,
                    error = message,
                    logs = listOf(notStarted),
                    skipReason = notStarted,
                )
            )
        }
        val failed = loaded.copy(
            state = RunJobState.FAILED,
            error = message,
            nodes = graphNodes + savedNodes,
        )
        return failed
    }

    // ---- internals ----------------------------------------------------------

    private suspend fun write(tabId: String, snapshot: GraphSnapshot) {
        // TODO: Controller read-modify-write authoring operations are not yet serialized
        // against full-snapshot UI autosaves; the rename path coordinates explicitly.
        storage?.putJson(graphKey(tabId), json.encodeToString(GraphSnapshot.serializer(), snapshot))
    }

    private fun failedRun(
        runId: String,
        tabId: String,
        states: ConcurrentHashMap<String, NodeRun>,
        message: String,
    ): RunJob {
        val terminalNodes = states.toRunSnapshot().states.mapValues { (_, node) ->
            if (node.status == RunStatus.RUNNING) {
                node.copy(status = RunStatus.ERROR, error = message)
            } else {
                node
            }
        }
        return RunJob(
            runId = runId,
            tabId = tabId,
            state = RunJobState.FAILED,
            error = message,
            nodes = terminalNodes,
        )
    }

    private fun uniqueTitle(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base $n" in taken) n++
        return "$base $n"
    }

    private fun unknownKindMessage(kind: String): String {
        val registered = registry.all().map(NodeSpec::id).sorted()
        // For dynamic tools, spend the capped error budget on the relevant source
        // namespace (tool:boss: or tool:ext:) instead of unrelated built-ins.
        val namespace = kind.dynamicToolNamespace()
        val relevant = namespace
            ?.let { prefix -> registered.filter { it.startsWith(prefix) } }
            .orEmpty()
            .ifEmpty { registered }
        val shown = relevant.take(MAX_KINDS_IN_ERROR)
        val remainder = relevant.size - shown.size
        val suffix = if (remainder > 0) ", … and $remainder more" else ""
        val syncHint = if (namespace != null) {
            val registeredToolCount = registered.count { it.startsWith("tool:") }
            " Dynamic tool kinds may still be synchronizing " +
                "($registeredToolCount tool kinds currently registered); retry shortly."
        } else {
            ""
        }
        return "Unknown node kind '$kind'. Valid kinds: ${shown.joinToString(", ")}$suffix.$syncHint"
    }

    private fun String.dynamicToolNamespace(): String? {
        if (!startsWith("tool:")) return null
        val secondColon = indexOf(':', startIndex = "tool:".length)
        return if (secondColon >= 0) substring(0, secondColon + 1) else "tool:"
    }

    private suspend fun closeOpenFlowTabs(tabId: String) {
        val activeTabs = context.activeTabsProvider ?: return
        activeTabs.refreshTabs()
        val openCount = activeTabs.activeTabs.value.count { it.tabId == tabId }
        repeat(openCount) {
            val closed = withContext(Dispatchers.Main.immediate) { activeTabs.closeTab(tabId) }
            check(closed) { "Could not close open flow tab '$tabId'; deletion was cancelled" }
        }
    }

    private fun graphKey(tabId: String) = "$GRAPH_PREFIX$tabId"
    private fun runKey(runId: String) = "$RUN_PREFIX$runId"

    companion object {
        private const val MAX_KINDS_IN_ERROR = 30
        const val MAX_FLOW_NAME_LENGTH = 100
        const val STORAGE_NAMESPACE = "ai.rever.boss.plugin.dynamic.flowtab"
        const val GRAPH_PREFIX = "graph:"
        const val RUN_PREFIX = "run:"
        const val DEFAULT_RUN_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/**
 * Assemble the headless [FlowController] used by the MCP authoring path with the SAME
 * node kinds a UI tab has: built-ins + host (boss) registry tools + agent + lanager +
 * external MCP tools. This is the single wiring point (red-team S1): previously the
 * plugin built a controller that registered agent/lanager/external but never called
 * [syncBossTools], so `flow_run` on a `tool:boss:*` node authored over MCP failed with
 * "Unknown node kind" while the identical UI-authored flow ran fine. Keep every kind the
 * UI can resolve resolvable here too.
 */
fun buildHeadlessController(
    context: PluginContext,
    prompts: PromptRegistry,
    external: ExternalMcpManager?,
    scope: CoroutineScope? = null,
): FlowController {
    val initialScope = scope ?: context.pluginScope
    val scopeProvider: () -> CoroutineScope = { scope ?: context.pluginScope }
    val controller = FlowController(
        context = context,
        scopeProvider = scopeProvider,
    )
    // Host tools -> tool:boss:* kinds (the fix): reactively synced onto this registry.
    syncBossTools(context, controller.registry, initialScope)
    // Make agent + lanager kinds runnable in headless (MCP-driven) runs too, sharing
    // the controller's registry so a lanager's sub-run resolves the same kinds.
    controller.registry.register(defaultAgentNodeSpec(context, prompts, external))
    controller.registry.register(lanagerNodeSpec(controller))
    // Surface external MCP tools (flag-gated inside) as tool:ext:<server>/* kinds.
    external?.let { syncExternalMcpTools(it, controller.registry, initialScope) }
    return controller
}
