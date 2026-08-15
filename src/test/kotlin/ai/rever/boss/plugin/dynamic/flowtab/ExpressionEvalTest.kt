package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionEvalTest {

    private val empty = JsonObject(emptyMap())

    @Test
    fun `resolves a simple json field`() {
        val json = buildJsonObject { put("name", "Bob") }
        assertEquals("Bob", ExpressionEval.interpolate("{{ \$json.name }}", json, emptyMap()))
    }

    @Test
    fun `interpolates within surrounding text and multiple expressions`() {
        val json = buildJsonObject { put("first", "Ada"); put("last", "Lovelace") }
        assertEquals(
            "Hi Ada Lovelace!",
            ExpressionEval.interpolate("Hi {{ \$json.first }} {{ \$json.last }}!", json, emptyMap())
        )
    }

    @Test
    fun `nested path and array index`() {
        val json = buildJsonObject {
            put("user", buildJsonObject { put("name", "Z") })
            put("items", buildJsonArray { add("a"); add("b") })
        }
        assertEquals("Z", ExpressionEval.interpolate("{{ \$json.user.name }}", json, emptyMap()))
        assertEquals("b", ExpressionEval.interpolate("{{ \$json.items[1] }}", json, emptyMap()))
    }

    @Test
    fun `resolves a node reference`() {
        val outputs = mapOf("Extract" to listOf(Item(buildJsonObject { put("v", "42") })))
        assertEquals("42", ExpressionEval.interpolate("{{ \$node[\"Extract\"].json.v }}", empty, outputs))
    }

    @Test
    fun `missing path renders empty, plain text passes through`() {
        assertEquals("", ExpressionEval.interpolate("{{ \$json.nope }}", empty, emptyMap()))
        assertEquals("just text", ExpressionEval.interpolate("just text", empty, emptyMap()))
    }

    @Test
    fun `json interpolation preserves whole-expression types`() {
        val json = buildJsonObject {
            put("count", 3)
            put("profile", buildJsonObject { put("name", "Ada") })
        }
        val template = buildJsonObject {
            put("count", "{{ \$json.count }}")
            put("profile", "{{ \$json.profile }}")
            put("label", "count={{ \$json.count }}")
        }
        val result = ExpressionEval.interpolateJson(template, json, emptyMap()) as JsonObject

        assertEquals("3", result["count"].toString())
        assertEquals("{\"name\":\"Ada\"}", result["profile"].toString())
        assertEquals("\"count=3\"", result["label"].toString())
    }
}
