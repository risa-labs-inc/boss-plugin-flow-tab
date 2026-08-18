package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns N long-lived browser sessions keyed by `session_id`, each with its own
 * [Mutex] fence. This externalizes browser statefulness out of a single-session
 * [RunContext] (red-team F2/F12): a session outlives the call that opened it and is
 * reachable by any caller that holds its id — a native browser node, an agent, or an
 * external MCP caller with no [RunContext] at all.
 *
 * Opening a session uses [BrowserService]/[ai.rever.boss.plugin.api.ActiveTabsProvider]
 * exactly as the old `RunContext.openSession` did:
 *  - **headless** → an offscreen [ai.rever.boss.plugin.browser.BrowserHandle] on a
 *    throwaway ([BrowserConfig.ephemeralProfile]) profile, wrapped by
 *    [BrowserHandleIntegration]; disposed on [close].
 *  - **visible** → a real Fluck tab in a right split (created on [Dispatchers.Main]),
 *    wrapped by [LoadAwaitingIntegration]. Interactive owners leave it open on [close]
 *    for inspection; headless owners close it. Falls back to headless if the host
 *    can't open one.
 *
 * Not tied to a run: [RunContext] draws its default session from here so native nodes
 * keep working unchanged, while [FlowBrowserToolSource] drives arbitrary sessions by id.
 */
class SessionRegistry(
    private val context: PluginContext,
    /** Reports the visible browser tab id an open produced, so the UI can close it
     *  before the next run (each run opens a fresh tab). */
    private val onVisibleTab: (String?) -> Unit = {},
    /** Whether [close] owns and closes visible tabs. False for an interactive canvas,
     * true for MCP/headless runs that have no UI owner to reclaim them. */
    private val closeVisibleTabsOnClose: Boolean = false,
) {
    private class Entry(
        val integration: BrowserIntegration,
        val visibleTabId: String? = null,
        val closer: suspend () -> Unit,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    /** Per-id fences. Kept separate from [entries] so a mutex is stable for an id
     *  across (re)opens and can exist before/after a session's integration does. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val counter = AtomicInteger(0)

    /** Mint a fresh, unique session id. */
    fun newSessionId(): String = "s${counter.incrementAndGet()}"

    /** The stable fence for [id] (created on first use). */
    fun mutexFor(id: String): Mutex = mutexes.getOrPut(id) { Mutex() }

    /** Ids of currently-open sessions. */
    fun ids(): Set<String> = entries.keys.toSet()

    /** The [BrowserIntegration] for [id], or null if no such session is open. */
    fun get(id: String): BrowserIntegration? = entries[id]?.integration

    /** Focus the visible host tab backing [id]. Headless sessions return false. */
    suspend fun focus(id: String): Boolean {
        val tabId = entries[id]?.visibleTabId ?: return false
        val tabs = context.activeTabsProvider ?: return false
        return withContext(Dispatchers.Main) {
            tabs.refreshTabs()
            val panelId = tabs.activeTabs.value.firstOrNull { it.tabId == tabId }?.panelId
                ?: return@withContext false
            tabs.selectTab(tabId, panelId)
            true
        }
    }

    /**
     * Open a browser session and register it under [id] (a fresh id by default).
     * Returns the session id. When [headless] is false (default), opens a visible
     * right-split tab; if the host can't, falls back to an offscreen headless browser.
     */
    suspend fun open(headless: Boolean, id: String = newSessionId(), log: (String) -> Unit = {}): String {
        val service = context.browserService
            ?: throw ExecError("Browser is unavailable in this build (no browserService)")
        if (!service.isAvailable()) throw ExecError("Browser engine is not available")
        val entry = if (headless) {
            openHeadless(service)
        } else {
            openVisible(log) ?: run {
                log("Visible browser unavailable — running headless (offscreen)")
                openHeadless(service)
            }
        }
        // Re-opening an id must not orphan the prior browser (red-team S5): dispose the
        // handle we're replacing before installing the new one.
        entries.put(id, entry)?.let { prior -> runCatching { prior.closer.invoke() } }
        mutexFor(id)
        return id
    }

    /**
     * Open [id] only when it is absent, under the same per-session fence used by
     * browser actions. Returns true when an existing session was reused. This keeps
     * a run-bound agent open atomic with native browser nodes on the default session.
     */
    suspend fun openIfAbsent(headless: Boolean, id: String, log: (String) -> Unit = {}): Boolean =
        mutexFor(id).withLock {
            if (entries.containsKey(id)) {
                true
            } else {
                open(headless, id, log)
                false
            }
        }

    /**
     * Run [action] against the session named [id] while holding that session's fence,
     * so two callers never drive the same page concurrently. Throws [ExecError] if the
     * session isn't open.
     */
    suspend fun <T> withSession(id: String, action: suspend (BrowserIntegration) -> T): T {
        val entry = entries[id] ?: throw ExecError("No browser session '$id'")
        return mutexFor(id).withLock { action(entry.integration) }
    }

    /** Close and release session [id]. Headless handles are disposed; visible tabs
     *  are closed only when this registry owns their lifecycle. No-op if absent. */
    suspend fun close(id: String) {
        val entry = entries.remove(id) ?: return
        runCatching { entry.closer.invoke() }
    }

    /** Close every open session. */
    suspend fun closeAll() {
        ids().forEach { close(it) }
    }

    private suspend fun openHeadless(service: BrowserService): Entry {
        val handle = service.createBrowser(BrowserConfig().apply { ephemeralProfile = true })
            ?: throw ExecError("Failed to open a browser session")
        return Entry(BrowserHandleIntegration(handle)) { service.disposeBrowser(handle) }
    }

    /**
     * Open a visible browser tab in a right split and wait for it to become drivable.
     * Split-view/tab creation must happen on the UI thread, so host calls are
     * marshalled to [Dispatchers.Main]. Returns null (with a logged reason) if the
     * host can't open one, so [open] can fall back to headless.
     */
    private suspend fun openVisible(log: (String) -> Unit): Entry? {
        val tabs = context.activeTabsProvider ?: run {
            log("No activeTabsProvider in this context")
            return null
        }
        val tabId = try {
            withContext(Dispatchers.Main) { tabs.createBrowserTabInRightSplit("about:blank", "Browser") }
        } catch (e: Exception) {
            log("Right-split open threw: ${e.message ?: e.toString()}")
            null
        }
        if (tabId == null) {
            log("createBrowserTabInRightSplit returned null (host has no split support?)")
            return null
        }
        log("Opened browser tab in right split ($tabId); waiting for it to attach…")
        var waited = 0
        while (waited < VISIBLE_TAB_TIMEOUT_MS) {
            val integration = withContext(Dispatchers.Main) { tabs.getBrowserIntegration(tabId) }
            if (integration != null) {
                onVisibleTab(tabId)
                return Entry(
                    integration = LoadAwaitingIntegration(integration),
                    visibleTabId = tabId,
                    closer = {
                        if (closeVisibleTabsOnClose) {
                            withContext(Dispatchers.Main) { tabs.closeTab(tabId) }
                        }
                    },
                )
            }
            delay(POLL_INTERVAL_MS.toLong()); waited += POLL_INTERVAL_MS
        }
        log("Browser tab never became drivable after ${VISIBLE_TAB_TIMEOUT_MS}ms")
        return null
    }

    private companion object {
        const val VISIBLE_TAB_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 100
    }
}
