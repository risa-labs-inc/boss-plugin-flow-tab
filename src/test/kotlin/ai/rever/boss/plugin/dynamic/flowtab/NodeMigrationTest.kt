package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0 migration contract: `NodeModel.type` is now a String kind-id. Legacy enum-name
 * graphs still decode; unknown kinds load as a first-class "unavailable" node (no
 * throw); the schemaVersion gate refuses newer-than-known graphs gracefully.
 */
class NodeMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy enum-name graph decodes into node models`() {
        val legacy = """{"nodes":[{"id":"n1","type":"HTTP","title":"HTTP","x":1,"y":2}],"edges":[],"nextId":2}"""
        val snap = json.decodeFromString(GraphSnapshot.serializer(), legacy)
        assertEquals("HTTP", snap.nodes.single().type)
        assertEquals(1, snap.schemaVersion) // absent field defaults to v1
    }

    @Test
    fun `unknown kind-id loads as an unavailable node without throwing`() {
        val state = FlowGraphState() // default builtin registry
        val snap = GraphSnapshot(
            nodes = listOf(NodeModel("n1", "tool:boss:absent", "Absent Tool", 0f, 0f)),
            schemaVersion = 2,
        )
        assertTrue(state.load(snap))
        val node = state.nodes.single()
        assertEquals("tool:boss:absent", node.kind)         // id preserved
        assertTrue(node.spec.isUnavailable)
        assertNull(node.spec.executor)                      // not runnable
    }

    @Test
    fun `unavailable node round-trips its kind-id on save`() {
        val state = FlowGraphState()
        state.load(GraphSnapshot(nodes = listOf(NodeModel("n1", "agent", "Agent", 0f, 0f)), schemaVersion = 2))
        val back = state.toSnapshot()
        assertEquals("agent", back.nodes.single().type)
    }

    @Test
    fun `a known builtin resolves to its real spec on load`() {
        val state = FlowGraphState()
        state.load(GraphSnapshot(nodes = listOf(NodeModel("n1", "HTTP", "HTTP", 0f, 0f)), schemaVersion = 1))
        val node = state.nodes.single()
        assertFalse(node.spec.isUnavailable)
        assertEquals("HTTP Request", node.spec.label)
    }

    @Test
    fun `save writes the supported schema version`() {
        val state = FlowGraphState()
        state.addNode("TRIGGER", androidx.compose.ui.geometry.Offset(0f, 0f))
        assertEquals(SUPPORTED_SCHEMA_VERSION, state.toSnapshot().schemaVersion)
    }

    @Test
    fun `a newer-schema graph is refused gracefully, leaving the canvas untouched`() {
        val state = FlowGraphState()
        state.addNode("TRIGGER", androidx.compose.ui.geometry.Offset(0f, 0f))
        val before = state.nodes.size
        val newer = GraphSnapshot(
            nodes = listOf(NodeModel("z", "HTTP", "HTTP", 0f, 0f)),
            schemaVersion = SUPPORTED_SCHEMA_VERSION + 1,
        )
        assertFalse(newer.isSchemaSupported())
        assertFalse(state.load(newer))     // refused
        assertEquals(before, state.nodes.size) // unchanged
    }
}
