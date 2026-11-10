package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.notifications.CollaborationNotificationDelivery
import dev.fajar.starter.app.work.ApplicationPush
import dev.fajar.starter.app.work.ApplicationPushReceiver
import dev.fajar.starter.app.work.CollaborationDeliveryWorker
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.app.work.CollaborationPriorityRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.thread.ChatThreadViewModel
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.securestorage.AndroidCredentialStore
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Actual app bindings/storage/worker, seeded fictional login, actual control-plane and SDK I/O. */
internal class HrisWorkerProofContainer(
    context: Context,
    private val owner: CollaborationOwner,
    private val conversationId: String,
    hrisAccessToken: String,
    private val runtime: CollaborationRuntimeRepository,
    nativeHttp: HttpClient,
    platformScheduler: WorkScheduler? = null,
    private val backgroundOnly: Boolean = false,
    beforeOriginalProjection: (suspend (CollaborationMessageRecord) -> Unit)? = null,
    afterSpaceStaging: (suspend (CollaborationSpaceMessageRecord) -> Unit)? = null,
) {
    private val application = HrisProofApplicationAccess(owner, hrisAccessToken, backgroundOnly)
    private val database = createAppDatabase(context)
    private val access = owner.localListAccess()
    private val requests = mutableListOf<String>()
    private var priorityNotifications: HrisPriorityNotificationHost? = null
    val scheduledKeys: List<String>
        get() = synchronized(requests) { requests.toList() }

    private val container =
        createAppContainer(
                createUserPreferences(context),
                database,
                module {
                    single<
                        dev.fajar.starter.notifications.data.datasources.NotificationPermissionSource
                    > {
                        object :
                            dev.fajar.starter.notifications.data.datasources.NotificationPermissionSource {
                            override suspend fun check() =
                                dev.fajar.starter.notifications.data.dto.NotificationPermission
                                    .Denied

                            override suspend fun request():
                                dev.fajar.starter.notifications.data.dto.NotificationPermission =
                                error("No implicit notification permission request")
                        }
                    }
                },
                platform = AppPlatform.Android,
                commandCredentials =
                    AndroidCredentialStore(context, "worker-proof-command-content-key"),
                collaborationListCredentials =
                    AndroidCredentialStore(context, "worker-proof-list-content-key"),
                collaborationSpaceDirectoryCredentials =
                    AndroidCredentialStore(context, "worker-proof-space-directory-content-key"),
                collaborationReceiptCredentials =
                    AndroidCredentialStore(context, "worker-proof-receipt-content-key"),
                collaborationHistoryCredentials =
                    AndroidCredentialStore(context, "worker-proof-history-content-key"),
                collaborationNotificationInputCredentials =
                    AndroidCredentialStore(context, "worker-proof-notification-input-key"),
                workScheduler =
                    platformScheduler
                        ?: object : WorkScheduler {
                            override suspend fun enqueue(key: String) {
                                check(
                                    key in
                                        setOf(
                                            QueueCollaborationText.DELIVERY_KEY,
                                            CollaborationRecoveryWake.WORK_KEY,
                                            CollaborationSpaceRecoveryWake.WORK_KEY,
                                            CollaborationSpaceIncomingWake.WORK_KEY,
                                        )
                                )
                                synchronized(requests) { requests += key }
                            }
                        },
                http =
                    module {
                        single<HttpClient>(named(HttpClients.Authenticated)) { application.client }
                        single<HttpClient>(named(HttpClients.Public)) { nativeHttp }
                    },
                collaborationRuntime = { _, _ -> runtime },
            )
            .also {
                // Replace seeded platform inputs before any feature/worker binding is resolved.
                it.koin.loadModules(
                    listOf(
                        module {
                            single<SessionRepository> { application.sessions }
                            single<WorkspaceRepository> { application.workspaces }
                            single<IdentityServer> { application.server }
                            single<AppBuild> { application.build }
                        }
                    )
                )
                if (beforeOriginalProjection != null) {
                    val original = it.koin.get<CollaborationMessageRepository>()
                    it.koin.loadModules(
                        listOf(
                            module {
                                single<CollaborationMessageRepository> {
                                    object : CollaborationMessageRepository by original {
                                        override suspend fun update(
                                            access: CollaborationLocalAccess,
                                            expected: CollaborationMessageRecord,
                                            state: CollaborationMessageState,
                                        ): AppResult<Boolean> {
                                            if (state is CollaborationMessageState.Sent)
                                                beforeOriginalProjection(expected)
                                            return original.update(access, expected, state)
                                        }
                                    }
                                }
                            }
                        )
                    )
                }
                if (afterSpaceStaging != null) {
                    val original = it.koin.get<CollaborationSpaceMessageRepository>()
                    it.koin.loadModules(
                        listOf(
                            module {
                                single<CollaborationSpaceMessageRepository> {
                                    object : CollaborationSpaceMessageRepository by original {
                                        override suspend fun update(
                                            access: CollaborationSpaceLocalAccess,
                                            expected: CollaborationSpaceMessageRecord,
                                            state: CollaborationMessageState,
                                        ): AppResult<Boolean> {
                                            val result = original.update(access, expected, state)
                                            if (
                                                state is CollaborationMessageState.Staged &&
                                                    result == AppResult.Success(true)
                                            ) {
                                                val retained =
                                                    requireNotNull(
                                                        expect(
                                                            original.get(access, expected.intent.id)
                                                        )
                                                    )
                                                check(
                                                    retained.intent == expected.intent &&
                                                        retained.state == state
                                                )
                                                afterSpaceStaging(retained)
                                            }
                                            return result
                                        }
                                    }
                                }
                            }
                        )
                    )
                }
            }

    fun incomingHistory() = HrisSpaceIncomingProofHost(container.koin)

    fun incomingWorker() = HrisSpaceIncomingWorkerProofHost(container.koin) { scheduledKeys }

    fun spaceRecoverySeed(
        original: ProtectedHrisProofOwner,
        previous: JSONObject,
        group: JSONObject,
    ) = HrisSpaceRecoverySeed(container.koin, original, previous, group)

    fun spaceRecovery(seed: HrisSpaceRecoverySeed) =
        HrisSpaceRecoveryProofHost(container.koin, seed) { scheduledKeys }

    suspend fun activate() {
        database.accounts.activate(
            access.sessionId,
            accountStorageKey(access.audience, access.accountId),
        )
        // This fixture seeds the application host instead of starting its periodic scheduler.
        // A reopened foreground host must replace the prior background-only retention decision.
        val parent =
            CollaborationNativeParent(
                owner.sessionId,
                owner.audience,
                owner.accountId,
                owner.nativeSessionId,
            )
        expect(
            runtime.updateRetention(
                CollaborationRuntimeRetention(parent, owner.takeUnless { backgroundOnly })
            )
        )
    }

    suspend fun cacheConversation(targetConversationId: String = conversationId) {
        expect(
            container.koin
                .get<RefreshCollaborationList>()
                .conversation(application.workspaceId, targetConversationId)
        )
    }

    suspend fun queue(
        id: String,
        body: String,
        targetConversationId: String = conversationId,
    ): CollaborationMessageRecord =
        expect(
            container.koin.get<QueueCollaborationText>()(
                application.workspaceId,
                targetConversationId,
                id,
                body,
                access,
            )
        )

    /** Stages the fixture through the real app policy; delivery remains paused. */
    suspend fun stageOriginal(deviceId: String, messageId: String): CollaborationMessageRecord =
        expect(
                container.koin.get<StageCollaborationText>()(
                    application.workspaceId,
                    deviceId,
                    messageId,
                )
            )
            .also { check(it.state is CollaborationMessageState.Staged && it.state.origin != null) }

    suspend fun prepareOriginalRoom(
        owner: CollaborationSpaceOwner,
        canonicalId: String,
    ): CollaborationSpaceConversation {
        check(
            owner.parent.sessionId == access.sessionId && owner.parent.accountId == access.accountId
        )
        val conversation =
            expect(
                container.koin
                    .get<CollaborationSpaceConversationRepository>()
                    .get(owner, canonicalId)
            )
        val bindings = container.koin.get<CollaborationSpaceRoomBindingRepository>()
        val readWake = container.koin.get<ReadCollaborationSpaceRecoveryWake>()
        val previous = expect(bindings.getByConversation(owner.localDirectoryAccess(), canonicalId))
        val previousWake = expect(readWake())
        val previousRequests = scheduledKeys.count { it == CollaborationSpaceRecoveryWake.WORK_KEY }
        val retained =
            expect(container.koin.get<PrepareCollaborationSpaceRoomBinding>()(conversation))
        check(retained.original?.conversation?.position?.conversationId == conversationId)
        val wake = expect(readWake())
        val requests = scheduledKeys.count { it == CollaborationSpaceRecoveryWake.WORK_KEY }
        if (previous == null) {
            check(wake != null && wake != previousWake && requests == previousRequests + 1)
        } else {
            check(retained == previous && wake == previousWake && requests == previousRequests)
        }
        return conversation
    }

    /** Canonical API facts only: new worker coverage starts without any local room binding. */
    suspend fun retainUnboundSpaceConversation(
        owner: CollaborationSpaceOwner,
        canonicalId: String,
    ): CollaborationSpaceConversation {
        check(
            owner.parent.sessionId == access.sessionId && owner.parent.accountId == access.accountId
        )
        val local = owner.localDirectoryAccess()
        val bindings = container.koin.get<CollaborationSpaceRoomBindingRepository>()
        check(expect(bindings.getByConversation(local, canonicalId)) == null)
        val conversation =
            expect(
                container.koin
                    .get<CollaborationSpaceConversationRepository>()
                    .get(owner, canonicalId)
            )
        val metadata = container.koin.get<CollaborationSpaceConversationMetadataRepository>()
        check(expect(metadata.retain(local, conversation)))
        check(expect(metadata.getById(local, canonicalId)) == conversation.localMetadata())
        check(expect(bindings.getByConversation(local, canonicalId)) == null)
        return conversation
    }

    suspend fun queueSpace(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        id: String,
        body: String,
    ) = expect(container.koin.get<QueueCollaborationSpaceText>()(access, conversationId, id, body))

    suspend fun stageSpace(
        ready: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        id: String,
    ) = expect(container.koin.get<StageCollaborationSpaceText>()(ready, conversation, id))

    suspend fun spaceMessage(access: CollaborationSpaceLocalAccess, id: String) =
        expect(container.koin.get<CollaborationSpaceMessageRepository>().get(access, id))

    suspend fun spaceReceipts(access: CollaborationSpaceLocalAccess, conversationId: String) =
        expect(
            container.koin.get<CollaborationSpaceReceiptRepository>().read(access, conversationId)
        )

    suspend fun originalQueue(ready: CollaborationSpaceRuntimeReady, roomId: String) =
        expect(
            container.koin
                .get<CollaborationSpaceRuntimeRepository>()
                .inspectOriginalQueue(ready, roomId)
        )

    suspend fun deliverOriginals(
        ready: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ) = container.koin.get<DeliverCollaborationSpaceOriginalMessages>()(ready, conversation)

    /** Fetches actual native identity metadata; never invents the original selection time. */
    suspend fun originalIdentity(owner: CollaborationSpaceOwner): CollaborationSpaceIdentity =
        requireNotNull(
            expect(container.koin.get<CollaborationSpaceIdentityRepository>().read(owner)).identity
        )

    suspend fun originalBootstrap(
        owner: CollaborationSpaceOwner,
        request: CollaborationSpaceBootstrap,
    ): CollaborationSpaceGateway =
        expect(container.koin.get<CollaborationSpaceSetupRepository>().bootstrap(owner, request))

    suspend fun cancel(expected: CollaborationMessageRecord) {
        expect(
            container.koin.get<CancelCollaborationText>()(application.workspaceId, access, expected)
        )
    }

    suspend fun message(id: String): CollaborationMessageRecord? =
        expect(container.koin.get<CollaborationMessageRepository>().get(access, id))

    fun observePending(targetConversationId: String = conversationId) =
        container.koin.get<ObserveCollaborationMessages>()(
            application.workspaceId,
            targetConversationId,
        )

    suspend fun receipts(targetConversationId: String = conversationId) =
        expect(
            container.koin.get<CollaborationReceiptRepository>().read(access, targetConversationId)
        )

    suspend fun draft(): CollaborationDraft? =
        expect(
            container.koin.get<ObserveCollaborationDraft>()(
                    application.workspaceId,
                    conversationId,
                    access,
                )
                .first()
        )

    suspend fun freeze(id: String, body: String): CollaborationDraft {
        check(draft() == null)
        val edited =
            expect(
                container.koin.get<EditCollaborationDraft>()(
                    application.workspaceId,
                    conversationId,
                    null,
                    id,
                    body,
                    access,
                )
            )
        return expect(
                container.koin.get<PrepareCollaborationDraftSend>()(
                    application.workspaceId,
                    edited,
                    access,
                )
            )
            .also {
                check(it.submissionId == id && it.text == body && !it.reviewRequired)
                check(it.revision != id)
            }
    }

    suspend fun pending(): List<CollaborationMessageRecord> =
        expect(
            container.koin.get<ObserveCollaborationMessages>()(
                    application.workspaceId,
                    conversationId,
                )
                .first()
        )

    suspend fun history(): CollaborationHistoryPage =
        expect(
            container.koin.get<ObserveCollaborationHistory>()(
                    application.workspaceId,
                    conversationId,
                )
                .first()
        )

    suspend fun completion(id: String): CollaborationMessageCompletion? =
        expect(
            container.koin
                .get<CollaborationMessageCompletionRepository>()
                .readCompletion(access, id)
        )

    fun syncTasks(): List<SyncTask> {
        val tasks = container.koin.getAll<SyncTask>()
        val worker = container.koin.get<CollaborationDeliveryWorker>()
        check(tasks.single { it.key == worker.key } === worker)
        return tasks
    }

    suspend fun runWorker() = syncTasks().single { it.key == QueueCollaborationText.DELIVERY_KEY }()

    suspend fun capturedPending() =
        expect(
            container.koin.get<ObserveCollaborationMessages>()(
                    CollaborationLocalScope.Captured(access),
                    conversationId,
                )
                .first()
        )

    /** Raw retained facts are checked only after the real local access gate admits this owner. */
    suspend fun capturedHistory(): CollaborationHistoryPage {
        check(
            expect(
                container.koin.get<ObserveCollaborationAccess>()(
                        CollaborationLocalScope.Captured(access)
                    )
                    .first()
            ) == access
        )
        return expect(
            container.koin
                .get<CollaborationHistoryRepository>()
                .observe(access, conversationId)
                .first()
        )
    }

    /** Bounded fixture scan, through owned local reads; never enlarges a production page. */
    suspend fun capturedOriginals(): List<CollaborationHistoryMessage> {
        val messages = mutableListOf<CollaborationHistoryMessage>()
        var after: String? = null
        repeat(4) {
            check(
                expect(
                    container.koin.get<ObserveCollaborationAccess>()(
                            CollaborationLocalScope.Captured(access)
                        )
                        .first()
                ) == access
            )
            val page =
                expect(
                    container.koin
                        .get<CollaborationHistoryRepository>()
                        .observe(access, conversationId, after = after)
                        .first()
                )
            messages += page.messages
            if (page.next == null) {
                check(messages.map { it.primaryEventId }.distinct().size == messages.size)
                return messages
            }
            check(page.next != after)
            after = page.next
        }
        error("Fixture exceeded its four-page retained history bound")
    }

    suspend fun capturedReferences(
        deviceId: String,
        roomId: String,
        purpose: CollaborationHistoryPurpose = CollaborationHistoryPurpose.Incoming,
    ) =
        expect(
            container.koin.get<ReadCollaborationHistoryReferences>()(
                CollaborationLocalScope.Captured(access),
                CollaborationHistoryTraversal(conversationId, roomId, deviceId, purpose),
            )
        )

    suspend fun queuePriority(fields: Map<String, String>) {
        check(backgroundOnly)
        check(
            expect(container.koin.get<ApplicationPushReceiver>().queue(fields)) ==
                ApplicationPush.Chat
        )
    }

    suspend fun prioritySnapshot() = expect(container.koin.get<ReadCollaborationPushPriority>()())

    suspend fun priorityWake() = expect(container.koin.get<ReadCollaborationRecoveryWake>()())

    suspend fun priorityPosition(deviceId: String, roomId: String): List<Any> =
        listOf(
            expect(container.koin.get<ReadCollaborationRecoveryProgress>()()),
            expect(container.koin.get<ReadCollaborationRoomRecovery>()(access)),
            capturedReferences(deviceId, roomId, CollaborationHistoryPurpose.Browse),
            capturedReferences(deviceId, roomId, CollaborationHistoryPurpose.Incoming),
        )

    suspend fun runPriority(
        parent: CollaborationNativeParent
    ): dev.fajar.starter.sync.domain.SyncResult {
        check(backgroundOnly)
        return container.koin.get<CollaborationPriorityRecovery>()(parent)
    }

    fun startPriorityNotifications(deviceId: String) {
        check(backgroundOnly && priorityNotifications == null)
        priorityNotifications =
            HrisPriorityNotificationHost(
                container.koin,
                CollaborationNativeParent(
                    owner.sessionId,
                    owner.audience,
                    owner.accountId,
                    owner.nativeSessionId,
                ),
            ) {
                check(!expect(runtime.isOpen(owner, deviceId)))
            }
    }

    fun priorityNotificationSubmissions() = requireNotNull(priorityNotifications).submissions

    suspend fun priorityPublicationState() =
        expect(
                container.koin
                    .get<
                        dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationPublicationRepository
                    >()
                    .read(
                        CollaborationRecoveryAccess(
                            owner.sessionId,
                            owner.audience,
                            owner.accountId,
                        ),
                        owner.companyId,
                        conversationId,
                    )
            )
            ?.body
            ?.state

    suspend fun runIncoming(deviceId: String): dev.fajar.starter.sync.domain.SyncResult {
        check(backgroundOnly)
        val parent =
            CollaborationNativeParent(
                owner.sessionId,
                owner.audience,
                owner.accountId,
                owner.nativeSessionId,
            )
        expect(runtime.updateRetention(CollaborationRuntimeRetention(parent, null)))
        val page = expect(container.koin.get<LoadCollaborationDevicePage>()(parent, limit = 1))
        check(page.parent == parent && page.nextAfterCompanyId == null)
        val reference = page.devices.single()
        check(reference.companyId == owner.companyId && reference.deviceId == deviceId)
        val grant =
            expect(container.koin.get<AcquireCollaborationBackgroundAdmission>()(parent, reference))
        return container.koin.get<CollaborationIncomingRecovery>()(grant)
    }

    suspend fun foregroundHistory(
        deviceId: String,
        roomId: String,
        originalEventId: String,
        originalBody: String,
        historical: List<HrisHistoricalMessage>,
    ): HrisForegroundHistoryResult =
        HrisForegroundHistoryProbe(
                factory = {
                    container.koin.get<ChatThreadViewModel> {
                        parametersOf(application.workspaceId, conversationId)
                    }
                },
                snapshot = { capturedOriginals() },
                references = {
                    expect(
                        container.koin.get<ReadCollaborationHistoryReferences>()(
                            application.workspaceId,
                            CollaborationHistoryTraversal(conversationId, roomId, deviceId),
                        )
                    )
                },
            )
            .run(originalEventId, originalBody, historical)

    suspend fun close() =
        withContext(NonCancellable) {
            try {
                container.koin.get<CollaborationNotificationDelivery>().closeAndJoin()
                container.koin.get<ApplicationCollaborationLifetime>().closeAndJoin()
                drainHrisProofRuntime { runtime.closeOtherOwners(null) }
            } finally {
                try {
                    container.close()
                } finally {
                    application.close()
                }
            }
        }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
