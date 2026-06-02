package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

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

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register the main-panel TAB TYPE that renders the canvas.
        context.tabRegistry.registerTabType(FlowTabType) { tabInfo, ctx ->
            FlowTabComponent(ctx, tabInfo, context)
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
    }

    override fun dispose() {
        // Unregister tab type + panel when the plugin is unloaded.
        pluginContext?.tabRegistry?.unregisterTabType(FlowTabType.typeId)
        pluginContext?.panelRegistry?.unregisterPanel(FlowLauncherInfo.id)
        FlowLauncherInfo.onLaunch = null // drop the captured context to avoid a leak
        pluginContext = null
    }
}
