package ai.rever.boss.plugin.dynamic.flowtab

/**
 * Where a tool lives. Names the four provenance lanes the orchestrator unifies:
 * host registry ([BOSS]), external MCP servers ([EXT]), Flow's own authoring tools
 * ([FLOW]), and the stateful browser façade ([BROWSER]). The wire name is the
 * middle segment of a tool node's kind-id (`tool:<scope>:<name>`).
 */
enum class ToolScope(val wire: String) {
    BOSS("boss"),
    EXT("ext"),
    FLOW("flow"),
    BROWSER("browser"),
}

/**
 * A stable, typed reference to one tool. [kindId] is the [NodeSpec.id] of the node
 * that runs it, so a saved graph re-resolves the tool by id at load.
 */
data class ToolRef(val scope: ToolScope, val name: String) {
    /** Node kind-id, e.g. `tool:boss:search`. */
    val kindId: String get() = "tool:${scope.wire}:$name"
}

/** A tool the orchestrator can render as a node: its [ref], display [name],
 *  [description], and JSON-Schema [inputSchema] string (may be loose/empty). */
data class ToolDescriptor(
    val ref: ToolRef,
    val name: String,
    val description: String,
    val inputSchema: String,
)

/** Outcome of invoking a tool. [isError] true means the call failed (its executor
 *  throws [ExecError] so the DAG's exception-based failure model kicks in, F8). */
data class ToolResult(val text: String, val isError: Boolean)

/**
 * A stateless source of tools: enumerate them and invoke one by name with a JSON
 * argument string. Impls wrap the host registry ([BossRegistryToolSource]) and —
 * in later phases — external MCP servers and the browser session façade.
 */
interface ToolSource {
    suspend fun list(): List<ToolDescriptor>
    suspend fun invoke(name: String, argsJson: String): ToolResult
}
