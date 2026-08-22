package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
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
import kotlin.test.assertFalse
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
    override fun copySelection() {}
    override fun paste() {}
    override fun cut() {}
    override fun selectAll() {}
    override fun setOpenInNewTabCallback(callback: (String) -> Unit) {}
    override fun requestPictureInPicture() {}
    override fun setFullscreenHandler(tabId: String, onEnterFullscreen: () -> Unit, onExitFullscreen: () -> Unit) {}
    override fun requestExitFullscreen() {}
    override fun showDevTools() {}
    override suspend fun fillCredentials(username: String, password: String, fillBoth: Boolean) = false
    @Composable override fun Content() {}
    override fun dispose() {}
}

private class FakeService(private val handle: FakeHandle) : BrowserService {
    override fun isAvailable() = true
    override suspend fun createBrowser(config: BrowserConfig): BrowserHandle = handle
    override suspend fun disposeBrowser(handle: BrowserHandle) {}
    override fun getActiveBrowserCount() = 1
}

private class FakeNotifications : NotificationProvider {
    data class Shown(
        val message: String,
        val type: NotificationType,
        val duration: NotificationDuration,
        val title: String?,
    )

    val shown = mutableListOf<Shown>()
    val dismissed = mutableListOf<String>()

    override fun showToast(
        message: String,
        type: NotificationType,
        duration: NotificationDuration,
        title: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ): String = "notification-${shown.size + 1}".also {
        shown += Shown(message, type, duration, title)
    }

    override fun dismiss(notificationId: String) {
        dismissed += notificationId
    }

    override fun dismissAll() {
        dismissed += shown.indices.map { "notification-${it + 1}" }
    }
}

private class FakeContext(
    private val service: BrowserService?,
    private val notifications: NotificationProvider? = null,
) : PluginContext {
    override val panelRegistry = PanelRegistry()
    override val tabRegistry = TabRegistry()
    override val pluginScope = CoroutineScope(Dispatchers.Default)
    override val browserService: BrowserService? get() = service
    override val notificationProvider: NotificationProvider? get() = notifications
}

// ---- helpers ---------------------------------------------------------------

private fun n(id: String, type: NodeType, vararg cfg: Pair<String, String>, title: String = id) =
    PlanNode(id, type.name, title, buildJsonObject { cfg.forEach { put(it.first, it.second) } })

/** PlanNode with an arbitrary (possibly unregistered) kind-id. */
private fun nk(id: String, kind: String, vararg cfg: Pair<String, String>, title: String = id) =
    PlanNode(id, kind, title, buildJsonObject { cfg.forEach { put(it.first, it.second) } })

private fun e(from: String, to: String, fp: Int = 0, tp: Int = 0) =
    EdgeModel("$from-$to-$fp-$tp", from, fp, to, tp)

private fun runGraph(
    nodes: List<PlanNode>,
    edges: List<EdgeModel>,
    service: BrowserService? = null,
    registry: NodeRegistry = builtinNodeRegistry(),
    secrets: SecretResolver = SecretResolver.constant(null),
    notifications: NotificationProvider? = null,
    flowState: FlowStateBuffer = FlowStateBuffer(),
): Map<String, NodeRun> {
    val states = ConcurrentHashMap<String, NodeRun>()
    runBlocking(Dispatchers.Default) {
        FlowExecutor(FakeContext(service, notifications), registry, secrets).run(
            nodes, edges, flowState = flowState,
        ) { id, r -> states[id] = r }
    }
    return states
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

class FlowExecutorTest {

    @Test
    fun `state node stages current values and later nodes read prior-run state`() {
        val state = FlowStateBuffer(buildJsonObject { put("lastSeen", "before") })
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("set", NodeType.SET, "assignments" to """{"was":"{{ ${'$'}state.lastSeen }}","now":"after"}"""),
            n("state", NodeType.STATE, "assignments" to """{"lastSeen":"{{ ${'$'}json.now }}"}"""),
        )
        val states = runGraph(nodes, listOf(e("t", "set"), e("set", "state")), flowState = state)

        assertEquals("before", states["set"]?.output?.single()?.json?.str("was"))
        assertEquals(RunStatus.SUCCESS, states["state"]?.status)
        assertEquals("after", state.changes()["lastSeen"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cycle is rejected`() {
        val nodes = listOf(n("a", NodeType.SET), n("b", NodeType.SET))
        val edges = listOf(e("a", "b"), e("b", "a"))
        assertFailsWith<ExecError> { runGraph(nodes, edges) }
    }

    @Test
    fun `self edge is rejected instead of waiting on itself`() {
        val nodes = listOf(n("a", NodeType.SET))

        assertFailsWith<ExecError> { runGraph(nodes, listOf(e("a", "a"))) }
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
    fun `set preserves nested arrays and resolves node-output references`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n(
                "source",
                NodeType.SET,
                "assignments" to """{"payload":[{"id":7},{"id":9}],"enabled":true}""",
                title = "Source",
            ),
            n(
                "target",
                NodeType.SET,
                "assignments" to """
                    {"copy":"{{ ${'$'}node[\"Source\"].json.payload }}",
                     "first":"{{ ${'$'}node[\"Source\"].json.payload[0].id }}",
                     "nested":["{{ ${'$'}json.payload[1].id }}",{"ok":"{{ ${'$'}node[\"Source\"].json.enabled }}"}],
                     "label":"id={{ ${'$'}node[\"Source\"].json.payload[0].id }}"}
                """.trimIndent(),
            ),
        )
        val states = runGraph(nodes, listOf(e("t", "source"), e("source", "target")))

        assertEquals(RunStatus.SUCCESS, states["target"]?.status)
        val out = states["target"]!!.output.single().json
        assertEquals("[{\"id\":7},{\"id\":9}]", out["copy"].toString())
        assertEquals("7", out["first"]?.jsonPrimitive?.content)
        assertEquals("[9,{\"ok\":true}]", out["nested"].toString())
        assertEquals("id=7", out.str("label"))
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
    fun `per-item node emitting no items leaves downstream skipped`() {
        val registry = builtinNodeRegistry().also { reg ->
            reg.register(
                NodeSpec(
                    id = "EMPTY_PER_ITEM",
                    label = "Empty Per Item",
                    inputs = 1,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.PER_ITEM,
                    executor = NodeExecutor { _, _, _, _ -> NodeOutput.single(emptyList()) },
                )
            )
            reg.register(
                NodeSpec(
                    id = "TWO_ITEMS",
                    label = "Two Items",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        NodeOutput.single(listOf(Item(JsonObject(emptyMap())), Item(JsonObject(emptyMap()))))
                    },
                )
            )
        }
        val nodes = listOf(
            nk("source", "TWO_ITEMS"),
            nk("empty", "EMPTY_PER_ITEM"),
            n("after", NodeType.SET, "assignments" to """{"ran":true}"""),
        )
        val states = runGraph(nodes, listOf(e("source", "empty"), e("empty", "after")), registry = registry)

        assertEquals(RunStatus.SUCCESS, states["empty"]?.status)
        assertTrue(states["empty"]!!.output.isEmpty())
        assertEquals(RunStatus.SKIPPED, states["after"]?.status)
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
            n("after2", NodeType.SET, "assignments" to """{"z":"3"}"""), // transitive downstream
            n("indep", NodeType.SET, "assignments" to """{"y":"2"}""")  // independent branch
        )
        val edges = listOf(
            e("t", "open"), e("open", "ex"), e("ex", "after"), e("after", "after2"), e("t", "indep")
        )
        val states = runGraph(nodes, edges, FakeService(handle))

        assertEquals(RunStatus.ERROR, states["ex"]?.status)
        assertEquals(RunStatus.SKIPPED, states["after"]?.status)
        assertEquals(listOf(SKIP_UPSTREAM_FAILED), states["after"]?.logs)
        assertEquals(SKIP_UPSTREAM_FAILED, states["after"]?.skipReason)
        assertEquals(RunStatus.SKIPPED, states["after2"]?.status)
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
    fun `click uses its configured element wait timeout`() {
        val handle = FakeHandle(responder = { false })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n("click", NodeType.CLICK, "selector" to "#late", "waitMs" to "0"),
            ),
            listOf(e("open", "click")),
            FakeService(handle),
        )

        assertEquals(RunStatus.ERROR, states["click"]?.status)
        assertEquals("Click: no element matched '#late' within 0ms", states["click"]?.error)
    }

    @Test
    fun `await login prompts once and resumes when the signed-in marker appears`() {
        val notifications = FakeNotifications()
        var markerPolls = 0
        val handle = FakeHandle(responder = { script ->
            if (script.contains("return !!")) {
                markerPolls++
                notifications.shown.isNotEmpty()
            } else {
                true
            }
        })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n(
                    "login",
                    NodeType.AWAIT_LOGIN,
                    "selector" to ".account-menu",
                    "waitMs" to "3000",
                    "message" to "Please finish SSO",
                ),
            ),
            listOf(e("open", "login")),
            service = FakeService(handle),
            notifications = notifications,
        )

        assertEquals(RunStatus.SUCCESS, states["login"]?.status)
        assertTrue(markerPolls > 1)
        assertEquals(
            listOf(
                FakeNotifications.Shown(
                    "Please finish SSO",
                    NotificationType.INFO,
                    NotificationDuration.INDEFINITE,
                    "Flow waiting for sign-in",
                ),
            ),
            notifications.shown,
        )
        assertEquals(listOf("notification-1"), notifications.dismissed)
        assertEquals(
            listOf("Please finish SSO", "Sign-in detected; continuing flow"),
            states["login"]?.logs,
        )
    }

    @Test
    fun `await login grace period avoids prompting while an authenticated page renders`() {
        val notifications = FakeNotifications()
        var markerPolls = 0
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n("login", NodeType.AWAIT_LOGIN, "selector" to ".account-menu"),
            ),
            listOf(e("open", "login")),
            service = FakeService(FakeHandle(responder = { script ->
                if (script.contains("return !!")) ++markerPolls >= 3 else true
            })),
            notifications = notifications,
        )

        assertEquals(RunStatus.SUCCESS, states["login"]?.status)
        assertEquals(3, markerPolls)
        assertTrue(notifications.shown.isEmpty())
        assertEquals(listOf("Sign-in already detected"), states["login"]?.logs)
    }

    @Test
    fun `await login dismisses its prompt when the marker times out`() {
        val notifications = FakeNotifications()
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n(
                    "login",
                    NodeType.AWAIT_LOGIN,
                    "selector" to ".account-menu",
                    "waitMs" to "1001",
                ),
            ),
            listOf(e("open", "login")),
            service = FakeService(FakeHandle(responder = { false })),
            notifications = notifications,
        )

        assertEquals(RunStatus.ERROR, states["login"]?.status)
        assertEquals(
            "Await Login: sign-in marker '.account-menu' not found within 1001ms",
            states["login"]?.error,
        )
        assertEquals(listOf("notification-1"), notifications.dismissed)
    }

    @Test
    fun `await login dismisses its prompt when the run is cancelled`() = runBlocking {
        val notifications = FakeNotifications()
        val states = ConcurrentHashMap<String, NodeRun>()
        val executor = FlowExecutor(
            FakeContext(FakeService(FakeHandle(responder = { false })), notifications),
        )
        val job = launch(Dispatchers.Default) {
            executor.run(
                nodes = listOf(
                    n("open", NodeType.OPEN_BROWSER),
                    n("login", NodeType.AWAIT_LOGIN, "selector" to ".account-menu"),
                ),
                edges = listOf(e("open", "login")),
            ) { id, run -> states[id] = run }
        }

        withTimeout(3_000) {
            while (notifications.shown.isEmpty()) delay(10)
        }
        job.cancelAndJoin()

        assertEquals(listOf("notification-1"), notifications.dismissed)
    }

    @Test
    fun `await login requires a signed-in marker`() {
        val states = runGraph(listOf(n("login", NodeType.AWAIT_LOGIN)), emptyList())

        assertEquals(RunStatus.ERROR, states["login"]?.status)
        assertEquals("Await Login needs a signed-in marker", states["login"]?.error)
    }

    @Test
    fun `browser value nodes resolve secrets at runtime without logging them`() {
        val password = "line one\nline'two"
        val token = "token line one\ntoken'two"
        val lookups = mutableListOf<String>()
        val handle = FakeHandle(responder = { true })
        val nodes = listOf(
            n("open", NodeType.OPEN_BROWSER),
            n("type", NodeType.TYPE, "selector" to "#password", "text" to "{{ \$secret.login_password }}"),
            n("inject", NodeType.INJECT, "script" to "window.apiToken='{{ \$secret.api_token }}'"),
        )
        val states = runGraph(
            nodes,
            listOf(e("open", "type"), e("type", "inject")),
            service = FakeService(handle),
            secrets = SecretResolver { name ->
                lookups += name
                when (name) {
                    "login_password" -> password
                    "api_token" -> token
                    else -> null
                }
            },
        )

        assertEquals(listOf("login_password", "api_token"), lookups)
        assertTrue(handle.jsCalls.any { it.contains("el.value='line one\\nline\\'two'") })
        assertTrue(handle.jsCalls.any { it.contains("window.apiToken='token line one\\ntoken\\'two'") })
        assertEquals(RunStatus.SUCCESS, states["type"]?.status)
        assertEquals(RunStatus.SUCCESS, states["inject"]?.status)
        val reported = states.values.flatMap { it.logs } + states.values.mapNotNull { it.error }
        assertFalse(reported.any { password in it || token in it })
    }

    @Test
    fun `browser secret lookup errors do not expose provider failures`() {
        val states = runGraph(
            listOf(n("type", NodeType.TYPE, "selector" to "#password", "text" to "{{ \$secret.login_password }}")),
            emptyList(),
            secrets = SecretResolver { throw IllegalStateException("provider credential") },
        )

        assertEquals(RunStatus.ERROR, states["type"]?.status)
        assertEquals("Secret 'login_password' was not found", states["type"]?.error)
        assertFalse(states["type"]!!.error!!.contains("provider credential"))
    }

    @Test
    fun `inject waits for its optional target before running`() {
        var polls = 0
        val handle = FakeHandle(responder = { script ->
            when {
                script.contains("return !!") -> ++polls >= 2
                script == "window.didRun=true" -> null
                else -> true
            }
        })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n(
                    "inject",
                    NodeType.INJECT,
                    "waitForType" to "css",
                    "waitFor" to ".ready",
                    "waitMs" to "1000",
                    "script" to "window.didRun=true",
                ),
            ),
            listOf(e("open", "inject")),
            FakeService(handle),
        )

        assertEquals(RunStatus.SUCCESS, states["inject"]?.status)
        assertEquals(2, polls)
        assertTrue(states["inject"]!!.logs.any { "Waited for '.ready'" in it })
        assertEquals("window.didRun=true", handle.jsCalls.last())
    }

    @Test
    fun `inject fails itself when its wait target never appears`() {
        val handle = FakeHandle(responder = { false })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n(
                    "inject",
                    NodeType.INJECT,
                    "waitFor" to "#missing",
                    "waitMs" to "0",
                    "script" to "window.didRun=true",
                ),
            ),
            listOf(e("open", "inject")),
            FakeService(handle),
        )

        assertEquals(RunStatus.ERROR, states["inject"]?.status)
        assertEquals("Inject: no element matched '#missing' within 0ms", states["inject"]?.error)
        assertFalse(handle.jsCalls.contains("window.didRun=true"))
    }

    @Test
    fun `inject treats exact false as failure but keeps null backward compatible`() {
        val failed = runGraph(
            listOf(n("open", NodeType.OPEN_BROWSER), n("inject", NodeType.INJECT, "script" to "false")),
            listOf(e("open", "inject")),
            FakeService(FakeHandle(responder = { false })),
        )
        val succeeded = runGraph(
            listOf(n("open", NodeType.OPEN_BROWSER), n("inject", NodeType.INJECT, "script" to "void 0")),
            listOf(e("open", "inject")),
            FakeService(FakeHandle(responder = { null })),
        )

        assertEquals(RunStatus.ERROR, failed["inject"]?.status)
        assertEquals("Inject script returned false", failed["inject"]?.error)
        assertEquals(RunStatus.SUCCESS, succeeded["inject"]?.status)
        assertEquals(listOf("Ran injected script"), succeeded["inject"]?.logs)
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
    fun `code transforms each item with a typed JSON template`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("s", NodeType.SET, "assignments" to """{"name":"Ada"}"""),
            n(
                "c",
                NodeType.CODE,
                "code" to """{"greeting":"Hello {{ ${'$'}json.name }}","active":true,"count":2}""",
            ),
        )
        val states = runGraph(nodes, listOf(e("t", "s"), e("s", "c")))

        assertEquals(RunStatus.SUCCESS, states["c"]?.status)
        val out = states["c"]!!.output.single().json
        assertEquals("Hello Ada", out.str("greeting"))
        assertEquals("true", out["active"]?.jsonPrimitive?.content)
        assertEquals("2", out["count"]?.jsonPrimitive?.content)
    }

    @Test
    fun `code rejects blank malformed and non-object templates`() {
        val blank = runGraph(listOf(n("c", NodeType.CODE)), emptyList())
        assertEquals(RunStatus.ERROR, blank["c"]?.status)
        assertTrue(blank["c"]!!.error!!.contains("needs an output JSON template"))

        val malformed = runGraph(listOf(n("c", NodeType.CODE, "code" to "{")), emptyList())
        assertEquals(RunStatus.ERROR, malformed["c"]?.status)
        assertTrue(malformed["c"]!!.error!!.contains("valid JSON"))

        val array = runGraph(listOf(n("c", NodeType.CODE, "code" to "[1,2]")), emptyList())
        assertEquals(RunStatus.ERROR, array["c"]?.status)
        assertTrue(array["c"]!!.error!!.contains("produce an object"))
    }

    @Test
    fun `set and code fail at an unresolved template expression`() {
        val set = runGraph(
            listOf(n("s", NodeType.SET, "assignments" to """{"message":"{{ ${'$'}json.missing }}"}""")),
            emptyList(),
        )
        val code = runGraph(
            listOf(n("c", NodeType.CODE, "code" to """{"message":"{{ ${'$'}json.missing }}"}""")),
            emptyList(),
        )

        assertEquals(RunStatus.ERROR, set["s"]?.status)
        assertEquals("Unresolved template expression '{{ ${'$'}json.missing }}'", set["s"]?.error)
        assertEquals(RunStatus.ERROR, code["c"]?.status)
        assertEquals("Unresolved template expression '{{ ${'$'}json.missing }}'", code["c"]?.error)
    }

    @Test
    fun `if independently routes multiple items through both output ports`() {
        val registry = builtinNodeRegistry().also { reg ->
            reg.register(
                NodeSpec(
                    id = "TEST_SOURCE",
                    label = "Test Source",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        NodeOutput.single(
                            listOf(90, 70, 95).map { score ->
                                Item(buildJsonObject { put("score", score) })
                            }
                        )
                    },
                )
            )
        }
        val nodes = listOf(
            nk("source", "TEST_SOURCE"),
            n("if", NodeType.IF, "condition" to "{{ \$json.score }} >= 80"),
            n("yes", NodeType.SET, "assignments" to """{"branch":"yes"}"""),
            n("no", NodeType.SET, "assignments" to """{"branch":"no"}"""),
        )
        val edges = listOf(
            e("source", "if"),
            e("if", "yes", fp = 0),
            e("if", "no", fp = 1),
        )
        val states = runGraph(nodes, edges, registry = registry)

        assertEquals(listOf("90", "95"), states["yes"]!!.output.map { it.json.str("score") })
        assertEquals(listOf("70"), states["no"]!!.output.map { it.json.str("score") })
        assertTrue(states["yes"]!!.output.all { it.json.str("branch") == "yes" })
        assertTrue(states["no"]!!.output.all { it.json.str("branch") == "no" })
    }

    @Test
    fun `if parses its raw condition before interpolated data can become an operator`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("source", NodeType.SET, "assignments" to """{"text":"1 > 2"}"""),
            n("if", NodeType.IF, "condition" to "{{ \$json.text }}"),
            n("yes", NodeType.SET, "assignments" to """{"branch":"yes"}"""),
            n("no", NodeType.SET, "assignments" to """{"branch":"no"}"""),
        )
        val edges = listOf(
            e("t", "source"),
            e("source", "if"),
            e("if", "yes", fp = 0),
            e("if", "no", fp = 1),
        )

        val states = runGraph(nodes, edges)

        assertEquals(RunStatus.SUCCESS, states["yes"]?.status)
        assertEquals("yes", states["yes"]!!.output.single().json.str("branch"))
        assertEquals(RunStatus.SKIPPED, states["no"]?.status)
    }

    @Test
    fun `mixed ordering type errors the if node and skips both outputs`() {
        val registry = builtinNodeRegistry().also { reg ->
            reg.register(
                NodeSpec(
                    id = "TEST_SOURCE",
                    label = "Test Source",
                    inputs = 0,
                    outputs = 1,
                    accent = 0,
                    description = "test only",
                    runMode = RunMode.ONCE,
                    executor = NodeExecutor { _, _, _, _ ->
                        NodeOutput.single(
                            listOf(
                                Item(buildJsonObject { put("price", 150) }),
                                Item(buildJsonObject { put("price", "N/A") }),
                            ),
                        )
                    },
                ),
            )
        }
        val nodes = listOf(
            nk("source", "TEST_SOURCE"),
            n("if", NodeType.IF, "condition" to "{{ \$json.price }} > 100"),
            n("yes", NodeType.SET, "assignments" to """{"branch":"yes"}"""),
            n("no", NodeType.SET, "assignments" to """{"branch":"no"}"""),
        )
        val edges = listOf(
            e("source", "if"),
            e("if", "yes", fp = 0),
            e("if", "no", fp = 1),
        )

        val states = runGraph(nodes, edges, registry = registry)

        assertEquals(RunStatus.ERROR, states["if"]?.status)
        assertTrue(states["if"]!!.error!!.contains("N/A"))
        assertEquals(RunStatus.SKIPPED, states["yes"]?.status)
        assertEquals(RunStatus.SKIPPED, states["no"]?.status)
    }

    @Test
    fun `merge concatenates items arriving on both input ports`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("a", NodeType.SET, "assignments" to """{"side":"a"}"""),
            n("b", NodeType.SET, "assignments" to """{"side":"b"}"""),
            n("m", NodeType.MERGE),
        )
        val edges = listOf(
            e("t", "a"),
            e("t", "b"),
            e("b", "m", tp = 1),
            e("a", "m", tp = 0),
        )
        val states = runGraph(nodes, edges)

        assertEquals(RunStatus.SUCCESS, states["m"]?.status)
        assertEquals(listOf("a", "b"), states["m"]!!.output.map { it.json.str("side") })
    }

    @Test
    fun `merge accepts one empty input port`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("if", NodeType.IF, "condition" to "true"),
            n("m", NodeType.MERGE),
        )
        val edges = listOf(
            e("t", "if"),
            e("if", "m", fp = 0, tp = 0),
            e("if", "m", fp = 1, tp = 1),
        )
        val states = runGraph(nodes, edges)

        assertEquals(RunStatus.SUCCESS, states["m"]?.status)
        assertEquals(1, states["m"]!!.output.size)
    }

    @Test
    fun `empty non-control output skips downstream with an explanatory log`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":true,"value":[]}""" else true
        })
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("open", NodeType.OPEN_BROWSER),
            n("ex", NodeType.EXTRACT, "selector" to ".missing", "multiple" to "true"),
            n("set", NodeType.SET, "assignments" to """{"ran":true}"""),
        )
        val edges = listOf(e("t", "open"), e("open", "ex"), e("ex", "set"))
        val states = runGraph(nodes, edges, FakeService(handle))

        assertEquals(RunStatus.SUCCESS, states["ex"]?.status)
        assertTrue(states["ex"]!!.output.isEmpty())
        assertEquals(RunStatus.SKIPPED, states["set"]?.status)
        assertTrue(states["set"]!!.output.isEmpty())
        assertTrue(states["set"]!!.logs.any { it.contains("skipped", ignoreCase = true) })
    }

    @Test
    fun `optional extract emits null so If can route a missing element to fallback`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) {
                """{"ok":false,"error":"$EXTRACT_NO_MATCH_ERROR"}"""
            } else {
                true
            }
        })
        val nodes = listOf(
            n("open", NodeType.OPEN_BROWSER),
            n("ex", NodeType.EXTRACT, "selector" to ".primary", "field" to "section", "optional" to "true"),
            n("if", NodeType.IF, "condition" to "{{ \$json.section }}"),
            n("primary", NodeType.SET, "assignments" to """{"branch":"primary"}"""),
            n("fallback", NodeType.SET, "assignments" to """{"branch":"fallback"}"""),
        )
        val edges = listOf(
            e("open", "ex"),
            e("ex", "if"),
            e("if", "primary", fp = 0),
            e("if", "fallback", fp = 1),
        )

        val states = runGraph(nodes, edges, FakeService(handle))

        assertEquals(RunStatus.SUCCESS, states["ex"]?.status)
        assertEquals(JsonNull, states["ex"]!!.output.single().json["section"])
        assertEquals(RunStatus.SKIPPED, states["primary"]?.status)
        assertEquals(RunStatus.SUCCESS, states["fallback"]?.status)
        assertEquals("fallback", states["fallback"]!!.output.single().json.str("branch"))
    }

    @Test
    fun `non-optional extract preserves a missing-element failure`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) {
                """{"ok":false,"error":"$EXTRACT_NO_MATCH_ERROR"}"""
            } else {
                true
            }
        })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n("ex", NodeType.EXTRACT, "selector" to ".required"),
            ),
            listOf(e("open", "ex")),
            FakeService(handle),
        )

        assertEquals(RunStatus.ERROR, states["ex"]?.status)
        assertEquals("Extract failed: $EXTRACT_NO_MATCH_ERROR", states["ex"]?.error)
    }

    @Test
    fun `optional multiple extract converts an empty match list into one null item`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) """{"ok":true,"value":[]}""" else true
        })
        val nodes = listOf(
            n("open", NodeType.OPEN_BROWSER),
            n(
                "ex",
                NodeType.EXTRACT,
                "selector" to ".result",
                "field" to "value",
                "multiple" to "true",
                "optional" to "true",
            ),
            n("set", NodeType.SET, "assignments" to """{"continued":true}"""),
        )

        val states = runGraph(nodes, listOf(e("open", "ex"), e("ex", "set")), FakeService(handle))

        assertEquals(JsonNull, states["ex"]!!.output.single().json["value"])
        assertEquals(RunStatus.SUCCESS, states["set"]?.status)
    }

    @Test
    fun `optional extract does not hide selector script errors`() {
        val handle = FakeHandle(responder = { script ->
            if (script.contains("JSON.stringify")) {
                """{"ok":false,"error":"SyntaxError: invalid selector"}"""
            } else {
                true
            }
        })
        val states = runGraph(
            listOf(
                n("open", NodeType.OPEN_BROWSER),
                n("ex", NodeType.EXTRACT, "selector" to "[", "optional" to "true"),
            ),
            listOf(e("open", "ex")),
            FakeService(handle),
        )

        assertEquals(RunStatus.ERROR, states["ex"]?.status)
        assertTrue(states["ex"]?.error.orEmpty().contains("SyntaxError"))
    }

    @Test
    fun `unavailable kind on an unselected branch is skipped`() {
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            n("if", NodeType.IF, "condition" to "false"),
            nk("missing", "tool:boss:absent"),
        )
        val edges = listOf(e("t", "if"), e("if", "missing", fp = 0))
        val states = runGraph(nodes, edges)

        assertEquals(RunStatus.SKIPPED, states["missing"]?.status)
        assertTrue(states["missing"]!!.error == null)
    }

    @Test
    fun `unknown kind-id fails only that node with a clear message`() {
        // A graph referencing a kind the registry doesn't know (e.g. a tool whose
        // provider is absent) must not crash the run — the node errors, siblings run.
        val nodes = listOf(
            n("t", NodeType.TRIGGER),
            nk("x", "tool:boss:absent"),
            n("ok", NodeType.SET, "assignments" to """{"y":"1"}""")
        )
        val edges = listOf(e("t", "x"), e("t", "ok"))
        val states = runGraph(nodes, edges)
        assertEquals(RunStatus.ERROR, states["x"]?.status)
        assertTrue(states["x"]!!.error!!.contains("Unknown node kind", ignoreCase = true))
        assertEquals(RunStatus.SUCCESS, states["ok"]?.status)
    }

    @Test
    fun `legacy enum-name graph decodes and runs`() {
        // Graphs saved before the enum→String migration serialized `"type":"TRIGGER"`.
        // A String `type` field decodes them unchanged; they still run.
        val legacy = """
            {"nodes":[
              {"id":"t","type":"TRIGGER","title":"Trigger","x":0,"y":0},
              {"id":"s","type":"SET","title":"Set","x":0,"y":0,"config":{"assignments":"{\"a\":\"1\"}"}}
            ],"edges":[{"id":"e","fromNode":"t","fromPort":0,"toNode":"s","toPort":0}],"nextId":3}
        """.trimIndent()
        val snap = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(GraphSnapshot.serializer(), legacy)
        assertEquals("TRIGGER", snap.nodes[0].type)
        val plan = snap.nodes.map { PlanNode(it.id, it.type, it.title, it.config) }
        val states = runGraph(plan, snap.edges)
        assertEquals(RunStatus.SUCCESS, states["s"]?.status)
        assertEquals("1", states["s"]!!.output.single().json.str("a"))
    }

    @Test
    fun `anyToJson normalizes primitive types`() {
        assertEquals("\"x\"", anyToJson("x").toString())
        assertEquals("true", anyToJson(true).toString())
        assertEquals("3", anyToJson(3).toString())
        assertEquals("null", anyToJson(null).toString())
    }
}
