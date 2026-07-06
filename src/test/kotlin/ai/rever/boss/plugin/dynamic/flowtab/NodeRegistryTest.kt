package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The registry is the keystone that replaces the closed [NodeType] enum: an open,
 * runtime map of kind-id -> [NodeSpec]. These tests pin its contract before any
 * consumer (palette, executor dispatch) is migrated onto it.
 */
class NodeRegistryTest {

    private fun spec(id: String) = NodeSpec(
        id = id,
        label = id,
        inputs = 1,
        outputs = 1,
        accent = 0xFF000000,
        description = "",
    )

    @Test
    fun `registered spec is retrievable by id`() {
        val reg = NodeRegistry()
        val s = spec("HTTP")
        reg.register(s)
        assertEquals(s, reg["HTTP"])
    }

    @Test
    fun `unknown id returns null so callers can treat it as unavailable`() {
        assertNull(NodeRegistry()["does-not-exist"])
    }

    @Test
    fun `all preserves registration order`() {
        val reg = NodeRegistry()
        reg.register(spec("A")); reg.register(spec("B")); reg.register(spec("C"))
        assertEquals(listOf("A", "B", "C"), reg.all().map { it.id })
    }

    @Test
    fun `re-registering an id replaces the spec but keeps its position`() {
        val reg = NodeRegistry()
        reg.register(spec("A")); reg.register(spec("B"))
        reg.register(spec("A").copy(label = "A2"))
        assertEquals(listOf("A", "B"), reg.all().map { it.id })
        assertEquals("A2", reg["A"]?.label)
    }

    @Test
    fun `unregister removes the spec`() {
        val reg = NodeRegistry()
        reg.register(spec("A"))
        reg.unregister("A")
        assertNull(reg["A"])
    }
}
