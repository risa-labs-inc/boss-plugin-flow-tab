package ai.rever.boss.plugin.dynamic.flowtab

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the HTTP node against a real local server, and uses it to prove the
 * executor runs independent branches **in parallel** (the server records the max
 * number of concurrent in-flight requests).
 */
class HttpNodeTest {

    private fun startServer(maxConc: AtomicInteger): HttpServer {
        val conc = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val c = conc.incrementAndGet()
            maxConc.updateAndGet { max(it, c) }
            Thread.sleep(80) // hold the connection so overlap is observable
            val body = """{"hello":"world"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            conc.decrementAndGet()
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        return server
    }

    private fun n(id: String, type: NodeType, vararg cfg: Pair<String, String>) =
        PlanNode(id, type.name, id, buildJsonObject { cfg.forEach { put(it.first, it.second) } })

    private fun run(
        nodes: List<PlanNode>,
        edges: List<EdgeModel>,
        secrets: SecretResolver = SecretResolver.constant(null),
    ): Map<String, NodeRun> {
        val states = ConcurrentHashMap<String, NodeRun>()
        runBlocking(Dispatchers.Default) {
            FlowExecutor(object : ai.rever.boss.plugin.api.PluginContext {
                override val panelRegistry = ai.rever.boss.plugin.api.PanelRegistry()
                override val tabRegistry = ai.rever.boss.plugin.api.TabRegistry()
                override val pluginScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
            }, secrets = secrets).run(nodes, edges) { id, r -> states[id] = r }
        }
        return states
    }

    @Test
    fun `http node returns status and parsed body`() {
        val maxConc = AtomicInteger(0)
        val server = startServer(maxConc)
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            val states = run(
                listOf(n("t", NodeType.TRIGGER), n("h", NodeType.HTTP, "method" to "GET", "url" to url)),
                listOf(EdgeModel("t-h", "t", 0, "h", 0))
            )
            val item = states["h"]!!.output.single().json
            assertEquals("200", item["status"]?.jsonPrimitive?.content)
            assertEquals("world", item["body"]?.jsonObject?.get("hello")?.jsonPrimitive?.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `independent branches run in parallel`() {
        val maxConc = AtomicInteger(0)
        val server = startServer(maxConc)
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            // Two HTTP nodes, both off the trigger → no dependency between them.
            run(
                listOf(
                    n("t", NodeType.TRIGGER),
                    n("h1", NodeType.HTTP, "method" to "GET", "url" to url),
                    n("h2", NodeType.HTTP, "method" to "GET", "url" to url)
                ),
                listOf(EdgeModel("t-h1", "t", 0, "h1", 0), EdgeModel("t-h2", "t", 0, "h2", 0))
            )
            // If the executor serialized branches, the server would never see 2 at once.
            assertTrue(maxConc.get() >= 2, "expected concurrent requests, saw ${maxConc.get()}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http resolves secrets at runtime without changing config or leaking logs`() {
        val receivedPath = AtomicReference<String>()
        val receivedAuth = AtomicReference<String>()
        val receivedBody = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                receivedPath.set(exchange.requestURI.path)
                receivedAuth.set(exchange.requestHeaders.getFirst("Authorization"))
                receivedBody.set(exchange.requestBody.bufferedReader().readText())
                val bytes = "ok".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val token = "token-that-must-stay-private"
        val path = "private-webhook-path"
        val resolver = SecretResolver { name ->
            when (name) {
                "api_token" -> token
                "webhook_path" -> path
                else -> null
            }
        }
        val http = n(
            "h",
            NodeType.HTTP,
            "method" to "POST",
            "url" to "http://127.0.0.1:${server.address.port}/{{ \$secret.webhook_path }}",
            "headers" to "{\"Authorization\":\"Bearer {{ \$secret.api_token }}\"}",
            "body" to "credential={{ \$secret.api_token }}",
        )

        try {
            val states = run(
                listOf(n("t", NodeType.TRIGGER), http),
                listOf(EdgeModel("t-h", "t", 0, "h", 0)),
                resolver,
            )

            assertEquals("/$path", receivedPath.get())
            assertEquals("Bearer $token", receivedAuth.get())
            assertEquals("credential=$token", receivedBody.get())
            assertTrue(http.config.toString().contains("\$secret.api_token"))
            assertFalse(http.config.toString().contains(token))
            val report = states.getValue("h").let { it.logs.joinToString("\n") + it.error.orEmpty() }
            assertFalse(report.contains(token))
            assertFalse(report.contains(path))
            assertEquals(listOf("POST HTTP request", "→ 200"), states.getValue("h").logs)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `http request errors do not expose a resolved secret URL`() {
        val secretUrl = "://credential-that-must-not-appear"
        val http = n("h", NodeType.HTTP, "url" to "{{ \$secret.bad_url }}")

        val states = run(
            listOf(n("t", NodeType.TRIGGER), http),
            listOf(EdgeModel("t-h", "t", 0, "h", 0)),
            SecretResolver { name -> secretUrl.takeIf { name == "bad_url" } },
        )

        val report = states.getValue("h").let { it.logs.joinToString("\n") + it.error.orEmpty() }
        assertFalse(report.contains(secretUrl))
        assertEquals("HTTP request failed before a response was received", states.getValue("h").error)
    }
}
