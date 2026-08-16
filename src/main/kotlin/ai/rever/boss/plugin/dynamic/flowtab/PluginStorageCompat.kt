package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal const val JSON_STORAGE_PREFIX = "json:"
internal const val RUN_STATE_PREFIX = "runstate:"

/**
 * Orders run-state persistence against destructive actions such as clearing a flow.
 *
 * A generation token prevents an invalidated run from publishing late status or a
 * final snapshot. The mutex closes the remaining race where a final save has already
 * passed its generation check when Clear starts: Clear waits for that save, then
 * removes it; if Clear wins the mutex, the stale save re-checks its token and skips.
 */
internal class RunStatePersistenceGate {
    private val generation = AtomicLong(0)
    private val persistenceMutex = Mutex()

    fun beginRun(): Long = generation.incrementAndGet()

    fun invalidateRun() {
        generation.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = generation.get() == token

    suspend fun persistIfCurrent(token: Long, persist: suspend () -> Unit): Boolean =
        withContext(NonCancellable) {
            persistenceMutex.withLock {
                if (!isCurrent(token)) return@withLock false
                persist()
                true
            }
        }

    suspend fun clear(clearPersisted: suspend () -> Unit) {
        withContext(NonCancellable) {
            persistenceMutex.withLock { clearPersisted() }
        }
    }
}

/**
 * Remove a JSON value across both storage-provider key conventions.
 *
 * The current desktop host exposes typed JSON values as raw `json:<key>` entries
 * to [PluginStorageProvider.remove], while logical providers remove the same value
 * with `<key>`. Removing both is idempotent and keeps the plugin compatible with
 * current desktop hosts and prefix-aware providers. Keys passed here must be
 * JSON-only because both the logical and physical raw names are claimed.
 *
 * @throws IllegalStateException if [PluginStorageProvider.getJson] still finds the value
 * after both removal attempts.
 * @throws CancellationException without converting cancellation into a removal failure.
 */
internal suspend fun PluginStorageProvider.removeJsonValue(key: String) {
    val failures = mutableListOf<Exception>()
    for (candidate in listOf(key, "$JSON_STORAGE_PREFIX$key")) {
        try {
            remove(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += failure
        }
    }

    val remaining = try {
        getJson(key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Suppressing an exception onto itself throws and would hide the host failure.
        failures.forEach { if (it !== failure) failure.addSuppressed(it) }
        throw failure
    }
    if (remaining != null) {
        val failure = IllegalStateException("JSON value '$key' remains after removal")
        failures.forEach(failure::addSuppressed)
        throw failure
    }
}

/** Clear the persisted run snapshot for [tabId]. Extracted from the Composable for testing. */
internal suspend fun clearPersistedRunState(storage: PluginStorageProvider?, tabId: String) {
    withContext(NonCancellable) {
        storage?.removeJsonValue("$RUN_STATE_PREFIX$tabId")
    }
}
