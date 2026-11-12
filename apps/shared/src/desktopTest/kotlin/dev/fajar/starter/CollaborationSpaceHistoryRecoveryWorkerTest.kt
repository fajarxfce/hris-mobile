@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceHistoryRecoveryWorkerTest {
    @Test
    fun historyOnlyWorkAdoptsExistingRuntimeAndFinishesWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyTurn = { actual ->
            assertEquals(f.runtime, actual)
            f.historyRecovery = assertNotNull(f.historyRecovery).copy(revision = 1, complete = true)
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "adopt" })
        assertEquals(1, f.historyAttempts.size)
        assertNull(f.wake)
        assertFalse("resume" in f.calls)
        assertTrue(f.attempted.isEmpty())
    }

    @Test
    fun deferredHistorySchedulesRetryWithoutAdoptionResumeOrFinish() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        val retry =
            assertNotNull(
                nextCollaborationSpaceIncomingRetry(null, f.base.clock.now().toEpochMilliseconds())
            )
        val history =
            CollaborationSpaceHistoryRecovery(recoveryId(7000), revision = 1, retry = retry)
        f.historyRecovery = history
        repeat(2) { assertIs<SyncResult.Retry>(f.worker()) }
        assertEquals(history, f.historyRecovery)
        assertTrue(f.historyAttempts.isEmpty())
        assertFalse(f.calls.any { it in listOf("adopt", "resume", "finish") })
        assertNotNull(f.wake)
    }

    @Test
    fun completedUnresolvedHistoryDoesNotWakeItselfAgain() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        val history =
            CollaborationSpaceHistoryRecovery(
                recoveryId(7000),
                revision = 1,
                complete = true,
                unresolved = true,
            )
        f.historyRecovery = history
        assertEquals(SyncResult.Complete, f.worker())
        assertNull(f.wake)
        f.calls.clear()
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.historyAttempts.isEmpty())
        assertEquals(history, f.historyRecovery)
        assertFalse(f.calls.any { it in listOf("adopt", "history", "resume") })
    }

    @Test
    fun newKeyDemandMakesSleepingHistoryReadyAgain() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery =
            CollaborationSpaceHistoryRecovery(
                recoveryId(7000),
                revision = 1,
                complete = true,
                unresolved = true,
            )
        assertEquals(SyncResult.Complete, f.worker())
        val generation = recoveryId(7001)
        f.historyRecovery =
            assertIs<AppResult.Success<CollaborationSpaceHistoryRecovery>>(
                    planCollaborationSpaceHistoryRecoveryRequest(f.historyRecovery, generation)
                )
                .value
        f.wake = CollaborationSpaceIncomingWake(generation)
        f.historyTurn = {
            f.historyRecovery = assertNotNull(f.historyRecovery).copy(revision = 1, complete = true)
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.historyAttempts.size)
        assertEquals(generation, f.historyAttempts.single()?.generation)
        assertNull(f.wake)
    }

    @Test
    fun oneHistoryPageRequestsOnlyOneLaterTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyTurn = {
            f.historyRecovery =
                assertNotNull(f.historyRecovery)
                    .copy(revision = 1, afterPartition = "next-partition")
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.historyAttempts.size)
        assertEquals(1, f.calls.count { it == "resume" })
        assertFalse("finish" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun operationalDeferralDoesNotImmediatelyResumeActiveHistory() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyTurn = {
            f.historyRecovery =
                assertNotNull(f.historyRecovery)
                    .copy(
                        revision = 1,
                        retry =
                            nextCollaborationSpaceIncomingRetry(
                                null,
                                f.base.clock.now().toEpochMilliseconds(),
                            ),
                    )
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Deferred)
        }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.historyAttempts.size)
        assertFalse("resume" in f.calls || "finish" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun deferredHistoryDoesNotBlockReadyPrimaryMessages() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.historyRecovery =
            CollaborationSpaceHistoryRecovery(
                recoveryId(7000),
                revision = 1,
                retry =
                    nextCollaborationSpaceIncomingRetry(
                        null,
                        f.base.clock.now().toEpochMilliseconds(),
                    ),
            )
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.attempted.size)
        assertTrue(f.rooms.isEmpty())
        assertTrue(f.historyAttempts.isEmpty())
        assertFalse("resume" in f.calls)
    }

    @Test
    fun inventoryAndHistoryGetServiceBeforeSlowPrimaryWork() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 4)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(7000))
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7001))
        f.inventoryTurn = { _, source ->
            delay(9000)
            f.sources[0] = source.copy(inventoryGeneration = null)
            AppResult.Success(CollaborationSpaceInventoryOutcome.Complete)
        }
        f.historyTurn = {
            delay(34_000)
            f.historyRecovery = assertNotNull(f.historyRecovery).copy(revision = 1, complete = true)
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        f.before = { stage -> if (stage == "content") delay(35_000) }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(1, f.historyAttempts.size)
        assertTrue(f.calls.indexOf("inventory") < f.calls.indexOf("history"))
        assertTrue(f.calls.indexOf("history") < f.calls.indexOf("content"))
        assertEquals(
            3,
            f.rooms.size,
            "At least one primary room finishes within the shared90s lifetime",
        )
        assertNotNull(f.wake)
    }

    @Test
    fun fullQueueStillServicesHistoryAndTransfersFiniteMissingKeyTasks() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 64)
        for (i in f.rooms.indices) f.rooms[i] =
            f.rooms[i].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyTurn = {
            f.historyRecovery = assertNotNull(f.historyRecovery).copy(revision = 1, complete = true)
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        f.missingKeys = true
        f.demoteRoomResult = true
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.historyAttempts.size)
        assertEquals(4, f.demotions.size)
        assertEquals(60, f.rooms.size)
        assertNotNull(f.historyRecovery?.requestedGeneration)
        assertTrue(f.deferred.isEmpty())
        assertFalse("recover" in f.calls || "provider" in f.calls)
        assertEquals(1, f.calls.count { it == "resume" })
    }

    @Test
    fun historyFailureRetainsWakeWithoutImmediateResume() = runTest {
        for (kind in listOf(FailureKind.Storage, FailureKind.AccessDenied)) {
            val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
            val history = CollaborationSpaceHistoryRecovery(recoveryId(7000))
            f.historyRecovery = history
            f.historyTurn = { AppResult.Failed(Failure(kind, "Fixture failure")) }
            val result = f.worker()
            if (kind == FailureKind.AccessDenied) assertIs<SyncResult.Blocked>(result)
            else assertIs<SyncResult.Retry>(result)
            assertEquals(history, f.historyRecovery)
            assertNotNull(f.wake)
            assertFalse("finish" in f.calls || "resume" in f.calls)
        }
    }

    @Test
    fun parentReplacementCancelsAndJoinsHistoryService() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        val history = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyRecovery = history
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.historyTurn = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val running = async { f.worker() }
        entered.await()
        f.base.states.value = f.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(running.await())
        assertTrue(stopped)
        assertEquals(history, f.historyRecovery)
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls || "resume" in f.calls)
    }

    @Test
    fun concurrentTriggersShareMutexAndObserveOneSavedDeferral() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.historyTurn = {
            entered.complete(Unit)
            release.await()
            f.historyRecovery =
                assertNotNull(f.historyRecovery)
                    .copy(
                        revision = 1,
                        retry =
                            nextCollaborationSpaceIncomingRetry(
                                null,
                                f.base.clock.now().toEpochMilliseconds(),
                            ),
                    )
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Deferred)
        }
        val first = async { f.worker() }
        entered.await()
        val second = async { f.worker() }
        runCurrent()
        release.complete(Unit)
        assertIs<SyncResult.Retry>(first.await())
        assertIs<SyncResult.Retry>(second.await())
        assertEquals(1, f.historyAttempts.size)
        assertEquals(1, f.calls.count { it == "adopt" })
        assertFalse("resume" in f.calls || "finish" in f.calls)
    }

    @Test
    fun sourceLossAfterHistoryCannotAcknowledgeWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.historyRecovery = CollaborationSpaceHistoryRecovery(recoveryId(7000))
        f.historyTurn = {
            f.installed = null
            AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced)
        }
        assertIs<SyncResult.Retry>(f.worker())
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls || "resume" in f.calls)
    }

    @Test
    fun cleanPrimaryCompletionDoesNotInvokeKeyTransfer() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.demoteRoomFailure = Failure(FailureKind.AccessDenied, "Must not run")
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.demotions.isEmpty())
        assertNull(f.historyRecovery)
        assertNull(f.wake)
    }

    @Test
    fun incompleteFiniteSeekDoesNotTransferMissingKeysEarly() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.missingKeys = true
        f.referencesMore = true
        f.demoteRoomResult = true
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.demotions.isEmpty())
        assertNull(f.historyRecovery)
        assertNull(f.rooms.single().retry)
        assertFalse("recover" in f.calls)
        assertEquals(1, f.calls.count { it == "resume" })
    }

    @Test
    fun unconfirmedTransferKeepsKnownProviderPositionAndBackoff() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.missingKeys = true
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.demotions.size)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.rooms.single().phase)
        assertNotNull(f.rooms.single().retry)
        assertEquals(1, f.calls.count { it == "recover" })
        assertFalse("provider" in f.calls)
        assertNull(f.historyRecovery)
    }

    @Test
    fun insufficientHeadCoverageUsesGuardedProviderRecoveryBeforeAnotherTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.missingKeys = true
        f.hiddenHead = true
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.demotions.size)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.rooms.single().phase)
        assertNotNull(f.rooms.single().retry)
        assertFalse("provider" in f.calls)
        assertNull(f.historyRecovery)
    }

    @Test
    fun transferStorageFailureRetainsPrimaryTaskForRetry() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.missingKeys = true
        f.demoteRoomFailure = Failure(FailureKind.Storage, "Fixture transfer failure")
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.rooms.size)
        assertNotNull(f.rooms.single().retry)
        assertNull(f.historyRecovery)
        assertNotNull(f.wake)
        assertFalse("recover" in f.calls || "resume" in f.calls)
    }

    @Test
    fun publishedTransferUsesLatestPersistedPendingCaptureAfterProviderPage() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.missingKeys = true
        f.demoteRoomResult = true
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.demotions.single().captured.phase)
        assertTrue(f.rooms.isEmpty())
        assertNotNull(f.historyRecovery)
        assertFalse("recover" in f.calls)
        assertTrue(f.deferred.isEmpty())
    }
}
