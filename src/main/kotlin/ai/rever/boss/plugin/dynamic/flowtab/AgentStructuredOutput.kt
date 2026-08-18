package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parsed, locally validated JSON Schema for an Agent node's structured result.
 *
 * The schema is also advertised to the model as the input schema of
 * [AgentStructuredOutput.TOOL_NAME]. Provider-side tool argument validation is useful,
 * but not trusted as the correctness boundary: [validate] checks the submitted value
 * again before it can enter the flow item stream.
 */
data class AgentOutputSchema(
    val json: JsonObject,
    val source: String,
) {
    fun validate(value: JsonObject): String? = AgentStructuredOutput.validate(json, value)
}

/** Provider-agnostic structured-output protocol and its bounded JSON Schema validator. */
internal object AgentStructuredOutput {
    const val TOOL_NAME = "flow_submit_output"

    const val SYSTEM_INSTRUCTION =
        "When the final answer is ready, call the flow_submit_output tool exactly once with an object " +
            "that satisfies its JSON Schema. The submission must be the only tool call in that turn. " +
            "Do not return the final answer as plain text."

    const val MISSING_SUBMISSION_MESSAGE =
        "A structured result is required. Call flow_submit_output with a conforming object; do not answer in text."

    private val JSON = Json { isLenient = false }

    fun parse(raw: String): AgentOutputSchema {
        val trimmed = raw.trim()
        val parsed = runCatching { JSON.parseToJsonElement(trimmed) }.getOrElse { error ->
            throw ExecError("Agent output schema (outputSchema) must be valid JSON: ${error.message}")
        }
        val root = parsed as? JsonObject
            ?: throw ExecError("Agent output schema (outputSchema) must be a JSON object")
        val rootTypes = schemaTypes(root, "\$")
        if (rootTypes != null && "object" !in rootTypes) {
            throw ExecError("Agent output schema (outputSchema) must describe an object")
        }
        validateSchema(root, "\$")
        return AgentOutputSchema(root, root.toString())
    }

    fun descriptor(schema: AgentOutputSchema): ToolDescriptor = ToolDescriptor(
        ref = ToolRef(ToolScope.FLOW, TOOL_NAME),
        name = TOOL_NAME,
        description = "Submit the Agent node's final structured result.",
        inputSchema = schema.source,
    )

    fun parseSubmission(raw: String, schema: AgentOutputSchema): Result<JsonObject> = runCatching {
        val parsed = JSON.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("the submission must be a JSON object")
        schema.validate(parsed)?.let { throw IllegalArgumentException(it) }
        parsed
    }

    fun validate(schema: JsonElement, value: JsonElement, path: String = "\$"): String? {
        if (schema is JsonPrimitive && schema.strictBooleanOrNull() != null) {
            return if (schema.strictBooleanOrNull() == true) null else "$path is rejected by the schema"
        }
        val obj = schema as? JsonObject ?: return "$path has an invalid schema"

        (obj["allOf"] as? JsonArray)?.forEach { branch ->
            validate(branch, value, path)?.let { return it }
        }
        (obj["anyOf"] as? JsonArray)?.let { branches ->
            if (branches.none { validate(it, value, path) == null }) return "$path does not match any allowed schema"
        }
        (obj["oneOf"] as? JsonArray)?.let { branches ->
            if (branches.count { validate(it, value, path) == null } != 1) {
                return "$path must match exactly one allowed schema"
            }
        }
        obj["not"]?.let { forbidden ->
            if (validate(forbidden, value, path) == null) return "$path matches a forbidden schema"
        }
        obj["if"]?.let { condition ->
            val branch = if (validate(condition, value, path) == null) obj["then"] else obj["else"]
            branch?.let { validate(it, value, path)?.let { error -> return error } }
        }

        obj["const"]?.let { expected ->
            if (value != expected) return "$path must equal ${display(expected)}"
        }
        (obj["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) return "$path must be one of ${allowed.joinToString { display(it) }}"
        }

        val types = schemaTypes(obj, path)
        if (types != null && types.none { matchesType(value, it) }) {
            return "$path must be ${types.joinToString(" or ")}"
        }

        when (value) {
            is JsonObject -> validateObject(obj, value, path)?.let { return it }
            is JsonArray -> validateArray(obj, value, path)?.let { return it }
            is JsonPrimitive -> when {
                value.isString -> validateString(obj, value.content, path)?.let { return it }
                value !== JsonNull && value.booleanOrNull == null ->
                    validateNumber(obj, value, path)?.let { return it }
            }
        }
        return null
    }

    private fun validateObject(schema: JsonObject, value: JsonObject, path: String): String? {
        val required = schema["required"] as? JsonArray
        required?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.forEach { key ->
            if (key !in value) return "${propertyPath(path, key)} is required"
        }

        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        for ((key, propertySchema) in properties) {
            value[key]?.let { child ->
                validate(propertySchema, child, propertyPath(path, key))?.let { return it }
            }
        }

        val extras = value.keys - properties.keys
        when (val additional = schema["additionalProperties"]) {
            is JsonPrimitive -> if (additional.strictBooleanOrNull() == false && extras.isNotEmpty()) {
                return "${propertyPath(path, extras.first())} is not allowed"
            }
            is JsonObject -> extras.forEach { key ->
                validate(additional, value.getValue(key), propertyPath(path, key))?.let { return it }
            }
            else -> Unit
        }

        schema.nonNegativeInt("minProperties")?.let { if (value.size < it) return "$path must have at least $it properties" }
        schema.nonNegativeInt("maxProperties")?.let { if (value.size > it) return "$path must have at most $it properties" }
        return null
    }

    private fun validateArray(schema: JsonObject, value: JsonArray, path: String): String? {
        schema["items"]?.let { itemSchema ->
            value.forEachIndexed { index, element ->
                validate(itemSchema, element, "$path[$index]")?.let { return it }
            }
        }
        schema.nonNegativeInt("minItems")?.let { if (value.size < it) return "$path must have at least $it items" }
        schema.nonNegativeInt("maxItems")?.let { if (value.size > it) return "$path must have at most $it items" }
        if ((schema["uniqueItems"] as? JsonPrimitive)?.strictBooleanOrNull() == true &&
            value.distinct().size != value.size
        ) {
            return "$path must contain unique items"
        }
        return null
    }

    private fun validateString(schema: JsonObject, value: String, path: String): String? {
        val length = value.codePointCount(0, value.length)
        schema.nonNegativeInt("minLength")?.let { if (length < it) return "$path must contain at least $it characters" }
        schema.nonNegativeInt("maxLength")?.let { if (length > it) return "$path must contain at most $it characters" }
        schema.string("pattern")?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(value)) return "$path must match pattern ${display(JsonPrimitive(pattern))}"
        }
        return null
    }

    private fun validateNumber(schema: JsonObject, value: JsonPrimitive, path: String): String? {
        val number = value.doubleOrNull ?: return null
        schema.number("minimum")?.let { if (number < it) return "$path must be at least $it" }
        schema.number("maximum")?.let { if (number > it) return "$path must be at most $it" }
        schema.number("exclusiveMinimum")?.let { if (number <= it) return "$path must be greater than $it" }
        schema.number("exclusiveMaximum")?.let { if (number >= it) return "$path must be less than $it" }
        schema.number("multipleOf")?.let { divisor ->
            val quotient = number / divisor
            if (kotlin.math.abs(quotient - kotlin.math.round(quotient)) > 1e-9) {
                return "$path must be a multiple of $divisor"
            }
        }
        return null
    }

    private fun validateSchema(schema: JsonElement, path: String) {
        if (schema is JsonPrimitive && schema.strictBooleanOrNull() != null) return
        val obj = schema as? JsonObject ?: throw ExecError("Agent output schema at $path must be an object or boolean")
        val unsupported = UNSUPPORTED_KEYWORDS.firstOrNull { it in obj }
        if (unsupported != null) {
            throw ExecError("Agent output schema at $path uses unsupported keyword '$unsupported'")
        }
        schemaTypes(obj, path)
        obj["required"]?.let { required ->
            val array = required as? JsonArray
                ?: throw ExecError("Agent output schema at $path has a non-array 'required'")
            if (array.any { it !is JsonPrimitive || !it.isString }) {
                throw ExecError("Agent output schema at $path has a non-string required property")
            }
        }
        obj["properties"]?.let { properties ->
            val map = properties as? JsonObject
                ?: throw ExecError("Agent output schema at $path has non-object 'properties'")
            map.forEach { (key, child) -> validateSchema(child, propertyPath(path, key)) }
        }
        obj["additionalProperties"]?.let { validateSchema(it, "$path.additionalProperties") }
        obj["items"]?.let { validateSchema(it, "$path.items") }
        for (keyword in listOf("allOf", "anyOf", "oneOf")) {
            obj[keyword]?.let { branches ->
                val array = branches as? JsonArray
                    ?: throw ExecError("Agent output schema at $path has non-array '$keyword'")
                if (array.isEmpty()) throw ExecError("Agent output schema at $path has empty '$keyword'")
                array.forEachIndexed { index, child -> validateSchema(child, "$path.$keyword[$index]") }
            }
        }
        obj["not"]?.let { validateSchema(it, "$path.not") }
        obj["if"]?.let { validateSchema(it, "$path.if") }
        obj["then"]?.let { validateSchema(it, "$path.then") }
        obj["else"]?.let { validateSchema(it, "$path.else") }
        obj["enum"]?.let {
            if (it !is JsonArray || it.isEmpty()) throw ExecError("Agent output schema at $path has invalid 'enum'")
        }
        for (keyword in NON_NEGATIVE_INTEGER_KEYWORDS) obj[keyword]?.let {
            val number = (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            if (number == null || number < 0) {
                throw ExecError("Agent output schema at $path has invalid '$keyword'")
            }
        }
        for (keyword in NUMBER_KEYWORDS) obj[keyword]?.let {
            val number = (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
            if (number == null || !number.isFinite() || (keyword == "multipleOf" && number <= 0)) {
                throw ExecError("Agent output schema at $path has invalid '$keyword'")
            }
        }
        obj["uniqueItems"]?.let {
            if ((it as? JsonPrimitive)?.strictBooleanOrNull() == null) {
                throw ExecError("Agent output schema at $path has non-boolean 'uniqueItems'")
            }
        }
        obj["pattern"]?.let {
            val pattern = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw ExecError("Agent output schema at $path has non-string 'pattern'")
            runCatching { Regex(pattern) }.getOrElse { error ->
                throw ExecError("Agent output schema at $path has invalid 'pattern': ${error.message}")
            }
        }
    }

    private fun schemaTypes(schema: JsonObject, path: String): Set<String>? {
        val type = schema["type"] ?: return null
        val types = when (type) {
            is JsonPrimitive -> type.takeIf(JsonPrimitive::isString)?.contentOrNull?.let(::setOf)
            is JsonArray -> type
                .takeIf { values -> values.all { it is JsonPrimitive && it.isString } }
                ?.map { (it as JsonPrimitive).content }
                ?.toSet()
            else -> null
        } ?: throw ExecError("Agent output schema at $path has invalid 'type'")
        if (types.isEmpty() || types.any { it !in JSON_TYPES }) {
            throw ExecError("Agent output schema at $path has invalid 'type'")
        }
        return types
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && value.strictBooleanOrNull() != null
        "null" -> value === JsonNull
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let { it % 1.0 == 0.0 } == true
        else -> false
    }

    private fun JsonObject.nonNegativeInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonPrimitive.strictBooleanOrNull(): Boolean? =
        takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private fun propertyPath(parent: String, key: String): String =
        if (SIMPLE_PROPERTY.matches(key)) "$parent.$key" else "$parent[${display(JsonPrimitive(key))}]"

    private fun display(value: JsonElement): String = value.toString().take(MAX_DISPLAY_CHARS)

    private val SIMPLE_PROPERTY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val JSON_TYPES = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    private val NON_NEGATIVE_INTEGER_KEYWORDS = setOf(
        "minProperties", "maxProperties", "minItems", "maxItems", "minLength", "maxLength",
    )
    private val NUMBER_KEYWORDS = setOf(
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    )
    private val UNSUPPORTED_KEYWORDS = setOf(
        "\$ref", "\$dynamicRef", "patternProperties", "dependentSchemas", "dependentRequired",
        "propertyNames", "prefixItems", "contains", "minContains", "maxContains", "unevaluatedItems",
        "unevaluatedProperties",
    )
    private const val MAX_DISPLAY_CHARS = 160
}
