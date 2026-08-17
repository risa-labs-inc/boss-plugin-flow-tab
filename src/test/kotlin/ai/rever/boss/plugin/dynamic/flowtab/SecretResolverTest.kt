package ai.rever.boss.plugin.dynamic.flowtab

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SecretResolverTest {

    @Test
    fun `host lookup requests a non-empty first page and matches logical name`() = runBlocking {
        var requested: Triple<String, Int, Int>? = null
        val provider = object : SecretDataProvider {
            override suspend fun searchSecrets(query: String, limit: Int, offset: Int): Result<PaginatedSecretsData> {
                requested = Triple(query, limit, offset)
                return Result.success(
                    PaginatedSecretsData(
                        data = listOf(
                            SecretEntryData(
                                id = "secret-1",
                                website = "API_TOKEN",
                                username = "service-user",
                                password = "runtime-value",
                                createdAt = "now",
                                updatedAt = "now",
                            ),
                        ),
                        hasMore = false,
                    ),
                )
            }

            override suspend fun getUserSecrets(limit: Int, offset: Int) = unsupported<PaginatedSecretsData>()
            override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int) =
                unsupported<PaginatedSecretsWithSharingData>()
            override suspend fun createSecret(request: CreateSecretRequestData) = unsupported<Unit>()
            override suspend fun updateSecret(request: UpdateSecretRequestData) = unsupported<Unit>()
            override suspend fun deleteSecret(id: String) = unsupported<Unit>()
            override suspend fun getSecretShares(secretId: String) = unsupported<List<SecretShareData>>()
            override suspend fun shareSecret(request: ShareSecretRequestData) = unsupported<Unit>()
            override suspend fun unshareSecret(request: UnshareSecretRequestData) = unsupported<Unit>()
        }
        val context = object : PluginContext {
            override val panelRegistry = PanelRegistry()
            override val tabRegistry = TabRegistry()
            override val pluginScope = CoroutineScope(Dispatchers.Default)
            override val secretDataProvider = provider
        }

        assertEquals("runtime-value", SecretResolver.fromSecrets(context).get("api_token"))
        assertEquals(Triple("api_token", 20, 0), requested)
    }

    private fun <T> unsupported(): Result<T> = Result.failure(UnsupportedOperationException())
}
