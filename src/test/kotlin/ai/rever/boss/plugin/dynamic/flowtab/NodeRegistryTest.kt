package ai.rever.boss.plugin.dynamic.flowtab

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `snapshots stay safe while registrations change concurrently`() {
        val reg = NodeRegistry()
        repeat(100) { reg.register(spec("seed-$it")) }
        val failures = ConcurrentLinkedQueue<Throwable>()

        val writer = thread(name = "registry-writer") {
            repeat(10_000) { index ->
                runCatching {
                    val id = "dynamic-${index % 200}"
                    reg.register(spec(id))
                    if (index % 2 == 0) reg.unregister(id)
                }.onFailure(failures::add)
            }
        }
        val reader = thread(name = "registry-reader") {
            repeat(10_000) { index ->
                runCatching {
                    reg.all()
                    reg.resolve("dynamic-${index % 200}")
                }.onFailure(failures::add)
            }
        }

        writer.join()
        reader.join()

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
