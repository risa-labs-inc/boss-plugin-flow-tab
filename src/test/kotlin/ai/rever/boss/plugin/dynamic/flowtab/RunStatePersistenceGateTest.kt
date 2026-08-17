package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunStatePersistenceGateTest {

    @Test
    fun `current run can persist`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        val token = gate.beginRun()
        var saved = false

        val persisted = gate.persistIfCurrent(token) { saved = true }

        assertTrue(persisted)
        assertTrue(saved)
    }

    @Test
    fun `clear removes a save already in progress`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        val token = gate.beginRun()
        val saveEntered = CompletableDeferred<Unit>()
        val finishSave = CompletableDeferred<Unit>()
        var saved: String? = null
        val saveJob = launch {
            gate.persistIfCurrent(token) {
                saveEntered.complete(Unit)
                finishSave.await()
                saved = "stale run"
            }
        }
        saveEntered.await()

        val invalidation = gate.invalidateRun()
        val clearJob = launch { gate.clearAfterInvalidation(invalidation) { saved = null } }
        finishSave.complete(Unit)
        saveJob.join()
        clearJob.join()

        assertNull(saved)
    }

    @Test
    fun `stale save queued behind clear is skipped`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        val token = gate.beginRun()
        val invalidation = gate.invalidateRun()
        val clearEntered = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        var saved: String? = "previous run"
        val clearJob = launch {
            gate.clearAfterInvalidation(invalidation) {
                clearEntered.complete(Unit)
                finishClear.await()
                saved = null
            }
        }
        clearEntered.await()

        var persistCalled = false
        var persisted = true
        val saveJob = launch {
            persisted = gate.persistIfCurrent(token) {
                persistCalled = true
                saved = "stale run"
            }
        }
        finishClear.complete(Unit)
        clearJob.join()
        saveJob.join()

        assertFalse(persisted)
        assertFalse(persistCalled)
        assertNull(saved)
    }

    @Test
    fun `queued clear does not invalidate a newer run`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        gate.beginRun()
        val invalidation = gate.invalidateRun()
        val newerToken = gate.beginRun()

        gate.clearAfterInvalidation(invalidation) { }

        assertTrue(gate.isCurrent(newerToken))
    }

    @Test
    fun `queued clear does not delete a newer persisted run`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        gate.beginRun()
        val invalidation = gate.invalidateRun()
        val newerToken = gate.beginRun()
        var saved: String? = null
        gate.persistIfCurrent(newerToken) { saved = "newer run" }

        val cleared = gate.clearAfterInvalidation(invalidation) { saved = null }

        assertTrue(cleared == RunStateClearResult.PRESERVED_NEWER)
        assertTrue(saved == "newer run")
    }

    @Test
    fun `persist times out while clear owns the mutex`() = runTimedTest {
        val gate = RunStatePersistenceGate(persistTimeoutMs = 20, clearTimeoutMs = 1_000)
        gate.beginRun()
        val invalidation = gate.invalidateRun()
        val clearEntered = CompletableDeferred<Unit>()
        val finishClear = CompletableDeferred<Unit>()
        val clearJob = launch {
            gate.clearAfterInvalidation(invalidation) {
                clearEntered.complete(Unit)
                finishClear.await()
            }
        }
        clearEntered.await()
        val newerToken = gate.beginRun()

        val persisted = gate.persistIfCurrent(newerToken) { }

        assertFalse(persisted)
        finishClear.complete(Unit)
        clearJob.join()
    }

    @Test
    fun `cancelled run still executes final persistence on IO`() = runTimedTest {
        val gate = RunStatePersistenceGate()
        val token = gate.beginRun()
        val started = CompletableDeferred<Unit>()
        var persisted = false
        val run = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                persistRunStateOnIo(gate, token) { persisted = true }
            }
        }
        started.await()

        run.cancelAndJoin()

        assertTrue(persisted)
    }

    private fun runTimedTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { block() }
    }
}
