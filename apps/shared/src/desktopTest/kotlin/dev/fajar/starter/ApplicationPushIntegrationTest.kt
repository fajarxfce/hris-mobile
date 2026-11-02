package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryWakeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.inbox.domain.push.repositories.InboxPushQueueRepository
import dev.fajar.starter.inbox.domain.push.usecases.QueueInboxPushSignal
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ApplicationPushIntegrationTest {
    @Test
    fun realContainerRetainsChatWakeAcrossReopenWithoutWorkspaceOrSdk() = runBlocking {
        val f = CollaborationWorkerFixture()
        try {
            val session = signIn(f)
            f.scheduled.clear()
            assertNull(
                (f.koin.get<WorkspaceRepository>().current(session.id) as AppResult.Success).value
            )
            val receive = f.koin.get<ApplicationPushReceiver>()
            val wakes = f.koin.get<CollaborationRecoveryWakeRepository>()
            assertEquals(
                AppResult.Success(ApplicationPush.Chat),
                receive.queue(chatFields(session)),
            )
            val first = assertNotNull((wakes.pending() as AppResult.Success).value)
            assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
            assertEquals(0, f.sdk.restores)
            assertEquals(0, f.sdk.backgroundAcquisitions)
            assertEquals(0, f.sdk.historyReads)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertTrue(
                (f.koin.get<InboxPushQueueRepository>().read() as AppResult.Success)
                    .value
                    .pending
                    .isEmpty()
            )
            f.reopen()
            val reopened = f.koin.get<CollaborationRecoveryWakeRepository>()
            assertEquals(AppResult.Success(first), reopened.pending())
            assertEquals(
                AppResult.Success(ApplicationPush.Chat),
                f.koin.get<ApplicationPushReceiver>().queue(chatFields(session)),
            )
            assertEquals(AppResult.Success(false), reopened.acknowledge(first))
            assertNotEquals(first, (reopened.pending() as AppResult.Success).value)
            assertTrue(f.server.unexpectedPaths.isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun inboxStillQueuesSeparatelyAndInvalidChatCannotFallBackToInbox() = runBlocking {
        val f = CollaborationWorkerFixture()
        try {
            val session = signIn(f)
            f.scheduled.clear()
            val receiver = f.koin.get<ApplicationPushReceiver>()
            val inbox =
                mapOf(
                    "schemaVersion" to "1",
                    "eventCode" to "communications.inbox_available",
                    "eventId" to f.server.inbox.firstId,
                    "accountId" to session.user.id,
                    "companyId" to NativeUiServer.COMPANY_ONE,
                    "inboxId" to f.server.inbox.firstId,
                )
            assertEquals(AppResult.Success(ApplicationPush.Inbox), receiver.queue(inbox))
            assertEquals(listOf(QueueInboxPushSignal.KEY), f.scheduled)
            assertEquals(
                1,
                (f.koin.get<InboxPushQueueRepository>().read() as AppResult.Success)
                    .value
                    .pending
                    .size,
            )
            assertNull(
                (f.koin.get<CollaborationRecoveryWakeRepository>().pending() as AppResult.Success)
                    .value
            )
            assertEquals(
                AppResult.Success(null),
                receiver.queue(inbox + ("eventCode" to "collaboration.chat_available")),
            )
            assertEquals(
                AppResult.Success(null),
                receiver.queue(chatFields(session) + ("destination" to "arbitrary")),
            )
            assertEquals(
                AppResult.Success(null),
                receiver.queue(chatFields(session) + ("accountId" to UUID.randomUUID().toString())),
            )
            assertEquals(listOf(QueueInboxPushSignal.KEY), f.scheduled)
        } finally {
            f.close()
        }
    }

    private suspend fun signIn(f: CollaborationWorkerFixture): Session {
        assertIs<AppResult.Success<*>>(
            f.koin.get<SignIn>()(
                "alex@example.com",
                "Fictional-password-123!",
                UUID.randomUUID().toString(),
            )
        )
        return assertNotNull((f.koin.get<SessionRepository>().current() as AppResult.Success).value)
    }

    private fun chatFields(session: Session) =
        mapOf(
            "schemaVersion" to "1",
            "eventCode" to "collaboration.chat_available",
            "eventId" to UUID.randomUUID().toString(),
            "accountId" to session.user.id,
            "companyId" to NativeUiServer.COMPANY_ONE,
            "nativeSessionId" to session.binding.serverId,
            "deviceId" to UUID.randomUUID().toString(),
            "revision" to "1",
            "issuedAt" to "1793491200",
            "expiresAt" to "1793491500",
        )
}
