package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmProvider
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins where an agent's Anthropic credential comes from.
 *
 * The agent node speaks Anthropic's tool-use wire format only, so the shared AI provider
 * config is not simply "use whatever is active" — an active OpenAI provider is no use to it,
 * and picking its key up anyway would send an `sk-…` key to `api.anthropic.com`.
 */
class AgentCredentialResolutionTest {

    private fun cfg(
        id: String,
        format: LlmApiFormat,
        key: String = "key-$id",
        baseUrl: String = "https://$id.test/v1/messages",
    ) = LlmConfig(
        providerId = id,
        displayName = id,
        apiFormat = format,
        apiKey = key,
        baseUrl = baseUrl,
        modelId = "model-from-settings",
    )

    private fun provider(active: LlmConfig?, configured: List<LlmConfig> = emptyList()) =
        object : LlmProvider {
            override fun activeConfig(): LlmConfig? = active
            override fun configuredProviders(): List<LlmConfig> = configured
        }

    @Test
    fun `the active provider is used when it speaks Anthropic`() {
        val anthropic = cfg("ANTHROPIC", LlmApiFormat.ANTHROPIC_MESSAGES)
        val resolved = anthropicConfigFrom(provider(active = anthropic))
        assertEquals("key-ANTHROPIC", resolved?.apiKey)
        assertEquals("https://ANTHROPIC.test/v1/messages", resolved?.baseUrl)
    }

    /**
     * The case that matters: an OpenAI-format key must never be handed to the Anthropic
     * transport. Falling back to another *configured* Anthropic provider keeps agents working
     * for a user whose active provider is OpenAI.
     */
    @Test
    fun `a non-Anthropic active provider is skipped in favour of a configured Anthropic one`() {
        val openai = cfg("OPENAI", LlmApiFormat.OPENAI_CHAT, key = "sk-openai")
        val anthropic = cfg("ANTHROPIC", LlmApiFormat.ANTHROPIC_MESSAGES, key = "sk-ant")

        val resolved = anthropicConfigFrom(provider(active = openai, configured = listOf(openai, anthropic)))

        assertEquals("sk-ant", resolved?.apiKey)
    }

    @Test
    fun `no Anthropic provider anywhere resolves to null so the caller can fall back`() {
        val openai = cfg("OPENAI", LlmApiFormat.OPENAI_CHAT)
        val google = cfg("GOOGLE", LlmApiFormat.GOOGLE_GENERATIVE)
        assertNull(anthropicConfigFrom(provider(active = openai, configured = listOf(openai, google))))
    }

    @Test
    fun `a blank key is treated as unconfigured`() {
        val blank = cfg("ANTHROPIC", LlmApiFormat.ANTHROPIC_MESSAGES, key = "")
        assertNull(anthropicConfigFrom(provider(active = blank, configured = listOf(blank))))
    }

    @Test
    fun `a null provider resolves to null rather than throwing`() {
        assertNull(anthropicConfigFrom(null))
    }

    /**
     * `configuredProviders()` has a **default body returning an empty list**, so a provider
     * implementation that never overrides it is indistinguishable from one with nothing
     * configured. Empty must therefore mean "unknown, fall back", never "the user has nothing".
     */
    @Test
    fun `an implementation that does not override configuredProviders still resolves its active config`() {
        val anthropic = cfg("ANTHROPIC", LlmApiFormat.ANTHROPIC_MESSAGES)
        val bare = object : LlmProvider {
            override fun activeConfig(): LlmConfig = anthropic
            // configuredProviders() deliberately not overridden.
        }
        assertEquals("key-ANTHROPIC", anthropicConfigFrom(bare)?.apiKey)
        assertEquals(emptyList(), bare.configuredProviders())
    }

    /**
     * The whole contract on the wire: the **key and endpoint** come from the provider config,
     * the **model** from the node.
     *
     * Taking `LlmConfig.modelId` instead would silently change which model existing flows run
     * on, decided in a settings panel the flow author may never have opened — so this asserts
     * the node's model reaches the request even though the config names a different one.
     */
    @Test
    fun `key and endpoint come from the config, the model from the node`() {
        val cap = Capture()
        val srv = mockServer("""{"content":[{"type":"text","text":"ok"}],"usage":{}}""", cap)
        try {
            val anthropic = cfg(
                "ANTHROPIC",
                LlmApiFormat.ANTHROPIC_MESSAGES,
                key = "sk-from-settings",
                baseUrl = "http://127.0.0.1:${srv.address.port}/",
            )
            val built = anthropicProviderFor(
                llm = provider(active = anthropic),
                // Blank on purpose: if the config path were not taken, the provider would throw
                // "No Anthropic API key" rather than reach the server at all.
                fallbackKeys = SecretResolver.constant(null),
                model = "claude-from-node",
            )

            val turn = runBlocking { built.step("SYS", listOf(UserMsg("hi")), emptyList()) }

            assertEquals("ok", turn.text)
            assertEquals("sk-from-settings", cap.headers?.getFirst("x-api-key"))
            assertContains(cap.body, "\"model\":\"claude-from-node\"")
            assertFalse(
                cap.body.contains("model-from-settings"),
                "the settings-panel model must not override the node's Model field",
            )
        } finally {
            srv.stop(0)
        }
    }

    private class Capture {
        var headers: Headers? = null
        var body: String = ""
    }

    /** Ephemeral localhost server that captures the request and replies with [body]. */
    private fun mockServer(body: String, cap: Capture): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                cap.headers = ex.requestHeaders
                cap.body = ex.requestBody.readBytes().decodeToString()
                val bytes = body.encodeToByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
}
