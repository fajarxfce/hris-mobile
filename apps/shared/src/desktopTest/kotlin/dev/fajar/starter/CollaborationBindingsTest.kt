package dev.fajar.starter

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.notifications.CollaborationNotificationCommands
import dev.fajar.starter.app.notifications.CollaborationNotificationReplies
import dev.fajar.starter.app.work.CollaborationNotificationReplyRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxViewModel
import dev.fajar.starter.collaboration.presentation.participants.ChatParticipantsViewModel
import dev.fajar.starter.collaboration.presentation.setup.ChatSetupViewModel
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadViewModel
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
import org.koin.core.parameter.parametersOf
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
                    override suspend fun handoverIntent(
                        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                        deviceId: String,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent?
                    > = error("Unexpected SDK handover in this fixture")

                    override suspend fun stageHandover(
                        owner: dev.fajar.starter.collaboration.domain.entities.CollaborationOwner,
                        deviceId: String,
                        intent:
                            dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeHandoverIntent
                    > = error("Unexpected SDK handover in this fixture")

                    override suspend fun updateRetention(
                        retention:
                            dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
                    ): AppResult<Unit> = closeOtherOwners(retention.foreground)

                    override suspend fun acquireBackground(
                        owner: CollaborationOwner,
                        deviceId: String,
                        lifetimeMillis: Long,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
                    > = error("No implicit background runtime")

                    override suspend fun readOwnReadReceipts(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
                    > = error("No implicit read receipt lookup")

                    override suspend fun sendReadReceipt(
                        owner: CollaborationOwner,
                        deviceId: String,
                        matrixRoomId: String,
                        eventId: String,
                        visibility:
                            dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
                    ): AppResult<Unit> = error("No implicit read receipt delivery")

                    override suspend fun activatePusher(
                        owner: CollaborationOwner,
                        deviceId: String,
                    ): AppResult<Unit> = error("No implicit pusher activation")

                    override suspend fun selfIdentity(
                        owner: CollaborationOwner,
                        deviceId: String,
                    ): AppResult<
                        dev.fajar.starter.collaboration.domain.entities.CollaborationSelfIdentity?
                    > = error("No implicit identity acquisition")

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
                        untilPosition: String?,
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
                assertNotNull(container.koin.get<CollaborationSpaceRepository>())
                assertNotNull(container.koin.get<DiscoverCollaborationSpace>())
                assertNotNull(container.koin.get<ReadCollaborationSpaceContacts>())
                assertNotNull(container.koin.get<ObserveCollaborationSpaceDirectory>())
                assertNotNull(container.koin.get<CollaborationSpaceIdentityRepository>())
                assertNotNull(container.koin.get<CollaborationSpaceIdentityCommandRepository>())
                assertNotNull(container.koin.get<ObserveCollaborationSpaceIdentityCommand>())
                assertNotNull(container.koin.get<PrepareCollaborationSpaceIdentity>())
                assertSame(
                    container.koin.get<ReplayCollaborationSpaceIdentity>(),
                    container.koin.get<ReplayCollaborationSpaceIdentity>(),
                )
                assertNotNull(container.koin.get<CollaborationSpaceRootRepository>())
                assertNotNull(container.koin.get<ObserveKnownCollaborationSpace>())
                assertNotNull(container.koin.get<PublishKnownCollaborationSpace>())
                assertSame(
                    container.koin.get<RefreshCollaborationSpaceDirectory>(),
                    container.koin.get<RefreshCollaborationSpaceDirectory>(),
                )
                assertNotNull(container.koin.get<CollaborationSetupCommandRepository>())
                assertNotNull(container.koin.get<LoadCollaborationSetup>())
                assertSame(
                    container.koin.get<ActivateCollaborationPusher>(),
                    container.koin.get<ActivateCollaborationPusher>(),
                )
                assertSame(
                    container.koin.get<LoadCollaborationDevicePage>(),
                    container.koin.get<LoadCollaborationDevicePage>(),
                )
                assertSame(
                    container.koin.get<AcquireCollaborationBackgroundAdmission>(),
                    container.koin.get<AcquireCollaborationBackgroundAdmission>(),
                )
                assertSame(
                    container.koin.get<AcquireCollaborationBackgroundRuntime>(),
                    container.koin.get<AcquireCollaborationBackgroundRuntime>(),
                )
                assertSame(
                    container.koin.get<CollaborationRecoveryRepository>(),
                    container.koin.get<CollaborationRecoveryRepository>(),
                )
                assertNotNull(container.koin.get<ReadCollaborationControl>())
                assertNotNull(container.koin.get<ReadCollaborationRecoveryProgress>())
                assertNotNull(container.koin.get<EnsureCollaborationRecoveryWake>())
                val recovery =
                    container.koin.get<dev.fajar.starter.app.work.CollaborationRecoveryWorker>()
                assertSame(
                    recovery,
                    container.koin.getAll<dev.fajar.starter.sync.domain.SyncTask>().single {
                        it.key == CollaborationRecoveryWake.WORK_KEY
                    },
                )
                assertSame(
                    container.koin.get<dev.fajar.starter.app.work.CollaborationIncomingRecovery>(),
                    container.koin.get<dev.fajar.starter.app.work.CollaborationIncomingRecovery>(),
                )
                assertSame(
                    container.koin.get<dev.fajar.starter.app.work.CollaborationCompanyRecovery>(),
                    container.koin.get<dev.fajar.starter.app.work.CollaborationCompanyRecovery>(),
                )
                assertNotNull(container.koin.get<UpdateCollaborationRecoveryProgress>())
                assertNotNull(container.koin.get<ReadCollaborationRoomRecovery>())
                assertNotNull(container.koin.get<UpdateCollaborationRoomRecovery>())
                assertSame(
                    container.koin.get<CollaborationRoomRecoveryRepository>(),
                    container.koin.get<CollaborationRoomRecoveryRepository>(),
                )
                assertSame(
                    container.koin.get<CollaborationRecoveryProgressRepository>(),
                    container.koin.get<CollaborationRecoveryProgressRepository>(),
                )
                val wakeRepository = container.koin.get<CollaborationRecoveryWakeRepository>()
                assertSame(
                    wakeRepository,
                    container.koin.get<CollaborationRecoveryWakeRepository>(),
                )
                assertNotNull(container.koin.get<RequestCollaborationRecovery>())
                assertNotNull(container.koin.get<ResumeCollaborationRecovery>())
                val readWake = container.koin.get<ReadCollaborationRecoveryWake>()
                val completeWake = container.koin.get<CompleteCollaborationRecoveryWake>()
                assertEquals(AppResult.Success(null), readWake())
                val firstWake =
                    assertIs<AppResult.Success<CollaborationRecoveryWake>>(wakeRepository.request())
                        .value
                val laterWake =
                    assertIs<AppResult.Success<CollaborationRecoveryWake>>(wakeRepository.request())
                        .value
                assertEquals(AppResult.Success(false), completeWake(firstWake))
                assertEquals(AppResult.Success(laterWake), readWake())
                assertEquals(AppResult.Success(true), completeWake(laterWake))
                assertEquals(AppResult.Success(null), readWake())
                assertNotNull(container.koin.get<ReadCollaborationIdentity>())
                assertNotNull(container.koin.get<ReadCollaborationThreadContext>())
                assertNotNull(container.koin.get<ReadCollaborationDeliveryWork>())
                assertNotNull(container.koin.get<RequestCollaborationDelivery>())
                assertNotNull(container.koin.get<AdvanceCollaborationDelivery>())
                assertNotNull(container.koin.get<CollaborationDeliveryCursorRepository>())
                assertNotNull(container.koin.get<CollaborationIdentityRepository>())
                assertNotNull(container.koin.get<CollaborationConversationRepository>())
                assertNotNull(container.koin.get<ReadCollaborationConversations>())
                assertNotNull(container.koin.get<CollaborationListRepository>())
                assertNotNull(
                    container.koin.get<
                        dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
                    >()
                )
                assertNotNull(container.koin.get<CollaborationMessageRepository>())
                assertNotNull(container.koin.get<CollaborationReceiptRepository>())
                assertNotNull(container.koin.get<RetireCollaborationMessages>())
                assertSame<Any>(
                    container.koin.get<CollaborationReceiptRepository>(),
                    container.koin.get<CollaborationReceiptRetirementRepository>(),
                )
                assertSame<Any>(
                    container.koin.get<CollaborationMessageRepository>(),
                    container.koin.get<CollaborationMessageCompletionRepository>(),
                )
                assertNotNull(container.koin.get<CollaborationHistoryRepository>())
                assertNotNull(container.koin.get<CollaborationReadStateRepository>())
                assertNotNull(container.koin.get<CollaborationNotificationSettingsRepository>())
                assertNotNull(container.koin.get<CollaborationNotificationActionRepository>())
                assertNotNull(container.koin.get<OpenCollaborationNotificationAction>())
                assertSame(
                    container.koin.get<CollaborationNotificationPublicationRepository>(),
                    container.koin.get<CollaborationNotificationPublicationRepository>(),
                )
                assertNotNull(container.koin.get<CollaborationNotificationReplyRepository>())
                assertNotNull(
                    container.koin.get<CollaborationNotificationReplyRecoveryCursorRepository>()
                )
                assertSame(
                    container.koin.get<CollaborationNotificationReplyRecoveryWorker>(),
                    container.koin.get<CollaborationNotificationReplyRecoveryWorker>(),
                )
                assertNotNull(container.koin.get<ReadCollaborationNotificationReplyRecovery>())
                assertNotNull(container.koin.get<AdvanceCollaborationNotificationReplyRecovery>())
                assertNotNull(
                    container.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()
                )
                assertNotNull(
                    container.koin.get<SettleCollaborationNotificationReplyRecoveryCycle>()
                )
                assertNotNull(
                    container.koin.get<FinishCollaborationNotificationReplyRecoveryCycle>()
                )
                assertNotNull(container.koin.get<ReadCollaborationNotificationReply>())
                assertSame(
                    container.koin.get<ReviewCollaborationNotificationReply>(),
                    container.koin.get<ReviewCollaborationNotificationReply>(),
                )
                assertSame(
                    container.koin.get<CompleteCollaborationNotificationReply>(),
                    container.koin.get<CompleteCollaborationNotificationReply>(),
                )
                assertSame(
                    container.koin.get<RetainCollaborationNotificationReply>(),
                    container.koin.get<RetainCollaborationNotificationReply>(),
                )
                assertSame(
                    container.koin.get<CollaborationNotificationCommands>(),
                    container.koin.get<CollaborationNotificationCommands>(),
                )
                assertSame(
                    container.koin.get<CollaborationNotificationReplies>(),
                    container.koin.get<CollaborationNotificationReplies>(),
                )
                assertNotNull(container.koin.get<ReadCollaborationNotificationSettings>())
                assertNotNull(container.koin.get<PrepareCollaborationMessageNotification>())
                assertNotNull(container.koin.get<ValidateCollaborationNotificationPublication>())
                assertSame(
                    container.koin.get<FinishCollaborationNotificationPublication>(),
                    container.koin.get<FinishCollaborationNotificationPublication>(),
                )
                assertSame(
                    container.koin.get<IssueCollaborationMessageNotification>(),
                    container.koin.get<IssueCollaborationMessageNotification>(),
                )
                val notificationSettings =
                    container.koin.get<UpdateCollaborationNotificationSettings>()
                assertSame(
                    notificationSettings,
                    container.koin.get<UpdateCollaborationNotificationSettings>(),
                )
                assertNotNull(container.koin.get<ReadCollaborationReadState>())
                assertSame(
                    container.koin.get<DeliverCollaborationReadReceipts>(),
                    container.koin.get<DeliverCollaborationReadReceipts>(),
                )
                assertSame(
                    container.koin.get<MarkCollaborationMessagesRead>(),
                    container.koin.get<MarkCollaborationMessagesRead>(),
                )
                assertSame(
                    container.koin.get<CollaborationHistoryTraversalRepository>(),
                    container.koin.get<CollaborationHistoryTraversalRepository>(),
                )
                assertNotNull(container.koin.get<CollaborationDraftRepository>())
                assertNotNull(container.koin.get<CollaborationDraftRecoveryCursorRepository>())
                assertNotNull(container.koin.get<ReadCollaborationDraftRecovery>())
                assertNotNull(container.koin.get<AdvanceCollaborationDraftRecovery>())
                assertNotNull(container.koin.get<SuspendCollaborationDraftSend>())
                assertNotNull(container.koin.get<ObserveCollaborationDraft>())
                assertNotNull(container.koin.get<EditCollaborationDraft>())
                assertNotNull(container.koin.get<PrepareCollaborationDraftSend>())
                assertNotNull(container.koin.get<ConfirmCollaborationDraftSend>())
                assertNotNull(container.koin.get<CancelCollaborationText>())
                assertSame<Any>(
                    container.koin.get<CollaborationMessageRepository>(),
                    container.koin.get<CollaborationMessageCancellationRepository>(),
                )
                assertNotNull(container.koin.get<AdvanceCollaborationIncomingReferences>())
                assertNotNull(container.koin.get<ObserveCollaborationHistory>())
                assertNotNull(container.koin.get<AcquireCollaborationHistory>())
                assertNotNull(container.koin.get<AcquireCollaborationProviderHistory>())
                assertNotNull(container.koin.get<ReadCollaborationHistoryReferences>())
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
                    container.koin.get<RecoverCollaborationMessageReceipts>(),
                    container.koin.get<RecoverCollaborationMessageReceipts>(),
                )
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
                assertNotNull(container.koin.get<ObserveCollaborationInbox>())
                assertNotNull(container.koin.get<ObserveCollaborationAccess>())
                assertNotNull(container.koin.get<ObserveCollaborationSavedReplies>())
                assertNotNull(container.koin.get<DiscardCollaborationNotificationReply>())
                assertNotNull(
                    container.koin.get<
                        dev.fajar.starter.collaboration.domain.repositories.CollaborationMessagePreventionRepository
                    >()
                )
                val screens = ViewModelStore()
                try {
                    screens.put(
                        "saved-replies",
                        container.koin.get<
                            dev.fajar.starter.collaboration.presentation.replies.SavedRepliesViewModel
                        > {
                            parametersOf("selection", "")
                        },
                    )
                    screens.put(
                        "thread",
                        container.koin.get<ChatThreadViewModel> {
                            parametersOf("selection", "conversation")
                        },
                    )
                    screens.put(
                        "setup",
                        container.koin.get<ChatSetupViewModel> { parametersOf("selection") },
                    )
                    screens.put(
                        "inbox",
                        container.koin.get<ChatInboxViewModel> { parametersOf("selection") },
                    )
                    screens.put(
                        "participants",
                        container.koin.get<ChatParticipantsViewModel> { parametersOf("selection") },
                    )
                } finally {
                    screens.clear()
                }
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
