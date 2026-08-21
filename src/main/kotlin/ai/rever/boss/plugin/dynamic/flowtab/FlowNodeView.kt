package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

// Shared palette for the canvas + nodes — all via FlowTheme tokens.
val CanvasBackground = FlowTheme.Canvas
private val NodeBody = FlowTheme.NodeBody
private val NodeBorder = FlowTheme.Border
private val NodeBorderHover = FlowTheme.BorderStrong
private val PortFill = FlowTheme.Canvas
private val LabelColor = FlowTheme.TextMuted

/** Keep the node name scannable before falling back to an ellipsis. */
private const val NODE_TITLE_MAX_LINES = 2

/** Generous radius (world units) for snapping a dragged wire to an input port. */
const val SNAP_RADIUS = PORT_RADIUS * 4f

/** Convert a world-space length to a [Dp] that renders as that many device px. */
@Composable
private fun Float.wdp(): Dp = (this / LocalDensity.current.density).dp

/** Icon shown in a node's header, keyed by registry kind-id. Unknown/dynamic kinds
 *  fall back to a neutral glyph so any spec renders. */
fun iconForKind(kind: String): ImageVector = when (kind) {
    "TRIGGER" -> Icons.Filled.Bolt
    "OPEN_BROWSER" -> Icons.Filled.Public
    "NAVIGATE" -> Icons.Filled.Navigation
    "AWAIT_LOGIN" -> Icons.AutoMirrored.Filled.Login
    "CLICK" -> Icons.Filled.TouchApp
    "TYPE" -> Icons.Filled.Keyboard
    "EXTRACT" -> Icons.Filled.Download
    "INJECT" -> Icons.Filled.Upload
    "HTTP" -> Icons.Filled.Language
    "CODE" -> Icons.Filled.Code
    "IF" -> Icons.AutoMirrored.Filled.CallSplit
    "SET" -> Icons.Filled.Tune
    "MERGE" -> Icons.AutoMirrored.Filled.MergeType
    // Dynamic tool nodes (host/external registry) share a wrench glyph.
    else -> if (kind.startsWith("tool:")) Icons.Filled.Build else Icons.Filled.Tune
}

/** Icon for a node spec (by its kind-id). */
fun NodeSpec.icon(): ImageVector = iconForKind(id)

/** Border/badge color for a node's current run status (null = idle). */
fun runStatusColor(status: RunStatus?): Color? = when (status) {
    RunStatus.RUNNING -> FlowTheme.Accent
    RunStatus.SUCCESS -> FlowTheme.Success
    RunStatus.SKIPPED -> FlowTheme.TextMuted
    RunStatus.ERROR -> FlowTheme.Error
    else -> null
}

/** Human-facing, one-based canvas position used by the node number badge. */
fun nodeNumberLabel(displayNumber: Int): String = "#${displayNumber.coerceAtLeast(1)}"

/**
 * One-line action sentence shown under a node's title. It describes intent rather
 * than dumping raw config, so cards remain understandable without exposing typed
 * values, credentials, request bodies, or assignment payloads on the canvas.
 */
fun nodeSummary(node: FlowNode): String {
    fun c(k: String) = (node.config[k] as? JsonPrimitive)?.content ?: ""
    fun configured(k: String): Boolean = when (val value = node.config[k]) {
        null, JsonNull -> false
        is JsonPrimitive -> value.content.isNotBlank()
        else -> true
    }
    fun withTarget(action: String, target: String, fallback: String): String =
        if (target.isBlank()) fallback else "$action ${target.trim()}"

    return when (node.kind) {
        "TRIGGER" -> "Starts this flow"
        "OPEN_BROWSER" -> withTarget("Opens", c("url"), "Opens a browser session")
        "NAVIGATE" -> withTarget("Navigates to", c("url"), "Navigates to a URL")
        "AWAIT_LOGIN" -> withTarget("Waits for sign-in marker", c("selector"), "Waits for a human to sign in")
        "CLICK" -> withTarget("Clicks", c("selector"), "Clicks a page element")
        "TYPE" -> withTarget("Types into", c("selector"), "Types text into a page field")
        "EXTRACT" -> {
            val mode = c("mode").ifBlank { "text" }
            withTarget("Extracts $mode from", c("selector"), "Extracts $mode from the page")
        }
        "INJECT" -> c("waitFor").let { waitFor ->
            if (waitFor.isBlank()) {
                "Runs custom JavaScript in the page"
            } else {
                "Waits for ${waitFor.trim()}, then runs JavaScript"
            }
        }
        "HTTP" -> {
            val method = c("method").ifBlank { "GET" }.uppercase()
            withTarget("$method request to", c("url"), "Sends a $method request")
        }
        "SET" -> "Sets or updates item fields"
        "CODE" -> "Transforms each input item"
        "IF" -> withTarget("Branches when", c("condition"), "Routes items by a condition")
        "MERGE" -> "Combines both input branches"
        AgentNode.KIND -> if (configured(AgentNode.OUTPUT_SCHEMA_KEY)) {
            "Runs an AI agent and returns structured data"
        } else {
            "Runs an AI agent with approved tools"
        }
        LanagerNode.KIND -> withTarget("Runs sub-flow", c(LanagerNode.FLOW_ID_KEY), "Runs another flow")
        else -> node.spec.description.ifBlank { "Runs ${node.spec.label}" }
    }
}

/** Small at-a-glance metadata chips for a node (selector kind, method, …). */
fun nodeMetaChips(node: FlowNode): List<String> {
    fun c(k: String) = (node.config[k] as? JsonPrimitive)?.content ?: ""
    fun valueSource(value: String): String = when {
        value.isBlank() -> "no value"
        "\$secret." in value -> "secret value"
        "{{" in value -> "dynamic value"
        else -> "fixed value"
    }
    fun customWaitChip(default: Int = ELEMENT_WAIT_MS): String? {
        val configured = c("waitMs").toIntOrNull() ?: return null
        val bounded = configured.coerceIn(0, MAX_ELEMENT_WAIT_MS)
        return if (bounded == default) null else "${bounded}ms wait"
    }
    return when (node.kind) {
        "OPEN_BROWSER" -> listOf(if (c("headless").equals("true", true)) "headless" else "visible")
        "AWAIT_LOGIN" -> {
            val waitMs = c("waitMs").toIntOrNull()?.coerceIn(0, MAX_ELEMENT_WAIT_MS) ?: LOGIN_WAIT_MS
            listOf(c("selectorType").ifBlank { "css" }, "${waitMs}ms wait")
        }
        "HTTP" -> buildList {
            add(c("method").ifBlank { "GET" }.uppercase())
            if ("\$secret." in c("headers") || "\$secret." in c("body") || "\$secret." in c("url")) add("uses secret")
        }
        "CLICK" -> buildList {
            add(c("selectorType").ifBlank { "css" })
            customWaitChip()?.let(::add)
        }
        "TYPE" -> buildList {
            add(c("selectorType").ifBlank { "css" })
            add(valueSource(c("text")))
            customWaitChip()?.let(::add)
        }
        "INJECT" -> {
            val waitFor = c("waitFor")
            if (waitFor.isBlank()) {
                listOf("runs immediately")
            } else {
                val waitMs = c("waitMs").toIntOrNull()?.coerceIn(0, MAX_ELEMENT_WAIT_MS) ?: ELEMENT_WAIT_MS
                listOf(c("waitForType").ifBlank { "css" }, "${waitMs}ms wait")
            }
        }
        "EXTRACT" -> buildList {
            add(c("selectorType").ifBlank { "css" })
            add(c("mode").ifBlank { "text" })
            if (c("multiple").equals("true", true)) add("all matches")
            if (c("optional").equals("true", true)) add("optional")
            customWaitChip()?.let(::add)
        }
        else -> emptyList()
    }
}

/**
 * A single node on the canvas: a colored-header card with input ports on the
 * left edge and output ports on the right edge.
 *
 * Positioned in screen space via an [offset] lambda (reads the reactive world
 * position without recomposing) and scaled by the canvas zoom via
 * [graphicsLayer]. Because the layer transform is applied *outside* the pointer
 * handlers, drag deltas arrive in world units regardless of zoom.
 */
@Composable
fun FlowNodeView(state: FlowGraphState, node: FlowNode, displayNumber: Int) {
    val selected = state.selection == Selection.Node(node.id)
    val accent = Color(node.spec.accent)
    val h = nodeHeight(node.spec)
    val corner = NODE_CORNER.wdp()

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val elevation by animateDpAsState(if (selected) 16.dp else if (hovered) 12.dp else 6.dp)

    val run = state.runStates[node.id]
    val status = run?.status

    // Border reflects run state (error > running > success); otherwise selection/hover.
    val borderColor = when (status) {
        RunStatus.ERROR -> FlowTheme.Error
        RunStatus.RUNNING -> FlowTheme.Accent
        RunStatus.SUCCESS -> FlowTheme.Success
        RunStatus.SKIPPED -> FlowTheme.TextMuted
        else -> when {
            selected -> accent
            hovered -> NodeBorderHover
            else -> NodeBorder
        }
    }
    val borderWidth = if (status != null || selected) 1.5.dp else 1.dp

    Box(
        modifier = Modifier
            .offset {
                val s = state.toScreen(Offset(node.x, node.y))
                IntOffset(s.x.roundToInt(), s.y.roundToInt())
            }
            .graphicsLayer {
                scaleX = state.scale
                scaleY = state.scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(width = nodeOuterWidth().wdp(), height = h.wdp())
            .hoverable(interaction)
            // Select + drag-to-move. Consuming the down keeps the canvas from panning.
            .pointerInput(node.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    down.consume()
                    state.selection = Selection.Node(node.id)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (state.moveNodeBy(node, delta)) {
                            change.consume()
                        }
                    }
                }
            }
    ) {
        // One neutral card. Accent color lives only in the icon tile — the rest is
        // a calm surface, the way commercial node canvases (n8n, Retool) read.
        Box(
            modifier = Modifier
                .offset(x = NODE_PAD.wdp())
                .size(width = NODE_WIDTH.wdp(), height = h.wdp())
                .shadow(elevation, RoundedCornerShape(corner), clip = false)
                .clip(RoundedCornerShape(corner))
                .background(NodeBody)
                .border(borderWidth, borderColor, RoundedCornerShape(corner))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12f.wdp(), vertical = 8f.wdp()),
                verticalAlignment = Alignment.Top,
            ) {
                // Colored icon tile.
                Box(
                    modifier = Modifier
                        .size(44f.wdp())
                        .clip(RoundedCornerShape(10f.wdp()))
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(node.spec.icon(), null, tint = Color.White, modifier = Modifier.size(22f.wdp()))
                }
                Spacer(Modifier.width(12f.wdp()))
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NodeNumberBadge(displayNumber, accent)
                        Spacer(Modifier.width(6f.wdp()))
                        Text(
                            text = node.spec.label.uppercase(),
                            color = FlowTheme.TextFaint,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (status != null) {
                            Spacer(Modifier.width(6f.wdp()))
                            StatusPill(status, run)
                        }
                    }
                    Spacer(Modifier.height(2f.wdp()))
                    Text(
                        text = node.title,
                        color = FlowTheme.TextPrimary,
                        fontSize = 16.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = NODE_TITLE_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Human-readable action sentence; never dumps sensitive value payloads.
                    Text(
                        text = nodeSummary(node),
                        color = FlowTheme.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(y = 1f.wdp())
                    )
                    // Metadata chips (selector kind, HTTP method, headless, …).
                    if (node.spec.hasMetaRow) {
                        Row(
                            modifier = Modifier.offset(y = 5f.wdp()),
                            horizontalArrangement = Arrangement.spacedBy(5f.wdp())
                        ) {
                            for (chip in nodeMetaChips(node)) MetaChip(chip)
                        }
                    }
                }
            }

            // Port-row labels for multi-port nodes (If → true/false, Merge → a/b).
            for (i in 0 until node.spec.inputs) {
                val label = node.spec.inputLabel(i)
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        color = LabelColor,
                        fontSize = 10.sp,
                        modifier = Modifier.offset(
                            x = 14f.wdp(),
                            y = (portRowY(i, node.spec.inputs, h) - 7f).wdp()
                        )
                    )
                }
            }
            for (i in 0 until node.spec.outputs) {
                val label = node.spec.outputLabel(i)
                if (label.isNotEmpty()) {
                    Text(
                        text = label,
                        color = LabelColor,
                        fontSize = 10.sp,
                        modifier = Modifier.offset(
                            x = (NODE_WIDTH - 46f).wdp(),
                            y = (portRowY(i, node.spec.outputs, h) - 7f).wdp()
                        )
                    )
                }
            }
        }

        // Input ports (drop targets) on the left card edge.
        for (i in 0 until node.spec.inputs) {
            val snapped = state.connectSnap == (node.id to i)
            PortDot(
                accent = accent,
                centerX = NODE_PAD,
                centerY = portRowY(i, node.spec.inputs, h),
                active = state.connecting || hovered,
                snapped = snapped
            )
        }

        // Output ports (drag sources) on the right card edge.
        for (i in 0 until node.spec.outputs) {
            PortDot(
                accent = accent,
                centerX = NODE_PAD + NODE_WIDTH,
                centerY = portRowY(i, node.spec.outputs, h),
                active = hovered,
                snapped = false,
                dragHandler = Modifier
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(node.id, i) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            state.connecting = true
                            state.connectSnap = null
                            state.pendingConnection =
                                PendingConnection(node.id, i, outputPortPos(node.x, node.y, i, node.spec))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    finishConnection(state)
                                    break
                                }
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    val pc = state.pendingConnection
                                    if (pc != null) {
                                        val next = pc.current + delta
                                        state.pendingConnection = pc.copy(current = next)
                                        state.connectSnap = state.findInputPortAt(next, SNAP_RADIUS)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
            )
        }
    }
}

/** Creation-order badge shown before the node type. */
@Composable
private fun NodeNumberBadge(number: Int, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5f.wdp()))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 5f.wdp(), vertical = 1f.wdp()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = nodeNumberLabel(number),
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Tiny metadata chip (selector kind, HTTP method, …) under a node's subtitle. */
@Composable
private fun MetaChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5f.wdp()))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 6f.wdp(), vertical = 2f.wdp()),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = FlowTheme.TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

/** Compact run-status chip shown at the right of the node's title row. */
@Composable
private fun StatusPill(status: RunStatus, run: NodeRun?) {
    val color = runStatusColor(status) ?: return
    val text = when (status) {
        RunStatus.RUNNING -> "running"
        RunStatus.SUCCESS -> "✓ ${run?.output?.size ?: 0}"
        RunStatus.SKIPPED -> "skipped"
        RunStatus.ERROR -> "✕ error"
        else -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6f.wdp()))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6f.wdp(), vertical = 3f.wdp()),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Drop or discard the in-progress wire; open the picker if dropped on empty canvas. */
private fun finishConnection(state: FlowGraphState) {
    val pc = state.pendingConnection
    if (pc != null) {
        val hit = state.findInputPortAt(pc.current, SNAP_RADIUS)
        if (hit != null) {
            state.connect(pc.fromNodeId, pc.fromPort, hit.first, hit.second)
        } else {
            // Dropped on empty canvas → offer to create a connected node there.
            state.pickerRequest = PickerRequest(
                screenAnchor = state.toScreen(pc.current),
                intent = PickerIntent.ConnectFrom(pc.fromNodeId, pc.fromPort, pc.current)
            )
        }
    }
    state.pendingConnection = null
    state.connecting = false
    state.connectSnap = null
}

/**
 * A port marker centered on a card edge. The interactive box is the port
 * margin (so it stays inside the node's bounds); the visible dot is drawn at
 * its center, which lands exactly on the card edge where edges attach.
 *
 * @param active grows/brightens the dot (node hovered, or a wire is in flight)
 * @param snapped the dragged wire would connect here — show a strong glow
 */
@Composable
private fun PortDot(
    accent: Color,
    centerX: Float,
    centerY: Float,
    active: Boolean,
    snapped: Boolean,
    dragHandler: Modifier = Modifier
) {
    val box = NODE_PAD * 2f
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val emphasized = snapped || hovered
    val scale by animateFloatAsState(if (snapped) 1.7f else if (hovered || active) 1.35f else 1f)

    Box(
        modifier = Modifier
            .offset(x = (centerX - NODE_PAD).wdp(), y = (centerY - NODE_PAD).wdp())
            .size(box.wdp())
            .hoverable(interaction)
            .then(dragHandler),
        contentAlignment = Alignment.Center
    ) {
        // Soft halo when this port is the active snap target.
        if (snapped) {
            Box(
                modifier = Modifier
                    .size((PORT_RADIUS * 3.4f).wdp())
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.25f))
            )
        }
        Box(
            modifier = Modifier
                .size((PORT_RADIUS * 2f).wdp())
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (emphasized) accent else PortFill)
                .border(2.dp, accent, CircleShape)
        )
    }
}
