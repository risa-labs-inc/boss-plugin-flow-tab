package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider

/**
 * Remove a JSON value across both storage-provider key conventions.
 *
 * The current desktop host exposes typed JSON values as raw `json:<key>` entries
 * to [PluginStorageProvider.remove], while logical providers
 * remove the same value with `<key>`. Removing both is idempotent and keeps the
 * plugin compatible with current desktop hosts and prefix-aware providers.
 */
internal suspend fun PluginStorageProvider.removeJsonValue(key: String) {
    val logical = runCatching { remove(key) }
    val physical = runCatching { remove("json:$key") }
    if (logical.isFailure && physical.isFailure) throw logical.exceptionOrNull()!!
}
