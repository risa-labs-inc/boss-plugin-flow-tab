package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpaRecorderImportTest {

    private val sample = """
        {
          "name": "demo",
          "description": "",
          "actions": [
            {"type":"navigate","selector":{"type":"none"},"value":"https://e.com"},
            {"type":"input","selector":{"type":"css","value":"#q"},"value":"hi"},
            {"type":"click","selector":{"type":"xpath","value":"//b"}},
            {"type":"screenshot","selector":{"type":"none"}},
            {"type":"select","selector":{"type":"css","value":"#s"},"value":"opt"}
          ]
        }
    """.trimIndent()

    @Test
    fun `maps a recording to a connected node chain`() {
        val result = RpaRecorderImport.convert(sample)
        // First navigate is folded into Open Browser; screenshot is skipped.
        assertEquals(
            listOf("OPEN_BROWSER", "TYPE", "CLICK", "INJECT"),
            result.steps.map { it.kind }
        )
        assertTrue("screenshot" in result.skipped)
    }

    @Test
    fun `open browser takes the first navigate url`() {
        val open = RpaRecorderImport.convert(sample).steps.first()
        assertEquals("OPEN_BROWSER", open.kind)
        assertEquals("https://e.com", (open.config["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `input maps to a Type node with selector + text`() {
        val type = RpaRecorderImport.convert(sample).steps.first { it.kind == "TYPE" }
        assertEquals("#q", (type.config["selector"] as? JsonPrimitive)?.content)
        assertEquals("hi", (type.config["text"] as? JsonPrimitive)?.content)
        assertEquals("css", (type.config["selectorType"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `select waits for its target and reports a missing element`() {
        val select = RpaRecorderImport.convert(sample).steps.first { it.title == "Select option" }

        assertEquals("#s", (select.config["waitFor"] as? JsonPrimitive)?.content)
        assertEquals("css", (select.config["waitForType"] as? JsonPrimitive)?.content)
        val script = (select.config["script"] as? JsonPrimitive)?.content.orEmpty()
        assertTrue("if(!el)return false" in script)
        assertTrue("return true" in script)
    }

    @Test
    fun `empty or actionless config yields just an open-browser step`() {
        val result = RpaRecorderImport.convert("""{"name":"x","actions":[]}""")
        assertEquals(listOf("OPEN_BROWSER"), result.steps.map { it.kind })
    }
}
