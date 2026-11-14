package dev.fajar.starter.fixtures

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.di.*
import dev.fajar.starter.app.notifications.CollaborationNotificationDelivery
import dev.fajar.starter.app.work.CollaborationDeliveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.securestorage.MemoryCredentialStore
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.workspace.domain.usecases.SelectWorkspace
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*
import org.koin.core.KoinApplication
import org.koin.dsl.module

class CollaborationWorkerFixture {
    private val directory = Files.createTempDirectory("collaboration-worker").toFile()
    val server =
        NativeUiServer().also {
            it.collaboration.enabled = true
            it.companyPolicy.extraModules = setOf("COLLABORATION")
        }
    val sdk = NativeWorkerSdk(server.collaboration)
    val scheduled = mutableListOf<String>()
    private val sessionCredentials = MemoryCredentialStore()
    private val workspaceCredentials = MemoryCredentialStore()
    private val commands = MemoryCredentialStore()
    private val lists = MemoryCredentialStore()
    private val spaceDirectory = MemoryCredentialStore()
    private val receipts = MemoryCredentialStore()
    private val history = MemoryCredentialStore()
    private val notificationInputs = MemoryCredentialStore()
    var container = open()
        private set

    val koin
        get() = container.koin

    lateinit var session: Session
    lateinit var workspaceId: String
    val first
        get() = server.collaboration.ids.first()

    val worker
        get() = koin.get<CollaborationDeliveryWorker>()

    private fun open(): KoinApplication =
        createAppContainer(
            createUserPreferences(directory),
            createAppDatabase(directory),
            module {
                single<
                    dev.fajar.starter.notifications.data.datasources.NotificationPermissionSource
                > {
                    object :
                        dev.fajar.starter.notifications.data.datasources.NotificationPermissionSource {
                        override suspend fun check() =
                            dev.fajar.starter.notifications.data.dto.NotificationPermission.Denied

                        override suspend fun request():
                            dev.fajar.starter.notifications.data.dto.NotificationPermission =
                            error("No implicit notification permission request")
                    }
                }
            },
            credentials = sessionCredentials,
            workspaceCredentials = workspaceCredentials,
            commandCredentials = commands,
            collaborationListCredentials = lists,
            collaborationSpaceDirectoryCredentials = spaceDirectory,
            collaborationReceiptCredentials = receipts,
            collaborationHistoryCredentials = history,
            collaborationNotificationInputCredentials = notificationInputs,
            workScheduler =
                object : WorkScheduler {
                    override suspend fun enqueue(key: String) {
                        scheduled += key
                    }
                },
            platform = AppPlatform.Android,
            http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
            collaborationRuntime = { _, _ -> sdk },
        )

    suspend fun initialize() {
        assertIs<AppResult.Success<*>>(
            koin.get<SignIn>()(
                "alex@example.com",
                "Fictional-password-123!",
                UUID.randomUUID().toString(),
            )
        )
        session =
            assertNotNull(
                assertIs<AppResult.Success<Session?>>(koin.get<SessionRepository>().current()).value
            )
        server.collaboration.nativeSessionId = session.binding.serverId
        workspaceId =
            assertIs<AppResult.Success<dev.fajar.starter.workspace.domain.entities.Workspace>>(
                    koin.get<SelectWorkspace>()(session.id, NativeUiServer.COMPANY_ONE)
                )
                .value
                .id
        for (id in server.collaboration.ids) assertIs<AppResult.Success<*>>(
            koin.get<RefreshCollaborationList>().conversation(workspaceId, id)
        )
    }

    suspend fun queue(
        conversationId: String = first,
        text: String = "Fictional background message",
    ): CollaborationMessageRecord =
        assertIs<AppResult.Success<CollaborationMessageRecord>>(
                koin.get<QueueCollaborationText>()(
                    workspaceId,
                    conversationId,
                    UUID.randomUUID().toString(),
                    text,
                )
            )
            .value

    suspend fun reopen() {
        container.koin.get<CollaborationNotificationDelivery>().closeAndJoin()
        container.koin.get<ApplicationCollaborationLifetime>().closeAndJoin()
        container.close()
        sdk.closeOtherOwners(null)
        container = open()
    }

    suspend fun close() {
        container.koin.get<CollaborationNotificationDelivery>().closeAndJoin()
        container.koin.get<ApplicationCollaborationLifetime>().closeAndJoin()
        container.close()
        sdk.closeOtherOwners(null)
        directory.deleteRecursively()
    }
}
