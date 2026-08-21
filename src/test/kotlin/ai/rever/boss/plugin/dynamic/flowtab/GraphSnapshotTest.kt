package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * GraphSnapshot v2 adds [GraphSnapshot.schemaVersion] + [GraphSnapshot.metadata].
 * The critical property is back-compat: every graph already saved to disk lacks
 * these fields and MUST still load (defaulting to v1, no metadata). schemaVersion
 * is the gate an older build reads to refuse a newer graph gracefully (F10).
 */
class GraphSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy snapshot without schemaVersion decodes to v1 with no metadata`() {
        val legacy = """{"nodes":[],"edges":[],"nextId":5}"""
        val snap = json.decodeFromString(GraphSnapshot.serializer(), legacy)
        assertEquals(1, snap.schemaVersion)
        assertNull(snap.metadata)
        assertEquals(5L, snap.nextId)
    }

    @Test
    fun `v2 snapshot round-trips metadata`() {
        val snap = GraphSnapshot(
            schemaVersion = 2,
            metadata = FlowMeta(name = "My Lanager", description = "does X", version = 3),
        )
        val back = json.decodeFromString(
            GraphSnapshot.serializer(),
            json.encodeToString(GraphSnapshot.serializer(), snap),
        )
        assertEquals(2, back.schemaVersion)
        assertEquals("My Lanager", back.metadata?.name)
        assertEquals("does X", back.metadata?.description)
        assertEquals(3, back.metadata?.version)
    }

    @Test
    fun `flow schedule is optional and round-trips in metadata`() {
        val legacyMetadata = json.decodeFromString(
            FlowMeta.serializer(),
            """{"name":"Manual"}""",
        )
        assertNull(legacyMetadata.schedule)

        val scheduled = FlowMeta(
            name = "Digest",
            schedule = FlowSchedule(intervalMinutes = 15),
        )
        val back = json.decodeFromString(
            FlowMeta.serializer(),
            json.encodeToString(FlowMeta.serializer(), scheduled),
        )
        assertEquals(15L, back.schedule?.intervalMinutes)
    }

    @Test
    fun `imported flow drops its armed schedule`() {
        val snapshot = GraphSnapshot(
            metadata = FlowMeta(name = "Shared", schedule = FlowSchedule(intervalMinutes = 1)),
        )

        val imported = snapshot.withoutSchedule()

        assertEquals("Shared", imported.metadata?.name)
        assertNull(imported.metadata?.schedule)
    }

    @Test
    fun `workflow revision ignores layout but captures executable edits`() {
        val base = GraphSnapshot(
            nodes = listOf(
                NodeModel(
                    "n1", "HTTP", "Fetch claim", 20f, 30f,
                    JsonObject(mapOf("url" to JsonPrimitive("https://a"))),
                ),
            ),
            metadata = FlowMeta(name = "Claims", version = 7),
        )

        val moved = base.copy(nodes = base.nodes.map { it.copy(x = 900f, y = 500f) })
        val reconfigured = base.copy(
            nodes = base.nodes.map {
                it.copy(config = JsonObject(mapOf("url" to JsonPrimitive("https://b"))))
            },
        )

        assertEquals(base.executionFingerprint(), moved.executionFingerprint())
        assertEquals(base.toWorkflowRevision(10L, "canvas").id, moved.toWorkflowRevision(20L, "canvas").id)
        assertFalse(base.executionFingerprint() == reconfigured.executionFingerprint())
    }

    @Test
    fun `workflow revision snapshot round-trips with a run`() {
        val revision = GraphSnapshot(nodes = listOf(NodeModel("n1", "TRIGGER", "Start", 1f, 2f)))
            .toWorkflowRevision(123L, "headless")
        val job = RunJob("run-1", "flow-1", RunJobState.SUCCEEDED, workflowRevision = revision)

        val restored = json.decodeFromString(RunJob.serializer(), json.encodeToString(RunJob.serializer(), job))

        assertEquals(revision, restored.workflowRevision)
    }
}
