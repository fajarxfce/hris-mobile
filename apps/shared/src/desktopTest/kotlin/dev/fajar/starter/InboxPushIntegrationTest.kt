package dev.fajar.starter

import dev.fajar.starter.app.di.*
import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.app.work.InboxPushWorker
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.admitInbox
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.notifications.data.datasources.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.usecases.SelectWorkspace
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.module

class InboxPushIntegrationTest {
    @Test
    fun realContainerSynchronizesEncryptedInboxBeforeDisplayingAValidatedTarget() = runBlocking {
        withFixture { server, koin, session, access, shown ->
            val receive = koin.get<QueueInboxPushSignal>()
            assertEquals(AppResult.Success(true), receive(fields(server, session)))
            val worker = koin.get<InboxPushWorker>()
            assertEquals(SyncResult.Retry(), worker())
            assertEquals(SyncResult.Complete, worker())
            val notification = shown.single()
            assertEquals("HRIS", notification.title)
            assertEquals("Open your inbox to view an update.", notification.body)
            assertFalse(notification.body.contains(server.inbox.firstBody))
            val link =
                koin.get<ResolveAppLink>()(
                    "${AppEnvironment.Dev.linkScheme}://app/${notification.destination}"
                )
            assertEquals(
                AppLink.InboxItem(session.user.id, access.companyId, server.inbox.firstId),
                link,
            )
            val saved =
                koin
                    .get<InboxCacheRepository>()
                    .observe(access, InboxCacheQuery.Item(server.inbox.firstId))
                    .first()
            val page = assertIs<AppResult.Success<InboxCachePage>>(saved).value
            assertNotNull(page.checkpoint)
            assertEquals(server.inbox.firstBody, page.items.single().body)
            assertEquals(AppResult.Success(false), receive(fields(server, session)))
            assertEquals(1, shown.size)
            assertTrue(server.inbox.actions.isEmpty())
            assertTrue(server.unexpectedPaths.isEmpty())
        }
    }

    @Test
    fun networkFailureAndConfirmedDenialRetainTheHintUntilAdmittedSynchronization() = runBlocking {
        withFixture { server, koin, session, access, shown ->
            val receive = koin.get<QueueInboxPushSignal>()
            val worker = koin.get<InboxPushWorker>()
            val queue = koin.get<InboxPushQueueRepository>()
            val cache = koin.get<InboxCacheRepository>()
            server.inbox.offline = true
            assertEquals(AppResult.Success(true), receive(fields(server, session)))
            assertIs<SyncResult.Retry>(worker())
            assertEquals(
                1,
                assertIs<
                        AppResult.Success<
                            dev.fajar.starter.inbox.domain.push.entities.InboxPushQueue
                        >
                    >(
                        queue.read()
                    )
                    .value
                    .pending
                    .single()
                    .preparations,
            )
            assertTrue(shown.isEmpty())
            server.inbox.offline = false
            server.inbox.failureCode = "mfa_required"
            assertIs<SyncResult.Blocked>(worker())
            assertEquals(
                "mfa_required",
                (cache.admission(access) as AppResult.Success).value.denial?.code,
            )
            assertTrue(shown.isEmpty())
            server.inbox.failureCode = null
            assertEquals(AppResult.Success(true), receive(fields(server, session)))
            assertEquals(SyncResult.Retry(), worker())
            assertEquals(1, shown.size)
            assertNull((cache.admission(access) as AppResult.Success).value.denial)
            assertTrue((queue.read() as AppResult.Success).value.pending.isEmpty())
            assertTrue(server.inbox.actions.isEmpty())
            assertTrue(server.unexpectedPaths.isEmpty())
        }
    }

    private fun fields(server: NativeUiServer, session: Session) =
        mapOf(
            "schemaVersion" to "1",
            "eventCode" to "communications.inbox_available",
            "eventId" to server.inbox.firstId,
            "accountId" to session.user.id,
            "companyId" to NativeUiServer.COMPANY_ONE,
            "inboxId" to server.inbox.firstId,
        )

    private suspend fun withFixture(
        block:
            suspend (
                NativeUiServer, Koin, Session, InboxAccess, MutableList<NotificationPayload>,
            ) -> Unit
    ) {
        val directory = Files.createTempDirectory("hris-inbox-push").toFile()
        val server = NativeUiServer()
        val shown = mutableListOf<NotificationPayload>()
        val container =
            createAppContainer(
                createUserPreferences(directory),
                createAppDatabase(directory),
                module {
                    single<PushTokenSource> { UnavailablePushTokenSource() }
                    single<NotificationPermissionSource> {
                        object : NotificationPermissionSource {
                            override suspend fun check() = NotificationPermission.Granted

                            override suspend fun request(): NotificationPermission =
                                error("No implicit permission")
                        }
                    }
                    single<NotificationDisplaySource> {
                        object : NotificationDisplaySource {
                            override suspend fun show(payload: NotificationPayload) {
                                shown += payload
                            }
                        }
                    }
                },
                platform = AppPlatform.Android,
                http = applicationNetworkModule(AppEnvironment.Dev, server::engine),
            )
        try {
            val koin = container.koin
            assertEquals(
                AppResult.Success(Unit),
                koin.get<dev.fajar.starter.settings.domain.usecases.SetLanguage>()(
                    dev.fajar.starter.settings.domain.entities.AppLanguage.English
                ),
            )
            assertIs<AppResult.Success<*>>(
                koin.get<SignIn>()(
                    "alex@example.com",
                    "Fictional-password-123!",
                    "55555555-5555-4555-8555-555555555555",
                )
            )
            val session =
                requireNotNull((koin.get<SessionRepository>().current() as AppResult.Success).value)
            val workspace =
                (koin.get<SelectWorkspace>()(session.id, NativeUiServer.COMPANY_ONE)
                        as AppResult.Success)
                    .value
            val push = koin.get<NativePushStateRepository>()
            assertEquals(
                AppResult.Success(true),
                push.compareAndSet(
                    NativePushState(),
                    NativePushState(desired = true, owner = session.pushOwner()),
                ),
            )
            val access = (admitInbox(workspace.id, workspace, session) as AppResult.Success).value
            block(server, koin, session, access, shown)
        } finally {
            container.close()
            directory.deleteRecursively()
        }
    }
}
