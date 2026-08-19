package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private val mutex = Mutex()

    suspend fun load(): FlowSettings = mutex.withLock { read() }

    suspend fun save(settings: FlowSettings): Unit = mutex.withLock { write(settings) }

    suspend fun isExternalMcpEnabled(): Boolean = mutex.withLock { read().externalMcpEnabled }

    /** Atomic read-modify-write; returns whether the stored value changed. */
    suspend fun setExternalMcpEnabled(on: Boolean): Boolean = mutex.withLock {
        val current = read()
        if (current.externalMcpEnabled == on) return@withLock false
        write(current.copy(externalMcpEnabled = on))
        true
    }

    private suspend fun read(): FlowSettings {
        val raw = storage?.getJson(KEY) ?: return FlowSettings()
        return runCatching { json.decodeFromString(FlowSettings.serializer(), raw) }.getOrDefault(FlowSettings())
    }

    private suspend fun write(settings: FlowSettings) {
        storage?.putJson(KEY, json.encodeToString(FlowSettings.serializer(), settings))
    }

    companion object { const val KEY = "settings" }
}

/** Collapse whitespace, apply literal redactions, and bound external-MCP diagnostics. */
internal fun boundedExternalMcpDiagnostic(
    raw: String?,
    fallback: String = "External MCP operation failed",
    redactions: List<String> = emptyList(),
): String {
    var safe = raw.orEmpty()
    redactions.filter { it.isNotBlank() }.forEach { secret -> safe = safe.replace(secret, "***") }
    safe = safe.replace(Regex("\\s+"), " ").trim().ifBlank { fallback }
    return safe.take(ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH)
}

/**
 * Plugin-wide owner of external-MCP persistence, lifecycle, and discovery. Every write,
 * reconcile, and live `listTools` request runs on one manager-owned IO actor. Callers only
 * await an unparented completion, so closing a dialog/tab cannot cancel an accepted request.
 * Tabs collect [descriptors] and never issue transport discovery themselves.
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

    private class Live(
        val transport: McpTransport,
        val source: ExternalMcpToolSource,
        val resolvedSecret: String?,
    )

    private sealed interface ManagerRequest {
        data class Start(val result: CompletableDeferred<Unit>) : ManagerRequest
        data class Refresh(val result: CompletableDeferred<Unit>) : ManagerRequest
        data class Upsert(val config: McpServerConfig, val result: CompletableDeferred<Unit>) : ManagerRequest
        data class Add(val config: McpServerConfig, val result: CompletableDeferred<Boolean>) : ManagerRequest
        data class SetEnabled(
            val name: String,
            val enabled: Boolean,
            val result: CompletableDeferred<Boolean>,
        ) : ManagerRequest
        data class Remove(val name: String, val result: CompletableDeferred<Boolean>) : ManagerRequest
        data class SetSettings(val enabled: Boolean, val result: CompletableDeferred<Unit>) : ManagerRequest
        data class Dispose(val result: CompletableDeferred<Unit>) : ManagerRequest
    }

    private val open = ConcurrentHashMap<String, Live>()
    private val configMutex = Mutex()
    private val mutableChangeTick = MutableStateFlow(0L)
    private val mutableServerStatuses = MutableStateFlow<Map<String, ExternalMcpServerStatus>>(emptyMap())
    private val mutableDescriptors = MutableStateFlow<List<ToolDescriptor>>(emptyList())
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + ioDispatcher)
    private val requests = Channel<ManagerRequest>(Channel.UNLIMITED)
    private val requestLock = Any()
    private var acceptingRequests = true
    private var initialized = false
    private var disposeResult: CompletableDeferred<Unit>? = null

    /** Advances once after each settled reconcile/discovery and after disposal. */
    val changeTick: StateFlow<Long> = mutableChangeTick.asStateFlow()

    /** Current connection/error state, keyed by configured server name. */
    val serverStatuses: StateFlow<Map<String, ExternalMcpServerStatus>> = mutableServerStatuses.asStateFlow()

    /** Cached descriptor snapshot from the manager's single discovery pass. */
    val descriptors: StateFlow<List<ToolDescriptor>> = mutableDescriptors.asStateFlow()

    private val worker = managerScope.launch {
        for (request in requests) {
            if (!process(request)) break
        }
        requests.close()
        managerJob.cancel()
    }

    // ---- caller-facing requests --------------------------------------------

    /** Idempotent startup: N tabs may call this, but only the first request discovers. */
    fun requestStart(): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.Start(result), result)
    }

    suspend fun start() = requestStart().await()

    /** Explicitly reconcile/discover once, owned by the manager actor. */
    fun requestRefresh(): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.Refresh(result), result)
    }

    suspend fun refresh() = requestRefresh().await()

    fun requestUpsertConfig(config: McpServerConfig): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.Upsert(config, result), result)
    }

    suspend fun upsertConfig(config: McpServerConfig) = requestUpsertConfig(config).await()

    /** Add only when [McpServerConfig.name] is unused; never silently replaces a server. */
    fun requestAddConfig(config: McpServerConfig): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.Add(config, result), result)
    }

    suspend fun addConfig(config: McpServerConfig): Boolean = requestAddConfig(config).await()

    /** Toggle the latest stored config by name; false means it was removed meanwhile. */
    fun requestSetConfigEnabled(name: String, enabled: Boolean): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.SetEnabled(name, enabled, result), result)
    }

    suspend fun setConfigEnabled(name: String, enabled: Boolean): Boolean =
        requestSetConfigEnabled(name, enabled).await()

    fun requestRemoveConfig(name: String): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.Remove(name, result), result)
    }

    suspend fun removeConfig(name: String): Boolean = requestRemoveConfig(name).await()

    fun requestSetSettingsEnabled(on: Boolean): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.SetSettings(on, result), result)
    }

    suspend fun setSettingsEnabled(on: Boolean) = requestSetSettingsEnabled(on).await()

    private fun <T> enqueue(request: ManagerRequest, result: CompletableDeferred<T>): Deferred<T> {
        val accepted = synchronized(requestLock) {
            acceptingRequests && requests.trySend(request).isSuccess
        }
        if (!accepted) result.completeExceptionally(IllegalStateException(DISPOSED_MESSAGE))
        // This completion has no caller Job parent. Cancelling an awaiter does not cancel
        // the synchronously accepted actor request or its write/reconcile/discovery work.
        return result
    }

    // ---- config/settings reads ---------------------------------------------

    suspend fun listConfigs(): List<McpServerConfig> = withContext(ioDispatcher) {
        configMutex.withLock { readConfigs() }
    }

    suspend fun settingsEnabled(): Boolean = withContext(ioDispatcher) {
        settings.isExternalMcpEnabled()
    }

    private suspend fun readConfigs(): List<McpServerConfig> {
        val raw = storage?.getJson(CONFIG_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    private suspend fun writeConfigs(configs: List<McpServerConfig>) {
        storage?.putJson(CONFIG_KEY, json.encodeToString(listSerializer, configs))
    }

    // ---- single actor -------------------------------------------------------

    /** Return false only for Dispose, which is the sole actor-termination path. */
    private suspend fun process(request: ManagerRequest): Boolean {
        return try {
            when (request) {
                is ManagerRequest.Start -> {
                    if (!initialized) reconcileAndDiscover()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Refresh -> {
                    reconcileAndDiscover()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Upsert -> {
                    val changed = configMutex.withLock {
                        val current = readConfigs()
                        val next = current.filter { it.name != request.config.name } + request.config
                        if (next == current) false else {
                            writeConfigs(next)
                            true
                        }
                    }
                    if (changed) reconcileAndDiscover()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Add -> {
                    val added = configMutex.withLock {
                        val current = readConfigs()
                        if (current.any { it.name == request.config.name }) false else {
                            writeConfigs(current + request.config)
                            true
                        }
                    }
                    if (added) reconcileAndDiscover()
                    request.result.complete(added)
                    true
                }
                is ManagerRequest.SetEnabled -> {
                    var found = false
                    var changed = false
                    configMutex.withLock {
                        val current = readConfigs()
                        val next = current.map { cfg ->
                            if (cfg.name == request.name) {
                                found = true
                                cfg.copy(enabled = request.enabled).also { changed = changed || it != cfg }
                            } else {
                                cfg
                            }
                        }
                        if (changed) writeConfigs(next)
                    }
                    if (changed) reconcileAndDiscover()
                    request.result.complete(found)
                    true
                }
                is ManagerRequest.Remove -> {
                    val removed = configMutex.withLock {
                        val current = readConfigs()
                        val next = current.filter { it.name != request.name }
                        if (next == current) false else {
                            writeConfigs(next)
                            true
                        }
                    }
                    if (removed) reconcileAndDiscover()
                    request.result.complete(removed)
                    true
                }
                is ManagerRequest.SetSettings -> {
                    if (settings.setExternalMcpEnabled(request.enabled)) reconcileAndDiscover()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Dispose -> {
                    disposeOwnedState()
                    request.result.complete(Unit)
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                request.fail(cancelled)
                throw cancelled
            }
            // A transport can originate CancellationException while the actor remains
            // healthy. Fail only that request with a sanitized cancellation; manager
            // disposal is the sole worker stop.
            request.fail(sanitizedCancellation(cancelled))
            true
        } catch (failure: Exception) {
            request.fail(
                IllegalStateException(
                    boundedExternalMcpDiagnostic(failure.message),
                ),
            )
            true
        } catch (fatal: Error) {
            request.fail(fatal)
            throw fatal
        }
    }

    private fun ManagerRequest.fail(failure: Throwable) {
        when (this) {
            is ManagerRequest.Start -> result.completeExceptionally(failure)
            is ManagerRequest.Refresh -> result.completeExceptionally(failure)
            is ManagerRequest.Upsert -> result.completeExceptionally(failure)
            is ManagerRequest.Add -> result.completeExceptionally(failure)
            is ManagerRequest.SetEnabled -> result.completeExceptionally(failure)
            is ManagerRequest.Remove -> result.completeExceptionally(failure)
            is ManagerRequest.SetSettings -> result.completeExceptionally(failure)
            is ManagerRequest.Dispose -> result.completeExceptionally(failure)
        }
    }

    // ---- manager-owned reconcile/discovery ---------------------------------

    private suspend fun reconcileAndDiscover() {
        val configs = configMutex.withLock { readConfigs() }
        val featureEnabled = settings.isExternalMcpEnabled()
        val enabled = if (featureEnabled) configs.filter { it.enabled }.associateBy { it.name } else emptyMap()

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

        for (name in open.keys.toList()) {
            if (name !in enabled) closeOne(name)
        }

        for ((name, cfg) in enabled) {
            if (open.containsKey(name)) continue
            setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTING))
            var transport: McpTransport? = null
            var resolvedSecret: String? = null
            try {
                resolvedSecret = cfg.secretRef?.let { secrets.get(it) }
                transport = transportFactory(cfg, resolvedSecret)
                transport.connect()
                open[name] = Live(transport, ExternalMcpToolSource(name, transport), resolvedSecret)
            } catch (cancelled: CancellationException) {
                transport?.let { opened ->
                    runCatching { withContext(NonCancellable) { opened.close() } }
                }
                setStatus(
                    name,
                    ExternalMcpServerStatus(
                        ExternalMcpServerState.ERROR,
                        failureDetail("Connection cancelled", cancelled, resolvedSecret),
                    ),
                )
                if (!currentCoroutineContext().isActive) throw cancelled
                throw sanitizedCancellation(cancelled, resolvedSecret)
            } catch (failure: Exception) {
                transport?.let { runCatching { it.close() } }
                val detail = failureDetail("Connection failed", failure, resolvedSecret)
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                logFailure("external MCP server", name, "failed to connect", detail)
            }
        }

        val discovered = buildList {
            for ((name, live) in open.entries.sortedBy { it.key }) {
                try {
                    addAll(live.source.list())
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTED))
                } catch (cancelled: CancellationException) {
                    setStatus(
                        name,
                        ExternalMcpServerStatus(
                            ExternalMcpServerState.ERROR,
                            failureDetail("Tool discovery cancelled", cancelled, live.resolvedSecret),
                        ),
                    )
                    if (!currentCoroutineContext().isActive) throw cancelled
                    throw sanitizedCancellation(cancelled, live.resolvedSecret)
                } catch (failure: Exception) {
                    val detail = failureDetail("Tool discovery failed", failure, live.resolvedSecret)
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                    logFailure("external MCP server", name, "failed to list tools", detail)
                }
            }
        }
        mutableDescriptors.value = discovered
        initialized = true
        mutableChangeTick.value += 1
    }

    private suspend fun closeOne(name: String) {
        val live = open.remove(name) ?: return
        try {
            live.transport.close()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            val detail = failureDetail("Close cancelled", cancelled)
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            logFailure("external MCP server", name, "failed to close", detail)
        } catch (failure: Exception) {
            val detail = failureDetail("Close failed", failure)
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            logFailure("external MCP server", name, "failed to close", detail)
        }
    }

    // ---- cached ToolSource --------------------------------------------------

    /** Cached manager-owned discovery; never performs transport I/O for a tab/agent. */
    override suspend fun list(): List<ToolDescriptor> = descriptors.value

    override suspend fun invoke(name: String, argsJson: String): ToolResult {
        val server = name.substringBefore('/', "")
        val live = open[server]
            ?: return ToolResult("No external MCP server '$server' is connected", isError = true)
        return live.source.invoke(name, argsJson)
    }

    // ---- disposal -----------------------------------------------------------

    /**
     * Stop accepting requests, queue disposal after every already-accepted request,
     * safely reap transports and publish the terminal snapshot, then terminate the actor.
     * Caller cancellation only stops awaiting; the manager-owned disposal keeps running.
     */
    suspend fun disposeAll() {
        val result = synchronized(requestLock) {
            disposeResult ?: CompletableDeferred<Unit>().also { completion ->
                acceptingRequests = false
                disposeResult = completion
                if (requests.trySend(ManagerRequest.Dispose(completion)).isFailure) {
                    completion.completeExceptionally(IllegalStateException("External MCP disposal could not be queued"))
                }
            }
        }
        result.await()
        worker.join()
    }

    private suspend fun disposeOwnedState() {
        for (name in open.keys.toList()) closeOne(name)
        mutableDescriptors.value = emptyList()
        replaceStatuses(
            mutableServerStatuses.value.mapValues { _ ->
                ExternalMcpServerStatus(ExternalMcpServerState.DISCONNECTED, "Disconnected")
            },
        )
        initialized = true
        mutableChangeTick.value += 1
    }

    private fun replaceStatuses(statuses: Map<String, ExternalMcpServerStatus>) {
        mutableServerStatuses.value = statuses
    }

    private fun setStatus(name: String, status: ExternalMcpServerStatus) {
        mutableServerStatuses.update { current ->
            if (name in current) current + (name to status) else current
        }
    }

    private fun failureDetail(prefix: String, failure: Throwable, resolvedSecret: String? = null): String =
        boundedExternalMcpDiagnostic(
            raw = "$prefix: ${failure.message.orEmpty()}",
            fallback = prefix,
            redactions = listOfNotNull(resolvedSecret),
        )

    private fun sanitizedCancellation(
        cancelled: CancellationException,
        resolvedSecret: String? = null,
    ): CancellationException = CancellationException(
        boundedExternalMcpDiagnostic(
            cancelled.message,
            fallback = "External MCP operation cancelled",
            redactions = listOfNotNull(resolvedSecret),
        ),
    )

    private fun logFailure(subject: String, serverName: String, action: String, detail: String) {
        val safeName = boundedExternalMcpDiagnostic(serverName, fallback = "unnamed server")
        log(boundedExternalMcpDiagnostic("$subject '$safeName' $action: $detail"))
    }

    companion object {
        const val CONFIG_KEY = "mcpservers:config"
        internal const val MAX_STATUS_DETAIL_LENGTH = 240
        private const val DISPOSED_MESSAGE = "External MCP manager is disposed"
    }
}

/**
 * Apply manager-owned descriptor snapshots to one registry. Startup is idempotent in the
 * manager; this collector performs no connection/discovery I/O and is safe per tab.
 */
fun syncExternalMcpTools(manager: ExternalMcpManager, registry: NodeRegistry, scope: CoroutineScope): Job {
    val sync = ToolNodeSync(manager, registry)
    return scope.launch {
        try {
            manager.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Still mirror any last successfully published snapshot.
        }
        manager.descriptors.collect { snapshot -> sync.apply(snapshot) }
    }
}
