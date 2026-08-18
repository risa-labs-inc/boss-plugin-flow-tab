package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Config keys + constants for the `lanager` node. */
object LanagerNode {
    const val KIND = "lanager"

    /** The tabId of the sub-flow to run as a nested async job. */
    const val FLOW_ID_KEY = "flowId"

    const val ACCENT = 0xFF00897B

    /** Default cap on lanager nesting depth (belt-and-braces with cycle detection). */
    const val DEFAULT_MAX_DEPTH = 3
}

/**
 * Executor for a `lanager` node: a nested sub-workflow run as an **async job** via
 * [FlowController.startRun] — deliberately NOT through the 60s MCP fence, since a
 * sub-flow can be a full browser DAG (F1). Before launching it enforces two guards so
 * nested lanagers can't recurse unbounded (plan §08):
 *  - **cycle detection:** refuse a target already on the call stack ([RunContext.ancestry]);
 *  - **depth limit:** refuse once nesting would exceed [maxDepth].
 *
 * It then polls the sub-run to a terminal state; a failed sub-run fails this node (its
 * error propagates), a succeeded one emits a small descriptor [Item] pointing at the run.
 * The sub-run's lifetime is bound to this node: Canvas Stop, `flow_stop`, or a parent
 * watchdog timeout explicitly stops it, and nested lanagers cascade that stop to their
 * own children. Stop transitions a still-running child before requesting cancellation,
 * so it can win a race with terminal publication and record FAILED even when the child's
 * work just completed and its node snapshot contains only successful nodes.
 */
class LanagerNodeExecutor(
    private val controller: FlowController,
    private val maxDepth: Int = LanagerNode.DEFAULT_MAX_DEPTH,
) : NodeExecutor {

    override suspend fun run(
        ctx: RunContext,
        cfg: ConfigReader,
        inputs: List<Item>,
        log: (String) -> Unit,
    ): NodeOutput {
        val subId = cfg.str(LanagerNode.FLOW_ID_KEY).ifBlank { throw ExecError("lanager needs a 'flowId'") }

        if (subId in ctx.ancestry) throw ExecError("lanager cycle detected: '$subId' is already running")
        if (ctx.depth + 1 > maxDepth) throw ExecError("lanager depth limit ($maxDepth) exceeded")
        if (controller.getFlow(subId) == null) throw ExecError("lanager: no flow '$subId'")

        log("lanager → sub-flow '$subId' (depth ${ctx.depth + 1})")
        val runId = controller.startRun(subId, depth = ctx.depth + 1, ancestry = ctx.ancestry)
        val job = try {
            awaitTerminal(runId)
        } finally {
            // startRun launches on the controller scope, not as a child of this node.
            // Explicitly stop an active sub-run when the parent is cancelled or times out.
            // stopRun publishes FAILED and requests cancellation without joining, so child execution
            // and session cleanup may briefly overlap a subsequent run. Its persistence has no separate
            // deadline, however, and may delay this finally when host storage is slow.
            withContext(NonCancellable) { controller.stopRun(runId) }
        }

        if (job.state == RunJobState.FAILED) {
            throw ExecError("lanager sub-flow '$subId' failed: ${job.error ?: "unknown error"}")
        }
        log("lanager ← sub-flow '$subId' ${job.state}")
        return NodeOutput.single(listOf(
            Item(buildJsonObject {
                put("subFlow", subId)
                put("runId", runId)
                put("state", job.state.name)
            })
        ))
    }

    /**
     * Await the sub-run's terminal state without layering on a second, conflicting timeout.
     * Every controller run is independently bounded by its configured watchdog timeout, while
     * individual nodes retain their tighter agent, HTTP, and element-wait limits.
     */
    private suspend fun awaitTerminal(runId: String): RunJob {
        while (controller.runStatus(runId)?.state == RunJobState.RUNNING) delay(POLL_MS)
        return controller.runStatus(runId) ?: throw ExecError("lanager: sub-run '$runId' vanished")
    }

    private companion object {
        const val POLL_MS = 15L
    }
}

/**
 * Build the `lanager` [NodeSpec], bound to [controller] (whose registry it should be
 * registered into) and a nesting [maxDepth]. Runs [RunMode.ONCE] — the sub-flow fires
 * once for the node, not per input item.
 */
fun lanagerNodeSpec(
    controller: FlowController,
    maxDepth: Int = LanagerNode.DEFAULT_MAX_DEPTH,
): NodeSpec = NodeSpec(
    id = LanagerNode.KIND,
    label = "Lanager",
    inputs = 1,
    outputs = 1,
    accent = LanagerNode.ACCENT,
    description = "Run another flow as a nested async sub-workflow (depth- & cycle-guarded).",
    runMode = RunMode.ONCE,
    configFields = listOf(
        ConfigField(LanagerNode.FLOW_ID_KEY, "Sub-flow id", FieldType.TEXT, placeholder = "flow-…"),
    ),
    executor = LanagerNodeExecutor(controller, maxDepth),
)
