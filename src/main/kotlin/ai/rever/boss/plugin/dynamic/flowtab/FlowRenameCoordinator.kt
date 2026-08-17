package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a rename authoritative across the two independent writers for a flow:
 * the storage-seated controller used by the launcher and the open tab's debounced
 * autosave. Without this coordination, a tab that still held the previous metadata
 * could write the old name back while it was closing.
 */
internal object FlowRenameCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val mutableNames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Latest successful rename per tab, replayed to tabs that start collecting later. */
    val names = mutableNames.asStateFlow()

    suspend fun <T> withFlowLock(tabId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(tabId) { Mutex() }.withLock { block() }

    fun publish(tabId: String, name: String) {
        mutableNames.update { current -> current + (tabId to name) }
    }

    fun latestName(tabId: String): String? = mutableNames.value[tabId]

    /** Preserve a newer rename when an autosave was captured from stale open-tab state. */
    fun applyLatestName(tabId: String, snapshot: GraphSnapshot): GraphSnapshot {
        val name = latestName(tabId) ?: return snapshot
        val metadata = (snapshot.metadata ?: FlowMeta()).copy(name = name)
        return if (snapshot.metadata == metadata) snapshot else snapshot.copy(metadata = metadata)
    }

    fun forget(tabId: String) {
        mutableNames.update { current -> current - tabId }
    }
}
