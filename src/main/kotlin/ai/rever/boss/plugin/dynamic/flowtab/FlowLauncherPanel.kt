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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sidebar panel info for the Flow launcher. */
object FlowLauncherInfo : PanelInfo {
    override val id = PanelId("flow-launcher", 50)
    override val displayName = "Flow"
    override val icon = Icons.Outlined.AccountTree
    override val defaultSlotPosition = left.bottom

    override val sidebarItem: SidebarItem
        get() = SidebarItem(id, icon, displayName, onClick = null)
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
        var operationError by remember { mutableStateOf<String?>(null) }
        var creatingFlow by remember { mutableStateOf(false) }
        var refreshGeneration by remember { mutableIntStateOf(0) }
        var openingFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var deletingFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var pendingDelete by remember { mutableStateOf<FlowSummary?>(null) }
        var renamingFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var pendingRename by remember { mutableStateOf<FlowSummary?>(null) }
        var renameText by remember { mutableStateOf("") }
        var schedulingFlowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var pendingSchedule by remember { mutableStateOf<FlowSummary?>(null) }
        var scheduleMinutesText by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(controller, refreshGeneration) {
            val requestGeneration = refreshGeneration
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
                if (refreshGeneration == requestGeneration) loading = false
            }
        }

        // Scheduled rows are operational status, not static metadata. Refresh them
        // quietly while at least one cadence is enabled so last/next stays useful.
        LaunchedEffect(controller) {
            while (isActive) {
                delay(SCHEDULE_STATUS_REFRESH_MS)
                if (savedFlows.none { it.schedule != null }) continue
                try {
                    savedFlows = withContext(Dispatchers.IO) { controller?.listFlowDetails().orEmpty() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Manual Refresh owns visible load errors; a transient background
                    // refresh must not replace the last known-good launcher list.
                }
            }
        }

        // Canvas-originated renames publish through the same coordinator. Reflect them
        // immediately instead of leaving the saved-flow list stale until Refresh.
        LaunchedEffect(Unit) {
            FlowPersistenceCoordinator.names.collect { renamed ->
                if (renamed.isNotEmpty()) {
                    savedFlows = savedFlows.map { flow ->
                        renamed[flow.tabId]?.let { name -> flow.copy(name = name) } ?: flow
                    }
                }
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

            NewFlowButton(enabled = splitView != null && controller != null && !creatingFlow) {
                if (creatingFlow) return@NewFlowButton
                creatingFlow = true
                operationError = null
                scope.launch {
                    try {
                        val title = nextFlowName(savedFlows)
                        val tabId = withContext(Dispatchers.IO) {
                            val activeController = requireNotNull(controller)
                            activeController.createFlow(FlowMeta(name = title)).also { newId ->
                                activeController.addNode(newId, "TRIGGER")
                            }
                        }
                        splitView?.openTab(FlowTabData(id = tabId, title = title))
                        refreshGeneration++
                    } catch (failure: Exception) {
                        operationError = failure.message ?: failure.toString()
                    } finally {
                        creatingFlow = false
                    }
                }
            }

            if (splitView == null || controller == null) {
                Text(
                    text = "Flow creation is unavailable in this context.",
                    color = Color(0xFFE5935B),
                    fontSize = 11.sp
                )
            }
            if (context.activeTabsProvider == null) {
                Text(
                    text = "Opening saved flows is unavailable in this context.",
                    color = Color(0xFFE5935B),
                    fontSize = 11.sp,
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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    item { LauncherMessage(if (savedFlows.isEmpty()) "Loading saved flows…" else "Refreshing…") }
                }
                loadError?.let { error ->
                    item { LauncherMessage("Could not refresh saved flows: $error", Color(0xFFE5935B)) }
                }
                operationError?.let { error ->
                    item { LauncherMessage(error, Color(0xFFE5935B)) }
                }
                if (!loading && loadError == null && savedFlows.isEmpty()) {
                    item { LauncherMessage("No saved flows yet.") }
                }
                items(savedFlows, key = { it.tabId }) { flow ->
                    SavedFlowRow(
                        flow = flow,
                        openEnabled = flow.readable && splitView != null &&
                            context.activeTabsProvider != null && flow.tabId !in openingFlowIds,
                        deleteEnabled = controller != null && flow.tabId !in deletingFlowIds,
                        renameEnabled = flow.readable && controller != null && flow.tabId !in renamingFlowIds,
                        scheduleEnabled = flow.readable && controller != null && flow.tabId !in schedulingFlowIds,
                        onOpen = {
                            if (flow.tabId in openingFlowIds) return@SavedFlowRow
                            openingFlowIds += flow.tabId
                            operationError = null
                            scope.launch {
                                try {
                                    val activeTabs = requireNotNull(context.activeTabsProvider) {
                                        "Saved-flow opening is unavailable because active-tab discovery is unavailable."
                                    }
                                    // refreshTabs is a suspend contract: when it returns, value contains
                                    // the host's latest snapshot and is safe to sample synchronously.
                                    activeTabs.refreshTabs()
                                    val alreadyOpen = activeTabs.activeTabs.value.firstOrNull { it.tabId == flow.tabId }
                                    if (alreadyOpen != null) {
                                        activeTabs.selectTab(alreadyOpen.tabId, alreadyOpen.panelId)
                                    } else {
                                        val title = flow.name.ifBlank { "Untitled Flow" }
                                        splitView?.openTab(FlowTabData(id = flow.tabId, title = title))
                                    }
                                } catch (failure: Exception) {
                                    operationError = failure.message ?: failure.toString()
                                } finally {
                                    openingFlowIds -= flow.tabId
                                }
                            }
                        },
                        onRename = {
                            renameText = flow.name
                            pendingRename = flow
                        },
                        onSchedule = {
                            scheduleMinutesText = flow.schedule?.intervalMinutes?.toString().orEmpty()
                            pendingSchedule = flow
                        },
                        onDelete = { pendingDelete = flow },
                    )
                }
            }
        }

        pendingDelete?.let { flow ->
            val title = flow.name.ifBlank { flow.tabId }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete \"$title\"?") },
                text = { Text("This permanently removes the saved flow and closes it if it is open.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDelete = null
                            if (flow.tabId in deletingFlowIds) return@TextButton
                            deletingFlowIds += flow.tabId
                            operationError = null
                            scope.launch {
                                try {
                                    val deleted = withContext(Dispatchers.IO) {
                                        requireNotNull(controller).deleteFlow(flow.tabId)
                                    }
                                    if (!deleted) error("Flow '${flow.tabId}' no longer exists")
                                    savedFlows = savedFlows.filterNot { it.tabId == flow.tabId }
                                    refreshGeneration++
                                } catch (failure: Exception) {
                                    operationError = failure.message ?: failure.toString()
                                } finally {
                                    deletingFlowIds -= flow.tabId
                                }
                            }
                        },
                    ) {
                        Text("Delete", color = Color(0xFFE57373))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                },
            )
        }

        pendingRename?.let { flow ->
            AlertDialog(
                onDismissRequest = { pendingRename = null },
                title = { Text("Rename saved flow") },
                text = {
                    TextField(
                        value = renameText,
                        onValueChange = { renameText = it.take(FlowController.MAX_FLOW_NAME_LENGTH) },
                        label = { Text("Flow name") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = renameText.isNotBlank(),
                        onClick = {
                            val newName = renameText.trim()
                            pendingRename = null
                            if (flow.tabId in renamingFlowIds) return@TextButton
                            renamingFlowIds += flow.tabId
                            operationError = null
                            scope.launch {
                                try {
                                    val renamed = requireNotNull(controller).renameFlow(flow.tabId, newName)
                                    savedFlows = savedFlows.map { current ->
                                        if (current.tabId == flow.tabId) renamed else current
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    operationError = failure.message ?: failure.toString()
                                } finally {
                                    renamingFlowIds -= flow.tabId
                                }
                            }
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRename = null }) { Text("Cancel") }
                },
            )
        }

        pendingSchedule?.let { flow ->
            val minutes = scheduleMinutesText.toLongOrNull()
            val valid = minutes != null &&
                minutes in FlowController.MIN_SCHEDULE_INTERVAL_MINUTES..FlowController.MAX_SCHEDULE_INTERVAL_MINUTES
            AlertDialog(
                onDismissRequest = { pendingSchedule = null },
                title = { Text("Schedule ${flow.name.ifBlank { "flow" }}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Run this flow automatically at a fixed interval.")
                        TextField(
                            value = scheduleMinutesText,
                            onValueChange = { scheduleMinutesText = it.filter(Char::isDigit).take(8) },
                            label = { Text("Interval (minutes)") },
                            singleLine = true,
                        )
                        flowScheduleStatus(flow)?.let { status -> Text(status, fontSize = 11.sp) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            val selectedMinutes = requireNotNull(minutes)
                            pendingSchedule = null
                            if (flow.tabId in schedulingFlowIds) return@TextButton
                            schedulingFlowIds += flow.tabId
                            operationError = null
                            scope.launch {
                                try {
                                    val scheduled = requireNotNull(controller).updateSchedule(
                                        flow.tabId,
                                        selectedMinutes,
                                    )
                                    savedFlows = savedFlows.map { current ->
                                        if (current.tabId == flow.tabId) scheduled else current
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    operationError = failure.message ?: failure.toString()
                                } finally {
                                    schedulingFlowIds -= flow.tabId
                                }
                            }
                        },
                    ) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        if (flow.schedule != null) {
                            TextButton(
                                onClick = {
                                    pendingSchedule = null
                                    if (flow.tabId in schedulingFlowIds) return@TextButton
                                    schedulingFlowIds += flow.tabId
                                    operationError = null
                                    scope.launch {
                                        try {
                                            val disabled = requireNotNull(controller).updateSchedule(flow.tabId, null)
                                            savedFlows = savedFlows.map { current ->
                                                if (current.tabId == flow.tabId) disabled else current
                                            }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            operationError = failure.message ?: failure.toString()
                                        } finally {
                                            schedulingFlowIds -= flow.tabId
                                        }
                                    }
                                },
                            ) { Text("Disable") }
                        }
                        TextButton(onClick = { pendingSchedule = null }) { Text("Cancel") }
                    }
                },
            )
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
private fun SavedFlowRow(
    flow: FlowSummary,
    openEnabled: Boolean,
    deleteEnabled: Boolean,
    renameEnabled: Boolean,
    scheduleEnabled: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onSchedule: () -> Unit,
) {
    val title = flow.name.ifBlank { "Untitled Flow" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (openEnabled) Color(0xFF29292F) else Color(0xFF242429))
            .border(1.dp, Color(0xFF383840), RoundedCornerShape(8.dp))
            .clickable(enabled = openEnabled, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = if (flow.readable) title else "$title · unreadable",
                color = if (flow.readable) Color.White else Color(0xFFE5935B),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (flow.description.isNotBlank()) {
                Text(
                    flow.description,
                    color = Color(0xFFB0B0B8),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (flow.readable) {
                    "${flow.nodeCount} node(s) · ${flow.tabId.take(13)}…"
                } else {
                    flow.tabId
                },
                color = Color(0xFF7E7E88),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            flowScheduleStatus(flow)?.let { status ->
                Text(
                    text = status,
                    color = Color(0xFF8FB8E8),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column {
            IconButton(enabled = scheduleEnabled, onClick = onSchedule, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = "Schedule $title",
                    tint = if (scheduleEnabled) Color(0xFF8FB8E8) else Color(0xFF66666F),
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(enabled = renameEnabled, onClick = onRename, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Rename $title",
                    tint = if (renameEnabled) Color(0xFFB0B0B8) else Color(0xFF66666F),
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(enabled = deleteEnabled, onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete $title",
                    tint = if (deleteEnabled) Color(0xFFB0B0B8) else Color(0xFF66666F),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private val scheduleTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault())

internal fun flowScheduleStatus(flow: FlowSummary): String? {
    val schedule = flow.schedule ?: return null
    val unit = if (schedule.intervalMinutes == 1L) "minute" else "minutes"
    val last = flow.lastScheduledRunAtEpochMs?.let {
        scheduleTimeFormatter.format(Instant.ofEpochMilli(it)) +
            flow.lastScheduledRunState?.let { state -> " (${state.name.lowercase()})" }.orEmpty()
    } ?: "not run yet"
    val next = flow.nextScheduledRunAtEpochMs?.let {
        scheduleTimeFormatter.format(Instant.ofEpochMilli(it))
    } ?: "pending"
    return "Every ${schedule.intervalMinutes} $unit · Last: $last · Next: $next"
}

private const val SCHEDULE_STATUS_REFRESH_MS = 5_000L

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

internal fun nextFlowName(flows: List<FlowSummary>): String {
    val names = flows.mapTo(mutableSetOf()) { it.name }
    return generateSequence(1) { it + 1 }
        .map { "Flow $it" }
        .first { it !in names }
}
