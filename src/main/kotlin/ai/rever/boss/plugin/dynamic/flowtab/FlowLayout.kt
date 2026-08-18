package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.ui.geometry.Offset

/** Minimal geometry needed by the pure layout functions. */
internal data class LayoutNode(
    val id: String,
    val height: Float,
    val x: Float,
    val y: Float,
)

internal const val LAYOUT_ORIGIN_X = 240f
internal const val LAYOUT_CENTER_Y = 320f
internal const val LAYOUT_COLUMN_GAP = 140f
internal const val LAYOUT_ROW_GAP = 72f
internal const val NEW_NODE_ORIGIN_X = 320f
internal const val NEW_NODE_ORIGIN_Y = 200f
internal const val NEW_NODE_GAP = 40f
internal const val NEW_NODE_COLUMN_GAP = 120f

/** Authoring uses a wider pitch than tidy columns so manually moved cards leave breathing room. */
internal fun newNodeStepX(): Float = nodeOuterWidth() + NEW_NODE_COLUMN_GAP

/**
 * Assign a deterministic left-to-right layered layout.
 *
 * Rank is the longest dependency path from a root. Nodes in each rank are ordered
 * by the vertical barycenter of their already-positioned parents; output-port order
 * breaks sibling ties so If true/false branches remain readable. Cyclic residuals
 * are kept together in one final rank rather than making the UI action fail.
 */
internal fun layeredNodeLayout(
    nodes: List<LayoutNode>,
    edges: List<EdgeModel>,
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()

    val nodeById = nodes.associateBy { it.id }
    val stableOrder = nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val validEdges = edges.filter { it.fromNode in nodeById && it.toNode in nodeById }
    val outgoing = nodes.associate { it.id to mutableListOf<EdgeModel>() }
    val incoming = nodes.associate { it.id to mutableListOf<EdgeModel>() }
    val indegree = nodes.associate { it.id to 0 }.toMutableMap()
    validEdges.forEach { edge ->
        outgoing.getValue(edge.fromNode).add(edge)
        incoming.getValue(edge.toNode).add(edge)
        indegree[edge.toNode] = indegree.getValue(edge.toNode) + 1
    }
    outgoing.values.forEach { list ->
        list.sortWith(compareBy<EdgeModel>({ it.fromPort }, { it.toPort }, { stableOrder.getValue(it.toNode) }))
    }

    val rank = nodes.associate { it.id to 0 }.toMutableMap()
    val queue = ArrayDeque(nodes.filter { indegree.getValue(it.id) == 0 }.map { it.id })
    val processed = linkedSetOf<String>()
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        processed.add(id)
        outgoing.getValue(id).forEach { edge ->
            rank[edge.toNode] = maxOf(rank.getValue(edge.toNode), rank.getValue(id) + 1)
            val remaining = indegree.getValue(edge.toNode) - 1
            indegree[edge.toNode] = remaining
            if (remaining == 0) queue.addLast(edge.toNode)
        }
    }

    // UI-created cycles are legal to draw even though execution rejects them. Keep
    // every residual node visible and non-overlapping in a final stable column.
    val residualRank = (processed.maxOfOrNull { rank.getValue(it) } ?: -1) + 1
    nodes.filterNot { it.id in processed }.forEach { node ->
        rank[node.id] = maxOf(rank.getValue(node.id), residualRank)
    }

    val result = linkedMapOf<String, Offset>()
    val placedCenters = mutableMapOf<String, Float>()
    rank.values.toSortedSet().forEach { column ->
        val columnNodes = nodes.filter { rank.getValue(it.id) == column }
            .sortedWith(
                compareBy<LayoutNode>(
                    { node ->
                        incoming.getValue(node.id)
                            .mapNotNull { placedCenters[it.fromNode] }
                            .averageOrNull() ?: node.y.toDouble()
                    },
                    { node -> incoming.getValue(node.id).minOfOrNull { it.fromPort } ?: Int.MAX_VALUE },
                    { it.y },
                    { it.x },
                    { stableOrder.getValue(it.id) },
                ),
            )
        val totalHeight = columnNodes.sumOf { it.height.toDouble() }.toFloat() +
            LAYOUT_ROW_GAP * (columnNodes.size - 1).coerceAtLeast(0)
        var y = LAYOUT_CENTER_Y - totalHeight / 2f
        columnNodes.forEach { node ->
            val position = Offset(
                x = LAYOUT_ORIGIN_X + column * (nodeOuterWidth() + LAYOUT_COLUMN_GAP),
                y = y,
            )
            result[node.id] = position
            placedCenters[node.id] = y + node.height / 2f
            y += node.height + LAYOUT_ROW_GAP
        }
    }
    return result
}

/**
 * First standard authoring slot whose padded node rectangle does not collide.
 * Searching from the origin also reuses holes left by deletion instead of deriving
 * position from node count and stacking a replacement on an existing node.
 */
internal fun collisionFreeNodePosition(
    existing: List<LayoutNode>,
    newNodeHeight: Float,
): Offset {
    val stepX = newNodeStepX()
    // An off-grid card can straddle two standard slots, so inspect up to two
    // candidates per existing card before using the guaranteed-right fallback.
    for (column in 0..existing.size * 2) {
        val candidate = Offset(NEW_NODE_ORIGIN_X + column * stepX, NEW_NODE_ORIGIN_Y)
        if (existing.none { it.overlaps(candidate, newNodeHeight) }) return candidate
    }
    return Offset(
        x = (existing.maxOfOrNull { it.x } ?: NEW_NODE_ORIGIN_X) + stepX,
        y = NEW_NODE_ORIGIN_Y,
    )
}

private fun LayoutNode.overlaps(candidate: Offset, candidateHeight: Float): Boolean {
    val width = nodeOuterWidth()
    return candidate.x < x + width + NEW_NODE_GAP &&
        candidate.x + width + NEW_NODE_GAP > x &&
        candidate.y < y + height + NEW_NODE_GAP &&
        candidate.y + candidateHeight + NEW_NODE_GAP > y
}

private fun List<Float>.averageOrNull(): Double? =
    if (isEmpty()) null else sumOf { it.toDouble() } / size
