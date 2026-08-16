package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunJobFenceTest {

    @Test
    fun `new run waits for cancelled run to finish unwinding`() = runBlocking {
        val fence = RunJobFence(this)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        fence.launch {
            firstStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirst.await() }
        }
        firstStarted.await()

        fence.cancelCurrent()
        val second = fence.launch { secondStarted.complete(Unit) }
        delay(20)

        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        second.join()
        assertTrue(secondStarted.isCompleted)
    }
}
