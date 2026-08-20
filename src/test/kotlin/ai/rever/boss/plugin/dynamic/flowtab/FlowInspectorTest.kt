package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlowInspectorTest {

    private val jsonField = ConfigField("payload", "Payload", FieldType.JSON, default = "{}")
    private val spec = NodeSpec(
        id = "test",
        label = "Test",
        inputs = 0,
        outputs = 0,
        accent = 0,
        description = "Test node",
        configFields = listOf(jsonField),
    )

    @Test
    fun `structured JSON object renders as editable text and round trips through the inspector`() {
        assertStructuredJsonRoundTrip(
            buildJsonObject {
                put("enabled", true)
                put("nested", buildJsonObject { put("name", "flow") })
            },
        )
    }

    @Test
    fun `structured JSON array renders as editable text and round trips through the inspector`() {
        assertStructuredJsonRoundTrip(
            buildJsonArray {
                add("tool:boss:docker_ps")
                add(buildJsonObject { put("retries", 2) })
            },
        )
    }

    private fun assertStructuredJsonRoundTrip(structured: JsonElement) {
        val node = FlowNode("n1", spec, "Test", 0f, 0f, buildJsonObject { put(jsonField.key, structured) })

        val rendered = configValue(node, jsonField)
        assertEquals(structured, Json.parseToJsonElement(rendered))

        setConfig(node, jsonField.key, rendered)
        val stored = assertIs<JsonPrimitive>(node.config[jsonField.key])
        assertEquals(rendered, stored.jsonPrimitive.content)
        assertEquals(structured, Json.parseToJsonElement(configValue(node, jsonField)))
    }
}
