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

/** Per-node execution status (drives the canvas badge + inspector). */
@Serializable
enum class RunStatus { IDLE, RUNNING, SUCCESS, ERROR }

/** Live execution state for one node. */
data class NodeRun(
    val status: RunStatus = RunStatus.IDLE,
    val output: List<Item> = emptyList(),
    val error: String? = null,
    val logs: List<String> = emptyList()
)

// ---------------------------------------------------------------------------
// Persisted run state (so the last run's status/output survives reopening).
// ---------------------------------------------------------------------------

@Serializable
data class NodeRunSnap(
    val status: RunStatus,
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val output: List<JsonObject> = emptyList()
)

@Serializable
data class RunSnapshot(val states: Map<String, NodeRunSnap> = emptyMap())

/** Build a (capped) persistable snapshot of the current run states. */
fun Map<String, NodeRun>.toRunSnapshot(maxItems: Int = 20): RunSnapshot =
    RunSnapshot(
        mapValues { (_, r) ->
            NodeRunSnap(r.status, r.error, r.logs.take(50), r.output.take(maxItems).map { it.json })
        }
    )

/** Restore run states from a persisted snapshot. */
fun RunSnapshot.toRuns(): Map<String, NodeRun> =
    states.mapValues { (_, s) -> NodeRun(s.status, s.output.map { Item(it) }, s.error, s.logs) }
