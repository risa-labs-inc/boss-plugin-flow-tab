package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunJobFenceTest {

    @Test
    fun `cancelling with no current run is a no-op`() = runTimedTest {
        val fence = RunJobFence(this)

        fence.cancelAll()

        assertFalse(fence.hasActiveRun())
    }

    @Test
    fun `new run waits for cancelled run to finish unwinding`() = runTimedTest {
        val fence = RunJobFence(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        fence.launch {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstStarted.await()

        fence.cancelAll()
        val second = fence.launch { secondStarted.complete(Unit) }
        yield()

        try {
            assertFalse(secondStarted.isCompleted)
        } finally {
            releaseFirst.complete(Unit)
        }
        second.join()
        assertTrue(secondStarted.isCompleted)
    }

    @Test
    fun `cancelling a queued run completes it after the prior run unwinds`() = runTimedTest {
        val fence = RunJobFence(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var queuedBlockStarted = false
        fence.launch {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstStarted.await()

        val queued = fence.launch { queuedBlockStarted = true }
        var completionCalled = false
        queued.invokeOnCompletion { completionCalled = true }
        // Let the queued coroutine enter its non-cancellable fence wait before
        // cancelling it; otherwise it may be cancelled before its body starts.
        yield()
        fence.cancelAll()
        yield()

        try {
            assertFalse(completionCalled)
            assertFalse(queuedBlockStarted)
        } finally {
            releaseFirst.complete(Unit)
        }
        queued.join()
        assertTrue(completionCalled)
        assertFalse(queuedBlockStarted)
    }

    private fun runTimedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
}
