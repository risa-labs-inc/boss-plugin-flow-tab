package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Renders a tool's JSON-Schema `inputSchema` string into a flat list of
 * [ConfigField]s the inspector shows and [ToolArgs] marshals back to `argsJson`.
 *
 * Mapping (red-team F3 — the schemas are arbitrary, so bound the blast radius):
 *  - `string` → [FieldType.TEXT]  (or [FieldType.SELECT] when it carries an `enum`)
 *  - `integer` / `number` → [FieldType.NUMBER]
 *  - `boolean` → [FieldType.BOOL]
 *  - `object` / `array` / unknown-or-missing type → [FieldType.JSON] (a raw editor)
 *
 * Degenerate schemas degrade rather than throw:
 *  - unparseable / non-object / object-without-`properties` → one raw-JSON fallback
 *    field keyed [RAW_ARGS_KEY] that holds the entire `argsJson`,
 *  - an explicit empty `properties` (a no-arg tool) → no fields.
 */
object JsonSchemaToConfig {
    /** Config key of the single raw-JSON fallback field (whole `argsJson`). `__`-prefixed
     *  so it never collides with a real schema property name. */
    const val RAW_ARGS_KEY = "__args"

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    fun convert(inputSchema: String): List<ConfigField> {
        val root = runCatching { JSON.parseToJsonElement(inputSchema) }.getOrNull() as? JsonObject
            ?: return listOf(rawArgsField())
        val props = root["properties"] as? JsonObject ?: return listOf(rawArgsField())
        if (props.isEmpty()) return emptyList()
        return props.map { (key, el) -> fieldFor(key, el as? JsonObject ?: JsonObject(emptyMap())) }
    }

    private fun rawArgsField(): ConfigField = ConfigField(
        key = RAW_ARGS_KEY,
        label = "Arguments (JSON)",
        type = FieldType.JSON,
        placeholder = "{ }",
    )

    private fun fieldFor(key: String, s: JsonObject): ConfigField {
        val title = (s["title"] as? JsonPrimitive)?.contentOrNull ?: key
        val desc = (s["description"] as? JsonPrimitive)?.contentOrNull ?: ""
        val default = s["default"]?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""
        val enum = s["enum"] as? JsonArray
        val type = (s["type"] as? JsonPrimitive)?.contentOrNull
        return when {
            enum != null -> ConfigField(
                key, title, FieldType.SELECT,
                options = enum.mapNotNull { (it as? JsonPrimitive)?.content },
                default = default,
            )
            type == "boolean" -> ConfigField(key, title, FieldType.BOOL, default = default.ifEmpty { "false" })
            type == "integer" || type == "number" ->
                ConfigField(key, title, FieldType.NUMBER, placeholder = desc, default = default)
            type == "string" -> ConfigField(key, title, FieldType.TEXT, placeholder = desc, default = default)
            // object / array / unknown / missing → raw JSON editor.
            else -> ConfigField(key, title, FieldType.JSON, placeholder = desc, default = default)
        }
    }
}
