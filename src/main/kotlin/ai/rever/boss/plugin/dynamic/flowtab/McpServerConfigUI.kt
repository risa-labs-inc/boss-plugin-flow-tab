package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch

/**
 * Minimal connection-manager UI for external MCP servers (P7). It edits the master
 * feature flag (OFF by default) and the per-server [McpServerConfig] list held by
 * [manager]; every mutation persists through the manager (config JSON at
 * [ExternalMcpManager.CONFIG_KEY], flag in [SettingsStore]) and then calls
 * [ExternalMcpManager.refresh] to reconcile live connections.
 *
 * Secrets are never entered/stored here as values — a server references a secret by its
 * logical *name* ([McpServerConfig.secretRef]), resolved from the host vault at connect
 * time. Deliberately compact and consistent with [FlowTheme]; not unit-tested (UI).
 */
@Composable
fun McpServerConfigPanel(
    manager: ExternalMcpManager,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    val servers = remember { mutableStateListOf<McpServerConfig>() }

    // New-server form fields.
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(McpTransportKind.STDIO) }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var secretRef by remember { mutableStateOf("") }

    suspend fun reload() {
        enabled = manager.settingsEnabled()
        servers.clear()
        servers.addAll(manager.listConfigs())
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier
            .fillMaxWidth()
            .background(FlowTheme.Surface)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("External MCP servers", color = FlowTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

        // Master feature flag.
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    scope.launch { manager.setSettingsEnabled(on); manager.refresh(); reload(); onChanged() }
                },
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
                onToggle = { on ->
                    scope.launch { manager.upsertConfig(cfg.copy(enabled = on)); manager.refresh(); reload(); onChanged() }
                },
                onRemove = {
                    scope.launch { manager.removeConfig(cfg.name); manager.refresh(); reload(); onChanged() }
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
        } else {
            Field("URL", url) { url = it }
        }
        Field("Secret name (optional)", secretRef) { secretRef = it }
        Pill("Add server", enabledLook = name.isNotBlank()) {
            if (name.isBlank()) return@Pill
            val cfg = McpServerConfig(
                name = name.trim(),
                kind = kind,
                command = command.trim(),
                args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                url = url.trim(),
                enabled = false,
                secretRef = secretRef.trim().ifBlank { null },
            )
            scope.launch {
                manager.upsertConfig(cfg); reload(); onChanged()
                name = ""; command = ""; args = ""; url = ""; secretRef = ""
            }
        }
    }
}

@Composable
private fun ServerRow(cfg: McpServerConfig, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
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
        }
        Switch(
            checked = cfg.enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = FlowTheme.Success),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Remove", color = FlowTheme.Error, fontSize = 12.sp,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onRemove() },
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
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(FlowTheme.Border))
}
