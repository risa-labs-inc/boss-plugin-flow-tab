package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunStatePersistenceGateTest {

    @Test
    fun `current run can persist`() = runBlocking {
        val gate = RunStatePersistenceGate()
        val token = gate.beginRun()
        var saved = false

        val persisted = gate.persistIfCurrent(token) { saved = true }

        assertTrue(persisted)
        assertTrue(saved)
    }

    @Test
    fun `clear removes a save already in progress`() = runBlocking {
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
    fun `stale save queued behind clear is skipped`() = runBlocking {
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
    fun `queued clear does not invalidate a newer run`() = runBlocking {
        val gate = RunStatePersistenceGate()
        gate.beginRun()
        val invalidation = gate.invalidateRun()
        val newerToken = gate.beginRun()

        gate.clearAfterInvalidation(invalidation) { }

        assertTrue(gate.isCurrent(newerToken))
    }
}
