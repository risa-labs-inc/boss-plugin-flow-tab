package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentStructuredOutputTest {
    private val json = Json

    @Test
    fun `validates required properties types arrays and additional properties`() {
        val schema = AgentStructuredOutput.parse(
            """
            {
              "type":"object",
              "properties":{
                "selector":{"type":"string","minLength":1},
                "scores":{"type":"array","items":{"type":"integer"},"minItems":1}
              },
              "required":["selector","scores"],
              "additionalProperties":false
            }
            """.trimIndent(),
        )

        assertNull(schema.validate(obj("""{"selector":"#main","scores":[1,2]}""")))
        assertEquals("$.selector is required", schema.validate(obj("""{"scores":[1]}""")))
        assertEquals("$.scores[1] must be integer", schema.validate(obj("""{"selector":"#main","scores":[1,2.5]}""")))
        assertEquals("$.extra is not allowed", schema.validate(obj("""{"selector":"#main","scores":[1],"extra":true}""")))
    }

    @Test
    fun `supports enum const numeric bounds and schema composition`() {
        val schema = AgentStructuredOutput.parse(
            """
            {
              "type":"object",
              "properties":{
                "status":{"enum":["found","missing"]},
                "score":{"allOf":[{"type":"number","minimum":0},{"maximum":1}]},
                "exact":{"const":true}
              },
              "required":["status","score","exact"]
            }
            """.trimIndent(),
        )

        assertNull(schema.validate(obj("""{"status":"found","score":0.5,"exact":true}""")))
        assertContains(schema.validate(obj("""{"status":"other","score":0.5,"exact":true}"""))!!, "$.status")
        assertEquals("$.score must be at most 1.0", schema.validate(obj("""{"status":"found","score":2,"exact":true}""")))
        assertContains(schema.validate(obj("""{"status":"found","score":0.5,"exact":false}"""))!!, "$.exact")
    }

    @Test
    fun `rejects malformed root and unsupported validation keywords before model work`() {
        assertContains(
            assertFailsWith<ExecError> { AgentStructuredOutput.parse("not json") }.message!!,
            "must be valid JSON",
        )
        assertEquals(
            "Agent output schema (outputSchema) must be a JSON object",
            assertFailsWith<ExecError> { AgentStructuredOutput.parse("[]") }.message,
        )
        assertEquals(
            "Agent output schema (outputSchema) must describe an object",
            assertFailsWith<ExecError> { AgentStructuredOutput.parse("""{"type":"string"}""") }.message,
        )
        assertEquals(
            "Agent output schema (outputSchema) must describe an object",
            assertFailsWith<ExecError> {
                AgentStructuredOutput.parse("""{"properties":{"answer":{"type":"string"}}}""")
            }.message,
        )
        assertContains(
            assertFailsWith<ExecError> {
                AgentStructuredOutput.parse(
                    """{"type":"object","properties":{"x":{"${'$'}ref":"#/${'$'}defs/x"}}}""",
                )
            }.message!!,
            "unsupported keyword '${'$'}ref'",
        )
    }

    @Test
    fun `submission must be an object conforming to the schema`() {
        val schema = AgentStructuredOutput.parse(
            """{"type":"object","properties":{"answer":{"type":"boolean"}},"required":["answer"]}""",
        )

        assertEquals(obj("""{"answer":true}"""), AgentStructuredOutput.parseSubmission("""{"answer":true}""", schema).getOrThrow())
        assertEquals("the submission must be a JSON object", AgentStructuredOutput.parseSubmission("[]", schema).exceptionOrNull()?.message)
        assertEquals("$.answer must be boolean", AgentStructuredOutput.parseSubmission("""{"answer":"yes"}""", schema).exceptionOrNull()?.message)
        assertEquals("$.answer must be boolean", AgentStructuredOutput.parseSubmission("""{"answer":"true"}""", schema).exceptionOrNull()?.message)
    }

    @Test
    fun `schema primitives and numeric keywords do not accept quoted lookalikes`() {
        assertContains(
            assertFailsWith<ExecError> {
                AgentStructuredOutput.parse(
                    """{"type":"object","properties":{"answer":"true"}}""",
                )
            }.message!!,
            "$.answer must be an object or boolean",
        )
        assertContains(
            assertFailsWith<ExecError> {
                AgentStructuredOutput.parse(
                    """{"type":"object","properties":{"answer":{"type":"string","minLength":"2"}}}""",
                )
            }.message!!,
            "invalid 'minLength'",
        )
    }

    @Test
    fun `composed and type-specific constraints are enforced locally`() {
        val schema = AgentStructuredOutput.parse(
            """
            {
              "type":"object",
              "properties":{
                "choice":{"oneOf":[{"type":"string"},{"type":"integer"}]},
                "name":{"type":"string","pattern":"^[A-Z]+${'$'}"},
                "count":{"type":"number","multipleOf":2},
                "tags":{"type":"array","uniqueItems":true},
                "allowed":{"not":{"const":false}}
              },
              "required":["choice","name","count","tags","allowed"],
              "additionalProperties":{"type":"integer"},
              "minProperties":5,
              "if":{"properties":{"choice":{"const":"special"}}},
              "then":{"required":["specialCode"]}
            }
            """.trimIndent(),
        )

        assertNull(
            schema.validate(
                obj("""{"choice":2,"name":"ABC","count":4,"tags":["a","b"],"allowed":true,"extra":1}"""),
            ),
        )
        assertContains(schema.validate(obj("""{"choice":true,"name":"ABC","count":4,"tags":[],"allowed":true}"""))!!, "$.choice")
        assertContains(schema.validate(obj("""{"choice":2,"name":"abc","count":4,"tags":[],"allowed":true}"""))!!, "$.name")
        assertContains(schema.validate(obj("""{"choice":2,"name":"ABC","count":3,"tags":[],"allowed":true}"""))!!, "$.count")
        assertContains(schema.validate(obj("""{"choice":2,"name":"ABC","count":4,"tags":["a","a"],"allowed":true}"""))!!, "$.tags")
        assertContains(schema.validate(obj("""{"choice":2,"name":"ABC","count":4,"tags":[],"allowed":false}"""))!!, "$.allowed")
        assertEquals(
            "$.extra must be integer",
            schema.validate(obj("""{"choice":2,"name":"ABC","count":4,"tags":[],"allowed":true,"extra":"1"}""")),
        )
        assertEquals(
            "$.specialCode is required",
            schema.validate(obj("""{"choice":"special","name":"ABC","count":4,"tags":[],"allowed":true}""")),
        )
    }

    @Test
    fun `numeric equality applies to const enum and unique items`() {
        val schema = AgentStructuredOutput.parse(
            """
            {
              "type":"object",
              "properties":{
                "constant":{"const":1},
                "choice":{"enum":[1,2]},
                "values":{"type":"array","uniqueItems":true}
              },
              "required":["constant","choice","values"]
            }
            """.trimIndent(),
        )

        assertNull(schema.validate(obj("""{"constant":1.0,"choice":2.0,"values":[1,2.0]}""")))
        assertEquals(
            "$.values must contain unique items",
            schema.validate(obj("""{"constant":1.0,"choice":2.0,"values":[1,1.0]}""")),
        )
    }

    private fun obj(source: String): JsonObject = json.parseToJsonElement(source) as JsonObject
}
