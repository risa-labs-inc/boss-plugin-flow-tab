package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemTest {

    @Test
    fun `node output normalizes empty ports and flattens in port order`() {
        val low = Item(buildJsonObject { put("port", 0) })
        val high = Item(buildJsonObject { put("port", 2) })

        assertEquals(NodeOutput.EMPTY, NodeOutput.single(emptyList()))
        assertEquals(NodeOutput.EMPTY, NodeOutput.onPort(4, emptyList()))
        assertEquals(listOf(low, high), NodeOutput(mapOf(2 to listOf(high), 0 to listOf(low))).allItems())
    }
}
