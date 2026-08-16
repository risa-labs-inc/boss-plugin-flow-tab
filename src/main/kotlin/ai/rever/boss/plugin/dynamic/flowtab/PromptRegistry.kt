package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * CRUD store for [Prompt]s, persisted through the host's [PluginStorageProvider].
 *
 * Layout (F: absent-host-degrades): each prompt is a blob at `prompt:<id>`; the set of
 * live ids is a JSON string array at `prompts:index`, so [list] never has to enumerate
 * every storage key. All methods are null-safe: with no [storage] (host without a
 * storage factory) reads return empty/null and writes are no-ops rather than crashing.
 */
class PromptRegistry(private val storage: PluginStorageProvider?) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private suspend fun readIndex(): List<String> {
        val raw = storage?.getJson(INDEX_KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    private suspend fun writeIndex(ids: List<String>) {
        storage?.putJson(INDEX_KEY, json.encodeToString(ListSerializer(String.serializer()), ids))
    }

    /** All stored prompts, in index order. Missing/corrupt blobs are skipped. */
    suspend fun list(): List<Prompt> = readIndex().mapNotNull { get(it) }

    /** The prompt with [id], or null if absent/corrupt. */
    suspend fun get(id: String): Prompt? {
        val raw = storage?.getJson(key(id)) ?: return null
        return runCatching { json.decodeFromString(Prompt.serializer(), raw) }.getOrNull()
    }

    /** Insert or replace [prompt]; keeps the index de-duplicated. */
    suspend fun upsert(prompt: Prompt) {
        val s = storage ?: return
        s.putJson(key(prompt.id), json.encodeToString(Prompt.serializer(), prompt))
        val ids = readIndex()
        if (prompt.id !in ids) writeIndex(ids + prompt.id)
    }

    /**
     * Remove [id]'s blob and its index entry (no-op if unknown). The index is
     * updated only after verified blob removal, so a storage failure leaves the
     * prompt visible instead of hiding an orphan that cannot be deleted.
     *
     * @throws IllegalStateException if the host leaves the JSON blob behind.
     */
    suspend fun delete(id: String) {
        val s = storage ?: return
        s.removeJsonValue(key(id))
        val ids = readIndex()
        if (id in ids) writeIndex(ids - id)
    }

    private fun key(id: String) = "$PROMPT_PREFIX$id"

    companion object {
        const val PROMPT_PREFIX = "prompt:"
        const val INDEX_KEY = "prompts:index"
    }
}
