package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeExecutorsTest {

    @Test
    fun `condition evaluator supports every comparison operator`() {
        assertTrue(NodeCatalog.evaluateCondition("2 == 2"))
        assertTrue(NodeCatalog.evaluateCondition("2 != 3"))
        assertTrue(NodeCatalog.evaluateCondition("3 > 2"))
        assertTrue(NodeCatalog.evaluateCondition("3 >= 3"))
        assertTrue(NodeCatalog.evaluateCondition("2 < 3"))
        assertTrue(NodeCatalog.evaluateCondition("2 <= 2"))
    }

    @Test
    fun `condition evaluator supports quoted text and does not split inside html`() {
        assertTrue(NodeCatalog.evaluateCondition("'Ada Lovelace' == \"Ada Lovelace\""))
        assertTrue(NodeCatalog.evaluateCondition("'<div>hello</div>' == '<div>hello</div>'"))
        assertFalse(NodeCatalog.evaluateCondition("'<div>hello</div>' == '<span>hello</span>'"))
    }

    @Test
    fun `condition evaluator recognizes documented falsy values`() {
        listOf("", "false", "null", "undefined", "0", "no", "off").forEach { value ->
            assertFalse(NodeCatalog.evaluateCondition(value), "expected '$value' to be falsy")
        }
        assertTrue(NodeCatalog.evaluateCondition("true"))
        assertTrue(NodeCatalog.evaluateCondition("1"))
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
    fun `ordering rejects non-numeric operands`() {
        assertFailsWith<ExecError> {
            NodeCatalog.evaluateCondition("abc >= 5")
        }
    }
}
