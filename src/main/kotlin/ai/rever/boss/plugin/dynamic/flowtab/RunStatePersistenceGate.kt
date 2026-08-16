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

/**
 * Orders run-state persistence against destructive actions such as clearing a flow.
 *
 * A generation token prevents an invalidated run from publishing late status or a
 * final snapshot. The mutex closes the remaining race where a final save has already
 * passed its generation check when Clear starts: Clear waits for that save, then
 * removes it; if Clear wins the mutex, the stale save re-checks its token and skips.
 */
internal class RunStatePersistenceGate {
    private val generation = AtomicLong(0)
    private val persistenceMutex = Mutex()
    private var lastPersistedGeneration = 0L

    fun beginRun(): Long = generation.incrementAndGet()

    fun invalidateRun(): RunInvalidation = RunInvalidation(this, generation.incrementAndGet())

    fun isCurrent(token: Long): Boolean = generation.get() == token

    suspend fun persistIfCurrent(token: Long, persist: suspend () -> Unit): Boolean =
        withContext(NonCancellable) {
            withTimeoutOrNull(PERSIST_TIMEOUT_MS) {
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
    ): Boolean {
        check(invalidation.owner === this && generation.get() >= invalidation.generation) {
            "Run was not invalidated by this gate before clear"
        }
        return withContext(NonCancellable) {
            persistenceMutex.withLock {
                // If a newer run already saved while this async clear was queued, its
                // snapshot owns the shared key and must not be removed.
                if (lastPersistedGeneration > invalidation.generation) return@withLock false
                clearPersisted()
                true
            }
        }
    }

    private companion object {
        const val PERSIST_TIMEOUT_MS = 5_000L
    }
}
