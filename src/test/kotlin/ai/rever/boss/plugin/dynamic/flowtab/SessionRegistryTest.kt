package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.ContextMenuCallback
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3: browser statefulness externalized into a [SessionRegistry] (F2/F12). Uses an
 * in-memory fake [BrowserService] that mints a fresh [BrowserHandle] per session, so
 * we can pin: open/get/close lifecycle, that two sessions are isolated, that the
 * per-session fence serializes concurrent access, and that [FlowBrowserToolSource]
 * drives the session named by its `session_id` arg.
 */
class SessionRegistryTest {

    // ---- in-memory fakes ----------------------------------------------------

    /** A fake page: records the JS/navigation calls made against one handle, and can
     *  answer executeJavaScript via a per-page responder. Tracks max concurrency so a
     *  fence test can assert serialized access. */
    private class FakePage(val responder: (String) -> Any? = { true }, val delayMs: Long = 0) : BrowserHandle {
        val navigated = mutableListOf<String>()
        val jsCalls = mutableListOf<String>()
        private val concurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        var disposed = false

        override suspend fun loadUrl(url: String) = bracket { navigated.add(url) }
        override suspend fun loadUrlAndWait(url: String) = bracket { navigated.add(url) }
        override suspend fun executeJavaScript(script: String): Any? {
            var result: Any? = null
            bracket { jsCalls.add(script); result = responder(script) }
            return result
        }

        private suspend fun bracket(body: () -> Unit) {
            val c = concurrency.incrementAndGet()
            maxConcurrency.updateAndGet { max(it, c) }
            try {
                body()
                if (delayMs > 0) delay(delayMs)
            } finally {
                concurrency.decrementAndGet()
            }
        }

        // --- unused BrowserHandle surface ---
        override val id = "fake"
        override val isValid = true
        override fun getCurrentUrl() = ""
        override fun getTitle() = ""
        override fun addNavigationListener(listener: (String) -> Unit) {}
        override fun removeNavigationListener(listener: (String) -> Unit) {}
        override fun addTitleListener(listener: (String) -> Unit) {}
        override fun removeTitleListener(listener: (String) -> Unit) {}
        override fun addFaviconListener(listener: (String?) -> Unit) {}
        override fun removeFaviconListener(listener: (String?) -> Unit) {}
        override fun goBack() {}
        override fun goForward() {}
        override fun reload() {}
        override fun stop() {}
        override fun canGoBack() = false
        override fun canGoForward() = false
        override fun getZoomLevel() = 1.0
        override fun setZoomLevel(level: Double) {}
        override fun zoomIn() {}
        override fun zoomOut() {}
        override fun resetZoom() {}
        override fun addZoomListener(listener: (Double) -> Unit) {}
        override fun removeZoomListener(listener: (Double) -> Unit) {}
        override fun isLoading() = false
        override fun addLoadingListener(listener: (Boolean) -> Unit) {}
        override fun removeLoadingListener(listener: (Boolean) -> Unit) {}
        override fun isSecure() = false
        override fun setContextMenuCallback(callback: ContextMenuCallback?) {}
        override suspend fun fillCredentials(username: String, password: String, fillBoth: Boolean) = true
        override fun copySelection() {}
        override fun paste() {}
        override fun cut() {}
        override fun selectAll() {}
        override fun setOpenInNewTabCallback(callback: (String) -> Unit) {}
        override fun requestPictureInPicture() {}
        override fun setFullscreenHandler(tabId: String, onEnterFullscreen: () -> Unit, onExitFullscreen: () -> Unit) {}
        override fun requestExitFullscreen() {}
        override fun showDevTools() {}
        @Composable override fun Content() {}
        override fun dispose() {}
    }

    /** Mints a fresh [FakePage] per createBrowser, so distinct sessions get distinct pages. */
    private class MultiFakeService(private val make: () -> FakePage) : BrowserService {
        val pages = mutableListOf<FakePage>()
        override fun isAvailable() = true
        override suspend fun createBrowser(config: BrowserConfig): BrowserHandle =
            make().also { pages.add(it) }
        override suspend fun disposeBrowser(handle: BrowserHandle) { (handle as FakePage).disposed = true }
        override fun getActiveBrowserCount() = pages.size
    }

    private class Ctx(private val service: BrowserService?) : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(Dispatchers.Default)
        override val browserService: BrowserService? get() = service
    }

    private fun registry(make: () -> FakePage = { FakePage() }): Pair<SessionRegistry, MultiFakeService> {
        val svc = MultiFakeService(make)
        return SessionRegistry(Ctx(svc)) to svc
    }

    // ---- lifecycle ----------------------------------------------------------

    @Test
    fun `open registers a session that get returns and close removes`() = runBlocking {
        val (reg, _) = registry()
        val id = reg.open(headless = true)
        assertNotNull(reg.get(id))
        reg.close(id)
        assertNull(reg.get(id))
    }

    @Test
    fun `open without a browserService errors`() = runBlocking {
        val reg = SessionRegistry(Ctx(null))
        assertTrue(runCatching { reg.open(headless = true) }.exceptionOrNull() is ExecError)
    }

    @Test
    fun `two sessions are isolated`() = runBlocking {
        val (reg, svc) = registry { FakePage() }
        val a = reg.open(headless = true)
        val b = reg.open(headless = true)
        assertTrue(a != b)
        reg.withSession(a) { it.navigate("https://a.example") }
        reg.withSession(b) { it.navigate("https://b.example") }
        // Each session drove its own page only.
        assertEquals(listOf("https://a.example"), svc.pages[0].navigated)
        assertEquals(listOf("https://b.example"), svc.pages[1].navigated)
    }

    @Test
    fun `withSession serializes concurrent access to one session`() = runBlocking {
        val (reg, svc) = registry { FakePage(delayMs = 40) }
        val id = reg.open(headless = true)
        val page = svc.pages[0]
        launch { reg.withSession(id) { it.executeJavaScript("a") } }
        launch { reg.withSession(id) { it.executeJavaScript("b") } }
        // let both finish
        delay(300)
        assertEquals(1, page.maxConcurrency.get())
    }

    @Test
    fun `closeAll disposes every session`() = runBlocking {
        val (reg, svc) = registry { FakePage() }
        reg.open(headless = true)
        reg.open(headless = true)
        reg.closeAll()
        assertTrue(svc.pages.all { it.disposed })
        assertTrue(reg.ids().isEmpty())
    }

    // ---- FlowBrowserToolSource ---------------------------------------------

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `browser tool source lists browser-scoped tools`() = runBlocking {
        val (reg, _) = registry()
        val tools = FlowBrowserToolSource(reg).list()
        assertTrue(tools.all { it.ref.scope == ToolScope.BROWSER })
        val names = tools.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("browser_open", "browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_close")))
    }

    @Test
    fun `browser_open returns a session_id resolvable in the registry`() = runBlocking {
        val (reg, _) = registry()
        val src = FlowBrowserToolSource(reg)
        val res = src.invoke("browser_open", """{"headless":true}""")
        assertTrue(!res.isError)
        val id = JSON.parseToJsonElement(res.text).jsonObject["session_id"]!!.jsonPrimitive.content
        assertNotNull(reg.get(id))
    }

    @Test
    fun `browser tools drive the session named by session_id`() = runBlocking {
        val (reg, svc) = registry { FakePage(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":true,"value":"Hello"}""" else true
        }) }
        val src = FlowBrowserToolSource(reg)
        // Two sessions; a tool call must only touch the one it names.
        val idA = JSON.parseToJsonElement(src.invoke("browser_open", """{"headless":true}""").text)
            .jsonObject["session_id"]!!.jsonPrimitive.content
        val idB = JSON.parseToJsonElement(src.invoke("browser_open", """{"headless":true}""").text)
            .jsonObject["session_id"]!!.jsonPrimitive.content
        val pageA = svc.pages[0]
        val pageB = svc.pages[1]

        src.invoke("browser_navigate", """{"session_id":"$idA","url":"https://only-a"}""")
        src.invoke("browser_click", """{"session_id":"$idB","selector":"#go"}""")

        assertTrue(pageA.navigated.contains("https://only-a"))
        assertTrue(pageB.navigated.isEmpty())               // click didn't navigate B
        assertTrue(pageB.jsCalls.any { it.contains("#go") }) // click hit B
        assertTrue(pageA.jsCalls.none { it.contains("#go") })

        // extract returns the extracted value
        val ex = src.invoke("browser_extract", """{"session_id":"$idB","selector":"h1"}""")
        assertTrue(!ex.isError)
        assertTrue(ex.text.contains("Hello"))
    }

    @Test
    fun `a browser tool against an unknown session errors, not crashes`() = runBlocking {
        val (reg, _) = registry()
        val res = FlowBrowserToolSource(reg).invoke("browser_navigate", """{"session_id":"nope","url":"x"}""")
        assertTrue(res.isError)
    }

    @Test
    fun `browser_close closes the named session`() = runBlocking {
        val (reg, _) = registry()
        val src = FlowBrowserToolSource(reg)
        val id = JSON.parseToJsonElement(src.invoke("browser_open", """{"headless":true}""").text)
            .jsonObject["session_id"]!!.jsonPrimitive.content
        assertNotNull(reg.get(id))
        val res = src.invoke("browser_close", """{"session_id":"$id"}""")
        assertTrue(!res.isError)
        assertNull(reg.get(id))
    }
}
