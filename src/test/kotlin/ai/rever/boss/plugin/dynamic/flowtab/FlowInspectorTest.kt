package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        executor = NodeExecutor { _, _, _, _ -> NodeOutput.single(emptyList()) },
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
        assertTrue(stored.isString)
        assertEquals(rendered, stored.content)
        assertEquals(structured, Json.parseToJsonElement(configValue(node, jsonField)))
    }

    @Test
    fun `config value preserves primitive and fallback behavior`() {
        val primitive = node(buildJsonObject { put(jsonField.key, "already string-backed") })
        assertEquals("already string-backed", configValue(primitive, jsonField))

        assertEquals(jsonField.default, configValue(node(buildJsonObject {}), jsonField))
        assertEquals(jsonField.default, configValue(node(buildJsonObject { put(jsonField.key, JsonNull) }), jsonField))

        val textField = ConfigField(jsonField.key, "Payload", FieldType.TEXT, default = "text fallback")
        val structured = node(buildJsonObject { put(jsonField.key, buildJsonObject { put("kept", true) }) })
        assertEquals(textField.default, configValue(structured, textField))
    }

    private fun node(config: JsonObject): FlowNode =
        FlowNode("n1", spec, "Test", 0f, 0f, config)
}
