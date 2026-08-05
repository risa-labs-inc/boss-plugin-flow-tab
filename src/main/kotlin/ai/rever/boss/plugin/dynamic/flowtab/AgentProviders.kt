package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scriptable [AgentProvider] for tests and offline demos — no network. Construct it
 * with a `(step, messages, tools) -> AssistantTurn` function for dynamic behavior, or
 * via [scripted] to replay a fixed list of turns in order (running off the end yields a
 * final "no more scripted turns" text so a misbudgeted test terminates instead of hangs).
 */
class FakeProvider(
    private val fn: suspend (step: Int, system: String, messages: List<AgentMessage>, tools: List<ToolDescriptor>) -> AssistantTurn,
) : AgentProvider {
    private val step = AtomicInteger(0)

    override suspend fun step(system: String, messages: List<AgentMessage>, tools: List<ToolDescriptor>): AssistantTurn =
        fn(step.getAndIncrement(), system, messages, tools)

    companion object {
        /** Replay [turns] in order; extra steps return a terminal text. */
        fun scripted(vararg turns: AssistantTurn): FakeProvider {
            val list = turns.toList()
            return FakeProvider { i, _, _, _ -> list.getOrElse(i) { AssistantTurn(text = "(no more scripted turns)") } }
        }
    }
}

/**
 * Resolves an API key by logical name. Kept as a seam so the runtime never touches the
 * host directly and tests can inject a constant.
 *
 * Production agents do not build one of these directly — [anthropicProviderFor] takes the key
 * from the shared AI provider config the user set up in Settings → AI Providers, which is
 * where every BOSS plugin's AI keys now live. [fromSecrets] remains as its last resort, for a
 * user who stored an `ANTHROPIC_API_KEY` secret by hand and never opened that panel.
 */
fun interface SecretResolver {
    suspend fun get(name: String): String?

    companion object {
        fun constant(value: String?): SecretResolver = SecretResolver { value }

        /** Look a key up in the host secret store, matching name against website/username. */
        fun fromSecrets(context: ai.rever.boss.plugin.api.PluginContext): SecretResolver = SecretResolver { name ->
            val provider = context.secretDataProvider ?: return@SecretResolver null
            runCatching {
                val page = provider.searchSecrets(name, 0, 20).getOrNull() ?: return@runCatching null
                page.data.firstOrNull { it.website.equals(name, true) || it.username.equals(name, true) }?.password
            }.getOrNull()
        }
    }
}

/**
 * The Anthropic credential + endpoint to run an agent with, resolved from the shared AI
 * provider config the secret-manager plugin owns.
 *
 * The agent node speaks Anthropic's tool-use format only, so an OpenAI-format provider is no
 * use to it even when that is what the user has *active* — hence the two-step search: the
 * active provider first, then any other configured Anthropic one.
 *
 * Returns null when nothing suitable is configured, which is not the same as "the user has
 * nothing": `configuredProviders()` has a default body returning an empty list, so an
 * implementation that does not override it is indistinguishable from an unconfigured one.
 * That is why the caller falls back to the secret store rather than failing here.
 */
internal fun anthropicConfigFrom(llm: LlmProvider?): LlmConfig? {
    if (llm == null) return null
    fun usable(c: LlmConfig?) =
        c?.takeIf { it.apiFormat == LlmApiFormat.ANTHROPIC_MESSAGES && it.apiKey.isNotBlank() }

    return usable(llm.activeConfig())
        ?: llm.configuredProviders().firstNotNullOfOrNull { usable(it) }
}

/**
 * Build the agent's provider: key and endpoint from the shared AI provider config when one is
 * configured, else the old secret-store lookup.
 *
 * The **model stays the node's** ([AgentSettings.model], a visible per-node config field with
 * its own default). Taking `LlmConfig.modelId` instead would silently change which model
 * existing flows run on, decided in a settings panel the flow author may never have opened.
 *
 * `LlmConfig.maxTokens` is likewise ignored: it is a chat-completion default (2000) chosen for
 * one-shot replies, while a bounded tool-use loop needs the 4096 headroom
 * [AnthropicProvider] defaults to. The runtime's own [AgentBudget] is what bounds a run.
 */
internal fun anthropicProviderFor(
    llm: LlmProvider?,
    fallbackKeys: SecretResolver,
    model: String,
): AgentProvider {
    val cfg = anthropicConfigFrom(llm) ?: return AnthropicProvider(fallbackKeys, model = model)
    return AnthropicProvider(
        keys = SecretResolver.constant(cfg.apiKey),
        model = model,
        endpoint = cfg.baseUrl,
    )
}

/**
 * Concrete [AgentProvider] over Anthropic's Messages API (`/v1/messages`), tool-use
 * format. The key is fetched lazily via [keys] (default secret name `ANTHROPIC_API_KEY`)
 * so it never lives in a graph. One [step] serializes the transcript + advertised tools,
 * POSTs, and parses the assistant turn (text blocks + `tool_use` blocks) back into an
 * [AssistantTurn]; usage is read from the response so the runtime's token budget applies.
 *
 * NOTE: this module has no Ktor dependency, so the transport is the JDK [HttpClient]
 * already used by the HTTP node — the provider seam is what matters, not the client.
 * Untested by unit tests (no network); tests use [FakeProvider].
 */
class AnthropicProvider(
    private val keys: SecretResolver,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = 4096,
    private val keyName: String = DEFAULT_KEY_NAME,
    private val endpoint: String = ENDPOINT,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : AgentProvider {

    override suspend fun step(system: String, messages: List<AgentMessage>, tools: List<ToolDescriptor>): AssistantTurn {
        val apiKey = keys.get(keyName)
            ?: throw ExecError(
                "No Anthropic API key — configure an Anthropic provider in " +
                    "Settings → AI Providers, or store a secret named '$keyName'."
            )

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system.isNotBlank()) put("system", system)
            if (tools.isNotEmpty()) put("tools", toolsJson(tools))
            put("messages", messagesJson(messages))
        }.toString()

        val resp = withContext(Dispatchers.IO) {
            val req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(120)) // backstop; AgentRuntime also bounds the call (S3)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString())
        }
        if (resp.statusCode() !in 200..299) throw ExecError("Anthropic ${resp.statusCode()}: ${resp.body()}")
        return parse(JSON.parseToJsonElement(resp.body()).jsonObject)
    }

    private fun toolsJson(tools: List<ToolDescriptor>): JsonArray = buildJsonArray {
        for (t in tools) addJsonObject {
            put("name", t.name)
            put("description", t.description)
            put("input_schema", runCatching { JSON.parseToJsonElement(t.inputSchema).jsonObject }
                .getOrElse { buildJsonObject { put("type", "object") } })
        }
    }

    private fun messagesJson(messages: List<AgentMessage>): JsonArray = buildJsonArray {
        for (m in messages) when (m) {
            is UserMsg -> addJsonObject {
                put("role", "user")
                put("content", m.text)
            }
            is AssistantMsg -> addJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    if (!m.text.isNullOrBlank()) addJsonObject { put("type", "text"); put("text", m.text) }
                    for (c in m.toolCalls) addJsonObject {
                        put("type", "tool_use")
                        put("id", c.id)
                        put("name", c.name)
                        put("input", runCatching { JSON.parseToJsonElement(c.argsJson).jsonObject }
                            .getOrElse { buildJsonObject { } })
                    }
                }
            }
            is ToolResultsMsg -> addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    for (o in m.outcomes) addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", o.id)
                        put("content", o.content)
                        if (o.isError) put("is_error", true)
                    }
                }
            }
        }
    }

    private fun parse(obj: JsonObject): AssistantTurn {
        val content = obj["content"]?.jsonArray ?: JsonArray(emptyList())
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (block in content) {
            val b = block.jsonObject
            when (b["type"]?.jsonPrimitive?.content) {
                "text" -> text.append(b["text"]?.jsonPrimitive?.content ?: "")
                "tool_use" -> calls.add(
                    ToolCall(
                        id = b["id"]?.jsonPrimitive?.content ?: "",
                        name = b["name"]?.jsonPrimitive?.content ?: "",
                        argsJson = (b["input"] as? JsonObject)?.toString() ?: "{}",
                    )
                )
            }
        }
        val usage = (obj["usage"] as? JsonObject)?.let {
            TokenUsage(
                input = runCatching { it["input_tokens"]!!.jsonPrimitive.int }.getOrDefault(0),
                output = runCatching { it["output_tokens"]!!.jsonPrimitive.int }.getOrDefault(0),
            )
        }
        return AssistantTurn(text = text.toString().ifBlank { null }, toolCalls = calls, usage = usage)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val DEFAULT_KEY_NAME = "ANTHROPIC_API_KEY"
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
