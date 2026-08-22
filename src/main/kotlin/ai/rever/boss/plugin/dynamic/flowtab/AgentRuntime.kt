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
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference

/**
 * One tool the model asked to run this turn. [id] correlates the call with its result
 * so a provider can thread multi-tool turns; [argsJson] is the raw JSON argument string
 * handed straight to [ToolSource.invoke].
 */
data class ToolCall(val id: String, val name: String, val argsJson: String)

/** The outcome of one [ToolCall], fed back to the model as DATA (never as instructions). */
data class ToolOutcome(val id: String, val name: String, val content: String, val isError: Boolean)

/**
 * Token accounting a provider may report per turn. [input] includes the transcript and
 * completed tool rounds replayed on that request. [output] includes generated model text
 * and tool-call arguments; those arguments and their tool results count again as input
 * when replayed on later turns. Both dimensions are summed across all turns, so replayed
 * content is charged on every request. Providers may omit usage, making token enforcement
 * best-effort rather than a guaranteed bound.
 */
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

/**
 * Bounds on a single agent run. [maxTokens] is cumulative provider-reported input plus
 * output usage across all model turns, including content replayed between requests,
 * unlike a provider's per-request output cap. It is a soft threshold checked before each
 * request: the request that crosses it finishes, and the run stops before another request,
 * so actual usage can exceed [maxTokens] by one full turn. It is best-effort when usage
 * is absent. [maxSteps] counts model requests. Step and token thresholds are evaluated
 * between requests and before generic tools whose results could not be sent in another
 * request; timeout also bounds an in-flight provider or tool call.
 */
data class AgentBudget(
    val maxSteps: Int = 8,
    val timeoutMs: Long = 120_000,
    val maxTokens: Int = Int.MAX_VALUE,
)

/** Why the loop stopped. [COMPLETED] means final text or a valid structured result was produced. */
enum class StopReason { COMPLETED, MAX_STEPS, TIMEOUT, TOKEN_BUDGET }

/** The outcome of an agent run: its final [finalText], why it stopped, and run counters. */
data class AgentResult(
    val finalText: String,
    val stopReason: StopReason,
    val steps: Int,
    val toolCalls: Int,
    val usage: TokenUsage = TokenUsage(),
    val usageReported: Boolean = false,
    val structuredOutput: JsonObject? = null,
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
) : ExecError(
    "Agent stopped: FAILED after $steps completed step(s), $toolCalls attempted tool call(s): " +
        (cause.message?.takeIf { it.isNotBlank() } ?: cause::class.simpleName ?: "unknown error"),
    cause,
)

/** A bad Agent setting discovered after the runtime enumerates its dynamic tool source. */
class AgentConfigurationError(message: String) : ExecError(message)

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
 *    [AgentNodeExecutor] escalates every non-completed stop reason to a node error so
 *    partial model prose never becomes successful downstream data.
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
        val usageReported: Boolean = false,
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
        outputSchema: AgentOutputSchema? = null,
        log: (String) -> Unit = {},
    ): AgentResult {
        val timeoutMs = budget.timeoutMs.coerceAtLeast(0)
        if (timeoutMs == 0L) {
            return done(
                text = "",
                reason = StopReason.TIMEOUT,
                steps = 0,
                toolCalls = 0,
                usage = TokenUsage(),
                usageReported = false,
            ).also {
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
        fun terminalLog(message: String) {
            // Closing waits for any in-flight worker write. The direct write afterward
            // is therefore guaranteed to be the final line even when the worker ignores
            // cancellation and finishes a host call after this run returns.
            logGate.close()
            log(message)
        }
        fun wrappedFailure(cause: Throwable): Throwable {
            val snapshot = progress.get()
            return when (cause) {
                is AgentConfigurationError, is AgentRunFailure -> cause
                else -> AgentRunFailure(snapshot.steps, snapshot.toolCalls, cause)
            }
        }
        val loopStarted = CompletableDeferred<Unit>()
        val execution = executionScope.async {
            loopStarted.complete(Unit)
            runLoop(system, input, allowlist, outputSchema, progress, logGate::write)
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
            terminalLog("agent admission failed: ${cause.message}")
            executionScope.cancel(CancellationException("Agent execution lane unavailable"))
            throw cause
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
                        snapshot.usageReported,
                    )
                }
            terminalLog(stopLog(completed))
            return completed
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            // Some host/provider APIs use CancellationException as their own failure
            // signal while the calling flow is still active. Preserve real caller
            // cancellation, but diagnose a boundary-local cancellation like any other
            // provider failure.
            val wrapped = wrappedFailure(cancelled)
            terminalLog(failureLog(wrapped))
            throw wrapped
        } catch (cause: Exception) {
            val wrapped = wrappedFailure(cause)
            terminalLog(failureLog(wrapped))
            throw wrapped
        } catch (cause: LinkageError) {
            // Optional plugin/API skew reaches Kotlin as NoClassDefFoundError,
            // NoSuchMethodError, or AbstractMethodError rather than Exception. Diagnose
            // those expected boundary failures without swallowing VM-fatal Errors.
            val wrapped = wrappedFailure(cause)
            terminalLog(failureLog(wrapped))
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
        outputSchema: AgentOutputSchema?,
        progress: AtomicReference<Progress>,
        log: (String) -> Unit,
    ): AgentResult {
        val started = System.currentTimeMillis()
        val available = source.list()
        var effectiveAllowlist = allowlist
        if (outputSchema != null) {
            val reservedDescriptor = AgentStructuredOutput.descriptor(outputSchema)
            val allowlistedRealReserved = available.any { descriptor ->
                descriptor.ref.name == AgentStructuredOutput.TOOL_NAME &&
                    (descriptor.ref.name in allowlist || descriptor.ref.kindId in allowlist)
            }
            if (allowlistedRealReserved) {
                throw AgentConfigurationError(
                    "Agent output tool '${AgentStructuredOutput.TOOL_NAME}' conflicts with an allowlisted tool of the same name",
                )
            }
            // Older flows sometimes listed the synthetic submission tool explicitly.
            // It is added below exactly once, so those redundant entries remain a no-op.
            effectiveAllowlist = allowlist - AgentStructuredOutput.TOOL_NAME - reservedDescriptor.ref.kindId
        }
        val resolved = available.filter { descriptor ->
            descriptor.ref.name in effectiveAllowlist || descriptor.ref.kindId in effectiveAllowlist
        }
        val matched = resolved.flatMapTo(HashSet()) { listOf(it.ref.name, it.ref.kindId) }
        val unmatched = effectiveAllowlist.filterNot { entry ->
            entry in matched
        }
        log(resolvedToolsLog(resolved, outputSchema != null))
        if (unmatched.isNotEmpty()) {
            throw AgentConfigurationError(
                "Agent tool allowlist contains ${unmatched.size} unavailable " +
                    "${if (unmatched.size == 1) "entry" else "entries"}: " +
                    formatConfiguredEntries(unmatched) +
                    " (misspelled, not registered, or its tool source is unavailable)",
            )
        }
        val allowed = buildList {
            addAll(resolved)
            outputSchema?.let { add(AgentStructuredOutput.descriptor(it)) }
        }
        val allowedNames = allowed.map { it.ref.name }.toSet()
        val effectiveSystem = if (outputSchema == null) {
            system
        } else {
            listOf(system.trim(), AgentStructuredOutput.SYSTEM_INSTRUCTION)
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        }

        val messages = mutableListOf<AgentMessage>(UserMsg(input))
        var steps = 0
        var toolCalls = 0
        var usage = TokenUsage()
        var usageReported = false
        var lastText = ""
        var structuredFailures = 0

        fun publishProgress() {
            progress.set(
                Progress(
                    lastText = lastText,
                    steps = steps,
                    toolCalls = toolCalls,
                    usage = usage,
                    usageReported = usageReported,
                ),
            )
        }

        /** Token precedence is intentional when one turn reaches both soft thresholds. */
        fun pendingModelLimit(): StopReason? = when {
            usage.total >= budget.maxTokens -> StopReason.TOKEN_BUDGET
            steps >= budget.maxSteps -> StopReason.MAX_STEPS
            else -> null
        }

        fun stoppedAt(reason: StopReason): AgentResult =
            done(lastText, reason, steps, toolCalls, usage, usageReported)

        suspend fun executeTool(call: ToolCall): ToolOutcome {
            toolCalls++
            publishProgress()
            val prefix = "agent tool $toolCalls '${safeToolName(call.name)}'"
            if (call.name !in allowedNames) {
                log("$prefix: blocked (not in allowlist)")
                return ToolOutcome(
                    call.id,
                    call.name,
                    "tool '${call.name}' is not in this agent's allowlist",
                    isError = true,
                )
            }
            log("$prefix: started")
            val toolRemaining = budget.timeoutMs - (System.currentTimeMillis() - started)
            val result = if (toolRemaining <= 0) {
                ToolResult("agent wall-clock budget exceeded", isError = true)
            } else {
                runCatching {
                    withTimeoutOrNull(toolRemaining) { source.invoke(call.name, call.argsJson) }
                        ?: ToolResult("tool '${call.name}' timed out", isError = true)
                }.getOrElse { ToolResult(it.message ?: it.toString(), isError = true) }
            }
            log("$prefix: ${if (result.isError) "failed" else "succeeded"}")
            return ToolOutcome(call.id, call.name, result.text, result.isError)
        }

        while (true) {
            pendingModelLimit()?.let { reason ->
                return stoppedAt(reason)
            }
            if (System.currentTimeMillis() - started >= budget.timeoutMs)
                return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage, usageReported)

            // Bound the call itself, not just the gaps between steps: a hung model call
            // must be interrupted at the wall-clock budget (red-team S3).
            val stepRemaining = budget.timeoutMs - (System.currentTimeMillis() - started)
            if (stepRemaining <= 0) {
                return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage, usageReported)
            }
            log("agent step ${steps + 1}: requesting model")
            val turn = withTimeoutOrNull(stepRemaining) { provider.step(effectiveSystem, messages.toList(), allowed) }
                ?: return done(lastText, StopReason.TIMEOUT, steps, toolCalls, usage, usageReported)
            steps++
            turn.usage?.let {
                usage += it
                usageReported = true
            }
            turn.text?.let { lastText = it }
            publishProgress()
            messages.add(AssistantMsg(turn.text, turn.toolCalls))

            if (outputSchema != null) {
                val submissions = turn.toolCalls.filter { it.name == AgentStructuredOutput.TOOL_NAME }
                if (submissions.isNotEmpty()) {
                    val submissionIsAlone = turn.toolCalls.size == 1
                    // A sole valid submission completes this turn even on the last permitted
                    // request. Mixed calls are non-completing, so do not run their generic tools
                    // when no following request could consume the outcomes.
                    if (!submissionIsAlone) {
                        pendingModelLimit()?.let { reason -> return stoppedAt(reason) }
                    }
                    val outcomes = turn.toolCalls.map { call ->
                        if (call.name != AgentStructuredOutput.TOOL_NAME) {
                            executeTool(call)
                        } else if (submissionIsAlone) {
                            toolCalls++
                            publishProgress()
                            val parsed = AgentStructuredOutput.parseSubmission(call.argsJson, outputSchema)
                            val value = parsed.getOrNull()
                            if (value != null) {
                                turn.text?.takeIf { it.isNotBlank() }?.let {
                                    log("agent non-structured text withheld (${it.length} chars)")
                                }
                                log("agent structured output submission: accepted")
                                return done(
                                    text = "",
                                    reason = StopReason.COMPLETED,
                                    steps = steps,
                                    toolCalls = toolCalls,
                                    usage = usage,
                                    usageReported = usageReported,
                                    structuredOutput = value,
                                )
                            }
                            val reason = parsed.exceptionOrNull()?.message ?: "the structured output is invalid"
                            log("agent structured output submission: rejected (${reason.take(MAX_LOG_VALIDATION_REASON_CHARS)})")
                            ToolOutcome(
                                call.id,
                                call.name,
                                reason,
                                isError = true,
                            )
                        } else {
                            toolCalls++
                            publishProgress()
                            val prefix = "agent tool $toolCalls '${safeToolName(call.name)}'"
                            log("$prefix: blocked (structured output must be submitted exactly once, alone)")
                            ToolOutcome(
                                call.id,
                                call.name,
                                "flow_submit_output must be called exactly once and be the only tool call in its turn",
                                isError = true,
                            )
                        }
                    }
                    if (submissionIsAlone) {
                        structuredFailures++
                        if (structuredFailures >= MAX_STRUCTURED_OUTPUT_FAILURES) {
                            throw ExecError(STRUCTURED_OUTPUT_FAILURE_MESSAGE)
                        }
                        pendingModelLimit()?.let { reason -> return stoppedAt(reason) }
                    }
                    messages.add(ToolResultsMsg(outcomes))
                    continue
                }
                if (turn.toolCalls.isEmpty()) {
                    if (turn.text.isNullOrBlank()) {
                        throw ExecError("Agent returned an empty response instead of required structured output")
                    }
                    log("agent structured output submission: missing")
                    structuredFailures++
                    if (structuredFailures >= MAX_STRUCTURED_OUTPUT_FAILURES) {
                        throw ExecError(STRUCTURED_OUTPUT_FAILURE_MESSAGE)
                    }
                    messages.add(UserMsg(AgentStructuredOutput.MISSING_SUBMISSION_MESSAGE))
                    continue
                }
            }

            if (turn.toolCalls.isEmpty()) {
                return done(lastText, StopReason.COMPLETED, steps, toolCalls, usage, usageReported)
            }

            // Do not create side effects whose outcomes can never be presented to the model.
            // These skipped calls are neither attempted nor included in the tool-call counter.
            pendingModelLimit()?.let { reason -> return stoppedAt(reason) }

            val outcomes = turn.toolCalls.map { executeTool(it) }
            messages.add(ToolResultsMsg(outcomes))

        }
    }

    private fun done(
        text: String,
        reason: StopReason,
        steps: Int,
        toolCalls: Int,
        usage: TokenUsage,
        usageReported: Boolean,
        structuredOutput: JsonObject? = null,
    ) = AgentResult(text, reason, steps, toolCalls, usage, usageReported, structuredOutput)

    private fun stopLog(result: AgentResult): String =
        "agent stopped: ${result.stopReason} " +
            "(${result.steps} completed step(s), ${result.toolCalls} attempted tool call(s))"

    private fun failureLog(failure: Throwable): String = when (failure) {
        is AgentConfigurationError -> "agent configuration failed"
        is AgentRunFailure ->
            "agent stopped: FAILED " +
                "(${failure.steps} completed step(s), ${failure.toolCalls} attempted tool call(s))"
        else -> "agent stopped: FAILED"
    }

    private fun resolvedToolsLog(resolved: List<ToolDescriptor>, structuredOutput: Boolean): String {
        val tools = if (resolved.isEmpty()) "" else " (${formatToolNames(resolved.map { it.ref.name })})"
        val structured = if (structuredOutput) "; structured output enabled" else ""
        return "agent tools resolved: ${resolved.size}$tools$structured"
    }

    private fun formatToolNames(names: Collection<String>): String {
        val shown = names.take(MAX_LOG_TOOL_LIST_ENTRIES).joinToString(", ") { safeToolName(it) }
        return shown + if (names.size > MAX_LOG_TOOL_LIST_ENTRIES) ", …" else ""
    }

    /** Preserve config typos exactly enough to diagnose them while keeping one bounded line. */
    private fun formatConfiguredEntries(names: Collection<String>): String =
        names.take(MAX_LOG_TOOL_LIST_ENTRIES).joinToString(", ") { name ->
            buildString {
                append('\'')
                for (char in name.take(MAX_LOG_TOOL_NAME_CHARS)) {
                    when (char) {
                        '\\' -> append("\\\\")
                        '\'' -> append("\\'")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (char.isISOControl()) append('_') else append(char)
                    }
                }
                if (name.length > MAX_LOG_TOOL_NAME_CHARS) append('…')
                append('\'')
            }
        } + if (names.size > MAX_LOG_TOOL_LIST_ENTRIES) ", … (${names.size} total)" else ""

    /** Model-supplied names must remain one bounded, trustworthy log-line token. */
    private fun safeToolName(name: String): String =
        name.take(MAX_LOG_TOOL_NAME_CHARS)
            .map { char ->
                if (char.isLetterOrDigit() || char in "._:/-") char else '_'
            }
            .joinToString("")
            .ifBlank { "<unnamed>" }

    private fun Long.saturatingPlus(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

    private companion object {
        const val AGENT_EXECUTION_PARALLELISM = 64
        const val ADMISSION_TIMEOUT_MS = 1_000L
        const val MIN_HARD_TIMEOUT_GRACE_MS = 500L
        const val MAX_LOG_TOOL_NAME_CHARS = 80
        const val MAX_LOG_TOOL_LIST_ENTRIES = 12
        const val MAX_LOG_VALIDATION_REASON_CHARS = 240
        const val MAX_STRUCTURED_OUTPUT_FAILURES = 3
        val STRUCTURED_OUTPUT_FAILURE_MESSAGE =
            "Agent did not produce valid structured output after $MAX_STRUCTURED_OUTPUT_FAILURES attempts " +
                "(initial attempt plus ${MAX_STRUCTURED_OUTPUT_FAILURES - 1} corrections)"

        // Dispatchers.IO's elastic limited view confines permanently wedged Flow Agent
        // calls without consuming every permit used by unrelated host IO. Once saturated,
        // admission above fails clearly instead of queueing healthy agents indefinitely.
        val agentExecutionDispatcher = Dispatchers.IO.limitedParallelism(AGENT_EXECUTION_PARALLELISM)
    }
}
