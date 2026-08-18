package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicReference

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
 * a real model ([GatewayAgentProvider]); [FakeProvider] scripts turns for tests. Keeping
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
 * A provider/runtime failure with the last safely published progress counters.
 * The message adds only counters to the existing boundary error; the runtime never
 * appends prompts, model output, tool arguments, or tool-result content.
 */
class AgentRunFailure(
    val steps: Int,
    val toolCalls: Int,
    cause: Throwable,
) : Exception(
    "Agent stopped: FAILED after $steps completed step(s), $toolCalls tool call(s): " +
        (cause.message ?: cause::class.simpleName ?: "unknown error"),
    cause,
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
 *    [AgentNodeExecutor] escalates [StopReason.TIMEOUT] to a node error.
 */
class AgentRuntime(
    private val provider: AgentProvider,
    private val source: ToolSource,
    private val budget: AgentBudget = AgentBudget(),
    private val executionDispatcher: CoroutineDispatcher = agentExecutionDispatcher,
) {
    private data class Progress(
        val lastText: String = "",
        val steps: Int = 0,
        val toolCalls: Int = 0,
        val usage: TokenUsage = TokenUsage(),
    )

    /**
     * Serializes the worker's writes with closing the gate at the timeout boundary.
     * [sink] must stay non-blocking because timeout closure waits for an in-flight write.
     */
    private class LogGate(private val sink: (String) -> Unit) {
        private val lock = Any()
        private var accepting = true

        fun write(message: String) = synchronized(lock) {
            if (accepting) sink(message)
        }

        fun close() = synchronized(lock) {
            accepting = false
        }
    }

    /**
     * [log] is invoked from the bounded agent execution lane. Calls are serialized, and
     * the gate is closed before [run] returns so a late host response cannot write afterward.
     */
    suspend fun run(
        system: String,
        input: String,
        allowlist: Set<String>,
        log: (String) -> Unit = {},
    ): AgentResult {
        val timeoutMs = budget.timeoutMs.coerceAtLeast(0)
        if (timeoutMs == 0L) {
            return done("", StopReason.TIMEOUT, steps = 0, toolCalls = 0, usage = TokenUsage()).also {
                log(stopLog(it))
            }
        }
        // Provider and tool integrations are host/plugin boundaries and may block without
        // cooperating with coroutine cancellation. Keep their loop in an independently
        // owned scope so the caller can publish TIMEOUT at the configured wall-clock
        // deadline instead of waiting for a late host call to return. Cancellation remains
        // best-effort; a late result has no path back to the already-returned AgentResult.
        val executionScope = CoroutineScope(SupervisorJob() + executionDispatcher)
        val progress = AtomicReference(Progress())
        val successfulCompletion = AtomicReference<AgentResult?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val logGate = LogGate(log)
        val loopStarted = CompletableDeferred<Unit>()
        val execution = executionScope.async {
            loopStarted.complete(Unit)
            runLoop(system, input, allowlist, progress, logGate::write)
                .also { successfulCompletion.set(it) }
        }
        execution.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) failure.compareAndSet(null, cause)
        }

        // A bounded lane prevents non-cooperative host calls from consuming the host's
        // entire IO pool. Queue time is not agent execution time: wait briefly for a lane,
        // then fail explicitly as capacity exhaustion instead of reporting a bogus 0-step
        // provider timeout. Cancellation removes a queued coroutine before it can run.
        val admissionTimeoutMs = timeoutMs.coerceAtMost(ADMISSION_TIMEOUT_MS)
        val admitted = try {
            withTimeoutOrNull(admissionTimeoutMs) {
                loopStarted.await()
                true
            } ?: false
        } catch (cancelled: CancellationException) {
            logGate.close()
            executionScope.cancel(CancellationException("Agent run cancelled before execution"))
            throw cancelled
        }
        if (!admitted) {
            val cause = ExecError("Agent execution capacity is busy; retry after other Agent runs finish")
            val wrapped = AgentRunFailure(steps = 0, toolCalls = 0, cause = cause)
            logGate.write(failureLog(wrapped))
            logGate.close()
            executionScope.cancel(CancellationException("Agent execution lane unavailable"))
            throw wrapped
        }

        val graceMs = maxOf(MIN_HARD_TIMEOUT_GRACE_MS, timeoutMs / 20)
        val hardTimeoutMs = timeoutMs.saturatingPlus(graceMs)
        try {
            // Let the loop's cooperative timeout publish its complete result first. The
            // grace is only an escape hatch for a host call that ignores cancellation.
            val result = withTimeoutOrNull(hardTimeoutMs) { execution.await() }
            val completed = result
                ?: successfulCompletion.get()
                ?: failure.get()?.let { throw it }
                ?: progress.get().let { snapshot ->
                    done(
                        snapshot.lastText,
                        StopReason.TIMEOUT,
                        snapshot.steps,
                        snapshot.toolCalls,
                        snapshot.usage,
                    )
                }
            logGate.write(stopLog(completed))
            return completed
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            // Some host/provider APIs use CancellationException as their own failure
            // signal while the calling flow is still active. Preserve real caller
            // cancellation, but diagnose a boundary-local cancellation like any other
            // provider failure.
            val snapshot = progress.get()
            val wrapped = AgentRunFailure(snapshot.steps, snapshot.toolCalls, cancelled)
            logGate.write(failureLog(wrapped))
            throw wrapped
        } catch (cause: Exception) {
            val snapshot = progress.get()
            val wrapped = if (cause is AgentRunFailure) cause else {
                AgentRunFailure(snapshot.steps, snapshot.toolCalls, cause)
            }
            logGate.write(failureLog(wrapped))
            throw wrapped
        } finally {
            logGate.close()
            executionScope.cancel(CancellationException("Agent execution ended"))
        }
    }

    private suspend fun runLoop(
        system: String,
        input: String,
        allowlist: Set<String>,
        progress: AtomicReference<Progress>,
        log: (String) -> Unit,
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

            // Bound the call itself, not just the gaps between steps: a hung model call
            // must be interrupted at the wall-clock budget (red-team S3).
            val stepRemaining = budget.timeoutMs - (System.currentTimeMillis() - started)
            if (stepRemaining <= 0) return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage)
            log("agent step ${steps + 1}: requesting model")
            val turn = withTimeoutOrNull(stepRemaining) { provider.step(system, messages.toList(), allowed) }
                ?: return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage)
            steps++
            turn.usage?.let { usage += it }
            turn.text?.let { lastText = it }
            progress.set(Progress(lastText, steps, toolCalls, usage))
            messages.add(AssistantMsg(turn.text, turn.toolCalls))

            if (turn.toolCalls.isEmpty()) {
                return done(lastText, StopReason.COMPLETED, steps, toolCalls, usage)
            }

            val outcomes = turn.toolCalls.map { c ->
                toolCalls++
                progress.set(Progress(lastText, steps, toolCalls, usage))
                val prefix = "agent tool $toolCalls '${c.name}'"
                if (c.name !in allowedNames) {
                    log("$prefix: blocked (not in allowlist)")
                    ToolOutcome(c.id, c.name, "tool '${c.name}' is not in this agent's allowlist", isError = true)
                } else {
                    log("$prefix: started")
                    // A hung tool call is bounded by the remaining budget too (S3).
                    val toolRemaining = budget.timeoutMs - (System.currentTimeMillis() - started)
                    val r = if (toolRemaining <= 0) ToolResult("agent wall-clock budget exceeded", isError = true)
                    else runCatching {
                        withTimeoutOrNull(toolRemaining) { source.invoke(c.name, c.argsJson) }
                            ?: ToolResult("tool '${c.name}' timed out", isError = true)
                    }.getOrElse { ToolResult(it.message ?: it.toString(), isError = true) }
                    log("$prefix: ${if (r.isError) "failed" else "succeeded"}")
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

    private fun stopLog(result: AgentResult): String =
        "agent stopped: ${result.stopReason} (${result.steps} step(s), ${result.toolCalls} tool call(s))"

    private fun failureLog(failure: AgentRunFailure): String =
        "agent stopped: FAILED (${failure.steps} completed step(s), ${failure.toolCalls} tool call(s))"

    private fun Long.saturatingPlus(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private companion object {
        const val AGENT_EXECUTION_PARALLELISM = 64
        const val ADMISSION_TIMEOUT_MS = 1_000L
        const val MIN_HARD_TIMEOUT_GRACE_MS = 500L

        // Dispatchers.IO's elastic limited view confines permanently wedged Flow Agent
        // calls without consuming every permit used by unrelated host IO. Once saturated,
        // admission above fails clearly instead of queueing healthy agents indefinitely.
        val agentExecutionDispatcher = Dispatchers.IO.limitedParallelism(AGENT_EXECUTION_PARALLELISM)
    }
}
