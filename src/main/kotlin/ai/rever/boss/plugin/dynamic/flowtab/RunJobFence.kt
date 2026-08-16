package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Launches runs serially even when cancellation of the preceding run is cooperative. */
internal class RunJobFence(private val scope: CoroutineScope) {
    private var current: Job? = null

    fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> Unit,
    ): Job {
        val previous = current
        return scope.launch(context) {
            previous?.join()
            block()
        }.also { current = it }
    }

    fun cancelCurrent() {
        current?.cancel()
    }
}
