package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowInspectorCopyTest {

    private fun context(clipboard: () -> ClipboardProvider?): PluginContext = object : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(Dispatchers.Unconfined)
        override val clipboardProvider: ClipboardProvider? get() = clipboard()
    }

    @Test
    fun `copied output is the complete pretty printed item array`() {
        val items = listOf(
            Item(buildJsonObject { put("name", "first") }),
            Item(
                buildJsonObject {
                    put("name", "second")
                    put("nested", buildJsonObject { put("visibleAfterCollapse", true) })
                },
            ),
        )

        val copied = inspectorOutputText(items)

        assertTrue(copied.lines().size > 2, "structured output should retain pretty-printed line breaks")
        assertEquals(JsonArray(items.map { it.json }), Json.parseToJsonElement(copied))
    }

    @Test
    fun `copied logs preserve stored boundaries and embedded line breaks`() {
        assertEquals(
            "first log\nsecond log\ncontinued",
            inspectorLogsText(listOf("first log", "second log\ncontinued")),
        )
    }

    @Test
    fun `empty output copies as an empty JSON array`() {
        assertEquals("[]", inspectorOutputText(emptyList()))
    }

    @Test
    fun `host clipboard copy preserves content and reports success`() {
        var copied = ""
        val clipboard = object : ClipboardProvider {
            override fun readText(): String? = copied
            override fun setText(text: String): Boolean {
                copied = text
                return true
            }
            override fun hasText(): Boolean = copied.isNotEmpty()
        }

        assertTrue(copyInspectorText(context { clipboard }, "line one\nline two"))
        assertEquals("line one\nline two", copied)
    }

    @Test
    fun `missing or throwing clipboard provider reports failure`() {
        assertFalse(copyInspectorText(context { null }, "content"))
        assertFalse(copyInspectorText(context { error("host unavailable") }, "content"))
    }
}
