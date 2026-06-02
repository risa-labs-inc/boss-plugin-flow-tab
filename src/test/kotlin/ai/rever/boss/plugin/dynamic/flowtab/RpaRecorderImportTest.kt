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
            listOf(NodeType.OPEN_BROWSER, NodeType.TYPE, NodeType.CLICK, NodeType.INJECT),
            result.steps.map { it.type }
        )
        assertTrue("screenshot" in result.skipped)
    }

    @Test
    fun `open browser takes the first navigate url`() {
        val open = RpaRecorderImport.convert(sample).steps.first()
        assertEquals(NodeType.OPEN_BROWSER, open.type)
        assertEquals("https://e.com", (open.config["url"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `input maps to a Type node with selector + text`() {
        val type = RpaRecorderImport.convert(sample).steps.first { it.type == NodeType.TYPE }
        assertEquals("#q", (type.config["selector"] as? JsonPrimitive)?.content)
        assertEquals("hi", (type.config["text"] as? JsonPrimitive)?.content)
        assertEquals("css", (type.config["selectorType"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `empty or actionless config yields just an open-browser step`() {
        val result = RpaRecorderImport.convert("""{"name":"x","actions":[]}""")
        assertEquals(listOf(NodeType.OPEN_BROWSER), result.steps.map { it.type })
    }
}
