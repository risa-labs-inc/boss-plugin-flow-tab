package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService

/**
 * A browser the flow drives, abstracting the two ways the host can hand us one:
 *  - an offscreen [BrowserHandle] (headless runs), or
 *  - a real, visible Fluck browser tab — opened in a right split — driven through
 *    [BrowserIntegration] (visible runs, so the page can be watched live).
 *
 * Node executors only ever touch this interface, so they don't care which kind
 * of session is backing them.
 */
interface BrowserSession {
    suspend fun navigate(url: String)
    suspend fun executeJavaScript(script: String): Any?
    suspend fun close()
}

/** Headless session: an offscreen [BrowserHandle] on a throwaway profile. */
class HandleSession(
    private val handle: BrowserHandle,
    private val service: BrowserService,
) : BrowserSession {
    override suspend fun navigate(url: String) = handle.loadUrlAndWait(url)
    override suspend fun executeJavaScript(script: String): Any? = handle.executeJavaScript(script)
    override suspend fun close() {
        service.disposeBrowser(handle)
    }
}

/**
 * Visible session: a real host browser tab (opened in a right split) driven via
 * [BrowserIntegration]. The tab is left open on [close] so the user can inspect
 * the final page; the host tears it down with the window/tab.
 */
class TabSession(
    private val integration: BrowserIntegration,
    val tabId: String,
) : BrowserSession {
    override suspend fun navigate(url: String) = integration.navigate(url)
    override suspend fun executeJavaScript(script: String): Any? = integration.executeJavaScript(script)
    override suspend fun close() { /* leave the visible tab open for inspection */ }
}
