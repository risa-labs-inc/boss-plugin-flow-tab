package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.Serializable

/**
 * One tool the model asked to run this turn. [id] correlates the call with its result
 * so a provider can thread multi-tool turns; [argsJson] is the raw JSON argument string
 * handed straight to [ToolSource.invoke].
 */
data class ToolCall(val id: String, val name: String, val argsJson: String)

/** The outcome of one [ToolCall], fed back to the model as DATA (never as instructions). */
data class ToolOutcome(val id: String, val name: String, val content: String, val isError: Boolean)

/** Token accounting a provider may report per turn; summed to enforce the budget. */
@Serializable
data class TokenUsage(val input: Int = 0, val output: Int = 0) {
    val total: Int get() = input + output
    operator fun plus(o: TokenUsage) = TokenUsage(input + o.input, output + o.output)
}

/**
 * One assistant turn produced by an [AgentProvider]: free [text] and/or a set of
 * [toolCalls]. An empty [toolCalls] with [text] is a final answer; a non-empty
 * [toolCalls] means "run these, then step me again with the results."
 */
data class AssistantTurn(
    val text: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: TokenUsage? = null,
)

/**
 * A message in the running conversation. The runtime keeps the transcript as a list of
 * these and replays it to the provider each step. Tool output is a dedicated
 * [ToolResultsMsg] — kept structurally distinct from user/assistant text so a provider
 * can render it as observation data, not as a fresh instruction (prompt-injection guard).
 */
sealed interface AgentMessage
data class UserMsg(val text: String) : AgentMessage
data class AssistantMsg(val text: String?, val toolCalls: List<ToolCall>) : AgentMessage
data class ToolResultsMsg(val outcomes: List<ToolOutcome>) : AgentMessage

/**
 * Provider-agnostic seam. One [step] takes the system prompt, the full transcript, and
 * the advertised [tools], and returns the next [AssistantTurn]. Concrete impls talk to
 * a real model ([AnthropicProvider]); [FakeProvider] scripts turns for tests. Keeping
 * this a `fun interface` lets a test pass a lambda.
 */
fun interface AgentProvider {
    suspend fun step(system: String, messages: List<AgentMessage>, tools: List<ToolDescriptor>): AssistantTurn
}

/** Bounds on a single agent run. All three are enforced; whichever trips first wins. */
data class AgentBudget(
    val maxSteps: Int = 8,
    val timeoutMs: Long = 120_000,
    val maxTokens: Int = Int.MAX_VALUE,
)

/** Why the loop stopped. [COMPLETED] = the model returned a final text with no tool calls. */
enum class StopReason { COMPLETED, MAX_STEPS, TIMEOUT, TOKEN_BUDGET }

/** The outcome of an agent run: its final [finalText], why it stopped, and run counters. */
data class AgentResult(
    val finalText: String,
    val stopReason: StopReason,
    val steps: Int,
    val toolCalls: Int,
    val usage: TokenUsage = TokenUsage(),
)

/**
 * A bounded, provider-agnostic tool-loop. Given a [provider] and a [source] of tools,
 * it drives: system prompt + transcript → model → execute the model's tool calls via
 * [source] → feed results back → repeat until the model answers with no tool calls or a
 * [budget] bound trips.
 *
 * Safety properties (plan §08 risks):
 *  - **Least privilege / injection:** only tools named in `allowlist` are advertised to
 *    the model AND callable; a call to anything else never reaches [source] — it comes
 *    back as an error outcome the model must cope with.
 *  - **Tool output is data:** results are appended as [ToolResultsMsg]; the loop never
 *    interprets returned text as new instructions.
 *  - **Bounded:** the loop stops cleanly at [AgentBudget.maxSteps], [AgentBudget.timeoutMs],
 *    or [AgentBudget.maxTokens], reporting the [StopReason] rather than running away.
 */
class AgentRuntime(
    private val provider: AgentProvider,
    private val source: ToolSource,
    private val budget: AgentBudget = AgentBudget(),
) {
    suspend fun run(
        system: String,
        input: String,
        allowlist: Set<String>,
        log: (String) -> Unit = {},
    ): AgentResult {
        val started = System.currentTimeMillis()
        val allowed = source.list().filter { it.ref.name in allowlist }
        val allowedNames = allowed.map { it.ref.name }.toSet()

        val messages = mutableListOf<AgentMessage>(UserMsg(input))
        var steps = 0
        var toolCalls = 0
        var usage = TokenUsage()
        var lastText = ""

        while (true) {
            if (steps >= budget.maxSteps) return done(lastText, StopReason.MAX_STEPS, steps, toolCalls, usage)
            if (System.currentTimeMillis() - started >= budget.timeoutMs)
                return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage)

            val turn = provider.step(system, messages.toList(), allowed)
            steps++
            turn.usage?.let { usage += it }
            turn.text?.let { lastText = it }
            messages.add(AssistantMsg(turn.text, turn.toolCalls))

            if (turn.toolCalls.isEmpty()) {
                return done(lastText, StopReason.COMPLETED, steps, toolCalls, usage)
            }

            val outcomes = turn.toolCalls.map { c ->
                toolCalls++
                if (c.name !in allowedNames) {
                    log("blocked tool '${c.name}' (not in allowlist)")
                    ToolOutcome(c.id, c.name, "tool '${c.name}' is not in this agent's allowlist", isError = true)
                } else {
                    log("→ ${c.name} ${c.argsJson}")
                    val r = runCatching { source.invoke(c.name, c.argsJson) }
                        .getOrElse { ToolResult(it.message ?: it.toString(), isError = true) }
                    ToolOutcome(c.id, c.name, r.text, r.isError)
                }
            }
            messages.add(ToolResultsMsg(outcomes))

            if (usage.total >= budget.maxTokens)
                return done(lastText, StopReason.TOKEN_BUDGET, steps, toolCalls, usage)
        }
    }

    private fun done(text: String, reason: StopReason, steps: Int, toolCalls: Int, usage: TokenUsage) =
        AgentResult(text, reason, steps, toolCalls, usage)
}
