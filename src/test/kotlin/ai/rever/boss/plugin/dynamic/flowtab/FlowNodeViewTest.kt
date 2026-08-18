package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FlowNodeViewTest {

    private val registry = builtinNodeRegistry()

    private fun node(type: NodeType, vararg config: Pair<String, String>): FlowNode = FlowNode(
        id = "n1",
        spec = registry[type.name]!!,
        title = type.label,
        x = 0f,
        y = 0f,
        config = buildJsonObject { config.forEach { put(it.first, it.second) } },
    )

    @Test
    fun `node numbering is one based and visible as a compact badge label`() {
        assertEquals("#1", nodeNumberLabel(1))
        assertEquals("#12", nodeNumberLabel(12))
    }

    @Test
    fun `summaries describe actions and configured targets`() {
        assertEquals("Starts this flow", nodeSummary(node(NodeType.TRIGGER)))
        assertEquals(
            "Clicks button.checkout",
            nodeSummary(node(NodeType.CLICK, "selector" to "button.checkout")),
        )
        assertEquals(
            "Extracts html from article",
            nodeSummary(node(NodeType.EXTRACT, "mode" to "html", "selector" to "article")),
        )
        assertEquals(
            "POST request to https://example.com/orders",
            nodeSummary(node(NodeType.HTTP, "method" to "POST", "url" to "https://example.com/orders")),
        )
    }

    @Test
    fun `type summary identifies its target without exposing its value`() {
        val secret = "literal-password-that-must-not-render"
        val type = node(NodeType.TYPE, "selector" to "#password", "text" to secret)

        assertEquals("Types into #password", nodeSummary(type))
        assertFalse(secret in nodeSummary(type))
        assertEquals(listOf("css", "fixed value"), nodeMetaChips(type))
    }

    @Test
    fun `metadata identifies dynamic and secret value sources without rendering values`() {
        val dynamic = node(NodeType.TYPE, "selectorType" to "xpath", "text" to "{{ \$json.name }}")
        val secret = node(NodeType.TYPE, "text" to "{{ \$secret.account_password }}")
        val authenticatedHttp = node(
            NodeType.HTTP,
            "method" to "post",
            "headers" to "{\"Authorization\":\"Bearer {{ \$secret.api_token }}\"}",
        )

        assertEquals(listOf("xpath", "dynamic value"), nodeMetaChips(dynamic))
        assertEquals(listOf("css", "secret value"), nodeMetaChips(secret))
        assertEquals(listOf("POST", "uses secret"), nodeMetaChips(authenticatedHttp))
    }

    @Test
    fun `inject summary and metadata make wait behavior visible`() {
        val immediate = node(NodeType.INJECT, "script" to "window.scrollTo(0, 0)")
        val waiting = node(
            NodeType.INJECT,
            "waitForType" to "xpath",
            "waitFor" to "//section[@data-ready]",
            "waitMs" to "5000",
            "script" to "window.didRun=true",
        )

        assertEquals("Runs custom JavaScript in the page", nodeSummary(immediate))
        assertEquals(listOf("runs immediately"), nodeMetaChips(immediate))
        assertEquals(
            "Waits for //section[@data-ready], then runs JavaScript",
            nodeSummary(waiting),
        )
        assertEquals(listOf("xpath", "5000ms wait"), nodeMetaChips(waiting))
    }
}
