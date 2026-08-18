package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class BrowserScriptsTest {

    @Test
    fun `input script escapes line terminators in selectors and typed values`() {
        val script = BrowserScripts.inputScript(
            selectorType = "css",
            selector = "textarea[data-label='Address'\n]",
            value = "line one\nline two\rthird\u2028fourth\u2029end",
        )

        assertContains(script, "textarea[data-label=\\'Address\\'\\n]")
        assertContains(script, "el.value='line one\\nline two\\rthird\\u2028fourth\\u2029end'")
        assertFalse(script.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' })
    }

    @Test
    fun `input script escapes controls backslashes and apostrophes`() {
        val script = BrowserScripts.inputScript(
            selectorType = "css",
            selector = "#target",
            value = "\\quote'\b\u000C\t\u0000\u001f",
        )

        assertContains(script, "el.value='\\\\quote\\'\\b\\f\\t\\u0000\\u001f'")
    }

    @Test
    fun `extract script escapes attribute line terminators`() {
        val script = BrowserScripts.extractScript(
            selectorType = "css",
            selector = "#target",
            mode = "attr",
            attr = "data\nvalue\u2028suffix",
            multiple = false,
        )

        assertContains(script, "getAttribute('data\\nvalue\\u2028suffix')")
        assertFalse(script.any { it == '\n' || it == '\u2028' })
    }

    @Test
    fun `single extract reports the shared no-match sentinel`() {
        val script = BrowserScripts.extractScript(
            selectorType = "css",
            selector = ".missing",
            mode = "text",
            attr = "",
            multiple = false,
        )

        assertContains(script, EXTRACT_NO_MATCH_ERROR)
    }
}
