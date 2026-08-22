package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FlowLauncherPanelTest {
    @Test
    fun `controller graph writes announce launcher discovery changes`() = runBlocking {
        val storage = DesktopStorage()
        val context = object : ai.rever.boss.plugin.api.PluginContext {
            override val panelRegistry = ai.rever.boss.plugin.api.PanelRegistry()
            override val tabRegistry = ai.rever.boss.plugin.api.TabRegistry()
            override val pluginScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            override val mcpToolRegistry: ai.rever.boss.plugin.api.McpToolRegistry? = null
            override val pluginStorageFactory = object : ai.rever.boss.plugin.api.PluginStorageFactory {
                override fun createStorage(pluginId: String) = storage
            }
        }
        val controller = FlowController(context)
        val before = FlowPersistenceCoordinator.flowListRevisions.value

        try {
            val tabId = controller.createFlow()
            val afterCreate = FlowPersistenceCoordinator.flowListRevisions.value
            assertTrue(afterCreate > before)

            controller.deleteFlow(tabId)
            assertTrue(FlowPersistenceCoordinator.flowListRevisions.value > afterCreate)
        } finally {
            controller.dispose()
        }
    }

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
