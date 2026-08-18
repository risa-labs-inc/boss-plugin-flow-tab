package ai.rever.boss.plugin.dynamic.flowtab

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerConfigUITest {
    @Test
    fun `flow tab bytecode mounts the external MCP configuration panel`() {
        val componentClass = FlowTabComponent::class.java
        val classBytes = componentClass.getResourceAsStream("/${componentClass.name.replace('.', '/')}.class")
            ?.use { it.readBytes() }
        assertNotNull(classBytes)

        val constantPool = classBytes.toString(StandardCharsets.ISO_8859_1)
        assertTrue(
            constantPool.contains("McpServerConfigUIKt") && constantPool.contains("McpServerConfigPanel"),
            "FlowTabComponent must keep McpServerConfigPanel reachable from the Flow tab",
        )
    }

    @Test
    fun `stdio draft requires command and normalizes persisted config`() {
        assertNull(
            McpServerDraft("slack", McpTransportKind.STDIO, "", "-y server", "", "TOKEN")
                .toConfigOrNull(),
        )

        val config = McpServerDraft(
            name = "  slack  ",
            kind = McpTransportKind.STDIO,
            command = "  npx ",
            args = "  -y   @modelcontextprotocol/server-slack  ",
            url = "",
            secretRef = "  SLACK_TOKEN  ",
        ).toConfigOrNull()

        assertNotNull(config)
        assertEquals("slack", config.name)
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-slack"), config.args)
        assertEquals("SLACK_TOKEN", config.secretRef)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `http draft requires URL and never treats secret reference as a value`() {
        assertNull(
            McpServerDraft("linear", McpTransportKind.HTTP_SSE, "", "", "", "LINEAR_TOKEN")
                .toConfigOrNull(),
        )

        val config = McpServerDraft(
            name = "linear",
            kind = McpTransportKind.HTTP_SSE,
            command = "ignored",
            args = "ignored args",
            url = "  https://mcp.example.test/sse  ",
            secretRef = "   ",
        ).toConfigOrNull()

        assertNotNull(config)
        assertEquals("https://mcp.example.test/sse", config.url)
        assertEquals("", config.command)
        assertTrue(config.args.isEmpty())
        assertNull(config.secretRef)
    }
}
