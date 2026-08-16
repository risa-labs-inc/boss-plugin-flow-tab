package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Launches runs serially even when cancellation of the preceding run is cooperative.
 * Calls are confined to the UI thread; the launched jobs may execute elsewhere.
 * The predecessor wait is intentionally unbounded to prevent side-effect overlap; a
 * wedged predecessor blocks the queue, and Clear is the UI escape hatch.
 */
internal class RunJobFence(private val scope: CoroutineScope) {
    private var current: Job? = null

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> Unit,
    ): Job {
        val previous = current
        return scope.launch(context) {
            // Cancelling a queued run must not break the dependency chain and let a
            // third run overlap the still-unwinding first run.
            withContext(NonCancellable) { previous?.join() }
            coroutineContext.ensureActive()
            block()
        }.also { current = it }
    }

    fun cancelCurrent() {
        current?.cancel()
    }
}
