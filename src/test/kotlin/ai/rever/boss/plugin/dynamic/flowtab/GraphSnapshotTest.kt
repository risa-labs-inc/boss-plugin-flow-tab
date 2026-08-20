package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
