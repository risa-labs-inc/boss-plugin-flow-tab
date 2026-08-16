package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Proof that a run generation was invalidated before its destructive clear was scheduled. */
internal class RunInvalidation internal constructor(
    internal val owner: RunStatePersistenceGate,
    internal val generation: Long,
)

internal enum class RunStateClearResult {
    CLEARED,
    PRESERVED_NEWER,
    TIMED_OUT,
}

/**
 * Orders run-state persistence against destructive actions such as clearing a flow.
 *
 * A generation token prevents an invalidated run from publishing late status or a
 * final snapshot. The mutex closes the remaining race where a final save has already
 * passed its generation check when Clear starts: Clear waits for that save, then
 * removes it; if Clear wins the mutex, the stale save re-checks its token and skips.
 */
internal class RunStatePersistenceGate(
    private val persistTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
    private val clearTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
) {
    private val generation = AtomicLong(0)
    private val persistenceMutex = Mutex()
    private var lastPersistedGeneration = 0L

    fun beginRun(): Long = generation.incrementAndGet()

    fun invalidateRun(): RunInvalidation = RunInvalidation(this, generation.incrementAndGet())

    fun isCurrent(token: Long): Boolean = generation.get() == token

    suspend fun persistIfCurrent(token: Long, persist: suspend () -> Unit): Boolean =
        withContext(NonCancellable) {
            // This bounds mutex contention and suspending provider calls. A host API
            // that blocks its thread without suspending cannot be pre-empted.
            withTimeoutOrNull(persistTimeoutMs) {
                persistenceMutex.withLock {
                    if (!isCurrent(token)) return@withLock false
                    persist()
                    lastPersistedGeneration = token
                    true
                }
            } ?: false
        }

    /**
     * Run a destructive clear that was preceded synchronously by [invalidateRun].
     * Requiring its opaque result makes that ordering part of this API without
     * invalidating a newer run that may start while the asynchronous clear is queued.
     */
    suspend fun clearAfterInvalidation(
        invalidation: RunInvalidation,
        clearPersisted: suspend () -> Unit,
    ): RunStateClearResult {
        check(invalidation.owner === this) { "Run was invalidated by a different gate" }
        return withContext(NonCancellable) {
            // As above, this bounds contention and cooperative/suspending host calls.
            withTimeoutOrNull(clearTimeoutMs) {
                persistenceMutex.withLock {
                    // If a newer run already saved while this async clear was queued,
                    // its snapshot owns the shared key and must not be removed.
                    if (lastPersistedGeneration > invalidation.generation) {
                        return@withLock RunStateClearResult.PRESERVED_NEWER
                    }
                    clearPersisted()
                    RunStateClearResult.CLEARED
                }
            } ?: RunStateClearResult.TIMED_OUT
        }
    }

    private companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 5_000L
    }
}
