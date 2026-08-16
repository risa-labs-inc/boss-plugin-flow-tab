package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PluginStorageCompatTest {

    @Test
    fun `clearPersistedRunState removes a desktop physical JSON key`() = runBlocking {
        val storage = DesktopStorage()
        storage.putJson("runstate:flow-1", "{}")

        clearPersistedRunState(storage, "flow-1")

        assertNull(storage.getJson("runstate:flow-1"))
    }

    @Test
    fun `removeJsonValue removes a logical provider JSON key`() = runBlocking {
        val storage = TestStorage()
        storage.putJson("runstate:flow-1", "{}")

        storage.removeJsonValue("runstate:flow-1")

        assertNull(storage.getJson("runstate:flow-1"))
    }

    @Test
    fun `removeJsonValue fails when both removals are silent no-ops`() = runBlocking {
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) = Unit
        }
        storage.putJson("runstate:flow-1", "{}")

        val failure = assertFailsWith<IllegalStateException> {
            storage.removeJsonValue("runstate:flow-1")
        }
        assertEquals("JSON value 'runstate:flow-1' remains after removal", failure.message)
    }

    @Test
    fun `removeJsonValue reports a failed physical removal after a logical no-op`() = runBlocking {
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) {
                if (key.startsWith(JSON_STORAGE_PREFIX)) throw IOException("disk write failed")
                super.remove(key)
            }
        }
        storage.putJson("runstate:flow-1", "{}")

        val failure = assertFailsWith<IllegalStateException> {
            storage.removeJsonValue("runstate:flow-1")
        }
        assertEquals("disk write failed", failure.suppressed.single().message)
    }

    @Test
    fun `removeJsonValue tolerates a rejected redundant key when deletion succeeds`() = runBlocking {
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) {
                if (!key.startsWith(JSON_STORAGE_PREFIX)) throw IOException("logical key rejected")
                super.remove(key)
            }
        }
        storage.putJson("runstate:flow-1", "{}")

        storage.removeJsonValue("runstate:flow-1")

        assertNull(storage.getJson("runstate:flow-1"))
    }

    @Test
    fun `clearPersistedRunState is non-cancellable once removal starts`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) {
                entered.complete(Unit)
                delay(20)
                super.remove(key)
            }
        }
        storage.putJson("runstate:flow-1", "{}")
        val job = launch { clearPersistedRunState(storage, "flow-1") }
        entered.await()

        job.cancelAndJoin()

        assertNull(storage.getJson("runstate:flow-1"))
    }

    @Test
    fun `clearPersistedRunState accepts a missing storage provider`() = runBlocking {
        clearPersistedRunState(null, "flow-1")
    }

    @Test
    fun `removeJsonValue propagates cancellation immediately`() = runBlocking {
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) {
                throw CancellationException("cancelled")
            }
        }

        val failure = assertFailsWith<CancellationException> {
            storage.removeJsonValue("runstate:flow-1")
        }
        assertEquals("cancelled", failure.message)
    }
}
