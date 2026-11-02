package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.app.work.CollaborationNotificationReplyRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.fixtures.NativeUiServer
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Actual application bindings and encrypted SQLite; fictional HTTP and simulated Matrix SDK. */
class CollaborationNotificationReplyRecoveryWorkerTest {
    @Test
    fun healthyPagesReopenWithoutSelectionAndRetainEachImmutableCommandOnce() = runBlocking {
        withFixture { f, target ->
            val inputs = (1..6).map { f.retain(target, "Retained reply $it") }
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertEquals(inputs.drop(4), f.inputs())
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertTrue(f.inputs().isEmpty())
            for (input in inputs) assertEquals(input.candidate.text, f.message(input)?.intent?.text)
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
        }
    }

    @Test
    fun earlyFailureSurvivesAHealthyLastPageAndFreshWorkerBeforeRetryingFromMinimum() =
        runBlocking {
            withFixture { f, target ->
                val inputs = (1..6).map { f.retain(target, "Across pages $it") }
                val actual = f.koin.get<CollaborationNotificationActionRepository>()
                val worker =
                    f.workerWithOpen { parent, token ->
                        if (token == inputs.first().candidate.token)
                            AppResult.Failed(
                                Failure(FailureKind.Storage, "Temporary local read failure")
                            )
                        else actual.open(parent, token)
                    }
                assertEquals(SyncResult.Retry(), worker(f.parent()))
                assertTrue(f.cursor()!!.cycle!!.retryRequired)
                f.reopen()
                f.withoutIncomingSelection()
                assertNotNull(
                    assertIs<SyncResult.Retry>(
                            f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent())
                        )
                        .failure
                )
                assertEquals(listOf(inputs.first()), f.inputs())
                assertTrue(f.cursor()!!.cycle!!.finished)
                val appended = f.retain(target, "New tail after failed cycle")
                f.reopen()
                f.withoutIncomingSelection()
                assertEquals(
                    SyncResult.Complete,
                    f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
                )
                assertTrue(f.inputs().isEmpty())
                for (input in inputs + appended) assertEquals(
                    input.candidate.text,
                    f.message(input)?.intent?.text,
                )
                assertTrue(f.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun deniedFirstCompanyDoesNotHideTheHealthyCompanyOrCauseAnImmediateNewCycle() = runBlocking {
        withFixture { f, target ->
            val deniedTarget = target.copy(companyId = NativeUiServer.COMPANY_TWO)
            val denied = (1..4).map { f.retain(deniedTarget, "Retained denied reply $it") }
            val healthy = f.retain(target, "Other company is admitted")
            assertEquals(
                AppResult.Success(true),
                f.koin
                    .get<CollaborationListRepository>()
                    .restrict(
                        f.access().copy(companyId = NativeUiServer.COMPANY_TWO),
                        Failure(
                            FailureKind.AccessDenied,
                            "Fictional retained company denial",
                            code = "collaboration_access_denied",
                        ),
                    ),
            )
            assertEquals(
                SyncResult.Retry(),
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertEquals(denied, f.inputs())
            assertTrue(f.inputs().all { it.review == null })
            assertEquals(healthy.candidate.text, f.message(healthy)?.intent?.text)
            assertTrue(f.cursor()!!.cycle!!.finished)
            assertFalse(f.cursor()!!.cycle!!.retryRequired)
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun interruptedLastAttemptIsNotAcknowledgedByAHealthyNewTail() = runBlocking {
        withFixture { f, target ->
            val old = f.retain(target, "Before interruption")
            val begun =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot?>>(
                        f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                    )
                    .value!!
            val work =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                        f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                    )
                    .value!!
            assertIs<AppResult.Success<*>>(
                f.koin.get<AdvanceCollaborationNotificationReplyRecovery>()(
                    work,
                    work.entries.single(),
                    begun.cursor,
                )
            )
            f.reopen()
            f.withoutIncomingSelection()
            val new = f.retain(target, "After interruption")
            assertNotNull(
                assertIs<SyncResult.Retry>(
                        f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent())
                    )
                    .failure
            )
            assertEquals(listOf(old), f.inputs())
            assertNotNull(f.message(new))
            assertNull(f.message(old))
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertTrue(f.inputs().isEmpty())
            assertNotNull(f.message(old))
        }
    }

    @Test
    fun oneSlowInputIsCancelledAndJoinedBeforeTheNextInputGetsItsTurn() = runBlocking {
        withFixture { f, target ->
            val slow = f.retain(target, "Slow input remains")
            val healthy = f.retain(target, "Healthy later input")
            var joined = false
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val worker =
                f.workerWithOpen { parent, token ->
                    if (token == slow.candidate.token) {
                        try {
                            awaitCancellation()
                        } finally {
                            joined = true
                        }
                    } else {
                        assertTrue(joined)
                        actual.open(parent, token)
                    }
                }
            assertNotNull(assertIs<SyncResult.Retry>(worker(f.parent())).failure)
            assertTrue(joined)
            assertEquals(listOf(slow), f.inputs())
            assertNotNull(f.message(healthy))
            assertTrue(f.cursor()!!.cycle!!.retryRequired)
            assertTrue(f.cursor()!!.cycle!!.finished)
        }
    }

    @Test
    fun cancellationRetainsInFlightAndConcurrentRecoveryDoesNotOpenAnotherTurn() = runBlocking {
        withFixture { f, target ->
            val input = f.retain(target, "Cancelled retained input")
            val entered = CompletableDeferred<Unit>()
            var joined = false
            val worker =
                f.workerWithOpen { _, _ ->
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
            val job = async { worker(f.parent()) }
            entered.await()
            val cursor = f.cursor()
            assertNotNull(assertIs<SyncResult.Retry>(worker(f.parent())).failure)
            assertEquals(cursor, f.cursor())
            job.cancelAndJoin()
            assertTrue(joined)
            assertTrue(f.cursor()!!.cycle!!.inFlight)
            assertEquals(listOf(input), f.inputs())
            f.reopen()
            f.withoutIncomingSelection()
            assertNotNull(
                assertIs<SyncResult.Retry>(
                        f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent())
                    )
                    .failure
            )
            assertEquals(listOf(input), f.inputs())
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(f.parent()),
            )
            assertTrue(f.inputs().isEmpty())
        }
    }

    @Test
    fun staleCallerCannotAdvanceTheCurrentAccountsCycle() = runBlocking {
        withFixture { f, target ->
            val input = f.retain(target, "Keep under correct owner")
            assertIs<SyncResult.Blocked>(
                f.koin.get<CollaborationNotificationReplyRecoveryWorker>()(
                    f.parent().copy(nativeSessionId = id())
                )
            )
            assertNull(f.cursor())
            assertEquals(listOf(input), f.inputs())
            assertNull(f.message(input))
        }
    }

    private fun CollaborationWorkerFixture.workerWithOpen(
        onOpen:
            suspend (CollaborationNativeParent, CollaborationNotificationActionToken) -> AppResult<
                    CollaborationNotificationAction
                >
    ): CollaborationNotificationReplyRecoveryWorker {
        val actual = koin.get<CollaborationNotificationActionRepository>()
        val actions =
            object : CollaborationNotificationActionRepository by actual {
                override suspend fun open(
                    parent: CollaborationNativeParent,
                    token: CollaborationNotificationActionToken,
                ) = onOpen(parent, token)
            }
        val commands =
            CollaborationNotificationCommands(
                OpenCollaborationNotificationAction(
                    actions,
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    koin.get(),
                    Clock.System,
                ),
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
                commands,
                koin.get(),
            )
        return CollaborationNotificationReplyRecoveryWorker(
            koin.get(),
            koin.get(),
            koin.get(),
            koin.get(),
            koin.get(),
            replies,
        )
    }

    private fun CollaborationWorkerFixture.parent() =
        CollaborationNativeParent(
            session.id,
            session.binding.audience,
            session.user.id,
            session.binding.serverId,
        )

    private fun CollaborationWorkerFixture.access() = incomingGrant().owner.localListAccess()

    private fun CollaborationWorkerFixture.recoveryAccess() =
        CollaborationRecoveryAccess(session.id, session.binding.audience, session.user.id)

    private suspend fun CollaborationWorkerFixture.cursor() =
        assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryCursor?>>(
                koin
                    .get<CollaborationNotificationReplyRecoveryCursorRepository>()
                    .current(recoveryAccess())
            )
            .value

    private suspend fun CollaborationWorkerFixture.inputs() =
        assertIs<AppResult.Success<CollaborationNotificationReplyPage>>(
                koin
                    .get<CollaborationNotificationReplyRepository>()
                    .page(recoveryAccess(), pendingOnly = false)
            )
            .value
            .inputs

    private suspend fun CollaborationWorkerFixture.message(
        input: CollaborationNotificationReplyInput
    ) =
        assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                koin
                    .get<CollaborationMessageRepository>()
                    .get(access(), input.candidate.action.operationId)
            )
            .value

    private suspend fun CollaborationWorkerFixture.retain(
        target: CollaborationNotificationActionTarget,
        text: String,
    ): CollaborationNotificationReplyInput {
        val token =
            assertIs<AppResult.Success<CollaborationNotificationActionToken>>(
                    koin
                        .get<CollaborationNotificationActionRepository>()
                        .seal(parent(), CollaborationNotificationAction.Reply(id(), target))
                )
                .value
        return assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                koin.get<RetainCollaborationNotificationReply>()(token, text)
            )
            .value
    }

    private suspend fun withFixture(
        block: suspend (CollaborationWorkerFixture, CollaborationNotificationActionTarget) -> Unit
    ) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val event = f.sdk.incoming(f.first, "Fictional reply context")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            block(
                f,
                CollaborationNotificationActionTarget(
                    f.access().companyId,
                    f.first,
                    f.server.collaboration.roomId(f.first),
                    event.eventId,
                    id(),
                    id(),
                ),
            )
        } finally {
            f.close()
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
