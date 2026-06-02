package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree

/**
 * Flow tab type info (Dynamic Plugin).
 *
 * This tab type provides a node-based flow canvas where nodes can be spawned
 * and connected with edges.
 */
object FlowTabType : TabTypeInfo {
    override val typeId = TabTypeId("flow")
    override val displayName = "Flow"
    override val icon = Icons.Outlined.AccountTree
}
