package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [MergedToolSource] fans several tool sources into one. The routing must not re-`list()`
 * every source on every `invoke` (red-team S6) — with external MCP that is a network
 * round-trip per agent tool call. Routes are built from `list()` and cached.
 */
class MergedToolSourceTest {

    private class CountingSource(private val d: List<ToolDescriptor>) : ToolSource {
        var listCount = 0
        override suspend fun list(): List<ToolDescriptor> { listCount++; return d }
        override suspend fun invoke(name: String, argsJson: String) = ToolResult("ok:$name", isError = false)
    }

    private fun desc(n: String) = ToolDescriptor(ToolRef(ToolScope.BOSS, n), n, "d", "{}")

    @Test
    fun `invoke routes from a cache without re-listing sources every call`() = runBlocking {
        val s = CountingSource(listOf(desc("a"), desc("b")))
        val merged = MergedToolSource(listOf(s))
        merged.list() // builds the route table once
        merged.invoke("a", "{}")
        merged.invoke("b", "{}")
        merged.invoke("a", "{}")
        assertEquals(1, s.listCount) // was: one list() per invoke (4 total)
    }

    @Test
    fun `a cold invoke builds routes once then succeeds`() = runBlocking {
        val s = CountingSource(listOf(desc("a")))
        val merged = MergedToolSource(listOf(s))
        val r = merged.invoke("a", "{}") // no prior list(): must refresh once on miss
        assertFalse(r.isError)
        assertEquals(1, s.listCount)
    }
}
