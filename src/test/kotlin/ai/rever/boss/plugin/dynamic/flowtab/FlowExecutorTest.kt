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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---- fakes -----------------------------------------------------------------

/**
 * A fake [BrowserHandle] that records the calls the executors actually make
 * (loadUrlAndWait / executeJavaScript) and detects overlap. The rest of the
 * (large) BrowserHandle surface is stubbed — the executors never touch it.
 */
private class FakeHandle(
    private val responder: (String) -> Any? = { true },
    private val delayMs: Long = 0
) : BrowserHandle {
    val navigated = mutableListOf<String>()
    val jsCalls = mutableListOf<String>()
    private val concurrency = AtomicInteger(0)
    val maxConcurrency = AtomicInteger(0)

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

    // --- unused BrowserHandle surface (no-op stubs) ---
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

private class FakeService(private val handle: FakeHandle) : BrowserService {
    override fun isAvailable() = true
    override suspend fun createBrowser(config: BrowserConfig): BrowserHandle = handle
    override suspend fun disposeBrowser(handle: BrowserHandle) {}
    override fun getActiveBrowserCount() = 1
}

private class FakeContext(private val service: BrowserService?) : PluginContext {
    override val panelRegistry = PanelRegistry()
    override val tabRegistry = TabRegistry()
    override val pluginScope = CoroutineScope(Dispatchers.Default)
    override val browserService: BrowserService? get() = service
}

// ---- helpers ---------------------------------------------------------------

private fun n(id: String, type: NodeType, vararg cfg: Pair<String, String>, title: String = id) =
    PlanNode(id, type, title, buildJsonObject { cfg.forEach { put(it.first, it.second) } })

private fun e(from: String, to: String, fp: Int = 0, tp: Int = 0) =
    EdgeModel("$from-$to-$fp-$tp", from, fp, to, tp)

private fun runGraph(
    nodes: List<PlanNode>,
    edges: List<EdgeModel>,
    service: BrowserService? = null
): Map<String, NodeRun> {
    val states = ConcurrentHashMap<String, NodeRun>()
    runBlocking(Dispatchers.Default) {
        FlowExecutor(FakeContext(service)).run(nodes, edges) { id, r -> states[id] = r }
    }
    return states
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

class FlowExecutorTest {

    @Test
    fun `cycle is rejected`() {
        val nodes = listOf(n("a", NodeType.SET), n("b", NodeType.SET))
        val edges = listOf(e("a", "b"), e("b", "a"))
        assertFailsWith<ExecError> { runGraph(nodes, edges) }
    }

    @Test
    fun `linear chain threads data and resolves expressions`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("s1", NodeType.SET, "assignments" to """{"a":"1"}"""),
            n("s2", NodeType.SET, "assignments" to "{\"b\":\"{{ \$json.a }}\"}")
        )
        val edges = listOf(e("t", "s1"), e("s1", "s2"))
        val states = runGraph(nodes, edges)

        assertEquals(RunStatus.SUCCESS, states["s2"]?.status)
        val out = states["s2"]!!.output.single().json
        assertEquals("1", out.str("a"))
        assertEquals("1", out.str("b")) // {{ $json.a }} resolved from upstream
    }

    @Test
    fun `per-item run mode runs once per input item`() {
        val handle = FakeHandle(responder = { """{"ok":true,"value":["a","b","c"]}""" })
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("open", NodeType.OPEN_BROWSER),
            n("ex", NodeType.EXTRACT, "selector" to ".x", "field" to "v", "multiple" to "true"),
            n("set", NodeType.SET, "assignments" to """{"seen":"yes"}""")
        )
        val edges = listOf(e("t", "open"), e("open", "ex"), e("ex", "set"))
        val states = runGraph(nodes, edges, FakeService(handle))

        assertEquals(3, states["ex"]?.output?.size) // one item per matched element
        assertEquals(3, states["set"]?.output?.size) // SET ran once per item
        assertEquals("yes", states["set"]!!.output.first().json.str("seen"))
    }

    @Test
    fun `failure skips downstream but independent branch still runs`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":false,"error":"boom"}""" else true
        })
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("open", NodeType.OPEN_BROWSER),
            n("ex", NodeType.EXTRACT, "selector" to ".x"), // will fail (ok:false)
            n("after", NodeType.SET, "assignments" to """{"x":"1"}"""), // downstream of failure
            n("indep", NodeType.SET, "assignments" to """{"y":"2"}""")  // independent branch
        )
        val edges = listOf(e("t", "open"), e("open", "ex"), e("ex", "after"), e("t", "indep"))
        val states = runGraph(nodes, edges, FakeService(handle))

        assertEquals(RunStatus.ERROR, states["ex"]?.status)
        assertNull(states["after"]?.status) // skipped (never ran)
        assertEquals(RunStatus.SUCCESS, states["indep"]?.status) // unaffected
    }

    @Test
    fun `browser nodes drive the session with the right calls`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":true,"value":"Hello"}""" else true
        })
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("open", NodeType.OPEN_BROWSER, "url" to "https://example.com"),
            n("nav", NodeType.NAVIGATE, "url" to "https://example.com/x"),
            n("click", NodeType.CLICK, "selector" to "#go"),
            n("ex", NodeType.EXTRACT, "selector" to "h1", "field" to "title")
        )
        val edges = listOf(e("t", "open"), e("open", "nav"), e("nav", "click"), e("click", "ex"))
        val states = runGraph(nodes, edges, FakeService(handle))

        assertTrue(handle.navigated.contains("https://example.com"))   // Open Browser start url
        assertTrue(handle.navigated.contains("https://example.com/x")) // Navigate
        assertTrue(handle.jsCalls.any { it.contains("#go") })          // Click selector in JS
        assertEquals("Hello", states["ex"]!!.output.single().json.str("title")) // Extract returned data
    }

    @Test
    fun `session fence serializes parallel browser nodes`() {
        // Two Clicks both depend only on Open Browser, so they are ready together;
        // the session mutex must keep them from overlapping (max concurrency 1).
        val handle = FakeHandle(responder = { true }, delayMs = 40)
        val nodes = listOf(
            n("open", NodeType.OPEN_BROWSER),
            n("c1", NodeType.CLICK, "selector" to "#a"),
            n("c2", NodeType.CLICK, "selector" to "#b")
        )
        val edges = listOf(e("open", "c1"), e("open", "c2"))
        runGraph(nodes, edges, FakeService(handle))

        assertEquals(1, handle.maxConcurrency.get())
    }

    @Test
    fun `unimplemented node type errors clearly`() {
        val states = runGraph(listOf(n("c", NodeType.CODE)), emptyList())
        assertEquals(RunStatus.ERROR, states["c"]?.status)
        assertTrue(states["c"]!!.error!!.contains("not runnable", ignoreCase = true))
    }

    @Test
    fun `anyToJson normalizes primitive types`() {
        assertEquals("\"x\"", anyToJson("x").toString())
        assertEquals("true", anyToJson(true).toString())
        assertEquals("3", anyToJson(3).toString())
        assertEquals("null", anyToJson(null).toString())
    }
}
