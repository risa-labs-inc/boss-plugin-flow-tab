package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val GRID = 32f
private const val ZOOM_STEP = 1.1f

private val EdgeColor = FlowTheme.TextFaint
private val EdgeHighlight = FlowTheme.Accent

/**
 * The infinite, pannable / zoomable flow canvas. Renders, bottom to top:
 *  1. a dot grid, 2. all edges + any in-progress wire, 3. the node composables,
 *  4. hover affordances (edge ×/+ buttons), 5. the add button + empty state,
 *  6. the node picker popup.
 */
@Composable
fun FlowCanvas(
    state: FlowGraphState,
    modifier: Modifier = Modifier,
    onViewportSize: (Size) -> Unit = {}
) {
    var viewport by remember { mutableStateOf(Size.Zero) }

    fun openPicker(intent: PickerIntent, anchor: Offset) {
        state.pickerRequest = PickerRequest(anchor, intent)
    }

    fun viewCenterWorld(): Offset =
        if (viewport.width > 0f) state.toWorld(Offset(viewport.width / 2f, viewport.height / 2f))
        else Offset(320f, 220f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasBackground)
            .pointerHoverIcon(if (state.hoveredEdgeId != null) PointerIcon.Hand else PointerIcon.Default)
            .clipToBounds()
            .onSizeChanged {
                viewport = Size(it.width.toFloat(), it.height.toFloat())
                onViewportSize(viewport)
            }
            // Scroll = zoom, Move = edge hover, Exit = clear hover.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val change = event.changes.firstOrNull() ?: continue
                                val sy = change.scrollDelta.y
                                // Don't zoom while the picker is open (it has its own
                                // scrollable list) or if a child already used the scroll.
                                if (sy != 0f && state.pickerRequest == null && !change.isConsumed) {
                                    val factor = if (sy < 0f) ZOOM_STEP else 1f / ZOOM_STEP
                                    state.zoomBy(factor, change.position)
                                    change.consume()
                                }
                            }
                            PointerEventType.Move -> {
                                val pos = event.changes.firstOrNull()?.position
                                if (state.connecting || pos == null) {
                                    state.hoveredEdgeId = null
                                } else {
                                    // Keep the active edge alive while the cursor is over
                                    // its action bar (a disc around the edge midpoint), so
                                    // moving from the thin wire to the buttons doesn't drop
                                    // the menu out from under the pointer.
                                    val keepalive = 46.dp.toPx()
                                    val nearButtons = state.hoveredEdgeId?.let { id ->
                                        state.edges.firstOrNull { it.id == id }
                                            ?.let { state.edgeMidpoint(it) }
                                            ?.let { (state.toScreen(it) - pos).getDistance() <= keepalive }
                                    } ?: false
                                    if (!nearButtons) {
                                        state.hoveredEdgeId = state.findEdgeNear(state.toWorld(pos), threshold = 22f)
                                    }
                                }
                            }
                            PointerEventType.Exit -> state.hoveredEdgeId = null
                            else -> {}
                        }
                    }
                }
            }
            // Pan on background drag; tap selects an edge or clears.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val downPos = down.position
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!dragged) {
                                val world = state.toWorld(downPos)
                                state.selection = state.findEdgeNear(world, threshold = 14f)?.let { Selection.Edge(it) }
                            }
                            break
                        }
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            dragged = true
                            state.panOffset += delta
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) { drawGrid(state.panOffset, state.scale) }
        Canvas(Modifier.fillMaxSize()) { drawEdges(state) }

        // Observe the run-time repaint pulse: while a run is in progress this is
        // bumped on a timer, forcing this subtree to recompose so live node status
        // (written from the run's background thread) reaches a frame even when a
        // visible browser pane is otherwise idle. No-op when not running.
        @Suppress("UNUSED_EXPRESSION") state.repaintTick

        state.nodes.forEach { node ->
            key(node.id) { FlowNodeView(state, node) }
        }

        EdgeActions(state)

        // Add-node button (bottom-left).
        AddButton(
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            onClick = { openPicker(PickerIntent.AddAt(viewCenterWorld()), Offset(72f, viewport.height - 72f)) }
        )

        if (state.nodes.isEmpty()) {
            EmptyState(
                modifier = Modifier.align(Alignment.Center),
                onAdd = { openPicker(PickerIntent.AddAt(viewCenterWorld()), Offset(viewport.width / 2f, viewport.height / 2f)) }
            )
        } else {
            Text(
                text = "Drag a node's right port onto another node to connect, or drop on empty space to add a connected node. Scroll to zoom · Delete to remove.",
                color = FlowTheme.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }

        FlowNodePicker(state)
    }
}

/**
 * Floating action bar shown at the midpoint of the active (hovered or selected)
 * edge: insert a node on the wire, or delete the wire. A single contiguous,
 * shadowed bar — easier to land on than two separate circles, and the canvas
 * keeps the edge "alive" while the cursor is anywhere near it (see the Move
 * handler's keepalive disc).
 */
@Composable
private fun EdgeActions(state: FlowGraphState) {
    val activeId = (state.selection as? Selection.Edge)?.id ?: state.hoveredEdgeId ?: return
    val edge = state.edges.firstOrNull { it.id == activeId } ?: return
    val midWorld = state.edgeMidpoint(edge) ?: return
    val mid = state.toScreen(midWorld)
    val density = LocalDensity.current.density
    // Bar is 69x30 dp (two 34dp cells + a 1dp divider); center it on the midpoint.
    val halfW = (69f * density) / 2f
    val halfH = (30f * density) / 2f

    // Belt-and-suspenders with the Move keepalive: hovering the bar re-asserts
    // the active edge so it can't blink out.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) state.hoveredEdgeId = edge.id }

    Row(
        modifier = Modifier
            .offset { IntOffset((mid.x - halfW).roundToInt(), (mid.y - halfH).roundToInt()) }
            .shadow(6.dp, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp))
            .background(FlowTheme.Surface)
            .border(1.dp, FlowTheme.Border, RoundedCornerShape(9.dp))
            .hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EdgeActionCell(Icons.Filled.Add, "Insert node", FlowTheme.PrimaryTint, FlowTheme.PrimaryTint) {
            state.pickerRequest = PickerRequest(mid, PickerIntent.InsertOnEdge(edge.id, midWorld))
        }
        Box(Modifier.width(1.dp).height(20.dp).background(FlowTheme.Border))
        EdgeActionCell(Icons.Filled.Close, "Delete edge", FlowTheme.TextMuted, FlowTheme.Error) {
            state.removeEdge(edge.id)
        }
    }
}

/** One cell of the edge action bar: icon, hover highlight, tint that shifts on hover. */
@Composable
private fun EdgeActionCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    hoverTint: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 30.dp)
            .background(if (hovered) Color.White.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = if (hovered) hoverTint else tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AddButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(FlowTheme.Primary)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Add node", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Build your flow", color = FlowTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Add a node to get started.", color = FlowTheme.TextFaint, fontSize = 13.sp)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(FlowTheme.rMd))
                .background(FlowTheme.Primary)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add first node", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun DrawScope.drawGrid(pan: Offset, scale: Float) {
    val spacing = GRID * scale
    if (spacing < 6f) return
    val startX = pan.x.mod(spacing)
    val startY = pan.y.mod(spacing)
    val dotColor = Color.White.copy(alpha = 0.05f)
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(dotColor, radius = 1.25f, center = Offset(x, y))
            y += spacing
        }
        x += spacing
    }
}

private fun DrawScope.drawEdges(state: FlowGraphState) {
    for (edge in state.edges) {
        val ends = state.edgeEndpoints(edge) ?: continue
        val active = state.selection == Selection.Edge(edge.id) || state.hoveredEdgeId == edge.id
        val color = if (active) EdgeHighlight else EdgeColor
        val width = if (active) 3.5f else 2f
        drawEdgePath(state, ends.first, ends.second, color, width, arrow = true)
    }

    val pending = state.pendingConnection
    if (pending != null) {
        val from = state.nodeById(pending.fromNodeId)
        if (from != null) {
            val startWorld = outputPortPos(from.x, from.y, pending.fromPort, from.type)
            // Snap the loose end onto the candidate input port, if any.
            val snap = state.connectSnap
            val endWorld = if (snap != null) {
                state.nodeById(snap.first)?.let { inputPortPos(it.x, it.y, snap.second, it.type) } ?: pending.current
            } else pending.current
            val color = if (snap != null) EdgeHighlight else Color(from.type.accent).copy(alpha = 0.9f)
            drawEdgePath(state, startWorld, endWorld, color, if (snap != null) 3f else 2f)
            drawCircle(color, radius = if (snap != null) 5f else 4f, center = state.toScreen(endWorld))
        }
    }
}

private fun DrawScope.drawEdgePath(
    state: FlowGraphState,
    startWorld: Offset,
    endWorld: Offset,
    color: Color,
    width: Float,
    arrow: Boolean = false
) {
    val (c1w, c2w) = edgeControlPoints(startWorld, endWorld)
    val start = state.toScreen(startWorld)
    val c1 = state.toScreen(c1w)
    val c2 = state.toScreen(c2w)
    val end = state.toScreen(endWorld)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))

    if (arrow) {
        // Arrowhead at the target end, pointing along the curve's tangent (c2 → end).
        val dx = end.x - c2.x
        val dy = end.y - c2.y
        val len = hypot(dx, dy)
        if (len > 0.01f) {
            val ux = dx / len
            val uy = dy / len
            val size = 9f
            val baseX = end.x - ux * size
            val baseY = end.y - uy * size
            val half = size * 0.6f
            val head = Path().apply {
                moveTo(end.x, end.y)
                lineTo(baseX - uy * half, baseY + ux * half)
                lineTo(baseX + uy * half, baseY - ux * half)
                close()
            }
            drawPath(head, color)
        }
    }
}
