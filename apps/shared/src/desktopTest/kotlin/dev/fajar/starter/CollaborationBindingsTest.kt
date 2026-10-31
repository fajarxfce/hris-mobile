package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

class CollaborationBindingsTest {
    @Test
    fun oneApplicationRuntimeOwnsObservationAcrossRepeatedStartsAndClosesBeforeDisposal() =
        runBlocking {
            val directory = Files.createTempDirectory("collaboration-bindings").toFile()
            val firstClose = CompletableDeferred<Unit>()
            val closeCount = AtomicInteger()
            val factoryCount = AtomicInteger()
            var suppliedServer: IdentityServer? = null
            val runtime =
                object : CollaborationRuntimeRepository {
                    override suspend fun install(
                        owner: CollaborationOwner,
                        command: CollaborationSetupCommandRecord,
                    ): AppResult<CollaborationRuntimeLease> = error("No implicit installation")

                    override suspend fun restore(
                        owner: CollaborationOwner,
                        deviceId: String,
                    ): AppResult<CollaborationRuntimeLease?> = error("No implicit restoration")

                    override suspend fun closeOtherOwners(
                        retainedOwner: CollaborationOwner?
                    ): AppResult<Unit> {
                        assertNull(retainedOwner)
                        closeCount.incrementAndGet()
                        firstClose.complete(Unit)
                        return AppResult.Success(Unit)
                    }
                }
            val container =
                createAppContainer(
                    createUserPreferences(directory),
                    createAppDatabase(directory),
                    module {},
                    http =
                        module {
                            single<HttpClient>(named(HttpClients.Authenticated)) {
                                    HttpClient(MockEngine { error("No implicit network request") })
                                }
                                .onClose { it?.close() }
                        },
                    collaborationRuntime = { server ->
                        suppliedServer = server
                        factoryCount.incrementAndGet()
                        runtime
                    },
                )
            val lifetime = container.koin.get<ApplicationCollaborationLifetime>()
            try {
                assertSame(runtime, container.koin.get<CollaborationRuntimeRepository>())
                assertSame(runtime, container.koin.get<CollaborationRuntimeRepository>())
                assertEquals(1, factoryCount.get())
                assertSame(container.koin.get<IdentityServer>(), suppliedServer)
                assertNotNull(container.koin.get<CollaborationSetupRepository>())
                assertNotNull(container.koin.get<CollaborationSetupCommandRepository>())
                assertNotNull(container.koin.get<LoadCollaborationSetup>())
                assertNotNull(container.koin.get<CollaborationConversationRepository>())
                assertNotNull(container.koin.get<ReadCollaborationConversations>())
                assertNotNull(container.koin.get<CollaborationConversationCommandRepository>())
                assertNotNull(container.koin.get<PrepareCollaborationConversation>())
                assertNotNull(container.koin.get<ReplayCollaborationConversation>())
                assertNotNull(container.koin.get<ReconcileCollaborationConversation>())
                assertNotNull(container.koin.get<PrepareCollaborationSetup>())
                assertNotNull(container.koin.get<ReplayCollaborationSetup>())
                assertNotNull(container.koin.get<ReconcileCollaborationSetupReceipt>())
                assertNotNull(container.koin.get<InstallCollaborationRuntime>())
                assertNotNull(container.koin.get<RestoreCollaborationRuntime>())
                lifetime.start()
                lifetime.start()
                withTimeout(5_000) { firstClose.await() }
                lifetime.closeAndJoin()
                assertEquals(2, closeCount.get())
                lifetime.start()
                lifetime.closeAndJoin()
                assertEquals(2, closeCount.get())
            } finally {
                lifetime.closeAndJoin()
                container.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun hostsWithoutThePlatformBindingNeverClaimAnInstalledOrEmptyNativeStore() = runBlocking {
        val directory = Files.createTempDirectory("collaboration-unavailable").toFile()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {},
            )
        try {
            val runtime = container.koin.get<CollaborationRuntimeRepository>()
            val owner =
                CollaborationOwner(
                    "local",
                    "https://hris.example.test/",
                    "account",
                    "company",
                    "family",
                )
            val result = assertIs<AppResult.Failed>(runtime.restore(owner, "device"))
            assertEquals("client.collaboration_runtime_unavailable", result.failure.code)
            assertEquals(AppResult.Success(Unit), runtime.closeOtherOwners(null))
        } finally {
            container.close()
            directory.deleteRecursively()
        }
    }
}
