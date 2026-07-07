package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P6: lanager template gallery + versioning. Pins:
 *  - bundled starter templates parse to valid [GraphSnapshot]s and [FlowGraphState.load].
 *  - export inlines referenced prompts into agent nodes + strips secret-looking config keys.
 *  - importing a template whose [GraphSnapshot.schemaVersion] is newer is refused gracefully.
 *  - a template round-trips (encode -> decode is identity) and reloads.
 *  - [classifyImport] routes flow vs template (metadata) vs recording, and gates schema.
 */
class TemplatesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ---- bundled catalog ----------------------------------------------------

    @Test
    fun `bundled catalog is non-empty and every template parses + loads`() {
        val entries = TemplateCatalog().all()
        assertTrue(entries.isNotEmpty(), "expected bundled templates on the classpath")
        for (e in entries) {
            assertTrue(e.snapshot.nodes.isNotEmpty(), "template '${e.id}' has no nodes")
            assertNotNull(e.snapshot.metadata, "template '${e.id}' must carry FlowMeta")
            // A fresh canvas loads the bundled snapshot (schema supported, no throw).
            val loaded = FlowGraphState().load(e.snapshot)
            assertTrue(loaded, "template '${e.id}' failed to load")
        }
    }

    @Test
    fun `catalog exposes the scrape and agent starters`() {
        val ids = TemplateCatalog().ids().toSet()
        assertTrue("scrape" in ids, "expected a scrape starter; got $ids")
        assertTrue("agent" in ids, "expected an agent starter; got $ids")
    }

    // ---- export: inline prompts + strip secrets -----------------------------

    @Test
    fun `export inlines a referenced prompt into the agent node`() = runBlocking {
        val prompts = PromptRegistry(TestStorage())
        prompts.upsert(
            Prompt(
                id = "p1", name = "Router",
                base = "You are a router.",
                rules = listOf("Never invent a value"),
            )
        )
        val snap = GraphSnapshot(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            metadata = FlowMeta(name = "Agent flow"),
            nodes = listOf(
                NodeModel(
                    id = "n1", type = AgentNode.KIND, title = "Agent", x = 0f, y = 0f,
                    config = buildJsonObject { put(AgentNode.PROMPT_ID_KEY, "p1") },
                )
            ),
        )
        val exported = FlowTemplates.export(snap, prompts)
        val agent = exported.nodes.single()
        val inlined = agent.config[AgentNode.SYSTEM_KEY]?.jsonPrimitive?.content
        assertNotNull(inlined, "export must inline the prompt into the system field")
        assertTrue(inlined!!.contains("You are a router."), "inlined system missing base")
        assertTrue(inlined.contains("Never invent a value"), "inlined system missing rules")
    }

    @Test
    fun `export strips secret-looking config keys`() = runBlocking {
        val snap = GraphSnapshot(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            metadata = FlowMeta(name = "leaky"),
            nodes = listOf(
                NodeModel(
                    id = "n1", type = "HTTP", title = "HTTP", x = 0f, y = 0f,
                    config = buildJsonObject {
                        put("url", "https://api.example.com")
                        put("apiKey", "sk-secret-value")
                        put("sessionToken", "tok-123")
                        put("password", "hunter2")
                    },
                )
            ),
        )
        val exported = FlowTemplates.export(snap, null)
        val cfg = exported.nodes.single().config
        assertTrue(cfg.containsKey("url"), "non-secret key must survive")
        assertFalse(cfg.containsKey("apiKey"))
        assertFalse(cfg.containsKey("sessionToken"))
        assertFalse(cfg.containsKey("password"))
        // And the serialized template must not carry the secret values either.
        val text = json.encodeToString(GraphSnapshot.serializer(), exported)
        assertFalse(text.contains("sk-secret-value"))
        assertFalse(text.contains("tok-123"))
        assertFalse(text.contains("hunter2"))
    }

    @Test
    fun `export synthesizes metadata when the source flow has none`() = runBlocking {
        val snap = GraphSnapshot(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            metadata = null,
            nodes = listOf(NodeModel("n1", "TRIGGER", "Trigger", 0f, 0f)),
        )
        val exported = FlowTemplates.export(snap, null)
        assertNotNull(exported.metadata, "a template must carry FlowMeta")
    }

    // ---- schema gating ------------------------------------------------------

    @Test
    fun `classifyImport refuses a newer schemaVersion gracefully`() {
        val future = GraphSnapshot(
            schemaVersion = SUPPORTED_SCHEMA_VERSION + 1,
            metadata = FlowMeta(name = "from the future"),
            nodes = listOf(NodeModel("n1", "TRIGGER", "Trigger", 0f, 0f)),
        )
        val text = json.encodeToString(GraphSnapshot.serializer(), future)
        val result = classifyImport(text)
        assertTrue(result is TemplateImportResult.RefusedNewer, "expected RefusedNewer, got $result")
        assertEquals(SUPPORTED_SCHEMA_VERSION + 1, (result as TemplateImportResult.RefusedNewer).schemaVersion)
    }

    // ---- classification -----------------------------------------------------

    @Test
    fun `classifyImport routes a template by its metadata`() {
        val entry = TemplateCatalog().all().first()
        val result = classifyImport(entry.raw)
        assertTrue(result is TemplateImportResult.Graph)
        assertEquals(ImportKind.TEMPLATE, (result as TemplateImportResult.Graph).kind)
    }

    @Test
    fun `classifyImport routes a plain flow with no metadata as a flow`() {
        val flow = GraphSnapshot(nodes = listOf(NodeModel("n1", "TRIGGER", "Trigger", 0f, 0f)))
        // A UI export omits null metadata (encodeDefaults=false); mimic that here.
        val text = Json { encodeDefaults = false }.encodeToString(GraphSnapshot.serializer(), flow)
        assertFalse(text.contains("metadata"))
        val result = classifyImport(text)
        assertTrue(result is TemplateImportResult.Graph)
        assertEquals(ImportKind.FLOW, (result as TemplateImportResult.Graph).kind)
    }

    @Test
    fun `classifyImport treats an explicit null metadata as a flow not a template`() {
        // FlowController encodes with encodeDefaults=true, so a metadata-less flow is
        // serialized as "metadata":null — that must NOT be mistaken for a template.
        val flow = GraphSnapshot(nodes = listOf(NodeModel("n1", "TRIGGER", "Trigger", 0f, 0f)))
        val text = json.encodeToString(GraphSnapshot.serializer(), flow) // encodeDefaults=true
        assertTrue(text.contains("metadata"))
        val result = classifyImport(text)
        assertTrue(result is TemplateImportResult.Graph)
        assertEquals(ImportKind.FLOW, (result as TemplateImportResult.Graph).kind)
    }

    @Test
    fun `classifyImport treats a recording without nodes as a recording`() {
        val recording = """{"name":"demo","actions":[{"type":"navigate","value":"https://e.com"}]}"""
        assertEquals(TemplateImportResult.Recording, classifyImport(recording))
    }

    // ---- round-trip ---------------------------------------------------------

    @Test
    fun `a template round-trips through encode-decode and reloads`() {
        val entry = TemplateCatalog().all().first()
        val encoded = json.encodeToString(GraphSnapshot.serializer(), entry.snapshot)
        val decoded = json.decodeFromString(GraphSnapshot.serializer(), encoded)
        assertEquals(entry.snapshot, decoded, "template must survive encode/decode intact")
        assertTrue(FlowGraphState().load(decoded))
    }

    @Test
    fun `exported template round-trips and reloads`() = runBlocking {
        val entry = TemplateCatalog().all().first { it.id == "agent" }
        val exported = FlowTemplates.export(entry.snapshot, null)
        val encoded = json.encodeToString(GraphSnapshot.serializer(), exported)
        val decoded = json.decodeFromString(GraphSnapshot.serializer(), encoded)
        assertEquals(exported, decoded)
        assertTrue(FlowGraphState().load(decoded))
    }
}
