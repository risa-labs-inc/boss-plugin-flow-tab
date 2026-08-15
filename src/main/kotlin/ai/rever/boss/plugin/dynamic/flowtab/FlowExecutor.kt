package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
) {

    suspend fun run(
        nodes: List<PlanNode>,
        edges: List<EdgeModel>,
        humanize: Boolean = false,
        onVisibleTab: (String?) -> Unit = {},
        /** Nesting level of this run (0 top-level); a lanager sub-run passes parent+1. */
        depth: Int = 0,
        /** Flow ids already on the call stack, so a nested lanager can detect cycles. */
        ancestry: Set<String> = emptySet(),
        onStatus: (nodeId: String, NodeRun) -> Unit
    ) {
        val byId = nodes.associateBy { it.id }
        topoSort(nodes, edges) // validate: throws on cycle (else awaits would deadlock)

        val depsOf: Map<String, List<String>> = nodes.associate { n ->
            n.id to edges.filter { it.toNode == n.id }.map { it.fromNode }.distinct()
        }
        val outputsById = ConcurrentHashMap<String, NodeOutput>()
        val failed = ConcurrentHashMap.newKeySet<String>()
        val done = nodes.associate { it.id to CompletableDeferred<Unit>() }
        val ctx = RunContext(
            context,
            onVisibleTab = onVisibleTab,
            depth = depth,
            ancestry = ancestry,
        )

        try {
            coroutineScope {
                for (node in nodes) {
                    launch {
                        // Wait for every upstream node to finish.
                        depsOf[node.id]?.forEach { dep -> done[dep]?.await() }

                        // If anything upstream failed, skip this node (and thus its branch).
                        if (depsOf[node.id]?.any { failed.contains(it) } == true) {
                            failed.add(node.id)
                            done[node.id]?.complete(Unit)
                            return@launch
                        }

                        onStatus(node.id, NodeRun(RunStatus.RUNNING))
                        // Realistic mode: pause a random, human-like beat before acting —
                        // paces the run so it's watchable and mimics a person at the keyboard.
                        if (humanize) delay(Random.nextLong(HUMANIZE_MIN_MS, HUMANIZE_MAX_MS))
                        val logs = mutableListOf<String>()
                        try {
                            val incoming = edges.filter { it.toNode == node.id }
                            val inputs = incoming
                                .sortedBy { it.toPort }
                                .flatMap { outputsById[it.fromNode]?.port(it.fromPort).orEmpty() }
                            val out = if (incoming.isNotEmpty() && inputs.isEmpty()) {
                                // An upstream control port emitted no items. Do not seed
                                // and accidentally execute the unselected branch.
                                logs.add("Skipped — no input items")
                                NodeOutput.EMPTY
                            } else if (registry[node.kind]?.usesSession == true) {
                                ctx.sessionMutex.withLock { runNode(ctx, node, inputs) { logs.add(it) } }
                            } else {
                                runNode(ctx, node, inputs) { logs.add(it) }
                            }
                            outputsById[node.id] = out
                            val flattened = out.allItems()
                            ctx.outputsByTitle[node.title] = flattened
                            onStatus(node.id, NodeRun(RunStatus.SUCCESS, flattened, null, logs))
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            failed.add(node.id)
                            onStatus(node.id, NodeRun(RunStatus.ERROR, emptyList(), e.message ?: e.toString(), logs))
                        }
                        done[node.id]?.complete(Unit)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { ctx.close() }
        }
    }

    private suspend fun runNode(
        ctx: RunContext,
        node: PlanNode,
        inputs: List<Item>,
        log: (String) -> Unit
    ): NodeOutput {
        val spec = registry[node.kind]
            ?: throw ExecError("Unknown node kind '${node.kind}' — its provider isn't available")
        val exec = spec.executor
            ?: throw ExecError("${spec.label} is unavailable — its provider isn't loaded")
        return when (spec.runMode) {
            RunMode.PER_ITEM -> {
                val accumulated = HashMap<Int, MutableList<Item>>()
                for (item in inputs.ifEmpty { SEED_ITEMS }) {
                    val output = exec.run(
                        ctx,
                        ConfigReader(node.config, item, ctx.outputsByTitle),
                        listOf(item),
                        log,
                    )
                    output.ports.forEach { (port, items) ->
                        accumulated.getOrPut(port) { mutableListOf() }.addAll(items)
                    }
                }
                NodeOutput(accumulated.mapValues { (_, items) -> items.toList() })
            }
            RunMode.ONCE -> {
                val item = inputs.firstOrNull() ?: SEED_ITEMS.first()
                exec.run(ctx, ConfigReader(node.config, item, ctx.outputsByTitle), inputs, log)
            }
        }
    }

    /** Kahn topological sort; throws on cycle. Unconnected nodes are included. */
    private fun topoSort(nodes: List<PlanNode>, edges: List<EdgeModel>): List<String> {
        val ids = nodes.map { it.id }.toSet()
        val indeg = ids.associateWith { 0 }.toMutableMap()
        val adj = ids.associateWith { mutableListOf<String>() }.toMutableMap()
        for (e in edges) {
            if (e.fromNode in ids && e.toNode in ids && e.fromNode != e.toNode) {
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
    }
}
