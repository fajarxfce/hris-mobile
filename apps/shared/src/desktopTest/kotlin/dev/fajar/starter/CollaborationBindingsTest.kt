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
import dev.fajar.starter.network.cancelAndClose
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
            var suppliedHttp: HttpClient? = null
            val runtime =
                object : CollaborationRuntimeRepository {
                    override suspend fun prepareHistoryReopen(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                        anchorEventId: String,
                    ): AppResult<Boolean> = error("No implicit history reopening")

                    override suspend fun readProviderHistory(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                        position: String?,
                    ): AppResult<CollaborationProviderHistoryPage> =
                        error("No implicit provider history page")

                    override suspend fun findHistoryHead(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                    ): AppResult<CollaborationHistoryHead> =
                        error("No implicit history head lookup")

                    override suspend fun readOriginalHistory(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                        anchorEventId: String,
                    ): AppResult<CollaborationOriginalWindow> =
                        error("No implicit history acquisition")

                    override suspend fun acquireMessageQueue(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
                    > = error("No implicit queue acquisition")

                    override suspend fun isOpen(
                        owner: CollaborationOwner,
                        deviceId: String,
                    ): AppResult<Boolean> = error("No implicit runtime probe")

                    override suspend fun enterTimeline(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                    ): AppResult<CollaborationTimelineLease> =
                        error("No implicit conversation entry")

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
                            single<HttpClient>(named(HttpClients.Public)) {
                                    HttpClient(MockEngine { error("No implicit network request") })
                                }
                                .onClose { it?.cancelAndClose() }
                            single<HttpClient>(named(HttpClients.Authenticated)) {
                                    HttpClient(MockEngine { error("No implicit network request") })
                                }
                                .onClose { it?.cancelAndClose() }
                        },
                    collaborationRuntime = { server, http ->
                        suppliedHttp = http
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
                assertSame(container.koin.get<HttpClient>(named(HttpClients.Public)), suppliedHttp)
                assertNotNull(container.koin.get<CollaborationSetupRepository>())
                assertNotNull(container.koin.get<CollaborationSetupCommandRepository>())
                assertNotNull(container.koin.get<LoadCollaborationSetup>())
                assertNotNull(container.koin.get<CollaborationConversationRepository>())
                assertNotNull(container.koin.get<ReadCollaborationConversations>())
                assertNotNull(container.koin.get<CollaborationListRepository>())
                assertNotNull(container.koin.get<CollaborationMessageRepository>())
                assertNotNull(container.koin.get<CollaborationReceiptRepository>())
                assertNotNull(container.koin.get<CollaborationHistoryRepository>())
                assertSame(
                    container.koin.get<CollaborationHistoryTraversalRepository>(),
                    container.koin.get<CollaborationHistoryTraversalRepository>(),
                )
                assertNotNull(container.koin.get<ObserveCollaborationHistory>())
                assertNotNull(container.koin.get<AcquireCollaborationHistory>())
                assertNotNull(container.koin.get<AcquireCollaborationProviderHistory>())
                assertSame(
                    container.koin.get<DeliverCollaborationMessages>(),
                    container.koin.get<DeliverCollaborationMessages>(),
                )
                assertSame(
                    container.koin.get<ReconcileCollaborationMessages>(),
                    container.koin.get<ReconcileCollaborationMessages>(),
                )
                assertNotNull(container.koin.get<ObserveCollaborationMessages>())
                assertSame(
                    container.koin.get<ReopenCollaborationHistory>(),
                    container.koin.get<ReopenCollaborationHistory>(),
                )
                assertSame(
                    container.koin.get<ReconcileCollaborationHistoryReferences>(),
                    container.koin.get<ReconcileCollaborationHistoryReferences>(),
                )
                assertSame(
                    container.koin.get<StageCollaborationText>(),
                    container.koin.get<StageCollaborationText>(),
                )
                assertSame(
                    container.koin.get<QueueCollaborationText>(),
                    container.koin.get<QueueCollaborationText>(),
                )
                assertNotNull(container.koin.get<ObserveCollaborationList>())
                assertSame(
                    container.koin.get<RefreshCollaborationList>(),
                    container.koin.get<RefreshCollaborationList>(),
                )
                assertNotNull(container.koin.get<CollaborationConversationCommandRepository>())
                assertNotNull(container.koin.get<PrepareCollaborationConversation>())
                assertNotNull(container.koin.get<ReplayCollaborationConversation>())
                assertNotNull(container.koin.get<ReconcileCollaborationConversation>())
                assertNotNull(container.koin.get<PrepareCollaborationSetup>())
                assertNotNull(container.koin.get<ReplayCollaborationSetup>())
                assertNotNull(container.koin.get<ReconcileCollaborationSetupReceipt>())
                assertNotNull(container.koin.get<InstallCollaborationRuntime>())
                assertNotNull(container.koin.get<RestoreCollaborationRuntime>())
                assertSame(
                    container.koin.get<EnsureCollaborationRuntime>(),
                    container.koin.get<EnsureCollaborationRuntime>(),
                )
                assertNotNull(container.koin.get<EnterCollaborationConversation>())
                lifetime.start()
                lifetime.start()
                withTimeout(5_000) { firstClose.await() }
                lifetime.closeAndJoin()
                assertEquals(2, closeCount.get())
                lifetime.start()
                lifetime.closeAndJoin()
                assertEquals(2, closeCount.get())
                assertTrue(requireNotNull(suppliedHttp).coroutineContext.isActive)
            } finally {
                lifetime.closeAndJoin()
                container.close()
                assertFalse(requireNotNull(suppliedHttp).coroutineContext.isActive)
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
            assertEquals(
                "client.collaboration_runtime_unavailable",
                assertIs<AppResult.Failed>(runtime.enterTimeline(owner, "device", "!room"))
                    .failure
                    .code,
            )
            assertEquals(AppResult.Success(Unit), runtime.closeOtherOwners(null))
        } finally {
            container.close()
            directory.deleteRecursively()
        }
    }
}
