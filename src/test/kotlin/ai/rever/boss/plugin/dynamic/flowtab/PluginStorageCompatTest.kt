package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.CancellationException
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

        assertFailsWith<IllegalStateException> {
            storage.removeJsonValue("runstate:flow-1")
        }
        Unit
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
        Unit
    }

    @Test
    fun `removeJsonValue propagates cancellation immediately`() = runBlocking {
        val storage = object : DesktopStorage() {
            override suspend fun remove(key: String) {
                throw CancellationException("cancelled")
            }
        }

        assertFailsWith<CancellationException> {
            storage.removeJsonValue("runstate:flow-1")
        }
        Unit
    }
}
