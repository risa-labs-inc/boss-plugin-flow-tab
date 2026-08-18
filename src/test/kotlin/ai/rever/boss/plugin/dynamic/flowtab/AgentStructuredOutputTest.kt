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
    }

    private fun obj(source: String): JsonObject = json.parseToJsonElement(source) as JsonObject
}
