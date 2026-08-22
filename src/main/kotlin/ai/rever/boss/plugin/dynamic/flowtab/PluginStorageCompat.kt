package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val JSON_STORAGE_PREFIX = "json:"
internal const val RUN_STATE_PREFIX = "runstate:"
/** A per-flow display preference, deliberately separate from durable run history. */
internal const val RUN_VIEW_PREFIX = "runview:"

/**
 * A reset means "do not automatically reopen a result from before this instant".
 *
 * The timestamp is a cutoff rather than a boolean so that a run started after Reset
 * becomes visible normally, even if the app is closed before its final UI state is
 * written. Run records themselves are never modified or removed.
 */
@Serializable
internal data class RunViewPreference(val freshAfterMs: Long)

internal fun RunViewPreference.allowsAutoDisplay(startedAtMs: Long): Boolean =
    startedAtMs >= freshAfterMs

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

/** Persist a fresh canvas view without touching the workflow or its durable run history. */
internal suspend fun resetPersistedRunView(
    storage: PluginStorageProvider?,
    tabId: String,
    freshAfterMs: Long,
) {
    withContext(NonCancellable) {
        storage?.putJson(
            "$RUN_VIEW_PREFIX$tabId",
            Json.encodeToString(RunViewPreference.serializer(), RunViewPreference(freshAfterMs)),
        )
        storage?.removeJsonValue("$RUN_STATE_PREFIX$tabId")
    }
}

internal suspend fun loadRunViewPreference(
    storage: PluginStorageProvider?,
    tabId: String,
): RunViewPreference? = storage?.getJson("$RUN_VIEW_PREFIX$tabId")
    ?.let { raw -> runCatching { Json.decodeFromString(RunViewPreference.serializer(), raw) }.getOrNull() }

/** Used when the workflow itself is deleted; a newly-created flow must not inherit it. */
internal suspend fun clearPersistedRunViewPreference(storage: PluginStorageProvider?, tabId: String) {
    withContext(NonCancellable) {
        storage?.removeJsonValue("$RUN_VIEW_PREFIX$tabId")
    }
}
