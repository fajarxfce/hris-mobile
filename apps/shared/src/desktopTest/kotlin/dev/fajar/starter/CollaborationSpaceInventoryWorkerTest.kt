@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceIncomingQueueContention
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceIncomingWorkerFixture
import dev.fajar.starter.fixtures.recoveryId
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceInventoryWorkerTest {
    @Test
    fun inventoryOnlyDemandUsesTheExistingRuntimeAndFinishesTheSameWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6000))
        f.inventoryTurn = { _, captured ->
            f.sources[0] = captured.copy(inventoryGeneration = null)
            AppResult.Success(CollaborationSpaceInventoryOutcome.Complete)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "adopt" })
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(1, f.calls.count { it == "finish" })
        assertNull(f.wake)
        assertTrue(f.attempted.isEmpty())
        assertTrue(f.physicalAttempts.isEmpty())
    }

    @Test
    fun aFullPageSchedulesOneLaterTurnWithoutEnumeratingInline() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6001))
        f.inventoryTurn = { _, captured ->
            f.sources[0] = captured.copy(inventoryAfterRoomId = "!page16:test")
            AppResult.Success(CollaborationSpaceInventoryOutcome.Advanced)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(1, f.calls.count { it == "resume" })
        assertNotNull(f.wake)
        assertTrue(f.deferredInventories.isEmpty())
        assertFalse("finish" in f.calls)
    }

    @Test
    fun repeatedWakesDuringRoomBackpressureDoNotRepeatInventory() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6002))
        f.inventoryTurn = { _, _ ->
            AppResult.Success(CollaborationSpaceInventoryOutcome.WaitingForRoom)
        }
        assertIs<SyncResult.Retry>(f.worker())
        val retained = f.sources.single()
        assertNotNull(retained.inventoryRetry)
        assertEquals(1, f.inventoryAttempts.size)
        f.calls.clear()
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(retained, f.sources.single())
        assertEquals(1, f.inventoryAttempts.size)
        assertFalse(
            f.calls.any { it in setOf("adopt", "inventory", "resume", "finish", "defer-inventory") }
        )
        assertNotNull(f.wake)
    }

    @Test
    fun aFullPrimaryQueueLeavesInventoryRetainedWithoutEnumerating() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 64)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6003))
        f.missingKeys = true
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(64, f.rooms.size)
        assertEquals(4, f.attempted.size)
        assertTrue(f.inventoryAttempts.isEmpty())
        assertNotNull(f.sources.single().inventoryGeneration)
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls)
    }

    @Test
    fun aFutureInventoryRetryDoesNotAdoptOrAcknowledgeTheWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        val now = f.base.clock.now().toEpochMilliseconds()
        f.sources[0] =
            f.sources
                .single()
                .copy(
                    inventoryGeneration = recoveryId(6004),
                    inventoryRetry = CollaborationSpaceIncomingRetry(now, now + 30_000, 1),
                )
        assertIs<SyncResult.Retry>(f.worker())
        assertTrue(f.inventoryAttempts.isEmpty())
        assertFalse("adopt" in f.calls || "finish" in f.calls || "resume" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun inventoryKeepsTheExistingFourRoomFifoTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 5)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6005))
        f.inventoryTurn = { _, captured ->
            assertEquals(4, f.attempted.size)
            f.sources[0] = captured.copy(inventoryGeneration = null)
            AppResult.Success(CollaborationSpaceInventoryOutcome.Complete)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals((0 until 4).map { recoveryId(1000 + it) }, f.attempted)
        assertEquals(1, f.rooms.size)
        assertEquals(1, f.inventoryAttempts.size)
        assertTrue(f.calls.lastIndexOf("complete") in 0 until f.calls.indexOf("inventory"))
        assertNotNull(f.wake)
    }

    @Test
    fun partialProgressDefersTheRetainedCursorRatherThanReplayingTheOldPage() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6006))
        f.inventoryTurn = { _, captured ->
            f.sources[0] =
                captured.copy(inventoryAfterRoomId = "!consumed:test", position = "continuous")
            AppResult.Success(CollaborationSpaceInventoryOutcome.WaitingForRoom)
        }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals("!consumed:test", f.deferredInventories.single().first.inventoryAfterRoomId)
        assertEquals("continuous", f.sources.single().position)
        assertEquals("!consumed:test", f.sources.single().inventoryAfterRoomId)
        assertNotNull(f.sources.single().inventoryRetry)
        assertEquals(1, f.inventoryAttempts.size)
    }

    @Test
    fun capacityBackpressureUsesPersistentDeferralAndKeepsTheWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6007))
        f.inventoryTurn = { _, _ -> AppResult.Success(CollaborationSpaceInventoryOutcome.Capacity) }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.deferredInventories.size)
        assertNotNull(f.sources.single().inventoryRetry)
        assertNotNull(f.wake)
        assertFalse("resume" in f.calls || "finish" in f.calls)
    }

    @Test
    fun laterInventoryLossIsNotDelayedByTheOldFailedTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6008))
        f.inventoryTurn = { _, captured ->
            f.sources[0] =
                captured.copy(inventoryGeneration = recoveryId(6009), position = "new-loss")
            AppResult.Failed(Failure(FailureKind.Unavailable, "fixture"))
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(recoveryId(6009), f.sources.single().inventoryGeneration)
        assertNull(f.sources.single().inventoryRetry)
        assertTrue(f.deferredInventories.isEmpty())
        assertEquals(1, f.calls.count { it == "resume" })
        assertEquals(1, f.inventoryAttempts.size)
    }

    @Test
    fun completionAfterAnInterruptedReplyIsObservedWithoutInventingAnotherInventory() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6010))
        f.inventoryTurn = { _, captured ->
            f.sources[0] = captured.copy(inventoryGeneration = null)
            AppResult.Failed(Failure(FailureKind.Storage, "reply interrupted"))
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.inventoryAttempts.size)
        assertTrue(f.deferredInventories.isEmpty())
        assertNull(f.wake)
    }

    @Test
    fun rejectedQueueCasKeepsTheShortRetryWithoutPersistentInventoryBackoff() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6011))
        val busy = collaborationSpaceIncomingQueueContention()
        f.inventoryTurn = { _, _ -> busy }
        assertEquals(SyncResult.Retry(busy.failure), f.worker())
        assertEquals(1L, busy.failure.retryAfterSeconds)
        assertNull(f.sources.single().inventoryRetry)
        assertEquals(1, f.inventoryAttempts.size)
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls || "resume" in f.calls)
    }

    @Test
    fun inventoryAccessDenialBlocksBeforeDeferralAndWakeRetirement() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6012))
        val denied = Failure(FailureKind.AccessDenied, "fixture denied")
        f.inventoryTurn = { _, _ -> AppResult.Failed(denied) }
        assertEquals(SyncResult.Blocked(denied), f.worker())
        assertTrue(f.deferredInventories.isEmpty())
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls || "resume" in f.calls)
    }

    @Test
    fun stalledInventoryIsCancelledBeforeTheRetainedRetry() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6013))
        var stopped = false
        f.inventoryTurn = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(10_000L, currentTime)
        assertTrue(stopped)
        assertEquals(FailureKind.Timeout, f.deferredInventories.single().second?.kind)
        assertNotNull(f.sources.single().inventoryRetry)
        assertEquals(1, f.inventoryAttempts.size)
    }

    @Test
    fun parentReplacementCancelsAndJoinsThePendingInventoryRead() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6014))
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.inventoryTurn = { _, _ ->
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
        assertTrue(f.deferredInventories.isEmpty())
        assertNotNull(f.wake)
        assertNotNull(f.sources.single().inventoryGeneration)
        assertFalse("finish" in f.calls)
    }

    @Test
    fun concurrentTriggersShareTheExistingMutexAndSeeTheSavedDeferral() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6015))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.inventoryTurn = { _, _ ->
            entered.complete(Unit)
            release.await()
            AppResult.Failed(Failure(FailureKind.Unavailable, "fixture offline"))
        }
        val first = async { f.worker() }
        entered.await()
        val second = async { f.worker() }
        runCurrent()
        assertEquals(1, f.inventoryAttempts.size)
        release.complete(Unit)
        assertIs<SyncResult.Retry>(first.await())
        assertIs<SyncResult.Retry>(second.await())
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(1, f.deferredInventories.size)
        assertNotNull(f.wake)
    }

    @Test
    fun anotherOriginalsInventoryCannotBeReadOrDiscardedByTheInstalledRuntime() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, count = 0)
        val other = f.source.copy(storeId = "2".repeat(64), matrixDeviceId = "OTHER")
        f.sources += CollaborationSpaceIncomingSourceProgress(other, "other", recoveryId(6016))
        assertIs<SyncResult.Retry>(f.worker())
        assertTrue(f.inventoryAttempts.isEmpty())
        assertNotNull(f.sources.last().inventoryGeneration)
        assertNotNull(f.wake)
        assertFalse("finish" in f.calls)
    }
}
