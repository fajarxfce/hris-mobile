package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Actual application graph and encrypted SQLite; fictional server/SDK, no real account messages.
 */
class CollaborationSavedReplyIntegrationTest {
    @Test
    fun discardReopensAndLateCallbackCannotResurrectOrQueueTheOriginalReply() = runBlocking {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            val original = f.sdk.incoming(f.first, "Fictional notification original")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            val session = f.session
            val parent =
                CollaborationNativeParent(
                    session.id,
                    session.binding.audience,
                    session.user.id,
                    session.binding.serverId,
                )
            val action =
                CollaborationNotificationAction.Reply(
                    id(),
                    CollaborationNotificationActionTarget(
                        dev.fajar.starter.fixtures.NativeUiServer.COMPANY_ONE,
                        f.first,
                        f.server.collaboration.roomId(f.first),
                        original.eventId,
                        id(),
                        id(),
                    ),
                )
            val token =
                assertIs<AppResult.Success<CollaborationNotificationActionToken>>(
                        f.koin.get<CollaborationNotificationActionRepository>().seal(parent, action)
                    )
                    .value
            val input =
                assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                        f.koin.get<RetainCollaborationNotificationReply>()(
                            token,
                            "Saved private reply",
                        )
                    )
                    .value
            val snapshot =
                withTimeout(5_000) {
                    assertIs<AppResult.Success<CollaborationSavedReplies>>(
                            f.koin.get<ObserveCollaborationSavedReplies>()(f.workspaceId).first()
                        )
                        .value
                }
            assertEquals(input, snapshot.items.single().input)
            assertNotNull(snapshot.items.single().conversationName)
            assertEquals(
                AppResult.Success(CollaborationReplyDiscard.Discarded),
                f.koin.get<DiscardCollaborationNotificationReply>()(
                    f.workspaceId,
                    snapshot.access,
                    input,
                ),
            )
            f.reopen()
            val result =
                assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(
                        f.koin
                            .get<CollaborationNotificationReplies>()
                            .submit(token, "Saved private reply")
                    )
                    .value
            assertEquals(
                CollaborationNotificationReplyResult.CommandRetained(
                    CollaborationMessageState.Cancelled
                ),
                result,
            )
            val remaining =
                withTimeout(5_000) {
                    assertIs<AppResult.Success<CollaborationSavedReplies>>(
                            f.koin.get<ObserveCollaborationSavedReplies>()(f.workspaceId).first()
                        )
                        .value
                }
            assertTrue(remaining.items.isEmpty())
            assertTrue(
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .observe(remaining.access, null)
                            .first()
                    )
                    .value
                    .isEmpty()
            )
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
        } finally {
            f.close()
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
