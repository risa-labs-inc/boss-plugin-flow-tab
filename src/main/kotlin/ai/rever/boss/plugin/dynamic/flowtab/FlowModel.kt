package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.max

/** How the executor feeds items to a node. */
enum class RunMode {
    /** Run the executor once per input item (data nodes). */
    PER_ITEM,
    /** Run the executor once with all input items (browser / control nodes). */
    ONCE
}

/** Kind of input control the inspector renders for a config field. */
enum class FieldType { TEXT, TEXTAREA, SELECT, BOOL }

/** Declarative config-field schema — rendered by the inspector, read by executors. */
data class ConfigField(
    val key: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val options: List<String> = emptyList(),
    val placeholder: String = "",
    val default: String = ""
)

/**
 * The kinds of node that can be spawned on the canvas.
 *
 * Enums serialize by name, so existing names (TRIGGER, HTTP, …) stay stable for
 * persistence. [runMode] + [configFields] + the executor in [NodeCatalog] give
 * each kind its executable behavior.
 */
enum class NodeType(
    val label: String,
    val inputs: Int,
    val outputs: Int,
    val accent: Long,
    val description: String,
    val runMode: RunMode = RunMode.PER_ITEM
) {
    TRIGGER("Trigger", 0, 1, 0xFF4CAF50, "Starts the workflow", RunMode.ONCE),
    OPEN_BROWSER("Open Browser", 1, 1, 0xFF26A69A, "Open a browser session", RunMode.ONCE),
    NAVIGATE("Navigate", 1, 1, 0xFF42A5F5, "Go to a URL", RunMode.ONCE),
    CLICK("Click", 1, 1, 0xFF5C6BC0, "Click an element", RunMode.ONCE),
    TYPE("Type", 1, 1, 0xFFAB47BC, "Type into a field", RunMode.ONCE),
    EXTRACT("Extract", 1, 1, 0xFFFFA726, "Extract data from the page", RunMode.ONCE),
    INJECT("Inject", 1, 1, 0xFFEC407A, "Run JS / push data to the page", RunMode.ONCE),
    HTTP("HTTP Request", 1, 1, 0xFF2196F3, "Call an API endpoint", RunMode.PER_ITEM),
    SET("Set", 1, 1, 0xFF00BCD4, "Set or edit fields", RunMode.PER_ITEM),
    CODE("Code", 1, 1, 0xFF9C27B0, "Run custom code (not yet runnable)", RunMode.PER_ITEM),
    IF("If", 1, 2, 0xFFFF9800, "Branch on a condition (not yet runnable)", RunMode.ONCE),
    MERGE("Merge", 2, 1, 0xFF607D8B, "Combine two inputs (not yet runnable)", RunMode.ONCE);

    /** Human-friendly label for an output port (e.g. If → true/false). */
    fun outputLabel(index: Int): String = when (this) {
        IF -> if (index == 0) "true" else "false"
        else -> ""
    }

    /** Human-friendly label for an input port. */
    fun inputLabel(index: Int): String = when (this) {
        MERGE -> if (index == 0) "a" else "b"
        else -> ""
    }

    /**
     * True for nodes that drive the shared browser session. The executor runs
     * these behind a per-run session mutex (the "fence") so parallel branches
     * never touch the one page at once; dependency edges preserve their order.
     */
    fun usesSession(): Boolean = when (this) {
        OPEN_BROWSER, NAVIGATE, CLICK, TYPE, EXTRACT, INJECT -> true
        else -> false
    }

    /**
     * Whether this type renders a small chip row of extra metadata under the
     * subtitle (selector kind, HTTP method, headless, …). Type-determined so
     * [nodeHeight] stays a pure function of type and ports never drift.
     */
    fun hasMetaRow(): Boolean = when (this) {
        OPEN_BROWSER, HTTP, CLICK, TYPE, EXTRACT -> true
        else -> false
    }

    /** Config fields shown in the inspector. String fields may contain {{ }} expressions. */
    fun configFields(): List<ConfigField> = when (this) {
        OPEN_BROWSER -> listOf(
            ConfigField("url", "Start URL", FieldType.TEXT, placeholder = "https://example.com"),
            ConfigField("headless", "Headless (no visible window)", FieldType.BOOL, default = "false")
        )
        NAVIGATE -> listOf(
            ConfigField("url", "URL", FieldType.TEXT, placeholder = "https://example.com")
        )
        CLICK -> listOf(
            ConfigField("selectorType", "Selector type", FieldType.SELECT, listOf("css", "xpath", "text"), default = "css"),
            ConfigField("selector", "Selector", FieldType.TEXT, placeholder = "button.submit")
        )
        TYPE -> listOf(
            ConfigField("selectorType", "Selector type", FieldType.SELECT, listOf("css", "xpath", "text"), default = "css"),
            ConfigField("selector", "Selector", FieldType.TEXT, placeholder = "input[name=q]"),
            ConfigField("text", "Text", FieldType.TEXT, placeholder = "hello or {{ \$json.q }}")
        )
        EXTRACT -> listOf(
            ConfigField("selectorType", "Selector type", FieldType.SELECT, listOf("css", "xpath", "text"), default = "css"),
            ConfigField("selector", "Selector", FieldType.TEXT, placeholder = "h1, .title"),
            ConfigField("mode", "Extract", FieldType.SELECT, listOf("text", "html", "attr"), default = "text"),
            ConfigField("attr", "Attribute", FieldType.TEXT, placeholder = "href (when mode = attr)"),
            ConfigField("field", "Output field", FieldType.TEXT, default = "value"),
            ConfigField("multiple", "All matches", FieldType.BOOL, default = "false")
        )
        INJECT -> listOf(
            ConfigField("script", "JavaScript", FieldType.TEXTAREA, placeholder = "document.title = '{{ \$json.title }}'")
        )
        HTTP -> listOf(
            ConfigField("method", "Method", FieldType.SELECT, listOf("GET", "POST", "PUT", "DELETE", "PATCH"), default = "GET"),
            ConfigField("url", "URL", FieldType.TEXT, placeholder = "https://api.example.com/x"),
            ConfigField("headers", "Headers (JSON)", FieldType.TEXTAREA, placeholder = "{\"Authorization\":\"Bearer …\"}"),
            ConfigField("body", "Body", FieldType.TEXTAREA, placeholder = "{{ \$json }} or raw text")
        )
        SET -> listOf(
            ConfigField("assignments", "Fields (JSON: key → value/expr)", FieldType.TEXTAREA, placeholder = "{\"name\":\"{{ \$json.first }}\"}")
        )
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------
// Geometry — single source of truth shared by rendering and hit-testing.
// All values are in *world* units (canvas space, before pan/zoom).
// ---------------------------------------------------------------------------

/** Visual width of the node card (excluding the left/right port margins). */
const val NODE_WIDTH = 240f
const val PORT_RADIUS = 6f
const val NODE_CORNER = 14f

/** Base card height — icon tile + title/subtitle — used when a side has ≤1 port. */
const val NODE_ROW_H = 74f

/** Extra height for the metadata chip row, added only to types that show it. */
const val NODE_META_H = 22f

/** Extra height per port beyond the first, so multi-port nodes (If/Merge) breathe. */
const val NODE_PORT_GAP = 28f

/**
 * Horizontal margin on each side of the card. Ports are centered on the card
 * edges but live inside this margin so their interactive box stays within the
 * node's layout bounds (Compose won't deliver pointer events to children that
 * fall outside their parent).
 */
const val NODE_PAD = 10f

/** Full interactive width of a node (card + left/right port margins). */
fun nodeOuterWidth(): Float = NODE_WIDTH + NODE_PAD * 2

/** Total height of a node given its type (grows with its busiest side's port count). */
fun nodeHeight(type: NodeType): Float {
    val maxPorts = max(1, max(type.inputs, type.outputs))
    val base = NODE_ROW_H + if (type.hasMetaRow()) NODE_META_H else 0f
    return base + (maxPorts - 1) * NODE_PORT_GAP
}

/**
 * Vertical center of port [index] of [count] ports on one side of a node of
 * [height]. Ports are distributed evenly down the edge: a single port lands on
 * the card mid-line; two split into thirds; etc. (the n8n layout).
 */
fun portRowY(index: Int, count: Int, height: Float): Float =
    height * (index + 1) / (count + 1).toFloat()

/** World position of input port [index] for a node whose top-left is (x, y). */
fun inputPortPos(x: Float, y: Float, index: Int, type: NodeType): Offset =
    Offset(x + NODE_PAD, y + portRowY(index, max(1, type.inputs), nodeHeight(type)))

/** World position of output port [index] for a node whose top-left is (x, y). */
fun outputPortPos(x: Float, y: Float, index: Int, type: NodeType): Offset =
    Offset(x + NODE_PAD + NODE_WIDTH, y + portRowY(index, max(1, type.outputs), nodeHeight(type)))

// ---------------------------------------------------------------------------
// Serializable graph snapshot (persistence)
// ---------------------------------------------------------------------------

@Serializable
data class NodeModel(
    val id: String,
    val type: NodeType,
    val title: String,
    val x: Float,
    val y: Float,
    val config: JsonObject = JsonObject(emptyMap())
)

/**
 * A connection from an output port to an input port.
 *
 * @param fromNode source node id, @param fromPort source output port index
 * @param toNode target node id, @param toPort target input port index
 */
@Serializable
data class EdgeModel(
    val id: String,
    val fromNode: String,
    val fromPort: Int,
    val toNode: String,
    val toPort: Int
)

@Serializable
data class GraphSnapshot(
    val nodes: List<NodeModel> = emptyList(),
    val edges: List<EdgeModel> = emptyList(),
    val nextId: Long = 1L
)
