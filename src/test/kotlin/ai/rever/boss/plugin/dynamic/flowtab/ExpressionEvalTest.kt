package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
            put("slides", buildJsonArray {
                add(buildJsonObject { put("title", "First") })
            })
        }
        assertEquals("Z", ExpressionEval.interpolate("{{ \$json.user.name }}", json, emptyMap()))
        assertEquals("b", ExpressionEval.interpolate("{{ \$json.items[1] }}", json, emptyMap()))
        assertEquals("First", ExpressionEval.interpolate("{{ \$json.slides.0.title }}", json, emptyMap()))
    }

    @Test
    fun `resolves a node reference`() {
        val outputs = mapOf("Extract" to listOf(Item(buildJsonObject { put("v", "42") })))
        assertEquals("42", ExpressionEval.interpolate("{{ \$node[\"Extract\"].json.v }}", empty, outputs))
    }

    @Test
    fun `missing and unsupported paths fail with the expression named`() {
        val missing = assertFailsWith<TemplateResolutionException> {
            ExpressionEval.interpolate("{{ \$json.nope }}", empty, emptyMap())
        }
        val unsupported = assertFailsWith<TemplateResolutionException> {
            ExpressionEval.interpolate(
                "{{ \$json.issueTitle.length }}",
                buildJsonObject { put("issueTitle", "Bug") },
                emptyMap(),
            )
        }

        assertEquals("Unresolved template expression '{{ \$json.nope }}'", missing.message)
        assertEquals("Unresolved template expression '{{ \$json.issueTitle.length }}'", unsupported.message)
        assertEquals("just text", ExpressionEval.interpolate("just text", empty, emptyMap()))
    }

    @Test
    fun `explicit json null is resolved rather than treated as a missing path`() {
        val json = buildJsonObject { put("optional", JsonNull) }

        assertEquals("", ExpressionEval.interpolate("{{ \$json.optional }}", json, emptyMap()))
        assertEquals(
            JsonNull,
            ExpressionEval.interpolateJson(JsonPrimitive("{{ \$json.optional }}"), json, emptyMap()),
        )
    }

    @Test
    fun `malformed path syntax fails instead of being partially evaluated`() {
        val json = buildJsonObject { put("name", "Ada") }

        assertFailsWith<TemplateResolutionException> {
            ExpressionEval.interpolate("{{ \$json.name() }}", json, emptyMap())
        }
        assertFailsWith<TemplateResolutionException> {
            ExpressionEval.interpolate("{{ \$json[name] }}", json, emptyMap())
        }
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

    @Test
    fun `json interpolation keeps two expressions as one rendered string`() {
        val json = buildJsonObject { put("first", "Ada"); put("last", "Lovelace") }
        val template = buildJsonObject {
            put("name", "{{ \$json.first }} {{ \$json.last }}")
        }

        val result = ExpressionEval.interpolateJson(template, json, emptyMap()) as JsonObject

        assertEquals("\"Ada Lovelace\"", result["name"].toString())
    }

    @Test
    fun `json interpolation recurses through arrays and nested objects`() {
        val json = buildJsonObject { put("count", 3); put("name", "Ada") }
        val template = buildJsonObject {
            put("nested", buildJsonObject { put("count", "{{ \$json.count }}") })
            put("values", buildJsonArray { add("{{ \$json.name }}"); add("count={{ \$json.count }}") })
        }

        val result = ExpressionEval.interpolateJson(template, json, emptyMap()) as JsonObject

        assertEquals("3", (result["nested"] as JsonObject)["count"].toString())
        assertEquals("[\"Ada\",\"count=3\"]", (result["values"] as JsonArray).toString())
    }
}
