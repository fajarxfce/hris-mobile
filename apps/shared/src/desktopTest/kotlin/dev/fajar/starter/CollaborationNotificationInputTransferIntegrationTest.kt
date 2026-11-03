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
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

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

    @Test
    fun callbackCaptureWithoutSessionRetainsWakeAndRestoresOnlyForOldFamilyReview() = runBlocking {
        withFixture { f, action, token ->
            val old = f.session
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(old, null),
            )
            val reference =
                expect(
                    f.koin
                        .get<CollaborationNotificationReplies>()
                        .capture(token, "Captured while signed out")
                )
            assertNotNull(expect(f.koin.get<CollaborationRecoveryWakeRepository>().pending()))
            assertEquals(CollaborationRecoveryWake.WORK_KEY, f.scheduled.last())
            assertEquals("Captured while signed out", f.protected(reference)!!.reply.text)
            f.reopen()
            assertIs<AppResult.Failed>(
                f.koin.get<CollaborationNotificationReplies>().resumeProtected(reference)
            )
            val next = old.copy(id = id())
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(null, next),
            )
            f.session = next
            f.withoutIncomingSelection()
            assertIs<CollaborationNotificationReplyResult.NeedsReview>(
                expect(f.koin.get<CollaborationNotificationReplies>().resumeProtected(reference))
            )
            assertNull(f.protected(reference))
            assertEquals("Captured while signed out", f.replies().single().candidate.text)
            assertNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun callbackCaptureAndRecoveryReplayTheSameCommandAfterReopen() = runBlocking {
        withFixture { f, action, token ->
            val flow = f.koin.get<CollaborationNotificationReplies>()
            val reference = expect(flow.capture(token, "One captured command"))
            assertNull(f.message(action.operationId))
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(flow.resumeProtected(reference))
            )
            val original = assertNotNull(f.message(action.operationId))
            f.reopen()
            f.withoutIncomingSelection()
            val current = f.koin.get<CollaborationNotificationReplies>()
            val replay = expect(current.capture(token, "One captured command"))
            assertEquals(reference.id, replay.id)
            assertNotEquals(reference.generation, replay.generation)
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(current.resumeProtected(replay))
            )
            assertEquals(original, f.message(action.operationId))
            assertTrue(f.replies().isEmpty())
            assertNull(f.protected(replay))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun cleanupOwnsReplyMutexButCaptureStaysAvailableAndCancellationJoinsBeforeRetry() =
        runBlocking {
            withFixture { f, action, token ->
                val actual = f.koin.get<CollaborationNotificationInputRepository>()
                val entered = CompletableDeferred<Unit>()
                var pending = true
                var joined = false
                val blocked =
                    object : CollaborationNotificationInputRepository by actual {
                        override suspend fun remove(
                            expected: CollaborationNotificationInput
                        ): AppResult<Boolean> {
                            if (pending)
                                try {
                                    entered.complete(Unit)
                                    awaitCancellation()
                                } finally {
                                    joined = true
                                }
                            return actual.remove(expected)
                        }
                    }
                val flow = f.inputFlow(transfer = f.transferOwner(inputs = blocked))
                val reference = expect(flow.capture(token, "Keep both copies until cleanup"))
                val job = launch { flow.resumeProtected(reference) }
                select<Unit> {
                    entered.onAwait {}
                    job.onJoin { fail("Recovery returned before the cleanup barrier") }
                }
                val copied = f.replies().single()
                assertNotNull(f.protected(reference))
                assertNull(f.message(action.operationId))
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(flow.resume(copied.id)).failure.kind,
                )
                assertEquals(
                    FailureKind.Unavailable,
                    assertIs<AppResult.Failed>(flow.resumeProtected(reference)).failure.kind,
                )
                assertEquals(
                    reference,
                    expect(flow.capture(token, "Keep both copies until cleanup")),
                )
                job.cancelAndJoin()
                assertTrue(joined)
                assertNotNull(f.protected(reference))
                assertEquals(copied, f.replies().single())
                pending = false
                assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                    expect(flow.resumeProtected(reference))
                )
                assertNull(f.protected(reference))
                assertTrue(f.replies().isEmpty())
                assertNotNull(f.message(action.operationId))
                assertTrue(f.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun failedSchedulerCannotTurnDurableCaptureIntoLostInput() = runBlocking {
        withFixture { f, action, token ->
            var requests = 0
            val schedule =
                object : SyncScheduleRepository {
                    override suspend fun request(key: String): AppResult<Unit> {
                        requests++
                        assertEquals(CollaborationRecoveryWake.WORK_KEY, key)
                        return AppResult.Failed(
                            Failure(FailureKind.Unavailable, "Controlled scheduler failure")
                        )
                    }
                }
            val flow = f.inputFlow(recover = RequestCollaborationRecovery(f.koin.get(), schedule))
            val reference = expect(flow.capture(token, "Preserve despite failed enqueue"))
            assertEquals(1, requests)
            assertNotNull(expect(f.koin.get<CollaborationRecoveryWakeRepository>().pending()))
            assertNotNull(f.protected(reference))
            assertNull(f.message(action.operationId))
            f.reopen()
            f.withoutIncomingSelection()
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(f.koin.get<CollaborationNotificationReplies>().resumeProtected(reference))
            )
            assertNotNull(f.message(action.operationId))
        }
    }

    @Test
    fun uncertainCleanupDoesNotProcessInputUntilIndependentAccountRecovery() = runBlocking {
        withFixture { f, action, token ->
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
            val flow = f.inputFlow(transfer = f.transferOwner(inputs = lost))
            val reference = expect(flow.capture(token, "Process after uncertain cleanup"))
            assertIs<AppResult.Failed>(flow.resumeProtected(reference))
            assertNull(f.protected(reference))
            assertNull(f.message(action.operationId))
            val copy = f.replies().single()
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyResult.Absent),
                flow.resumeProtected(reference),
            )
            assertNull(f.message(action.operationId))
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(flow.resume(copy.id))
            )
            assertNotNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun captureAdmissionAndStorageFailureDoNotRequestWakeOrLeakCancelledSlots() = runBlocking {
        withFixture { f, _, token ->
            val actual = f.koin.get<CollaborationNotificationInputRepository>()
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            var pending = true
            val blocked =
                object : CollaborationNotificationInputRepository by actual {
                    override suspend fun retain(
                        reply: CollaborationUnverifiedNotificationReply
                    ): AppResult<CollaborationNotificationInputRetention> {
                        calls++
                        if (pending) {
                            if (calls == 8) entered.complete(Unit)
                            awaitCancellation()
                        }
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Controlled protection failure")
                        )
                    }
                }
            val flow = f.inputFlow(protect = ProtectCollaborationNotificationReply(blocked))
            val schedules = f.scheduled.size
            val jobs =
                (1..8).map { index -> launch { flow.capture(token, "Pending capture $index") } }
            withTimeout(2_000) { entered.await() }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(flow.capture(token, "Ninth capture")).failure.kind,
            )
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            pending = false
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(flow.capture(token, "Next capture")).failure.kind,
            )
            assertEquals(9, calls)
            assertEquals(schedules, f.scheduled.size)
            assertTrue(expect(actual.page()).isEmpty())
            assertTrue(f.replies().isEmpty())
        }
    }

    @Test
    fun captureWakeDeadlineJoinsSchedulerAndReturnsTheProtectedReference() = runBlocking {
        withFixture { f, action, token ->
            var joined = false
            val schedule =
                object : SyncScheduleRepository {
                    override suspend fun request(key: String): AppResult<Unit> =
                        try {
                            awaitCancellation()
                        } finally {
                            joined = true
                        }
                }
            val flow = f.inputFlow(recover = RequestCollaborationRecovery(f.koin.get(), schedule))
            val reference = expect(flow.capture(token, "Retained before slow scheduler"))
            assertTrue(joined)
            assertNotNull(f.protected(reference))
            assertNotNull(expect(f.koin.get<CollaborationRecoveryWakeRepository>().pending()))
            assertNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun uncertainCopyDoesNotCreateCommandAndReplayFinishesTheOriginalInput() = runBlocking {
        withFixture { f, action, token ->
            val actual = f.koin.get<CollaborationNotificationReplyRepository>()
            var uncertain = true
            val lost =
                object : CollaborationNotificationReplyRepository by actual {
                    override suspend fun retain(
                        access: CollaborationRecoveryAccess,
                        candidate: CollaborationNotificationReplyCandidate,
                    ): AppResult<CollaborationNotificationReplyRetention> {
                        val result = actual.retain(access, candidate)
                        return if (uncertain)
                            AppResult.Failed(
                                Failure(FailureKind.Storage, "Controlled lost copy response")
                            )
                        else result
                    }
                }
            val flow = f.inputFlow(transfer = f.transferOwner(replies = lost))
            val reference = expect(flow.capture(token, "Retained before lost copy response"))
            assertIs<AppResult.Failed>(flow.resumeProtected(reference))
            assertNotNull(f.protected(reference))
            assertEquals("Retained before lost copy response", f.replies().single().candidate.text)
            assertNull(f.message(action.operationId))
            uncertain = false
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(
                expect(flow.resumeProtected(reference))
            )
            assertNull(f.protected(reference))
            assertNotNull(f.message(action.operationId))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    private fun CollaborationWorkerFixture.inputFlow(
        protect: ProtectCollaborationNotificationReply = koin.get(),
        transfer: TransferCollaborationNotificationInput = koin.get(),
        recover: RequestCollaborationRecovery = koin.get(),
    ) =
        CollaborationNotificationReplies(
            koin.get(),
            koin.get(),
            koin.get(),
            koin.get(),
            koin.get(),
            recover,
            protect,
            transfer,
        )

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
