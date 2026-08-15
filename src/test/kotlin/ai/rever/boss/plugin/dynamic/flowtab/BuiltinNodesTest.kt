package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Characterization tests: the builtin registry must reproduce the legacy [NodeType]
 * enum's behavior EXACTLY, so migrating the palette + executor dispatch onto the
 * registry changes nothing observable. If any of these drift, the migration is unsafe.
 */
class BuiltinNodesTest {

    private val reg = builtinNodeRegistry()

    @Test
    fun `registers exactly the 12 builtin kinds keyed by enum name in order`() {
        assertEquals(NodeType.entries.map { it.name }, reg.all().map { it.id })
    }

    @Test
    fun `each builtin spec mirrors the enum metadata`() {
        for (t in NodeType.entries) {
            val s = reg[t.name] ?: error("missing spec for ${t.name}")
            assertEquals(t.label, s.label, t.name)
            assertEquals(t.inputs, s.inputs, t.name)
            assertEquals(t.outputs, s.outputs, t.name)
            assertEquals(t.accent, s.accent, t.name)
            assertEquals(t.description, s.description, t.name)
            assertEquals(t.runMode, s.runMode, t.name)
            assertEquals(t.usesSession(), s.usesSession, "${t.name} usesSession")
            assertEquals(t.hasMetaRow(), s.hasMetaRow, "${t.name} hasMetaRow")
            assertEquals(t.configFields(), s.configFields, "${t.name} configFields")
        }
    }

    @Test
    fun `port labels match the enum`() {
        for (t in NodeType.entries) {
            val s = reg[t.name]!!
            for (i in 0 until t.outputs) assertEquals(t.outputLabel(i), s.outputLabel(i), "${t.name} out $i")
            for (i in 0 until t.inputs) assertEquals(t.inputLabel(i), s.inputLabel(i), "${t.name} in $i")
        }
    }

    @Test
    fun `all builtin kinds are runnable`() {
        for (t in NodeType.entries) {
            val s = reg[t.name]!!
            assertNotNull(s.executor, "${t.name} registry executor")
        }
    }
}
