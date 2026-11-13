@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.RecoveryWorkerFixture
import dev.fajar.starter.fixtures.SpaceComposerCompletionOutboxFixture
import dev.fajar.starter.fixtures.SpaceRecoveryLocalStorageFixture
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*

class CollaborationSpaceRecoveryWorkerTest {
    @Test
    fun locallyIdleRoomCompletesItsClaimWithoutDeliveryTransport() = runTest {
        val f = Fixture(testScheduler)
        f.needsDelivery = false
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNotNull(f.wake)
        assertNotNull(f.progress?.sweep)
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNull(f.wake)
        assertNull(f.progress?.sweep)
    }

    @Test
    fun failedDemandRetainsWakeAndBacksOffWithoutDelivery() = runTest {
        val f = Fixture(testScheduler)
        f.demandFailure = Failure(FailureKind.Storage, "fixture")
        assertIs<SyncResult.Retry>(f.worker())
        assertTrue(f.delivered.isEmpty())
        assertNotNull(f.wake)
        assertTrue(f.progress!!.sweep!!.retryRequired)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun cancelledDemandRetainsClaimBeforeAnyDelivery() = runTest {
        val f = Fixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        f.beforeDemand = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.worker() }
        entered.await()
        job.cancelAndJoin()
        assertNotNull(f.progress!!.sweep!!.pending)
        assertNotNull(f.wake)
        assertTrue(f.delivered.isEmpty())
    }

    @Test
    fun fourRawPagesPerTurnRetainTheSeekAndLateDuplicateRequestsDoNoWork() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.delivered.size)
        assertEquals(raw(4), f.progress!!.sweep!!.afterId)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(4, f.delivered.size)
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
        assertEquals(2, f.adoptions)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.adoptions)
    }

    @Test
    fun newerWakeAtAcknowledgmentSurvivesTheCompletedSweep() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val old = f.wake
        f.beforeAck = { f.wake = CollaborationSpaceRecoveryWake(id(999)) }
        assertEquals(SyncResult.Complete, f.worker())
        assertNotNull(f.wake)
        assertNotEquals(old, f.wake)
        assertNull(f.progress!!.sweep)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
    }

    @Test
    fun oneProcessorOneWaiterAndNoThirdCapturedNativeWork() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeAdopt = {
            entered.complete(Unit)
            release.await()
        }
        val first = async { f.worker() }
        entered.await()
        val second = async { f.worker() }
        runCurrent()
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.adoptions)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.adoptions)
    }

    @Test
    fun cancellationAfterClaimKeepsTheExactRoomForTheNextAttempt() = runTest {
        val f = Fixture(testScheduler, count = 2)
        val entered = CompletableDeferred<Unit>()
        f.beforeDelivery = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.worker() }
        entered.await()
        job.cancelAndJoin()
        val retained = f.progress!!.sweep!!.pending!!.conversationId
        assertNotNull(retained)
        assertNotNull(f.wake)
        f.beforeDelivery = {}
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(retained, retained), f.delivered)
        assertNull(f.wake)
    }

    @Test
    fun changedPublishedParentCancelsInFlightAdoptionWithoutAcknowledgment() = runTest {
        val f = Fixture(testScheduler)
        val entered = CompletableDeferred<Unit>()
        var released = false
        f.beforeAdopt = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        val result = async { f.worker() }
        entered.await()
        f.base.states.value = f.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(result.await())
        assertTrue(released)
        assertNotNull(f.wake)
        assertNull(f.progress)
    }

    @Test
    fun unresolvedNativeReportBacksOffWithoutAnImmediateContinuationLoop() = runTest {
        val f = Fixture(testScheduler)
        f.report =
            CollaborationDeliveryReport(
                emptySet(),
                setOf(id(80)),
                CollaborationQueueDeliveryReason.STOPPED,
            )
        val result = assertIs<SyncResult.Retry>(f.worker())
        assertEquals(30L, result.failure!!.retryAfterSeconds)
        assertTrue(f.progress!!.sweep!!.retryRequired)
        assertTrue(f.scheduled.isEmpty())
        assertEquals(1, f.delivered.size)
        assertNotNull(f.wake)
    }

    @Test
    fun failedContinuationSchedulingKeepsTheCommittedSeekAndWake() = runTest {
        val f = Fixture(testScheduler)
        f.scheduleFailure = Failure(FailureKind.Unavailable, "Fixture scheduler")
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(raw(4), f.progress!!.sweep!!.afterId)
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
    }

    @Test
    fun absentOriginalRootCompletesWithoutAnyAdoptionSyncOrRoomWork() = runTest {
        val f = Fixture(testScheduler)
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(0, f.adoptions)
        assertEquals(0, f.syncs)
        assertTrue(f.delivered.isEmpty())
        assertNull(f.wake)
    }

    @Test
    fun requestedDeliveryCannotCreateAWakeButScheduledDiscoveryCan() = runTest {
        val f = Fixture(testScheduler)
        f.wake = null
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(0, f.deviceReads)
        assertEquals(SyncResult.Complete, f.worker.invoke(SyncTrigger.Scheduled))
        assertEquals(1, f.wakeWrites)
        assertEquals(1, f.deviceReads)
    }

    @Test
    fun unsupportedHostCannotAcknowledgeNativeWork() = runTest {
        val f = Fixture(testScheduler)
        assertEquals(SyncResult.Complete, f.createWorker(AppPlatform.Desktop)())
        assertNotNull(f.wake)
        assertEquals(0, f.deviceReads)
    }

    @Test
    fun confirmedDenialRetainsWakeWithoutWritingOrLooping() = runTest {
        val f = Fixture(testScheduler)
        f.syncFailure = Failure(FailureKind.AccessDenied, "Fixture denied")
        assertIs<SyncResult.Blocked>(f.worker())
        assertNull(f.progress)
        assertNotNull(f.wake)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun wholeTurnTimeoutCancelsLocalWorkAndReleasesTheProcessor() = runTest {
        val f = Fixture(testScheduler)
        var released = false
        f.beforeDevice = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<SyncResult.Retry>(f.worker()).failure!!.kind)
        assertTrue(released)
        assertEquals(90_000L, testScheduler.currentTime)
        f.beforeDevice = {}
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
    }

    @Test
    fun pendingMessageWithoutBindingPreparesStagesAndSendsItsOriginal() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0)
        assertTrue(f.retained.isEmpty())
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(original.intent.conversationId), f.preparedMessages)
        assertEquals(listOf(original.intent.id), f.stagedMessages)
        assertIs<CollaborationMessageState.Sent>(f.local.records.getValue(original.intent.id).state)
        assertNull(f.wake)
    }

    @Test
    fun mappedOriginalRecoversWithoutAnotherStagingAttempt() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0, staged = true)
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.stagedMessages.isEmpty())
        val sent =
            f.local.records.getValue(original.intent.id).state as CollaborationMessageState.Sent
        assertEquals(
            (original.state as CollaborationMessageState.Staged).transactionId,
            sent.transactionId,
        )
    }

    @Test
    fun unreadyFirstRecipientDoesNotStarveTheSecondAcrossBackoff() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val first = f.pending(0)
        val second = f.pending(1)
        f.unready += first.intent.conversationId
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(first.intent.id, f.progress!!.sweep!!.messages!!.after!!.messageId)
        assertIs<SyncResult.Retry>(f.worker())
        assertIs<CollaborationMessageState.Sent>(f.local.records.getValue(second.intent.id).state)
        assertEquals(
            CollaborationMessageState.Queued,
            f.local.records.getValue(first.intent.id).state,
        )
        assertTrue(f.scheduled.isEmpty())
        f.unready.clear()
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(first.intent.id, second.intent.id, first.intent.id), f.stagedMessages)
        assertNull(f.wake)
    }

    @Test
    fun interruptionAfterStagingRetainsClaimAndOriginalTransactionForNextProcessor() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0)
        val entered = CompletableDeferred<Unit>()
        f.beforeDelivery = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch { f.worker() }
        entered.await()
        job.cancelAndJoin()
        val staged = f.local.records.getValue(original.intent.id)
        val claim = f.progress!!.sweep!!.messages!!.pending!!
        assertEquals(original.intent.id, claim.position.messageId)
        val originalState = assertIs<CollaborationMessageState.Staged>(staged.state)
        f.beforeDelivery = {}
        assertEquals(SyncResult.Complete, f.createWorker()())
        val sent =
            f.local.records.getValue(original.intent.id).state as CollaborationMessageState.Sent
        assertEquals(originalState.transactionId, sent.transactionId)
        assertEquals(listOf(original.intent.id), f.stagedMessages)
    }

    @Test
    fun fourPendingAttemptsPerTurnPreserveTheFifthOriginal() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val originals = List(5) { f.pending(it) }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(originals.take(4).map { it.intent.id }, f.stagedMessages)
        assertEquals(
            CollaborationMessageState.Queued,
            f.local.records.getValue(originals.last().intent.id).state,
        )
        assertNotNull(f.wake)
        assertEquals(1, f.scheduled.size)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(originals.map { it.intent.id }, f.stagedMessages)
        assertNull(f.wake)
    }

    @Test
    fun lateNewCommandAndWakeSurviveEarlierCompletion() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val first = f.pending(0)
        f.beforeAck = {
            f.pending(1)
            f.wake = CollaborationSpaceRecoveryWake(id(999))
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertNotNull(f.wake)
        assertIs<CollaborationMessageState.Sent>(f.local.records.getValue(first.intent.id).state)
        f.beforeAck = {}
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.stagedMessages.size)
        assertNull(f.wake)
    }

    @Test
    fun failedPendingInventoryDoesNotSkipToEmptyBindingCompletion() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        f.pending(0)
        f.local.failure = Failure(FailureKind.Storage, "Fictional unavailable store")
        assertIs<SyncResult.Retry>(f.worker())
        assertNotNull(f.wake)
        assertTrue(f.preparedMessages.isEmpty())
        assertTrue(f.delivered.isEmpty())
        f.local.failure = null
        assertEquals(SyncResult.Complete, f.worker())
        assertNull(f.wake)
    }

    @Test
    fun denialDuringClaimedReadBlocksSendingAndKeepsTheOriginal() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0)
        f.local.beforeGet = {
            f.local.gate =
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(
                        id(9999),
                        Failure(FailureKind.AccessDenied, "Fictional denial"),
                    )
                )
        }
        assertIs<SyncResult.Blocked>(f.worker())
        assertEquals(original, f.local.records.getValue(original.intent.id))
        assertNotNull(f.wake)
        assertTrue(f.preparedMessages.isEmpty())
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun cancellationBeforeClaimedReadIsNotAnInferredMessageAcknowledgement() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0)
        f.local.beforeGet = {
            f.local.records[original.intent.id] =
                original.copy(state = CollaborationMessageState.Cancelled)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(
            CollaborationMessageState.Cancelled,
            f.local.records.getValue(original.intent.id).state,
        )
        assertTrue(f.stagedMessages.isEmpty())
        assertTrue(f.delivered.isEmpty())
    }

    @Test
    fun cancellationRacingStagingEndsTheAttemptWithoutInvokingDelivery() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val original = f.pending(0)
        f.stagePending = { _, _, id ->
            assertEquals(original.intent.id, id)
            val cancelled = original.copy(state = CollaborationMessageState.Cancelled)
            f.local.records[id] = cancelled
            AppResult.Success(cancelled)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.delivered.isEmpty())
        assertEquals(
            CollaborationMessageState.Cancelled,
            f.local.records.getValue(original.intent.id).state,
        )
        assertNull(f.wake)
    }

    @Test
    fun localComposerPassPrecedesNativeDeviceAdoptionAndSync() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.beforeComposer = { assertEquals(0, f.deviceReads + f.adoptions + f.syncs) }
        f.beforeDevice = { assertEquals(1, f.composerPasses) }
        f.beforeAdopt = { assertEquals(1, f.composerPasses) }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.composerPasses)
        assertEquals(1, f.deviceReads)
    }

    @Test
    fun missingPublishedNativeParentDoesNotPreventTheLocalComposerPass() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.base.states.value = dev.fajar.starter.app.bootstrap.ApplicationCollaborationState.Pending
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.composerPasses)
        assertEquals(0, f.deviceReads + f.adoptions + f.syncs)
        assertNotNull(f.wake)
    }

    @Test
    fun localComposerDenialStopsNativeWorkWithoutDiscardingTheWake() = runTest {
        for (kind in
            listOf(
                FailureKind.Unauthorized,
                FailureKind.AccessDenied,
                FailureKind.Validation,
                FailureKind.Conflict,
            )) {
            val f = Fixture(testScheduler, count = 0)
            val old = f.wake
            val failure = Failure(kind, "Local admission failed")
            f.composerResult = AppResult.Failed(failure)
            assertEquals(SyncResult.Blocked(failure), f.worker())
            assertEquals(1, f.composerPasses)
            assertEquals(0, f.deviceReads + f.adoptions + f.syncs)
            assertEquals(old, f.wake)
            assertTrue(f.scheduled.isEmpty())
        }
    }

    @Test
    fun composerQueueCapacityFailureStillDrainsExistingMessagesBeforeBackingOff() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.enablePendingDelivery()
        val queued = f.pending(0)
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(id(201), id(202), "Retained original", f.base.clock.now()),
                id(203),
                f.base.clock.now(),
            )
        f.composers.submissions[original.id] = original
        val failure =
            Failure(
                FailureKind.Storage,
                "Command capacity reached",
                code = "client.command_queue_full",
            )
        f.composerResult = AppResult.Failed(failure)
        val wake = f.wake
        assertEquals(SyncResult.Retry(failure), f.worker())
        assertEquals(listOf(queued.intent.conversationId), f.delivered)
        val sent = f.local.records.getValue(queued.intent.id)
        assertEquals(queued.intent, sent.intent)
        assertIs<CollaborationMessageState.Sent>(sent.state)
        assertEquals(original, f.composers.submissions[original.id])
        assertEquals(wake, f.wake)
        assertEquals(0, f.acknowledgments)
        assertTrue(f.scheduled.isEmpty())
        f.composers.submissions.clear()
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(false, false))
        assertEquals(SyncResult.Complete, f.worker())
        assertNull(f.wake)
        assertEquals(listOf(queued.intent.conversationId), f.delivered)
    }

    @Test
    fun failedComposerPassUsesBackoffWhenTheNativeSweepYields() = runTest {
        val f = Fixture(testScheduler, count = 8)
        val failure = Failure(FailureKind.Timeout, "Local pass timed out")
        f.composerResult = AppResult.Failed(failure)
        val wake = f.wake
        assertEquals(SyncResult.Retry(failure), f.worker())
        assertTrue(f.delivered.isNotEmpty())
        assertEquals(wake, f.wake)
        assertEquals(0, f.acknowledgments)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun authoritativeEmptyCompletionCanDischargeARecoveredLocalFailure() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.composerResult = AppResult.Failed(Failure(FailureKind.Storage, "Lost local response"))
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.deviceReads)
        assertEquals(1, f.acknowledgments)
        assertNull(f.wake)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun missingNativeOriginalRetainsFailedLocalWorkWithoutImmediateRescheduling() = runTest {
        val f = Fixture(testScheduler, count = 0)
        val failure = Failure(FailureKind.Storage, "Local publication failed")
        f.composerResult = AppResult.Failed(failure)
        f.original = null
        val wake = f.wake
        assertEquals(SyncResult.Retry(failure), f.worker())
        assertEquals(1, f.deviceReads)
        assertEquals(wake, f.wake)
        assertEquals(0, f.acknowledgments + f.adoptions + f.syncs)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun cancellationDuringLocalMaterializationReleasesTheProcessorBeforeNativeWork() = runTest {
        val f = Fixture(testScheduler, count = 0)
        val entered = CompletableDeferred<Unit>()
        f.beforeComposer = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.worker() }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(0, f.deviceReads + f.adoptions + f.syncs)
        assertNotNull(f.wake)
        f.beforeComposer = {}
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(2, f.composerPasses)
    }

    @Test
    fun pendingComposerKeepsTheWakeUntilALaterBoundedPassDrainsIt() = runTest {
        val f = Fixture(testScheduler, count = 0)
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(id(201), id(202), "Pending original", f.base.clock.now()),
                id(203),
                f.base.clock.now(),
            )
        f.composers.submissions[original.id] = original
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(true, false))
        assertEquals(SyncResult.Complete, f.worker())
        assertNotNull(f.wake)
        assertEquals(0, f.acknowledgments)
        assertEquals(original, f.composers.submissions[original.id])
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
        f.composers.submissions.clear()
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(false, false))
        assertEquals(SyncResult.Complete, f.worker())
        assertNull(f.wake)
        assertEquals(1, f.acknowledgments)
    }

    @Test
    fun missingNativeOriginalCannotAcknowledgePendingLocalComposerWork() = runTest {
        val f = Fixture(testScheduler, count = 0)
        val old = f.wake
        f.original = null
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(true, false))
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(old, f.wake)
        assertEquals(0, f.acknowledgments + f.adoptions + f.syncs)
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun missingNativeOriginalKeepsMaterializedMessagesPendingWithoutImmediateSelfWake() = runTest {
        val f = Fixture(testScheduler, count = 0)
        val queued = f.pending(0)
        f.original = null
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(false, true))
        val wake = f.wake
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(wake, f.wake)
        assertEquals(queued, f.local.records[queued.intent.id])
        assertEquals(0, f.acknowledgments + f.adoptions + f.syncs)
        assertTrue(f.scheduled.isEmpty() && f.delivered.isEmpty())
    }

    @Test
    fun aConcurrentHandoffCannotBeAcknowledgedByAnEarlierLocalPassWithoutANativeDevice() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.original = null
        val newer = CollaborationSpaceRecoveryWake(id(900))
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(id(201), id(202), "Concurrent handoff", f.base.clock.now()),
                id(203),
                f.base.clock.now(),
            )
        f.beforeComposer = {
            f.composers.submissions[original.id] = original
            f.wake = newer
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(newer, f.wake)
        assertEquals(original, f.composers.submissions[original.id])
        assertEquals(0, f.acknowledgments)
        assertEquals(listOf(CollaborationSpaceRecoveryWake.WORK_KEY), f.scheduled)
    }

    @Test
    fun workAppearingAfterAnAbsentWakeSnapshotRemainsPendingWithoutASelfWake() = runTest {
        val f = Fixture(testScheduler, count = 0)
        f.wake = null
        val newer = CollaborationSpaceRecoveryWake(id(900))
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(id(201), id(202), "Concurrent handoff", f.base.clock.now()),
                id(203),
                f.base.clock.now(),
            )
        f.beforeComposer = {
            f.composers.submissions[original.id] = original
            f.wake = newer
        }
        f.composerResult = AppResult.Success(CollaborationSpaceComposerRecovery(true, false))
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(newer, f.wake)
        assertEquals(original, f.composers.submissions[original.id])
        assertEquals(0, f.acknowledgments + f.deviceReads + f.adoptions + f.syncs)
        assertTrue(f.scheduled.isEmpty())
    }

    private class Fixture(scheduler: TestCoroutineScheduler, val count: Int = 8) {
        val base = RecoveryWorkerFixture(scheduler, count = 0)
        val owner = CollaborationSpaceOwner(base.parent, id(100))
        val local = SpaceRecoveryLocalStorageFixture(owner)
        val composers = SpaceComposerCompletionOutboxFixture(owner.localDirectoryAccess())
        var composerPasses = 0
        var beforeComposer: suspend () -> Unit = {}
        var composerResult: AppResult<CollaborationSpaceComposerRecovery> =
            AppResult.Success(CollaborationSpaceComposerRecovery(false, false))
        var original: CollaborationSpaceRecoveryDevice? =
            CollaborationSpaceRecoveryDevice(owner, id(101), id(102))
        val runtime = CollaborationSpaceRuntimeReady(owner, id(103), id(102))
        val bindingTime = base.clock.now()
        val boundary =
            CollaborationSpaceRoomBindingBoundary(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                id(104),
                1,
            )
        var wake: CollaborationSpaceRecoveryWake? = CollaborationSpaceRecoveryWake(id(1))
        var progress: CollaborationSpaceRecoveryProgress? = null
        var writes = 0
        var acknowledgments = 0
        var wakeWrites = 0
        var deviceReads = 0
        var adoptions = 0
        var syncs = 0
        val delivered = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        var beforeDevice: suspend () -> Unit = {}
        var beforeAdopt: suspend () -> Unit = {}
        var beforeDemand: suspend () -> Unit = {}
        var needsDelivery = true
        var demandFailure: Failure? = null
        var beforeDelivery: suspend () -> Unit = {}
        var beforeAck: suspend () -> Unit = {}
        var scheduleFailure: Failure? = null
        var syncFailure: Failure? = null
        val published = linkedMapOf<String, CollaborationSpaceRoomBinding>()
        val preparedMessages = mutableListOf<String>()
        val stagedMessages = mutableListOf<String>()
        val unready = mutableSetOf<String>()
        var prepareMessage:
            suspend (CollaborationSpaceConversation) -> AppResult<CollaborationSpaceRoomBinding> =
            {
                error("No pending message")
            }
        var stagePending:
            suspend (
                CollaborationSpaceRuntimeReady, CollaborationSpaceConversation, String,
            ) -> AppResult<CollaborationSpaceMessageRecord> =
            { _, _, _ ->
                error("No pending message")
            }
        var deliverPending:
            (suspend (CollaborationSpaceConversation) -> AppResult<CollaborationDeliveryReport>)? =
            null
        var report =
            CollaborationDeliveryReport(
                emptySet(),
                emptySet(),
                CollaborationQueueDeliveryReason.DRAINED,
            )
        val wakes =
            object : CollaborationSpaceRecoveryWakeRepository {
                override suspend fun pending() = AppResult.Success(wake)

                override suspend fun request(): AppResult<CollaborationSpaceRecoveryWake> {
                    val value = CollaborationSpaceRecoveryWake(id(500 + ++wakeWrites))
                    wake = value
                    return AppResult.Success(value)
                }

                override suspend fun acknowledge(
                    wake: CollaborationSpaceRecoveryWake
                ): AppResult<Boolean> {
                    beforeAck()
                    if (this@Fixture.wake != wake) return AppResult.Success(false)
                    this@Fixture.wake = null
                    acknowledgments++
                    return AppResult.Success(true)
                }
            }
        val schedules =
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    scheduled += key
                    return scheduleFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                }
            }
        val checkpoints =
            object : CollaborationSpaceRecoveryProgressRepository {
                override suspend fun current(owner: CollaborationSpaceOwner) =
                    AppResult.Success(progress)

                override suspend fun replace(
                    owner: CollaborationSpaceOwner,
                    expected: CollaborationSpaceRecoveryProgress?,
                    sweep: CollaborationSpaceRecoverySweep?,
                ): AppResult<CollaborationSpaceRecoveryProgress?> {
                    if (expected != progress) return AppResult.Success(null)
                    val saved = CollaborationSpaceRecoveryProgress(id(1000 + ++writes), sweep)
                    progress = saved
                    return AppResult.Success(saved)
                }
            }
        val retained =
            (0 until count step 2).associate { n -> binding(n).let { it.conversation.id to it } }
        val bindings =
            object : CollaborationSpaceRoomBindingRepository {
                override fun observeByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): Flow<AppResult<CollaborationSpaceRoomBinding?>> =
                    error("Binding observation is not part of this fixture")

                override suspend fun getByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ) = AppResult.Success(retained[conversationId])

                override suspend fun findByRoom(
                    access: CollaborationSpaceLocalAccess,
                    serverName: String,
                    roomId: String,
                ): AppResult<CollaborationSpaceRoomBinding?> = error("No discovery")

                override suspend fun retain(
                    access: CollaborationSpaceLocalAccess,
                    binding: CollaborationSpaceRoomBinding,
                ): AppResult<Boolean> = error("No room creation")
            }
        val inventory =
            object : CollaborationSpaceRoomBindingInventoryRepository {
                override suspend fun page(
                    access: CollaborationSpaceLocalAccess,
                    after: CollaborationSpaceRoomBindingCursor?,
                    limit: Int,
                ): AppResult<CollaborationSpaceRoomBindingPage> {
                    assertEquals(1, limit)
                    val index = after?.afterId?.drop(2)?.toInt(16) ?: 0
                    val items =
                        if (index < count && index % 2 == 0) listOf(binding(index)) else emptyList()
                    return AppResult.Success(
                        CollaborationSpaceRoomBindingPage(
                            if (count == 0) null else boundary,
                            items,
                            if (index + 1 < count)
                                CollaborationSpaceRoomBindingCursor(boundary, raw(index + 1))
                            else null,
                            false,
                        )
                    )
                }
            }
        val worker = createWorker()

        fun createWorker(platform: AppPlatform = AppPlatform.Android) =
            CollaborationSpaceRecoveryWorker(
                states = lazy { base.states },
                ensureWake = EnsureCollaborationSpaceRecoveryWake(wakes),
                readWake = ReadCollaborationSpaceRecoveryWake(wakes),
                completeWake = CompleteCollaborationSpaceRecoveryWake(wakes),
                resume = lazy { ResumeCollaborationSpaceRecovery(wakes, schedules) },
                recoverComposer = {
                    composerPasses++
                    beforeComposer()
                    composerResult
                },
                device = {
                    assertEquals(base.parent, it)
                    deviceReads++
                    beforeDevice()
                    AppResult.Success(original)
                },
                adopt = {
                    adoptions++
                    beforeAdopt()
                    AppResult.Success(runtime)
                },
                sync = {
                    syncs++
                    syncFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
                },
                readProgress =
                    ReadCollaborationSpaceRecoveryProgress(checkpoints, base.sessions, base.server),
                updateProgress =
                    UpdateCollaborationSpaceRecoveryProgress(
                        checkpoints,
                        base.sessions,
                        base.server,
                    ),
                page = LoadCollaborationSpaceRecoveryPage(inventory, base.sessions, base.server),
                room =
                    LoadCollaborationSpaceRecoveryRoom(
                        bindings,
                        inventory,
                        base.sessions,
                        base.server,
                    ),
                finishSweep =
                    FinishCollaborationSpaceRecovery(
                        checkpoints,
                        inventory,
                        local.messages,
                        composers.repository,
                        local.directory,
                        base.sessions,
                        base.server,
                    ),
                pendingMessages = {
                    ReadCollaborationSpacePendingMessages(
                        local.messages,
                        local.directory,
                        base.sessions,
                        base.server,
                    )(it)
                },
                message =
                    LoadCollaborationSpaceRecoveryMessage(
                        local.messages,
                        local.metadata,
                        local.directory,
                        base.sessions,
                        base.server,
                    ),
                prepareRoom = { prepareMessage(it) },
                stageMessage = { runtime, conversation, id ->
                    stagePending(runtime, conversation, id)
                },
                demand = { _, _ ->
                    beforeDemand()
                    demandFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(needsDelivery)
                },
                deliver = { _, conversation ->
                    delivered += conversation.id
                    beforeDelivery()
                    deliverPending?.invoke(conversation) ?: AppResult.Success(report)
                },
                build = base.build.copy(platform = platform),
            )

        fun pending(index: Int, staged: Boolean = false): CollaborationSpaceMessageRecord {
            val bound =
                binding(index).let {
                    it.copy(
                        room =
                            it.room.copy(
                                origin = CollaborationSpaceRoomOrigin.SPACE,
                                original = null,
                            ),
                        original = null,
                    )
                }
            published[bound.conversation.id] = bound
            local.conversations[bound.conversation.id] = bound.conversation
            val intent =
                CollaborationSpaceMessageIntent(
                    id(9000 + index),
                    local.access.messageOwner(),
                    bound.conversation.id,
                    bindingTime + index.nanoseconds,
                    "Fictional pending $index",
                )
            val device =
                CollaborationMessageDevice(
                    owner.parent.nativeSessionId,
                    runtime.deviceId,
                    runtime.operationId,
                    bound.room.roomId,
                )
            val state =
                CollaborationMessageState.Staged(
                    device,
                    "original-${intent.id}",
                    CollaborationMessageOrigin("@fixture:matrix.example.test", "1".repeat(64)),
                )
            val record =
                CollaborationSpaceMessageRecord(
                    intent,
                    if (staged) 2 else 0,
                    if (staged) state else CollaborationMessageState.Queued,
                )
            local.records[intent.id] = record
            return record
        }

        fun enablePendingDelivery() {
            prepareMessage = { conversation ->
                preparedMessages += conversation.id
                AppResult.Success(published.getValue(conversation.id))
            }
            stagePending = { ready, conversation, id ->
                assertEquals(runtime, ready)
                stagedMessages += id
                if (conversation.id in unready)
                    AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Fictional recipient not ready",
                            code = "client.collaboration_recipient_unavailable",
                        )
                    )
                else {
                    val previous = local.records.getValue(id)
                    val device =
                        CollaborationMessageDevice(
                            owner.parent.nativeSessionId,
                            runtime.deviceId,
                            runtime.operationId,
                            published.getValue(conversation.id).room.roomId,
                        )
                    val staged =
                        previous.copy(
                            revision = 2,
                            state =
                                CollaborationMessageState.Staged(
                                    device,
                                    "original-$id",
                                    CollaborationMessageOrigin(
                                        "@fixture:matrix.example.test",
                                        "1".repeat(64),
                                    ),
                                ),
                        )
                    local.records[id] = staged
                    AppResult.Success(staged)
                }
            }
            deliverPending = { conversation ->
                val sent = mutableSetOf<String>()
                for (record in local.records.values.toList()) {
                    val state = record.state
                    if (
                        record.intent.conversationId == conversation.id &&
                            state is CollaborationMessageState.Staged
                    ) {
                        local.records[record.intent.id] =
                            record.copy(
                                revision = 3,
                                state =
                                    CollaborationMessageState.Sent(
                                        state.device,
                                        state.transactionId,
                                        "\$event-${record.intent.id}",
                                        state.origin,
                                    ),
                            )
                        sent += record.intent.id
                    }
                }
                AppResult.Success(
                    CollaborationDeliveryReport(
                        sent,
                        emptySet(),
                        CollaborationQueueDeliveryReason.DRAINED,
                    )
                )
            }
        }

        private fun binding(index: Int): CollaborationSpaceRoomBinding {
            val pair = listOf(owner.parent.accountId, id(6000)).sorted()
            val time = bindingTime
            val position = CollaborationOriginalConversationPosition(id(7000), id(8000 + index))
            val room = "!original$index:matrix.example.test"
            return CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    owner.spaceId,
                    id(3000 + index),
                    pair[0],
                    pair[1],
                    pair[0],
                    time,
                    0,
                ),
                CollaborationSpacePublishedRoom(
                    room,
                    "matrix.example.test",
                    CollaborationSpaceRoomOrigin.LEGACY,
                    time,
                    CollaborationSpaceOriginalRoom(position, room, time - 60.seconds),
                ),
                CollaborationSpaceOriginalRoomMetadata(
                    CollaborationOriginalConversation(
                        position,
                        pair[0],
                        pair[1],
                        pair[0],
                        time - 120.seconds,
                        0,
                    ),
                    CollaborationOriginalRoom(
                        "matrix.example.test",
                        room,
                        time - 60.seconds,
                        pair[0],
                    ),
                ),
            )
        }
    }

    companion object {
        private fun id(n: Int) = "e0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

        private fun raw(n: Int) = "b-" + n.toString(16).padStart(64, '0')
    }
}
