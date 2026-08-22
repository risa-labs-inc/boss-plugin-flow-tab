package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7: external MCP client. All behavior is proven against an in-memory [FakeTransport]
 * (no real process/network — that's the noted untested boundary in
 * [McpClientTransports]). Pins: ext-namespacing, prefix-stripping invoke, node-spec
 * generation, feature-flag gating, per-server enable, cross-server routing, secret
 * resolution (never from config), config persistence, lifecycle reaping, and the
 * login-shell PATH command construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalMcpTest {

    // ---- test doubles -------------------------------------------------------

    /** In-memory transport: scripts tools/results, records connect/close + secret. */
    private class FakeTransport(
        var tools: List<RemoteTool>,
        val secret: String? = null,
        val failConnect: Boolean = false,
        var failList: Boolean = false,
        val onCall: (String, String) -> RemoteToolResult = { n, _ -> RemoteToolResult("ok:$n", false) },
    ) : McpTransport {
        val connected = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val listCalls = AtomicInteger(0)
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun connect() {
            if (failConnect) throw ExecError("connect boom")
            connected.set(true)
        }
        override suspend fun listTools(): List<RemoteTool> {
            listCalls.incrementAndGet()
            if (failList) error("list boom")
            return tools
        }
        override suspend fun callTool(name: String, argsJson: String): RemoteToolResult {
            calls += name to argsJson
            return onCall(name, argsJson)
        }
        override suspend fun close() { closed.set(true) }
    }

    private fun remote(name: String, schema: String = "{}") = RemoteTool(name, "desc $name", schema)

    private fun manager(
        storage: TestStorage = TestStorage(),
        secrets: SecretResolver = SecretResolver.constant(null),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        log: (String) -> Unit = {},
        serverOperationTimeoutMs: Long = ExternalMcpManager.DEFAULT_SERVER_OPERATION_TIMEOUT_MS,
        implicitRetryCooldownMs: Long = ExternalMcpManager.DEFAULT_IMPLICIT_RETRY_COOLDOWN_MS,
        implicitRetryAwaitTimeoutMs: Long = ExternalMcpManager.DEFAULT_IMPLICIT_RETRY_AWAIT_TIMEOUT_MS,
        nowMillis: () -> Long = System::currentTimeMillis,
        factory: McpTransportFactory,
    ) = ExternalMcpManager(
        storage = storage,
        secrets = secrets,
        settings = SettingsStore(storage),
        transportFactory = factory,
        log = log,
        ioDispatcher = ioDispatcher,
        serverOperationTimeoutMs = serverOperationTimeoutMs,
        implicitRetryCooldownMs = implicitRetryCooldownMs,
        implicitRetryAwaitTimeoutMs = implicitRetryAwaitTimeoutMs,
        nowMillis = nowMillis,
    )

    private suspend fun seedEnabledServer(storage: TestStorage, config: McpServerConfig) {
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)),
        )
        storage.putJson(SettingsStore.KEY, Json.encodeToString(FlowSettings.serializer(), FlowSettings(true)))
    }

    // ---- ExternalMcpToolSource ----------------------------------------------

    @Test
    fun `source namespaces remote tools as ext-scoped descriptors`() = runBlocking {
        val src = ExternalMcpToolSource("weather", FakeTransport(listOf(remote("forecast"))))
        val d = src.list().single()
        assertEquals(ToolScope.EXT, d.ref.scope)
        assertEquals("weather/forecast", d.ref.name)
        assertEquals("tool:ext:weather/forecast", d.ref.kindId)
    }

    @Test
    fun `source invoke strips the server prefix before proxying`() = runBlocking {
        val t = FakeTransport(listOf(remote("forecast")))
        val src = ExternalMcpToolSource("weather", t)
        val r = src.invoke("weather/forecast", """{"q":1}""")
        assertFalse(r.isError)
        assertEquals("forecast" to """{"q":1}""", t.calls.single())
        assertEquals("ok:forecast", r.text)
    }

    @Test
    fun `source maps a remote error result`() = runBlocking {
        val t = FakeTransport(listOf(remote("x"))) { _, _ -> RemoteToolResult("nope", true) }
        val r = ExternalMcpToolSource("srv", t).invoke("srv/x", "{}")
        assertTrue(r.isError)
        assertEquals("nope", r.text)
    }

    @Test
    fun `an ext descriptor becomes a tool node spec with ext kind-id`() = runBlocking {
        val src = ExternalMcpToolSource("srv", FakeTransport(listOf(remote("q", """{"type":"object","properties":{"a":{"type":"string"}}}"""))))
        val spec = toolNodeSpec(src.list().single(), src)
        assertEquals("tool:ext:srv/q", spec.id)
        assertEquals(listOf("a"), spec.configFields.map { it.key })
        assertNotNull(spec.executor)
    }

    // ---- feature-flag gating ------------------------------------------------

    @Test
    fun `manager exposes no tools when the feature flag is off`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { _, _ -> FakeTransport(listOf(remote("t"))) }
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        // flag defaults OFF
        m.refresh()
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `manager exposes enabled servers' tools when the flag is on`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { cfg, _ ->
            FakeTransport(if (cfg.name == "on") listOf(remote("t")) else listOf(remote("u")))
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("on", McpTransportKind.STDIO, command = "x", enabled = true))
        m.upsertConfig(McpServerConfig("off", McpTransportKind.STDIO, command = "x", enabled = false))
        m.refresh()
        val names = m.list().map { it.ref.name }
        assertEquals(listOf("on/t"), names)
    }

    // ---- routing / namespacing ----------------------------------------------

    @Test
    fun `manager keeps tool names unique across servers and routes invoke to the owner`() = runBlocking {
        val storage = TestStorage()
        val hits = mutableListOf<String>()
        val m = manager(storage) { cfg, _ ->
            FakeTransport(listOf(remote("run"))) { n, _ -> hits += "${cfg.name}:$n"; RemoteToolResult("r", false) }
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("a", McpTransportKind.STDIO, command = "x", enabled = true))
        m.upsertConfig(McpServerConfig("b", McpTransportKind.STDIO, command = "x", enabled = true))
        m.refresh()
        assertEquals(setOf("a/run", "b/run"), m.list().map { it.ref.name }.toSet())
        m.invoke("b/run", "{}")
        assertEquals(listOf("b:run"), hits)
    }

    @Test
    fun `manager invoke on an unknown server is an error not a crash`() = runBlocking {
        val m = manager { _, _ -> FakeTransport(emptyList()) }
        val r = m.invoke("ghost/x", "{}")
        assertTrue(r.isError)
    }

    // ---- secrets ------------------------------------------------------------

    @Test
    fun `manager resolves the secret from the store and never persists it in config`() = runBlocking {
        val storage = TestStorage()
        var passedSecret: String? = "unset"
        val secrets = SecretResolver { name -> if (name == "MY_TOKEN") "s3cr3t" else null }
        val m = manager(storage, secrets) { _, secret -> passedSecret = secret; FakeTransport(listOf(remote("t"))) }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("s", McpTransportKind.HTTP_SSE, url = "http://x", enabled = true, secretRef = "MY_TOKEN"))
        m.refresh()
        assertEquals("s3cr3t", passedSecret)
        // The persisted config carries the *reference*, never the resolved secret value.
        val raw = storage.map[ExternalMcpManager.CONFIG_KEY]!!
        assertTrue(raw.contains("MY_TOKEN"))
        assertFalse(raw.contains("s3cr3t"))
    }

    @Test
    fun `legacy stdio secret reference is normalized and never resolved`() = runBlocking {
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig(
                "legacy",
                McpTransportKind.STDIO,
                command = "x",
                enabled = true,
                secretRef = "ORPHANED_TOKEN",
            ),
        )
        var secretReads = 0
        val m = manager(
            storage = storage,
            secrets = SecretResolver { secretReads++; "unused-secret" },
        ) { _, secret ->
            assertNull(secret)
            FakeTransport(emptyList())
        }

        m.start()

        assertNull(m.listConfigs().single().secretRef)
        assertEquals(0, secretReads)
        m.upsertConfig(
            McpServerConfig(
                "legacy",
                McpTransportKind.STDIO,
                command = "updated",
                enabled = true,
                secretRef = "ORPHANED_TOKEN",
            ),
        )
        assertFalse("ORPHANED_TOKEN" in storage.map.getValue(ExternalMcpManager.CONFIG_KEY))
        m.disposeAll()
    }

    // ---- persistence --------------------------------------------------------

    @Test
    fun `configs persist and reload through a fresh manager`() = runBlocking {
        val storage = TestStorage()
        val f: McpTransportFactory = { _, _ -> FakeTransport(emptyList()) }
        manager(storage, factory = f).upsertConfig(
            McpServerConfig("srv", McpTransportKind.STDIO, command = "npx", args = listOf("-y", "pkg"), enabled = true)
        )
        val reloaded = manager(storage, factory = f).listConfigs().single()
        assertEquals("srv", reloaded.name)
        assertEquals(listOf("-y", "pkg"), reloaded.args)
        assertTrue(reloaded.enabled)
    }

    @Test
    fun `removeConfig drops a server`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { _, _ -> FakeTransport(emptyList()) }
        m.upsertConfig(McpServerConfig("a", McpTransportKind.STDIO, command = "x"))
        m.upsertConfig(McpServerConfig("b", McpTransportKind.STDIO, command = "x"))
        m.removeConfig("a")
        assertEquals(listOf("b"), m.listConfigs().map { it.name })
    }

    @Test
    fun `addConfig rejects a duplicate name without replacing the existing server`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { _, _ -> FakeTransport(emptyList()) }
        val original = McpServerConfig("same", McpTransportKind.STDIO, command = "original")
        val duplicate = McpServerConfig("same", McpTransportKind.HTTP_SSE, url = "https://replacement.test")

        assertTrue(m.addConfig(original))
        assertFalse(m.addConfig(duplicate))
        assertEquals(original, m.listConfigs().single())
    }

    @Test
    fun `manager rejects invalid requested names and ignores invalid stored names`() = runBlocking {
        val storage = TestStorage()
        var factoryCalls = 0
        val m = manager(storage) { _, _ ->
            factoryCalls++
            FakeTransport(emptyList())
        }

        val slashFailure = assertFailsWith<IllegalArgumentException> {
            m.addConfig(McpServerConfig("team/server", McpTransportKind.STDIO, command = "x"))
        }
        assertTrue("must not contain" in slashFailure.message.orEmpty())
        assertFailsWith<IllegalArgumentException> {
            m.upsertConfig(McpServerConfig("forged\nserver", McpTransportKind.STDIO, command = "x"))
        }

        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(
                ListSerializer(McpServerConfig.serializer()),
                listOf(McpServerConfig("bad/name", McpTransportKind.STDIO, command = "x", enabled = true)),
            ),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        assertTrue(m.listConfigs().isEmpty())
        m.start()
        assertEquals(0, factoryCalls)

        assertTrue(m.addConfig(McpServerConfig("  safe  ", McpTransportKind.STDIO, command = "x")))
        assertEquals("safe", m.listConfigs().single().name)
        m.disposeAll()
    }

    @Test
    fun `identical upsert preserves position and does not rediscover`() = runBlocking {
        val storage = TestStorage()
        val first = McpServerConfig("first", McpTransportKind.STDIO, command = "x", enabled = true)
        val second = McpServerConfig("second", McpTransportKind.STDIO, command = "y", enabled = true)
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(first, second)),
        )
        storage.putJson(SettingsStore.KEY, Json.encodeToString(FlowSettings.serializer(), FlowSettings(true)))
        val transports = mutableMapOf<String, FakeTransport>()
        val m = manager(storage) { cfg, _ ->
            FakeTransport(listOf(remote(cfg.name))).also { transports[cfg.name] = it }
        }
        m.start()
        val tick = m.changeTick.value

        m.upsertConfig(first)

        assertEquals(listOf(first, second), m.listConfigs())
        assertEquals(tick, m.changeTick.value)
        assertEquals(1, transports.getValue("first").listCalls.get())
        assertEquals(1, transports.getValue("second").listCalls.get())
        m.disposeAll()
    }

    @Test
    fun `changing a connected server config closes and reopens its transport`() = runBlocking {
        val storage = TestStorage()
        val opened = mutableListOf<Pair<McpServerConfig, FakeTransport>>()
        val m = manager(storage) { cfg, _ ->
            FakeTransport(listOf(remote(cfg.url))).also { opened += cfg to it }
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        val original = McpServerConfig(
            "remote",
            McpTransportKind.HTTP_SSE,
            url = "https://old.example/sse",
            enabled = true,
        )
        val changed = original.copy(url = "https://new.example/sse")

        m.upsertConfig(original)
        m.upsertConfig(changed)

        assertEquals(listOf(original, changed), opened.map { it.first })
        assertTrue(opened.first().second.closed.get())
        assertTrue(opened.last().second.connected.get())
        assertEquals(listOf("remote/https://new.example/sse"), m.descriptors.value.map { it.ref.name })
        m.disposeAll()
    }

    // ---- lifecycle / reaping ------------------------------------------------

    @Test
    fun `refresh closes servers that become disabled`() = runBlocking {
        val storage = TestStorage()
        val opened = mutableListOf<FakeTransport>()
        val m = manager(storage) { _, _ -> FakeTransport(listOf(remote("t"))).also { opened += it } }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        m.refresh()
        assertEquals(1, opened.size)
        assertTrue(opened[0].connected.get())
        // Disable the server and refresh: its transport is closed (reaped).
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = false))
        m.refresh()
        assertTrue(opened[0].closed.get())
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `disposeAll closes every open transport`() = runBlocking {
        val storage = TestStorage()
        val opened = mutableListOf<FakeTransport>()
        val m = manager(storage) { _, _ -> FakeTransport(listOf(remote("t"))).also { opened += it } }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("a", McpTransportKind.STDIO, command = "x", enabled = true))
        m.upsertConfig(McpServerConfig("b", McpTransportKind.STDIO, command = "x", enabled = true))
        m.refresh()
        m.disposeAll()
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.closed.get() })
        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `dispose invokes every close concurrently and bounds a hung server`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val configs = listOf("first", "second", "third").map { name ->
            McpServerConfig(name, McpTransportKind.STDIO, command = "x", enabled = true)
        }
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        val closeInvoked = configs.associate { it.name to AtomicBoolean(false) }
        val closeCompleted = configs.associate { it.name to AtomicBoolean(false) }
        val m = manager(storage, ioDispatcher = dispatcher) { cfg, _ ->
            object : McpTransport {
                override suspend fun connect() = Unit
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() {
                    closeInvoked.getValue(cfg.name).set(true)
                    if (cfg.name == "first") CompletableDeferred<Unit>().await()
                    closeCompleted.getValue(cfg.name).set(true)
                }
            }
        }
        m.start()

        val disposal = async { m.disposeAll() }
        runCurrent()

        assertTrue(closeInvoked.values.all(AtomicBoolean::get))
        assertTrue(closeCompleted.getValue("second").get())
        assertTrue(closeCompleted.getValue("third").get())
        assertFalse(disposal.isCompleted)

        advanceTimeBy(ExternalMcpManager.MAX_TRANSPORT_CLEANUP_TIMEOUT_MS)
        runCurrent()
        disposal.await()

        assertFalse(closeCompleted.getValue("first").get())
        assertTrue(m.descriptors.value.isEmpty())
    }

    @Test
    fun `one server failing to connect does not break the others`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { cfg, _ ->
            FakeTransport(listOf(remote("t")), failConnect = cfg.name == "bad")
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("bad", McpTransportKind.STDIO, command = "x", enabled = true))
        m.upsertConfig(McpServerConfig("good", McpTransportKind.STDIO, command = "x", enabled = true))
        m.refresh()
        assertEquals(listOf("good/t"), m.list().map { it.ref.name })
    }

    @Test
    fun `connection failure publishes a bounded per-server error and a change tick`() = runBlocking {
        val storage = TestStorage()
        val longFailure = "connect boom " + "x".repeat(ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH * 2)
        val m = manager(storage) { _, _ ->
            object : McpTransport {
                override suspend fun connect() = error(longFailure)
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", true)
                override suspend fun close() = Unit
            }
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("bad", McpTransportKind.STDIO, command = "x", enabled = true))
        val before = m.changeTick.value

        m.refresh()

        val status = m.serverStatuses.value.getValue("bad")
        assertEquals(ExternalMcpServerState.ERROR, status.state)
        assertTrue(status.detail!!.startsWith("Connection failed: connect boom"))
        assertTrue(status.detail.length <= ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH)
        assertEquals(before + 1, m.changeTick.value)
    }

    @Test
    fun `refresh runs connect and close on its IO dispatcher`() {
        val io = Executors.newSingleThreadExecutor { task -> Thread(task, "external-mcp-test-io") }
            .asCoroutineDispatcher()
        try {
            runBlocking {
                val storage = TestStorage()
                var connectThread = ""
                var closeThread = ""
                val m = manager(storage, ioDispatcher = io) { _, _ ->
                    object : McpTransport {
                        override suspend fun connect() { connectThread = Thread.currentThread().name }
                        override suspend fun listTools(): List<RemoteTool> = emptyList()
                        override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                        override suspend fun close() { closeThread = Thread.currentThread().name }
                    }
                }
                SettingsStore(storage).setExternalMcpEnabled(true)
                m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
                m.refresh()
                m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = false))
                m.refresh()

                assertTrue(connectThread.startsWith("external-mcp-test-io"))
                assertTrue(closeThread.startsWith("external-mcp-test-io"))
            }
        } finally {
            io.close()
        }
    }

    @Test
    fun `hung connect times out cleans transport and lets queued remove settle`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val logged = mutableListOf<String>()
        seedEnabledServer(
            storage,
            McpServerConfig("hung", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val connectStarted = CompletableDeferred<Unit>()
        var closed = false
        val m = manager(
            storage = storage,
            ioDispatcher = dispatcher,
            log = logged::add,
            serverOperationTimeoutMs = 1_000,
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    connectStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() { closed = true }
            }
        }

        val startup = m.requestStart()
        connectStarted.await()
        val queuedRemove = m.requestRemoveConfig("hung")
        assertFalse(queuedRemove.isCompleted)

        advanceTimeBy(1_000)
        runCurrent()

        startup.await()
        assertTrue(queuedRemove.await())
        assertTrue(closed)
        assertTrue(m.listConfigs().isEmpty())
        assertTrue(logged.any { "timed out while connecting" in it })
        m.disposeAll()
    }

    @Test
    fun `hung discovery times out cleans live transport and lets queued remove settle`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val logged = mutableListOf<String>()
        seedEnabledServer(
            storage,
            McpServerConfig("hung", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val discoveryStarted = CompletableDeferred<Unit>()
        var closed = false
        val m = manager(
            storage = storage,
            ioDispatcher = dispatcher,
            log = logged::add,
            serverOperationTimeoutMs = 1_000,
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() = Unit
                override suspend fun listTools(): List<RemoteTool> {
                    discoveryStarted.complete(Unit)
                    return CompletableDeferred<List<RemoteTool>>().await()
                }
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() { closed = true }
            }
        }

        val startup = m.requestStart()
        discoveryStarted.await()
        val queuedRemove = m.requestRemoveConfig("hung")
        assertFalse(queuedRemove.isCompleted)

        advanceTimeBy(1_000)
        runCurrent()

        startup.await()
        assertTrue(queuedRemove.await())
        assertTrue(closed)
        assertTrue(m.descriptors.value.isEmpty())
        assertTrue(logged.any { "timed out while listing tools" in it })
        m.disposeAll()
    }

    @Test
    fun `caller cancellation does not cancel an accepted manager mutation`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val connectStarted = CompletableDeferred<Unit>()
        val allowConnect = CompletableDeferred<Unit>()
        val transport = object : McpTransport {
            override suspend fun connect() {
                connectStarted.complete(Unit)
                allowConnect.await()
            }
            override suspend fun listTools() = listOf(remote("survived"))
            override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
            override suspend fun close() = Unit
        }
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        val before = m.changeTick.value
        val acceptedRequest = m.requestSetSettingsEnabled(true)
        val uiAwaiter = launch { acceptedRequest.await() }
        connectStarted.await()

        uiAwaiter.cancel()
        uiAwaiter.join()
        allowConnect.complete(Unit)
        advanceUntilIdle()

        assertTrue(SettingsStore(storage).isExternalMcpEnabled())
        assertEquals(before + 1, m.changeTick.value)
        assertEquals(listOf("s/survived"), m.descriptors.value.map { it.ref.name })
    }

    @Test
    fun `multiple tab collectors use one manager discovery per settled change`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val transport = FakeTransport(listOf(remote("first")))
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        val firstRegistry = NodeRegistry()
        val secondRegistry = NodeRegistry()
        syncExternalMcpTools(m, firstRegistry, backgroundScope)
        syncExternalMcpTools(m, secondRegistry, backgroundScope)
        runCurrent()

        assertEquals(1, transport.listCalls.get())
        assertNotNull(firstRegistry["tool:ext:s/first"])
        assertNotNull(secondRegistry["tool:ext:s/first"])

        transport.tools = listOf(remote("second"))
        m.refresh()
        runCurrent()

        assertEquals(2, transport.listCalls.get())
        assertNull(firstRegistry["tool:ext:s/first"])
        assertNull(secondRegistry["tool:ext:s/first"])
        assertNotNull(firstRegistry["tool:ext:s/second"])
        assertNotNull(secondRegistry["tool:ext:s/second"])
    }

    @Test
    fun `stale tab toggle cannot resurrect a server removed by another tab`() = runBlocking {
        val storage = TestStorage()
        val m = manager(storage) { _, _ -> FakeTransport(emptyList()) }
        m.addConfig(McpServerConfig("shared", McpTransportKind.STDIO, command = "original"))
        val staleTabSnapshot = m.listConfigs().single()

        assertTrue(m.removeConfig("shared"))
        assertFalse(m.setConfigEnabled(staleTabSnapshot.name, true))

        assertTrue(m.listConfigs().isEmpty())
    }

    @Test
    fun `headless list retries transient startup discovery failure and clears error`() = runBlocking {
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val transport = FakeTransport(listOf(remote("recovered")), failList = true)
        var now = 1_000L
        val m = manager(storage, nowMillis = { now }) { _, _ -> transport }

        m.start()
        assertEquals(ExternalMcpServerState.ERROR, m.serverStatuses.value.getValue("s").state)
        assertTrue(m.descriptors.value.isEmpty())

        transport.failList = false
        now += ExternalMcpManager.DEFAULT_IMPLICIT_RETRY_COOLDOWN_MS
        val recovered = m.list()

        assertEquals(ExternalMcpServerState.CONNECTED, m.serverStatuses.value.getValue("s").state)
        assertEquals(listOf("s/recovered"), recovered.map { it.ref.name })
        assertEquals(2, transport.listCalls.get())
    }

    @Test
    fun `headless list coalesces retries and serves cache during cooldown`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("broken", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        var now = 1_000L
        val connectCalls = AtomicInteger(0)
        val m = manager(
            storage = storage,
            ioDispatcher = dispatcher,
            nowMillis = { now },
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    connectCalls.incrementAndGet()
                    error("permanently unavailable")
                }
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() = Unit
            }
        }

        val first = async { m.list() }
        val second = async { m.list() }
        first.await()
        second.await()
        assertEquals(1, connectCalls.get())

        m.list()
        assertEquals(1, connectCalls.get())

        now += ExternalMcpManager.DEFAULT_IMPLICIT_RETRY_COOLDOWN_MS
        m.list()
        assertEquals(2, connectCalls.get())
        m.disposeAll()
    }

    @Test
    fun `headless list bounds retry latency while manager-owned discovery continues`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("slow", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val connectStarted = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()
        val m = manager(
            storage = storage,
            ioDispatcher = dispatcher,
            implicitRetryAwaitTimeoutMs = 100,
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    connectStarted.complete(Unit)
                    releaseConnect.await()
                }
                override suspend fun listTools() = listOf(remote("eventual"))
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() = Unit
            }
        }

        val listing = async { m.list() }
        connectStarted.await()
        advanceTimeBy(100)
        runCurrent()

        assertTrue(listing.await().isEmpty())
        releaseConnect.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("slow/eventual"), m.descriptors.value.map { it.ref.name })
        m.disposeAll()
    }

    @Test
    fun `headless list returns its cache after terminal manager failure`() = runBlocking {
        val m = manager { _, _ -> FakeTransport(emptyList()) }
        m.cancelNow()

        assertTrue(m.list().isEmpty())
    }

    @Test
    fun `connection diagnostics redact resolved secrets before UI and logging`() = runBlocking {
        val storage = TestStorage()
        val logged = mutableListOf<String>()
        val secret = "resolved-super-secret"
        val serverName = "safe-server"
        val m = manager(
            storage = storage,
            secrets = SecretResolver.constant(secret),
            log = logged::add,
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() = error("request failed with $secret\nsecond line")
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() = Unit
            }
        }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.addConfig(
            McpServerConfig(
                serverName,
                McpTransportKind.HTTP_SSE,
                url = "https://example.test/sse",
                enabled = true,
                secretRef = "TOKEN",
            ),
        )

        val detail = m.serverStatuses.value.getValue(serverName).detail.orEmpty()
        assertFalse(secret in detail)
        assertFalse(secret in logged.joinToString())
        assertTrue(logged.all { '\n' !in it })
        assertTrue("***" in detail)
        assertFalse('\n' in detail)
        assertTrue(detail.length <= ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH)
    }

    @Test
    fun `close diagnostics redact resolved secrets before status and logging`() = runBlocking {
        val storage = TestStorage()
        val logged = mutableListOf<String>()
        val secret = "close-super-secret"
        seedEnabledServer(
            storage,
            McpServerConfig(
                "s",
                McpTransportKind.HTTP_SSE,
                url = "https://example.test/sse",
                enabled = true,
                secretRef = "TOKEN",
            ),
        )
        val m = manager(
            storage = storage,
            secrets = SecretResolver.constant(secret),
            log = logged::add,
        ) { _, _ ->
            object : McpTransport {
                override suspend fun connect() = Unit
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() = error("close leaked $secret\nprovider payload")
            }
        }
        m.start()

        assertTrue(m.setConfigEnabled("s", false))

        val detail = m.serverStatuses.value.getValue("s").detail.orEmpty()
        assertEquals(ExternalMcpServerState.ERROR, m.serverStatuses.value.getValue("s").state)
        assertFalse(secret in detail)
        assertTrue("***" in detail)
        assertFalse('\n' in detail)
        assertFalse(secret in logged.joinToString())
        assertTrue(logged.all { '\n' !in it })
    }

    @Test
    fun `fatal connect error terminates actor and later requests fail fast`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val rawPayload = "fatal-provider-secret\nforged line"
        val logged = mutableListOf<String>()
        var closed = false
        val m = manager(storage, ioDispatcher = dispatcher, log = logged::add) { _, _ ->
            object : McpTransport {
                override suspend fun connect(): Unit = throw NoClassDefFoundError(rawPayload)
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() { closed = true }
            }
        }

        val failedStart = m.requestStart()
        runCurrent()
        val failure = assertFailsWith<IllegalStateException> { failedStart.await() }
        assertEquals("External MCP manager crashed; reload the plugin to retry", failure.message)
        assertFalse(rawPayload in failure.message.orEmpty())
        assertTrue(closed)
        assertTrue(logged.any { "crashed; reload the plugin" in it && "NoClassDefFoundError" in it })
        assertTrue(logged.none { "fatal-provider-secret" in it || "forged line" in it || '\n' in it })
        assertTrue(logged.all { it.length <= ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH })

        val next = m.requestAddConfig(McpServerConfig("later", McpTransportKind.STDIO, command = "y"))
        assertTrue(next.isCompleted)
        val rejection = assertFailsWith<IllegalStateException> { next.await() }
        assertEquals("External MCP manager crashed; reload the plugin to retry", rejection.message)
        val rejectionChain = generateSequence<Throwable>(rejection) { it.cause }.toList()
        assertTrue(rejectionChain.none { "fatal-provider-secret" in it.message.orEmpty() || '\n' in it.message.orEmpty() })
    }

    @Test
    fun `generic actor failure logs bounded type but exposes no provider payload`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val backing = TestStorage()
        val rawPayload = "storage-provider-secret\nforged line"
        val storage = object : PluginStorageProvider by backing {
            override suspend fun putJson(key: String, jsonValue: String) {
                throw IllegalStateException(rawPayload)
            }
        }
        val logged = mutableListOf<String>()
        val m = ExternalMcpManager(
            storage = storage,
            secrets = SecretResolver.constant(null),
            settings = SettingsStore(storage),
            transportFactory = { _, _ -> FakeTransport(emptyList()) },
            log = logged::add,
            ioDispatcher = dispatcher,
        )

        val failure = assertFailsWith<IllegalStateException> {
            m.addConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x"))
        }

        assertEquals("External MCP operation failed", failure.message)
        val failureChain = generateSequence<Throwable>(failure) { it.cause }.toList()
        assertTrue(failureChain.none { "storage-provider-secret" in it.message.orEmpty() || '\n' in it.message.orEmpty() })
        assertTrue(logged.any { "operation failed" in it && "IllegalStateException" in it })
        assertTrue(logged.none { "storage-provider-secret" in it || "forged line" in it || '\n' in it })
        assertTrue(logged.all { it.length <= ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH })
        m.cancelNow()
    }

    @Test
    fun `active transport cancellation marks error cleans up and leaves actor usable`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        SettingsStore(storage).setExternalMcpEnabled(true)
        val secret = "cancel-secret"
        var firstClosed = false
        var cleanupWasActive = false
        var attempts = 0
        val m = manager(
            storage = storage,
            secrets = SecretResolver.constant(secret),
            ioDispatcher = dispatcher,
        ) { _, _ ->
            attempts++
            if (attempts == 1) {
                object : McpTransport {
                    override suspend fun connect() {
                        throw CancellationException("transport cancelled $secret\nhandshake")
                    }
                    override suspend fun listTools(): List<RemoteTool> = emptyList()
                    override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                    override suspend fun close() {
                        firstClosed = true
                        cleanupWasActive = currentCoroutineContext().isActive
                    }
                }
            } else {
                FakeTransport(listOf(remote("later")))
            }
        }

        assertTrue(
            m.addConfig(
                McpServerConfig(
                    "s",
                    McpTransportKind.HTTP_SSE,
                    url = "https://example.test/sse",
                    enabled = true,
                    secretRef = "TOKEN",
                ),
            ),
        )
        assertTrue(firstClosed)
        assertTrue(cleanupWasActive)
        val status = m.serverStatuses.value.getValue("s")
        assertEquals(ExternalMcpServerState.ERROR, status.state)
        assertFalse(secret in status.detail.orEmpty())
        assertFalse('\n' in status.detail.orEmpty())

        m.refresh()

        assertEquals(2, attempts)
        assertEquals(listOf("s/later"), m.descriptors.value.map { it.ref.name })
    }

    @Test
    fun `active connect cancellation does not prevent later servers`() = runBlocking {
        val storage = TestStorage()
        val cancelled = McpServerConfig("cancelled", McpTransportKind.STDIO, command = "x", enabled = true)
        val good = McpServerConfig("good", McpTransportKind.STDIO, command = "y", enabled = true)
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(cancelled, good)),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        var cancelledClosed = false
        val m = manager(storage) { cfg, _ ->
            if (cfg.name == "cancelled") {
                object : McpTransport {
                    override suspend fun connect(): Unit = throw CancellationException("provider cancelled")
                    override suspend fun listTools(): List<RemoteTool> = emptyList()
                    override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                    override suspend fun close() { cancelledClosed = true }
                }
            } else {
                FakeTransport(listOf(remote("tool")))
            }
        }

        m.start()

        assertTrue(cancelledClosed)
        assertEquals(ExternalMcpServerState.ERROR, m.serverStatuses.value.getValue("cancelled").state)
        assertEquals(listOf("good/tool"), m.descriptors.value.map { it.ref.name })
        m.disposeAll()
    }

    @Test
    fun `active discovery cancellation does not prevent later servers`() = runBlocking {
        val storage = TestStorage()
        val cancelled = McpServerConfig("cancelled", McpTransportKind.STDIO, command = "x", enabled = true)
        val good = McpServerConfig("good", McpTransportKind.STDIO, command = "y", enabled = true)
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(cancelled, good)),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        var cancelledClosed = false
        val m = manager(storage) { cfg, _ ->
            if (cfg.name == "cancelled") {
                object : McpTransport {
                    override suspend fun connect() = Unit
                    override suspend fun listTools(): List<RemoteTool> =
                        throw CancellationException("provider cancelled")
                    override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                    override suspend fun close() { cancelledClosed = true }
                }
            } else {
                FakeTransport(listOf(remote("tool")))
            }
        }

        m.start()

        assertTrue(cancelledClosed)
        assertEquals(ExternalMcpServerState.ERROR, m.serverStatuses.value.getValue("cancelled").state)
        assertEquals(listOf("good/tool"), m.descriptors.value.map { it.ref.name })
        m.disposeAll()
    }

    @Test
    fun `dispose drains accepted requests before reaping and rejects later requests`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("first", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val connectStarted = CompletableDeferred<Unit>()
        val allowConnect = CompletableDeferred<Unit>()
        val transport = object : McpTransport {
            var closed = false
            override suspend fun connect() {
                connectStarted.complete(Unit)
                allowConnect.await()
            }
            override suspend fun listTools() = listOf(remote("tool"))
            override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
            override suspend fun close() { closed = true }
        }
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        val startup = async { m.start() }
        connectStarted.await()
        val accepted = async {
            m.addConfig(McpServerConfig("queued", McpTransportKind.STDIO, command = "q"))
        }
        runCurrent()
        val disposal = async { m.disposeAll() }
        runCurrent()

        assertFailsWith<IllegalStateException> {
            m.addConfig(McpServerConfig("late", McpTransportKind.STDIO, command = "late"))
        }
        allowConnect.complete(Unit)
        startup.await()
        assertTrue(accepted.await())
        disposal.await()

        assertTrue(transport.closed)
        assertEquals(setOf("first", "queued"), m.listConfigs().map { it.name }.toSet())
    }

    @Test
    fun `dispose publishes terminal descriptors statuses and tick`() = runBlocking {
        val storage = TestStorage()
        val transport = FakeTransport(listOf(remote("tool")))
        SettingsStore(storage).setExternalMcpEnabled(true)
        val m = manager(storage) { _, _ -> transport }
        m.addConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        val before = m.changeTick.value

        m.disposeAll()

        assertTrue(transport.closed.get())
        assertTrue(m.descriptors.value.isEmpty())
        assertEquals(ExternalMcpServerState.DISCONNECTED, m.serverStatuses.value.getValue("s").state)
        assertEquals(before + 1, m.changeTick.value)
    }

    @Test
    fun `dispose stops an active reconcile before connecting later servers`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val configs = listOf("first", "second").map { name ->
            McpServerConfig(name, McpTransportKind.STDIO, command = "x", enabled = true)
        }
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        val firstConnectStarted = CompletableDeferred<Unit>()
        val allowFirstConnect = CompletableDeferred<Unit>()
        val firstClosed = AtomicBoolean(false)
        val secondConnects = AtomicInteger(0)
        val m = manager(storage, ioDispatcher = dispatcher) { cfg, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    if (cfg.name == "first") {
                        firstConnectStarted.complete(Unit)
                        allowFirstConnect.await()
                    } else {
                        secondConnects.incrementAndGet()
                    }
                }
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() {
                    if (cfg.name == "first") firstClosed.set(true)
                }
            }
        }

        val startup = async { m.start() }
        firstConnectStarted.await()
        val disposal = async { m.disposeAll() }
        runCurrent()
        allowFirstConnect.complete(Unit)
        startup.await()
        disposal.await()

        assertEquals(0, secondConnects.get())
        assertTrue(firstClosed.get())
    }

    @Test
    fun `cancelNow reaps open transports while a later connect is stuck`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val configs = listOf("first", "second").map { name ->
            McpServerConfig(name, McpTransportKind.STDIO, command = "x", enabled = true)
        }
        storage.putJson(
            ExternalMcpManager.CONFIG_KEY,
            Json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs),
        )
        SettingsStore(storage).setExternalMcpEnabled(true)
        val firstClosed = AtomicBoolean(false)
        val secondConnectStarted = CompletableDeferred<Unit>()
        val secondClosed = AtomicBoolean(false)
        val m = manager(storage, ioDispatcher = dispatcher) { cfg, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    if (cfg.name == "second") {
                        secondConnectStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() {
                    if (cfg.name == "first") firstClosed.set(true) else secondClosed.set(true)
                }
            }
        }

        val startup = m.requestStart()
        secondConnectStarted.await()
        val forcedCleanup = m.cancelNow()
        forcedCleanup.join()

        assertFailsWith<CancellationException> { startup.await() }
        assertTrue(firstClosed.get())
        assertTrue(secondClosed.get())
    }

    @Test
    fun `cancelNow fails queued work rejects terminal work and is idempotent`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        seedEnabledServer(
            storage,
            McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true),
        )
        val connectStarted = CompletableDeferred<Unit>()
        var closed = false
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ ->
            object : McpTransport {
                override suspend fun connect() {
                    connectStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                override suspend fun listTools(): List<RemoteTool> = emptyList()
                override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
                override suspend fun close() { closed = true }
            }
        }
        val active = m.requestStart()
        connectStarted.await()
        val queued = m.requestAddConfig(McpServerConfig("queued", McpTransportKind.STDIO, command = "q"))

        val forcedCleanup = m.cancelNow()
        m.cancelNow()
        forcedCleanup.join()

        val activeFailure = assertFailsWith<CancellationException> { active.await() }
        assertEquals("External MCP operation cancelled", activeFailure.message)
        assertTrue(closed)
        assertTrue(queued.isCompleted)
        val queuedFailure = assertFailsWith<IllegalStateException> { queued.await() }
        assertEquals("External MCP manager is disposed", queuedFailure.message)
        val late = m.requestRefresh()
        assertTrue(late.isCompleted)
        val lateFailure = assertFailsWith<IllegalStateException> { late.await() }
        assertEquals("External MCP manager is disposed", lateFailure.message)
        val disposalFailure = assertFailsWith<IllegalStateException> { m.disposeAll() }
        assertEquals("External MCP manager is disposed", disposalFailure.message)
    }

    // ---- settings store -----------------------------------------------------

    @Test
    fun `settings default to external MCP disabled and round-trip`() = runBlocking {
        val storage = TestStorage()
        val s = SettingsStore(storage)
        assertFalse(s.isExternalMcpEnabled())
        s.setExternalMcpEnabled(true)
        assertTrue(SettingsStore(storage).isExternalMcpEnabled())
    }

    // ---- login-shell PATH construction (F9) ---------------------------------

    @Test
    fun `login shell resolve command uses an interactive login shell`() {
        val cmd = LoginShell.resolveCommand("npx", shell = "/bin/zsh")
        assertEquals(listOf("/bin/zsh", "-lic", "command -v 'npx'"), cmd)
    }

    @Test
    fun `login shell launch command quotes the binary and args`() {
        val cmd = LoginShell.launchCommand("uvx", listOf("mcp-server", "--flag"), shell = "/bin/zsh")
        assertEquals(listOf("/bin/zsh", "-lic", "'uvx' 'mcp-server' '--flag'"), cmd)
    }

    @Test
    fun `stdio process uses configured nonblank working directory`() {
        val config = McpServerConfig(
            name = "local",
            kind = McpTransportKind.STDIO,
            command = "python3",
            workingDirectory = "  /tmp/mcp-project  ",
        )

        assertEquals(java.io.File("/tmp/mcp-project"), stdioProcessBuilder(config).directory())
    }

    @Test
    fun `stdio process preserves inherited directory when working directory is blank`() {
        val config = McpServerConfig("local", McpTransportKind.STDIO, command = "python3")

        assertEquals(null, stdioProcessBuilder(config).directory())
    }

    @Test
    fun `login shell defaults to the SHELL env or a POSIX fallback`() {
        // Never throws and always yields a non-blank shell path.
        assertTrue(LoginShell.shell().isNotBlank())
    }
}
