package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.Serializable

/**
 * One tool advertised by a remote MCP server: its bare [name] (no server prefix),
 * human [description], and JSON-Schema [inputSchema] string. The [ExternalMcpToolSource]
 * namespaces these into `ext:<server>/<tool>` refs before they surface as nodes.
 */
data class RemoteTool(val name: String, val description: String, val inputSchema: String)

/** The outcome of a remote tool call: its [text] payload and whether it [isError]. */
data class RemoteToolResult(val text: String, val isError: Boolean)

/**
 * The thin seam over an MCP client connection. Keeping list/invoke behind this interface
 * lets the whole orchestrator core (namespacing, flag gating, routing, secrets) be
 * unit-tested with an in-memory fake, while the real stdio / HTTP-SSE transports (which
 * spawn processes or open sockets — the untested boundary, [McpClientTransports]) plug in
 * behind the same contract. [connect] runs the MCP handshake; [close] must reap any child
 * process or socket (red-team F9).
 */
interface McpTransport {
    suspend fun connect()
    suspend fun listTools(): List<RemoteTool>
    suspend fun callTool(name: String, argsJson: String): RemoteToolResult
    suspend fun close()
}

/** How Flow reaches an external MCP server. [STDIO] spawns a local process; [HTTP_SSE]
 *  talks to a remote HTTP endpoint over Server-Sent Events. */
@Serializable
enum class McpTransportKind { STDIO, HTTP_SSE }

/**
 * Persisted, secret-free configuration for one external MCP server (stored as part of a
 * JSON list at [ExternalMcpManager.CONFIG_KEY]). Auth material is **never** held here —
 * only [secretRef], a logical name resolved at connect time via the host secret store
 * ([SecretResolver]); the resolved token is threaded to the transport, not written back.
 *
 * @param name unique server id; the namespace prefix for its tools (`ext:<name>/<tool>`).
 * @param kind [McpTransportKind.STDIO] or [McpTransportKind.HTTP_SSE].
 * @param command stdio server binary (e.g. `npx`, `uvx`, `node`) — resolved through a
 *   login shell so a packaged GUI app finds it (red-team F9).
 * @param args stdio server arguments.
 * @param url HTTP/SSE endpoint.
 * @param enabled per-server explicit opt-in; only enabled servers connect on refresh.
 * @param secretRef logical secret name for an auth header/token (looked up, never stored).
 * @param headerName / @param headerPrefix how the resolved secret is presented on SSE
 *   requests (e.g. `Authorization: Bearer <token>`).
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val kind: McpTransportKind,
    val command: String = "",
    val args: List<String> = emptyList(),
    val url: String = "",
    val enabled: Boolean = false,
    val secretRef: String? = null,
    val headerName: String = "Authorization",
    val headerPrefix: String = "Bearer ",
)

/**
 * A [ToolSource] over one connected external MCP server (scope [ToolScope.EXT]). It
 * namespaces the server's tools as `ext:<serverName>/<tool>` so ids stay unique across
 * servers, and strips that prefix back off before proxying an [invoke] to the underlying
 * [transport]. A remote error surfaces as [ToolResult.isError] so a tool-node executor
 * turns it into an [ExecError] (F8) and an agent sees the error text.
 */
class ExternalMcpToolSource(
    val serverName: String,
    private val transport: McpTransport,
) : ToolSource {

    private val prefix = "$serverName/"

    override suspend fun list(): List<ToolDescriptor> = transport.listTools().map { t ->
        ToolDescriptor(
            ref = ToolRef(ToolScope.EXT, "$prefix${t.name}"),
            name = "$prefix${t.name}",
            description = t.description,
            inputSchema = t.inputSchema,
        )
    }

    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val bare = if (name.startsWith(prefix)) name.removePrefix(prefix) else name
        val r = transport.callTool(bare, argsJson)
        return ToolResult(r.text, r.isError)
    }
}

/**
 * Resolves external-tool binaries through the user's real shell. macOS GUI apps do not
 * inherit the login shell's `PATH`, so `npx`/`uvx`/`node` installed via a version manager
 * are invisible to a naive [ProcessBuilder] (red-team F9). We therefore launch (and probe)
 * stdio servers through a login+interactive shell (`-lic`) which sources the user's
 * profile and makes those binaries resolvable.
 */
object LoginShell {
    /** The user's shell from `$SHELL`, falling back to a POSIX shell. Never blank. */
    fun shell(): String = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"

    /** argv that prints [binary]'s absolute path if it's on the login-shell PATH. */
    fun resolveCommand(binary: String, shell: String = shell()): List<String> =
        listOf(shell, "-lic", "command -v ${quote(binary)}")

    /** argv that launches a stdio server ([command] + [args]) through a login shell so
     *  its PATH is populated before exec. Each token is shell-quoted. */
    fun launchCommand(command: String, args: List<String>, shell: String = shell()): List<String> {
        val joined = (listOf(command) + args).joinToString(" ") { quote(it) }
        return listOf(shell, "-lic", joined)
    }

    /** Single-quote a token for a POSIX shell, escaping embedded single quotes. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
