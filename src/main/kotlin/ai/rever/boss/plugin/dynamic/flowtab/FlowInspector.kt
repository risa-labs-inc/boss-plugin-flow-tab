package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val PanelBg = FlowTheme.Surface
private val PanelBorder = FlowTheme.Border
private val FieldBg = FlowTheme.Canvas
private val Muted = FlowTheme.TextFaint
private val prettyJson = Json { prettyPrint = true; isLenient = true }

/** Replace one config field on [node], preserving the rest. */
private fun setConfig(node: FlowNode, key: String, value: String) {
    node.config = JsonObject(node.config + (key to JsonPrimitive(value)))
}

private fun configValue(node: FlowNode, field: ConfigField): String =
    (node.config[field.key] as? JsonPrimitive)?.content ?: field.default

/**
 * Right-side inspector for the selected node: edit its title + config fields
 * (Parameters), edit raw config JSON, and view the last run's output + logs.
 */
@Composable
fun FlowInspector(state: FlowGraphState, node: FlowNode, modifier: Modifier = Modifier) {
    // Default to the Output tab once a node has run, so its extracted data (or error)
    // is the first thing shown; otherwise start on Parameters.
    var tab by remember(node.id) { mutableStateOf(if (state.runStates[node.id] != null) 2 else 0) } // 0 params, 1 json, 2 output
    val accent = Color(node.type.accent)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(PanelBg)
            .border(1.dp, PanelBorder)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header: icon + type + close
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(24.dp).clip(RoundedCornerShape(FlowTheme.rSm)).background(accent),
                contentAlignment = Alignment.Center
            ) { Icon(node.type.icon(), null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(node.type.label, color = FlowTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // Zoom-independent delete; spaced + hover-highlighted so it isn't a
            // misclick trap next to Close.
            IconBtn(Icons.Filled.DeleteOutline, "Delete node", FlowTheme.Error) { state.removeNode(node.id) }
            Spacer(Modifier.width(6.dp))
            IconBtn(Icons.Filled.Close, "Close", Muted) { state.selection = null }
        }

        // Run status of this node — the first thing you want when a flow finishes.
        StatusBanner(state.runStates[node.id])

        // Title editor
        FieldLabel("Name")
        TextInput(value = node.title, singleLine = true) { node.title = it }

        // Tabs
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabChip("Parameters", tab == 0) { tab = 0 }
            TabChip("JSON", tab == 1) { tab = 1 }
            TabChip("Output", tab == 2) { tab = 2 }
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> ParametersTab(node)
                1 -> JsonTab(node)
                else -> OutputTab(state, node)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametersTab(node: FlowNode) {
    val fields = node.type.configFields()
    if (fields.isEmpty()) {
        Text("This node has no parameters.", color = Muted, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (field in fields) {
            FieldLabel(field.label)
            when (field.type) {
                FieldType.TEXT -> TextInput(configValue(node, field), placeholder = field.placeholder, singleLine = true) {
                    setConfig(node, field.key, it)
                }
                FieldType.TEXTAREA -> TextInput(configValue(node, field), placeholder = field.placeholder, singleLine = false) {
                    setConfig(node, field.key, it)
                }
                FieldType.SELECT -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val current = configValue(node, field).ifEmpty { field.default }
                    for (opt in field.options) {
                        SelectChip(opt, opt == current) { setConfig(node, field.key, opt) }
                    }
                }
                FieldType.BOOL -> {
                    val on = configValue(node, field).equals("true", true)
                    SelectChip(if (on) "On" else "Off", on) { setConfig(node, field.key, (!on).toString()) }
                }
            }
        }
    }
}

@Composable
private fun JsonTab(node: FlowNode) {
    var text by remember(node.id) { mutableStateOf(prettyJson.encodeToString(JsonObject.serializer(), node.config)) }
    var error by remember(node.id) { mutableStateOf<String?>(null) }
    FieldLabel("Config (raw JSON)")
    TextInput(text, singleLine = false, mono = true, error = error != null) {
        text = it
        error = runCatching {
            node.config = prettyJson.parseToJsonElement(it).let { e -> e as JsonObject }
            null
        }.getOrElse { ex -> ex.message ?: "invalid JSON" }
    }
    if (error != null) {
        Text(
            "⚠ Invalid JSON — changes not applied: ${error}",
            color = FlowTheme.Error,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun OutputTab(state: FlowGraphState, node: FlowNode) {
    val run = state.runStates[node.id]
    if (run == null) {
        Text("Run the flow to see this node's output.", color = Muted, fontSize = 12.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Status shown in the always-visible banner above; here we focus on detail.
        if (run.error != null) {
            FieldLabel("Error")
            Text(run.error, color = FlowTheme.Error, fontSize = 12.sp)
        }
        if (run.logs.isNotEmpty()) {
            FieldLabel("Logs")
            Text(run.logs.joinToString("\n"), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        FieldLabel("Output (${run.output.size} item${if (run.output.size == 1) "" else "s"})")
        val rendered = remember(run) {
            runCatching {
                prettyJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(JsonObject.serializer()),
                    run.output.map { it.json }
                )
            }.getOrDefault("[]")
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(FlowTheme.rSm)).background(FieldBg).padding(8.dp)) {
            SelectionContainer {
                Text(rendered, color = FlowTheme.TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * Always-visible run-status banner at the top of the inspector: idle / running /
 * success (+ item count) / error (+ message). Reads the reactive run map, so it
 * updates live while a flow executes.
 */
@Composable
private fun StatusBanner(run: NodeRun?) {
    val status = run?.status
    val color = runStatusColor(status) ?: FlowTheme.TextFaint
    val label = when (status) {
        RunStatus.RUNNING -> "Running…"
        RunStatus.SUCCESS -> "Success · ${run.output.size} item${if (run.output.size == 1) "" else "s"}"
        RunStatus.ERROR -> "Error"
        RunStatus.IDLE -> "Pending…"
        null -> "Not run yet"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(color.copy(alpha = if (status == null) 0.06f else 0.14f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = if (status == null) FlowTheme.TextMuted else color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        val err = run?.error
        if (status == RunStatus.ERROR && !err.isNullOrBlank()) {
            Text(
                err,
                color = FlowTheme.TextMuted,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 2.dp))
}

@Composable
private fun TextInput(
    value: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    mono: Boolean = false,
    error: Boolean = false,
    onChange: (String) -> Unit
) {
    Box(
        Modifier.fillMaxWidth()
            .let { if (singleLine) it else it.heightIn(min = 64.dp) }
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(FieldBg)
            .border(1.dp, if (error) FlowTheme.Error else PanelBorder, RoundedCornerShape(FlowTheme.rSm))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, color = FlowTheme.TextPlaceholder, fontSize = 12.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = FlowTheme.TextPrimary,
                fontSize = 12.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(FlowTheme.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Small square icon button with a hover highlight — used in the inspector header. */
@Composable
private fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(if (hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(15.dp)) }
}

/** View-switch tabs: solid fill. */
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(FlowTheme.rSm))
            .background(if (selected) FlowTheme.Primary else FlowTheme.NodeBody)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) Color.White else FlowTheme.TextMuted, fontSize = 12.sp)
    }
}

/** Value selector: outlined/tinted — visually distinct from the view tabs. */
@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(FlowTheme.rMd))
            .background(if (selected) FlowTheme.Primary.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) FlowTheme.Primary else PanelBorder, RoundedCornerShape(FlowTheme.rMd))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) FlowTheme.PrimaryTint else FlowTheme.TextMuted, fontSize = 12.sp)
    }
}
