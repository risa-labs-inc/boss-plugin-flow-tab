package ai.rever.boss.plugin.dynamic.flowtab

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull

class PluginStorageCompatTest {

    @Test
    fun `removeJsonValue removes a desktop physical JSON key`() = runBlocking {
        val storage = DesktopStorage()
        storage.putJson("runstate:flow-1", "{}")

        storage.removeJsonValue("runstate:flow-1")

        assertNull(storage.getJson("runstate:flow-1"))
    }

    @Test
    fun `removeJsonValue removes a logical provider JSON key`() = runBlocking {
        val storage = TestStorage()
        storage.putJson("runstate:flow-1", "{}")

        storage.removeJsonValue("runstate:flow-1")

        assertNull(storage.getJson("runstate:flow-1"))
    }
}
