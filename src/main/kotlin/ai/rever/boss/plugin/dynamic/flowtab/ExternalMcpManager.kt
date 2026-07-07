package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds an [McpTransport] for a server [McpServerConfig] given its already-resolved
 * secret (or null). Kept as a seam so tests inject an in-memory transport and the real
 * process/socket transports ([defaultMcpTransport]) stay out of the unit-tested core.
 */
typealias McpTransportFactory = (config: McpServerConfig, secret: String?) -> McpTransport

/** Plugin-wide settings persisted at [SettingsStore.KEY]. External MCP is OFF by default
 *  (red-team F9: the whole outward-process surface is opt-in). */
@kotlinx.serialization.Serializable
data class FlowSettings(val externalMcpEnabled: Boolean = false)

/** Reads/writes [FlowSettings] through plugin storage; null-safe when storage is absent. */
class SettingsStore(private val storage: PluginStorageProvider?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(): FlowSettings {
        val raw = storage?.getJson(KEY) ?: return FlowSettings()
        return runCatching { json.decodeFromString(FlowSettings.serializer(), raw) }.getOrDefault(FlowSettings())
    }

    suspend fun save(settings: FlowSettings) {
        storage?.putJson(KEY, json.encodeToString(FlowSettings.serializer(), settings))
    }

    suspend fun isExternalMcpEnabled(): Boolean = load().externalMcpEnabled
    suspend fun setExternalMcpEnabled(on: Boolean) = save(load().copy(externalMcpEnabled = on))

    companion object { const val KEY = "settings" }
}

/**
 * Connection manager for external MCP servers — the P7 surface, feature-flagged OFF by
 * default. It owns:
 *  - **config** — a secret-free [McpServerConfig] list persisted at [CONFIG_KEY]; secrets
 *    live only in the host secret store, resolved at connect time by [secrets] (F: no
 *    secret ever touches config or a graph).
 *  - **connections** — one [ExternalMcpToolSource] per enabled server, brought up/torn
 *    down by [refresh] and reaped by [disposeAll] (red-team F9 lifecycle).
 *  - **gating** — when [SettingsStore.isExternalMcpEnabled] is false, nothing connects and
 *    [list]/[invoke] expose no external tools at all.
 *
 * It is itself a [ToolSource]: [list] aggregates every open server's namespaced tools and
 * [invoke] routes by the `<server>/` prefix, so external tools merge into the same tool
 * set as boss/browser tools — becoming both nodes and agent-callable.
 */
class ExternalMcpManager(
    private val storage: PluginStorageProvider?,
    private val secrets: SecretResolver,
    private val settings: SettingsStore,
    private val transportFactory: McpTransportFactory = ::defaultMcpTransport,
    private val log: (String) -> Unit = {},
) : ToolSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(McpServerConfig.serializer())

    private class Live(val transport: McpTransport, val source: ExternalMcpToolSource)

    private val open = ConcurrentHashMap<String, Live>()
    /** Serializes [refresh]/[disposeAll] so connects and reaps never interleave. */
    private val lifecycleMutex = Mutex()

    // ---- config CRUD (secret-free, persisted) -------------------------------

    suspend fun listConfigs(): List<McpServerConfig> {
        val raw = storage?.getJson(CONFIG_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun upsertConfig(config: McpServerConfig) {
        val next = listConfigs().filter { it.name != config.name } + config
        writeConfigs(next)
    }

    suspend fun removeConfig(name: String) {
        writeConfigs(listConfigs().filter { it.name != name })
    }

    private suspend fun writeConfigs(configs: List<McpServerConfig>) {
        storage?.putJson(CONFIG_KEY, json.encodeToString(listSerializer, configs))
    }

    /** Whether the external-MCP feature flag is on (config UI convenience). */
    suspend fun settingsEnabled(): Boolean = settings.isExternalMcpEnabled()

    /** Flip the external-MCP feature flag (config UI convenience). */
    suspend fun setSettingsEnabled(on: Boolean) = settings.setExternalMcpEnabled(on)

    // ---- connection lifecycle (red-team F9) ---------------------------------

    /**
     * Reconcile live connections with the current config + feature flag: connect newly
     * enabled servers, close servers that were disabled or removed. When the flag is off,
     * everything is torn down. A single server failing to connect is logged and skipped —
     * it never blocks the others. Idempotent; safe to call on any config/flag change.
     */
    suspend fun refresh(): Unit = lifecycleMutex.withLock {
        val enabled = if (settings.isExternalMcpEnabled()) {
            listConfigs().filter { it.enabled }.associateBy { it.name }
        } else {
            emptyMap()
        }

        // Reap connections that are no longer wanted.
        for (name in open.keys.toList()) {
            if (name !in enabled) closeOne(name)
        }

        // Bring up connections that are wanted but not yet open.
        for ((name, cfg) in enabled) {
            if (open.containsKey(name)) continue
            runCatching {
                val secret = cfg.secretRef?.let { secrets.get(it) }
                val transport = transportFactory(cfg, secret)
                transport.connect()
                open[name] = Live(transport, ExternalMcpToolSource(name, transport))
            }.onFailure { log("external MCP server '$name' failed to connect: ${it.message}") }
        }
    }

    private suspend fun closeOne(name: String) {
        val live = open.remove(name) ?: return
        runCatching { live.transport.close() }.onFailure { log("closing '$name' failed: ${it.message}") }
    }

    /** Close and reap every open transport (call from plugin `dispose()`; no zombies). */
    suspend fun disposeAll(): Unit = lifecycleMutex.withLock {
        for (name in open.keys.toList()) closeOne(name)
    }

    // ---- aggregate ToolSource ----------------------------------------------

    /** Every open server's namespaced tools; empty when the flag is off / nothing open. */
    override suspend fun list(): List<ToolDescriptor> =
        open.values.flatMap { runCatching { it.source.list() }.getOrDefault(emptyList()) }

    /** Route [name] (`<server>/<tool>`) to its owning server; unknown server → error. */
    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val server = name.substringBefore('/', "")
        val live = open[server]
            ?: return ToolResult("No external MCP server '$server' is connected", isError = true)
        return live.source.invoke(name, argsJson)
    }

    companion object {
        const val CONFIG_KEY = "mcpservers:config"
    }
}

/**
 * Bring [manager]'s external tools into [registry] as palette/dispatch nodes on [scope]:
 * [ExternalMcpManager.refresh] connects the enabled servers (a no-op when the feature flag
 * is off), then a [ToolNodeSync] registers a `tool:ext:<server>/<tool>` spec per tool and
 * drops any that vanished. Re-run after a config or flag change. Returns the launched job.
 * Failures are swallowed so a bad external server never breaks the tab.
 */
fun syncExternalMcpTools(manager: ExternalMcpManager, registry: NodeRegistry, scope: CoroutineScope): Job {
    val sync = ToolNodeSync(manager, registry)
    return scope.launch {
        runCatching {
            manager.refresh()
            sync.apply(manager.list())
        }
    }
}
