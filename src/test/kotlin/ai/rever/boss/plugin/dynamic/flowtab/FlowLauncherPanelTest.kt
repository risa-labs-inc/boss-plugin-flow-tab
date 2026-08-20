package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull

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

    @Test
    fun `schedule status exposes cadence and last plus next state`() {
        assertNull(flowScheduleStatus(FlowSummary(tabId = "manual")))

        val status = flowScheduleStatus(
            FlowSummary(
                tabId = "scheduled",
                schedule = FlowSchedule(5),
                lastScheduledRunAtEpochMs = 1_000,
                nextScheduledRunAtEpochMs = 301_000,
                lastScheduledRunState = RunJobState.SUCCEEDED,
            ),
        )!!
        assertContains(status, "Every 5 minutes")
        assertContains(status, "succeeded")
        assertContains(status, "Next:")
    }
}
