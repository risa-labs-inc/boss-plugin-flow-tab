package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    val startedAtMs: Long = 0L,
    val nodeCount: Int = 0,
    val error: String? = null,
    val nodes: Map<String, NodeRunSnap> = emptyMap(),
    /** False only for a scrubbed in-process progress snapshot; durable jobs default to complete. */
    val contentComplete: Boolean = true,
) {
    val isTerminal: Boolean get() = state != RunJobState.RUNNING
}

/** Bounded run-history record returned by `flow_runs` and rendered by the canvas. */
@Serializable
data class RunSummary(
    val runId: String,
    val state: RunJobState,
    val startedAtMs: Long,
    val nodeCount: Int,
)

/** Lightweight discovery record used by the launcher and detailed MCP listing. */
@Serializable
data class FlowSummary(
    val tabId: String,
    val name: String = "",
    val description: String = "",
    val nodeCount: Int = 0,
    val readable: Boolean = true,
    val schedule: FlowSchedule? = null,
    val lastScheduledRunAtEpochMs: Long? = null,
    val nextScheduledRunAtEpochMs: Long? = null,
    val lastScheduledRunState: RunJobState? = null,
)

/** Durable scheduler cursor. Graph metadata remains the source of schedule configuration. */
@Serializable
data class FlowScheduleState(
    val tabId: String,
    val intervalMinutes: Long,
    val lastRunAtEpochMs: Long? = null,
    val nextRunAtEpochMs: Long? = null,
    val lastRunId: String? = null,
    val lastRunState: RunJobState? = null,
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val storage = runCatching {
        context.pluginStorageFactory?.createStorage(STORAGE_NAMESPACE)
    }.getOrNull()
    private val jobs = ConcurrentHashMap<String, RunJob>()
    private val ownedRunIds = ConcurrentHashMap.newKeySet<String>()
    private val executions = ConcurrentHashMap<String, Job>()
    private val runStates = ConcurrentHashMap<String, ConcurrentHashMap<String, NodeRun>>()
    /** Prevent duplicate dispatch if persisting a newly-started scheduler cursor fails. */
    private val scheduledExecutions = ConcurrentHashMap<String, String>()
    private val scheduleFailureTypes = ConcurrentHashMap<String, String>()
    private val invalidScheduleIntervals = ConcurrentHashMap<String, Long>()
    private val persistMutex = Mutex()
    private val scheduleSweepMutex = Mutex()
    private val schedulePassMutex = Mutex()
    /** Independent from pluginScope so controller-owned work survives a sandbox watchdog
     * replacing that scope. Everything launched here is cancelled from [dispose]. */
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toolSyncLock = Any()
    private var bossToolSyncJob: Job? = null
    private var externalToolSyncJob: Job? = null
    private var scheduleJob: Job? = null
    private var toolSyncJobs: List<Job> = emptyList()
    private var disposed = false

    /**
     * Keep the headless registry synchronized for this controller's whole lifetime.
     * These collectors deliberately do not belong to [scopeProvider]: that scope may be
     * replaced by the host while the controller and its registry remain registered. The
     * Boss and external jobs are memoized independently, so either missing source remains
     * retryable without duplicating the collector that did start. The first external start
     * fixes that manager for this controller; calls that start nothing return the same jobs.
     */
    internal fun startToolRegistrySync(external: ExternalMcpManager?): List<Job> {
        return synchronized(toolSyncLock) {
            check(!disposed) { "Cannot start tool registry synchronization after controller disposal" }
            val previousBoss = bossToolSyncJob
            val previousExternal = externalToolSyncJob
            val previousJobs = toolSyncJobs
            try {
                if (bossToolSyncJob == null) {
                    bossToolSyncJob = syncBossTools(context, registry, lifecycleScope)
                }
                if (externalToolSyncJob == null && external != null) {
                    externalToolSyncJob = syncExternalMcpTools(external, registry, lifecycleScope)
                }
                val jobs = listOfNotNull(bossToolSyncJob, externalToolSyncJob)
                if (jobs != toolSyncJobs) toolSyncJobs = jobs
                toolSyncJobs
            } catch (failure: Throwable) {
                if (bossToolSyncJob !== previousBoss) bossToolSyncJob?.cancel()
                if (externalToolSyncJob !== previousExternal) externalToolSyncJob?.cancel()
                bossToolSyncJob = previousBoss
                externalToolSyncJob = previousExternal
                toolSyncJobs = previousJobs
                throw failure
            }
        }
    }

    // ---- authoring (storage-seated) -----------------------------------------

    /** Create an empty flow, persist it at `graph:<tabId>`, and return the new tabId. */
    suspend fun createFlow(meta: FlowMeta? = null): String {
        val tabId = "flow-${UUID.randomUUID()}"
        writeUnlocked(tabId, GraphSnapshot(schemaVersion = SUPPORTED_SCHEMA_VERSION, metadata = meta))
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
        return FlowPersistenceCoordinator.withFlowLock(tabId) {
            val snap = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No flow '$tabId'")
            // Saved graphs still resolve missing kinds to placeholders so they round-trip,
            // but new authoring requests must not create nodes that can never execute.
            val spec = requireNotNull(registry[kind]) { unknownKindMessage(kind) }
            val nodeId = "n${snap.nextId}"
            val title = uniqueTitle(spec.label, snap.nodes.map { it.title }.toSet())
            val position = collisionFreeNodePosition(
                existing = snap.nodes.map { existing ->
                    LayoutNode(
                        id = existing.id,
                        height = nodeHeight(registry.resolve(existing.type)),
                        x = existing.x,
                        y = existing.y,
                    )
                },
                newNodeHeight = nodeHeight(spec),
            )
            val node = NodeModel(
                id = nodeId,
                type = spec.id,
                title = title,
                x = position.x,
                y = position.y,
                config = JsonObject(spec.defaultConfig + config),
            )
            val updated = snap.copy(nodes = snap.nodes + node, nextId = snap.nextId + 1)
            writeUnlocked(tabId, updated)
            FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
            nodeId
        }
    }

    /**
     * Wire output [fromPort] of node [from] into input [toPort] of node [to], returning
     * the new edge id. Rejects self-connections, unknown endpoints, and exact
     * duplicates (mirroring [FlowGraphState.connect]). Throws if the flow is absent.
     */
    suspend fun connect(tabId: String, from: String, fromPort: Int, to: String, toPort: Int): String {
        return FlowPersistenceCoordinator.withFlowLock(tabId) {
            val snap = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No flow '$tabId'")
            require(from != to) { "cannot connect a node to itself" }
            val ids = snap.nodes.map { it.id }.toSet()
            require(from in ids) { "unknown source node '$from'" }
            require(to in ids) { "unknown target node '$to'" }
            require(snap.edges.none { it.fromNode == from && it.fromPort == fromPort && it.toNode == to && it.toPort == toPort }) {
                "duplicate edge"
            }
            val edgeId = "e${snap.nextId}"
            val updated = snap.copy(
                edges = snap.edges + EdgeModel(edgeId, from, fromPort, to, toPort),
                nextId = snap.nextId + 1,
            )
            writeUnlocked(tabId, updated)
            FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
            edgeId
        }
    }

    /**
     * Patch a node's title and/or config without changing its kind or position. Config
     * keys are merged over the existing config so an MCP caller can correct one field
     * without first reproducing every default and secret-backed field.
     */
    suspend fun updateNode(
        tabId: String,
        nodeId: String,
        title: String? = null,
        configPatch: JsonObject? = null,
    ): NodeModel {
        require(title != null || configPatch != null) { "provide 'title' and/or 'config'" }
        val normalizedTitle = title?.trim()?.also {
            require(it.isNotEmpty()) { "Node title cannot be blank" }
            require(it.length <= MAX_NODE_TITLE_LENGTH) {
                "Node title cannot exceed $MAX_NODE_TITLE_LENGTH characters"
            }
        }
        return FlowPersistenceCoordinator.withFlowLock(tabId) {
            val snap = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No flow '$tabId'")
            val index = snap.nodes.indexOfFirst { it.id == nodeId }
            require(index >= 0) { "unknown node '$nodeId'" }
            if (normalizedTitle != null) {
                require(snap.nodes.none { it.id != nodeId && it.title == normalizedTitle }) {
                    "node title '$normalizedTitle' is already in use"
                }
            }
            val current = snap.nodes[index]
            val updatedNode = current.copy(
                title = normalizedTitle ?: current.title,
                config = configPatch?.let { JsonObject(current.config + it) } ?: current.config,
            )
            val updatedNodes = snap.nodes.toMutableList().also { it[index] = updatedNode }
            val updated = snap.copy(nodes = updatedNodes)
            writeUnlocked(tabId, updated)
            FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
            updatedNode
        }
    }

    /** Delete a node and all of its incident edges, returning the number of removed edges. */
    suspend fun deleteNode(tabId: String, nodeId: String): Int {
        return FlowPersistenceCoordinator.withFlowLock(tabId) {
            val snap = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No flow '$tabId'")
            require(snap.nodes.any { it.id == nodeId }) { "unknown node '$nodeId'" }
            val keptEdges = snap.edges.filterNot { it.fromNode == nodeId || it.toNode == nodeId }
            val removedEdgeCount = snap.edges.size - keptEdges.size
            val updated = snap.copy(
                nodes = snap.nodes.filterNot { it.id == nodeId },
                edges = keptEdges,
            )
            writeUnlocked(tabId, updated)
            FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
            removedEdgeCount
        }
    }

    /** Delete one edge by id. */
    suspend fun deleteEdge(tabId: String, edgeId: String) {
        FlowPersistenceCoordinator.withFlowLock(tabId) {
            val snap = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No flow '$tabId'")
            require(snap.edges.any { it.id == edgeId }) { "unknown edge '$edgeId'" }
            val updated = snap.copy(edges = snap.edges.filterNot { it.id == edgeId })
            writeUnlocked(tabId, updated)
            FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
        }
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
        val schedule = snapshot?.metadata?.schedule
        val scheduleState = if (schedule != null) loadScheduleState(tabId) else null
        FlowSummary(
            tabId = tabId,
            name = snapshot?.metadata?.name.orEmpty(),
            description = snapshot?.metadata?.description.orEmpty(),
            nodeCount = snapshot?.nodes?.size ?: 0,
            readable = snapshot != null,
            schedule = schedule,
            lastScheduledRunAtEpochMs = scheduleState?.lastRunAtEpochMs,
            nextScheduledRunAtEpochMs = scheduleState?.nextRunAtEpochMs,
            lastScheduledRunState = scheduleState?.lastRunState,
        )
    }

    /**
     * Set a fixed-interval schedule, or disable scheduling with null. The graph update is
     * coordinated with open-canvas autosave, while the durable cursor is reset from now
     * so changing a cadence can never cause an immediate stale-time invocation.
     */
    suspend fun updateSchedule(
        tabId: String,
        intervalMinutes: Long?,
        nowEpochMs: Long = nowMillis(),
    ): FlowSummary {
        intervalMinutes?.let {
            require(it in MIN_SCHEDULE_INTERVAL_MINUTES..MAX_SCHEDULE_INTERVAL_MINUTES) {
                "Schedule interval must be between $MIN_SCHEDULE_INTERVAL_MINUTES and " +
                    "$MAX_SCHEDULE_INTERVAL_MINUTES minutes"
            }
        }
        return schedulePassMutex.withLock {
            check(storage != null) { "Flow storage is unavailable" }
            val updated = FlowPersistenceCoordinator.withFlowLock(tabId) {
                val current = FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                    ?: getFlow(tabId)
                    ?: throw IllegalArgumentException("No readable flow '$tabId'")
                val metadata = (current.metadata ?: FlowMeta()).copy(
                    schedule = intervalMinutes?.let(::FlowSchedule),
                )
                current.copy(metadata = metadata).also { snapshot ->
                    writeUnlocked(tabId, snapshot)
                    FlowPersistenceCoordinator.publishGraphUpdate(tabId, snapshot)
                }
            }
            val prior = loadScheduleState(tabId)
            // A cadence edit resets the durable cursor. Do not let an old in-memory
            // dispatch overwrite that new phase on the next reconciliation pass.
            scheduledExecutions.remove(tabId)
            scheduleFailureTypes.remove(tabId)
            invalidScheduleIntervals.remove(tabId)
            val nextRunAt = intervalMinutes?.let { nowEpochMs + intervalMillis(it) }
            if (intervalMinutes == null) {
                storage.removeJsonValue(scheduleKey(tabId))
            } else {
                persistScheduleState(
                    FlowScheduleState(
                        tabId = tabId,
                        intervalMinutes = intervalMinutes,
                        lastRunAtEpochMs = prior?.lastRunAtEpochMs,
                        nextRunAtEpochMs = nextRunAt,
                        lastRunId = prior?.lastRunId,
                        lastRunState = prior?.lastRunState,
                    ),
                )
            }
            FlowSummary(
                tabId = tabId,
                name = updated.metadata?.name.orEmpty(),
                description = updated.metadata?.description.orEmpty(),
                nodeCount = updated.nodes.size,
                schedule = updated.metadata?.schedule,
                lastScheduledRunAtEpochMs = prior?.lastRunAtEpochMs,
                nextScheduledRunAtEpochMs = nextRunAt,
                lastScheduledRunState = prior?.lastRunState,
            )
        }
    }

    internal fun startScheduleRunner(
        pollIntervalMs: Long = DEFAULT_SCHEDULE_POLL_INTERVAL_MS,
        startupGraceMs: Long = DEFAULT_SCHEDULE_STARTUP_GRACE_MS,
    ): Job =
        synchronized(toolSyncLock) {
            check(!disposed) { "Cannot start scheduler after controller disposal" }
            scheduleJob ?: lifecycleScope.launch(Dispatchers.IO) {
                var nextDiscoveryAtEpochMs = Long.MIN_VALUE
                delay(startupGraceMs)
                while (isActive) {
                    try {
                        val nowEpochMs = nowMillis()
                        val discoverSchedules = nowEpochMs >= nextDiscoveryAtEpochMs
                        runSchedulePass(nowEpochMs, discoverSchedules)
                        if (discoverSchedules) {
                            nextDiscoveryAtEpochMs = nowEpochMs + SCHEDULE_DISCOVERY_INTERVAL_MS
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val safeMessage = failure.message
                            ?.filterNot(Char::isISOControl)
                            ?.take(MAX_SCHEDULE_DIAGNOSTIC_LENGTH)
                            ?: (failure::class.simpleName ?: "Exception")
                        println("[flow-tab] schedule pass failed: $safeMessage")
                    }
                    delay(pollIntervalMs)
                }
            }.also { scheduleJob = it }
        }

    /** One deterministic scheduler reconciliation; internal for lifecycle tests. */
    internal suspend fun runSchedulePass(
        nowEpochMs: Long = nowMillis(),
        discoverSchedules: Boolean = true,
    ) {
        scheduleSweepMutex.withLock {
            scheduledFlowIds(discoverSchedules).forEach { tabId ->
                schedulePassMutex.withLock {
                    try {
                        reconcileScheduledFlow(tabId, nowEpochMs)
                        scheduleFailureTypes.remove(tabId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val failureType = failure::class.simpleName ?: "Exception"
                        val safeMessage = failure.message
                            ?.filterNot(Char::isISOControl)
                            ?.take(MAX_SCHEDULE_DIAGNOSTIC_LENGTH)
                            ?: failureType
                        val diagnostic = "$failureType: $safeMessage"
                        if (scheduleFailureTypes.put(tabId, diagnostic) != diagnostic) {
                            val safeTabId = tabId.filterNot(Char::isISOControl).take(80)
                            println("[flow-tab] schedule '$safeTabId' failed: $diagnostic; later flows continue")
                        }
                    }
                }
            }
        }
    }

    private suspend fun scheduledFlowIds(discoverSchedules: Boolean): List<String> {
        if (discoverSchedules) return listFlows()
        return storage?.getAllKeys().orEmpty()
            .asSequence()
            .map { it.removePrefix(JSON_STORAGE_PREFIX) }
            .filter { it.startsWith(SCHEDULE_STATE_PREFIX) }
            .map { it.removePrefix(SCHEDULE_STATE_PREFIX) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun activeScheduledRunCount(): Int = scheduledExecutions.values.count { runId ->
        jobs[runId]?.state == RunJobState.RUNNING
    }

    private suspend fun reconcileScheduledFlow(tabId: String, nowEpochMs: Long) {
        val schedule = getFlow(tabId)?.metadata?.schedule
        if (schedule == null) {
            if (loadScheduleState(tabId) != null) {
                storage?.removeJsonValue(scheduleKey(tabId))
                scheduledExecutions.remove(tabId)
                scheduleFailureTypes.remove(tabId)
                invalidScheduleIntervals.remove(tabId)
            }
            return
        }
        if (schedule.intervalMinutes !in MIN_SCHEDULE_INTERVAL_MINUTES..MAX_SCHEDULE_INTERVAL_MINUTES) {
            if (invalidScheduleIntervals.put(tabId, schedule.intervalMinutes) != schedule.intervalMinutes) {
                val safeTabId = tabId.filterNot(Char::isISOControl).take(80)
                println("[flow-tab] schedule '$safeTabId' has invalid interval; skipping")
            }
            return
        }
        invalidScheduleIntervals.remove(tabId)
        val intervalMs = intervalMillis(schedule.intervalMinutes)
        var state = loadScheduleState(tabId)
        if (state == null ||
            state.intervalMinutes != schedule.intervalMinutes ||
            state.nextRunAtEpochMs == null ||
            state.nextRunAtEpochMs > nowEpochMs + intervalMs ||
            state.nextRunAtEpochMs < nowEpochMs - intervalMs
        ) {
            state = FlowScheduleState(
                tabId = tabId,
                intervalMinutes = schedule.intervalMinutes,
                lastRunAtEpochMs = state?.lastRunAtEpochMs,
                nextRunAtEpochMs = nowEpochMs + intervalMs,
                lastRunId = state?.lastRunId,
                lastRunState = state?.lastRunState,
            )
            persistScheduleState(state)
            return
        }

        val trackedRunId = scheduledExecutions[tabId] ?: state.lastRunId
        val priorJob = trackedRunId?.let { runStatus(it) }
        if (priorJob != null &&
            (trackedRunId != state.lastRunId || priorJob.state != state.lastRunState)
        ) {
            val recoveredDispatch = trackedRunId != state.lastRunId
            state = state.copy(
                lastRunAtEpochMs = if (recoveredDispatch) nowEpochMs else state.lastRunAtEpochMs,
                nextRunAtEpochMs = if (recoveredDispatch) {
                    nextScheduledDeadline(
                        previousDeadlineEpochMs = requireNotNull(state.nextRunAtEpochMs),
                        nowEpochMs = nowEpochMs,
                        intervalMs = intervalMs,
                    )
                } else {
                    state.nextRunAtEpochMs
                },
                lastRunId = trackedRunId,
                lastRunState = priorJob.state,
            )
            persistScheduleState(state)
        }
        // Local jobs close the window before their first persistence; the shared live
        // bus covers MCP controllers and open canvases after they publish admission.
        if (priorJob?.state == RunJobState.RUNNING) return
        if (nowEpochMs < requireNotNull(state.nextRunAtEpochMs)) return
        if (executions.keys.any { runId -> jobs[runId]?.tabId == tabId }) return
        if (FlowPersistenceCoordinator.isFlowLive(tabId)) return
        if (activeScheduledRunCount() >= MAX_CONCURRENT_SCHEDULED_RUNS) return

        val runId = startRun(tabId)
        scheduledExecutions[tabId] = runId
        persistScheduleState(
            state.copy(
                lastRunAtEpochMs = nowEpochMs,
                nextRunAtEpochMs = nextScheduledDeadline(
                    previousDeadlineEpochMs = requireNotNull(state.nextRunAtEpochMs),
                    nowEpochMs = nowEpochMs,
                    intervalMs = intervalMs,
                ),
                lastRunId = runId,
                lastRunState = RunJobState.RUNNING,
            ),
        )
    }

    internal suspend fun scheduleState(tabId: String): FlowScheduleState? = loadScheduleState(tabId)

    /** Rename a readable flow without changing any other metadata or graph content. */
    suspend fun renameFlow(tabId: String, name: String): FlowSummary =
        persistRenamedFlow(tabId, name, notifyOpenCanvas = true) {
            FlowPersistenceCoordinator.latestLiveSnapshot(tabId)
                ?: getFlow(tabId)
                ?: throw IllegalArgumentException("No readable flow '$tabId'")
        }

    /**
     * Persist (and, when necessary, create) the currently open graph from its live
     * canvas snapshot with a new name. This is deliberately internal to the tab UI;
     * storage-seated callers must use [renameFlow] so a stale supplied snapshot cannot
     * replace a saved graph.
     */
    internal suspend fun renameOpenFlow(tabId: String, name: String, snapshot: GraphSnapshot): FlowSummary =
        persistRenamedFlow(tabId, name, notifyOpenCanvas = false) { snapshot }

    private suspend fun persistRenamedFlow(
        tabId: String,
        name: String,
        notifyOpenCanvas: Boolean,
        loadSnapshot: suspend () -> GraphSnapshot,
    ): FlowSummary {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "Flow name cannot be blank" }
        require(normalizedName.length <= MAX_FLOW_NAME_LENGTH) {
            "Flow name cannot exceed $MAX_FLOW_NAME_LENGTH characters"
        }
        check(storage != null) { "Flow storage is unavailable" }
        // Waiting for a busy flow lock remains cancellable. Once acquired, tab close
        // must not interrupt the atomic read+write.
        val (snapshot, metadata) = FlowPersistenceCoordinator.withFlowLock(tabId) {
            withContext(NonCancellable + Dispatchers.IO) {
                // Loading inside the same lock is essential: otherwise a sidebar rename
                // can restore an older graph over a newer open-tab autosave.
                val current = loadSnapshot()
                val currentMetadata = (current.metadata ?: FlowMeta()).copy(name = normalizedName)
                val updated = current.copy(metadata = currentMetadata)
                writeUnlocked(tabId, updated)
                // Publish only after the storage write succeeds. Open tabs replay this name
                // into their live state, and their autosave consults it under the same lock.
                FlowPersistenceCoordinator.publishRename(tabId, normalizedName)
                if (notifyOpenCanvas) {
                    FlowPersistenceCoordinator.publishGraphUpdate(tabId, updated)
                }
                current to currentMetadata
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
    suspend fun deleteFlow(tabId: String): Boolean = schedulePassMutex.withLock {
        val store = storage ?: throw IllegalStateException("Flow storage is unavailable")
        if (store.getJson(graphKey(tabId)) == null) return@withLock false

        closeOpenFlowTabs(tabId)
        var deleted = false
        withContext(NonCancellable) {
            FlowPersistenceCoordinator.withFlowLock(tabId) {
                persistMutex.withLock {
                    if (store.getJson(graphKey(tabId)) != null) {
                        store.removeJsonValue(graphKey(tabId))
                        store.removeJsonValue("$RUN_STATE_PREFIX$tabId")
                        store.removeJsonValue(scheduleKey(tabId))
                        storedRuns().filter { it.second.tabId == tabId }.forEach { (runId, _) ->
                            store.removeJsonValue(runKey(runId))
                            jobs.remove(runId)
                            ownedRunIds.remove(runId)
                            FlowPersistenceCoordinator.forgetRun(runId)
                        }
                        deleted = true
                    }
                }
            }
        }
        if (!deleted) return false
        scheduledExecutions.remove(tabId)
        scheduleFailureTypes.remove(tabId)
        invalidScheduleIntervals.remove(tabId)
        FlowPersistenceCoordinator.forget(tabId)
        true
    }

    // ---- async run jobs (F1) ------------------------------------------------

    /**
     * Launch flow [tabId] on [scopeProvider] and return a runId immediately. The run drives
     * the headless [FlowExecutor]; poll [runStatus]/[runResult] for progress. A missing
     * flow or an executor throw becomes a [RunJobState.FAILED] job (never a crash); a
     * run in which any node errors is FAILED too, but always reaches a terminal state.
     * An already-disposed controller returns a terminal failure without dispatching work.
     */
    fun startRun(tabId: String, depth: Int = 0, ancestry: Set<String> = emptySet()): String {
        val runId = "run-${UUID.randomUUID()}"
        val startedAtMs = nextRunStartedAtMs()
        val disposalError = "Flow controller disposed"
        if (synchronized(toolSyncLock) { disposed }) {
            jobs[runId] = RunJob(
                runId = runId,
                tabId = tabId,
                state = RunJobState.FAILED,
                startedAtMs = startedAtMs,
                error = disposalError,
            )
            return runId
        }
        jobs[runId] = RunJob(runId, tabId, RunJobState.RUNNING, startedAtMs = startedAtMs)
        ownedRunIds += runId
        val states = ConcurrentHashMap<String, NodeRun>()
        runStates[runId] = states
        val execution = scopeProvider().launch(Dispatchers.Default) {
            // Persist RUNNING before executing so a reload can still diagnose an in-flight run.
            persistRun(jobs[runId] ?: RunJob(runId, tabId, RunJobState.RUNNING, startedAtMs = startedAtMs))
            val candidate = try {
                val snap = getFlow(tabId) ?: throw IllegalStateException("No flow '$tabId'")
                jobs.computeIfPresent(runId) { _, current -> current.copy(nodeCount = snap.nodes.size) }
                jobs[runId]?.let { persistRun(it) }
                val plan = snap.nodes.map { PlanNode(it.id, it.type, it.title, it.config) }
                // This flow is now on the call stack: a nested lanager pointing back at it
                // is a cycle. Depth is threaded so the nesting bound can be enforced.
                FlowExecutor(context, registry).run(
                    plan,
                    snap.edges,
                    closeVisibleTabsOnClose = true,
                    depth = depth,
                    ancestry = ancestry + tabId,
                ) { id, r ->
                    states[id] = r
                    // flow_result must be a non-blocking snapshot even while the
                    // run is active. Do not let late output overwrite a watchdog result.
                    val liveJob = jobs.computeIfPresent(runId) { _, current ->
                        if (current.state == RunJobState.RUNNING) {
                            current.copy(nodes = states.toRunSnapshot().states)
                        } else {
                            current
                        }
                    }
                    liveJob?.let(FlowPersistenceCoordinator::publishRunUpdate)
                    // Serialize storage writes through persistRun so a delayed live
                    // snapshot can never overwrite a terminal watchdog verdict.
                    lifecycleScope.launch { jobs[runId]?.let { persistRun(it, publish = false) } }
                }
                val firstError = states.values.firstOrNull { it.status == RunStatus.ERROR }
                RunJob(
                    runId = runId,
                    tabId = tabId,
                    state = if (firstError != null) RunJobState.FAILED else RunJobState.SUCCEEDED,
                    startedAtMs = jobs[runId]?.startedAtMs ?: startedAtMs,
                    nodeCount = snap.nodes.size,
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
        executions[runId] = execution
        // Install before rechecking under the lock: either this check cancels the run,
        // or a later dispose transition observes the already-present map entry in its sweep.
        execution.invokeOnCompletion {
            executions.remove(runId, execution)
            runStates.remove(runId, states)
        }
        if (synchronized(toolSyncLock) { disposed }) {
            transitionToFailed(runId, tabId, states, disposalError)
            execution.cancel(CancellationException(disposalError))
        }

        // This monitor is deliberately not a child of execution. join() is cancellable,
        // so its timeout fires even if execution is stuck in a non-suspending host call.
        lifecycleScope.launch {
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

    /**
     * Stop an actively executing in-memory run. Stopped runs use the existing FAILED
     * terminal state so older callers remain compatible, with an explicit caller-stop
     * error distinguishing cancellation from execution failure.
     *
     * Returns the terminal job, or null when the run is unknown or already terminal.
     */
    suspend fun stopRun(runId: String): RunJob? {
        val execution = executions[runId] ?: return null
        val states = runStates[runId] ?: return null
        val current = jobs[runId] ?: return null
        val message = "Flow run stopped by caller"
        val stopped = transitionToFailed(runId, current.tabId, states, message) ?: return null
        persistRun(stopped)
        execution.cancel(CancellationException(message))
        return stopped
    }

    /** Cancel current executions on every call; release lifecycle work on first teardown. */
    fun dispose() {
        val cancelLifecycle = synchronized(toolSyncLock) {
            if (disposed) {
                false
            } else {
                disposed = true
                true
            }
        }
        // Keep this outside the one-time lifecycle transition. startRun intentionally
        // retains its historical dispatch contract, so a later dispose call must still
        // cancel any execution installed after an earlier disposal pass.
        val disposalError = "Flow controller disposed"
        // Publish a terminal snapshot before cancellation/forgetting ownership. A
        // non-cooperative execution may never reach its finally block, and must not
        // leave another open canvas showing an eternal external RUNNING state.
        ownedRunIds.toList().forEach { runId ->
            val terminal = jobs.computeIfPresent(runId) { _, current ->
                if (current.state == RunJobState.RUNNING) {
                    current.copy(
                        state = RunJobState.FAILED,
                        error = disposalError,
                        nodes = current.nodes.mapValues { (_, node) ->
                            if (node.status == RunStatus.RUNNING) {
                                node.copy(status = RunStatus.ERROR, error = disposalError)
                            } else {
                                node
                            }
                        },
                    )
                } else {
                    current
                }
            }
            terminal?.let(FlowPersistenceCoordinator::publishRunUpdate)
        }
        executions.values.forEach { it.cancel(CancellationException(disposalError)) }
        ownedRunIds.forEach(FlowPersistenceCoordinator::forgetRun)
        ownedRunIds.clear()
        if (cancelLifecycle) lifecycleScope.cancel()
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

    private suspend fun persistRun(job: RunJob, publish: Boolean = true) {
        withContext(NonCancellable) {
            // Share the graph lock with deletion across controller instances. Whichever
            // operation wins is authoritative: delete removes an earlier terminal write,
            // while a later persist observes the missing graph and cannot recreate it.
            FlowPersistenceCoordinator.withFlowLock(job.tabId) {
                persistMutex.withLock {
                    runCatching {
                        // Always serialize the newest in-memory snapshot. Coroutine
                        // scheduling may otherwise let an older live write run last.
                        val safeJob = jobs[job.runId] ?: job
                        if (storage != null && storage.getJson(graphKey(safeJob.tabId)) == null) {
                            jobs.remove(safeJob.runId)
                            ownedRunIds.remove(safeJob.runId)
                            FlowPersistenceCoordinator.forgetRun(safeJob.runId, safeJob.tabId)
                            return@runCatching
                        }
                        storage?.putJson(runKey(job.runId), json.encodeToString(RunJob.serializer(), safeJob))
                        // dispose() already published a synchronous terminal snapshot and
                        // forgot the exact entry. A late NonCancellable write remains
                        // durable but must not repopulate the process-global live cache.
                        if (publish && !synchronized(toolSyncLock) { disposed }) {
                            FlowPersistenceCoordinator.publishRunUpdate(safeJob)
                        }
                        if (safeJob.isTerminal) evictOldRuns(safeJob.tabId)
                    }
                }
            }
        }
    }

    /** Record a canvas-owned run through the same durable/live channel as MCP runs. */
    internal fun publishCanvasRun(job: RunJob, persist: Boolean = true) {
        if (synchronized(toolSyncLock) { disposed }) return
        ownedRunIds += job.runId
        jobs.compute(job.runId) { _, current ->
            if (current?.isTerminal == true) current else job
        }
        val safeJob = jobs[job.runId] ?: job
        // A terminal update must not become observable until its complete output is
        // durable; persistRun writes storage first and then publishes it. Running
        // updates remain immediate, and only the admission snapshot is persisted.
        if (!persist || !safeJob.isTerminal) {
            FlowPersistenceCoordinator.publishRunUpdate(safeJob)
        }
        if (persist) lifecycleScope.launch { persistRun(safeJob) }
    }

    /** Millisecond timestamp made strictly monotonic for deterministic newest-first ordering. */
    internal fun nextRunStartedAtMs(): Long =
        FlowPersistenceCoordinator.nextRunStartedAtMs(nowMillis())

    /** Newest persisted/in-memory runs for [tabId], newest first. */
    suspend fun listRuns(tabId: String, limit: Int = DEFAULT_RUN_HISTORY_LIMIT): List<RunSummary> {
        require(limit in 1..MAX_RUN_HISTORY_LIMIT) {
            "limit must be between 1 and $MAX_RUN_HISTORY_LIMIT"
        }
        val ids = buildSet {
            jobs.values.asSequence().filter { it.tabId == tabId }.forEach { add(it.runId) }
            storage?.getAllKeys().orEmpty()
                .asSequence()
                .map { it.removePrefix(JSON_STORAGE_PREFIX) }
                .filter { it.startsWith(RUN_PREFIX) }
                .mapTo(this) { it.removePrefix(RUN_PREFIX) }
        }
        return ids.mapNotNull { runSnapshot(it) }
            .filter { it.tabId == tabId }
            .sortedWith(compareByDescending<RunJob> { it.startedAtMs }.thenByDescending { it.runId })
            .take(limit)
            .map { job ->
                RunSummary(
                    runId = job.runId,
                    state = job.state,
                    startedAtMs = job.startedAtMs,
                    nodeCount = maxOf(job.nodeCount, job.nodes.size),
                )
            }
    }

    private suspend fun evictOldRuns(tabId: String) {
        // One storage enumeration/decode pass per terminal persist. listRuns() would
        // enumerate and decode the same records again before deletion.
        val stored = storedRuns().filter { it.second.tabId == tabId }
        val candidates = LinkedHashMap<String, RunJob>()
        stored.forEach { (runId, job) -> candidates[runId] = job }
        jobs.values.filter { it.tabId == tabId }.forEach { candidates[it.runId] = it }
        val retained = candidates.values
            .sortedWith(compareByDescending<RunJob> { it.startedAtMs }.thenByDescending { it.runId })
            .take(DEFAULT_RUN_HISTORY_LIMIT)
            .mapTo(mutableSetOf()) { it.runId }
        stored.forEach { (runId, storedJob) ->
            val loaded = jobs[runId] ?: storedJob
            if (runId !in retained &&
                (loaded.isTerminal || !FlowPersistenceCoordinator.isRunLive(runId))
            ) {
                storage?.removeJsonValue(runKey(runId))
                jobs.remove(runId, loaded)
                ownedRunIds.remove(runId)
                FlowPersistenceCoordinator.forgetRun(runId)
            }
        }
        jobs.values.asSequence()
            .filter { it.tabId == tabId && it.runId !in retained && it.isTerminal }
            .toList()
            .forEach { old ->
                jobs.remove(old.runId, old)
                ownedRunIds.remove(old.runId)
                FlowPersistenceCoordinator.forgetRun(old.runId)
            }
    }

    private suspend fun storedRuns(): List<Pair<String, RunJob>> {
        val candidates = storage?.getAllKeys().orEmpty()
            .asSequence()
            .map { it.removePrefix(JSON_STORAGE_PREFIX) }
            .filter { it.startsWith(RUN_PREFIX) }
            .map { it.removePrefix(RUN_PREFIX) }
            .toList()
        return buildList {
            for (runId in candidates) {
                loadStoredJob(runId)?.let { add(runId to it) }
            }
        }
    }

    /**
     * Current job for [runId], or null if unknown. Falls back to the persisted
     * `run:<runId>` blob when the in-memory map has no entry (e.g. after a plugin
     * reload), so advertised durability is real (red-team S2), then re-caches it.
     */
    suspend fun runStatus(runId: String): RunJob? = resolveRun(runId, cacheStored = true)

    /** Read a history snapshot, sharing in-process liveness before repairing stale storage. */
    internal suspend fun runSnapshot(runId: String): RunJob? = resolveRun(runId, cacheStored = false)

    /** Resolve full owner/storage data without mistaking the scrubbed live bus for result storage. */
    private suspend fun resolveRun(runId: String, cacheStored: Boolean): RunJob? {
        val coordinated = FlowPersistenceCoordinator.runUpdate(runId)?.job
        if (coordinated?.state == RunJobState.RUNNING) {
            return if (runId in ownedRunIds) jobs[runId] ?: coordinated else coordinated
        }
        if (coordinated != null) {
            jobs[runId]?.takeIf { runId in ownedRunIds && it.isTerminal }?.let { return it }
            loadStoredJob(runId)?.takeIf(RunJob::isTerminal)?.let { return it }
            return coordinated
        }
        jobs[runId]?.let { return it }
        return loadJob(runId)?.also { if (cacheStored) jobs[runId] = it }
    }

    /** Per-node outputs for [runId] (in-memory or read back from storage), or null. */
    suspend fun runResult(runId: String): Map<String, NodeRunSnap>? = runStatus(runId)?.nodes

    private suspend fun loadJob(runId: String): RunJob? {
        val loaded = loadStoredJob(runId) ?: return null
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

    private suspend fun loadStoredJob(runId: String): RunJob? {
        val raw = storage?.getJson(runKey(runId)) ?: return null
        return runCatching { json.decodeFromString(RunJob.serializer(), raw) }.getOrNull()
    }

    // ---- internals ----------------------------------------------------------

    private suspend fun writeUnlocked(tabId: String, snapshot: GraphSnapshot) {
        // Read-modify-write callers already hold the per-flow mutex. Never acquire it
        // in this helper: kotlinx Mutex is non-reentrant and would deadlock.
        storage?.putJson(graphKey(tabId), json.encodeToString(GraphSnapshot.serializer(), snapshot))
    }

    private suspend fun loadScheduleState(tabId: String): FlowScheduleState? {
        val raw = storage?.getJson(scheduleKey(tabId)) ?: return null
        return runCatching { json.decodeFromString(FlowScheduleState.serializer(), raw) }.getOrNull()
    }

    private suspend fun persistScheduleState(state: FlowScheduleState) {
        storage?.putJson(
            scheduleKey(state.tabId),
            json.encodeToString(FlowScheduleState.serializer(), state),
        )
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
            startedAtMs = jobs[runId]?.startedAtMs ?: nextRunStartedAtMs(),
            nodeCount = jobs[runId]?.nodeCount ?: states.size,
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
    private fun scheduleKey(tabId: String) = "$SCHEDULE_STATE_PREFIX$tabId"

    private fun intervalMillis(intervalMinutes: Long): Long = intervalMinutes * 60_000L

    private fun nextScheduledDeadline(
        previousDeadlineEpochMs: Long,
        nowEpochMs: Long,
        intervalMs: Long,
    ): Long {
        val elapsed = (nowEpochMs - previousDeadlineEpochMs).coerceAtLeast(0L)
        val intervalsToAdvance = elapsed / intervalMs + 1L
        return previousDeadlineEpochMs + intervalsToAdvance * intervalMs
    }

    companion object {
        private const val MAX_KINDS_IN_ERROR = 30
        const val MAX_NODE_TITLE_LENGTH = 100
        const val MAX_FLOW_NAME_LENGTH = 100
        const val STORAGE_NAMESPACE = "ai.rever.boss.plugin.dynamic.flowtab"
        const val GRAPH_PREFIX = "graph:"
        const val RUN_PREFIX = "run:"
        const val SCHEDULE_STATE_PREFIX = "schedule:"
        const val MIN_SCHEDULE_INTERVAL_MINUTES = 1L
        const val MAX_SCHEDULE_INTERVAL_MINUTES = 365L * 24L * 60L
        const val DEFAULT_SCHEDULE_POLL_INTERVAL_MS = 15_000L
        const val DEFAULT_SCHEDULE_STARTUP_GRACE_MS = 60_000L
        const val SCHEDULE_DISCOVERY_INTERVAL_MS = 5 * 60_000L
        const val MAX_CONCURRENT_SCHEDULED_RUNS = 4
        const val DEFAULT_RUN_TIMEOUT_MS = 15 * 60 * 1000L
        const val DEFAULT_RUN_HISTORY_LIMIT = 20
        const val MAX_RUN_HISTORY_LIMIT = DEFAULT_RUN_HISTORY_LIMIT
        private const val MAX_SCHEDULE_DIAGNOSTIC_LENGTH = 300
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
 *
 * The optional [scope] controls run dispatch only. Registry synchronization belongs to
 * the returned controller's independent lifecycle, so every caller owns and must invoke
 * [FlowController.dispose] when that controller is no longer needed.
 */
fun buildHeadlessController(
    context: PluginContext,
    prompts: PromptRegistry,
    external: ExternalMcpManager?,
    scope: CoroutineScope? = null,
): FlowController {
    val scopeProvider: () -> CoroutineScope = { scope ?: context.pluginScope }
    val controller = FlowController(
        context = context,
        scopeProvider = scopeProvider,
    )
    // Make agent + lanager kinds runnable in headless (MCP-driven) runs too, sharing
    // the controller's registry so a lanager's sub-run resolves the same kinds.
    controller.registry.register(defaultAgentNodeSpec(context, prompts, external))
    controller.registry.register(lanagerNodeSpec(controller))
    // Start controller-owned background work only after construction can no longer fail.
    // Both collectors remain live across pluginScope replacement for the controller lifetime.
    controller.startToolRegistrySync(external)
    controller.startScheduleRunner()
    return controller
}
