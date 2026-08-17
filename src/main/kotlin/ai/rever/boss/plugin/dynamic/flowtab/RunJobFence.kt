package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Launches runs serially even when cancellation of the preceding run is cooperative.
 * Calls are confined to the UI thread; launched jobs and completion cleanup may run
 * elsewhere. [cancelAll] reaches every job in the chain. A non-cooperative predecessor
 * times out the queued attempt instead of allowing executor side effects to overlap.
 */
internal class RunJobFence(
    private val scope: CoroutineScope,
    private val predecessorTimeoutMs: Long = DEFAULT_PREDECESSOR_TIMEOUT_MS,
) {
    private val active = ConcurrentHashMap.newKeySet<Job>()
    private val predecessorWedged = AtomicBoolean(false)

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> Unit,
    ): Job {
        val predecessors = active.filterNot(Job::isCompleted)
        val job = scope.launch(context) {
            if (predecessors.isNotEmpty() && predecessorWedged.get()) {
                throw PredecessorRunTimeoutException()
            }
            // Every incomplete predecessor is snapshotted, so cancelling this queued
            // job is immediate without letting a later run skip an older active one.
            val predecessorsFinished = withTimeoutOrNull(predecessorTimeoutMs) {
                predecessors.joinAll()
                true
            } ?: false
            if (!predecessorsFinished) {
                predecessorWedged.set(true)
                throw PredecessorRunTimeoutException()
            }
            coroutineContext.ensureActive()
            block()
        }
        active += job
        job.invokeOnCompletion {
            active -= job
            if (active.none { !it.isCompleted }) predecessorWedged.set(false)
        }
        return job
    }

    fun cancelAll() {
        active.forEach(Job::cancel)
    }

    fun hasActiveRun(): Boolean = active.any { !it.isCompleted }

    private companion object {
        const val DEFAULT_PREDECESSOR_TIMEOUT_MS = 15_000L
    }
}

internal class PredecessorRunTimeoutException :
    CancellationException("Previous run did not stop before the queue timeout")
