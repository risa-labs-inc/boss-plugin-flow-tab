package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlowStateTest {
    @Test
    fun `buffer retains initial values but commits only values changed this run`() {
        val buffer = FlowStateBuffer(buildJsonObject { put("old", "kept") })
        buffer.putAll(buildJsonObject { put("cursor", "new") })

        assertEquals("kept", buffer.snapshot()["old"]?.jsonPrimitive?.content)
        assertEquals("new", buffer.changes()["cursor"]?.jsonPrimitive?.content)
        assertEquals(null, buffer.changes()["old"])
    }

    @Test
    fun `state is bounded before it can be committed`() {
        val tooMany = buildJsonObject {
            repeat(MAX_FLOW_STATE_KEYS + 1) { put("key$it", it) }
        }
        assertFailsWith<IllegalArgumentException> { FlowStateBuffer().putAll(tooMany) }
    }
}
