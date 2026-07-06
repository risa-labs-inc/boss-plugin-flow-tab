package ai.rever.boss.plugin.dynamic.flowtab

/**
 * Builds a [NodeRegistry] pre-populated with the 12 built-in kinds.
 *
 * During the migration this derives each [NodeSpec] from the legacy [NodeType]
 * enum + [NodeCatalog], guaranteeing byte-for-byte parity with the pre-registry
 * behavior (see [BuiltinNodesTest]). Once every consumer reads from the registry,
 * the enum's per-member metadata can be inlined here and the enum retired.
 */
fun builtinNodeRegistry(): NodeRegistry {
    val reg = NodeRegistry()
    for (t in NodeType.entries) {
        reg.register(
            NodeSpec(
                id = t.name,
                label = t.label,
                inputs = t.inputs,
                outputs = t.outputs,
                accent = t.accent,
                description = t.description,
                runMode = t.runMode,
                usesSession = t.usesSession(),
                hasMetaRow = t.hasMetaRow(),
                configFields = t.configFields(),
                outputLabels = (0 until t.outputs).map { t.outputLabel(it) },
                inputLabels = (0 until t.inputs).map { t.inputLabel(it) },
                executor = NodeCatalog.executor(t),
            )
        )
    }
    return reg
}
