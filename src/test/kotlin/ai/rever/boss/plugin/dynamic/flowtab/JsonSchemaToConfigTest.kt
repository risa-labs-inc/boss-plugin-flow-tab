package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tool-node bridge renders a host tool's JSON-Schema `inputSchema` into config
 * fields. These pin the mapping the inspector + arg-marshaller both depend on:
 * flat scalars → typed fields, enum → SELECT, nested/array/unknown → a JSON blob,
 * and a loose/absent schema → a single raw-JSON fallback (red-team F3).
 */
class JsonSchemaToConfigTest {

    @Test
    fun `flat scalar props map to typed fields`() {
        val schema = """
            {"type":"object","properties":{
              "q":{"type":"string"},
              "count":{"type":"integer"},
              "ratio":{"type":"number"},
              "flag":{"type":"boolean"}
            }}
        """.trimIndent()
        val fields = JsonSchemaToConfig.convert(schema).associateBy { it.key }
        assertEquals(FieldType.TEXT, fields["q"]!!.type)
        assertEquals(FieldType.NUMBER, fields["count"]!!.type)
        assertEquals(FieldType.NUMBER, fields["ratio"]!!.type)
        assertEquals(FieldType.BOOL, fields["flag"]!!.type)
    }

    @Test
    fun `enum maps to a SELECT with its options`() {
        val schema = """{"type":"object","properties":{"mode":{"type":"string","enum":["fast","slow"]}}}"""
        val f = JsonSchemaToConfig.convert(schema).single()
        assertEquals("mode", f.key)
        assertEquals(FieldType.SELECT, f.type)
        assertEquals(listOf("fast", "slow"), f.options)
    }

    @Test
    fun `nested object and array props become JSON fields`() {
        val schema = """
            {"type":"object","properties":{
              "filter":{"type":"object","properties":{"a":{"type":"string"}}},
              "tags":{"type":"array","items":{"type":"string"}}
            }}
        """.trimIndent()
        val fields = JsonSchemaToConfig.convert(schema).associateBy { it.key }
        assertEquals(FieldType.JSON, fields["filter"]!!.type)
        assertEquals(FieldType.JSON, fields["tags"]!!.type)
    }

    @Test
    fun `unknown-typed prop falls back to a JSON field`() {
        val schema = """{"type":"object","properties":{"weird":{"oneOf":[{"type":"string"}]}}}"""
        assertEquals(FieldType.JSON, JsonSchemaToConfig.convert(schema).single().type)
    }

    @Test
    fun `title and default are carried onto the field`() {
        val schema = """{"type":"object","properties":{"q":{"type":"string","title":"Query","default":"hi"}}}"""
        val f = JsonSchemaToConfig.convert(schema).single()
        assertEquals("Query", f.label)
        assertEquals("hi", f.default)
    }

    @Test
    fun `a loose or invalid schema yields one raw-JSON fallback field`() {
        for (bad in listOf("", "not json", "[1,2,3]", """{"type":"string"}""")) {
            val fields = JsonSchemaToConfig.convert(bad)
            assertEquals(1, fields.size, "for <$bad>")
            assertEquals(JsonSchemaToConfig.RAW_ARGS_KEY, fields.single().key, "for <$bad>")
            assertEquals(FieldType.JSON, fields.single().type, "for <$bad>")
        }
    }

    @Test
    fun `a no-arg schema yields no fields`() {
        val fields = JsonSchemaToConfig.convert("""{"type":"object","properties":{}}""")
        assertTrue(fields.isEmpty())
    }
}
