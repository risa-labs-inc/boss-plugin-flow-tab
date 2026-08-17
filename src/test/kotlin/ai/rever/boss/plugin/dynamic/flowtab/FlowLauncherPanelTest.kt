package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals

class FlowLauncherPanelTest {
    @Test
    fun `next flow name skips names already in use`() {
        val flows = listOf(
            FlowSummary(tabId = "a", name = "Flow 1", nodeCount = 1),
            FlowSummary(tabId = "b", name = "Claims", nodeCount = 2),
            FlowSummary(tabId = "c", name = "Flow 3", nodeCount = 1),
        )

        assertEquals("Flow 2", nextFlowName(flows))
    }
}
