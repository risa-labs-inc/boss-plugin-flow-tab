package ai.rever.boss.plugin.dynamic.flowtab

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Durable, flow-local memory used by the State node and `{{ $state… }}` templates.
 *
 * The store deliberately contains JSON only (no executable values), is scoped to one
 * flow id, and is not included in run-result responses. A run receives a copy and
 * commits its changes only after every node succeeds. This avoids a failed partial run
 * advancing a notification cursor. Values are bounded to keep plugin storage and the
 * in-memory execution context predictable.
 */
@Serializable
data class FlowStateSnapshot(
    val values: JsonObject = JsonObject(emptyMap()),
)

class FlowStateBuffer(initial: JsonObject = JsonObject(emptyMap())) {
    private val values = ConcurrentHashMap<String, JsonElement>(initial)
    private val changed = ConcurrentHashMap<String, JsonElement>()

    fun snapshot(): JsonObject = JsonObject(values.toSortedMap())

    /** Only values written by State nodes in this run; used for conflict-safe commit. */
    fun changes(): JsonObject = JsonObject(changed.toSortedMap())

    /** Atomically apply one State node's already-resolved assignments. */
    @Synchronized
    fun putAll(assignments: JsonObject) {
        val next = snapshot().toMutableMap().apply { putAll(assignments) }
        validateFlowState(JsonObject(next))
        values.putAll(assignments)
        changed.putAll(assignments)
    }
}

internal fun validateFlowState(values: JsonObject) {
    require(values.size <= MAX_FLOW_STATE_KEYS) {
        "State can contain at most $MAX_FLOW_STATE_KEYS keys"
    }
    require(FLOW_STATE_JSON.encodeToString(JsonObject.serializer(), values).length <= MAX_FLOW_STATE_JSON_CHARS) {
        "State is limited to $MAX_FLOW_STATE_JSON_CHARS characters"
    }
}

internal const val MAX_FLOW_STATE_KEYS = 64
internal const val MAX_FLOW_STATE_JSON_CHARS = 32 * 1024
internal val FLOW_STATE_JSON = Json { encodeDefaults = true }
