package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        val onCall: (String, String) -> RemoteToolResult = { n, _ -> RemoteToolResult("ok:$n", false) },
    ) : McpTransport {
        val connected = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun connect() {
            if (failConnect) throw ExecError("connect boom")
            connected.set(true)
        }
        override suspend fun listTools(): List<RemoteTool> = tools
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
        factory: McpTransportFactory,
    ) = ExternalMcpManager(
        storage = storage,
        secrets = secrets,
        settings = SettingsStore(storage),
        transportFactory = factory,
        ioDispatcher = ioDispatcher,
    )

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
    fun `cancelling connect closes the opened transport non-cancellably and preserves the cause`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val connectStarted = CompletableDeferred<Unit>()
        var closeCalled = false
        var cleanupWasActive = false
        val transport = object : McpTransport {
            override suspend fun connect() {
                connectStarted.complete(Unit)
                awaitCancellation()
            }
            override suspend fun listTools(): List<RemoteTool> = emptyList()
            override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
            override suspend fun close() {
                closeCalled = true
                cleanupWasActive = currentCoroutineContext().isActive
            }
        }
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        var completionCause: Throwable? = null
        val job = launch { m.refresh() }.also { launched ->
            launched.invokeOnCompletion { completionCause = it }
        }
        connectStarted.await()
        val cancellation = kotlinx.coroutines.CancellationException("stop connecting")

        job.cancel(cancellation)
        job.join()

        assertTrue(closeCalled)
        assertTrue(cleanupWasActive)
        assertEquals(cancellation, completionCause)
    }

    @Test
    fun `every tool synchronizer follows the manager change tick`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        val transport = FakeTransport(listOf(remote("first")))
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        val firstRegistry = NodeRegistry()
        val secondRegistry = NodeRegistry()
        syncExternalMcpTools(m, firstRegistry, backgroundScope)
        syncExternalMcpTools(m, secondRegistry, backgroundScope)
        runCurrent()

        assertNotNull(firstRegistry["tool:ext:s/first"])
        assertNotNull(secondRegistry["tool:ext:s/first"])

        transport.tools = listOf(remote("second"))
        m.refresh()
        runCurrent()

        assertNull(firstRegistry["tool:ext:s/first"])
        assertNull(secondRegistry["tool:ext:s/first"])
        assertNotNull(firstRegistry["tool:ext:s/second"])
        assertNotNull(secondRegistry["tool:ext:s/second"])
    }

    @Test
    fun `tool synchronization cancels stale discovery before applying the latest result`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val storage = TestStorage()
        var tools = listOf(remote("initial"))
        var blockNext: CompletableDeferred<Unit>? = null
        var blockedStarted = CompletableDeferred<Unit>()
        val transport = object : McpTransport {
            override suspend fun connect() = Unit
            override suspend fun listTools(): List<RemoteTool> {
                val snapshot = tools
                blockNext?.let { gate ->
                    blockNext = null
                    blockedStarted.complete(Unit)
                    gate.await()
                }
                return snapshot
            }
            override suspend fun callTool(name: String, argsJson: String) = RemoteToolResult("", false)
            override suspend fun close() = Unit
        }
        val m = manager(storage, ioDispatcher = dispatcher) { _, _ -> transport }
        SettingsStore(storage).setExternalMcpEnabled(true)
        m.upsertConfig(McpServerConfig("s", McpTransportKind.STDIO, command = "x", enabled = true))
        val registry = NodeRegistry()
        syncExternalMcpTools(m, registry, backgroundScope)
        runCurrent()
        assertNotNull(registry["tool:ext:s/initial"])

        tools = listOf(remote("stale"))
        blockNext = CompletableDeferred()
        blockedStarted = CompletableDeferred()
        m.refresh()
        runCurrent()
        blockedStarted.await()

        tools = listOf(remote("latest"))
        m.refresh()
        runCurrent()

        assertNull(registry["tool:ext:s/initial"])
        assertNull(registry["tool:ext:s/stale"])
        assertNotNull(registry["tool:ext:s/latest"])
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
    fun `login shell defaults to the SHELL env or a POSIX fallback`() {
        // Never throws and always yields a non-blank shell path.
        assertTrue(LoginShell.shell().isNotBlank())
    }
}
