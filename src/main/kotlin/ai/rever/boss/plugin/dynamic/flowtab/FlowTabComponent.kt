package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private val ToolbarBg = Color(0xFF202024)
private val PaletteBg = Color(0xFF1F1F23)
private val ToolbarBorder = Color(0xFF303036)
private val IconTint = Color(0xFFCDCDD4)
// Dark banner backgrounds for the single status bar (foreground text stays white).
private val ConfirmBg = Color(0xFF3A2E12)
private val NoticeBg = Color(0xFF26456E)
private val RunGreen = Color(0xFF2E7D32)
private const val WAITING_FOR_PREVIOUS_NOTICE = "Waiting for the previous run to stop…"
private const val STOPPING_PREVIOUS_NOTICE = "Stopping the previous run…"

/**
 * Flow tab component: a node-based canvas where nodes are spawned from the left
 * palette, dragged around, and connected by dragging from output to input ports.
 *
 * Layout: a top toolbar (zoom / fit / clear), a left node palette, and the
 * [FlowCanvas] filling the rest. Graph state is persisted per-tab to plugin
 * storage and restored on reopen.
 */
class FlowTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext,
    /** Shared external-MCP client (P7); null on hosts without storage or when the plugin
     *  couldn't stand it up. Its tools surface as palette nodes + an agent tool lane,
     *  flag-gated OFF by default. */
    private val externalMcp: ExternalMcpManager? = null,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = FlowTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val storageKey = "graph:${config.id}"

    // Run/graph state lives on the COMPONENT, not in the composition. Opening a
    // visible browser tab restructures the split tree, which repositions this tab's
    // composable into a different parent — `key()` doesn't move state across parents,
    // so Compose disposes and recreates the composition. If `state` were a
    // `remember`, that recreation would rebuild it empty and an in-flight run's node
    // status would vanish (it kept writing to the orphaned old instance). On the
    // component, it survives the recreation. The component itself is reused across
    // the split (the panel keeps its tab component).
    // One registry instance per tab, threaded to both the canvas state (palette +
    // geometry) and the executor (dispatch) so they agree on every kind-id.
    private val registry = builtinNodeRegistry()
    private val state = FlowGraphState(registry)
    private val executor = FlowExecutor(context, registry)
    // Prompt store + a headless controller sharing this tab's registry, so `agent` and
    // `lanager` nodes (P5) resolve the same kinds the canvas does. The lanager runs its
    // sub-flow through this controller (async, off the MCP fence).
    private val prompts = PromptRegistry(
        runCatching { context.pluginStorageFactory?.createStorage(FlowController.STORAGE_NAMESPACE) }.getOrNull()
    )
    private val controller = FlowController(context, { coroutineScope }, registry)
    // Bundled starter templates (scrape / agent), enumerated from resources/templates
    // via its index. Read-only; instantiated into a new tab from the gallery overlay.
    private val templateCatalog = TemplateCatalog()
    private val runJobs = RunJobFence(coroutineScope)
    private val runStatePersistence = RunStatePersistenceGate()
    private var initialized = false
    // "Realistic" mode: pace the run with human-like delays between steps, so it's
    // watchable and mimics a person driving the page. Observed by the toolbar.
    private var realistic by mutableStateOf(false)
    // The visible browser tab this flow opened; closed at the start of the next run
    // so each Run opens a fresh tab (no stale reuse, no stacked splits).
    private val visibleTabId = AtomicReference<String?>(null)

    init {
        // Surface the host registry's tools as live palette nodes, re-deriving whenever
        // the RBAC-filtered tool set changes. A null registry (older/sandboxed host)
        // degrades cleanly to built-ins only. The collector is tied to [coroutineScope]
        // and cancelled on destroy.
        runCatching { syncBossTools(context, registry, coroutineScope) }
        // Surface external MCP tools (P7) as palette nodes too — flag-gated inside refresh,
        // so nothing connects until the user enables external MCP and adds a server.
        externalMcp?.let { runCatching { syncExternalMcpTools(it, registry, coroutineScope) } }
        // Register the agent + lanager kinds so they appear in the palette and dispatch.
        runCatching {
            registry.register(defaultAgentNodeSpec(context, prompts, externalMcp))
            registry.register(lanagerNodeSpec(controller))
        }
        lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    controller.dispose()
                    coroutineScope.cancel()
                }
            }
        )
    }

    @Composable
    override fun Content() {
        var viewportSize by remember { mutableStateOf(Size.Zero) }
        val focusRequester = remember { FocusRequester() }

        val storage = remember {
            context.pluginStorageFactory?.createStorage("ai.rever.boss.plugin.dynamic.flowtab")
        }

        // Load persisted graph + last run state ONCE per component (guarded by the
        // [initialized] field, not a remembered flag). A split rebuilds this
        // composition; reloading then would clobber an in-flight run's status with
        // the stale persisted snapshot, so we must not redo it on recreation.
        LaunchedEffect(config.id) {
            if (!initialized) {
                val saved = runCatching { storage?.getJson(storageKey) }.getOrNull()
                if (!saved.isNullOrBlank()) {
                    runCatching {
                        state.load(json.decodeFromString(GraphSnapshot.serializer(), saved))
                    }
                }
                if (state.nodes.isEmpty()) {
                    state.addNode(NodeType.TRIGGER.name, Offset(320f, 200f))
                    state.selection = null
                }
                // Restore the last run's per-node status/output, if any.
                runCatching { storage?.getJson("$RUN_STATE_PREFIX${config.id}") }.getOrNull()?.let { rs ->
                    runCatching {
                        state.runStates.putAll(json.decodeFromString(RunSnapshot.serializer(), rs).toRuns())
                    }
                }
                initialized = true
            }
            focusRequester.requestFocus()
        }

        // Debounced autosave: waits for the one-time load, then writes after a quiet
        // period (collectLatest cancels a pending save when the graph changes again).
        LaunchedEffect(config.id) {
            if (storage == null) return@LaunchedEffect
            while (!initialized) delay(20)
            snapshotFlow { state.toSnapshot() }.collectLatest { snapshot ->
                delay(400)
                runCatching {
                    storage.putJson(storageKey, json.encodeToString(GraphSnapshot.serializer(), snapshot))
                }
            }
        }

        // While a run is in progress, pulse the canvas on a timer so live node status
        // shows up even when a visible browser pane sits idle between steps. Compose
        // Desktop renders on demand; the run writes node status from a background
        // thread, and with the page idle nothing invalidates the canvas, so status
        // used to "stick" until a frame was forced (tab switch, browser nav). A
        // frame-clock loop (withFrameNanos) stalls when idle — no frames to await —
        // so we drive this off a wall-clock delay instead: sendApplyNotifications
        // flushes the background writes, and bumping repaintTick (read by the canvas)
        // guarantees a fresh frame. ~10 fps is plenty for status and costs nothing
        // when not running.
        LaunchedEffect(state.isRunning) {
            while (state.isRunning) {
                delay(100)
                Snapshot.sendApplyNotifications()
                state.repaintTick++
            }
        }

        fun viewCenterScreen(): Offset =
            Offset(viewportSize.width / 2f, viewportSize.height / 2f)

        // ---- run wiring ---- (state/executor/runJobs/toggles are component fields,
        // so an in-flight run survives the split-induced composition recreation)
        fun startRun() {
            if (state.isRunning) return
            // Fresh start: close the browser tab a prior run opened (no-op if the user
            // already closed it) and clear tracking, so this run opens a new visible
            // tab rather than reusing a stale/closed one or stacking splits.
            visibleTabId.getAndSet(null)?.let { id ->
                runCatching { context.activeTabsProvider?.closeTab(id) }
            }
            state.clearRun()
            state.notice = null
            state.isRunning = true
            val waitingForPrevious = runJobs.hasActiveRun()
            if (waitingForPrevious) {
                state.notice = WAITING_FOR_PREVIOUS_NOTICE
            }
            val runToken = runStatePersistence.beginRun()
            val plan = state.nodes.map { PlanNode(it.id, it.kind, it.title, it.config) }
            val edges = state.edges.toList()
            val job = runJobs.launch(Dispatchers.Default) {
                try {
                    if (waitingForPrevious &&
                        runStatePersistence.isCurrent(runToken) &&
                        state.notice == WAITING_FOR_PREVIOUS_NOTICE
                    ) {
                        state.notice = null
                        Snapshot.sendApplyNotifications()
                    }
                    // Write status straight from the run thread. These are observable
                    // snapshot-state writes, so Compose picks them up on the next frame
                    // and the canvas updates live. (Marshalling them onto the Main scope
                    // instead queued them behind the browser's own Main-thread work, so
                    // they only landed once the browser tab was closed.)
                    executor.run(
                        plan, edges,
                        humanize = realistic,
                        onVisibleTab = { id ->
                            if (id == null) {
                                if (runStatePersistence.isCurrent(runToken)) {
                                    visibleTabId.set(null)
                                }
                            } else {
                                val tabId = id
                                if (runStatePersistence.isCurrent(runToken)) {
                                    visibleTabId.set(tabId)
                                    // Close a tab published across a concurrent Clear
                                    // after the first generation check passed.
                                    if (!runStatePersistence.isCurrent(runToken) &&
                                        visibleTabId.compareAndSet(tabId, null)
                                    ) {
                                        coroutineScope.launch(Dispatchers.Main) {
                                            runCatching { context.activeTabsProvider?.closeTab(tabId) }
                                        }
                                    }
                                } else {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        runCatching { context.activeTabsProvider?.closeTab(tabId) }
                                    }
                                }
                            }
                        },
                        // Seed this flow's own id so a lanager pointing back at it is caught
                        // as a cycle at depth 0, not only one level deeper (red-team S7).
                        ancestry = setOf(config.id),
                    ) { id, run ->
                        // A check/write can straddle Clear, but orphaned ids do not render
                        // and the separately gated finalizer cannot persist them.
                        if (runStatePersistence.isCurrent(runToken)) state.runStates[id] = run
                    }
                } catch (ce: CancellationException) {
                    // stopped by user
                } catch (e: Exception) {
                    if (runStatePersistence.isCurrent(runToken)) {
                        state.runError = e.message ?: e.toString()
                    }
                } finally {
                    // Give ordinary Stop immediate feedback. invokeOnCompletion below
                    // remains the fallback for a queued job cancelled before this block.
                    if (runStatePersistence.isCurrent(runToken)) state.isRunning = false
                    // Persist the run results (capped) so they survive reopening.
                    try {
                        val persisted = persistRunStateOnIo(runStatePersistence, runToken) {
                            storage?.putJson(
                                "$RUN_STATE_PREFIX${config.id}",
                                json.encodeToString(RunSnapshot.serializer(), state.runStates.toRunSnapshot())
                            )
                        }
                        if (!persisted && runStatePersistence.isCurrent(runToken)) {
                            state.notice = "Run completed, but its saved state timed out"
                            Snapshot.sendApplyNotifications()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Run-state persistence is best-effort, as before.
                    }
                }
            }
            // This also runs when a queued job is cancelled before its block starts.
            job.invokeOnCompletion { cause ->
                if (runStatePersistence.isCurrent(runToken)) {
                    state.isRunning = false
                    state.notice = when {
                        cause is PredecessorRunTimeoutException ->
                            "Previous run is not responding; queued run was cancelled"
                        state.notice == WAITING_FOR_PREVIOUS_NOTICE ||
                            state.notice == STOPPING_PREVIOUS_NOTICE -> null
                        else -> state.notice
                    }
                    Snapshot.sendApplyNotifications()
                }
            }
        }

        fun stopRun() {
            runJobs.cancelAll()
            if (state.notice == WAITING_FOR_PREVIOUS_NOTICE) {
                state.notice = STOPPING_PREVIOUS_NOTICE
            }
        }

        // ---- export / import ----
        val uiScope = rememberCoroutineScope()
        val prettyJson = remember { Json { prettyPrint = true; ignoreUnknownKeys = true } }

        fun exportFlow() {
            val text = prettyJson.encodeToString(GraphSnapshot.serializer(), state.toSnapshot())
            val picker = context.filePickerProvider
            if (picker != null) {
                picker.pickSaveFile(suggestedFileName = "flow.json", filters = listOf("json")) { path ->
                    if (path != null) runCatching { java.io.File(path).writeText(text) }
                }
            } else {
                context.clipboardProvider?.setText(text)
            }
        }

        // Seed a new Flow tab with [snapshotJson] and open it — imports land in their
        // own tab rather than overwriting the current canvas. A tab loads its graph
        // from storage key "graph:<tabId>", so we write there first, then open the tab.
        // Falls back to the current tab if tab ops / storage aren't available.
        fun openImportedInNewTab(snapshotJson: String, title: String, notice: String) {
            val parsed = runCatching { json.decodeFromString(GraphSnapshot.serializer(), snapshotJson) }.getOrNull()
            if (parsed == null) { state.runError = "Import failed: not a valid flow"; return }
            val splitView = context.splitViewOperations
            val store = storage
            if (splitView == null || store == null) {
                runCatching { state.load(parsed) }
                state.notice = "Imported into the current tab (open-in-new-tab unavailable here)"
                return
            }
            uiScope.launch {
                val newId = "flow-${java.util.UUID.randomUUID()}"
                runCatching { store.putJson("graph:$newId", snapshotJson) }
                splitView.openTab(FlowTabData(id = newId, title = title))
                state.notice = notice
            }
        }

        fun doClear() {
            // Invalidate before cancelling or mutating state so late executor callbacks
            // and the run finalizer cannot repopulate the cleared snapshot.
            val invalidation = runStatePersistence.invalidateRun()
            runJobs.cancelAll()
            state.isRunning = false
            state.notice = null
            visibleTabId.getAndSet(null)?.let { id ->
                runCatching { context.activeTabsProvider?.closeTab(id) }
            }
            state.nodes.clear()
            state.edges.clear()
            state.clearRun()
            state.selection = null
            // Component scope survives split-collapse recomposition. Closing the Flow
            // tab itself still cancels this scope before an undispatched clear can start.
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val result = runStatePersistence.clearAfterInvalidation(invalidation) {
                        clearPersistedRunState(storage, config.id)
                    }
                    when (result) {
                        RunStateClearResult.TIMED_OUT -> {
                            state.notice = "Flow cleared, but saved run-state cleanup timed out"
                            Snapshot.sendApplyNotifications()
                        }
                        RunStateClearResult.CLEARED,
                        RunStateClearResult.PRESERVED_NEWER -> Unit
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    state.notice = "Flow cleared, but saved run state could not be removed: ${failure.message}"
                    Snapshot.sendApplyNotifications()
                }
            }
        }

        // Import an RPA Recorder config → a new tab with a chain of browser nodes.
        fun applyRecording(text: String) {
            val result = runCatching { RpaRecorderImport.convert(text) }.getOrElse {
                state.runError = "RPA import failed: ${it.message}"; return
            }
            if (result.steps.isEmpty()) { state.notice = "No importable actions in that recording"; return }
            // Build the chain in a throwaway graph, then open it as its own tab.
            val staged = FlowGraphState()
            staged.importChain(result.steps, Offset(320f, 200f))
            val snapshotJson = prettyJson.encodeToString(GraphSnapshot.serializer(), staged.toSnapshot())
            val skipped = if (result.skipped.isNotEmpty()) " · skipped: ${result.skipped.joinToString(", ")}" else ""
            openImportedInNewTab(snapshotJson, "Imported RPA", "Imported ${result.steps.size} node(s) in a new tab$skipped")
        }

        // Smart import: classify the blob and route accordingly (one Import button, no
        // format picker to get wrong). A flow graph opens in a new tab; a lanager template
        // (a graph carrying metadata) opens the same way but labelled as a template; a
        // graph from a newer schema is refused gracefully; anything else is treated as an
        // RPA recording. Routing + schema-gating live in the shared, tested [classifyImport].
        fun importAny(text: String) {
            when (val r = classifyImport(text)) {
                is TemplateImportResult.Graph -> {
                    val name = r.snapshot.metadata?.name.orEmpty()
                    val (title, notice) = if (r.kind == ImportKind.TEMPLATE) {
                        "Imported Template" to ("Opened template" + (if (name.isNotBlank()) " '$name'" else "") + " in a new tab")
                    } else {
                        "Imported Flow" to "Opened the imported flow in a new tab"
                    }
                    openImportedInNewTab(text, title, notice)
                }
                TemplateImportResult.Recording -> applyRecording(text)
                is TemplateImportResult.RefusedNewer ->
                    state.notice = "Can't import: this file needs a newer Flow (schema v${r.schemaVersion}). Update the plugin."
                is TemplateImportResult.Invalid ->
                    state.runError = "Import failed: ${r.message}"
            }
        }

        fun importFlow() {
            val picker = context.filePickerProvider
            if (picker != null) {
                picker.pickFile(title = "Import flow or recording", filters = listOf("json")) { path ->
                    if (path != null) runCatching { java.io.File(path).readText() }.getOrNull()?.let(::importAny)
                }
            } else {
                context.clipboardProvider?.readText()?.let(::importAny)
            }
        }

        // Instantiate a bundled template into its own new tab, reusing the same
        // storage-seated open-in-new-tab path a file import uses.
        fun useTemplate(entry: TemplateEntry) {
            openImportedInNewTab(
                entry.raw,
                title = entry.name.ifBlank { "Template" },
                notice = "Created a flow from template '${entry.name}'",
            )
        }

        val selectedNode = (state.selection as? Selection.Node)?.let { state.nodeById(it.id) }
        var confirmClear by remember { mutableStateOf(false) }
        var showGallery by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(CanvasBackground)) {
            Toolbar(
                scale = state.scale,
                isRunning = state.isRunning,
                realistic = realistic,
                onToggleRealistic = { realistic = !realistic },
                headless = state.allBrowserHeadless,
                onToggleHeadless = { state.setAllBrowserHeadless(!state.allBrowserHeadless) },
                onRun = { startRun() },
                onStop = { stopRun() },
                onNewFlow = {
                    context.splitViewOperations?.openTab(
                        FlowTabData(id = "flow-${java.util.UUID.randomUUID()}", title = "Flow")
                    )
                },
                onTemplates = { showGallery = true },
                onZoomIn = { state.zoomBy(1.2f, viewCenterScreen()) },
                onZoomOut = { state.zoomBy(1f / 1.2f, viewCenterScreen()) },
                onFit = { state.fitToContent(viewportSize) },
                onReset = { state.resetView() },
                onClear = { confirmClear = true },
                onExport = { exportFlow() },
                onImport = { importFlow() }
            )

            // One status bar at a time, prioritized: clear-confirm > run error > notice.
            // (Stacking three banners pushed the canvas down and read as noise.)
            when {
                confirmClear -> Row(
                    modifier = Modifier.fillMaxWidth().background(ConfirmBg).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Clear all ${state.nodes.size} nodes and ${state.edges.size} edges?", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Clear",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(FlowTheme.rSm)).background(FlowTheme.Error)
                            .clickable { confirmClear = false; doClear() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Cancel",
                        color = FlowTheme.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { confirmClear = false }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                state.runError != null -> Text(
                    text = "Error: ${state.runError}  (click to dismiss)",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(FlowTheme.Error).clickable { state.runError = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                state.notice != null -> Text(
                    text = state.notice!!,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NoticeBg)
                        .clickable { state.notice = null }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            // Don't steal Backspace/Delete while the picker's search box is open.
                            if (event.type == KeyEventType.KeyDown &&
                                state.pickerRequest == null &&
                                (event.key == Key.Delete || event.key == Key.Backspace)
                            ) {
                                if (state.selection != null) {
                                    state.deleteSelection()
                                    true
                                } else false
                            } else false
                        }
                ) {
                    FlowCanvas(
                        state = state,
                        onViewportSize = { viewportSize = it }
                    )
                }

                if (selectedNode != null) {
                    FlowInspector(state, selectedNode)
                }
            }
        }

            // Template gallery overlay: instantiate a bundled starter into a new tab.
            if (showGallery) {
                TemplateGallery(
                    catalog = templateCatalog,
                    onPick = { entry -> showGallery = false; useTemplate(entry) },
                    onDismiss = { showGallery = false },
                )
            }
        }
    }
}

@Composable
private fun Toolbar(
    scale: Float,
    isRunning: Boolean,
    realistic: Boolean,
    onToggleRealistic: () -> Unit,
    headless: Boolean,
    onToggleHeadless: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onNewFlow: () -> Unit,
    onTemplates: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onReset: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(ToolbarBg)
            .border(width = 1.dp, color = ToolbarBorder)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FlowTabType.icon,
            contentDescription = null,
            tint = FlowTheme.PrimaryTint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Flow", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.width(12.dp))
        // Run / Stop
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (isRunning) FlowTheme.Error else RunGreen)
                .clickable(onClick = if (isRunning) onStop else onRun)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isRunning) "Stop" else "Run",
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(if (isRunning) "Stop" else "Run", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.width(8.dp))
        // Realistic-run toggle: human-like pauses between steps (watchable + lifelike).
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (realistic) FlowTheme.Primary.copy(alpha = 0.22f) else Color.Transparent)
                .border(1.dp, if (realistic) FlowTheme.Primary else ToolbarBorder, RoundedCornerShape(7.dp))
                .clickable(onClick = onToggleRealistic)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = "Realistic run (human-like delays between steps)",
                tint = if (realistic) FlowTheme.PrimaryTint else IconTint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Realistic",
                color = if (realistic) FlowTheme.PrimaryTint else IconTint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.width(8.dp))
        // Headless run-level toggle: force all browser nodes headless this run.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(if (headless) FlowTheme.Primary.copy(alpha = 0.22f) else Color.Transparent)
                .border(1.dp, if (headless) FlowTheme.Primary else ToolbarBorder, RoundedCornerShape(7.dp))
                .clickable(onClick = onToggleHeadless)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Headless (force all browser nodes to run with no visible window)",
                tint = if (headless) FlowTheme.PrimaryTint else IconTint,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Headless",
                color = if (headless) FlowTheme.PrimaryTint else IconTint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(FlowTheme.Primary)
                .clickable(onClick = onNewFlow)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("New Flow", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.width(8.dp))
        // Template gallery: instantiate a bundled starter (scrape / agent) into a new tab.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, ToolbarBorder, RoundedCornerShape(7.dp))
                .clickable(onClick = onTemplates)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.GridView, contentDescription = "Templates", tint = IconTint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text("Templates", color = IconTint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.weight(1f))

        ToolbarButton(Icons.Filled.ZoomOut, "Zoom out", onZoomOut)
        Text(
            text = "${(scale * 100).roundToInt()}%",
            color = IconTint,
            fontSize = 12.sp,
            modifier = Modifier.width(48.dp),
        )
        ToolbarButton(Icons.Filled.ZoomIn, "Zoom in", onZoomIn)
        ToolbarButton(Icons.Filled.FitScreen, "Fit to content", onFit)
        ToolbarButton(Icons.Filled.RestartAlt, "Reset view", onReset)
        ToolbarButton(Icons.Filled.DeleteOutline, "Clear canvas", onClear)
        ToolbarButton(Icons.Filled.SaveAlt, "Export workflow", onExport)
        ToolbarButton(Icons.Filled.FileOpen, "Import flow or recording", onImport)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    TooltipArea(
        delayMillis = 350,
        tooltip = {
            Box(
                Modifier
                    .shadow(4.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF111114))
                    .border(1.dp, ToolbarBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(description, color = Color.White, fontSize = 11.sp)
            }
        }
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(icon, contentDescription = description, tint = IconTint, modifier = Modifier.size(18.dp))
        }
    }
}
