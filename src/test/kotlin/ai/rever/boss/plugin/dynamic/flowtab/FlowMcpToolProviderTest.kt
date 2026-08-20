package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2: the fixed generic MCP tool set Flow exposes so an agent (Claude Code) can author
 * and run flows over the boss server. Each handler is exercised happy-path + error-path
 * against an in-memory store — no live boss server. Also pins F7: a *fixed* set, all
 * `flow_`/`prompt_`-prefixed, none colliding with the host's reserved tool names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowMcpToolProviderTest {

    private class FakeStorage : PluginStorageProvider {
        val map = ConcurrentHashMap<String, String>()
        override fun getPluginId() = "test"
        override suspend fun putJson(key: String, jsonValue: String) { map[key] = jsonValue }
        override suspend fun getJson(key: String): String? = map[key]
        override suspend fun contains(key: String): Boolean = map.containsKey(key)
        override suspend fun remove(key: String) { map.remove(key) }
        override suspend fun getAllKeys(): Set<String> = map.keys.toSet()
        override suspend fun clear() { map.clear() }
        override suspend fun putString(key: String, value: String) { map[key] = value }
        override suspend fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue
        override suspend fun putInt(key: String, value: Int) {}
        override suspend fun getInt(key: String, defaultValue: Int): Int = defaultValue
        override suspend fun putLong(key: String, value: Long) {}
        override suspend fun getLong(key: String, defaultValue: Long): Long = defaultValue
        override suspend fun putBoolean(key: String, value: Boolean) {}
        override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override suspend fun putFloat(key: String, value: Float) {}
        override suspend fun getFloat(key: String, defaultValue: Float): Float = defaultValue
        override fun observeString(key: String): Flow<String> = emptyFlow()
        override fun observeChanges(): Flow<String> = emptyFlow()
    }

    private class AvailableBrowserService : BrowserService {
        override fun isAvailable() = true
        override suspend fun createBrowser(config: BrowserConfig): BrowserHandle =
            error("visible-session test should not fall back to headless")
        override suspend fun disposeBrowser(handle: BrowserHandle) = Unit
        override fun getActiveBrowserCount() = 0
    }

    private class FakeVisibleTabs : ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(emptyList())
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        private val integration = object : BrowserIntegration {
            override suspend fun executeJavaScript(script: String): Any? = true
            override fun isBrowserAvailable() = true
            override suspend fun getCurrentUrl(): String = "about:blank"
        }

        override suspend fun refreshTabs() = Unit
        override fun selectTab(tabId: String, panelId: String) = Unit
        override fun getTabUrl(tabId: String): String? = "about:blank"
        override fun getFaviconCacheKey(tabId: String): String? = null
        @Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String): BrowserIntegration? =
            integration.takeIf { tabId in opened }
        override fun createBrowserTab(url: String, title: String): String? = null
        override fun createBrowserTabInRightSplit(url: String, title: String): String =
            "browser-${opened.size + 1}".also(opened::add)
        override fun closeTab(tabId: String): Boolean {
            closed += tabId
            return true
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun context(
        storage: PluginStorageProvider,
        browserService: BrowserService? = null,
        activeTabs: ActiveTabsProvider? = null,
    ): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = null
        override val browserService: BrowserService? = browserService
        override val activeTabsProvider: ActiveTabsProvider? = activeTabs
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    private fun provider(
        storage: PluginStorageProvider = FakeStorage(),
        registry: NodeRegistry = builtinNodeRegistry(),
        browserService: BrowserService? = null,
        activeTabs: ActiveTabsProvider? = null,
    ): FlowMcpToolProvider {
        val ctx = context(storage, browserService, activeTabs)
        return FlowMcpToolProvider(FlowController(ctx, { scope }, registry), PromptRegistry(storage))
    }

    private fun FlowMcpToolProvider.tool(name: String): McpToolDefinition =
        tools().single { it.name == name }

    private fun args(json: String) = McpToolArgs(emptyMap(), json)

    private suspend fun call(p: FlowMcpToolProvider, name: String, json: String = "{}"): McpToolResult =
        p.tool(name).handler.call(args(json))

    private fun obj(r: McpToolResult) = Json.parseToJsonElement(r.text).jsonObject

    // ---- tool set shape (F7) ------------------------------------------------

    @Test
    fun `exposes exactly the fixed generic tool set, all prefixed, none reserved`() {
        val names = provider().tools().map { it.name }
        assertEquals(
            setOf(
                "flow_create", "flow_rename", "flow_add_node", "flow_update_node",
                "flow_connect", "flow_delete_node", "flow_delete_edge", "flow_run", "flow_stop",
                "flow_status", "flow_result", "flow_runs", "flow_list", "flow_get", "flow_delete",
                "prompt_upsert", "prompt_get", "prompt_list",
            ),
            names.toSet(),
        )
        assertTrue(names.all { it.startsWith("flow_") || it.startsWith("prompt_") })
        val reserved = setOf(
            "list_tabs", "get_active_tab", "list_panes", "read_scrollback", "search_output",
            "get_last_command", "read_debug_console", "send_input", "send_signal", "run_in_panel",
            "run_command", "show_image", "run_in_sidebar", "cli", "manage_tools",
        )
        assertTrue(names.none { it in reserved })
    }

    // ---- authoring round-trip ----------------------------------------------

    @Test
    fun `create add connect get round-trips a flow`() = runBlocking {
        val storage = FakeStorage()
        val p = provider(storage)
        val tabId = obj(call(p, "flow_create", """{"name":"Router"}""")).getValue("tabId").jsonPrimitive.content
        val trig = obj(call(p, "flow_add_node", """{"tabId":"$tabId","kind":"TRIGGER"}""")).getValue("nodeId").jsonPrimitive.content
        val set = obj(call(p, "flow_add_node",
            """{"tabId":"$tabId","kind":"SET","config":{"assignments":"{\"g\":\"hi\"}"}}""")).getValue("nodeId").jsonPrimitive.content
        call(p, "flow_connect", """{"tabId":"$tabId","from":"$trig","to":"$set"}""").also { assertFalse(it.isError) }

        val snap = obj(call(p, "flow_get", """{"tabId":"$tabId"}"""))
        assertEquals(2, snap.getValue("nodes").jsonArray.size)
        assertEquals(1, snap.getValue("edges").jsonArray.size)

        val legacyListResult = obj(call(p, "flow_list"))
        val list = legacyListResult.getValue("flows").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tabId in list)
        assertFalse("flowDetails" in legacyListResult)

        val detailed = obj(call(p, "flow_list", """{"detail":true}"""))
        val detail = detailed.getValue("flowDetails").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tabId").jsonPrimitive.content == tabId }
        assertEquals("Router", detail.getValue("name").jsonPrimitive.content)
        assertEquals(2, detail.getValue("nodeCount").jsonPrimitive.content.toInt())
        assertEquals("true", detail.getValue("readable").jsonPrimitive.content)
    }

    @Test
    fun `rename update and delete tools repair an authored flow in place`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create", """{"name":"Draft"}"""))
            .getValue("tabId").jsonPrimitive.content
        val trigger = obj(call(p, "flow_add_node", """{"tabId":"$tabId","kind":"TRIGGER"}"""))
            .getValue("nodeId").jsonPrimitive.content
        val set = obj(call(p, "flow_add_node",
            """{"tabId":"$tabId","kind":"SET","config":{"assignments":"{}"}}"""))
            .getValue("nodeId").jsonPrimitive.content
        val edgeId = obj(call(p, "flow_connect",
            """{"tabId":"$tabId","from":"$trigger","to":"$set"}"""))
            .getValue("edgeId").jsonPrimitive.content

        assertFalse(call(p, "flow_rename", """{"tabId":"$tabId","name":"Production"}""").isError)
        assertFalse(call(p, "flow_update_node",
            """{"tabId":"$tabId","nodeId":"$set","title":"Prepare data","config":{"assignments":"{\"ready\":true}"}}"""
        ).isError)
        assertFalse(call(p, "flow_delete_edge", """{"tabId":"$tabId","edgeId":"$edgeId"}""").isError)

        val repaired = obj(call(p, "flow_get", """{"tabId":"$tabId"}"""))
        assertEquals("Production", repaired.getValue("metadata").jsonObject.getValue("name").jsonPrimitive.content)
        val updatedNode = repaired.getValue("nodes").jsonArray.map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == set }
        assertEquals("Prepare data", updatedNode.getValue("title").jsonPrimitive.content)
        assertEquals(
            "{\"ready\":true}",
            updatedNode.getValue("config").jsonObject.getValue("assignments").jsonPrimitive.content,
        )
        assertTrue(repaired.getValue("edges").jsonArray.isEmpty())

        call(p, "flow_connect", """{"tabId":"$tabId","from":"$trigger","to":"$set"}""")
        val deleted = obj(call(p, "flow_delete_node", """{"tabId":"$tabId","nodeId":"$set"}"""))
        assertEquals("1", deleted.getValue("deletedEdgeCount").jsonPrimitive.content)
        val afterDelete = obj(call(p, "flow_get", """{"tabId":"$tabId"}"""))
        assertEquals(1, afterDelete.getValue("nodes").jsonArray.size)
        assertTrue(afterDelete.getValue("edges").jsonArray.isEmpty())
    }

    @Test
    fun `mutation tools reject missing targets and empty updates`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content

        assertTrue(call(p, "flow_update_node", """{"tabId":"$tabId","nodeId":"missing"}""").isError)
        assertTrue(call(p, "flow_delete_node", """{"tabId":"$tabId","nodeId":"missing"}""").isError)
        assertTrue(call(p, "flow_delete_edge", """{"tabId":"$tabId","edgeId":"missing"}""").isError)
        assertTrue(call(p, "flow_rename", """{"tabId":"$tabId","name":"  "}""").isError)
    }

    @Test
    fun `detailed flow list exposes corrupt flows as unreadable`() = runBlocking {
        val storage = FakeStorage()
        val p = provider(storage)

        storage.putJson("${FlowController.GRAPH_PREFIX}flow-corrupt", "{not-json")
        val corrupt = obj(call(p, "flow_list", """{"detail":true}"""))
            .getValue("flowDetails").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tabId").jsonPrimitive.content == "flow-corrupt" }
        assertEquals("false", corrupt.getValue("readable").jsonPrimitive.content)
    }

    @Test
    fun `flow_delete permanently removes readable and corrupt flows`() = runBlocking {
        val storage = FakeStorage()
        val p = provider(storage)
        val tabId = obj(call(p, "flow_create", """{"name":"Disposable"}"""))
            .getValue("tabId").jsonPrimitive.content

        assertFalse(call(p, "flow_delete", """{"tabId":"$tabId"}""").isError)
        assertTrue(call(p, "flow_get", """{"tabId":"$tabId"}""").isError)

        storage.putJson("${FlowController.GRAPH_PREFIX}flow-corrupt", "{not-json")
        assertFalse(call(p, "flow_delete", """{"tabId":"flow-corrupt"}""").isError)
        assertTrue(call(p, "flow_delete", """{"tabId":"flow-missing"}""").isError)
    }

    @Test
    fun `flow_run then poll flow_status reaches success and flow_result has outputs`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
        val trig = obj(call(p, "flow_add_node", """{"tabId":"$tabId","kind":"TRIGGER"}""")).getValue("nodeId").jsonPrimitive.content
        val set = obj(call(p, "flow_add_node",
            """{"tabId":"$tabId","kind":"SET","config":{"assignments":"{\"g\":\"hi\"}"}}""")).getValue("nodeId").jsonPrimitive.content
        call(p, "flow_connect", """{"tabId":"$tabId","from":"$trig","to":"$set"}""")

        val runId = obj(call(p, "flow_run", """{"tabId":"$tabId"}""")).getValue("runId").jsonPrimitive.content
        val state = withTimeout(5_000) {
            var s: String
            while (true) {
                s = obj(call(p, "flow_status", """{"runId":"$runId"}""")).getValue("state").jsonPrimitive.content
                if (s != "RUNNING") break
                delay(15)
            }
            s
        }
        assertEquals("SUCCEEDED", state)
        val result = obj(call(p, "flow_result", """{"runId":"$runId"}"""))
        assertTrue(result.getValue("nodes").jsonObject.containsKey(set))
        assertEquals("false", result.getValue("outputIncluded").jsonPrimitive.content)
    }

    @Test
    fun `flow_runs lists only the requested flow newest first with bounded summaries`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create", """{"name":"History"}"""))
            .getValue("tabId").jsonPrimitive.content
        val otherTabId = obj(call(p, "flow_create", """{"name":"Other"}"""))
            .getValue("tabId").jsonPrimitive.content
        call(p, "flow_add_node", """{"tabId":"$tabId","kind":"TRIGGER"}""")

        suspend fun runAndWait(flowId: String): String {
            val runId = obj(call(p, "flow_run", """{"tabId":"$flowId"}"""))
                .getValue("runId").jsonPrimitive.content
            withTimeout(5_000) {
                while (obj(call(p, "flow_status", """{"runId":"$runId"}"""))
                        .getValue("state").jsonPrimitive.content == "RUNNING") {
                    delay(10)
                }
            }
            return runId
        }

        val first = runAndWait(tabId)
        delay(2)
        val second = runAndWait(tabId)
        runAndWait(otherTabId)

        val runs = obj(call(p, "flow_runs", """{"tabId":"$tabId","limit":1}"""))
            .getValue("runs").jsonArray
        assertEquals(1, runs.size)
        val latest = runs.single().jsonObject
        assertEquals(second, latest.getValue("runId").jsonPrimitive.content)
        assertEquals("SUCCEEDED", latest.getValue("state").jsonPrimitive.content)
        assertTrue(latest.getValue("startedAtMs").jsonPrimitive.content.toLong() > 0L)
        assertEquals("1", latest.getValue("nodeCount").jsonPrimitive.content)
        assertTrue(first != second)

        assertTrue(call(p, "flow_runs", """{"tabId":"$tabId","limit":0}""").isError)
        assertTrue(call(p, "flow_runs", """{"tabId":"missing"}""").isError)
    }

    @Test
    fun `MCP flow_run closes the visible browser tab after terminal cleanup`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val tabs = FakeVisibleTabs()
            val p = provider(
                browserService = AvailableBrowserService(),
                activeTabs = tabs,
            )
            val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
            call(p, "flow_add_node", """{"tabId":"$tabId","kind":"OPEN_BROWSER"}""")
            val runId = obj(call(p, "flow_run", """{"tabId":"$tabId"}"""))
                .getValue("runId").jsonPrimitive.content

            val state = withTimeout(5_000) {
                while (true) {
                    val current = obj(call(p, "flow_status", """{"runId":"$runId"}"""))
                        .getValue("state").jsonPrimitive.content
                    if (current != "RUNNING") return@withTimeout current
                    delay(10)
                }
                error("unreachable")
            }

            assertEquals("SUCCEEDED", state)
            assertEquals(listOf("browser-1"), tabs.opened)
            assertEquals(listOf("browser-1"), tabs.closed)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `interactive session ownership still leaves its visible tab open for inspection`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val tabs = FakeVisibleTabs()
            val registry = SessionRegistry(
                context(FakeStorage(), AvailableBrowserService(), tabs),
            )

            registry.open(headless = false)
            registry.closeAll()

            assertEquals(listOf("browser-1"), tabs.opened)
            assertTrue(tabs.closed.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `flow_stop cancels an active run and is idempotent once terminal`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "BLOCKING",
                    label = "Blocking",
                    inputs = 0,
                    outputs = 0,
                    accent = 0,
                    description = "test only",
                    executor = NodeExecutor { _, _, _, _ ->
                        started.complete(Unit)
                        awaitCancellation()
                    },
                )
            )
        }
        val p = provider(registry = registry)
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
        call(p, "flow_add_node", """{"tabId":"$tabId","kind":"BLOCKING"}""")
        val runId = obj(call(p, "flow_run", """{"tabId":"$tabId"}"""))
            .getValue("runId").jsonPrimitive.content
        withTimeout(5_000) { started.await() }

        val stopped = obj(call(p, "flow_stop", """{"runId":"$runId"}"""))
        assertEquals("true", stopped.getValue("stopped").jsonPrimitive.content)
        assertEquals("FAILED", stopped.getValue("state").jsonPrimitive.content)
        assertContains(stopped.getValue("error").jsonPrimitive.content, "stopped by caller")

        val repeated = obj(call(p, "flow_stop", """{"runId":"$runId"}"""))
        assertEquals("false", repeated.getValue("stopped").jsonPrimitive.content)
        assertEquals("FAILED", repeated.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `flow_result omits output by default and bounds explicit node output`() = runBlocking {
        val largeValue = "🙂".repeat(15_000)
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "LARGE_OUTPUT",
                    label = "Large Output",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    executor = NodeExecutor { _, _, _, log ->
                        repeat(25) { index -> log("request-$index:$largeValue") }
                        NodeOutput.single(
                            listOf(
                                Item(
                                    buildJsonObject {
                                        put("html", largeValue)
                                        put("nested", buildJsonObject { put("svg", largeValue) })
                                    }
                                )
                            )
                        )
                    },
                )
            )
        }
        val p = provider(registry = registry)
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
        val nodeId = obj(
            call(p, "flow_add_node", """{"tabId":"$tabId","kind":"LARGE_OUTPUT"}""")
        ).getValue("nodeId").jsonPrimitive.content
        val runId = obj(call(p, "flow_run", """{"tabId":"$tabId"}"""))
            .getValue("runId").jsonPrimitive.content

        withTimeout(5_000) {
            while (obj(call(p, "flow_status", """{"runId":"$runId"}"""))
                    .getValue("state").jsonPrimitive.content == "RUNNING") {
                delay(10)
            }
        }

        val summaryResult = call(p, "flow_result", """{"runId":"$runId"}""")
        val summary = obj(summaryResult)
        val summaryNode = summary.getValue("nodes").jsonObject.getValue(nodeId).jsonObject
        assertTrue(summaryResult.text.encodeToByteArray().size < 30_000)
        assertEquals("false", summary.getValue("outputIncluded").jsonPrimitive.content)
        assertEquals("true", summary.getValue("outputOmitted").jsonPrimitive.content)
        assertEquals(0, summaryNode.getValue("output").jsonArray.size)
        val summaryLogs = summaryNode.getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        assertContains(summaryLogs.first(), "request-0:")
        assertTrue(summaryLogs.any { "log lines omitted" in it })
        assertContains(summaryLogs.last(), "request-24:")

        assertTrue(call(p, "flow_result", """{"runId":"$runId","includeOutput":true}""").isError)
        val detailResult = call(
            p,
            "flow_result",
            """{"runId":"$runId","nodeId":"$nodeId","includeOutput":true}""",
        )
        val detail = obj(detailResult)
        val detailNode = detail.getValue("nodes").jsonObject.getValue(nodeId).jsonObject
        val output = detailNode.getValue("output").jsonArray.single().jsonObject
        assertTrue(detailResult.text.encodeToByteArray().size < 40_000)
        assertEquals("true", detail.getValue("outputIncluded").jsonPrimitive.content)
        assertEquals("true", detail.getValue("truncated").jsonPrimitive.content)
        assertContains(output.getValue("html").jsonPrimitive.content, "bytes omitted")
        assertContains(
            output.getValue("nested").jsonObject.getValue("svg").jsonPrimitive.content,
            "bytes omitted",
        )
    }

    @Test
    fun `null executor flow reaches failed and flow_result returns node state`() = runBlocking {
        val registry = builtinNodeRegistry().also {
            it.register(
                NodeSpec(
                    id = "NULL_EXECUTOR",
                    label = "Null Executor",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    executor = null,
                )
            )
        }
        val p = provider(registry = registry)
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
        val nullNode = obj(
            call(p, "flow_add_node", """{"tabId":"$tabId","kind":"NULL_EXECUTOR"}""")
        ).getValue("nodeId").jsonPrimitive.content
        val runId = obj(
            call(p, "flow_run", """{"tabId":"$tabId"}""")
        ).getValue("runId").jsonPrimitive.content

        val state = withTimeout(2_000) {
            var current: String
            while (true) {
                current = obj(
                    call(p, "flow_status", """{"runId":"$runId"}""")
                ).getValue("state").jsonPrimitive.content
                if (current != "RUNNING") break
                delay(10)
            }
            current
        }
        assertEquals("FAILED", state)

        val result = obj(call(p, "flow_result", """{"runId":"$runId"}"""))
        val node = result.getValue("nodes").jsonObject.getValue(nullNode).jsonObject
        assertEquals("ERROR", node.getValue("status").jsonPrimitive.content)
    }

    // ---- prompt tools -------------------------------------------------------

    @Test
    fun `prompt_upsert then get and list round-trips`() = runBlocking {
        val p = provider()
        call(p, "prompt_upsert",
            """{"id":"p1","name":"Router","base":"You route.","rules":["never invent"]}""").also { assertFalse(it.isError) }
        val got = obj(call(p, "prompt_get", """{"id":"p1"}"""))
        assertEquals("Router", got.getValue("name").jsonPrimitive.content)
        val ids = obj(call(p, "prompt_list")).getValue("prompts").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertTrue("p1" in ids)
    }

    // ---- error paths --------------------------------------------------------

    @Test
    fun `flow_add_node without tabId is an error result`() = runBlocking {
        assertTrue(call(provider(), "flow_add_node", """{"kind":"SET"}""").isError)
    }

    @Test
    fun `flow_add_node rejects an unregistered kind and names valid kinds`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content

        val result = call(
            p,
            "flow_add_node",
            """{"tabId":"$tabId","kind":"__invalid_probe_kind__"}""",
        )

        assertTrue(result.isError)
        assertContains(result.text, "__invalid_probe_kind__")
        assertContains(result.text, "Valid kinds:")
        assertTrue(obj(call(p, "flow_get", """{"tabId":"$tabId"}""")).getValue("nodes").jsonArray.isEmpty())
    }

    @Test
    fun `flow_get on an unknown flow is an error result`() = runBlocking {
        assertTrue(call(provider(), "flow_get", """{"tabId":"flow-nope"}""").isError)
    }

    @Test
    fun `flow_status on an unknown runId is an error result`() = runBlocking {
        assertTrue(call(provider(), "flow_status", """{"runId":"run-nope"}""").isError)
    }

    @Test
    fun `prompt_get on an unknown id is an error result`() = runBlocking {
        assertTrue(call(provider(), "prompt_get", """{"id":"ghost"}""").isError)
    }

    @Test
    fun `prompt_upsert with malformed body is an error result`() = runBlocking {
        assertTrue(call(provider(), "prompt_upsert", """{"name":"missing id"}""").isError)
    }

    @Test
    fun `flow_connect with an unknown node is an error result`() = runBlocking {
        val p = provider()
        val tabId = obj(call(p, "flow_create")).getValue("tabId").jsonPrimitive.content
        assertTrue(call(p, "flow_connect", """{"tabId":"$tabId","from":"nope","to":"nope2"}""").isError)
    }
}
