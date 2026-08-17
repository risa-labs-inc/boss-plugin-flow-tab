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

    /**
     * Latest successful rename per tab, replayed to tabs that start collecting later.
     * A closed tab has no autosave with which to acknowledge convergence, so its entry
     * remains until reopen or delete. Any future metadata-name writer must coordinate
     * here rather than bypassing this ordering guard.
     */
    val names = mutableNames.asStateFlow()

    suspend fun <T> withFlowLock(tabId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(tabId) { Mutex() }.withLock { block() }

    /**
     * Serialize an open tab's full-snapshot write against rename and replace only a
     * stale name. Once the live tab has saved the published name, the guard has
     * converged and is removed so a later import may intentionally change metadata.
     */
    suspend fun persistAutosave(
        tabId: String,
        snapshot: GraphSnapshot,
        persist: suspend (GraphSnapshot) -> Unit,
    ) = withFlowLock(tabId) {
        val name = latestName(tabId)
        when {
            name == null -> persist(snapshot)
            snapshot.metadata?.name == name -> {
                persist(snapshot)
                // Clear only after the converged snapshot is durable. A failed save
                // must leave the stale-write protection in place for the next attempt.
                forget(tabId)
            }
            else -> {
                val metadata = (snapshot.metadata ?: FlowMeta()).copy(name = name)
                persist(snapshot.copy(metadata = metadata))
            }
        }
    }

    fun publish(tabId: String, name: String) {
        mutableNames.update { current -> current + (tabId to name) }
    }

    fun latestName(tabId: String): String? = mutableNames.value[tabId]

    fun forget(tabId: String) {
        mutableNames.update { current -> current - tabId }
        // Keep the mutex entry for the plugin lifetime. Removing a held mutex could
        // let an already-waiting writer and a new writer acquire different locks.
    }
}
