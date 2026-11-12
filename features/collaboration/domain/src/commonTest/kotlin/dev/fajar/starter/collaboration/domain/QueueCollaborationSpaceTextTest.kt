@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class QueueCollaborationSpaceTextTest {
    @Test
    fun offlineIntentKeepsExactContentAndSchedulesAfterReadbackWithoutCompanyOrRenewal() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.native.setup.now += 400.days
        f.native.setup.workspace = null
        f.native.setup.beforeWorkspace = { error("No selected-company dependency") }
        f.onSchedule = {
            assertEquals(2, f.reads)
            assertNotNull(f.stored)
            AppResult.Success(Unit)
        }
        val text = "  exact\ncontent  "
        val result =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                f.queue()(f.access, f.record().intent.copy(text = text, replyToEventId = "\$reply"))
            )
        assertEquals(text, result.value.intent.text)
        assertEquals("\$reply", result.value.intent.replyToEventId)
        assertEquals(f.native.setup.now, result.value.intent.createdAt)
        assertEquals(CollaborationMessageState.Queued, result.value.state)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.schedules)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun replayAfterReauthenticationPreservesOriginalTimestampAndDoesNotWriteAgain() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original = f.record().intent
        val first = f.queue()(f.access, original)
        f.native.setup.now += 100.days
        val rebound = f.access.copy(sessionId = "replacement")
        f.identity.value = AppResult.Success(f.native.session.copy(id = rebound.sessionId))
        f.canonical = null
        assertEquals(first, f.queue()(rebound, original))
        assertEquals(1, f.writes.size)
        assertEquals(1, f.canonicalReads)
        assertEquals(2, f.schedules.size)
    }

    @Test
    fun lostWriteResponseIsRecoveredByExactReadback() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.onInsert = {
            f.stored = CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            AppResult.Failed(f.base.storageFailure)
        }
        assertEquals(AppResult.Success(f.record()), f.queue()(f.access, f.record().intent))
        assertEquals(1, f.writes.size)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun missingReadbackPreservesClassifiedWriteFailureAndDoesNotSchedule() = runTest {
        for (result in
            listOf(
                AppResult.Failed(
                    Failure(
                        FailureKind.Conflict,
                        "Capacity",
                        code = "client.collaboration_space_recovery_capacity",
                    )
                ),
                AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED),
                AppResult.Success(CollaborationMessageInsertion.SCOPE_CHANGED),
                AppResult.Success(CollaborationMessageInsertion.STORED),
            )) {
            val f = SpaceMessageUseCaseFixture()
            f.onInsert = { result }
            val failed = assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent))
            if (result is AppResult.Failed) assertEquals(result, failed)
            else
                assertEquals(
                    when ((result as AppResult.Success).value) {
                        CollaborationMessageInsertion.CAPACITY_EXCEEDED -> FailureKind.Storage
                        CollaborationMessageInsertion.SCOPE_CHANGED -> FailureKind.Unauthorized
                        CollaborationMessageInsertion.STORED -> FailureKind.Unexpected
                    },
                    failed.failure.kind,
                )
            assertEquals(2, f.reads)
            assertTrue(f.schedules.isEmpty())
        }
    }

    @Test
    fun failedReadbackNeverConfirmsOrSchedulesAnUnknownOutcome() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.onInsert = {
            f.stored = CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            f.readFailure = f.base.storageFailure
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
        assertEquals(
            AppResult.Failed(f.base.storageFailure),
            f.queue()(f.access, f.record().intent),
        )
        assertNotNull(f.stored)
        assertTrue(f.schedules.isEmpty())
    }

    @Test
    fun immutableIdConflictCannotChangeContentOrTriggerWork() = runTest {
        for (change in 0..3) {
            val f = SpaceMessageUseCaseFixture()
            val old = f.record()
            f.stored =
                old.copy(
                    intent =
                        when (change) {
                            0 -> old.intent.copy(text = "another message")
                            1 -> old.intent.copy(conversationId = f.access.accountId)
                            2 -> old.intent.copy(replyToEventId = "\$different")
                            else -> old.intent.copy(forwarded = true)
                        }
                )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent)).failure.kind,
            )
            assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        }
    }

    @Test
    fun cancelledOriginalReplayDoesNotResurrectWork() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.stored = f.record().copy(state = CollaborationMessageState.Cancelled)
        assertEquals(
            AppResult.Success(assertNotNull(f.stored)),
            f.queue()(f.access, f.record().intent),
        )
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
    }

    @Test
    fun invalidInputAndUnavailableCanonicalConversationNeverInsert() = runTest {
        val f = SpaceMessageUseCaseFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent.copy(id = "invalid")))
                .failure
                .kind,
        )
        assertEquals(0, f.reads)
        for (canonical in
            listOf(
                null,
                f.canonical!!.copy(spaceId = f.access.accountId),
                f.canonical!!.copy(id = f.access.accountId),
            )) {
            f.canonical = canonical
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent)).failure.kind,
            )
        }
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
    }

    @Test
    fun foreignLocalOwnerAndPriorDenialNeverReadMessageContent() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceMessageUseCaseFixture()
            if (denied) f.base.deny() else f.identity.value = AppResult.Success(null)
            val result = assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent))
            if (denied) assertEquals(f.base.denied, result.failure)
            assertEquals(0, f.reads)
            assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
        }
    }

    @Test
    fun accountReplacementDuringAdmissionCannotReadPrivateMessages() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.base.beforeAdmission = { f.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent)).failure.kind,
        )
        assertEquals(0, f.reads)
    }

    @Test
    fun lateDenialOrAccountChangeAfterWritePreservesIntentWithoutPublishingOrScheduling() =
        runTest {
            for (deny in listOf(false, true)) {
                val f = SpaceMessageUseCaseFixture()
                f.onInsert = {
                    f.stored =
                        CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
                    if (deny) f.base.deny() else f.identity.value = AppResult.Success(null)
                    AppResult.Failed(f.base.storageFailure)
                }
                val result = assertIs<AppResult.Failed>(f.queue()(f.access, f.record().intent))
                assertEquals(
                    if (deny) FailureKind.AccessDenied else FailureKind.Unauthorized,
                    result.failure.kind,
                )
                assertNotNull(f.stored)
                assertTrue(f.schedules.isEmpty())
            }
        }

    @Test
    fun schedulingFailureAndTimeoutKeepTheLocallyConfirmedMessage() = runTest {
        for (timeout in listOf(false, true)) {
            val f = SpaceMessageUseCaseFixture()
            f.onSchedule = {
                if (timeout) awaitCancellation() else AppResult.Failed(f.base.unavailable)
            }
            val start = testScheduler.currentTime
            assertIs<AppResult.Success<*>>(f.queue()(f.access, f.record().intent))
            assertEquals(if (timeout) 1500L else 0L, testScheduler.currentTime - start)
            assertEquals(1, f.schedules.size)
            assertNotNull(f.stored)
        }
    }

    @Test
    fun cancellationAfterCommitPropagatesAndRetryReusesTheExactOriginal() = runTest {
        for (duringSchedule in listOf(false, true)) {
            val f = SpaceMessageUseCaseFixture()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            suspend fun wait(): Nothing {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            if (duringSchedule) f.onSchedule = { wait() }
            else
                f.onInsert = {
                    f.stored =
                        CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
                    wait()
                }
            val job = async { f.queue()(f.access, f.record().intent) }
            entered.await()
            job.cancelAndJoin()
            stopped.await()
            assertFailsWith<CancellationException> { job.await() }
            f.onSchedule = { AppResult.Success(Unit) }
            assertEquals(
                AppResult.Success(assertNotNull(f.stored)),
                f.queue()(f.access, f.record().intent),
            )
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun lateDenialDuringScheduleCannotPublishSuccess() = runTest {
        val f = SpaceMessageUseCaseFixture()
        f.onSchedule = {
            f.base.deny()
            AppResult.Success(Unit)
        }
        assertEquals(AppResult.Failed(f.base.denied), f.queue()(f.access, f.record().intent))
        assertNotNull(f.stored)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun cancellationBeforeEntryDoesNoRepositoryWork() = runTest {
        val f = SpaceMessageUseCaseFixture()
        var invoked = false
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                invoked = true
                assertFailsWith<CancellationException> { f.queue()(f.access, f.record().intent) }
            }
        job.join()
        assertTrue(invoked)
        assertEquals(0, f.reads)
        assertTrue(f.writes.isEmpty() && f.schedules.isEmpty())
    }
}
