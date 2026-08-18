package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.BrowserIntegration
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.browser.ContextMenuCallback
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private class MultiFakeService(
        private val createDelayMs: Long = 0,
        private val make: () -> FakePage,
    ) : BrowserService {
        val pages = mutableListOf<FakePage>()
        override fun isAvailable() = true
        override suspend fun createBrowser(config: BrowserConfig): BrowserHandle {
            if (createDelayMs > 0) delay(createDelayMs)
            return make().also { pages.add(it) }
        }
        override suspend fun disposeBrowser(handle: BrowserHandle) { (handle as FakePage).disposed = true }
        override fun getActiveBrowserCount() = pages.size
    }

    private class Ctx(
        private val service: BrowserService?,
        private val tabs: ActiveTabsProvider? = null,
    ) : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(Dispatchers.Default)
        override val browserService: BrowserService? get() = service
        override val activeTabsProvider: ActiveTabsProvider? get() = tabs
    }

    private class FakeTabs(private val integration: BrowserIntegration) : ActiveTabsProvider {
        override val activeTabs: StateFlow<List<ActiveTabData>> = MutableStateFlow(
            listOf(
                ActiveTabData(
                    tabId = "browser-1",
                    typeId = "fluck",
                    title = "Browser",
                    workspaceId = "workspace-1",
                    workspaceName = "Workspace",
                    panelId = "panel-1",
                    windowId = "window-1",
                ),
            ),
        )
        var selected: Pair<String, String>? = null

        override suspend fun refreshTabs() = Unit
        override fun selectTab(tabId: String, panelId: String) {
            selected = tabId to panelId
        }
        override fun getTabUrl(tabId: String): String? = null
        override fun getFaviconCacheKey(tabId: String): String? = null
        @Composable override fun loadFavicon(cacheKey: String?): Painter? = null
        override fun getFallbackIcon(typeId: String): ImageVector? = null
        override fun getBrowserIntegration(tabId: String): BrowserIntegration? = integration
        override fun createBrowserTab(url: String, title: String): String = "browser-1"
        override fun createBrowserTabInRightSplit(url: String, title: String): String = "browser-1"
        override fun closeTab(tabId: String): Boolean = true
    }

    private fun registry(make: () -> FakePage = { FakePage() }): Pair<SessionRegistry, MultiFakeService> {
        val svc = MultiFakeService(make = make)
        return SessionRegistry(Ctx(svc)) to svc
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `focus selects the visible browser tab and panel`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val page = FakePage()
            val service = MultiFakeService { FakePage() }
            val tabs = FakeTabs(BrowserHandleIntegration(page))
            val registry = SessionRegistry(Ctx(service, tabs))
            val id = registry.newSessionId()

            registry.open(headless = false, id = id)

            assertTrue(registry.focus(id))
            assertEquals("browser-1" to "panel-1", tabs.selected)
        } finally {
            Dispatchers.resetMain()
        }
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
    fun `reopening an existing session id disposes the previous handle`() = runBlocking {
        val (reg, svc) = registry { FakePage() }
        reg.open(headless = true, id = "s")
        reg.open(headless = true, id = "s") // reopen same id — must not orphan the first browser
        assertEquals(2, svc.pages.size)
        assertTrue(svc.pages[0].disposed, "previous handle disposed on reopen (no leak — red-team S5)")
        assertFalse(svc.pages[1].disposed)
    }

    @Test
    fun `openIfAbsent serializes concurrent opens for one session id`() = runBlocking {
        val svc = MultiFakeService(make = { FakePage() }, createDelayMs = 50)
        val reg = SessionRegistry(Ctx(svc))

        val reused = listOf(
            async { reg.openIfAbsent(headless = true, id = "shared") },
            async { reg.openIfAbsent(headless = true, id = "shared") },
        ).awaitAll()

        assertEquals(1, svc.pages.size)
        assertEquals(1, reused.count { it })
        assertEquals(1, reused.count { !it })
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

    private fun ToolDescriptor.requiredProperties(): List<String> =
        JSON.parseToJsonElement(inputSchema).jsonObject["required"]
            ?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    @Test
    fun `browser tool source lists browser-scoped tools`() = runBlocking {
        val (reg, _) = registry()
        val tools = FlowBrowserToolSource(reg).list()
        assertTrue(tools.all { it.ref.scope == ToolScope.BROWSER })
        val names = tools.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("browser_open", "browser_navigate", "browser_click", "browser_type", "browser_extract", "browser_close")))
    }

    @Test
    fun `unbound browser tools still require explicit session ids`() = runBlocking {
        val (reg, _) = registry()
        val tools = FlowBrowserToolSource(reg).list().associateBy { it.name }

        assertTrue("session_id" in tools.getValue("browser_navigate").requiredProperties())
        assertFalse(tools.getValue("browser_open").description.contains("reuse", ignoreCase = true))

        val close = FlowBrowserToolSource(reg).invoke("browser_close", "{}")
        assertTrue(close.isError)
        assertTrue(close.text.contains("session_id"))
    }

    @Test
    fun `run-bound browser tool schemas advertise the default session contract`() = runBlocking {
        val (reg, _) = registry()
        val defaultId = reg.newSessionId()
        val src = FlowBrowserToolSource(reg, defaultId)

        val tools = src.list().associateBy { it.name }
        assertEquals(6, tools.size)
        listOf("browser_open", "browser_navigate", "browser_click", "browser_type", "browser_extract")
            .forEach { name ->
                assertTrue(tools.getValue(name).description.contains("Omit session_id"))
                assertFalse("session_id" in tools.getValue(name).requiredProperties())
            }
        assertEquals(listOf("url"), tools.getValue("browser_navigate").requiredProperties())
        assertEquals(listOf("selector"), tools.getValue("browser_click").requiredProperties())
        assertEquals(listOf("selector", "text"), tools.getValue("browser_type").requiredProperties())
        assertEquals(listOf("selector"), tools.getValue("browser_extract").requiredProperties())
        assertEquals(listOf("session_id"), tools.getValue("browser_close").requiredProperties())
        assertTrue(tools.getValue("browser_close").description.contains("cannot be closed"))
    }

    @Test
    fun `run-bound browser tools default to the reserved session`() = runBlocking {
        val (reg, svc) = registry { FakePage(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":true,"value":"Hello"}""" else true
        }) }
        val defaultId = reg.newSessionId()
        val src = FlowBrowserToolSource(reg, defaultId)

        val opened = src.invoke("browser_open", """{"headless":true}""")
        assertFalse(opened.isError)
        val openedJson = JSON.parseToJsonElement(opened.text).jsonObject
        assertEquals(defaultId, openedJson["session_id"]!!.jsonPrimitive.content)
        assertEquals(false, openedJson["reused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, openedJson["closable"]!!.jsonPrimitive.booleanOrNull)
        assertNotNull(reg.get(defaultId))

        // Opening again without an id reuses the page established for the run.
        val reopened = src.invoke("browser_open", """{"headless":true,"url":"https://open.example"}""")
        assertFalse(reopened.isError)
        assertEquals(true, JSON.parseToJsonElement(reopened.text).jsonObject["reused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(1, svc.pages.size)

        // Old prompts that explicitly name the default id must reuse it too.
        val explicitDefault = src.invoke(
            "browser_open",
            """{"session_id":"$defaultId","headless":true,"url":"https://explicit-default.example"}""",
        )
        assertFalse(explicitDefault.isError)
        assertEquals(true, JSON.parseToJsonElement(explicitDefault.text).jsonObject["reused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(1, svc.pages.size)

        assertFalse(src.invoke("browser_navigate", """{"url":"https://navigate.example"}""").isError)
        assertFalse(src.invoke("browser_click", """{"selector":"#go"}""").isError)
        assertFalse(src.invoke("browser_type", """{"selector":"#name","text":"Ada"}""").isError)
        val extracted = src.invoke("browser_extract", """{"selector":"h1"}""")
        assertFalse(extracted.isError)
        assertTrue(extracted.text.contains("Hello"))

        val page = svc.pages.single()
        assertEquals(
            listOf("https://open.example", "https://explicit-default.example", "https://navigate.example"),
            page.navigated,
        )
        assertTrue(page.jsCalls.any { it.contains("#go") })
        assertTrue(page.jsCalls.any { it.contains("#name") })

        val extra = src.invoke(
            "browser_open",
            """{"session_id":"agent-extra","headless":true,"url":"https://extra.example"}""",
        )
        assertFalse(extra.isError)
        val extraJson = JSON.parseToJsonElement(extra.text).jsonObject
        assertEquals(false, extraJson["reused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, extraJson["closable"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(2, svc.pages.size)
        assertEquals(
            listOf("https://open.example", "https://explicit-default.example", "https://navigate.example"),
            page.navigated,
        )
        assertEquals(listOf("https://extra.example"), svc.pages[1].navigated)

        val reusedExtra = src.invoke(
            "browser_open",
            """{"session_id":"agent-extra","headless":true,"url":"https://extra-reused.example"}""",
        )
        assertFalse(reusedExtra.isError)
        assertEquals(true, JSON.parseToJsonElement(reusedExtra.text).jsonObject["reused"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(2, svc.pages.size)
        assertEquals(listOf("https://extra.example", "https://extra-reused.example"), svc.pages[1].navigated)

        assertFalse(src.invoke("browser_close", """{"session_id":"agent-extra"}""").isError)
        assertTrue(svc.pages[1].disposed)

        val omittedClose = src.invoke("browser_close", "{}")
        assertTrue(omittedClose.isError)
        assertTrue(omittedClose.text.contains("session_id"))
        assertNotNull(reg.get(defaultId))
        assertFalse(page.disposed)

        val defaultClose = src.invoke("browser_close", """{"session_id":"$defaultId"}""")
        assertFalse(defaultClose.isError)
        val defaultCloseJson = JSON.parseToJsonElement(defaultClose.text).jsonObject
        assertEquals(false, defaultCloseJson["closed"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(defaultCloseJson["reason"]!!.jsonPrimitive.content.contains("flow run owns"))
        assertNotNull(reg.get(defaultId))
        assertFalse(page.disposed)

        reg.close(defaultId)
    }

    @Test
    fun `run-bound tool error does not leak an unopened default session id`() = runBlocking {
        val (reg, _) = registry()
        val defaultId = reg.newSessionId()
        val src = FlowBrowserToolSource(reg, defaultId)
        val omitted = src.invoke("browser_navigate", """{"url":"https://example.com"}""")
        val explicit = src.invoke(
            "browser_navigate",
            """{"session_id":"$defaultId","url":"https://example.com"}""",
        )

        listOf(omitted, explicit).forEach { result ->
            assertTrue(result.isError)
            assertTrue(result.text.contains("call browser_open first"))
            assertFalse(result.text.contains(defaultId))
        }
    }

    @Test
    fun `run-bound source rejects a blank default session id`() {
        val (reg, _) = registry()
        assertFailsWith<IllegalArgumentException> { FlowBrowserToolSource(reg, "") }
    }

    @Test
    fun `production agent tool wiring drives the RunContext default session`() = runBlocking {
        val svc = MultiFakeService { FakePage() }
        val context = Ctx(svc)
        val run = RunContext(context)
        val opened = run.openSession(headless = true)
        val source = defaultAgentToolSource(context, external = null, ctx = run)
        source.list()

        val result = source.invoke("browser_navigate", """{"url":"https://wired.example"}""")

        assertFalse(result.isError)
        assertEquals(listOf("https://wired.example"), svc.pages.single().navigated)
        assertTrue(run.session === opened)
        assertTrue(run.requireSession() === opened)
    }

    @Test
    fun `agent-opened default session is inherited by native nodes`() = runBlocking {
        val svc = MultiFakeService { FakePage() }
        val context = Ctx(svc)
        val run = RunContext(context)
        val source = defaultAgentToolSource(context, external = null, ctx = run)
        source.list()

        val result = source.invoke("browser_open", """{"headless":true}""")

        assertFalse(result.isError)
        assertNotNull(run.session)
        assertTrue(run.requireSession() === run.session)
        assertEquals(1, svc.pages.size)
    }

    @Test
    fun `explicit session id overrides a run-bound default`() = runBlocking {
        val (reg, svc) = registry()
        val defaultId = reg.newSessionId()
        reg.open(headless = true, id = defaultId)
        val explicitId = reg.open(headless = true)
        val src = FlowBrowserToolSource(reg, defaultId)

        val result = src.invoke(
            "browser_navigate",
            """{"session_id":"$explicitId","url":"https://explicit.example"}""",
        )

        assertFalse(result.isError)
        assertTrue(svc.pages[0].navigated.isEmpty())
        assertEquals(listOf("https://explicit.example"), svc.pages[1].navigated)
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
