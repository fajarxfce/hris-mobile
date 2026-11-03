package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.app.work.CollaborationNotificationInputRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

class CollaborationNotificationInputRecoveryWorkerTest {
    @Test
    fun fullQueueDoesNotStarveLaterInputAcrossReopenAndRetriesTheFailedPrefix() = runBlocking {
        withFixture { f, target ->
            val commands =
                (1..64).associate { index -> f.capture(target, "Finite captured input $index") }
            val blocked = commands.keys.sortedBy { it.id }.take(2).toSet()
            repeat(32) { turn ->
                val actual = f.koin.get<CollaborationNotificationInputRepository>()
                val inputs =
                    object : CollaborationNotificationInputRepository by actual {
                        override suspend fun get(
                            reference: CollaborationNotificationInputReference
                        ): AppResult<CollaborationNotificationInput?> =
                            if (reference in blocked)
                                AppResult.Failed(
                                    Failure(FailureKind.Storage, "Controlled first-input failure")
                                )
                            else actual.get(reference)
                    }
                val result = assertIs<SyncResult.Retry>(f.inputWorker(inputs)(f.parent()))
                if (turn < 31) assertNull(result.failure) else assertNotNull(result.failure)
                val cursor = f.cursor()
                assertEquals((turn + 1) * 2, cursor.state.nextIndex)
                assertTrue(cursor.state.retryRequired)
                f.reopen()
                f.withoutIncomingSelection()
            }
            val retained =
                value(f.koin.get<CollaborationNotificationInputRepository>().page()).toSet()
            assertEquals(blocked, retained)
            for ((reference, operationId) in commands) {
                if (reference in blocked) assertNull(f.message(operationId))
                else assertNotNull(f.message(operationId))
            }
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            assertTrue(
                value(f.koin.get<CollaborationNotificationInputRepository>().page()).isEmpty()
            )
            for (operationId in commands.values) assertNotNull(f.message(operationId))
            assertTrue(f.cursor().state.finished)
            assertFalse(f.cursor().state.retryRequired)
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun cancellationAfterAttemptReopensAtLaterInputAndRetainsInterruptedRetry() = runBlocking {
        withFixture { f, target ->
            val commands = (1..3).associate { f.capture(target, "Interrupted captured input $it") }
            val first = commands.keys.minBy { it.id }
            val actual = f.koin.get<CollaborationNotificationInputRepository>()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            val blocked =
                object : CollaborationNotificationInputRepository by actual {
                    override suspend fun get(
                        reference: CollaborationNotificationInputReference
                    ): AppResult<CollaborationNotificationInput?> {
                        if (reference == first)
                            try {
                                entered.complete(Unit)
                                awaitCancellation()
                            } finally {
                                joined = true
                            }
                        return actual.get(reference)
                    }
                }
            val job = launch { f.inputWorker(blocked)(f.parent()) }
            select<Unit> {
                entered.onAwait {}
                job.onJoin { fail("Worker returned before pending input read") }
            }
            job.cancelAndJoin()
            assertTrue(joined)
            assertEquals(1, f.cursor().state.nextIndex)
            assertTrue(f.cursor().state.inFlight)
            f.reopen()
            f.withoutIncomingSelection()
            val resumed =
                assertIs<SyncResult.Retry>(
                    f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent())
                )
            assertNotNull(resumed.failure)
            assertTrue(f.cursor().state.retryRequired)
            assertNull(f.message(commands.getValue(first)))
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            commands.values.forEach { assertNotNull(f.message(it)) }
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun newArrivalDuringSnapshotSchedulesAnotherPassWithoutReplayingOldCommands() = runBlocking {
        withFixture { f, target ->
            val commands = (1..3).associate { f.capture(target, "Original captured input $it") }
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            val new = f.capture(target, "New captured input")
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            assertTrue(f.cursor().state.continuationRequired)
            assertNull(f.message(new.second))
            val original = commands.values.map { assertNotNull(f.message(it)) }
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            assertEquals(original, commands.values.map { assertNotNull(f.message(it)) })
            assertNotNull(f.message(new.second))
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun signedOutCaptureWaitsForActualAccountAndOldFamilyBecomesReview() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token =
                value(
                    f.koin.get<CollaborationNotificationActionRepository>().seal(f.parent(), action)
                )
            val old = f.session
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(old, null),
            )
            val reference =
                value(
                    f.koin
                        .get<CollaborationNotificationReplies>()
                        .capture(token, "Captured before account restoration")
                )
            assertIs<SyncResult.Retry>(
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent())
            )
            f.reopen()
            val next = old.copy(id = id())
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(null, next),
            )
            f.session = next
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationInputRecoveryWorker>()(f.parent()),
            )
            assertNull(value(f.koin.get<CollaborationNotificationInputRepository>().get(reference)))
            val retained =
                value(
                        f.koin
                            .get<CollaborationNotificationReplyRepository>()
                            .page(f.access(), pendingOnly = false)
                    )
                    .inputs
                    .single()
            assertEquals(CollaborationNotificationReplyReview.SessionChanged, retained.review)
            assertEquals("Captured before account restoration", retained.candidate.text)
            assertNull(f.message(action.operationId))
        }
    }

    private fun CollaborationWorkerFixture.inputWorker(
        inputs: CollaborationNotificationInputRepository
    ): CollaborationNotificationInputRecoveryWorker {
        val transfer =
            TransferCollaborationNotificationInput(
                inputs,
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
            )
        val replies =
            CollaborationNotificationReplies(
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
                transfer,
            )
        return CollaborationNotificationInputRecoveryWorker(koin.get(), koin.get(), replies)
    }

    private suspend fun CollaborationWorkerFixture.capture(
        target: CollaborationNotificationActionTarget,
        text: String,
    ): Pair<CollaborationNotificationInputReference, String> {
        val action = CollaborationNotificationAction.Reply(id(), target)
        val token =
            value(koin.get<CollaborationNotificationActionRepository>().seal(parent(), action))
        return value(koin.get<CollaborationNotificationReplies>().capture(token, text)) to
            action.operationId
    }

    private fun CollaborationWorkerFixture.parent() =
        CollaborationNativeParent(
            session.id,
            session.binding.audience,
            session.user.id,
            session.binding.serverId,
        )

    private fun CollaborationWorkerFixture.access() =
        CollaborationRecoveryAccess(session.id, session.binding.audience, session.user.id)

    private suspend fun CollaborationWorkerFixture.cursor() =
        assertNotNull(
            value(koin.get<CollaborationNotificationInputRecoveryRepository>().current(access()))
        )

    private suspend fun CollaborationWorkerFixture.message(id: String) =
        value(
            koin
                .get<CollaborationMessageRepository>()
                .get(incomingGrant().owner.localListAccess(), id)
        )

    private fun <T> value(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun id() = UUID.randomUUID().toString()

    private suspend fun withFixture(
        block: suspend (CollaborationWorkerFixture, CollaborationNotificationActionTarget) -> Unit
    ) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val original = f.sdk.incoming(f.first, "Fictional recovery original")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            block(
                f,
                CollaborationNotificationActionTarget(
                    f.incomingGrant().owner.companyId,
                    f.first,
                    f.server.collaboration.roomId(f.first),
                    original.eventId,
                    id(),
                    id(),
                ),
            )
        } finally {
            f.close()
        }
    }
}
