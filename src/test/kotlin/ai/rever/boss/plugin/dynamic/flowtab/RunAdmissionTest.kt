package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse

class RunAdmissionTest {

    @Test
    fun `stale run does not perform admission reset`() = runTimedTest {
        var reset = false

        val admitted = admitRun(
            token = 1,
            context = EmptyCoroutineContext,
            isCurrent = { false },
        ) { reset = true }

        assertFalse(admitted)
        assertFalse(reset)
    }

    @Test
    fun `already cancelled run does not perform admission reset`() = runTimedTest {
        var reset = false
        val job = launch {
            coroutineContext.job.cancel()
            runCatching {
                admitRun(
                    token = 1,
                    context = EmptyCoroutineContext,
                    isCurrent = { true },
                ) { reset = true }
            }
        }

        job.join()

        assertFalse(reset)
    }

    private fun runTimedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
}
