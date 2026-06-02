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
 * Paths use `.key`, `["key"]`, and `[index]`. Anything unresolved renders empty.
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
        runCatching { render(eval(m.groupValues[1].trim(), json, nodeOutputsByTitle)) }
            .getOrDefault("")
    }

    /** Evaluate a single expression to a JSON element (or null). */
    fun eval(expr: String, json: JsonObject, nodeOutputsByTitle: Map<String, List<Item>>): JsonElement? {
        val (root, rest) = when {
            expr.startsWith("\$json") -> json to expr.removePrefix("\$json")
            expr.startsWith("\$node[") -> {
                val close = expr.indexOf(']')
                if (close < 0) return null
                val title = expr.substring(6, close).trim().trim('"', '\'')
                var after = expr.substring(close + 1)
                after = after.removePrefix(".json")
                val first = nodeOutputsByTitle[title]?.firstOrNull()?.json ?: return null
                first to after
            }
            else -> return null
        }
        var cur: JsonElement? = root
        for (seg in parseSegments(rest)) {
            cur = when (seg) {
                is Segment.Key -> (cur as? JsonObject)?.get(seg.key)
                is Segment.Index -> (cur as? JsonArray)?.getOrNull(seg.index)
            } ?: return null
        }
        return cur
    }

    private fun render(el: JsonElement?): String = when (el) {
        null, is JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private sealed interface Segment {
        data class Key(val key: String) : Segment
        data class Index(val index: Int) : Segment
    }

    private fun parseSegments(s: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) { sb.append(s[i]); i++ }
                    if (sb.isNotEmpty()) out.add(Segment.Key(sb.toString()))
                }
                '[' -> {
                    val end = s.indexOf(']', i)
                    if (end < 0) break
                    val inner = s.substring(i + 1, end).trim()
                    i = end + 1
                    if (inner.startsWith("\"") || inner.startsWith("'")) {
                        out.add(Segment.Key(inner.trim('"', '\'')))
                    } else {
                        inner.toIntOrNull()?.let { out.add(Segment.Index(it)) }
                    }
                }
                else -> i++
            }
        }
        return out
    }
}
