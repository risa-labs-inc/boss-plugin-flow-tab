package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import java.util.UUID

/** Sidebar panel info for the Flow launcher. */
object FlowLauncherInfo : PanelInfo {
    override val id = PanelId("flow-launcher", 50)
    override val displayName = "Flow"
    override val icon = Icons.Outlined.AccountTree
    override val defaultSlotPosition = left.bottom

    /**
     * Set by the plugin at register() time (it captures the PluginContext, which
     * this object can't see on its own). When non-null, the host runs this on a
     * sidebar click *instead of* toggling the docked pane — so clicking "Flow"
     * opens a tab directly rather than popping open the bottom split.
     */
    var onLaunch: (() -> Unit)? = null

    override val sidebarItem: SidebarItem
        get() = SidebarItem(id, icon, displayName, onClick = onLaunch)
}

/**
 * A small sidebar panel that launches Flow canvas tabs.
 *
 * Tab types registered by plugins aren't listed in the host's built-in New Tab
 * dialog, so this panel is the entry point: its button opens a fresh Flow tab
 * via the generic [ai.rever.boss.plugin.api.SplitViewOperations.openTab] API.
 */
class FlowLauncherComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val context: PluginContext
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        val splitView = context.splitViewOperations
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1F1F23))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Flow",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Node-based canvas. Open a new flow to start wiring nodes together.",
                color = Color(0xFF9A9AA4),
                fontSize = 12.sp
            )

            NewFlowButton(enabled = splitView != null) {
                splitView?.openTab(
                    FlowTabData(id = "flow-${UUID.randomUUID()}", title = "Flow")
                )
            }

            if (splitView == null) {
                Text(
                    text = "Tab operations are unavailable in this context.",
                    color = Color(0xFFE5935B),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun NewFlowButton(enabled: Boolean, onClick: () -> Unit) {
    val accent = Color(0xFF2196F3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) accent else accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("New Flow Canvas", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
