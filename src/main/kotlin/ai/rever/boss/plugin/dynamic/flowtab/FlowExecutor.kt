package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A static snapshot of a node for execution (decoupled from reactive UI state).
 * [kind] is the registry kind-id; the executor resolves it to a [NodeSpec] for
 * dispatch (run-mode, session use, executor). An unknown kind-id or a spec with no
 * executor fails only *that* node, with a clear message — the run does not crash.
 */
data class PlanNode(
    val id: String,
    val kind: String,
    val title: String,
    val config: JsonObject
)

/**
 * Phase 1 executor — **parallel topological run with a per-session fence.**
 *
 * Each node runs in its own coroutine that first awaits all of its dependency
 * nodes (the source nodes of its incoming edges), so independent branches run
 * concurrently. Session-touching (browser) nodes additionally run behind a
 * single [RunContext.sessionMutex], so two branches never drive the one browser
 * page at the same time; dependency edges preserve order along a chain.
 *
 * Failure is per-branch: a node that errors marks its downstream as skipped, but
 * independent branches keep running. The browser session is owned by the run and
 * closed in a `finally` (even on cancel / Stop).
 *
 * ```
 *   Trigger ──► Open ──► Navigate ──► Click ──► Extract     (session lane, serialized)
 *        └────► HTTP ──► Set                                 (runs in parallel)
 * ```
 */
class FlowExecutor(
    private val context: PluginContext,
    /** Kind-id → spec map used for dispatch. Defaults to the built-ins so tests and
     *  the ad-hoc executor path work without threading a registry through. */
    private val registry: NodeRegistry = builtinNodeRegistry(),
    /** Injectable for tests; production resolves from the host secret manager. */
    private val secrets: SecretResolver = SecretResolver.fromSecrets(context),
) {

    suspend fun run(
        nodes: List<PlanNode>,
        edges: List<EdgeModel>,
        humanize: Boolean = false,
        onVisibleTab: (String?) -> Unit = {},
        /** Close visible tabs during cleanup for MCP/headless runs. Interactive canvas
         * runs leave this false so their last browser tab remains inspectable. */
        closeVisibleTabsOnClose: Boolean = false,
        /** Nesting level of this run (0 top-level); a lanager sub-run passes parent+1. */
        depth: Int = 0,
        /** Flow ids already on the call stack, so a nested lanager can detect cycles. */
        ancestry: Set<String> = emptySet(),
        /** State copy staged for this run. The caller persists it on successful completion. */
        flowState: FlowStateBuffer = FlowStateBuffer(),
        onStatus: (nodeId: String, NodeRun) -> Unit
    ) {
        topoSort(nodes, edges) // validate: throws on cycle (else awaits would deadlock)

        val edgesByTarget = edges.groupBy { it.toNode }
        val incomingOf: Map<String, List<EdgeModel>> = nodes.associate { n ->
            n.id to edgesByTarget[n.id].orEmpty()
        }
        val depsOf: Map<String, List<String>> = incomingOf.mapValues { (_, incoming) ->
            incoming.map { it.fromNode }.distinct()
        }
        val outputsById = ConcurrentHashMap<String, NodeOutput>()
        val failed = ConcurrentHashMap.newKeySet<String>()
        val done = nodes.associate { it.id to CompletableDeferred<Unit>() }
        val ctx = RunContext(
            context,
            secrets = secrets,
            onVisibleTab = onVisibleTab,
            closeVisibleTabsOnClose = closeVisibleTabsOnClose,
            depth = depth,
            ancestry = ancestry,
            flowState = flowState,
        )

        try {
            coroutineScope {
                for (node in nodes) {
                    launch {
                        try {
                            // Wait for every upstream node to finish.
                            depsOf[node.id]?.forEach { dep -> done[dep]?.await() }

                            // If anything upstream failed, skip this node (and thus its branch).
                            if (depsOf[node.id]?.any { failed.contains(it) } == true) {
                                failed.add(node.id)
                                onStatus(
                                    node.id,
                                    NodeRun(
                                        RunStatus.SKIPPED,
                                        logs = listOf(SKIP_UPSTREAM_FAILED),
                                        skipReason = SKIP_UPSTREAM_FAILED,
                                    ),
                                )
                                return@launch
                            }

                            onStatus(node.id, NodeRun(RunStatus.RUNNING))
                            // Realistic mode: pause a random, human-like beat before acting —
                            // paces the run so it's watchable and mimics a person at the keyboard.
                            if (humanize) delay(Random.nextLong(HUMANIZE_MIN_MS, HUMANIZE_MAX_MS))
                            val logs = mutableListOf<String>()
                            try {
                                val incoming = incomingOf[node.id].orEmpty()
                                val inputs = incoming
                                    .sortedBy { it.toPort }
                                    .flatMap { outputsById[it.fromNode]?.port(it.fromPort).orEmpty() }
                                val skipped = incoming.isNotEmpty() && inputs.isEmpty()
                                val out = if (skipped) {
                                    // An upstream control port emitted no items. Do not seed
                                    // and accidentally execute the unselected branch.
                                    logs.add(SKIP_NO_INPUT)
                                    NodeOutput.EMPTY
                                } else {
                                    // Provider availability is a runtime property of the selected
                                    // branch. A dead branch must not fail an otherwise valid run.
                                    val spec = registry[node.kind]
                                        ?: throw ExecError(
                                            "Unknown node kind '${node.kind}' — its provider isn't available",
                                        )
                                    val exec = spec.executor
                                        ?: throw ExecError("${spec.label} is unavailable — its provider isn't loaded")
                                    if (spec.usesSession) {
                                        ctx.sessionMutex.withLock {
                                            runNode(ctx, node, inputs, spec, exec) { logs.add(it) }
                                        }
                                    } else {
                                        runNode(ctx, node, inputs, spec, exec) { logs.add(it) }
                                    }
                                }
                                outputsById[node.id] = out
                                val flattened = out.allItems()
                                ctx.outputsByTitle[node.title] = flattened
                                val status = if (skipped) RunStatus.SKIPPED else RunStatus.SUCCESS
                                onStatus(
                                    node.id,
                                    NodeRun(
                                        status = status,
                                        output = flattened,
                                        logs = logs,
                                        skipReason = SKIP_NO_INPUT.takeIf { skipped },
                                    ),
                                )
                            } catch (ce: CancellationException) {
                                if (!currentCoroutineContext().isActive) {
                                    // Propagate cancellation through the dependency signal instead
                                    // of letting a dependant misreport missing output as SKIPPED.
                                    done[node.id]?.cancel(ce)
                                    throw ce
                                }
                                // A host call may use CancellationException for its own timeout
                                // while this run is still active. That is a node failure, not a
                                // request to silently cancel this child coroutine.
                                failed.add(node.id)
                                onStatus(
                                    node.id,
                                    NodeRun(
                                        RunStatus.ERROR,
                                        error = ce.message ?: "Cancelled by host",
                                        logs = logs,
                                    ),
                                )
                            } catch (e: Exception) {
                                failed.add(node.id)
                                onStatus(node.id, NodeRun(RunStatus.ERROR, emptyList(), e.message ?: e.toString(), logs))
                            }
                        } finally {
                            // Every exit path signals dependants. Without this, an exception
                            // in status publication or setup can leave them awaiting forever.
                            done[node.id]?.complete(Unit)
                        }
                    }
                }
            }
        } finally {
            // Run cleanup independently so a non-cooperative host disposer cannot keep
            // this execution alive past the best-effort cleanup window. If a node itself
            // is stuck in a host call, cleanup cannot begin until that call eventually
            // returns; the controller watchdog can bound reporting, not reclaim that call.
            withContext(NonCancellable) {
                val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val cleanup = cleanupScope.launch { runCatching { ctx.close() } }
                cleanup.invokeOnCompletion { cleanupScope.cancel() }
                if (withTimeoutOrNull(CLEANUP_TIMEOUT_MS) { cleanup.join() } == null) {
                    println(
                        "[flow-tab] session cleanup exceeded ${CLEANUP_TIMEOUT_MS}ms; " +
                            "disposal continues in the background",
                    )
                }
            }
        }
    }

    private suspend fun runNode(
        ctx: RunContext,
        node: PlanNode,
        inputs: List<Item>,
        spec: NodeSpec,
        exec: NodeExecutor,
        log: (String) -> Unit
    ): NodeOutput {
        return when (spec.runMode) {
            RunMode.PER_ITEM -> {
                val accumulated = HashMap<Int, MutableList<Item>>()
                for (item in inputs.ifEmpty { SEED_ITEMS }) {
                    val output = exec.run(
                        ctx,
                        ConfigReader(node.config, item, ctx.outputsByTitle, ctx.flowState),
                        listOf(item),
                        log,
                    )
                    output.ports.forEach { (port, items) ->
                        if (items.isNotEmpty()) {
                            accumulated.getOrPut(port) { mutableListOf() }.addAll(items)
                        }
                    }
                }
                if (accumulated.isEmpty()) {
                    NodeOutput.EMPTY
                } else {
                    NodeOutput(accumulated.mapValues { (_, items) -> items.toList() })
                }
            }
            RunMode.ONCE -> {
                val item = inputs.firstOrNull() ?: SEED_ITEMS.first()
                exec.run(ctx, ConfigReader(node.config, item, ctx.outputsByTitle, ctx.flowState), inputs, log)
            }
        }
    }

    /** Kahn topological sort; throws on cycle. Unconnected nodes are included. */
    private fun topoSort(nodes: List<PlanNode>, edges: List<EdgeModel>): List<String> {
        val ids = nodes.map { it.id }.toSet()
        val indeg = ids.associateWith { 0 }.toMutableMap()
        val adj = ids.associateWith { mutableListOf<String>() }.toMutableMap()
        for (e in edges) {
            if (e.fromNode in ids && e.toNode in ids) {
                adj[e.fromNode]?.add(e.toNode)
                indeg[e.toNode] = (indeg[e.toNode] ?: 0) + 1
            }
        }
        val queue = ArrayDeque(ids.filter { indeg[it] == 0 })
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            for (m in adj[n].orEmpty()) {
                indeg[m] = (indeg[m] ?: 0) - 1
                if (indeg[m] == 0) queue.add(m)
            }
        }
        if (order.size != ids.size) throw ExecError("Cycle detected in the flow")
        return order
    }

    private companion object {
        // Human-like pause range (ms) inserted before each step in realistic mode.
        const val HUMANIZE_MIN_MS = 600L
        const val HUMANIZE_MAX_MS = 2000L
        const val CLEANUP_TIMEOUT_MS = 5_000L
    }
}
