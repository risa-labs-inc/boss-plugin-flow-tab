package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The config model gains [FieldType.JSON] + [FieldType.NUMBER] (P1 needs nested/array
 * tool inputs and numeric fields). [ConfigReader] must read them without changing the
 * existing scalar (TEXT/TEXTAREA/SELECT/BOOL) behavior.
 */
class ConfigReaderTest {

    private fun reader(config: JsonObject, item: JsonObject = JsonObject(emptyMap())) =
        ConfigReader(config, Item(item), emptyMap())

    @Test
    fun `new field types exist alongside the originals`() {
        // The four originals must still be present (behavior-preserving).
        val names = FieldType.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("TEXT", "TEXTAREA", "SELECT", "BOOL", "JSON", "NUMBER")))
    }

    @Test
    fun `NUMBER field reads as int and double`() {
        val r = reader(buildJsonObject { put("count", "42"); put("ratio", "1.5"); put("bad", "abc") })
        assertEquals(42, r.int("count"))
        assertEquals(1.5, r.double("ratio"))
        assertEquals(7, r.int("missing", default = 7)) // absent → default
        assertEquals(0, r.int("bad")) // non-numeric → default
    }

    @Test
    fun `NUMBER field still readable as a string, unchanged scalar behavior`() {
        val r = reader(buildJsonObject { put("count", "42") })
        assertEquals("42", r.str("count"))
        assertEquals("", r.str("nope"))
        assertEquals(false, r.bool("count"))
    }

    @Test
    fun `JSON field with a nested object is read as a raw element and as JSON text`() {
        val config = buildJsonObject {
            putJsonObject("payload") { put("a", 1); put("b", "x") }
        }
        val r = reader(config)
        val el = r.element("payload")
        assertTrue(el is JsonObject)
        assertEquals("1", (el as JsonObject)["a"]?.jsonPrimitive?.content)
        // jsonText serializes the nested structure back to a JSON string.
        val text = r.jsonText("payload")
        assertTrue(text.contains("\"a\"") && text.contains("\"b\""))
        assertNull(r.element("absent"))
    }

    @Test
    fun `JSON field stored as a string blob is returned unquoted with interpolation`() {
        // A JSON blob typed into a text/JSON field is stored as a JSON string; jsonText
        // returns its content (unquoted) and resolves {{ }} against the current item.
        val config = buildJsonObject { put("body", """{"q":"{{ ${'$'}json.term }}"}""") }
        val item = buildJsonObject { put("term", "cats") }
        val text = reader(config, item).jsonText("body")
        assertEquals("""{"q":"cats"}""", text)
    }
}
