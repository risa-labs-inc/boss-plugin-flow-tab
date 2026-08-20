package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowInspectorCopyTest {

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
}
