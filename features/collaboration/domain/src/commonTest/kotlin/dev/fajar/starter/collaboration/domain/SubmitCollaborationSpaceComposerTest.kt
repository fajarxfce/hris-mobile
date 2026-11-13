@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SubmitCollaborationSpaceComposerTest {
    @Test
    fun offlineSubmissionRetainsTheCapturedGestureAndAdvancesOnlyItsEditor() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected company") }
        f.base.beforeRead = { f.base.native.setup.now += 100.days }
        f.base.beforeCanonical = { f.base.native.setup.now += 100.days }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(f.submission, f.retained[f.submission.id])
        assertEquals(f.submission.nextDraft, f.editor)
        assertEquals(listOf(f.submission), f.handoffs)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.base.schedules)
        assertTrue(f.base.writes.isEmpty())
    }

    @Test
    fun exactReplayAfterLoginRenewalPreservesNewerTypingWithoutMetadata() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val submit = f.submit()
        assertEquals(AppResult.Success(f.submission), submit(f.access, null, f.submission))
        val newer = f.submission.nextDraft.copy(text = "Next message")
        f.editor = newer
        f.base.canonical = null
        val renewed = f.access.copy(sessionId = "renewed")
        f.base.identity.value =
            AppResult.Success(f.base.native.session.copy(id = renewed.sessionId))
        assertEquals(AppResult.Success(f.submission), submit(renewed, null, f.submission))
        assertEquals(newer, f.editor)
        assertEquals(1, f.handoffs.size)
        assertEquals(1, f.base.canonicalReads)
    }

    @Test
    fun lostHandoffReplyConfirmsTheOriginalWithoutAnotherCommit() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.handoffFailure = f.base.base.storageFailure
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(listOf(f.submission), f.handoffs)
        assertEquals(f.submission.nextDraft, f.editor)
        assertEquals(1, f.base.schedules.size)
    }

    @Test
    fun concurrentMaterializationCanConfirmATerminalCommandWithoutAnotherWake() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val newer = f.submission.nextDraft.copy(text = "Preserve this draft")
        f.beforeHandoff = {
            f.base.stored = f.record(CollaborationMessageState.Cancelled)
            f.editor = newer
        }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(newer, f.editor)
        assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
    }

    @Test
    fun changedTimestampInEitherRetainedLedgerNeverConfirmsTheGesture() = runTest {
        for (command in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            if (command)
                f.base.stored =
                    f.record().let {
                        it.copy(
                            intent =
                                it.intent.copy(createdAt = it.intent.createdAt + 1.milliseconds)
                        )
                    }
            else
                f.retained[f.submission.id] =
                    f.submission.copy(submittedAt = f.submission.submittedAt + 1.milliseconds)
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
            )
            assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
            assertEquals(0, f.base.canonicalReads)
        }
    }

    @Test
    fun unprovenCommitFailureKeepsItsClassificationAndDoesNotSchedule() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.handoffFailureBeforeCommit = f.base.base.storageFailure
        assertEquals(
            AppResult.Failed(f.base.base.storageFailure),
            f.submit()(f.access, null, f.submission),
        )
        assertTrue(f.handoffs.isEmpty() && f.retained.isEmpty() && f.base.schedules.isEmpty())
        assertNull(f.editor)
    }

    @Test
    fun fullOutboxPreservesInputWithoutScheduling() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.editor = f.original
        f.forcedHandoff = CollaborationComposerHandoffResult.CAPACITY_EXCEEDED
        val failure =
            assertIs<AppResult.Failed>(f.submit()(f.access, f.original, f.submission)).failure
        assertEquals("client.command_queue_full", failure.code)
        assertEquals(f.original, f.editor)
        assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
    }

    @Test
    fun priorDenialAndAccountReplacementDuringAdmissionCannotReadPrivateContent() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            if (denied) f.base.base.deny()
            else f.base.base.beforeAdmission = { f.base.identity.value = AppResult.Success(null) }
            val failure =
                assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                failure.kind,
            )
            assertEquals(0, f.base.reads + f.outboxReads)
            assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
        }
    }

    @Test
    fun lateDenialOrSignOutRetainsCommittedSubmissionWithoutPublishingOrScheduling() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            f.afterHandoff = {
                if (denied) f.base.base.deny() else f.base.identity.value = AppResult.Success(null)
            }
            val failure =
                assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                failure.kind,
            )
            assertEquals(f.submission, f.retained[f.submission.id])
            assertEquals(f.submission.nextDraft, f.editor)
            assertTrue(f.base.schedules.isEmpty())
        }
    }

    @Test
    fun absentOrForeignCanonicalConversationCannotCommitANewSend() = runTest {
        for (variant in 0..2) {
            val f = SpaceComposerUseCaseFixture()
            f.base.canonical =
                when (variant) {
                    0 -> null
                    1 -> f.base.canonical!!.copy(spaceId = f.access.accountId)
                    else -> f.base.canonical!!.copy(id = f.access.accountId)
                }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
            )
            assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
        }
    }

    @Test
    fun failedOrTimedOutSchedulerHintCannotUndoTheCommittedOriginal() = runTest {
        for (timeout in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            f.base.onSchedule = {
                if (timeout) awaitCancellation() else AppResult.Failed(f.base.base.storageFailure)
            }
            val started = testScheduler.currentTime
            assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
            assertEquals(if (timeout) 1500L else 0L, testScheduler.currentTime - started)
            assertEquals(f.submission, f.retained[f.submission.id])
            assertEquals(1, f.handoffs.size)
        }
    }

    @Test
    fun accountLossDuringTheHintCannotPublishAnOldAccountsSubmission() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.base.onSchedule = {
            f.base.identity.value = AppResult.Success(null)
            AppResult.Success(Unit)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(f.submission, f.retained[f.submission.id])
    }

    @Test
    fun cancellationAfterCommitPropagatesAndRetryUsesTheSameOriginal() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val committed = CompletableDeferred<Unit>()
        f.afterHandoff = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.submit()(f.access, null, f.submission) }
        committed.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(f.submission, f.retained[f.submission.id])
        assertTrue(f.base.schedules.isEmpty())
        f.afterHandoff = {}
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(1, f.handoffs.size)
    }

    @Test
    fun malformedInputAndMalformedRetainedCommandCannotBeConfirmed() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val invalid = f.submission.copy(original = f.original.copy(text = " "))
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, invalid)).failure.kind,
        )
        assertEquals(0, f.base.reads)
        f.base.stored = f.record().copy(revision = -1)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertTrue(f.handoffs.isEmpty() && f.base.schedules.isEmpty())
    }
}
