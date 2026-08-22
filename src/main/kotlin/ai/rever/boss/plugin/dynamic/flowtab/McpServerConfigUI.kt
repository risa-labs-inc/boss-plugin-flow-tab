package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Minimal connection-manager UI for external MCP servers (P7). It edits the master
 * feature flag (OFF by default) and the per-server [McpServerConfig] list held by
 * [manager]. Each mutation is submitted to the manager-owned actor, which atomically
 * persists, reconciles, discovers, and publishes snapshots even if this composition is
 * later cancelled. Every Flow tab observes descriptor snapshots independently.
 *
 * Secrets are never entered/stored here as values — a server references a secret by its
 * logical *name* ([McpServerConfig.secretRef]), resolved from the host vault at connect
 * time. Deliberately compact and consistent with [FlowTheme].
 */
@Composable
fun McpServerConfigPanel(
    manager: ExternalMcpManager,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var pendingOperations by remember { mutableStateOf(0) }
    val busy = pendingOperations > 0
    var operationError by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<McpServerConfig>>(emptyList()) }
    val statuses by manager.serverStatuses.collectAsState()

    // New-server form fields.
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(McpTransportKind.STDIO) }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var secretRef by remember { mutableStateOf("") }

    suspend fun reload() {
        enabled = manager.settingsEnabled()
        servers = manager.listConfigs()
    }

    suspend fun reloadSafely() {
        try {
            reload()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            operationError = boundedExternalMcpDiagnostic(null, fallback = "Could not reload external MCP settings")
        } catch (_: Exception) {
            operationError = boundedExternalMcpDiagnostic(null, fallback = "Could not reload external MCP settings")
        }
    }

    LaunchedEffect(manager) {
        manager.changeTick.collect { reloadSafely() }
    }

    fun <T> mutate(
        submit: () -> Deferred<T>,
        allowWhileBusy: Boolean = false,
        onResult: (T) -> Unit = {},
    ) {
        if (busy && !allowWhileBusy) return
        pendingOperations += 1
        operationError = null
        val submittedAtTick = manager.changeTick.value
        val request = submit()
        scope.launch {
            try {
                onResult(request.await())
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                operationError = boundedExternalMcpDiagnostic(cancelled.message)
            } catch (failure: Exception) {
                operationError = boundedExternalMcpDiagnostic(failure.message)
            } finally {
                try {
                    // A settled reconcile publishes a tick and the panel collector owns
                    // that reload. Only fall back to a direct reload when the request was
                    // rejected/no-op/failed before publishing one.
                    if (manager.changeTick.value == submittedAtTick) reloadSafely()
                } finally {
                    pendingOperations = (pendingOperations - 1).coerceAtLeast(0)
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(FlowTheme.Surface)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "External MCP servers",
                color = FlowTheme.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Pill("Refresh / retry", enabledLook = true) {
                mutate({ manager.requestRefresh() }, allowWhileBusy = true)
            }
        }
        if (busy) {
            Text("Updating connections…", color = FlowTheme.TextMuted, fontSize = 11.sp)
        }
        operationError?.let { message ->
            Text(message, color = FlowTheme.Error, fontSize = 11.sp)
        }

        // Master feature flag.
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { on -> mutate({ manager.requestSetSettingsEnabled(on) }) },
                enabled = !busy,
                colors = SwitchDefaults.colors(checkedThumbColor = FlowTheme.Primary),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Enable external MCP", color = FlowTheme.TextPrimary, fontSize = 13.sp)
                Text(
                    "Off by default. Connects to third-party MCP servers (stdio + HTTP/SSE).",
                    color = FlowTheme.TextFaint, fontSize = 11.sp,
                )
            }
        }

        Divider()

        // Configured servers.
        if (servers.isEmpty()) {
            Text("No servers configured.", color = FlowTheme.TextFaint, fontSize = 12.sp)
        }
        servers.forEach { cfg ->
            ServerRow(
                cfg = cfg,
                status = statuses[cfg.name],
                toggleEnabled = !busy,
                removeEnabled = true,
                onToggle = { on ->
                    mutate({ manager.requestSetConfigEnabled(cfg.name, on) }) { found ->
                        if (!found) {
                            operationError = "That server was removed in another tab."
                        }
                    }
                },
                onRemove = {
                    mutate(
                        submit = { manager.requestRemoveConfig(cfg.name) },
                        allowWhileBusy = true,
                    ) { removed ->
                        if (!removed) operationError = "That server was already removed."
                    }
                },
            )
        }

        Divider()

        // Add a server.
        Text("Add a server", color = FlowTheme.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Field("Name", name) { name = it }
        KindToggle(kind) { kind = it }
        if (kind == McpTransportKind.STDIO) {
            Field("Command (npx, uvx, node…)", command) { command = it }
            Field("Args (space-separated)", args) { args = it }
            Field("Working directory (optional)", workingDirectory) { workingDirectory = it }
        } else {
            Field("URL", url) { url = it }
            Field("Secret name (optional, HTTP/SSE only)", secretRef) { secretRef = it }
        }
        val draft = McpServerDraft(
            name = name,
            kind = kind,
            command = command,
            args = args,
            url = url,
            secretRef = secretRef,
            workingDirectory = workingDirectory,
        )
        val newConfig = draft.toConfigOrNull()
        val invalidServerName = name.isNotBlank() && normalizedExternalMcpServerName(name) == null
        val duplicateName = newConfig != null && servers.any { it.name == newConfig.name }
        if (invalidServerName) {
            Text("Server name cannot contain '/' or control characters.", color = FlowTheme.Error, fontSize = 11.sp)
        }
        if (duplicateName) {
            Text("A server named '${newConfig.name}' already exists.", color = FlowTheme.Error, fontSize = 11.sp)
        }
        Pill("Add server", enabledLook = newConfig != null && !duplicateName && !busy) {
            val cfg = newConfig ?: return@Pill
            mutate({ manager.requestAddConfig(cfg) }) { added ->
                if (!added) {
                    operationError = "A server named '${cfg.name}' already exists."
                } else {
                    name = ""; command = ""; args = ""; workingDirectory = ""; url = ""; secretRef = ""
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    cfg: McpServerConfig,
    status: ExternalMcpServerStatus?,
    toggleEnabled: Boolean,
    removeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(FlowTheme.rMd))
            .background(FlowTheme.Canvas)
            .border(1.dp, FlowTheme.Border, RoundedCornerShape(FlowTheme.rMd))
            .padding(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(cfg.name, color = FlowTheme.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            val detail = if (cfg.kind == McpTransportKind.STDIO) "stdio · ${cfg.command}" else "sse · ${cfg.url}"
            Text(detail, color = FlowTheme.TextFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            val state = status?.state ?: ExternalMcpServerState.DISCONNECTED
            val stateText = when (state) {
                ExternalMcpServerState.DISCONNECTED -> status?.detail ?: "Disconnected"
                ExternalMcpServerState.CONNECTING -> "Connecting…"
                ExternalMcpServerState.CONNECTED -> "Connected"
                ExternalMcpServerState.ERROR -> status?.detail ?: "Connection failed"
            }
            val stateColor = when (state) {
                ExternalMcpServerState.CONNECTED -> FlowTheme.Success
                ExternalMcpServerState.ERROR -> FlowTheme.Error
                else -> FlowTheme.TextMuted
            }
            Text(stateText, color = stateColor, fontSize = 11.sp, maxLines = 2)
        }
        Switch(
            checked = cfg.enabled, onCheckedChange = onToggle,
            enabled = toggleEnabled,
            colors = SwitchDefaults.colors(checkedThumbColor = FlowTheme.Success),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Remove", color = if (removeEnabled) FlowTheme.Error else FlowTheme.TextFaint, fontSize = 12.sp,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(enabled = removeEnabled) { onRemove() },
        )
    }
}

@Composable
private fun KindToggle(kind: McpTransportKind, onPick: (McpTransportKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        McpTransportKind.entries.forEach { k ->
            val on = k == kind
            Text(
                if (k == McpTransportKind.STDIO) "stdio" else "HTTP/SSE",
                color = if (on) FlowTheme.TextPrimary else FlowTheme.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(FlowTheme.rSm))
                    .background(if (on) FlowTheme.Primary.copy(alpha = 0.22f) else FlowTheme.Canvas)
                    .border(1.dp, if (on) FlowTheme.Primary else FlowTheme.Border, RoundedCornerShape(FlowTheme.rSm))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onPick(k) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = FlowTheme.TextFaint, fontSize = 11.sp)
        Spacer(Modifier.padding(top = 2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = FlowTheme.TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(FlowTheme.Accent),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(FlowTheme.rSm))
                .background(FlowTheme.Canvas)
                .border(1.dp, FlowTheme.Border, RoundedCornerShape(FlowTheme.rSm))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Pill(text: String, enabledLook: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabledLook) FlowTheme.TextPrimary else FlowTheme.TextFaint,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(if (enabledLook) FlowTheme.Primary.copy(alpha = 0.25f) else FlowTheme.Canvas)
            .border(1.dp, if (enabledLook) FlowTheme.Primary else FlowTheme.Border, RoundedCornerShape(FlowTheme.rSm))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabledLook, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Pure form-to-config conversion kept outside Compose so required transport fields
 * and secret-free persistence can be covered without a desktop UI harness. */
internal data class McpServerDraft(
    val name: String,
    val kind: McpTransportKind,
    val command: String,
    val args: String,
    val url: String,
    val secretRef: String,
    val workingDirectory: String = "",
) {
    fun toConfigOrNull(): McpServerConfig? {
        val normalizedName = normalizedExternalMcpServerName(name) ?: return null
        if (kind == McpTransportKind.STDIO && command.isBlank()) return null
        if (kind == McpTransportKind.HTTP_SSE && url.isBlank()) return null
        return McpServerConfig(
            name = normalizedName,
            kind = kind,
            command = if (kind == McpTransportKind.STDIO) command.trim() else "",
            args = if (kind == McpTransportKind.STDIO) {
                args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else {
                emptyList()
            },
            workingDirectory = if (kind == McpTransportKind.STDIO) workingDirectory.trim() else "",
            url = if (kind == McpTransportKind.HTTP_SSE) url.trim() else "",
            enabled = false,
            // Stdio child-process environment injection is not implemented; only the
            // HTTP/SSE transport consumes a resolved secret reference.
            secretRef = if (kind == McpTransportKind.HTTP_SSE) secretRef.trim().ifBlank { null } else null,
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(FlowTheme.Border))
}
