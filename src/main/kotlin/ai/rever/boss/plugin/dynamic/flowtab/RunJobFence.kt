package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Launches runs serially even when cancellation of the preceding run is cooperative.
 * The predecessor wait is intentionally unbounded to prevent side-effect overlap, so
 * a non-cooperative predecessor can still block the queue. [cancelAll] reaches every
 * job in the chain, while callers surface the queued state until they unwind.
 */
internal class RunJobFence(private val scope: CoroutineScope) {
    private val current = AtomicReference<Job?>(null)
    private val active = ConcurrentHashMap.newKeySet<Job>()

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> Unit,
    ): Job {
        val previous = current.get()
        val job = scope.launch(context) {
            // Cancelling a queued run must not break the dependency chain and let a
            // third run overlap the still-unwinding first run.
            withContext(NonCancellable) { previous?.join() }
            coroutineContext.ensureActive()
            block()
        }
        active += job
        current.set(job)
        job.invokeOnCompletion {
            active -= job
            current.compareAndSet(job, null)
        }
        return job
    }

    fun cancelAll() {
        active.forEach(Job::cancel)
    }

    fun hasActiveRun(): Boolean = active.any { !it.isCompleted }
}
