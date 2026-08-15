package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A unit of data flowing along a connection (n8n-style). A port carries a list
 * of items; an empty list means "no data / skip this branch".
 */
@Serializable
data class Item(val json: JsonObject)

typealias PortData = List<Item>

/** The seed item produced by a trigger: one empty object. */
val SEED_ITEMS: List<Item> = listOf(Item(JsonObject(emptyMap())))

/**
 * Data emitted by one node, keyed by output-port index. Most nodes emit only on
 * port 0; control nodes such as If choose between multiple ports. Keeping the
 * port here (instead of flattening immediately) makes [EdgeModel.fromPort]
 * meaningful at execution time.
 */
data class NodeOutput(val ports: Map<Int, PortData>) {
    fun port(index: Int): PortData = ports[index].orEmpty()

    /** Flattened view used by the inspector, persisted run state, and node refs. */
    fun allItems(): List<Item> = ports.toSortedMap().values.flatten()

    companion object {
        val EMPTY = NodeOutput(emptyMap())
        fun single(items: PortData): NodeOutput = if (items.isEmpty()) EMPTY else NodeOutput(mapOf(0 to items))
        fun onPort(index: Int, items: PortData): NodeOutput = if (items.isEmpty()) EMPTY else NodeOutput(mapOf(index to items))
    }
}

/** Per-node execution status (drives the canvas badge + inspector). */
@Serializable
enum class RunStatus { IDLE, RUNNING, SUCCESS, SKIPPED, ERROR }

internal const val SKIP_UPSTREAM_FAILED = "Skipped — upstream node failed"
internal const val SKIP_NO_INPUT = "Skipped — no input items"

/** Live execution state for one node. */
data class NodeRun(
    val status: RunStatus = RunStatus.IDLE,
    val output: List<Item> = emptyList(),
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val skipReason: String? = null,
)

// ---------------------------------------------------------------------------
// Persisted run state (so the last run's status/output survives reopening).
// ---------------------------------------------------------------------------

@Serializable
data class NodeRunSnap(
    val status: RunStatus,
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val output: List<JsonObject> = emptyList(),
    val skipReason: String? = null,
)

@Serializable
data class RunSnapshot(val states: Map<String, NodeRunSnap> = emptyMap())

/** Build a (capped) persistable snapshot of the current run states. */
fun Map<String, NodeRun>.toRunSnapshot(maxItems: Int = 20): RunSnapshot =
    RunSnapshot(
        mapValues { (_, r) ->
            NodeRunSnap(
                status = r.status,
                error = r.error,
                logs = r.logs.take(50),
                output = r.output.take(maxItems).map { it.json },
                skipReason = r.skipReason,
            )
        }
    )

/** Restore run states from a persisted snapshot. */
fun RunSnapshot.toRuns(): Map<String, NodeRun> =
    states.mapValues { (_, s) ->
        NodeRun(
            status = s.status,
            output = s.output.map { Item(it) },
            error = s.error,
            logs = s.logs,
            skipReason = s.skipReason,
        )
    }
