package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Perform a run's destructive UI reset only after fence admission, while the
 * requesting job is still active and still owns the current run generation.
 */
internal suspend fun admitRun(
    token: Long,
    admissionContext: CoroutineContext,
    isCurrent: (Long) -> Boolean,
    onAdmit: () -> Unit,
): Boolean = withContext(admissionContext) {
    coroutineContext.ensureActive()
    if (!isCurrent(token)) return@withContext false
    onAdmit()
    true
}
