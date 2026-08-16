package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal const val JSON_STORAGE_PREFIX = "json:"
internal const val RUN_STATE_PREFIX = "runstate:"

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
