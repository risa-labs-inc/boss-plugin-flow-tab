package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory [PluginStorageProvider] for tests — only the JSON/key/string surface
 * the registries and controller actually use is real; the typed scalar accessors are
 * inert. Mirrors the per-test fakes used elsewhere, hoisted so P5 tests reuse it.
 */
class TestStorage : PluginStorageProvider {
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

/**
 * Desktop-shaped storage fake. JSON values use physical `json:` keys, while
 * enumeration, contains, and remove operate on the raw backing keys exactly as
 * the current desktop host does.
 */
open class DesktopStorage : PluginStorageProvider {
    val map = ConcurrentHashMap<String, String>()
    override fun getPluginId() = "test-desktop"
    override suspend fun putJson(key: String, jsonValue: String) { map["$JSON_STORAGE_PREFIX$key"] = jsonValue }
    override suspend fun getJson(key: String): String? = map["$JSON_STORAGE_PREFIX$key"]
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
