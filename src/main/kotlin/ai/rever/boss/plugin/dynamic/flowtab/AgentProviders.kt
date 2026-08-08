package ai.rever.boss.plugin.dynamic.flowtab

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
