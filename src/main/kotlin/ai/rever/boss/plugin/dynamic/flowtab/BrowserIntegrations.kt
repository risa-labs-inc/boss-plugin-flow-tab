package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.delay

/**
 * Flow drives browsers through the host's existing [BrowserIntegration] interface,
 * so node executors don't care whether a run is headless or visible:
 *  - **visible** runs get the host's own [BrowserIntegration] for a real Fluck tab
 *    (via `ActiveTabsProvider.getBrowserIntegration`), wrapped by
 *    [LoadAwaitingIntegration] so navigate() blocks until the page is loaded;
 *  - **headless** runs get [BrowserHandleIntegration], an adapter over an offscreen
 *    [BrowserHandle].
 */

/** Adapts an offscreen [BrowserHandle] to [BrowserIntegration] for headless runs. */
class BrowserHandleIntegration(private val handle: BrowserHandle) : BrowserIntegration {
    override suspend fun executeJavaScript(script: String): Any? = handle.executeJavaScript(script)
    // loadUrlAndWait already blocks until the page has loaded.
    override suspend fun navigate(url: String) = handle.loadUrlAndWait(url)
    override fun isBrowserAvailable(): Boolean = handle.isValid
    override suspend fun getCurrentUrl(): String? = handle.getCurrentUrl()
}

/**
 * Wraps a host [BrowserIntegration] (a visible tab) so that [navigate] blocks until
 * the page finishes loading. The raw integration's navigate only *starts* the load
 * and returns immediately, so a following Extract/Click would run against the old
 * (or still-loading) page; this waits for `document.readyState == "complete"`,
 * bounded by a timeout. [MIN_WAIT_MS] guards against the previous page reporting
 * "complete" before the new navigation has committed.
 */
class LoadAwaitingIntegration(private val delegate: BrowserIntegration) : BrowserIntegration {
    override suspend fun executeJavaScript(script: String): Any? = delegate.executeJavaScript(script)

    override suspend fun navigate(url: String) {
        delegate.navigate(url)
        var waited = 0
        while (waited < NAV_TIMEOUT_MS) {
            delay(POLL_MS.toLong()); waited += POLL_MS
            val ready = runCatching { delegate.executeJavaScript("document.readyState") }.getOrNull()
            if (ready == "complete" && waited >= MIN_WAIT_MS) return
        }
    }

    override fun isBrowserAvailable(): Boolean = delegate.isBrowserAvailable()
    override suspend fun getCurrentUrl(): String? = delegate.getCurrentUrl()

    private companion object {
        const val NAV_TIMEOUT_MS = 15_000
        const val POLL_MS = 150
        const val MIN_WAIT_MS = 450
    }
}
