package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A controller-authored graph snapshot that an open canvas has not yet acknowledged. */
internal data class ExternalGraphUpdate(
    val revision: Long,
    val snapshot: GraphSnapshot,
)

/** Newest controller/canvas run snapshot for a flow, consumed by every open canvas. */
internal data class ExternalRunUpdate(
    val revision: Long,
    val job: RunJob,
)

/** Main-thread view of an open component's graph, including edits not yet autosaved. */
internal interface LiveFlowCanvas {
    val isInitialized: Boolean
    val appliedGraphRevision: Long
    fun snapshot(): GraphSnapshot
}

/**
 * Coordinates the independent full-snapshot writers for a flow: storage-seated
 * controller/MCP mutations and the open tab's debounced autosave.
 *
 * Controller writes publish a monotonically ordered [ExternalGraphUpdate]. An autosave
 * captured before that revision is skipped; once the canvas loads the update, its next
 * autosave carries the applied revision and clears the guard after a durable write.
 */
internal object FlowPersistenceCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val revisionCounter = AtomicLong()
    private val runStartedCounter = AtomicLong()
    private val mutableNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableGraphUpdates = MutableStateFlow<Map<String, ExternalGraphUpdate>>(emptyMap())
    private val mutableRunUpdates = MutableStateFlow<Map<String, ExternalRunUpdate>>(emptyMap())
    private val runUpdatesById = ConcurrentHashMap<String, ExternalRunUpdate>()
    private val liveCanvases = ConcurrentHashMap<String, MutableSet<LiveFlowCanvas>>()

    /** Latest successful rename per tab, used by toolbar/sidebar title synchronization. */
    val names = mutableNames.asStateFlow()

    /** Latest controller-authored snapshot per tab, replayed to an open or newly opened canvas. */
    val graphUpdates = mutableGraphUpdates.asStateFlow()

    /** Latest run snapshot per flow, including headless MCP runs. */
    val runUpdates = mutableRunUpdates.asStateFlow()

    suspend fun <T> withFlowLock(tabId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(tabId) { Mutex() }.withLock { block() }

    fun registerLiveCanvas(tabId: String, canvas: LiveFlowCanvas) {
        liveCanvases.computeIfAbsent(tabId) { ConcurrentHashMap.newKeySet() }.add(canvas)
    }

    fun unregisterLiveCanvas(tabId: String, canvas: LiveFlowCanvas) {
        liveCanvases[tabId]?.let { canvases ->
            canvases.remove(canvas)
            if (canvases.isEmpty()) liveCanvases.remove(tabId, canvases)
        }
    }

    /**
     * Current open-canvas graph, including changes still inside the debounce window.
     * Avoid a Main-dispatch hop when no canvas is registered (the headless/test path).
     */
    suspend fun latestLiveSnapshot(tabId: String): GraphSnapshot? {
        if (liveCanvases[tabId].isNullOrEmpty()) return null
        val requiredRevision = latestGraphUpdate(tabId)?.revision ?: 0L
        return withContext(Dispatchers.Main.immediate) {
            liveCanvases[tabId]
                ?.firstOrNull { canvas ->
                    canvas.isInitialized && canvas.appliedGraphRevision >= requiredRevision
                }
                ?.snapshot()
        }
    }

    /**
     * Persist a canvas snapshot only if it was captured after the latest controller
     * update. The caller must supply the newest external revision already loaded into
     * its live state; older snapshots are deliberately ignored instead of overwriting
     * storage with stale graph content.
     */
    suspend fun persistAutosave(
        tabId: String,
        snapshot: GraphSnapshot,
        appliedGraphRevision: Long = 0L,
        persist: suspend (GraphSnapshot) -> Unit,
    ) = withFlowLock(tabId) {
        val pendingUpdate = latestGraphUpdate(tabId)
        if (pendingUpdate != null && appliedGraphRevision < pendingUpdate.revision) {
            return@withFlowLock
        }

        val name = latestName(tabId)
        val current = if (name != null && snapshot.metadata?.name != name) {
            snapshot.copy(metadata = (snapshot.metadata ?: FlowMeta()).copy(name = name))
        } else {
            snapshot
        }
        persist(current)

        if (name != null && current.metadata?.name == name) forgetName(tabId)
        if (pendingUpdate != null && appliedGraphRevision >= pendingUpdate.revision) {
            forgetGraphUpdate(tabId, pendingUpdate.revision)
        }
    }

    fun publishRename(tabId: String, name: String) {
        mutableNames.update { current -> current + (tabId to name) }
    }

    fun latestName(tabId: String): String? = mutableNames.value[tabId]

    fun publishGraphUpdate(tabId: String, snapshot: GraphSnapshot): ExternalGraphUpdate {
        val update = ExternalGraphUpdate(revisionCounter.incrementAndGet(), snapshot)
        mutableGraphUpdates.update { current -> current + (tabId to update) }
        return update
    }

    fun latestGraphUpdate(tabId: String): ExternalGraphUpdate? = mutableGraphUpdates.value[tabId]

    fun publishRunUpdate(job: RunJob): ExternalRunUpdate {
        // This singleton is only a live-status bus, not the durable result store. In
        // particular, retaining node output here pins arbitrary JSON for the plugin
        // lifetime and duplicates the full job already held by its controller/storage.
        val liveJob = job.copy(
            error = job.error?.take(MAX_LIVE_MESSAGE_LENGTH),
            nodes = job.nodes.mapValues { (_, node) ->
                node.copy(
                    error = node.error?.take(MAX_LIVE_MESSAGE_LENGTH),
                    logs = node.logs.takeLast(MAX_LIVE_LOG_LINES).map { it.take(MAX_LIVE_MESSAGE_LENGTH) },
                    output = emptyList(),
                    skipReason = node.skipReason?.take(MAX_LIVE_MESSAGE_LENGTH),
                )
            },
        )
        val update = ExternalRunUpdate(revisionCounter.incrementAndGet(), liveJob)
        val effective = runUpdatesById.compute(job.runId) { _, previous ->
            if (previous?.job?.isTerminal == true) previous else update
        } ?: update
        mutableRunUpdates.update { current ->
            val previous = current[effective.job.tabId]
            if (previous == null || previous.job.runId == effective.job.runId ||
                effective.job.startedAtMs >= previous.job.startedAtMs
            ) {
                current + (effective.job.tabId to effective)
            } else {
                current
            }
        }
        return effective
    }

    fun latestRunUpdate(tabId: String): ExternalRunUpdate? = mutableRunUpdates.value[tabId]

    /** Exact in-process run snapshot, allowing separate controller instances to share liveness. */
    fun runUpdate(runId: String): ExternalRunUpdate? = runUpdatesById[runId]

    fun nextRunStartedAtMs(nowMillis: Long): Long = runStartedCounter.updateAndGet { previous ->
        maxOf(nowMillis, previous + 1L)
    }

    fun isRunLive(runId: String): Boolean = runUpdatesById[runId]?.job?.state == RunJobState.RUNNING

    fun forgetRun(runId: String) {
        runUpdatesById.remove(runId)
    }

    fun forget(tabId: String) {
        forgetName(tabId)
        mutableGraphUpdates.update { current -> current - tabId }
        mutableRunUpdates.update { current -> current - tabId }
        runUpdatesById.entries.removeIf { it.value.job.tabId == tabId }
        // Keep the mutex entry for the plugin lifetime. Removing a held mutex could
        // let an already-waiting writer and a new writer acquire different locks.
    }

    private fun forgetName(tabId: String) {
        mutableNames.update { current -> current - tabId }
    }

    private fun forgetGraphUpdate(tabId: String, revision: Long) {
        mutableGraphUpdates.update { current ->
            if (current[tabId]?.revision == revision) current - tabId else current
        }
    }

    private const val MAX_LIVE_LOG_LINES = 50
    private const val MAX_LIVE_MESSAGE_LENGTH = 4_096
}

/** Apply a newer controller snapshot to live canvas state and return its acknowledged revision. */
internal fun FlowGraphState.applyExternalGraphUpdate(
    update: ExternalGraphUpdate,
    appliedRevision: Long,
): Long {
    if (update.revision <= appliedRevision) return appliedRevision
    return if (load(update.snapshot)) update.revision else appliedRevision
}
