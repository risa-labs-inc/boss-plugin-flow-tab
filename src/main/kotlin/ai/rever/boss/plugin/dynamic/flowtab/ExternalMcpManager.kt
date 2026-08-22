package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/** Remove controls, collapse whitespace, apply literal redactions, and bound diagnostics. */
internal fun boundedExternalMcpDiagnostic(
    raw: String?,
    fallback: String = "External MCP operation failed",
    redactions: List<String> = emptyList(),
): String {
    var safe = raw.orEmpty()
    redactions.filter { it.isNotBlank() }.forEach { secret -> safe = safe.replace(secret, "***") }
    safe = safe.map { char ->
        if (char.isISOControl() || char == '\u2028' || char == '\u2029') ' ' else char
    }.joinToString("")
    safe = safe.replace(Regex("\\s+"), " ").trim().ifBlank { fallback }
    return safe.take(ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH)
}

/** Names are embedded in the `<server>/<tool>` routing key and must be one safe segment. */
internal fun normalizedExternalMcpServerName(raw: String): String? {
    val name = raw.trim()
    return name.takeIf { it.isNotEmpty() && '/' !in it && it.none { char -> char.isISOControl() } }
}

internal fun normalizedExternalMcpConfig(config: McpServerConfig): McpServerConfig? {
    val name = normalizedExternalMcpServerName(config.name) ?: return null
    return config.copy(
        name = name,
        workingDirectory = if (config.kind == McpTransportKind.STDIO) config.workingDirectory.trim() else "",
        secretRef = if (config.kind == McpTransportKind.STDIO) null else config.secretRef,
    )
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
    /** Cooperative deadline applied independently to each server connect, discovery, and close. */
    private val serverOperationTimeoutMs: Long = DEFAULT_SERVER_OPERATION_TIMEOUT_MS,
    /** Minimum delay between automatic, headless retries after an unsettled pass. */
    private val implicitRetryCooldownMs: Long = DEFAULT_IMPLICIT_RETRY_COOLDOWN_MS,
    /** Maximum Agent-listing latency spent awaiting an automatic external-MCP retry. */
    private val implicitRetryAwaitTimeoutMs: Long = DEFAULT_IMPLICIT_RETRY_AWAIT_TIMEOUT_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ToolSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(McpServerConfig.serializer())

    private class Live(
        val config: McpServerConfig,
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

    private enum class TerminalState { ACTIVE, DISPOSED, CRASHED }

    private val open = ConcurrentHashMap<String, Live>()
    private val configMutex = Mutex()
    private val implicitRetryMutex = Mutex()
    private var implicitRetryInFlight: Deferred<Unit>? = null
    private val mutableChangeTick = MutableStateFlow(0L)
    private val mutableServerStatuses = MutableStateFlow<Map<String, ExternalMcpServerStatus>>(emptyMap())
    private val mutableDescriptors = MutableStateFlow<List<ToolDescriptor>>(emptyList())
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + ioDispatcher)
    private val requests = Channel<ManagerRequest>(Channel.UNLIMITED)
    private val requestLock = Any()
    private var acceptingRequests = true
    private var terminalState = TerminalState.ACTIVE
    @Volatile
    private var initialized = false
    @Volatile
    private var lastReconcileFinishedAtMs = Long.MIN_VALUE
    private var disposeResult: CompletableDeferred<Unit>? = null

    /** Advances once after each settled reconcile/discovery and after disposal. */
    val changeTick: StateFlow<Long> = mutableChangeTick.asStateFlow()

    /** Current connection/error state, keyed by configured server name. */
    val serverStatuses: StateFlow<Map<String, ExternalMcpServerStatus>> = mutableServerStatuses.asStateFlow()

    /** Cached descriptor snapshot from the manager's single discovery pass. */
    val descriptors: StateFlow<List<ToolDescriptor>> = mutableDescriptors.asStateFlow()

    private val worker = managerScope.async(start = CoroutineStart.LAZY) {
        try {
            for (request in requests) {
                if (!process(request)) break
            }
        } finally {
            stopAcceptanceAndDrain()
            // A forced cancellation can race a successful connect being published to
            // [open]. Reap anything the synchronous cancelNow snapshot did not observe
            // before allowing this worker (and its plugin classloader) to terminate.
            try {
                closeDetachedLives(detachOpenLives())
            } catch (fatal: Error) {
                logActorFailure("forced cleanup failed", fatal)
            } finally {
                managerJob.cancel()
            }
        }
    }

    init {
        require(serverOperationTimeoutMs > 0) { "External MCP server operation timeout must be positive" }
        require(implicitRetryCooldownMs >= 0) { "External MCP implicit retry cooldown must not be negative" }
        require(implicitRetryAwaitTimeoutMs > 0) { "External MCP implicit retry await timeout must be positive" }
        worker.start()
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
        val normalized = normalizedExternalMcpConfig(config)
            ?: return rejectedRequest(INVALID_SERVER_NAME_MESSAGE)
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.Upsert(normalized, result), result)
    }

    suspend fun upsertConfig(config: McpServerConfig) = requestUpsertConfig(config).await()

    /** Add only when [McpServerConfig.name] is unused; never silently replaces a server. */
    fun requestAddConfig(config: McpServerConfig): Deferred<Boolean> {
        val normalized = normalizedExternalMcpConfig(config)
            ?: return rejectedRequest(INVALID_SERVER_NAME_MESSAGE)
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.Add(normalized, result), result)
    }

    suspend fun addConfig(config: McpServerConfig): Boolean = requestAddConfig(config).await()

    /** Toggle the latest stored config by name; false means it was removed meanwhile. */
    fun requestSetConfigEnabled(name: String, enabled: Boolean): Deferred<Boolean> {
        val normalizedName = normalizedExternalMcpServerName(name)
            ?: return rejectedRequest(INVALID_SERVER_NAME_MESSAGE)
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.SetEnabled(normalizedName, enabled, result), result)
    }

    suspend fun setConfigEnabled(name: String, enabled: Boolean): Boolean =
        requestSetConfigEnabled(name, enabled).await()

    fun requestRemoveConfig(name: String): Deferred<Boolean> {
        val normalizedName = normalizedExternalMcpServerName(name)
            ?: return rejectedRequest(INVALID_SERVER_NAME_MESSAGE)
        val result = CompletableDeferred<Boolean>()
        return enqueue(ManagerRequest.Remove(normalizedName, result), result)
    }

    suspend fun removeConfig(name: String): Boolean = requestRemoveConfig(name).await()

    fun requestSetSettingsEnabled(on: Boolean): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        return enqueue(ManagerRequest.SetSettings(on, result), result)
    }

    suspend fun setSettingsEnabled(on: Boolean) = requestSetSettingsEnabled(on).await()

    private fun <T> rejectedRequest(message: String): Deferred<T> =
        CompletableDeferred<T>().also { it.completeExceptionally(IllegalArgumentException(message)) }

    private fun <T> enqueue(request: ManagerRequest, result: CompletableDeferred<T>): Deferred<T> {
        val rejection = synchronized(requestLock) {
            if (acceptingRequests && requests.trySend(request).isSuccess) null else terminalFailureLocked()
        }
        rejection?.let { result.completeExceptionally(it) }
        // This completion has no caller Job parent. Cancelling an awaiter does not cancel
        // the synchronously accepted actor request or its write/reconcile/discovery work.
        return result
    }

    /**
     * Synchronous, idempotent teardown escape hatch for plugin unload. It rejects new
     * work immediately, fails queued requests, and cancels the actor even when a bounded
     * [disposeAll] attempt could not finish.
     */
    fun cancelNow(): Job {
        stopAcceptanceAndDrain()
        // Detach already-open transports before cancelling the actor. Their bounded
        // closes must not inherit managerJob cancellation, otherwise a timed-out plugin
        // unload can orphan stdio children or HTTP clients.
        val cleanup = launchForcedCleanup(detachOpenLives())
        managerJob.cancel(CancellationException(terminalMessage()))
        return cleanup
    }

    private fun isAcceptingRequests(): Boolean = synchronized(requestLock) { acceptingRequests }

    private fun stopAcceptanceAndDrain() {
        synchronized(requestLock) {
            acceptingRequests = false
            if (terminalState == TerminalState.ACTIVE) terminalState = TerminalState.DISPOSED
            requests.close()
            while (true) {
                val pending = requests.tryReceive().getOrNull() ?: break
                pending.fail(terminalFailureLocked())
            }
        }
    }

    private fun markCrashed(fatal: Error) {
        val shouldLog = synchronized(requestLock) {
            if (terminalState == TerminalState.CRASHED) {
                false
            } else {
                acceptingRequests = false
                terminalState = TerminalState.CRASHED
                true
            }
        }
        if (shouldLog) logActorFailure("crashed; reload the plugin", fatal)
    }

    private fun terminalMessage(): String = synchronized(requestLock) { terminalMessageLocked() }

    private fun terminalMessageLocked(): String = when (terminalState) {
        TerminalState.CRASHED -> CRASHED_MESSAGE
        TerminalState.ACTIVE, TerminalState.DISPOSED -> DISPOSED_MESSAGE
    }

    private fun terminalFailureLocked(): IllegalStateException = publicFailure(terminalMessageLocked())

    // ---- config/settings reads ---------------------------------------------

    suspend fun listConfigs(): List<McpServerConfig> = withContext(ioDispatcher) {
        configMutex.withLock { readConfigs() }
    }

    suspend fun settingsEnabled(): Boolean = withContext(ioDispatcher) {
        settings.isExternalMcpEnabled()
    }

    private suspend fun readConfigs(): List<McpServerConfig> {
        val raw = storage?.getJson(CONFIG_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
            .mapNotNull(::normalizedExternalMcpConfig)
    }

    private suspend fun writeConfigs(configs: List<McpServerConfig>) {
        val normalized = configs.mapNotNull(::normalizedExternalMcpConfig)
        storage?.putJson(CONFIG_KEY, json.encodeToString(listSerializer, normalized))
    }

    // ---- single actor -------------------------------------------------------

    /** Return false only for Dispose, which is the sole actor-termination path. */
    private suspend fun process(request: ManagerRequest): Boolean {
        return try {
            when (request) {
                is ManagerRequest.Start -> {
                    if (!initialized) reconcileAndDiscoverRecorded()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Refresh -> {
                    reconcileAndDiscoverRecorded()
                    request.result.complete(Unit)
                    true
                }
                is ManagerRequest.Upsert -> {
                    val changed = configMutex.withLock {
                        val current = readConfigs()
                        val next = if (current.any { it.name == request.config.name }) {
                            current.map { if (it.name == request.config.name) request.config else it }
                        } else {
                            current + request.config
                        }
                        if (next == current) false else {
                            writeConfigs(next)
                            true
                        }
                    }
                    if (changed) reconcileAndDiscoverRecorded()
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
                    if (added) reconcileAndDiscoverRecorded()
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
                    if (changed) reconcileAndDiscoverRecorded()
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
                    if (removed) reconcileAndDiscoverRecorded()
                    request.result.complete(removed)
                    true
                }
                is ManagerRequest.SetSettings -> {
                    if (settings.setExternalMcpEnabled(request.enabled)) reconcileAndDiscoverRecorded()
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
                request.fail(publicCancellation())
                throw cancelled
            }
            // A transport can originate CancellationException while the actor remains
            // healthy. Fail only that request with a payload-free public cancellation;
            // manager disposal is the sole worker stop.
            request.fail(publicCancellation())
            true
        } catch (failure: Exception) {
            logActorFailure("operation failed", failure)
            request.fail(publicFailure())
            true
        } catch (fatal: Error) {
            markCrashed(fatal)
            request.fail(publicFailure(CRASHED_MESSAGE))
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

    private suspend fun reconcileAndDiscoverRecorded() {
        try {
            reconcileAndDiscover()
        } finally {
            lastReconcileFinishedAtMs = nowMillis()
        }
    }

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
            if (!isAcceptingRequests()) break
            if (name !in enabled) closeOne(name)
        }

        for ((name, cfg) in enabled) {
            if (!isAcceptingRequests()) break
            var transport: McpTransport? = null
            var resolvedSecret: String? = null
            try {
                resolvedSecret = cfg.secretRef?.let { secrets.get(it) }
                val current = open[name]
                if (current != null) {
                    if (current.config == cfg && current.resolvedSecret == resolvedSecret) continue
                    closeOne(name)
                    if (!isAcceptingRequests()) break
                }
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTING))
                val opened = transportFactory(cfg, resolvedSecret)
                transport = opened
                withTimeout(serverOperationTimeoutMs) { opened.connect() }
                open[name] = Live(cfg, opened, ExternalMcpToolSource(name, opened), resolvedSecret)
            } catch (_: TimeoutCancellationException) {
                transport?.let { closeFailedOpen(it) }
                val detail = timeoutDetail("Connection")
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                logFailure("external MCP server", name, "timed out while connecting", detail)
            } catch (cancelled: CancellationException) {
                transport?.let { opened ->
                    closeFailedOpen(opened)
                }
                val detail = failureDetail("Connection cancelled", cancelled, resolvedSecret)
                setStatus(
                    name,
                    ExternalMcpServerStatus(
                        ExternalMcpServerState.ERROR,
                        detail,
                    ),
                )
                if (!currentCoroutineContext().isActive) throw cancelled
                logFailure(
                    "external MCP server",
                    name,
                    "cancelled while connecting",
                    detail,
                )
            } catch (failure: Exception) {
                transport?.let { closeFailedOpen(it) }
                val detail = failureDetail("Connection failed", failure, resolvedSecret)
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                logFailure("external MCP server", name, "failed to connect", detail)
            } catch (fatal: Error) {
                transport?.let { opened ->
                    try {
                        closeFailedOpen(opened)
                    } catch (cleanupFatal: Error) {
                        fatal.addSuppressed(cleanupFatal)
                    }
                }
                throw fatal
            }
        }

        val discovered = buildList {
            for ((name, live) in open.entries.sortedBy { it.key }) {
                if (!isAcceptingRequests()) break
                try {
                    addAll(withTimeout(serverOperationTimeoutMs) { live.source.list() })
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.CONNECTED))
                } catch (_: TimeoutCancellationException) {
                    open.remove(name, live)
                    closeFailedOpen(live.transport)
                    val detail = timeoutDetail("Tool discovery")
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                    logFailure("external MCP server", name, "timed out while listing tools", detail)
                } catch (cancelled: CancellationException) {
                    open.remove(name, live)
                    closeFailedOpen(live.transport)
                    val detail = failureDetail("Tool discovery cancelled", cancelled, live.resolvedSecret)
                    setStatus(
                        name,
                        ExternalMcpServerStatus(
                            ExternalMcpServerState.ERROR,
                            detail,
                        ),
                    )
                    if (!currentCoroutineContext().isActive) throw cancelled
                    logFailure("external MCP server", name, "cancelled while listing tools", detail)
                } catch (failure: Exception) {
                    val detail = failureDetail("Tool discovery failed", failure, live.resolvedSecret)
                    setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
                    logFailure("external MCP server", name, "failed to list tools", detail)
                }
            }
        }
        mutableDescriptors.value = discovered
        initialized = mutableServerStatuses.value.values.none { status ->
            status.state == ExternalMcpServerState.ERROR || status.state == ExternalMcpServerState.CONNECTING
        }
        mutableChangeTick.value += 1
    }

    private suspend fun closeOne(name: String) {
        val live = open.remove(name) ?: return
        try {
            withTimeout(serverOperationTimeoutMs) { live.transport.close() }
        } catch (_: TimeoutCancellationException) {
            val detail = timeoutDetail("Close")
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            logFailure("external MCP server", name, "timed out while closing", detail)
        } catch (cancelled: CancellationException) {
            val detail = failureDetail("Close cancelled", cancelled, live.resolvedSecret)
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            logFailure("external MCP server", name, "failed to close", detail)
            if (!currentCoroutineContext().isActive) throw cancelled
        } catch (failure: Exception) {
            val detail = failureDetail("Close failed", failure, live.resolvedSecret)
            if (name in mutableServerStatuses.value) {
                setStatus(name, ExternalMcpServerStatus(ExternalMcpServerState.ERROR, detail))
            }
            logFailure("external MCP server", name, "failed to close", detail)
        }
    }

    // ---- cached ToolSource --------------------------------------------------

    /**
     * Return the settled cache cheaply. When startup/discovery has not settled cleanly,
     * submit the manager-owned idempotent retry so headless callers self-heal. Await it
     * only briefly: external MCP must never hold up unrelated Agent tool discovery.
     */
    override suspend fun list(): List<ToolDescriptor> {
        if (!initialized && implicitRetryDue()) {
            implicitRetryMutex.withLock {
                if (!initialized && implicitRetryDue()) {
                    val retry = implicitRetryInFlight
                        ?.takeUnless { it.isCompleted }
                        ?: requestStart().also { implicitRetryInFlight = it }
                    try {
                        val completed = withTimeoutOrNull(implicitRetryAwaitTimeoutMs) {
                            retry.await()
                            true
                        } ?: false
                        if (!completed) lastReconcileFinishedAtMs = nowMillis()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // External MCP is an optional tool lane. Preserve the last
                        // published cache rather than failing unrelated Agent tools.
                        lastReconcileFinishedAtMs = nowMillis()
                    } finally {
                        if (retry.isCompleted && implicitRetryInFlight === retry) {
                            implicitRetryInFlight = null
                        }
                    }
                }
            }
        }
        return descriptors.value
    }

    private fun implicitRetryDue(): Boolean {
        val last = lastReconcileFinishedAtMs
        return last == Long.MIN_VALUE || nowMillis() - last >= implicitRetryCooldownMs
    }

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
                if (terminalState == TerminalState.ACTIVE) terminalState = TerminalState.DISPOSED
                disposeResult = completion
                if (requests.trySend(ManagerRequest.Dispose(completion)).isFailure) {
                    completion.completeExceptionally(terminalFailureLocked())
                }
            }
        }
        result.await()
        worker.join()
    }

    private suspend fun disposeOwnedState() {
        val lives = detachOpenLives()
        closeDetachedLives(lives)
        mutableDescriptors.value = emptyList()
        replaceStatuses(
            mutableServerStatuses.value.mapValues { _ ->
                ExternalMcpServerStatus(ExternalMcpServerState.DISCONNECTED, "Disconnected")
            },
        )
        initialized = true
        mutableChangeTick.value += 1
    }

    private fun detachOpenLives(): List<Live> = open.entries.mapNotNull { (name, live) ->
        live.takeIf { open.remove(name, live) }
    }

    private suspend fun closeDetachedLives(lives: List<Live>) {
        if (lives.isEmpty()) return
        val fatalCloseFailures = withContext(NonCancellable) {
            lives.map { live ->
                async {
                    try {
                        closeFailedOpen(live.transport)
                        null
                    } catch (fatal: Error) {
                        fatal
                    }
                }
            }.awaitAll().filterNotNull()
        }
        fatalCloseFailures.firstOrNull()?.let { first ->
            fatalCloseFailures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun launchForcedCleanup(lives: List<Live>): Job {
        val cleanupJob = SupervisorJob()
        return CoroutineScope(cleanupJob + ioDispatcher).launch {
            try {
                closeDetachedLives(lives)
                // The actor finalizer reaps any transport published after the snapshot.
                worker.join()
            } catch (fatal: Error) {
                logActorFailure("forced cleanup failed", fatal)
            } finally {
                cleanupJob.cancel()
            }
        }
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

    private fun publicCancellation(): CancellationException = CancellationException(CANCELLED_MESSAGE)

    private fun publicFailure(message: String = OPERATION_FAILED_MESSAGE): IllegalStateException =
        IllegalStateException(message)

    private fun timeoutDetail(operation: String): String =
        "$operation timed out after $serverOperationTimeoutMs ms"

    private fun logActorFailure(action: String, failure: Throwable) {
        // Fatal/generic actor failures may arrive outside a server context, where no
        // resolved-secret redaction set exists. Log only the bounded failure type, never
        // the provider-controlled message or cause.
        val failureType = failure.javaClass.simpleName.ifBlank { "unknown failure" }
        log(boundedExternalMcpDiagnostic("external MCP manager $action ($failureType)"))
    }

    private suspend fun closeFailedOpen(transport: McpTransport) {
        try {
            withContext(NonCancellable) {
                withTimeout(minOf(serverOperationTimeoutMs, MAX_TRANSPORT_CLEANUP_TIMEOUT_MS)) {
                    transport.close()
                }
            }
        } catch (_: Exception) {
            // The primary connect failure is reported; cleanup diagnostics may contain
            // provider payloads, so they are intentionally not exposed or logged.
        }
    }

    private fun logFailure(subject: String, serverName: String, action: String, detail: String) {
        val safeName = boundedExternalMcpDiagnostic(serverName, fallback = "unnamed server")
        log(boundedExternalMcpDiagnostic("$subject '$safeName' $action: $detail"))
    }

    companion object {
        const val CONFIG_KEY = "mcpservers:config"
        internal const val DEFAULT_SERVER_OPERATION_TIMEOUT_MS = 15_000L
        internal const val DEFAULT_IMPLICIT_RETRY_COOLDOWN_MS = 30_000L
        internal const val DEFAULT_IMPLICIT_RETRY_AWAIT_TIMEOUT_MS = 1_000L
        internal const val MAX_STATUS_DETAIL_LENGTH = 240
        internal const val MAX_TRANSPORT_CLEANUP_TIMEOUT_MS = 2_000L
        internal const val FORCED_CLEANUP_JOIN_TIMEOUT_MS = 2_500L
        private const val DISPOSED_MESSAGE = "External MCP manager is disposed"
        private const val INVALID_SERVER_NAME_MESSAGE =
            "External MCP server name must not contain '/' or control characters"
        private const val CRASHED_MESSAGE = "External MCP manager crashed; reload the plugin to retry"
        private const val CANCELLED_MESSAGE = "External MCP operation cancelled"
        private const val OPERATION_FAILED_MESSAGE = "External MCP operation failed"
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
