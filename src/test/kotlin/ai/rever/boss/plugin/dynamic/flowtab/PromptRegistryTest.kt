package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4: composable prompt registry persisted through [PluginStorageProvider]. Uses an
 * in-memory fake store to pin: upsert->get round-trip, that `prompts:index` tracks
 * ids (no dupes on re-upsert), that delete drops both the blob and the index entry,
 * that [composeSystemPrompt] assembles base+goals+rules+glossary in a stable order,
 * and that a null storage degrades gracefully instead of crashing (F7-style).
 */
class PromptRegistryTest {

    private fun sample(id: String = "p1") = Prompt(
        id = id,
        name = "Router",
        base = "You are a router.",
        goals = listOf("File a PA", "Ground every field"),
        rules = listOf("Never invent a value", "Use tools"),
        glossary = listOf("qd = daily", "bid = twice daily"),
        toolAllowlist = listOf(ToolRef(ToolScope.BOSS, "search"), ToolRef(ToolScope.BROWSER, "browser_open")),
        version = 1,
    )

    @Test
    fun `upsert then get round-trips the whole prompt`() = runBlocking {
        val reg = PromptRegistry(DesktopStorage())
        val p = sample()
        reg.upsert(p)
        assertEquals(p, reg.get("p1"))
    }

    @Test
    fun `index tracks upserted ids and list returns them`() = runBlocking {
        val reg = PromptRegistry(DesktopStorage())
        reg.upsert(sample("a"))
        reg.upsert(sample("b"))
        assertEquals(setOf("a", "b"), reg.list().map { it.id }.toSet())
    }

    @Test
    fun `re-upserting the same id does not duplicate the index`() = runBlocking {
        val storage = DesktopStorage()
        val reg = PromptRegistry(storage)
        reg.upsert(sample("a"))
        reg.upsert(sample("a").copy(name = "Renamed"))
        assertEquals(1, reg.list().size)
        assertEquals("Renamed", reg.get("a")?.name)
    }

    @Test
    fun `delete removes both the blob and the index entry`() = runBlocking {
        val storage = DesktopStorage()
        val reg = PromptRegistry(storage)
        reg.upsert(sample("a"))
        reg.upsert(sample("b"))
        reg.delete("a")
        assertNull(reg.get("a"))
        assertFalse(storage.map.containsKey("${JSON_STORAGE_PREFIX}prompt:a"))
        assertEquals(listOf("b"), reg.list().map { it.id })
    }

    @Test
    fun `delete also supports logical-key storage providers`() = runBlocking {
        val storage = TestStorage()
        val reg = PromptRegistry(storage)
        reg.upsert(sample("a"))

        reg.delete("a")

        assertNull(reg.get("a"))
        assertTrue(reg.list().isEmpty())
    }

    @Test
    fun `composeSystemPrompt is stable and includes every part in order`() {
        val out = composeSystemPrompt(sample())
        val expected = buildString {
            append("You are a router.\n")
            append("\n[GOALS]\n- File a PA\n- Ground every field\n")
            append("\n[RULES]\n- Never invent a value\n- Use tools\n")
            append("\n[GLOSSARY]\n- qd = daily\n- bid = twice daily\n")
        }
        assertEquals(expected, out)
    }

    @Test
    fun `composeSystemPrompt omits empty sections`() {
        val out = composeSystemPrompt(
            Prompt(id = "x", name = "x", base = "Base only.")
        )
        assertEquals("Base only.\n", out)
        assertTrue(!out.contains("[GOALS]"))
        assertTrue(!out.contains("[RULES]"))
        assertTrue(!out.contains("[GLOSSARY]"))
    }

    @Test
    fun `null storage degrades gracefully`() = runBlocking {
        val reg = PromptRegistry(null)
        assertTrue(reg.list().isEmpty())
        assertNull(reg.get("a"))
        reg.upsert(sample("a")) // no-op, must not throw
        reg.delete("a")         // no-op, must not throw
        assertTrue(reg.list().isEmpty())
    }
}
