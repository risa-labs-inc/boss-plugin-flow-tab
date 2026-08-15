package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonObject

/**
 * Runtime descriptor for one kind of node. Replaces the closed [NodeType] enum's
 * per-member metadata + the scattered `when` blocks: everything the canvas, the
 * inspector, and the executor need to know about a kind lives here, keyed by a
 * stable string [id].
 *
 * Built-ins register specs whose [id] is the legacy enum name (`"HTTP"`, …) so
 * existing serialized graphs keep loading. Dynamic kinds (registry tools, agents)
 * register specs with namespaced ids (`"tool:boss:foo"`, `"agent"`, …).
 *
 * [executor] is null only for unavailable kinds, such as a tool whose backing
 * provider is absent at load. The engine surfaces that as a first-class
 * "unavailable" node rather than failing graph loading.
 */
data class NodeSpec(
    val id: String,
    val label: String,
    val inputs: Int,
    val outputs: Int,
    val accent: Long,
    val description: String,
    val runMode: RunMode = RunMode.PER_ITEM,
    val usesSession: Boolean = false,
    val hasMetaRow: Boolean = false,
    val configFields: List<ConfigField> = emptyList(),
    /** Per-output-port labels (e.g. If -> ["true","false"]); missing index -> "". */
    val outputLabels: List<String> = emptyList(),
    /** Per-input-port labels (e.g. Merge -> ["a","b"]); missing index -> "". */
    val inputLabels: List<String> = emptyList(),
    /**
     * Config seeded into a freshly-spawned node of this kind (merged under the user's
     * fields). Tool nodes use it to cache their toolRef + schema snapshot so a saved
     * node keeps them when the backing tool is absent at load (F4). Empty for built-ins.
     */
    val defaultConfig: JsonObject = JsonObject(emptyMap()),
    val executor: NodeExecutor? = null,
) {
    fun outputLabel(index: Int): String = outputLabels.getOrElse(index) { "" }
    fun inputLabel(index: Int): String = inputLabels.getOrElse(index) { "" }

    /** True when this is a placeholder for a kind-id with no registered spec. */
    val isUnavailable: Boolean get() = executor == null

    companion object {
        /** Neutral accent for unavailable / unknown kinds. */
        const val UNAVAILABLE_ACCENT = 0xFF6B6B6B

        /**
         * A first-class placeholder [NodeSpec] for a kind-id that has no registered
         * spec (an unknown kind, or a tool whose backing provider is absent at load).
         * It renders as a single-in/single-out node so the graph still lays out and
         * saves round-trips its [id]; [executor] is null so a run surfaces a clear
         * per-node error instead of crashing the load.
         */
        fun unavailable(id: String): NodeSpec = NodeSpec(
            id = id,
            label = id,
            inputs = 1,
            outputs = 1,
            accent = UNAVAILABLE_ACCENT,
            description = "Unavailable — no node kind '$id' is registered",
            executor = null,
        )
    }
}
