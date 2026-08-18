package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

enum class ExternalMcpServerState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** User-facing, bounded connection state for one configured external MCP server. */
data class ExternalMcpServerStatus(
    val state: ExternalMcpServerState,
    val detail: String? = null,
)

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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ToolSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(McpServerConfig.serializer())

    private class Live(val transport: McpTransport, val source: ExternalMcpToolSource)

    private val open = ConcurrentHashMap<String, Live>()
    /** Serializes [refresh]/[disposeAll] so connects and reaps never interleave. */
    private val lifecycleMutex = Mutex()
    /** Prevents read-modify-write config races between multiple open settings dialogs. */
    private val configMutex = Mutex()
    private val mutableChangeTick = MutableStateFlow(0L)
    private val mutableServerStatuses = MutableStateFlow<Map<String, ExternalMcpServerStatus>>(emptyMap())

    /** Advances after each settled connection reconciliation. Every tab collects this. */
    val changeTick: StateFlow<Long> = mutableChangeTick.asStateFlow()

    /** Current connection/error state, keyed by configured server name. */
    val serverStatuses: StateFlow<Map<String, ExternalMcpServerStatus>> = mutableServerStatuses.asStateFlow()

    // ---- config CRUD (secret-free, persisted) -------------------------------

    suspend fun listConfigs(): List<McpServerConfig> = withContext(ioDispatcher) {
        configMutex.withLock { readConfigs() }
    }

    private suspend fun readConfigs(): List<McpServerConfig> {
        val raw = storage?.getJson(CONFIG_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun upsertConfig(config: McpServerConfig): Unit = withContext(ioDispatcher) {
        configMutex.withLock {
            val next = readConfigs().filter { it.name != config.name } + config
            writeConfigs(next)
        }
    }

    /** Add only when [McpServerConfig.name] is unused; never silently replaces a server. */
    suspend fun addConfig(config: McpServerConfig): Boolean = withContext(ioDispatcher) {
        configMutex.withLock {
            val current = readConfigs()
            if (current.any { it.name == config.name }) return@withLock false
            writeConfigs(current + config)
            true
        }
    }

    suspend fun removeConfig(name: String): Unit = withContext(ioDispatcher) {
        configMutex.withLock { writeConfigs(readConfigs().filter { it.name != name }) }
    }

    private suspend fun writeConfigs(configs: List<McpServerConfig>) {
        storage?.putJson(CONFIG_KEY, json.encodeToString(listSerializer, configs))
    }

    /** Whether the external-MCP feature flag is on (config UI convenience). */
    suspend fun settingsEnabled(): Boolean = withContext(ioDispatcher) { settings.isExternalMcpEnabled() }

    /** Flip the external-MCP feature flag (config UI convenience). */
    suspend fun setSettingsEnabled(on: Boolean) = withContext(ioDispatcher) { settings.setExternalMcpEnabled(on) }

    // ---- connection lifecycle (red-team F9) ---------------------------------

    /**
     * Reconcile live connections with the current config + feature flag: connect newly
     * enabled servers, close servers that were disabled or removed. When the flag is off,
     * everything is torn down. A single server failing to connect is logged and skipped —
     * it never blocks the others. Idempotent; safe to call on any config/flag change.
     */
    suspend fun refresh(): Unit = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            try {
                val configs = listConfigs()
                val featureEnabled = settings.isExternalMcpEnabled()
                val enabled = if (featureEnabled) {
                    configs.filter { it.enabled }.associateBy { it.name }
                } else {
                    emptyMap()
                }

                replaceStatuses(
                    configs.associate { cfg ->
                        cfg.name to when {
                            !featureEnabled -> ExternalMcpServerStatus(
                                ExternalMcpServerState.DISCONNECTED,
                                "External MCP is disabled",
                            )
                            !cfg.enabled -> ExternalMcpServerStatus(ExternalMcpServerState.DISCONNECTED, "Disabled")
                            open.containsKey(cfg.name) -> ExternalMcpServerStatus(ExternalMcpServerState.CONNECTED)
                            else -> ExternalMcpServerStatus(ExternalMcpServerState.CONNECTING)
                        }
                    },
                )

                // Reap connections that are no longer wanted.
                for (name in open.keys.toList()) {
                    if (name !in enabled) closeOne(name)
                }

                // Bring up connections that are wanted but not yet open.
                for ((name, cfg) in enabled) {
                    if (open.containsKey(name)) continue
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTING))
                    var transport: McpTransport? = null
                    try {
                        val secret = cfg.secretRef?.let { secrets.get(it) }
                        transport = transportFactory(cfg, secret)
                        transport.connect()
                        open[name] = Live(transport, ExternalMcpToolSource(name, transport))
                        setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTED))
                    } catch (cancelled: CancellationException) {
                        transport?.let { opened ->
                            runCatching { withContext(NonCancellable) { opened.close() } }
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        transport?.let { runCatching { it.close() } }
                        val detail = boundedFailure("Connection failed", failure)
                        setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                        log("external MCP server '$name' failed to connect: $detail")
                    }
                }
            } finally {
                mutableChangeTick.value += 1
            }
        }
    }

    private suspend fun closeOne(name: String) {
        val live = open.remove(name) ?: return
        try {
            live.transport.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = boundedFailure("Close failed", failure)
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            log("closing '$name' failed: $detail")
        }
    }

    /** Close and reap every open transport (call from plugin `dispose()`; no zombies). */
    suspend fun disposeAll(): Unit = withContext(ioDispatcher) {
        lifecycleMutex.withLock {
            try {
                for (name in open.keys.toList()) closeOne(name)
                replaceStatuses(
                    mutableServerStatuses.value.mapValues {
                        ExternalMcpServerStatus(ExternalMcpServerState.DISCONNECTED, "Disconnected")
                    },
                )
            } finally {
                mutableChangeTick.value += 1
            }
        }
    }

    // ---- aggregate ToolSource ----------------------------------------------

    /** Every open server's namespaced tools; empty when the flag is off / nothing open. */
    override suspend fun list(): List<ToolDescriptor> = withContext(ioDispatcher) {
        open.entries.sortedBy { it.key }.flatMap { (name, live) ->
            try {
                live.source.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = boundedFailure("Tool discovery failed", failure)
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                log("external MCP server '$name' failed to list tools: $detail")
                emptyList()
            }
        }
    }

    /** Route [name] (`<server>/<tool>`) to its owning server; unknown server → error. */
    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val server = name.substringBefore('/', "")
        val live = open[server]
            ?: return ToolResult("No external MCP server '$server' is connected", isError = true)
        return live.source.invoke(name, argsJson)
    }

    private fun replaceStatuses(statuses: Map<String, ExternalMcpServerStatus>) {
        mutableServerStatuses.value = statuses
    }

    private fun setStatus(name: String, status: ExternalMcpServerStatus) {
        mutableServerStatuses.update { current ->
            if (name in current) current + (name to status) else current
        }
    }

    private fun boundedFailure(prefix: String, failure: Throwable): String {
        val message = failure.message.orEmpty().replace(Regex("\\s+"), " ").trim().ifBlank {
            failure::class.simpleName ?: "Unknown error"
        }
        return "$prefix: $message".take(MAX_STATUS_DETAIL_LENGTH)
    }

    companion object {
        const val CONFIG_KEY = "mcpservers:config"
        internal const val MAX_STATUS_DETAIL_LENGTH = 240
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
        try {
            manager.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The change collector below still mirrors any already-open servers.
        }
        manager.changeTick.collectLatest {
            val descriptors = try {
                manager.list()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            sync.apply(descriptors)
        }
    }
}
