package ai.rever.boss.plugin.dynamic.flowtab

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpSseTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.concurrent.thread

/**
 * The real, network/process-touching MCP client transports (red-team F9). These are the
 * **untested boundary** noted in the P7 plan — they spawn a subprocess or open an SSE
 * socket, which unit tests deliberately never do (the core is proven against a fake
 * [McpTransport]). They are thin adapters over the MCP Kotlin SDK client [Client]:
 * [connect]/[listTools]/[callTool]/[close] map 1:1 onto SDK calls, and [close] reaps the
 * child process / HTTP client so nothing leaks on plugin dispose.
 */

/** Default [McpTransportFactory]: pick a transport by [McpServerConfig.kind]. */
fun defaultMcpTransport(config: McpServerConfig, secret: String?): McpTransport = when (config.kind) {
    McpTransportKind.STDIO -> StdioMcpTransport(config)
    McpTransportKind.HTTP_SSE -> SseMcpTransport(config, secret)
}

private const val CLIENT_NAME = "boss-flow-tab"
private const val CLIENT_VERSION = "1.0.0"

/** Shared SDK client + result marshalling for the concrete transports. */
private abstract class SdkMcpTransport : McpTransport {
    protected val client = Client(Implementation(CLIENT_NAME, CLIENT_VERSION))

    override suspend fun listTools(): List<RemoteTool> {
        val result = client.listTools()
        return result?.tools.orEmpty().map { it.toRemoteTool() }
    }

    override suspend fun callTool(name: String, argsJson: String): RemoteToolResult {
        val args = runCatching { JSON.parseToJsonElement(argsJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val res = client.callTool(CallToolRequest(name = name, arguments = args))
            ?: return RemoteToolResult("tool '$name' returned no result", isError = true)
        return res.toRemoteToolResult()
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** stdio transport: launches the server binary through a login shell (so `npx`/`uvx`/
 *  `node` resolve on a packaged macOS app — F9) and speaks MCP over its stdio. */
private class StdioMcpTransport(private val config: McpServerConfig) : SdkMcpTransport(), McpTransportDiagnostics {
    private var process: Process? = null
    private var stderrTail: ProcessStderrTail? = null

    override suspend fun connect() {
        val proc = stdioProcessBuilder(config)
            .redirectErrorStream(false)
            .start()
        process = proc
        val tail = ProcessStderrTail()
        stderrTail = tail
        thread(name = "flow-mcp-stderr-${config.name}", isDaemon = true) {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines -> lines.forEach(tail::append) }
            }
        }
        val transport = StdioClientTransport(
            input = proc.inputStream.asSource().buffered(),
            output = proc.outputStream.asSink().buffered(),
        )
        client.connect(transport)
    }

    override suspend fun close() {
        runCatching { client.close() }
        // Reap the child so no zombie survives plugin dispose (F9).
        process?.let { p ->
            runCatching { p.destroy() }
            runCatching { if (p.isAlive) p.destroyForcibly() }
        }
        process = null
    }

    override fun diagnostic(): String? = stderrTail?.snapshot()
}

/** Optional diagnostics from a concrete transport. The manager sanitizes and redacts
 * this provider-controlled text before it reaches UI/status state. */
internal interface McpTransportDiagnostics {
    fun diagnostic(): String?
}

/** A synchronized, fixed-size tail prevents a noisy child from consuming plugin memory. */
internal class ProcessStderrTail(private val maxChars: Int = 4_096, private val maxLines: Int = 8) {
    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Synchronized fun append(line: String) {
        val bounded = line.takeLast(maxChars)
        lines.addLast(bounded)
        chars += bounded.length + 1
        while (lines.size > maxLines || chars > maxChars) {
            chars -= lines.removeFirst().length + 1
        }
    }

    @Synchronized fun snapshot(): String? = lines.joinToString("\n").takeIf { it.isNotBlank() }
}

/** Build a stdio-server process without changing legacy blank-directory behavior. */
internal fun stdioProcessBuilder(config: McpServerConfig): ProcessBuilder =
    ProcessBuilder(LoginShell.launchCommand(config.command, config.args)).apply {
        config.workingDirectory.trim().takeIf { it.isNotEmpty() }?.let { directory(File(it)) }
    }

/** HTTP/SSE transport: connects to a remote endpoint, presenting the resolved [secret]
 *  (never persisted) as an auth header. */
private class SseMcpTransport(
    private val config: McpServerConfig,
    private val secret: String?,
) : SdkMcpTransport() {
    private var http: HttpClient? = null

    override suspend fun connect() {
        val httpClient = HttpClient(CIO) { install(SSE) }
        http = httpClient
        val transport = httpClient.mcpSseTransport(urlString = config.url) {
            if (!secret.isNullOrBlank()) {
                headers { append(config.headerName, "${config.headerPrefix}$secret") }
            }
        }
        client.connect(transport)
    }

    override suspend fun close() {
        runCatching { client.close() }
        runCatching { http?.close() }
        http = null
    }
}

// ---- SDK → Flow marshalling -------------------------------------------------

private fun Tool.toRemoteTool(): RemoteTool {
    val input = inputSchema
    val schema = buildJsonObject {
        put("type", "object")
        put("properties", input.properties)
        val required = input.required
        if (!required.isNullOrEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    return RemoteTool(name = name, description = description ?: "", inputSchema = schema.toString())
}

private fun CallToolResultBase.toRemoteToolResult(): RemoteToolResult {
    val text = content.joinToString("\n") { block ->
        (block as? TextContent)?.text ?: block.toString()
    }
    return RemoteToolResult(text = text, isError = isError == true)
}
