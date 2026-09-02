package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals

class FlowTabComponentTest {
    @Test
    fun `canvas identity uses the saved flow name`() {
        assertEquals(
            "Claims intake",
            currentFlowDisplayName(metadataName = "Claims intake", tabTitle = "Flow"),
        )
    }

    @Test
    fun `canvas identity falls back to the tab title and then Flow`() {
        assertEquals("Imported workflow", currentFlowDisplayName(null, "Imported workflow"))
        assertEquals("Flow", currentFlowDisplayName("   ", ""))
    }

    @Test
    fun `canvas identity preserves a long name for visual ellipsis and tooltip`() {
        val longName = "Quarterly claims intake and eligibility verification workflow"

        assertEquals(longName, currentFlowDisplayName(longName, "Flow"))
    }
}
