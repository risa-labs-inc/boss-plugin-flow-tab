package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
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
    fun `action metadata surfaces only a customized element wait`() {
        val normal = node(NodeType.CLICK, "selector" to "button")
        val slow = node(NodeType.CLICK, "selector" to "button", "waitMs" to "60000")

        assertEquals(listOf("css"), nodeMetaChips(normal))
        assertEquals(listOf("css", "60000ms wait"), nodeMetaChips(slow))
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
    fun `extract metadata makes optional fallback behavior visible`() {
        val optional = node(
            NodeType.EXTRACT,
            "selectorType" to "xpath",
            "mode" to "attr",
            "multiple" to "true",
            "optional" to "true",
        )

        assertEquals(listOf("xpath", "attr", "all matches", "optional"), nodeMetaChips(optional))
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

    @Test
    fun `await login card explains its marker and human wait`() {
        val login = node(
            NodeType.AWAIT_LOGIN,
            "selectorType" to "xpath",
            "selector" to "//button[@aria-label='Account']",
            "waitMs" to "300000",
        )

        assertEquals(
            "Waits for sign-in marker //button[@aria-label='Account']",
            nodeSummary(login),
        )
        assertEquals(listOf("xpath", "300000ms wait"), nodeMetaChips(login))
    }

    @Test
    fun `agent summary identifies structured mode for string and object schema config`() {
        fun agent(config: JsonObject) = FlowNode(
            id = "agent",
            spec = agentNodeSpec(
                prompts = null,
                providerFor = { FakeProvider.scripted(AssistantTurn(text = "unused")) },
                toolSourceFor = { object : ToolSource {
                    override suspend fun list() = emptyList<ToolDescriptor>()
                    override suspend fun invoke(name: String, argsJson: String) = ToolResult("unused", true)
                } },
            ),
            title = "Agent",
            x = 0f,
            y = 0f,
            config = config,
        )

        assertEquals(
            "Runs an AI agent with approved tools",
            nodeSummary(agent(buildJsonObject {})),
        )
        assertEquals(
            "Runs an AI agent and returns structured data",
            nodeSummary(agent(buildJsonObject { put(AgentNode.OUTPUT_SCHEMA_KEY, """{"type":"object"}""") })),
        )
        assertEquals(
            "Runs an AI agent and returns structured data",
            nodeSummary(
                agent(
                    buildJsonObject {
                        put(AgentNode.OUTPUT_SCHEMA_KEY, buildJsonObject { put("type", "object") })
                    },
                ),
            ),
        )
    }
}
