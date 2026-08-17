package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertIs
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
        assertTrue(fence.hasActiveRun())

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
    fun `cancelling a queued run completes immediately`() = runTimedTest {
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
            assertTrue(completionCalled)
            assertFalse(queuedBlockStarted)
        } finally {
            releaseFirst.complete(Unit)
        }
        queued.join()
        assertTrue(completionCalled)
        assertFalse(queuedBlockStarted)
    }

    @Test
    fun `third run still waits for oldest active run after middle run is cancelled`() = runTimedTest {
        val fence = RunJobFence(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        fence.launch {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstStarted.await()

        val middle = fence.launch { }
        yield()
        middle.cancel()
        middle.join()
        var thirdStarted = false
        val third = fence.launch { thirdStarted = true }
        yield()

        try {
            assertFalse(thirdStarted)
        } finally {
            releaseFirst.complete(Unit)
        }
        third.join()
        assertTrue(thirdStarted)
    }

    @Test
    fun `wedged predecessor times out queued run without starting it`() = runTimedTest {
        val fence = RunJobFence(this, predecessorTimeoutMs = 20)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        fence.launch {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstStarted.await()

        var queuedBlockStarted = false
        var completionCause: Throwable? = null
        val queued = fence.launch { queuedBlockStarted = true }
        queued.invokeOnCompletion { completionCause = it }
        try {
            queued.join()
            assertFalse(queuedBlockStarted)
            assertIs<PredecessorRunTimeoutException>(completionCause)
        } finally {
            releaseFirst.complete(Unit)
        }
    }

    private fun runTimedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
}
