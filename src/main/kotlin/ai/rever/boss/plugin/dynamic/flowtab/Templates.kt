package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P6 — lanager templates.
 *
 * A **template** is a [GraphSnapshot] v2 that carries [FlowMeta] (a name/description/
 * inputs) — the same on-disk shape a plain flow uses, distinguished only by the
 * presence of non-null [GraphSnapshot.metadata]. Bundled starters live as JSON
 * resources under `templates/` (enumerated by `templates/index.json`); users and
 * agents author their own and import/export them.
 */

/** One template from the bundled catalog: its [id], display [name]/[description]
 *  (from [FlowMeta]), the parsed [snapshot], and the exact [raw] JSON it was loaded
 *  from (handed to the import path so a gallery pick reuses the same open-in-new-tab
 *  flow as a file import). */
data class TemplateEntry(
    val id: String,
    val name: String,
    val description: String,
    val snapshot: GraphSnapshot,
    val raw: String,
)

/**
 * Loads bundled starter templates from the classpath. Rather than scan the jar (not
 * portably enumerable), it reads a hand-maintained `templates/index.json` array of
 * filenames and loads each. All methods degrade to empty on a missing/corrupt index
 * or blob rather than throwing.
 */
class TemplateCatalog(
    private val loader: ClassLoader = TemplateCatalog::class.java.classLoader,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun read(path: String): String? =
        loader.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    private fun index(): List<String> {
        val raw = read("$DIR/$INDEX") ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    /** Ids (filename without `.json`) of every bundled template, in index order. */
    fun ids(): List<String> = index().map { it.removeSuffix(".json") }

    /** Load one template by id or filename; null if absent/corrupt. */
    fun load(idOrFile: String): TemplateEntry? {
        val file = if (idOrFile.endsWith(".json")) idOrFile else "$idOrFile.json"
        val raw = read("$DIR/$file") ?: return null
        val snap = runCatching { json.decodeFromString(GraphSnapshot.serializer(), raw) }.getOrNull() ?: return null
        val id = file.removeSuffix(".json")
        return TemplateEntry(
            id = id,
            name = snap.metadata?.name?.ifBlank { id } ?: id,
            description = snap.metadata?.description.orEmpty(),
            snapshot = snap,
            raw = raw,
        )
    }

    /** Every bundled template that loads, in index order. */
    fun all(): List<TemplateEntry> = index().mapNotNull { load(it) }

    companion object {
        const val DIR = "templates"
        const val INDEX = "index.json"
    }
}

object FlowTemplates {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /**
     * Config keys that must never leave the app in an exported template — api keys,
     * session tokens, passwords, cookies, credentials. Matched case-insensitively as a
     * substring so `apiKey`, `session_token`, `AUTH_TOKEN`, etc. are all caught. External
     * server creds live only in `secretDataProvider` and are never in a [GraphSnapshot],
     * but a hand-built node could stash one in config — this is the belt-and-braces strip.
     */
    val SECRET_KEY_PATTERN = Regex(
        "(?i)(secret|token|password|passwd|api[-_]?key|apikey|cookie|session|bearer|credential|private[-_]?key|access[-_]?key)"
    )

    private fun isSecretKey(key: String): Boolean = SECRET_KEY_PATTERN.containsMatchIn(key)

    /** Recursively drop every secret-looking key from a config object/array tree. */
    private fun stripSecrets(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterKeys { !isSecretKey(it) }.mapValues { stripSecrets(it.value) }
        )
        is JsonArray -> JsonArray(element.map { stripSecrets(it) })
        else -> element
    }

    private fun stripSecrets(config: JsonObject): JsonObject = stripSecrets(config as JsonElement) as JsonObject

    /**
     * Produce a self-contained, shareable template from [snapshot]:
     *  - ensures [FlowMeta] is present (a template must carry metadata);
     *  - inlines any [Prompt] an `agent` node references by id into that node's inline
     *    `system` field via [composeSystemPrompt], so the template works on a machine
     *    without that prompt in its registry;
     *  - strips secret-looking config keys from every node (F: secrets never exported);
     *  - stamps the current [SUPPORTED_SCHEMA_VERSION].
     */
    suspend fun export(snapshot: GraphSnapshot, prompts: PromptRegistry?): GraphSnapshot {
        val meta = snapshot.metadata ?: FlowMeta(name = "Untitled flow")
        val nodes = snapshot.nodes.map { node ->
            var config = stripSecrets(node.config)
            if (node.type == AgentNode.KIND) config = inlinePrompt(config, prompts)
            node.copy(config = config)
        }
        return snapshot.copy(
            nodes = nodes,
            metadata = meta,
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
        )
    }

    /** Resolve an agent node's `promptId` against [prompts] and bake the composed system
     *  text into its inline `system` field. No-op when there is no id / it doesn't resolve. */
    private suspend fun inlinePrompt(config: JsonObject, prompts: PromptRegistry?): JsonObject {
        val id = (config[AgentNode.PROMPT_ID_KEY] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return config
        val prompt = prompts?.get(id) ?: return config
        val system = composeSystemPrompt(prompt)
        return JsonObject(config + (AgentNode.SYSTEM_KEY to JsonPrimitive(system)))
    }

    /** Serialize a template (with metadata + current schema) to pretty JSON for export. */
    fun encode(snapshot: GraphSnapshot): String =
        Json { prettyPrint = true; encodeDefaults = true }
            .encodeToString(GraphSnapshot.serializer(), snapshot)
}

// ---------------------------------------------------------------------------
// Import classification (shared by the UI import button + tests)
// ---------------------------------------------------------------------------

/** Which importer an incoming JSON blob routes to. */
enum class ImportKind { FLOW, TEMPLATE }

/**
 * Outcome of inspecting an imported blob. Keeps the routing/gating logic pure so the
 * UI and tests share one code path (the UI just maps each case to a tab-open / notice).
 * (Named distinctly from the RPA-recording [ImportResult] in the same package.)
 */
sealed interface TemplateImportResult {
    /** A loadable graph — a plain [ImportKind.FLOW] or a [ImportKind.TEMPLATE] (has metadata). */
    data class Graph(val kind: ImportKind, val snapshot: GraphSnapshot) : TemplateImportResult

    /** Not a flow graph (no `nodes`) — hand to the RPA-recording importer. */
    data object Recording : TemplateImportResult

    /** A graph whose [GraphSnapshot.schemaVersion] is newer than this build supports —
     *  refused gracefully rather than mis-loaded (the enum→String one-way-door guard). */
    data class RefusedNewer(val schemaVersion: Int) : TemplateImportResult

    /** Looked like a graph (has `nodes`) but failed to decode. */
    data class Invalid(val message: String) : TemplateImportResult
}

private val importJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Classify [text] for import: a JSON object with a `nodes` array is a flow graph — a
 * template when it also carries non-null `metadata`; anything else is treated as an
 * RPA recording. A graph is decoded and schema-gated: a newer [GraphSnapshot.schemaVersion]
 * yields [TemplateImportResult.RefusedNewer] instead of a broken load.
 */
fun classifyImport(text: String): TemplateImportResult {
    val obj = runCatching { importJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return TemplateImportResult.Recording
    if (!obj.containsKey("nodes")) return TemplateImportResult.Recording
    val snap = runCatching { importJson.decodeFromString(GraphSnapshot.serializer(), text) }.getOrNull()
        ?: return TemplateImportResult.Invalid("not a valid flow")
    if (!snap.isSchemaSupported()) return TemplateImportResult.RefusedNewer(snap.schemaVersion)
    val hasMeta = obj["metadata"].let { it != null && it !is JsonNull }
    return TemplateImportResult.Graph(if (hasMeta) ImportKind.TEMPLATE else ImportKind.FLOW, snap)
}
