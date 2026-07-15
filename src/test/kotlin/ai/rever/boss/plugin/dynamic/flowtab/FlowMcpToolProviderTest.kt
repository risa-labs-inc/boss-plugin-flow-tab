package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2: the fixed generic MCP tool set Flow exposes so an agent (Claude Code) can author
 * and run flows over the boss server. Each handler is exercised happy-path + error-path
 * against an in-memory store — no live boss server. Also pins F7: a *fixed* set, all
 * `flow_`/`prompt_`-prefixed, none colliding with the host's reserved tool names.
 */
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun context(storage: PluginStorageProvider): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val mcpToolRegistry: McpToolRegistry? = null
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
    }

    private fun provider(storage: PluginStorageProvider = FakeStorage()): FlowMcpToolProvider {
        val ctx = context(storage)
        return FlowMcpToolProvider(FlowController(ctx, scope), PromptRegistry(storage))
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
                "flow_create", "flow_add_node", "flow_connect", "flow_run",
                "flow_status", "flow_result", "flow_list", "flow_get",
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
        val p = provider()
        val tabId = obj(call(p, "flow_create", """{"name":"Router"}""")).getValue("tabId").jsonPrimitive.content
        val trig = obj(call(p, "flow_add_node", """{"tabId":"$tabId","kind":"TRIGGER"}""")).getValue("nodeId").jsonPrimitive.content
        val set = obj(call(p, "flow_add_node",
            """{"tabId":"$tabId","kind":"SET","config":{"assignments":"{\"g\":\"hi\"}"}}""")).getValue("nodeId").jsonPrimitive.content
        call(p, "flow_connect", """{"tabId":"$tabId","from":"$trig","to":"$set"}""").also { assertFalse(it.isError) }

        val snap = obj(call(p, "flow_get", """{"tabId":"$tabId"}"""))
        assertEquals(2, snap.getValue("nodes").jsonArray.size)
        assertEquals(1, snap.getValue("edges").jsonArray.size)

        val list = obj(call(p, "flow_list")).getValue("flows").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(tabId in list)
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
