package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A live, mutable node on the canvas. Position + config are reactive.
 *
 * Holds its resolved [spec] (from the graph's [NodeRegistry]) rather than a closed
 * [NodeType] enum — geometry, rendering, and dispatch all read the spec. [kind] is
 * the spec's stable id, written back on save. An unknown/absent kind resolves to a
 * [NodeSpec.unavailable] placeholder so the node still lays out and round-trips.
 */
class FlowNode(
    val id: String,
    val spec: NodeSpec,
    title: String,
    x: Float,
    y: Float,
    config: JsonObject = JsonObject(emptyMap())
) {
    /** Stable registry kind-id (persisted as [NodeModel.type]). */
    val kind: String get() = spec.id

    var title by mutableStateOf(title)
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var config by mutableStateOf(config)
}

/** What is currently selected on the canvas. */
sealed interface Selection {
    data class Node(val id: String) : Selection
    data class Edge(val id: String) : Selection
}

/** An in-progress connection being dragged out from an output port. */
data class PendingConnection(
    val fromNodeId: String,
    val fromPort: Int,
    val current: Offset // world coordinates of the dragging end
)

/** Registry kind-id of the built-in browser-opening node (used by the headless toggle). */
private const val OPEN_BROWSER_KIND = "OPEN_BROWSER"

/** What the node picker should do once a type is chosen. */
sealed interface PickerIntent {
    /** Drop a free-standing node at [world]. */
    data class AddAt(val world: Offset) : PickerIntent
    /** Create a node at [world] and wire [fromNode]'s output [fromPort] into it. */
    data class ConnectFrom(val fromNode: String, val fromPort: Int, val world: Offset) : PickerIntent
    /** Splice a node into [edgeId] at [world]. */
    data class InsertOnEdge(val edgeId: String, val world: Offset) : PickerIntent
}

/** A request to show the searchable node picker, anchored at a screen position. */
data class PickerRequest(val screenAnchor: Offset, val intent: PickerIntent)

/**
 * Runtime state for a single flow canvas: the graph (nodes + edges), the
 * view transform (pan + zoom), the current selection, and any in-progress
 * connection drag.
 *
 * Coordinates come in two spaces:
 *  - **world**: the logical canvas where nodes live (unaffected by pan/zoom).
 *  - **screen**: pixels in the composable, after applying pan/zoom.
 */
class FlowGraphState(
    /** Kind-id → spec map the canvas draws from. Defaults to the built-ins; the tab
     *  threads the same instance it gives the executor so palette/geometry/dispatch agree. */
    val registry: NodeRegistry = builtinNodeRegistry(),
) {

    val nodes = mutableStateListOf<FlowNode>()
    val edges = mutableStateListOf<EdgeModel>()

    /** Descriptive data loaded with the graph and preserved by every autosave. */
    var metadata by mutableStateOf<FlowMeta?>(null)

    var scale by mutableStateOf(1f)
    var panOffset by mutableStateOf(Offset.Zero)

    var selection by mutableStateOf<Selection?>(null)
    var pendingConnection by mutableStateOf<PendingConnection?>(null)

    /** True while a wire is being dragged out (flips twice per drag, not per pixel). */
    var connecting by mutableStateOf(false)

    /** The input port the dragged wire would snap to, if any. */
    var connectSnap by mutableStateOf<Pair<String, Int>?>(null)

    /** Edge currently under the cursor (for hover affordances). */
    var hoveredEdgeId by mutableStateOf<String?>(null)

    /** Active node-picker request, or null when the picker is closed. */
    var pickerRequest by mutableStateOf<PickerRequest?>(null)

    // ---- run state (Phase 1 execution) ----
    /** Per-node execution status, keyed by node id. */
    val runStates = mutableStateMapOf<String, NodeRun>()
    var isRunning by mutableStateOf(false)
    var runError by mutableStateOf<String?>(null)

    /**
     * Bumped on a timer while [isRunning] so the canvas re-reads node status and
     * repaints live. The run writes [runStates] from a background thread; Compose
     * Desktop only renders on invalidation, and a visible browser pane sitting idle
     * between steps produces no frame, so those writes wouldn't show until something
     * forced a frame. The canvas reads this, so each bump guarantees a fresh frame.
     */
    var repaintTick by mutableStateOf(0)

    // ---- headless (Open Browser nodes) ----
    /** True when there's at least one Open Browser node and all of them are headless.
     *  Drives the toolbar "Headless" toggle so it reflects the actual node config. */
    val allBrowserHeadless: Boolean
        get() = nodes.filter { it.kind == OPEN_BROWSER_KIND }
            .let { browsers ->
                browsers.isNotEmpty() && browsers.all {
                    (it.config["headless"] as? JsonPrimitive)?.content == "true"
                }
            }

    /** Set `headless` on every Open Browser node, so the toolbar toggle writes the
     *  real per-node config (and it shows correctly in the inspector + persists). */
    fun setAllBrowserHeadless(value: Boolean) {
        nodes.filter { it.kind == OPEN_BROWSER_KIND }.forEach { node ->
            node.config = JsonObject(node.config + ("headless" to JsonPrimitive(value.toString())))
        }
    }

    /** Transient neutral status message (e.g. import results). */
    var notice by mutableStateOf<String?>(null)

    /** One-step safety net for the last explicit Tidy layout action. */
    private var preTidyPositions by mutableStateOf<Map<String, Offset>?>(null)
    val canUndoTidyLayout: Boolean get() = preTidyPositions != null

    fun clearRun() {
        runStates.clear()
        runError = null
    }

    /**
     * Append a left-to-right chain of nodes (from an import), wiring each node's
     * output 0 into the next node's input 0. Existing graph is preserved.
     */
    fun importChain(steps: List<ImportStep>, origin: Offset) {
        if (steps.isEmpty()) return
        val stepX = nodeOuterWidth() + 120f
        var prevId: String? = null
        var firstId: String? = null
        steps.forEachIndexed { i, step ->
            val node = FlowNode(newId("n"), registry.resolve(step.kind), step.title, origin.x + i * stepX, origin.y, step.config)
            nodes.add(node)
            if (firstId == null) firstId = node.id
            prevId?.let { connect(it, 0, node.id, 0) }
            prevId = node.id
        }
        firstId?.let { selection = Selection.Node(it) }
    }

    private var idCounter = 1L

    // ---- transform ----------------------------------------------------------

    fun toScreen(world: Offset): Offset = world * scale + panOffset
    fun toWorld(screen: Offset): Offset = (screen - panOffset) / scale

    fun zoomBy(factor: Float, focusScreen: Offset) {
        val worldFocus = toWorld(focusScreen)
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        panOffset = focusScreen - worldFocus * newScale
        scale = newScale
    }

    fun resetView() {
        scale = 1f
        panOffset = Offset.Zero
    }

    /** Fit all nodes into [viewport] with padding. No-op when the graph is empty. */
    fun fitToContent(viewport: Size) {
        if (nodes.isEmpty() || viewport.width <= 0f || viewport.height <= 0f) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (n in nodes) {
            minX = min(minX, n.x)
            minY = min(minY, n.y)
            maxX = max(maxX, n.x + nodeOuterWidth())
            maxY = max(maxY, n.y + nodeHeight(n.spec))
        }
        val pad = 60f
        val contentW = (maxX - minX) + pad * 2
        val contentH = (maxY - minY) + pad * 2
        val newScale = min(viewport.width / contentW, viewport.height / contentH)
            .coerceIn(MIN_SCALE, MAX_SCALE)
        scale = newScale
        // Center the content's bounding box in the viewport.
        val centerWorld = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
        val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
        panOffset = viewportCenter - centerWorld * newScale
    }

    /**
     * Reposition every node from dependency depth. Returns false when no position
     * changed. The previous coordinates are retained for one explicit undo.
     */
    fun tidyLayout(): Boolean {
        val positions = layeredNodeLayout(
            nodes = nodes.map { LayoutNode(it.id, nodeHeight(it.spec), it.x, it.y) },
            edges = edges,
        )
        val changed = nodes.any { node ->
            val next = positions[node.id]
            next != null && (next.x != node.x || next.y != node.y)
        }
        if (!changed) return false

        preTidyPositions = nodes.associate { it.id to Offset(it.x, it.y) }
        nodes.forEach { node ->
            positions[node.id]?.let { position ->
                node.x = position.x
                node.y = position.y
            }
        }
        return true
    }

    /** Restore the coordinates captured immediately before the last tidy action. */
    fun undoTidyLayout(): Boolean {
        val positions = preTidyPositions ?: return false
        var changed = false
        nodes.forEach { node ->
            positions[node.id]?.let { position ->
                if (node.x != position.x || node.y != position.y) changed = true
                node.x = position.x
                node.y = position.y
            }
        }
        preTidyPositions = null
        return changed
    }

    // ---- mutations ----------------------------------------------------------

    private fun newId(prefix: String): String = "$prefix${idCounter++}"

    /** Add a node of [spec] centered on [worldCenter]; returns and selects it. */
    fun addNode(spec: NodeSpec, worldCenter: Offset): FlowNode {
        val h = nodeHeight(spec)
        val node = FlowNode(
            id = newId("n"),
            spec = spec,
            title = spec.label,
            x = worldCenter.x - nodeOuterWidth() / 2f,
            y = worldCenter.y - h / 2f,
            config = spec.defaultConfig,
        )
        nodes.add(node)
        selection = Selection.Node(node.id)
        return node
    }

    /** Convenience: add a node by kind-id, resolving the spec from the registry. */
    fun addNode(kind: String, worldCenter: Offset): FlowNode =
        addNode(registry.resolve(kind), worldCenter)

    fun nodeById(id: String): FlowNode? = nodes.firstOrNull { it.id == id }

    fun removeNode(id: String) {
        edges.removeAll { it.fromNode == id || it.toNode == id }
        nodes.removeAll { it.id == id }
        if (selection == Selection.Node(id)) selection = null
    }

    fun removeEdge(id: String) {
        edges.removeAll { it.id == id }
        if (selection == Selection.Edge(id)) selection = null
    }

    /** Delete whatever is currently selected. */
    fun deleteSelection() {
        when (val s = selection) {
            is Selection.Node -> removeNode(s.id)
            is Selection.Edge -> removeEdge(s.id)
            null -> {}
        }
    }

    /**
     * Create an edge from an output port to an input port if valid.
     * Rejects self-connections and exact duplicates.
     */
    fun connect(fromNode: String, fromPort: Int, toNode: String, toPort: Int): Boolean {
        if (fromNode == toNode) return false
        val duplicate = edges.any {
            it.fromNode == fromNode && it.fromPort == fromPort &&
                it.toNode == toNode && it.toPort == toPort
        }
        if (duplicate) return false
        edges.add(EdgeModel(newId("e"), fromNode, fromPort, toNode, toPort))
        return true
    }

    /** Resolve an open picker request by spawning [spec] and wiring it per the intent. */
    fun resolvePicker(spec: NodeSpec) {
        val req = pickerRequest ?: return
        when (val intent = req.intent) {
            is PickerIntent.AddAt -> addNode(spec, intent.world)
            is PickerIntent.ConnectFrom -> {
                val node = addNode(spec, intent.world)
                connect(intent.fromNode, intent.fromPort, node.id, 0)
            }
            is PickerIntent.InsertOnEdge -> {
                val edge = edges.firstOrNull { it.id == intent.edgeId }
                val node = addNode(spec, intent.world)
                if (edge != null && spec.inputs > 0 && spec.outputs > 0) {
                    connect(edge.fromNode, edge.fromPort, node.id, 0)
                    connect(node.id, 0, edge.toNode, edge.toPort)
                    removeEdge(edge.id)
                }
            }
        }
        pickerRequest = null
    }

    /** World-space midpoint of [edge]'s curve, or null if an endpoint is gone. */
    fun edgeMidpoint(edge: EdgeModel): Offset? {
        val (start, end) = edgeEndpoints(edge) ?: return null
        val (c1, c2) = edgeControlPoints(start, end)
        return cubicPoint(start, c1, c2, end, 0.5f)
    }

    // ---- hit testing (all in world coordinates) -----------------------------

    /** Endpoints (outputPort, inputPort) of [edge], or null if a node is gone. */
    fun edgeEndpoints(edge: EdgeModel): Pair<Offset, Offset>? {
        val from = nodeById(edge.fromNode) ?: return null
        val to = nodeById(edge.toNode) ?: return null
        return outputPortPos(from.x, from.y, edge.fromPort, from.spec) to
            inputPortPos(to.x, to.y, edge.toPort, to.spec)
    }

    /** Find an input port near [world], for dropping a dragged connection. */
    fun findInputPortAt(world: Offset, radius: Float = PORT_RADIUS * 2.5f): Pair<String, Int>? {
        for (node in nodes) {
            for (i in 0 until node.spec.inputs) {
                val p = inputPortPos(node.x, node.y, i, node.spec)
                if ((world - p).getDistance() <= radius) return node.id to i
            }
        }
        return null
    }

    /** Find the edge whose curve passes within [threshold] of [world]. */
    fun findEdgeNear(world: Offset, threshold: Float = 8f): String? {
        var best: String? = null
        var bestDist = threshold
        for (edge in edges) {
            val (start, end) = edgeEndpoints(edge) ?: continue
            val d = distanceToBezier(world, start, end)
            if (d < bestDist) {
                bestDist = d
                best = edge.id
            }
        }
        return best
    }

    // ---- persistence --------------------------------------------------------

    fun toSnapshot(): GraphSnapshot = GraphSnapshot(
        nodes = nodes.map { NodeModel(it.id, it.kind, it.title, it.x, it.y, it.config) },
        edges = edges.toList(),
        nextId = idCounter,
        schemaVersion = SUPPORTED_SCHEMA_VERSION,
        metadata = metadata,
    )

    /**
     * Replace the canvas with [snapshot]. A graph whose [GraphSnapshot.schemaVersion]
     * is newer than this build understands is **refused** (logged + skipped, returns
     * false) rather than mis-loaded — the current graph is left untouched. Unknown
     * node kind-ids load as [NodeSpec.unavailable] placeholders (no throw). Returns
     * true when the graph was loaded.
     */
    fun load(snapshot: GraphSnapshot): Boolean {
        if (!snapshot.isSchemaSupported()) {
            println(
                "flow-tab: refusing to load graph schemaVersion=${snapshot.schemaVersion} " +
                    "(this build supports up to $SUPPORTED_SCHEMA_VERSION); skipping."
            )
            return false
        }
        nodes.clear()
        edges.clear()
        metadata = snapshot.metadata
        snapshot.nodes.forEach { nodes.add(FlowNode(it.id, registry.resolve(it.type), it.title, it.x, it.y, it.config)) }
        edges.addAll(snapshot.edges)
        // Keep the counter ahead of any restored id to avoid collisions.
        val maxExisting = (snapshot.nodes.map { it.id } + snapshot.edges.map { it.id })
            .mapNotNull { it.drop(1).toLongOrNull() }
            .maxOrNull() ?: 0L
        idCounter = max(snapshot.nextId, maxExisting + 1)
        selection = null
        pendingConnection = null
        preTidyPositions = null
        return true
    }

    companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 2.5f
    }
}

/**
 * Cubic-bezier control points for an edge running left-to-right from [start]
 * (an output port) to [end] (an input port). The horizontal tangents give the
 * familiar "S" routing. Shared by both rendering and hit-testing so the curve
 * the user sees is exactly the curve we test against.
 */
fun edgeControlPoints(start: Offset, end: Offset): Pair<Offset, Offset> {
    val dx = max(40f, abs(end.x - start.x) * 0.5f)
    return Offset(start.x + dx, start.y) to Offset(end.x - dx, end.y)
}

/**
 * Return the minimum distance from [p] to a sampling of the edge curve between
 * [start] and [end].
 */
fun distanceToBezier(p: Offset, start: Offset, end: Offset): Float {
    val (c1, c2) = edgeControlPoints(start, end)
    var best = Float.MAX_VALUE
    val steps = 28
    var prev = start
    for (i in 1..steps) {
        val t = i / steps.toFloat()
        val pt = cubicPoint(start, c1, c2, end, t)
        best = min(best, distanceToSegment(p, prev, pt))
        prev = pt
    }
    return best
}

/** Evaluate a cubic bezier at parameter [t] in [0, 1]. */
fun cubicPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(
        a * p0.x + b * p1.x + c * p2.x + d * p3.x,
        a * p0.y + b * p1.y + c * p2.y + d * p3.y
    )
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq == 0f) return (p - a).getDistance()
    var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
    t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + t * abx, a.y + t * aby)
    return (p - proj).getDistance()
}
