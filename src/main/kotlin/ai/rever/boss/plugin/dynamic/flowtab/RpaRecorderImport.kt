package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Imports an **RPA Recorder** configuration (`RpaConfiguration` JSON, as written
 * by the rparecorder plugin's save/export) into a chain of Flow nodes.
 *
 * Mirror types match rparecorder's `RpaConfiguration` / `RpaActionConfig` /
 * `SelectorInfo` (we can't import the plugin's classes, so we re-declare the
 * shape and parse leniently). Action `type` ∈ click / input / navigate / wait /
 * select / scroll / switch_frame / run_script / screenshot / assert.
 */
@Serializable
private data class RecSelector(
    val type: String = "css",
    val value: String? = null,
    val isUnique: Boolean? = null
)

@Serializable
private data class RecAction(
    val name: String = "",
    val actionType: String = "default",
    val type: String = "",
    val selector: RecSelector = RecSelector(),
    val value: String? = null,
    val meta: Map<String, String>? = null
)

@Serializable
private data class RecConfig(
    val name: String = "",
    val description: String = "",
    val actions: List<RecAction> = emptyList()
)

/** One node to create when importing (type + display title + config). */
data class ImportStep(val type: NodeType, val title: String, val config: JsonObject)

/** Result of converting a recording: the node chain + any skipped action types. */
data class ImportResult(val steps: List<ImportStep>, val skipped: List<String>)

object RpaRecorderImport {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Parse recorder-config JSON and convert it to a Flow node chain. Throws on invalid JSON. */
    fun convert(text: String): ImportResult {
        val config = json.decodeFromString(RecConfig.serializer(), text)
        val steps = mutableListOf<ImportStep>()
        val skipped = mutableListOf<String>()

        // Lead with an Open Browser node. If the first action is a navigate, use
        // its URL as the start URL and consume it.
        val actions = config.actions
        var startIndex = 0
        val firstNav = actions.firstOrNull()?.takeIf { it.type == "navigate" }
        val startUrl = firstNav?.value.orEmpty()
        if (firstNav != null) startIndex = 1
        steps.add(
            ImportStep(
                NodeType.OPEN_BROWSER,
                if (startUrl.isNotBlank()) "Open ${shortUrl(startUrl)}" else "Open Browser",
                buildJsonObject {
                    if (startUrl.isNotBlank()) put("url", startUrl)
                    put("headless", "false")
                }
            )
        )

        for (i in startIndex until actions.size) {
            val a = actions[i]
            val step = mapAction(a) ?: run { skipped.add(a.type.ifBlank { "unknown" }); null }
            if (step != null) steps.add(step)
        }
        return ImportResult(steps, skipped.distinct())
    }

    private fun mapAction(a: RecAction): ImportStep? {
        val (selType, selVal) = mapSelector(a.selector)
        val v = a.value.orEmpty()
        return when (a.type) {
            "navigate" -> ImportStep(NodeType.NAVIGATE, a.name.ifBlank { "Navigate" },
                buildJsonObject { put("url", v) })
            "click" -> ImportStep(NodeType.CLICK, a.name.ifBlank { "Click" },
                buildJsonObject { put("selectorType", selType); put("selector", selVal) })
            "input" -> ImportStep(NodeType.TYPE, a.name.ifBlank { "Type" },
                buildJsonObject { put("selectorType", selType); put("selector", selVal); put("text", v) })
            "select" -> ImportStep(NodeType.INJECT, a.name.ifBlank { "Select option" },
                buildJsonObject { put("script", selectJs(selType, selVal, v)) })
            "scroll" -> ImportStep(NodeType.INJECT, a.name.ifBlank { "Scroll" },
                buildJsonObject { put("script", scrollJs(v)) })
            "run_script" -> ImportStep(NodeType.INJECT, a.name.ifBlank { "Run script" },
                buildJsonObject { put("script", v) })
            // No faithful Flow equivalent yet.
            "wait", "screenshot", "assert", "switch_frame" -> null
            else -> null
        }
    }

    /** Recorder selector → Flow (selectorType, selector). Folds "id" into a css "#id". */
    private fun mapSelector(sel: RecSelector): Pair<String, String> {
        val v = sel.value.orEmpty()
        return when (sel.type) {
            "id" -> "css" to (if (v.startsWith("#")) v else "#$v")
            "xpath" -> "xpath" to v
            "text" -> "text" to v
            else -> "css" to v
        }
    }

    private fun jsStr(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun selectJs(selType: String, selVal: String, value: String): String =
        "(function(){var el=${BrowserScripts.elementExpr(selType, selVal)}; " +
            "if(el){el.value=${jsStr(value)}; el.dispatchEvent(new Event('change',{bubbles:true}));}})()"

    private fun scrollJs(value: String): String {
        val parts = value.split(",").mapNotNull { it.trim().toIntOrNull() }
        val x = parts.getOrElse(0) { 0 }
        val y = parts.getOrElse(1) { 0 }
        return "window.scrollTo($x, $y)"
    }

    private fun shortUrl(url: String): String =
        url.removePrefix("https://").removePrefix("http://").take(22)
}
