package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerConfigUITest {
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
            workingDirectory = "  /projects/slack-mcp  ",
        ).toConfigOrNull()

        assertNotNull(config)
        assertEquals("slack", config.name)
        assertEquals("npx", config.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-slack"), config.args)
        assertEquals("/projects/slack-mcp", config.workingDirectory)
        assertNull(config.secretRef)
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
            workingDirectory = "/must-not-be-persisted-for-sse",
        ).toConfigOrNull()

        assertNotNull(config)
        assertEquals("https://mcp.example.test/sse", config.url)
        assertEquals("", config.command)
        assertTrue(config.args.isEmpty())
        assertEquals("", config.workingDirectory)
        assertNull(config.secretRef)
    }

    @Test
    fun `draft rejects names that break routing or contain controls`() {
        assertNull(
            McpServerDraft("team/server", McpTransportKind.STDIO, "npx", "", "", "")
                .toConfigOrNull(),
        )
        assertNull(
            McpServerDraft("forged\nserver", McpTransportKind.STDIO, "npx", "", "", "")
                .toConfigOrNull(),
        )
        assertNull(
            McpServerDraft("tab\tserver", McpTransportKind.HTTP_SSE, "", "", "https://example.test", "")
                .toConfigOrNull(),
        )
    }

    @Test
    fun `operation diagnostics are single-line and bounded`() {
        val diagnostic = boundedExternalMcpDiagnostic(
            "  first line\nsecond\u001b[31m\u0000line\u2028next\u2029paragraph  " + "x".repeat(500),
        )

        assertTrue(diagnostic.startsWith("first line second [31m line next paragraph"))
        assertTrue(diagnostic.none { it.isISOControl() || it == '\u2028' || it == '\u2029' })
        assertTrue(diagnostic.length <= ExternalMcpManager.MAX_STATUS_DETAIL_LENGTH)
    }
}
