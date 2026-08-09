package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.AiChunk
import ai.rever.boss.plugin.api.AiGatewayAPI
import ai.rever.boss.plugin.api.AiModelInfo
import ai.rever.boss.plugin.api.AiReply
import ai.rever.boss.plugin.api.AiRequest
import ai.rever.boss.plugin.api.AiRound
import ai.rever.boss.plugin.api.AiToolCall
import ai.rever.boss.plugin.api.AiToolOutcome
import ai.rever.boss.plugin.api.AiToolSpec
import ai.rever.boss.plugin.api.AiTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the translation between the runtime's transcript types and the gateway's.
 *
 * Replaces AgentCredentialResolutionTest, which pinned where an *Anthropic* key came from.
 * That question no longer exists: the agent node uses whatever provider is active, and the
 * gateway owns credential resolution. What can still go wrong is the tool round trip, since a
 * provider will not accept a tool result whose originating call was not replayed alongside it.
 */
class GatewayAgentProviderTest {

    private class RecordingGateway(
        private val turns: List<AiTurn>,
    ) : AiGatewayAPI {
        val steps = mutableListOf<Pair<AiRequest, List<AiRound>>>()
        val toolsSeen = mutableListOf<List<AiToolSpec>>()
        private var index = 0

        override suspend fun complete(request: AiRequest): Result<AiReply> =
            Result.success(AiReply("unused"))

        override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()

        override suspend fun runAgent(
            request: AiRequest,
            tools: List<AiToolSpec>,
            budget: ai.rever.boss.plugin.api.AiBudget,
            invoke: suspend (AiToolCall) -> AiToolOutcome,
        ): Result<ai.rever.boss.plugin.api.AiAgentResult> =
            Result.failure(UnsupportedOperationException())

        override suspend fun step(
            request: AiRequest,
            tools: List<AiToolSpec>,
            rounds: List<AiRound>,
        ): Result<AiTurn> {
            steps += request to rounds
            toolsSeen += tools
            return Result.success(turns.getOrElse(index++) { AiTurn(text = "done") })
        }

        override fun activeModel(): AiModelInfo? = AiModelInfo("OPENAI", "OpenAI", "gpt-x")
    }

    private val tool = ToolDescriptor(ToolRef(ToolScope.BOSS, "ls"), "ls", "list files", """{"type":"object"}""")

    @Test
    fun `a text turn comes back as a final answer`() =
        runBlocking {
            val gateway = RecordingGateway(listOf(AiTurn(text = "the answer")))
            val provider = GatewayAgentProvider({ gateway })

            val turn = provider.step("be brief", listOf(UserMsg("q")), emptyList())

            assertEquals("the answer", turn.text)
            assertTrue(turn.toolCalls.isEmpty())
        }

    @Test
    fun `tool calls and usage survive the translation`() =
        runBlocking {
            val gateway =
                RecordingGateway(
                    listOf(
                        AiTurn(
                            toolCalls = listOf(AiToolCall("c1", "ls", """{"p":"."}""")),
                            usage = ai.rever.boss.plugin.api.AiUsage(inputTokens = 7, outputTokens = 3),
                        ),
                    ),
                )
            val provider = GatewayAgentProvider({ gateway })

            val turn = provider.step("s", listOf(UserMsg("q")), listOf(tool))

            assertEquals(listOf(ToolCall("c1", "ls", """{"p":"."}""")), turn.toolCalls)
            assertEquals(10, turn.usage?.total)
            // The runtime's budget reads usage, so losing it means an unbounded run.
            assertNotNull(turn.usage)
        }

    @Test
    fun `a tool result is sent with the call that produced it`() =
        runBlocking {
            // The failure this guards: Anthropic rejects a tool_result whose tool_use was not
            // replayed, and the Responses API needs the function_call item alongside its
            // output. The runtime hands back a ToolResultsMsg with no assistant turn attached,
            // so the provider has to remember it.
            val asked = AiTurn(toolCalls = listOf(AiToolCall("c1", "ls", "{}")))
            val gateway = RecordingGateway(listOf(asked, AiTurn(text = "two files")))
            val provider = GatewayAgentProvider({ gateway })

            provider.step("s", listOf(UserMsg("q")), listOf(tool))
            val second =
                provider.step(
                    "s",
                    listOf(UserMsg("q"), AssistantMsg(null, asked.toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) }), ToolResultsMsg(listOf(ToolOutcome("c1", "ls", "a.txt", false)))),
                    listOf(tool),
                )

            assertEquals("two files", second.text)
            val (_, rounds) = gateway.steps[1]
            val round = rounds.single()
            assertEquals(listOf("c1"), round.outcomes.map { it.id })
            assertEquals("a.txt", round.outcomes.single().content)
            assertEquals(listOf("c1"), round.turn.toolCalls.map { it.id })
        }

    @Test
    fun `round one's observation is still visible on step three`() =
        runBlocking {
            // The regression the review caught. The runtime accumulates the transcript and the
            // default budget is maxSteps = 8, so a three-step run is ordinary - and with only
            // the latest round sent, what round 1's tool returned reached the model nowhere.
            // Nothing errors; the model just re-calls tools or answers without the evidence.
            val ask1 = AiTurn(toolCalls = listOf(AiToolCall("c1", "ls", "{}")))
            val ask2 = AiTurn(toolCalls = listOf(AiToolCall("c2", "cat", "{}")))
            val gateway = RecordingGateway(listOf(ask1, ask2, AiTurn(text = "final")))
            val provider = GatewayAgentProvider({ gateway })
            val calls1 = ask1.toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) }
            val calls2 = ask2.toolCalls.map { ToolCall(it.id, it.name, it.argumentsJson) }

            provider.step("s", listOf(UserMsg("q")), listOf(tool))
            provider.step(
                "s",
                listOf(UserMsg("q"), AssistantMsg(null, calls1), ToolResultsMsg(listOf(ToolOutcome("c1", "ls", "ROUND-ONE", false)))),
                listOf(tool),
            )
            val third =
                provider.step(
                    "s",
                    listOf(
                        UserMsg("q"),
                        AssistantMsg(null, calls1),
                        ToolResultsMsg(listOf(ToolOutcome("c1", "ls", "ROUND-ONE", false))),
                        AssistantMsg(null, calls2),
                        ToolResultsMsg(listOf(ToolOutcome("c2", "cat", "ROUND-TWO", false))),
                    ),
                    listOf(tool),
                )

            assertEquals("final", third.text)
            val rounds = gateway.steps[2].second
            assertEquals(listOf("c1", "c2"), rounds.map { it.turn.toolCalls.single().id })
            assertEquals(
                listOf("ROUND-ONE", "ROUND-TWO"),
                rounds.map { it.outcomes.single().content },
                "round one's observation must still be visible on step three",
            )
        }

    @Test
    fun `a tool result is not also sent as transcript text`() =
        runBlocking {
            // Sending it twice would show the model the same observation as both data and a
            // fresh user instruction, which is the prompt-injection shape the runtime's
            // separate ToolResultsMsg exists to avoid.
            val gateway = RecordingGateway(listOf(AiTurn(text = "ok")))
            val provider = GatewayAgentProvider({ gateway })

            provider.step(
                "s",
                listOf(UserMsg("q"), ToolResultsMsg(listOf(ToolOutcome("c1", "ls", "SECRET-OBSERVATION", false)))),
                listOf(tool),
            )

            val (request, rounds) = gateway.steps.single()
            assertTrue(request.messages.none { it.text.contains("SECRET-OBSERVATION") })
            // With no pending turn there is no round to close, so the outcome is dropped
            // rather than sent without the call that produced it - which no provider accepts.
            assertTrue(rounds.isEmpty())
        }

    @Test
    fun `no prior turn is sent when there are no outcomes`() =
        runBlocking {
            val gateway = RecordingGateway(listOf(AiTurn(text = "a"), AiTurn(text = "b")))
            val provider = GatewayAgentProvider({ gateway })

            provider.step("s", listOf(UserMsg("q")), emptyList())
            provider.step("s", listOf(UserMsg("q"), AssistantMsg("a", emptyList()), UserMsg("more")), emptyList())

            assertTrue(gateway.steps[1].second.isEmpty(), "a turn with nothing to correlate is not a round")
        }

    @Test
    fun `tool descriptors are forwarded with their schema`() =
        runBlocking {
            val gateway = RecordingGateway(listOf(AiTurn(text = "ok")))
            val provider = GatewayAgentProvider({ gateway })

            provider.step("s", listOf(UserMsg("q")), listOf(tool))

            val spec = gateway.toolsSeen.single().single()
            assertEquals("ls", spec.name)
            assertEquals("list files", spec.description)
            assertEquals("""{"type":"object"}""", spec.inputSchema)
        }

    @Test
    fun `a blank schema becomes an empty object rather than being sent blank`() =
        runBlocking {
            val gateway = RecordingGateway(listOf(AiTurn(text = "ok")))
            val provider = GatewayAgentProvider({ gateway })
            val loose = ToolDescriptor(ToolRef(ToolScope.EXT, "x"), "x", "d", "")

            provider.step("s", listOf(UserMsg("q")), listOf(loose))

            assertEquals("{}", gateway.toolsSeen.single().single().inputSchema)
        }

    @Test
    fun `no gateway fails the node rather than answering with nothing`() {
        val provider = GatewayAgentProvider({ null })

        val error =
            runCatching { runBlocking { provider.step("s", listOf(UserMsg("q")), emptyList()) } }
                .exceptionOrNull()

        // The runtime's DAG uses exceptions for node failure. An empty turn would look like a
        // model that answered with nothing and the flow would carry on.
        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("AI Gateway"))
    }

    @Test
    fun `a gateway failure is raised, not swallowed`() {
        val failing =
            object : AiGatewayAPI {
                override suspend fun complete(request: AiRequest) = Result.failure<AiReply>(IllegalStateException("x"))

                override fun stream(request: AiRequest): Flow<AiChunk> = emptyFlow()

                override suspend fun runAgent(
                    request: AiRequest,
                    tools: List<AiToolSpec>,
                    budget: ai.rever.boss.plugin.api.AiBudget,
                    invoke: suspend (AiToolCall) -> AiToolOutcome,
                ) = Result.failure<ai.rever.boss.plugin.api.AiAgentResult>(IllegalStateException("x"))

                override suspend fun step(
                    request: AiRequest,
                    tools: List<AiToolSpec>,
                    rounds: List<AiRound>,
                ) = Result.failure<AiTurn>(IllegalStateException("provider is down"))
            }

        val error =
            runCatching {
                runBlocking { GatewayAgentProvider({ failing }).step("s", listOf(UserMsg("q")), emptyList()) }
            }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("provider is down"))
    }
}
