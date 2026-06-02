package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tab data for Flow tabs.
 *
 * @param id Unique identifier for this tab instance.
 * @param title Display title for the tab.
 * @param icon Tab icon vector.
 * @param tabIcon Tab icon wrapper.
 */
data class FlowTabData(
    override val id: String,
    override val title: String = "Flow",
    override val icon: ImageVector = FlowTabType.icon,
    override val tabIcon: TabIcon? = TabIcon.Vector(FlowTabType.icon)
) : TabInfo {
    override val typeId: TabTypeId = FlowTabType.typeId
}
