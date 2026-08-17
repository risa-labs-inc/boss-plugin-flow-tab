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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Sidebar panel info for the Flow launcher. */
object FlowLauncherInfo : PanelInfo {
    override val id = PanelId("flow-launcher", 50)
    override val displayName = "Flow"
    override val icon = Icons.Outlined.AccountTree
    override val defaultSlotPosition = left.bottom

    /** Null uses the host's normal sidebar behavior and toggles this launcher panel. */
    var onLaunch: (() -> Unit)? = null

    override val sidebarItem: SidebarItem
        get() = SidebarItem(id, icon, displayName, onClick = onLaunch)
}

/**
 * Sidebar browser for opening saved flows or launching a new canvas.
 *
 * Tab types registered by plugins aren't listed in the host's built-in New Tab
 * dialog, so this panel is the entry point for both stored and new flow tabs via
 * the generic [ai.rever.boss.plugin.api.SplitViewOperations.openTab] API.
 */
class FlowLauncherComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val context: PluginContext,
    private val controller: FlowController?,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        val splitView = context.splitViewOperations
        var savedFlows by remember { mutableStateOf<List<FlowSummary>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var refreshGeneration by remember { mutableIntStateOf(0) }

        LaunchedEffect(controller, refreshGeneration) {
            if (controller == null) {
                loading = false
                loadError = "Saved-flow storage is unavailable in this context."
                return@LaunchedEffect
            }
            loading = true
            loadError = null
            try {
                savedFlows = withContext(Dispatchers.IO) { controller.listFlowDetails() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                loadError = failure.message ?: failure.toString()
            } finally {
                loading = false
            }
        }

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
                text = "Open a saved flow or start a new canvas.",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Saved flows",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                RefreshButton(enabled = !loading) { refreshGeneration++ }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading -> LauncherMessage("Loading saved flows…")
                    loadError != null -> LauncherMessage(
                        "Could not load saved flows: $loadError",
                        Color(0xFFE5935B),
                    )
                    savedFlows.isEmpty() -> LauncherMessage("No saved flows yet.")
                    else -> savedFlows.forEach { flow ->
                        SavedFlowRow(
                            flow = flow,
                            enabled = flow.readable && splitView != null,
                        ) {
                            val title = flow.name.ifBlank { "Flow" }
                            splitView?.openTab(FlowTabData(id = flow.tabId, title = title))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshButton(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = "Refresh saved flows",
            tint = if (enabled) Color(0xFFCDCDD4) else Color(0xFF66666F),
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun SavedFlowRow(flow: FlowSummary, enabled: Boolean, onClick: () -> Unit) {
    val title = flow.name.ifBlank { "Untitled Flow" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0xFF29292F) else Color(0xFF242429))
            .border(1.dp, Color(0xFF383840), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = if (flow.readable) title else "$title · unreadable",
            color = if (flow.readable) Color.White else Color(0xFFE5935B),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (flow.description.isNotBlank()) {
            Text(flow.description, color = Color(0xFFB0B0B8), fontSize = 11.sp)
        }
        Text(
            text = if (flow.readable) "${flow.nodeCount} node(s) · ${flow.tabId}" else flow.tabId,
            color = Color(0xFF7E7E88),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun LauncherMessage(text: String, color: Color = Color(0xFF8C8C96)) {
    Text(text = text, color = color, fontSize = 11.sp)
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
