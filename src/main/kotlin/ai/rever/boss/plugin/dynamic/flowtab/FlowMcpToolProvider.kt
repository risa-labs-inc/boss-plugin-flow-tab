package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The fixed, generic set of MCP tools Flow registers into the host's `boss` server so
 * an attached agent (Claude Code) can author and run flows and prompts. Names take
 * ids/kinds as **arguments** — it is deliberately NOT a tool-per-artifact surface,
 * because [McpToolProvider.tools] is queried once and the bridge dedups first-wins
 * (red-team F7). Every name is `flow_`/`prompt_`-prefixed to dodge the 15 reserved
 * boss tool names and cross-plugin collisions.
 *
 * All handlers are storage-seated via [FlowController] (no open tab needed) and async
 * for runs (F1: `flow_run` returns a runId, the agent polls `flow_status`/`flow_result`).
 * Every handler is wrapped so a bad argument yields an `isError` [McpToolResult] rather
 * than throwing across the MCP boundary.
 */
class FlowMcpToolProvider(
    private val controller: FlowController,
    private val prompts: PromptRegistry,
    override val providerId: String = PROVIDER_ID,
) : McpToolProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    override fun tools(): List<McpToolDefinition> = listOf(
        def("flow_create", "Create a new empty flow. Optional name/description. Returns {tabId}.",
            schema("""{"name":{"type":"string"},"description":{"type":"string"}}"""), readOnly = false) { a ->
            val o = a.obj()
            val meta = o.metaOrNull()
            ok(buildJsonObject { put("tabId", controller.createFlow(meta)) })
        },
        def("flow_rename", "Rename a flow without rebuilding it. Returns {ok,tabId,name}.",
            schema("""{"tabId":{"type":"string"},"name":{"type":"string"}}""",
                required = listOf("tabId", "name")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_rename requires 'tabId'")
            val name = o.str("name") ?: return@def err("flow_rename requires 'name'")
            val renamed = controller.renameFlow(tabId, name)
            ok(buildJsonObject {
                put("ok", true)
                put("tabId", renamed.tabId)
                put("name", renamed.name)
            })
        },
        def("flow_add_node", "Add a node of the given kind-id to a flow. Returns {nodeId}. " +
            "config is an object of the node's config fields.",
            schema("""{"tabId":{"type":"string"},"kind":{"type":"string"},"config":{"type":"object"}}""",
                required = listOf("tabId", "kind")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_add_node requires 'tabId'")
            val kind = o.str("kind") ?: return@def err("flow_add_node requires 'kind'")
            val config = o["config"] as? JsonObject ?: JsonObject(emptyMap())
            ok(buildJsonObject { put("nodeId", controller.addNode(tabId, kind, config)) })
        },
        def("flow_update_node", "Patch a node's title and/or config. Config keys are merged over " +
            "the existing config. Returns {ok,nodeId,title}.",
            schema("""{"tabId":{"type":"string"},"nodeId":{"type":"string"},"title":{"type":"string"},"config":{"type":"object"}}""",
                required = listOf("tabId", "nodeId")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_update_node requires 'tabId'")
            val nodeId = o.str("nodeId") ?: return@def err("flow_update_node requires 'nodeId'")
            val title = o.str("title")
            val config = when (val raw = o["config"]) {
                null -> null
                is JsonObject -> raw
                else -> return@def err("flow_update_node requires 'config' to be an object")
            }
            if (title == null && config == null) {
                return@def err("flow_update_node requires 'title' and/or 'config'")
            }
            val updated = controller.updateNode(tabId, nodeId, title, config)
            ok(buildJsonObject {
                put("ok", true)
                put("nodeId", updated.id)
                put("title", updated.title)
            })
        },
        def("flow_connect", "Connect an output port of one node to an input port of another. " +
            "Ports default to 0. Returns {edgeId}.",
            schema("""{"tabId":{"type":"string"},"from":{"type":"string"},"fromPort":{"type":"integer"},"to":{"type":"string"},"toPort":{"type":"integer"}}""",
                required = listOf("tabId", "from", "to")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_connect requires 'tabId'")
            val from = o.str("from") ?: return@def err("flow_connect requires 'from'")
            val to = o.str("to") ?: return@def err("flow_connect requires 'to'")
            val edgeId = controller.connect(tabId, from, o.int("fromPort"), to, o.int("toPort"))
            ok(buildJsonObject { put("ok", true); put("edgeId", edgeId) })
        },
        def("flow_delete_node", "Delete a node and all edges connected to it. " +
            "Returns {ok,nodeId,deletedEdgeCount}.",
            schema("""{"tabId":{"type":"string"},"nodeId":{"type":"string"}}""",
                required = listOf("tabId", "nodeId")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_delete_node requires 'tabId'")
            val nodeId = o.str("nodeId") ?: return@def err("flow_delete_node requires 'nodeId'")
            val deletedEdgeCount = controller.deleteNode(tabId, nodeId)
            ok(buildJsonObject {
                put("ok", true)
                put("nodeId", nodeId)
                put("deletedEdgeCount", deletedEdgeCount)
            })
        },
        def("flow_delete_edge", "Delete one connection by edgeId. Returns {ok,edgeId}.",
            schema("""{"tabId":{"type":"string"},"edgeId":{"type":"string"}}""",
                required = listOf("tabId", "edgeId")), readOnly = false) { a ->
            val o = a.obj()
            val tabId = o.str("tabId") ?: return@def err("flow_delete_edge requires 'tabId'")
            val edgeId = o.str("edgeId") ?: return@def err("flow_delete_edge requires 'edgeId'")
            controller.deleteEdge(tabId, edgeId)
            ok(buildJsonObject { put("ok", true); put("edgeId", edgeId) })
        },
        def("flow_run", "Start a flow running asynchronously. Returns {runId}; poll flow_status/flow_result.",
            schema("""{"tabId":{"type":"string"}}""", required = listOf("tabId")), readOnly = false) { a ->
            val tabId = a.obj().str("tabId") ?: return@def err("flow_run requires 'tabId'")
            if (controller.getFlow(tabId) == null) return@def err("No flow '$tabId'")
            ok(buildJsonObject { put("runId", controller.startRun(tabId)) })
        },
        def("flow_stop", "Stop a running flow by runId. Idempotently reports an already-terminal run.",
            schema("""{"runId":{"type":"string"}}""", required = listOf("runId")), readOnly = false) { a ->
            val runId = a.obj().str("runId") ?: return@def err("flow_stop requires 'runId'")
            val before = controller.runStatus(runId) ?: return@def err("Unknown runId '$runId'")
            val stopped = if (before.isTerminal) null else controller.stopRun(runId)
            val final = stopped ?: controller.runStatus(runId) ?: before
            ok(buildJsonObject {
                put("ok", true)
                put("runId", runId)
                put("stopped", stopped != null)
                put("state", final.state.name)
                final.error?.let { put("error", it) }
            })
        },
        def("flow_status", "Get a run's state: RUNNING | SUCCEEDED | FAILED (+ error).",
            schema("""{"runId":{"type":"string"}}""", required = listOf("runId")), readOnly = true) { a ->
            val runId = a.obj().str("runId") ?: return@def err("flow_status requires 'runId'")
            val job = controller.runStatus(runId) ?: return@def err("Unknown runId '$runId'")
            ok(buildJsonObject {
                put("state", job.state.name)
                job.error?.let { put("error", it) }
            })
        },
        def("flow_result", "Get a run's per-node status, errors, and bounded logs. " +
            "Set includeOutput=true with nodeId to include that node's bounded output.",
            schema(
                """{"runId":{"type":"string"},"nodeId":{"type":"string"},"includeOutput":{"type":"boolean"}}""",
                required = listOf("runId"),
            ), readOnly = true) { a ->
            val args = a.obj()
            val runId = args.str("runId") ?: return@def err("flow_result requires 'runId'")
            val job = controller.runStatus(runId) ?: return@def err("Unknown runId '$runId'")
            val nodeId = args.str("nodeId")
            val includeOutput = args.bool("includeOutput")
            if (includeOutput && nodeId == null) {
                return@def err("flow_result requires 'nodeId' when includeOutput=true")
            }
            if (nodeId != null && nodeId !in job.nodes) {
                return@def err("Unknown nodeId '$nodeId' for run '$runId'")
            }
            McpToolResult(json.encodeToString(JsonObject.serializer(), job.toMcpResult(includeOutput, nodeId)), false)
        },
        def("flow_runs", "List recent runs for a flow, newest first. Returns runId, state, " +
            "startedAtMs, and nodeCount.",
            schema(
                """{"tabId":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":${FlowController.MAX_RUN_HISTORY_LIMIT}}}""",
                required = listOf("tabId"),
            ), readOnly = true) { a ->
            val args = a.obj()
            val tabId = args.str("tabId") ?: return@def err("flow_runs requires 'tabId'")
            if (controller.getFlow(tabId) == null) return@def err("No flow '$tabId'")
            val limit = args["limit"]?.jsonPrimitive?.int ?: FlowController.DEFAULT_RUN_HISTORY_LIMIT
            val runs = controller.listRuns(tabId, limit)
            ok(buildJsonObject {
                put(
                    "runs",
                    json.encodeToJsonElement(ListSerializer(RunSummary.serializer()), runs),
                )
            })
        },
        def("flow_list", "List every stored flow's tabId. Pass detail=true to also return " +
            "flowDetails with names, descriptions, node counts, and readability.",
            schema("""{"detail":{"type":"boolean"}}"""), readOnly = true) { a ->
            val detail = a.obj().bool("detail")
            val details = if (detail) controller.listFlowDetails() else null
            val flowIds = details?.map(FlowSummary::tabId) ?: controller.listFlows()
            ok(buildJsonObject {
                put("flows", json.encodeToJsonElement(ListSerializer(String.serializer()), flowIds))
                if (details != null) {
                    put(
                        "flowDetails",
                        json.encodeToJsonElement(
                            ListSerializer(FlowSummary.serializer()),
                            details,
                        ),
                    )
                }
            })
        },
        def("flow_get", "Get a flow's full GraphSnapshot JSON.",
            schema("""{"tabId":{"type":"string"}}""", required = listOf("tabId")), readOnly = true) { a ->
            val tabId = a.obj().str("tabId") ?: return@def err("flow_get requires 'tabId'")
            val snap = controller.getFlow(tabId) ?: return@def err("No flow '$tabId'")
            McpToolResult(json.encodeToString(GraphSnapshot.serializer(), snap), false)
        },
        def("flow_delete", "Permanently delete a flow by tabId, closing an open tab first. Returns {ok,tabId}.",
            schema("""{"tabId":{"type":"string"}}""", required = listOf("tabId")), readOnly = false) { a ->
            val tabId = a.obj().str("tabId") ?: return@def err("flow_delete requires 'tabId'")
            if (!controller.deleteFlow(tabId)) return@def err("No flow '$tabId'")
            ok(buildJsonObject { put("ok", true); put("tabId", tabId) })
        },
        def("prompt_upsert", "Insert or replace a composable prompt. Body is the full Prompt JSON " +
            "(id, name required; base, rules[], glossary[], goals[], toolAllowlist[]).",
            schema("""{"id":{"type":"string"},"name":{"type":"string"},"base":{"type":"string"},"rules":{"type":"array"},"glossary":{"type":"array"},"goals":{"type":"array"}}""",
                required = listOf("id", "name")), readOnly = false) { a ->
            val prompt = json.decodeFromString(Prompt.serializer(), a.raw)
            prompts.upsert(prompt)
            ok(buildJsonObject { put("ok", true); put("id", prompt.id) })
        },
        def("prompt_get", "Get one prompt by id (full Prompt JSON).",
            schema("""{"id":{"type":"string"}}""", required = listOf("id")), readOnly = true) { a ->
            val id = a.obj().str("id") ?: return@def err("prompt_get requires 'id'")
            val p = prompts.get(id) ?: return@def err("Unknown prompt '$id'")
            McpToolResult(json.encodeToString(Prompt.serializer(), p), false)
        },
        def("prompt_list", "List every stored prompt. Returns {prompts:[...]}.",
            schema("{}"), readOnly = true) { _ ->
            ok(buildJsonObject {
                put("prompts", json.encodeToJsonElement(ListSerializer(Prompt.serializer()), prompts.list()))
            })
        },
    )

    // ---- helpers ------------------------------------------------------------

    /** Build a definition whose handler is wrapped so a throw becomes an isError result. */
    private fun def(
        name: String,
        description: String,
        inputSchema: String,
        readOnly: Boolean,
        block: suspend (McpToolArgs) -> McpToolResult,
    ): McpToolDefinition = McpToolDefinition(
        name, description, inputSchema, readOnly,
        object : McpToolHandler {
            override suspend fun call(args: McpToolArgs): McpToolResult =
                runCatching { block(args) }.getOrElse { err(it.message ?: it.toString()) }
        },
    )

    private fun ok(o: JsonObject) = McpToolResult(o.toString(), false)
    private fun err(message: String) = McpToolResult(message, true)

    /** Parse the raw arguments JSON into an object (empty on non-object / parse failure). */
    private fun McpToolArgs.obj(): JsonObject =
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
    private fun JsonObject.int(key: String, default: Int = 0): Int =
        runCatching { this[key]?.jsonPrimitive?.int }.getOrNull() ?: default
    private fun JsonObject.bool(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.metaOrNull(): FlowMeta? {
        val name = str("name")
        val description = str("description")
        return if (name == null && description == null) null
        else FlowMeta(name = name ?: "", description = description ?: "")
    }

    companion object {
        const val PROVIDER_ID = "flow-tab"

        private const val RESULT_ERROR_MAX_BYTES = 8 * 1024
        private const val RESULT_LOG_MAX_LINES = 20
        private const val RESULT_LOG_LINE_MAX_BYTES = 1024
        private const val RESULT_OUTPUT_STRING_MAX_BYTES = 4 * 1024

        /** Wrap a properties map into a minimal JSON-Schema object string. */
        private fun schema(properties: String, required: List<String> = emptyList()): String {
            val req = if (required.isEmpty()) "" else
                ""","required":[${required.joinToString(",") { "\"$it\"" }}]"""
            return """{"type":"object","properties":$properties$req}"""
        }
    }

    private fun RunJob.toMcpResult(includeOutput: Boolean, nodeId: String?): JsonObject {
        var contentTruncated = !contentComplete
        val selectedNodes = if (nodeId == null) nodes else mapOf(nodeId to nodes.getValue(nodeId))
        val outputOmitted = !contentComplete ||
            (!includeOutput && selectedNodes.values.any { it.output.isNotEmpty() })
        val boundedNodes = selectedNodes.mapValues { (_, node) ->
            val boundedError = node.error?.boundedUtf8(RESULT_ERROR_MAX_BYTES)?.also {
                contentTruncated = contentTruncated || it.truncated
            }?.value
            val selectedLogs = if (node.logs.size <= RESULT_LOG_MAX_LINES) {
                node.logs
            } else {
                contentTruncated = true
                val headSize = RESULT_LOG_MAX_LINES / 2
                val tailSize = RESULT_LOG_MAX_LINES - headSize
                node.logs.take(headSize) +
                    "… [${node.logs.size - RESULT_LOG_MAX_LINES} log lines omitted]" +
                    node.logs.takeLast(tailSize)
            }
            val boundedLogs = selectedLogs.map { line ->
                line.boundedUtf8(RESULT_LOG_LINE_MAX_BYTES).also {
                    contentTruncated = contentTruncated || it.truncated
                }.value
            }
            val boundedOutput = if (includeOutput) {
                node.output.map { output ->
                    output.boundedOutput().also {
                        contentTruncated = contentTruncated || it.truncated
                    }.value.jsonObject
                }
            } else {
                emptyList()
            }
            node.copy(error = boundedError, logs = boundedLogs, output = boundedOutput)
        }
        val boundedJobError = error?.boundedUtf8(RESULT_ERROR_MAX_BYTES)?.also {
            contentTruncated = contentTruncated || it.truncated
        }?.value
        val base = json.encodeToJsonElement(
            RunJob.serializer(),
            copy(error = boundedJobError, nodes = boundedNodes),
        ).jsonObject
        return buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("outputIncluded", includeOutput && contentComplete)
            put("outputOmitted", outputOmitted)
            put("truncated", contentTruncated)
            nodeId?.let { put("nodeId", it) }
        }
    }

    private data class Bounded<T>(val value: T, val truncated: Boolean)

    private fun JsonElement.boundedOutput(): Bounded<JsonElement> = when (this) {
        is JsonObject -> {
            var truncated = false
            val values = mapValues { (_, value) ->
                value.boundedOutput().also { truncated = truncated || it.truncated }.value
            }
            Bounded(JsonObject(values), truncated)
        }
        is JsonArray -> {
            var truncated = false
            val values = map { value ->
                value.boundedOutput().also { truncated = truncated || it.truncated }.value
            }
            Bounded(JsonArray(values), truncated)
        }
        is JsonPrimitive -> if (isString) {
            content.boundedUtf8(RESULT_OUTPUT_STRING_MAX_BYTES).let {
                Bounded(JsonPrimitive(it.value), it.truncated)
            }
        } else {
            Bounded(this, false)
        }
    }

    private fun String.boundedUtf8(maxBytes: Int): Bounded<String> {
        val bytes = encodeToByteArray()
        if (bytes.size <= maxBytes) return Bounded(this, false)
        var end = maxBytes
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        val omitted = bytes.size - end
        val prefix = bytes.copyOfRange(0, end).decodeToString()
        return Bounded("$prefix… [truncated, $omitted bytes omitted]", true)
    }
}
