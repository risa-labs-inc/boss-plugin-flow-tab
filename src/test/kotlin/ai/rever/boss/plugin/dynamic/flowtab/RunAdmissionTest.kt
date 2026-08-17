package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunAdmissionTest {

    @Test
    fun `stale run does not perform admission reset`() = runTimedTest {
        var reset = false

        val admitted = admitRun(
            token = 1,
            admissionContext = EmptyCoroutineContext,
            isCurrent = { false },
        ) { reset = true }

        assertFalse(admitted)
        assertFalse(reset)
    }

    @Test
    fun `current run admits once on the supplied dispatcher and checks its token`() = runTimedTest {
        var dispatched = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched = true
                block.run()
            }
        }
        var checkedToken: Long? = null
        var resetCount = 0

        val admitted = admitRun(
            token = 7,
            admissionContext = dispatcher,
            isCurrent = { token -> checkedToken = token; token == 7L },
        ) { resetCount++ }

        assertTrue(admitted)
        assertTrue(dispatched)
        assertEquals(7L, checkedToken)
        assertEquals(1, resetCount)

        val rejected = admitRun(
            token = 8,
            admissionContext = dispatcher,
            isCurrent = { token -> token == 7L },
        ) { resetCount++ }
        assertFalse(rejected)
        assertEquals(1, resetCount)
    }

    @Test
    fun `already cancelled run does not perform admission reset`() = runTimedTest {
        var reset = false
        var cancellationObserved = false
        val job = launch {
            coroutineContext.job.cancel()
            try {
                admitRun(
                    token = 1,
                    admissionContext = EmptyCoroutineContext,
                    isCurrent = { true },
                ) { reset = true }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
        }

        job.join()

        assertFalse(reset)
        assertTrue(cancellationObserved)
    }

    private fun runTimedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
}
