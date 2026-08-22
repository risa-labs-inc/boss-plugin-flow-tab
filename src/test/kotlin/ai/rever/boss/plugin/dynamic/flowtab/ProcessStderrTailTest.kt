package ai.rever.boss.plugin.dynamic.flowtab

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessStderrTailTest {
    @Test
    fun `stderr tail retains only the newest bounded lines`() {
        val tail = ProcessStderrTail(maxChars = 50, maxLines = 2)
        tail.append("first")
        tail.append("second")
        tail.append("third")

        assertEquals("second\nthird", tail.snapshot())
    }

    @Test
    fun `stderr tail keeps the end of an oversized line`() {
        val tail = ProcessStderrTail(maxChars = 5, maxLines = 2)
        tail.append("abcdefgh")

        assertEquals("defgh", tail.snapshot())
    }
}
