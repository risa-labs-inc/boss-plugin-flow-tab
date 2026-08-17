package ai.rever.boss.plugin.dynamic.flowtab

/**
 * Open, runtime map of kind-id -> [NodeSpec]. The single source of truth the
 * palette and the executor read from, replacing `NodeType.entries` and the
 * `when(type)` dispatch.
 *
 * Registration order is preserved (the palette renders in this order). Re-registering
 * an existing id replaces the spec in place. Public operations synchronize access so
 * MCP validation can safely snapshot the registry while tool StateFlows update it.
 */
class NodeRegistry {
    private val specs = LinkedHashMap<String, NodeSpec>()
    private val lock = Any()

    fun register(spec: NodeSpec) = synchronized(lock) { specs[spec.id] = spec }

    fun unregister(id: String) = synchronized(lock) { specs.remove(id) }

    operator fun get(id: String): NodeSpec? = synchronized(lock) { specs[id] }

    /**
     * The spec for [id], or a first-class [NodeSpec.unavailable] placeholder when no
     * spec is registered. Consumers that must always render/lay-out a node (geometry,
     * the canvas, the inspector) use this so an unknown kind degrades gracefully rather
     * than crashing; the placeholder's null executor produces a clear error at run.
     */
    fun resolve(id: String): NodeSpec = synchronized(lock) {
        specs[id] ?: NodeSpec.unavailable(id)
    }

    fun all(): List<NodeSpec> = synchronized(lock) { specs.values.toList() }
}
