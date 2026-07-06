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
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Bolt
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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

// Shared palette for the canvas + nodes — all via FlowTheme tokens.
val CanvasBackground = FlowTheme.Canvas
private val NodeBody = FlowTheme.NodeBody
private val NodeBorder = FlowTheme.Border
private val NodeBorderHover = FlowTheme.BorderStrong
private val PortFill = FlowTheme.Canvas
private val LabelColor = FlowTheme.TextMuted

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
    "CLICK" -> Icons.Filled.TouchApp
    "TYPE" -> Icons.Filled.Keyboard
    "EXTRACT" -> Icons.Filled.Download
    "INJECT" -> Icons.Filled.Upload
    "HTTP" -> Icons.Filled.Language
    "CODE" -> Icons.Filled.Code
    "IF" -> Icons.AutoMirrored.Filled.CallSplit
    "SET" -> Icons.Filled.Tune
    "MERGE" -> Icons.AutoMirrored.Filled.MergeType
    else -> Icons.Filled.Tune
}

/** Icon for a node spec (by its kind-id). */
fun NodeSpec.icon(): ImageVector = iconForKind(id)

/** Border/badge color for a node's current run status (null = idle). */
fun runStatusColor(status: RunStatus?): Color? = when (status) {
    RunStatus.RUNNING -> FlowTheme.Accent
    RunStatus.SUCCESS -> FlowTheme.Success
    RunStatus.ERROR -> FlowTheme.Error
    else -> null
}

/** One-line summary of a node's key config, shown under its title. */
fun nodeSummary(node: FlowNode): String {
    fun c(k: String) = (node.config[k] as? JsonPrimitive)?.content ?: ""
    val raw = when (node.kind) {
        "OPEN_BROWSER" -> c("url").ifBlank { "ephemeral session" }
        "NAVIGATE" -> c("url")
        // selector kind / method / mode move to the meta chip row below.
        "CLICK", "TYPE", "EXTRACT" -> c("selector")
        "HTTP" -> c("url")
        "SET" -> c("assignments")
        "INJECT" -> c("script")
        else -> ""
    }.trim()
    // Let the Text's maxLines=1 + ellipsis own truncation (no manual double-cut).
    return raw
}

/** Small at-a-glance metadata chips for a node (selector kind, method, …). */
fun nodeMetaChips(node: FlowNode): List<String> {
    fun c(k: String) = (node.config[k] as? JsonPrimitive)?.content ?: ""
    return when (node.kind) {
        "OPEN_BROWSER" -> listOf(if (c("headless").equals("true", true)) "headless" else "visible")
        "HTTP" -> listOf(c("method").ifBlank { "GET" })
        "CLICK", "TYPE" -> listOf(c("selectorType").ifBlank { "css" })
        "EXTRACT" -> buildList {
            add(c("selectorType").ifBlank { "css" })
            add(c("mode").ifBlank { "text" })
            if (c("multiple").equals("true", true)) add("all matches")
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
fun FlowNodeView(state: FlowGraphState, node: FlowNode) {
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
                        if (delta != Offset.Zero) {
                            node.x += delta.x
                            node.y += delta.y
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored icon tile.
                Box(
                    modifier = Modifier
                        .size(40f.wdp())
                        .clip(RoundedCornerShape(10f.wdp()))
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(node.spec.icon(), null, tint = Color.White, modifier = Modifier.size(21f.wdp()))
                }
                Spacer(Modifier.width(11f.wdp()))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = node.title,
                        color = FlowTheme.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Config preview, falling back to the type's description so the
                    // second line is never empty (no lonely-title look).
                    val subtitle = nodeSummary(node).ifBlank { node.spec.description }
                    Text(
                        text = subtitle,
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
                if (status != null) {
                    Spacer(Modifier.width(8f.wdp()))
                    StatusPill(status, run)
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
