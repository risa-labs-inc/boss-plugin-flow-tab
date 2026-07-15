package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.RegisteredMcpTool

/**
 * [ToolSource] over the host's [McpToolRegistry] — the in-process, RBAC-filtered
 * enumerate+invoke surface every plugin's tools are bridged through. We consume it,
 * we don't reinvent it (see plan §00 "Consume, don't build").
 *
 * [list] reads the current value of the reactive `tools` flow (already RBAC-filtered
 * to what this plugin may call); [invoke] delegates to the registry's 60s-fenced
 * invoke and maps the result. A live re-derive on flow changes is wired by
 * [syncBossTools].
 */
class BossRegistryToolSource(private val registry: McpToolRegistry) : ToolSource {

    override suspend fun list(): List<ToolDescriptor> =
        registry.tools.value.map { it.toDescriptor() }

    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val result = registry.invoke(name, argsJson)
        return ToolResult(result.text, result.isError)
    }
}

/** Map a host [RegisteredMcpTool] to a boss-scoped [ToolDescriptor]. */
fun RegisteredMcpTool.toDescriptor(): ToolDescriptor = ToolDescriptor(
    ref = ToolRef(ToolScope.BOSS, definition.name),
    name = definition.name,
    description = definition.description,
    inputSchema = definition.inputSchema,
)
