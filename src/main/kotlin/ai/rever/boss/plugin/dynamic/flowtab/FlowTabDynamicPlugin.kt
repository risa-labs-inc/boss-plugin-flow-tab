package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.McpServerController
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flow Tab dynamic plugin - Loaded from external JAR.
 *
 * Provides a node-based flow canvas in the main panel area: spawn nodes from a
 * palette, drag them around, and connect their ports with edges (n8n style).
 *
 * The plugin is fully self-contained — all rendering, gestures, and graph state
 * live in this module. It only depends on the host for the tab registry, an
 * optional storage provider (graph persistence), and an optional tab-update
 * provider (tab title updates).
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 */
class FlowTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.flowtab"
    override val displayName: String = "Flow"
    override val version: String = "1.0.0"
    override val description: String =
        "Node-based flow canvas — spawn nodes and connect them with edges, n8n style."
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-flow-tab"

    private var pluginContext: PluginContext? = null
    /** Shared external-MCP client (P7), feature-flagged OFF by default. One instance is
     *  threaded into every Flow tab + the headless MCP path so external servers are
     *  connected once and reaped once (red-team F9). Null on a host without storage. */
    private var externalMcp: ExternalMcpManager? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        // Bring up the shared external-MCP manager (does not connect anything until the
        // feature flag is enabled and servers are configured). Kept as a field so both
        // the tab UI and the headless MCP path share it and dispose() reaps its children.
        val external = runCatching {
            val storage = context.pluginStorageFactory?.createStorage(FlowController.STORAGE_NAMESPACE)
            ExternalMcpManager(storage, SecretResolver.fromSecrets(context), SettingsStore(storage))
        }.getOrNull()
        externalMcp = external

        // Register the main-panel TAB TYPE that renders the canvas.
        context.tabRegistry.registerTabType(FlowTabType) { tabInfo, ctx ->
            FlowTabComponent(ctx, tabInfo, context, external)
        }

        // Clicking the "Flow" sidebar item opens a Flow tab *directly* — the
        // host runs this onLaunch instead of toggling the docked pane. The panel
        // is still registered so the sidebar shows a "Flow" entry to click; its
        // FlowLauncherComponent only renders if the pane is opened manually.
        FlowLauncherInfo.onLaunch = {
            context.splitViewOperations?.openTab(
                FlowTabData(id = "flow-${java.util.UUID.randomUUID()}", title = "Flow")
            )
        }
        context.panelRegistry.registerPanel(FlowLauncherInfo) { ctx, panelInfo ->
            FlowLauncherComponent(ctx, panelInfo, context)
        }

        // Register the fixed, generic Flow MCP tool set into the host `boss` server so
        // an attached agent can headlessly author and run flows/prompts (P2, F1/F5/F7).
        // Storage-seated: the controller and prompt store share the tab's namespace, so
        // agent- and UI-authored artifacts read the same store. Everything degrades
        // gracefully — a host without an MCP registry ignores the registration.
        runCatching {
            val storage = context.pluginStorageFactory?.createStorage(FlowController.STORAGE_NAMESPACE)
            val controller = FlowController(context)
            val prompts = PromptRegistry(storage)
            // Make agent + lanager kinds runnable in headless (MCP-driven) runs too, sharing
            // the controller's registry so a lanager's sub-run resolves the same kinds (P5).
            controller.registry.register(defaultAgentNodeSpec(context, prompts, external))
            controller.registry.register(lanagerNodeSpec(controller))
            // Surface external MCP tools as headless nodes too (flag-gated inside refresh),
            // so a Claude-Code-authored flow can wire `tool:ext:<server>/*` nodes.
            external?.let { syncExternalMcpTools(it, controller.registry, context.pluginScope) }
            context.registerMcpToolProvider(FlowMcpToolProvider(controller, prompts))

            // The MCP *server* may be off by default; resolve its controller lazily and
            // only note its presence — providers are picked up whenever the server runs.
            val serverController = runCatching {
                context.getPluginAPI(McpServerController::class.java)
            }.getOrNull()
            if (serverController == null) {
                println("[flow-tab] MCP server controller unavailable; flow_ tools registered, will surface when the boss MCP server is enabled")
            }
        }.onFailure { println("[flow-tab] failed to register MCP tool provider: ${it.message}") }
    }

    override fun dispose() {
        // Unregister tab type + panel when the plugin is unloaded.
        pluginContext?.tabRegistry?.unregisterTabType(FlowTabType.typeId)
        pluginContext?.panelRegistry?.unregisterPanel(FlowLauncherInfo.id)
        runCatching { pluginContext?.unregisterMcpToolProvider(FlowMcpToolProvider.PROVIDER_ID) }
        // Reap any external MCP child processes / sockets (red-team F9), bounded so a
        // hung server can't block plugin teardown.
        externalMcp?.let { mgr ->
            runCatching { runBlocking { withTimeoutOrNull(5_000) { mgr.disposeAll() } } }
        }
        externalMcp = null
        FlowLauncherInfo.onLaunch = null // drop the captured context to avoid a leak
        pluginContext = null
    }
}
