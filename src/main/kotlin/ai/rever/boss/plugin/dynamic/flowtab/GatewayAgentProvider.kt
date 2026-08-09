package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiMessage
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiRound
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import ai.rever.boss.plugin.api.AiTurn

/**
 * An [AgentProvider] backed by the shared AI Gateway plugin.
 *
 * Replaces `AnthropicProvider`, which spoke one provider's tool-use format directly. Two
 * things change for the user, and the first is the point:
 *
 * - **the agent node works with every configured provider**, not only Anthropic. The old
 *   path searched for an Anthropic provider and fell back to a secret-store lookup, so a
 *   user whose active provider was OpenAI got an agent that either used a stale key or did
 *   not run;
 * - there is no Anthropic-specific credential resolution here at all. The gateway resolves
 *   whatever `Settings -> AI Providers` has active.
 *
 * The runtime's [AgentProvider] seam is unchanged, so [FakeProvider] and every runtime test
 * still work exactly as before. This class only translates between the runtime's transcript
 * types and the gateway's.
 *
 * The **model stays the node's** ([AgentSettings.model], a visible per-node config field).
 * The gateway uses whatever model the active provider has selected, so a node that names one
 * explicitly is documented as advisory rather than silently overriding a user's choice - see
 * [modelNote].
 */
internal class GatewayAgentProvider(
    private val gateway: () -> AiGatewayAPI?,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /**
     * Explains why AI is unavailable and offers the fix. Fire-and-forget: the node still
     * fails, because a DAG step cannot wait on a dialog and a run that silently paused
     * would be worse than one that failed with a reason.
     */
    private val promptAiFix: suspend (feature: String) -> Unit = {},
) : AgentProvider {

    /**
     * Every completed tool round this run, oldest first.
     *
     * A provider will not accept a tool result on its own - Anthropic rejects a `tool_result`
     * whose `tool_use` was not replayed, the Responses API needs the `function_call` item
     * beside its output - and the runtime hands back a [ToolResultsMsg] with no assistant turn
     * attached, so the turns are remembered here. They cannot be rebuilt from the transcript,
     * where the call ids are no longer attached.
     *
     * **All of them, not just the last.** Keeping one slot meant that from step 3 onwards the
     * model never saw what its first round's tools returned, so it re-called them or answered
     * without the evidence; and the resulting transcript had adjacent assistant turns, which
     * Anthropic and Google reject outright.
     *
     * One agent run is sequential by construction (the runtime awaits each step), so a plain
     * list is enough and there is no interleaving to guard against. A fresh provider per run
     * keeps one run's history out of the next.
     */
    private val rounds = mutableListOf<AiRound>()

    /** The turn awaiting its outcomes, i.e. the one the runtime is executing tools for. */
    private var pendingTurn: AiTurn? = null

    override suspend fun step(
        system: String,
        messages: List<AgentMessage>,
        tools: List<ToolDescriptor>,
    ): AssistantTurn {
        val api =
            gateway() ?: run {
                // Tell the user what is missing and offer the fix, then fail the node. The
                // message alone was a dead end: it named the gateway but led nowhere.
                promptAiFix("This agent node")
                error(NO_GATEWAY_MESSAGE)
            }

        // Close the previous round: the runtime has just executed the tools the last turn
        // asked for, and this is where its results arrive.
        val outcomes =
            (messages.lastOrNull() as? ToolResultsMsg)?.outcomes?.map { outcome ->
                AiToolOutcome(id = outcome.id, content = outcome.content, isError = outcome.isError)
            } ?: emptyList()
        if (outcomes.isNotEmpty()) {
            pendingTurn?.let { rounds += AiRound(turn = it, outcomes = outcomes) }
            pendingTurn = null
        }

        val turn =
            api
                .step(
                    request =
                        AiRequest(
                            system = system,
                            // Tool rounds travel structurally in `rounds`, not as transcript
                            // text, so they are dropped here rather than sent twice.
                            messages = messages.mapNotNull(::toAiMessage),
                            maxTokens = maxTokens,
                        ),
                    tools =
                        tools.map { descriptor ->
                            AiToolSpec(
                                name = descriptor.name,
                                description = descriptor.description,
                                inputSchema = descriptor.inputSchema.ifBlank { "{}" },
                            )
                        },
                    rounds = rounds.toList(),
                ).getOrElse { error ->
                    // Thrown, not swallowed: the runtime's DAG uses exceptions for node
                    // failure, and a turn reporting empty text would look like a model that
                    // answered with nothing.
                    throw IllegalStateException(error.message ?: "The AI request failed.", error)
                }

        pendingTurn = turn.takeIf { it.toolCalls.isNotEmpty() }
        return AssistantTurn(
            text = turn.text.takeIf { it.isNotBlank() },
            toolCalls = turn.toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) },
            usage = turn.usage?.let { TokenUsage(input = it.inputTokens, output = it.outputTokens) },
        )
    }

    /**
     * The runtime's transcript as gateway messages.
     *
     * [ToolResultsMsg] is deliberately skipped: it travels in [rounds] instead, where its
     * call ids survive. [AssistantMsg] keeps only its text for the same reason - its tool
     * calls are replayed from [rounds], which still has them attached to their ids.
     */
    private fun toAiMessage(message: AgentMessage): AiMessage? =
        when (message) {
            is UserMsg -> AiMessage.user(message.text)
            is AssistantMsg -> message.text?.takeIf { it.isNotBlank() }?.let { AiMessage.assistant(it) }
            is ToolResultsMsg -> null
        }

    companion object {
        const val NO_GATEWAY_MESSAGE =
            "AI is unavailable: install the AI Gateway plugin to run agent nodes."

        /**
         * A tool-use loop needs more headroom than a one-shot reply. The provider's own
         * configured `maxTokens` default is chosen for chat completions, so it is overridden
         * per request rather than per provider.
         */
        const val DEFAULT_MAX_TOKENS = 4096

    }
}
