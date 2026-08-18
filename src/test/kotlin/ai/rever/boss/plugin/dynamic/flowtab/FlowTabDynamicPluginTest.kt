package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.ConcurrentHashMap
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
    private class CapturingContext(scope: CoroutineScope, storage: PluginStorageProvider) : PluginContext {
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
}
