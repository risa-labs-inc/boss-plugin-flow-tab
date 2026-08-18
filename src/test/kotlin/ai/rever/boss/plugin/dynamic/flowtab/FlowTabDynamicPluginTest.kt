package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P2: the plugin lifecycle must wire Flow's MCP tools into the host `boss` server —
 * [FlowTabDynamicPlugin.register] registers a [FlowMcpToolProvider] and [dispose]
 * unregisters it by providerId. A host without an MCP registry / server controller
 * must degrade (getPluginAPI returns null) rather than crash.
 */
class FlowTabDynamicPluginTest {

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

    /** Captures MCP provider register/unregister so we can assert the wiring. */
    private class CapturingContext(
        scope: CoroutineScope,
        storage: PluginStorageProvider,
        override val mcpToolRegistry: McpToolRegistry? = null,
    ) : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = scope
        override val pluginStorageFactory = object : PluginStorageFactory {
            override fun createStorage(pluginId: String): PluginStorageProvider = storage
        }
        val registered = mutableListOf<McpToolProvider>()
        val unregistered = mutableListOf<String>()
        override fun registerMcpToolProvider(provider: McpToolProvider) { registered += provider }
        override fun unregisterMcpToolProvider(providerId: String) { unregistered += providerId }
    }

    private class TrackingRegistry : McpToolRegistry {
        private val delegate = MutableStateFlow(emptyList<RegisteredMcpTool>())
        val collectorStarts = AtomicInteger()
        val activeCollectors: Int get() = delegate.subscriptionCount.value
        override val tools: StateFlow<List<RegisteredMcpTool>> get() {
            collectorStarts.incrementAndGet()
            return delegate
        }
        override val allTools: StateFlow<List<RegisteredMcpTool>> get() = delegate
        override val disabledToolNames: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override fun setToolEnabled(toolName: String, enabled: Boolean) {}
        override suspend fun invoke(toolName: String, arguments: String): McpToolResult = McpToolResult("ok", false)
    }

    @Test
    fun `register wires a flow MCP tool provider then dispose unregisters it`() {
        val ctx = CapturingContext(scope, FakeStorage())
        val plugin = FlowTabDynamicPlugin()

        plugin.register(ctx)
        val provider = ctx.registered.singleOrNull { it.providerId == FlowMcpToolProvider.PROVIDER_ID }
        assertNotNull(provider, "register() must register a FlowMcpToolProvider")
        assertEquals(
            setOf(
                "flow_create", "flow_rename", "flow_add_node", "flow_update_node",
                "flow_connect", "flow_delete_node", "flow_delete_edge", "flow_run", "flow_stop",
                "flow_status", "flow_result", "flow_list", "flow_get", "flow_delete",
                "prompt_upsert", "prompt_get", "prompt_list",
            ),
            provider.tools().map { it.name }.toSet(),
        )

        plugin.dispose()
        assertTrue(FlowMcpToolProvider.PROVIDER_ID in ctx.unregistered, "dispose() must unregister by providerId")
    }

    @Test
    fun `register twice disposes the previous headless registry collector`() {
        val registry = TrackingRegistry()
        val ctx = CapturingContext(scope, FakeStorage(), registry)
        val plugin = FlowTabDynamicPlugin()

        try {
            plugin.register(ctx)
            runBlocking {
                withTimeout(2_000) {
                    while (registry.collectorStarts.get() < 1 || registry.activeCollectors != 1) delay(10)
                }
            }

            plugin.register(ctx)
            runBlocking {
                withTimeout(2_000) {
                    while (registry.collectorStarts.get() < 2 || registry.activeCollectors != 1) delay(10)
                }
            }
            assertEquals(2, registry.collectorStarts.get(), "each registration should start one headless collector")
        } finally {
            plugin.dispose()
            runBlocking {
                withTimeout(2_000) {
                    while (registry.activeCollectors != 0) delay(10)
                }
            }
        }
    }
}
