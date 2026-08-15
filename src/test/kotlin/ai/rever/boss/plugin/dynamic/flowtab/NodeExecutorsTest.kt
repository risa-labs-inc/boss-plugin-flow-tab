package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeExecutorsTest {

    private fun evaluate(raw: String): Boolean = NodeCatalog.evaluateCondition(raw) { it }

    @Test
    fun `condition evaluator supports every comparison operator`() {
        assertTrue(evaluate("2 == 2"))
        assertTrue(evaluate("2 != 3"))
        assertTrue(evaluate("3 > 2"))
        assertTrue(evaluate("3 >= 3"))
        assertTrue(evaluate("2 < 3"))
        assertTrue(evaluate("2 <= 2"))
    }

    @Test
    fun `condition evaluator supports quoted text and does not split inside html`() {
        assertTrue(evaluate("'Ada Lovelace' == \"Ada Lovelace\""))
        assertTrue(evaluate("'<div>hello</div>' == '<div>hello</div>'"))
        assertFalse(evaluate("'<div>hello</div>' == '<span>hello</span>'"))
    }

    @Test
    fun `condition evaluator recognizes documented falsy values`() {
        listOf("", "false", "null", "undefined", "0", "no", "off").forEach { value ->
            assertFalse(evaluate(value), "expected '$value' to be falsy")
        }
        assertTrue(evaluate("true"))
        assertTrue(evaluate("1"))
    }

    @Test
    fun `condition grammar is parsed before multiline data is interpolated`() {
        val result = NodeCatalog.evaluateCondition("{{ value }} == \"done\"") { fragment ->
            fragment.replace("{{ value }}", "not\ndone")
        }

        assertFalse(result)
    }

    @Test
    fun `operator-like text remains data in a truthiness condition`() {
        val result = NodeCatalog.evaluateCondition("{{ value }}") { fragment ->
            fragment.replace("{{ value }}", "Home > Docs > API")
        }

        assertTrue(result)
    }

    @Test
    fun `comparison ignores operators inside expressions and quoted literals`() {
        val nodeTitle = NodeCatalog.evaluateCondition("{{ \$node[\"A > B\"].json.x }} == 1") { fragment ->
            if (fragment.startsWith("{{")) "1" else fragment
        }
        assertTrue(nodeTitle)
        assertTrue(evaluate("\"a > b\" == \"a > b\""))
    }

    @Test
    fun `ordering supports two text operands but rejects mixed number and text`() {
        assertTrue(evaluate("\"2024-06-01\" >= \"2024-01-01\""))
        val error = assertFailsWith<ExecError> {
            evaluate("abc >= 5")
        }
        assertTrue(error.message!!.contains("'abc'"))
        assertTrue(error.message!!.contains("'5'"))
    }

    @Test
    fun `apostrophe in unquoted text does not hide the comparison operator`() {
        assertTrue(evaluate("Ada's book == Ada's book"))
        assertFalse(evaluate("Ada's book == Grace's book"))
    }

    @Test
    fun `malformed comparison reports a missing operand`() {
        assertFailsWith<ExecError> { evaluate("x ==") }
        assertFailsWith<ExecError> { evaluate("== 5") }
    }

    @Test
    fun `missing ordering operand is false and nonstandard number text is rejected`() {
        val missing = NodeCatalog.evaluateCondition("{{ missing }} > 10") { fragment ->
            fragment.replace("{{ missing }}", "")
        }
        val bothMissing = NodeCatalog.evaluateCondition("{{ left }} <= {{ right }}") { fragment ->
            fragment.replace("{{ left }}", "").replace("{{ right }}", "")
        }

        assertFalse(missing)
        assertFalse(bothMissing)
        assertFailsWith<ExecError> { evaluate("10d > 5") }
    }
}
