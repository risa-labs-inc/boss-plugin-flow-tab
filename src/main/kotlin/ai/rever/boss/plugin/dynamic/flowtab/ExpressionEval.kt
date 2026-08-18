package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimal `{{ }}` expression evaluator (Phase 1).
 *
 * Supports path access against:
 *  - `$json` — the current item's json object
 *  - `$node["Title"].json` — the first output item of the node titled "Title"
 *
 * Paths use `.key`, `.0`, `["key"]`, and `[index]`. An unresolved or malformed
 * expression throws [TemplateResolutionException] so the consuming node fails at
 * the source of the bad data instead of silently sending an empty value downstream.
 * [interpolateJson] recursively resolves JSON templates: a string containing exactly
 * one expression preserves the resolved JSON type, while expressions mixed with text
 * render into a string. `$node["Title"]` uses the node's flattened output ordered by
 * port number; when a control node emits on multiple ports, the first item is from the
 * lowest populated port.
 * This is deliberately NOT full JavaScript (GraalJS can't be plugin-bundled —
 * the host's binary-compat validator rejects the fat jar). Full JS would require
 * the host to ship GraalJS. See docs/AI_PIPELINE.md.
 */
object ExpressionEval {

    private val EXPR = Regex("""\{\{(.*?)}}""")

    /** Replace each `{{ expr }}` in [template] with its resolved value. */
    fun interpolate(
        template: String,
        json: JsonObject,
        nodeOutputsByTitle: Map<String, List<Item>>
    ): String = EXPR.replace(template) { m ->
        val expression = m.groupValues[1].trim()
        render(requireResolved(expression, json, nodeOutputsByTitle))
    }

    /** Evaluate a single expression to a JSON element (or null). */
    fun eval(expr: String, json: JsonObject, nodeOutputsByTitle: Map<String, List<Item>>): JsonElement? {
        val (root, rest) = when {
            expr == "\$json" || expr.startsWith("\$json.") || expr.startsWith("\$json[") ->
                json to expr.removePrefix("\$json")
            expr.startsWith("\$node[") -> {
                val close = expr.indexOf(']')
                if (close < 0) return null
                val title = expr.substring(6, close).trim().trim('"', '\'')
                if (title.isEmpty()) return null
                val suffix = expr.substring(close + 1)
                if (!suffix.startsWith(".json")) return null
                val after = suffix.removePrefix(".json")
                val first = nodeOutputsByTitle[title]?.firstOrNull()?.json ?: return null
                first to after
            }
            else -> return null
        }
        var cur: JsonElement? = root
        for (seg in parseSegments(rest) ?: return null) {
            cur = when (seg) {
                is Segment.Key -> (cur as? JsonObject)?.get(seg.key)
                is Segment.Index -> (cur as? JsonArray)?.getOrNull(seg.index)
            } ?: return null
        }
        return cur
    }

    /**
     * Recursively interpolate a JSON template. A string that consists solely of
     * one expression keeps the resolved JSON type (number, boolean, object, array,
     * or null); expressions embedded in surrounding text render as strings.
     */
    fun interpolateJson(
        template: JsonElement,
        json: JsonObject,
        nodeOutputsByTitle: Map<String, List<Item>>,
    ): JsonElement = when (template) {
        is JsonObject -> JsonObject(
            template.mapValues { (_, value) -> interpolateJson(value, json, nodeOutputsByTitle) }
        )
        is JsonArray -> JsonArray(template.map { interpolateJson(it, json, nodeOutputsByTitle) })
        is JsonPrimitive -> {
            if (!template.isString) template
            else {
                val trimmed = template.content.trim()
                // Reuse EXPR so a value containing two expressions cannot backtrack
                // into one false "whole expression" spanning the first {{ to last }}.
                val whole = EXPR.find(trimmed)?.takeIf { it.range.first == 0 && it.value.length == trimmed.length }
                if (whole != null) {
                    val expression = whole.groupValues[1].trim()
                    requireResolved(expression, json, nodeOutputsByTitle)
                } else {
                    JsonPrimitive(interpolate(template.content, json, nodeOutputsByTitle))
                }
            }
        }
    }

    private fun render(el: JsonElement?): String = when (el) {
        null, is JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private fun requireResolved(
        expression: String,
        json: JsonObject,
        nodeOutputsByTitle: Map<String, List<Item>>,
    ): JsonElement = eval(expression, json, nodeOutputsByTitle)
        ?: throw TemplateResolutionException(expression)

    private sealed interface Segment {
        data class Key(val key: String) : Segment
        data class Index(val index: Int) : Segment
    }

    private fun parseSegments(s: String): List<Segment>? {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) { sb.append(s[i]); i++ }
                    if (sb.isEmpty()) return null
                    val value = sb.toString()
                    out += value.toIntOrNull()?.let(Segment::Index) ?: Segment.Key(value)
                }
                '[' -> {
                    val end = s.indexOf(']', i)
                    if (end < 0) return null
                    val inner = s.substring(i + 1, end).trim()
                    i = end + 1
                    if (inner.length >= 2 &&
                        ((inner.first() == '"' && inner.last() == '"') ||
                            (inner.first() == '\'' && inner.last() == '\''))
                    ) {
                        out += Segment.Key(inner.substring(1, inner.lastIndex))
                    } else {
                        val index = inner.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                        out += Segment.Index(index)
                    }
                }
                else -> return null
            }
        }
        return out
    }
}

class TemplateResolutionException(expression: String) :
    IllegalArgumentException("Unresolved template expression '{{ $expression }}'")
