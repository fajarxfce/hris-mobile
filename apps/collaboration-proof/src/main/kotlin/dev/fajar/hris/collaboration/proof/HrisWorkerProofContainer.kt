package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.app.work.CollaborationDeliveryWorker
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
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
) {
    private val application = HrisProofApplicationAccess(owner, hrisAccessToken, backgroundOnly)
    private val database = createAppDatabase(context)
    private val access = owner.localListAccess()
    private val requests = mutableListOf<String>()
    val scheduledKeys: List<String>
        get() = synchronized(requests) { requests.toList() }

    private val container =
        createAppContainer(
                createUserPreferences(context),
                database,
                module {},
                platform = AppPlatform.Android,
                commandCredentials =
                    AndroidCredentialStore(context, "worker-proof-command-content-key"),
                collaborationListCredentials =
                    AndroidCredentialStore(context, "worker-proof-list-content-key"),
                collaborationReceiptCredentials =
                    AndroidCredentialStore(context, "worker-proof-receipt-content-key"),
                collaborationHistoryCredentials =
                    AndroidCredentialStore(context, "worker-proof-history-content-key"),
                workScheduler =
                    platformScheduler
                        ?: object : WorkScheduler {
                            override suspend fun enqueue(key: String) {
                                check(key == QueueCollaborationText.DELIVERY_KEY)
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
            }

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

    suspend fun cacheConversation() {
        expect(
            container.koin
                .get<RefreshCollaborationList>()
                .conversation(application.workspaceId, conversationId)
        )
    }

    suspend fun queue(id: String, body: String): CollaborationMessageRecord =
        expect(
            container.koin.get<QueueCollaborationText>()(
                application.workspaceId,
                conversationId,
                id,
                body,
                access,
            )
        )

    suspend fun cancel(expected: CollaborationMessageRecord) {
        expect(
            container.koin.get<CancelCollaborationText>()(application.workspaceId, access, expected)
        )
    }

    suspend fun message(id: String): CollaborationMessageRecord? =
        expect(container.koin.get<CollaborationMessageRepository>().get(access, id))

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
                snapshot = { history() },
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
