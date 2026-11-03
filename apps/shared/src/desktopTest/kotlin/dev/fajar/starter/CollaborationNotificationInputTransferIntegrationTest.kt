package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/**
 * Actual Koin, encrypted SQLite and reopen; fictional HTTP/Matrix, no native callback activation.
 */
class CollaborationNotificationInputTransferIntegrationTest {
    @Test
    fun noSessionProtectionReopensAndSameAccountReplacementTransfersOnlyForReview() = runBlocking {
        withFixture { f, action, token ->
            val old = f.session
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(old, null),
            )
            val input = f.protect(token)
            f.reopen()
            assertIs<AppResult.Failed>(
                f.koin.get<TransferCollaborationNotificationInput>()(input.reference)
            )
            assertEquals(input, f.protected(input.reference))
            val next = old.copy(id = id())
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(null, next),
            )
            f.session = next
            f.reopen()
            f.withoutIncomingSelection()
            val copied = f.transfer(input.reference)
            assertNull(f.protected(input.reference))
            val retained = f.replies().single()
            assertEquals(copied.replyId, retained.id)
            assertEquals(old.id, retained.candidate.parent.sessionId)
            assertEquals(old.binding.serverId, retained.candidate.parent.nativeSessionId)
            assertEquals(input.reply.text, retained.candidate.text)
            assertIs<CollaborationNotificationReplyResult.NeedsReview>(
                expect(f.koin.get<CollaborationNotificationReplies>().resume(copied.replyId))
            )
            assertEquals(
                CollaborationNotificationReplyReview.SessionChanged,
                f.replies().single().review,
            )
            assertNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun transferredAndRepeatedCallbackReopensAsOneDurableCommand() = runBlocking {
        withFixture { f, action, token ->
            val first = f.protect(token)
            val transferred = f.transfer(first.reference)
            assertNull(f.protected(first.reference))
            assertNull(f.message(action.operationId))
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(f.koin.get<CollaborationNotificationReplies>().resume(transferred.replyId))
            )
            val original = assertNotNull(f.message(action.operationId))
            f.reopen()
            f.withoutIncomingSelection()
            val replay = f.protect(token)
            assertEquals(first.reference.id, replay.reference.id)
            assertNotEquals(first.reference.generation, replay.reference.generation)
            val copied = f.transfer(replay.reference)
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(f.koin.get<CollaborationNotificationReplies>().resume(copied.replyId))
            )
            assertEquals(original, f.message(action.operationId))
            assertTrue(f.replies().isEmpty())
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
        }
    }

    @Test
    fun copyResponseLossLeavesBothEncryptedCopiesAndReopenFinishesExactTransfer() = runBlocking {
        withFixture { f, action, token ->
            val protected = f.protect(token)
            val actual = f.koin.get<CollaborationNotificationReplyRepository>()
            var writes = 0
            val lost =
                object : CollaborationNotificationReplyRepository by actual {
                    override suspend fun retain(
                        access: CollaborationRecoveryAccess,
                        candidate: CollaborationNotificationReplyCandidate,
                    ): AppResult<CollaborationNotificationReplyRetention> {
                        val result = actual.retain(access, candidate)
                        assertIs<AppResult.Success<CollaborationNotificationReplyRetention>>(result)
                        writes++
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Controlled lost copy response")
                        )
                    }
                }
            assertIs<AppResult.Failed>(f.transferOwner(replies = lost)(protected.reference))
            assertEquals(1, writes)
            assertEquals(protected, f.protected(protected.reference))
            val copied = f.replies().single()
            assertNull(f.message(action.operationId))
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(copied.id, f.transfer(protected.reference).replyId)
            assertEquals(copied, f.replies().single())
            assertNull(f.protected(protected.reference))
            assertNull(f.message(action.operationId))
        }
    }

    @Test
    fun cleanupResponseLossKeepsAccountOwnedInputForIndependentRecovery() = runBlocking {
        withFixture { f, action, token ->
            val input = f.protect(token)
            val actual = f.koin.get<CollaborationNotificationInputRepository>()
            val lost =
                object : CollaborationNotificationInputRepository by actual {
                    override suspend fun remove(
                        expected: CollaborationNotificationInput
                    ): AppResult<Boolean> {
                        assertEquals(AppResult.Success(true), actual.remove(expected))
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Controlled lost cleanup response")
                        )
                    }
                }
            assertIs<AppResult.Failed>(f.transferOwner(inputs = lost)(input.reference))
            assertNull(f.protected(input.reference))
            val copy = f.replies().single()
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                AppResult.Success(CollaborationNotificationInputTransfer.Absent),
                f.koin.get<TransferCollaborationNotificationInput>()(input.reference),
            )
            assertEquals(copy, f.replies().single())
            assertNull(f.message(action.operationId))
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(f.koin.get<CollaborationNotificationReplies>().resume(copy.id))
            )
            assertNotNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun differentActualAccountCannotClaimOrDiscardTheProtectedReply() = runBlocking {
        withFixture { f, action, token ->
            val input = f.protect(token)
            val old = f.session
            val foreign = old.copy(id = id(), user = old.user.copy(id = id()))
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(old, foreign),
            )
            f.session = foreign
            assertIs<AppResult.Failed>(
                f.koin.get<TransferCollaborationNotificationInput>()(input.reference)
            )
            assertEquals(input, f.protected(input.reference))
            assertTrue(f.replies().isEmpty())
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(foreign, old),
            )
            f.session = old
            f.reopen()
            f.withoutIncomingSelection()
            assertNotNull(f.transfer(input.reference).replyId)
            assertNull(f.message(action.operationId))
        }
    }

    private suspend fun CollaborationWorkerFixture.protect(
        token: CollaborationNotificationActionToken
    ) =
        expect(
            koin.get<ProtectCollaborationNotificationReply>()(
                token,
                "Preserve this notification reply",
            )
        )

    private suspend fun CollaborationWorkerFixture.protected(
        reference: CollaborationNotificationInputReference
    ) = expect(koin.get<CollaborationNotificationInputRepository>().get(reference))

    private suspend fun CollaborationWorkerFixture.transfer(
        reference: CollaborationNotificationInputReference
    ) =
        assertIs<CollaborationNotificationInputTransfer.Transferred>(
            expect(koin.get<TransferCollaborationNotificationInput>()(reference))
        )

    private fun CollaborationWorkerFixture.transferOwner(
        inputs: CollaborationNotificationInputRepository = koin.get(),
        replies: CollaborationNotificationReplyRepository = koin.get(),
    ) =
        TransferCollaborationNotificationInput(
            inputs,
            koin.get(),
            replies,
            koin.get(),
            koin.get<IdentityServer>(),
            koin.get<AppBuild>(),
        )

    private suspend fun CollaborationWorkerFixture.replies():
        List<CollaborationNotificationReplyInput> =
        expect(
                koin
                    .get<CollaborationNotificationReplyRepository>()
                    .page(
                        CollaborationRecoveryAccess(
                            session.id,
                            session.binding.audience,
                            session.user.id,
                        ),
                        pendingOnly = false,
                    )
            )
            .inputs

    private suspend fun CollaborationWorkerFixture.message(id: String) =
        expect(
            koin
                .get<CollaborationMessageRepository>()
                .get(incomingGrant().owner.localListAccess(), id)
        )

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private suspend fun withFixture(
        block:
            suspend (
                CollaborationWorkerFixture,
                CollaborationNotificationAction.Reply,
                CollaborationNotificationActionToken,
            ) -> Unit
    ) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val original = f.sdk.incoming(f.first, "Fictional notification original")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            val target =
                CollaborationNotificationActionTarget(
                    f.incomingGrant().owner.companyId,
                    f.first,
                    f.server.collaboration.roomId(f.first),
                    original.eventId,
                    id(),
                    id(),
                )
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token =
                expect(
                    f.koin
                        .get<CollaborationNotificationActionRepository>()
                        .seal(
                            CollaborationNativeParent(
                                f.session.id,
                                f.session.binding.audience,
                                f.session.user.id,
                                f.session.binding.serverId,
                            ),
                            action,
                        )
                )
            block(f, action, token)
        } finally {
            f.close()
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
