package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FlowLayoutTest {

    private fun node(id: String, height: Float = 100f, x: Float = 0f, y: Float = 0f) =
        LayoutNode(id, height, x, y)

    private fun edge(id: String, from: String, to: String, fromPort: Int = 0) =
        EdgeModel(id, from, fromPort, to, 0)

    @Test
    fun `linear dependencies occupy successive depth columns`() {
        val layout = layeredNodeLayout(
            nodes = listOf(node("a"), node("b"), node("c")),
            edges = listOf(edge("e1", "a", "b"), edge("e2", "b", "c")),
        )

        val step = nodeOuterWidth() + LAYOUT_COLUMN_GAP
        assertEquals(LAYOUT_ORIGIN_X, layout.getValue("a").x)
        assertEquals(LAYOUT_ORIGIN_X + step, layout.getValue("b").x)
        assertEquals(LAYOUT_ORIGIN_X + step * 2, layout.getValue("c").x)
        assertEquals(LAYOUT_CENTER_Y, layout.getValue("a").y + 50f)
        assertEquals(LAYOUT_CENTER_Y, layout.getValue("b").y + 50f)
    }

    @Test
    fun `siblings follow output ports and are centered under their parent`() {
        val layout = layeredNodeLayout(
            nodes = listOf(node("parent"), node("false"), node("true")),
            edges = listOf(
                edge("false-edge", "parent", "false", fromPort = 1),
                edge("true-edge", "parent", "true", fromPort = 0),
            ),
        )

        val parentCenter = layout.getValue("parent").y + 50f
        val trueCenter = layout.getValue("true").y + 50f
        val falseCenter = layout.getValue("false").y + 50f
        assertEquals(layout.getValue("true").x, layout.getValue("false").x)
        assertTrue(trueCenter < falseCenter)
        assertEquals(parentCenter, (trueCenter + falseCenter) / 2f)
        assertTrue(layout.getValue("true").y + 100f < layout.getValue("false").y)
    }

    @Test
    fun `merge rank uses the longest dependency path`() {
        val layout = layeredNodeLayout(
            nodes = listOf(node("root"), node("middle"), node("merge")),
            edges = listOf(
                edge("direct", "root", "merge"),
                edge("first", "root", "middle"),
                edge("second", "middle", "merge"),
            ),
        )

        val step = nodeOuterWidth() + LAYOUT_COLUMN_GAP
        assertEquals(LAYOUT_ORIGIN_X + step * 2, layout.getValue("merge").x)
    }

    @Test
    fun `cycle remains visible without overlapping`() {
        val layout = layeredNodeLayout(
            nodes = listOf(node("a"), node("b")),
            edges = listOf(edge("ab", "a", "b"), edge("ba", "b", "a")),
        )

        assertEquals(layout.getValue("a").x, layout.getValue("b").x)
        assertNotEquals(layout.getValue("a").y, layout.getValue("b").y)
        assertTrue(kotlin.math.abs(layout.getValue("a").y - layout.getValue("b").y) >= 100f + LAYOUT_ROW_GAP)
    }

    @Test
    fun `new node placement reuses a deleted slot without colliding`() {
        val step = newNodeStepX()
        val position = collisionFreeNodePosition(
            existing = listOf(
                node("first", x = NEW_NODE_ORIGIN_X, y = NEW_NODE_ORIGIN_Y),
                node("third", x = NEW_NODE_ORIGIN_X + step * 2, y = NEW_NODE_ORIGIN_Y),
            ),
            newNodeHeight = 100f,
        )

        assertEquals(Offset(NEW_NODE_ORIGIN_X + step, NEW_NODE_ORIGIN_Y), position)
    }

    @Test
    fun `off-grid card searches enough standard slots before falling back`() {
        val position = collisionFreeNodePosition(
            existing = listOf(node("between-slots", x = 500f, y = NEW_NODE_ORIGIN_Y)),
            newNodeHeight = 100f,
        )

        assertEquals(
            Offset(NEW_NODE_ORIGIN_X + newNodeStepX() * 2, NEW_NODE_ORIGIN_Y),
            position,
        )
    }

    @Test
    fun `graph state tidy is explicitly undoable`() {
        val state = FlowGraphState()
        val first = state.addNode(NodeType.TRIGGER.name, Offset(200f, 200f))
        val second = state.addNode(NodeType.SET.name, Offset(200f, 200f))
        state.connect(first.id, 0, second.id, 0)
        val original = state.nodes.associate { it.id to Offset(it.x, it.y) }

        assertTrue(state.tidyLayout())
        assertTrue(state.canUndoTidyLayout)
        assertNotEquals(Offset(first.x, first.y), Offset(second.x, second.y))
        assertTrue(state.undoTidyLayout())
        assertFalse(state.canUndoTidyLayout)
        assertEquals(original.getValue(first.id), Offset(first.x, first.y))
        assertEquals(original.getValue(second.id), Offset(second.x, second.y))
    }

    @Test
    fun `manual movement retires tidy undo instead of discarding later work`() {
        val state = FlowGraphState()
        val first = state.addNode(NodeType.TRIGGER.name, Offset(200f, 200f))
        val second = state.addNode(NodeType.SET.name, Offset(200f, 200f))
        state.connect(first.id, 0, second.id, 0)
        assertTrue(state.tidyLayout())

        val delta = Offset(45f, 30f)
        val beforeMove = Offset(second.x, second.y)
        assertTrue(state.moveNodeBy(second.id, delta))

        assertFalse(state.canUndoTidyLayout)
        assertFalse(state.undoTidyLayout())
        assertEquals(beforeMove + delta, Offset(second.x, second.y))
    }

    @Test
    fun `tidy is idempotent and load clears its undo snapshot`() {
        val state = FlowGraphState()
        val first = state.addNode(NodeType.TRIGGER.name, Offset(200f, 200f))
        val second = state.addNode(NodeType.SET.name, Offset(200f, 200f))
        state.connect(first.id, 0, second.id, 0)

        assertTrue(state.tidyLayout())
        assertFalse(state.tidyLayout())
        assertTrue(state.canUndoTidyLayout)

        assertTrue(state.load(state.toSnapshot()))
        assertFalse(state.canUndoTidyLayout)
    }

    @Test
    fun `topology edits and clear retire tidy undo`() {
        val state = FlowGraphState()
        val first = state.addNode(NodeType.TRIGGER.name, Offset(200f, 200f))
        val second = state.addNode(NodeType.SET.name, Offset(200f, 200f))
        state.connect(first.id, 0, second.id, 0)
        assertTrue(state.tidyLayout())

        state.addNode(NodeType.CODE.name, Offset(900f, 200f))
        assertFalse(state.canUndoTidyLayout)

        assertTrue(state.tidyLayout())
        state.clearGraph()
        assertFalse(state.canUndoTidyLayout)
        assertTrue(state.nodes.isEmpty())
        assertTrue(state.edges.isEmpty())
    }
}
