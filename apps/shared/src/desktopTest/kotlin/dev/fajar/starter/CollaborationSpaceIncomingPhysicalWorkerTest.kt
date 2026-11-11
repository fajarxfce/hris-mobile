@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceIncomingQueueContention
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceIncomingWorkerFixture
import dev.fajar.starter.fixtures.recoveryId
import dev.fajar.starter.sync.domain.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIncomingPhysicalWorkerTest {
    @Test
    fun knownPhysicalRoomConvertsLocallyAndTheNextTurnAcquiresItsContent() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        val original = f.queuePhysical(0, recoveryId(5000))
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(original.roomId), f.physicalAttempts)
        assertTrue(f.physicalRooms.isEmpty())
        assertEquals(original.generation, f.rooms.single().generation)
        assertEquals(original.position, f.rooms.single().position)
        assertEquals(original.sourceId, f.rooms.single().sourceId)
        assertFalse("provider" in f.calls || "finish" in f.calls)
        assertEquals(1, f.calls.count { it == "resume" })
        f.calls.clear()
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "provider" })
        assertEquals(1, f.calls.count { it == "content" })
        assertNull(f.wake)
    }

    @Test
    fun mixedLogicalAndPhysicalTasksShareFourFifoPositions() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 3)
        f.rooms.indices.forEach { i -> f.rooms[i] = f.rooms[i].copy(position = 2L * i + 1) }
        f.nextPosition = 6
        repeat(3) { f.queuePhysical(it, recoveryId(5000 + it)) }
        f.physicalRooms.indices.forEach { i ->
            f.physicalRooms[i] = f.physicalRooms[i].copy(position = 2L * i)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(
            listOf("binding", "room", "binding", "room"),
            f.calls.filter { it in setOf("binding", "room") },
        )
        assertEquals(2, f.attempted.size)
        assertEquals(2, f.physicalAttempts.size)
        assertEquals(1, f.physicalRooms.size)
        assertEquals(1, f.calls.count { it == "resume" })
    }

    @Test
    fun earlierPhysicalHintWaitingForLogicalKeysCannotKeepTakingEveryTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(position = 1)
        f.queuePhysical(
            0,
            assertIs<CollaborationSpaceIncomingTarget.Published>(f.rooms.single().target)
                .conversationId,
        )
        f.physicalRooms[0] = f.physicalRooms[0].copy(position = 0)
        f.missingKeys = true
        assertIs<SyncResult.Retry>(f.worker())
        assertNotNull(f.rooms.single().retry)
        assertNotNull(f.physicalRooms.single().retry)
        assertTrue(f.calls.indexOf("binding") < f.calls.indexOf("room"))
        assertFalse("resume" in f.calls)
        f.calls.clear()
        assertIs<SyncResult.Retry>(f.worker())
        assertFalse(f.calls.any { it in setOf("binding", "room", "adopt", "resume", "finish") })
    }

    @Test
    fun missingBindingIsDeferredWhileAnotherConversationCompletes() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.queuePhysical(0)
        assertIs<SyncResult.Retry>(f.worker())
        assertTrue(f.rooms.isEmpty())
        assertNotNull(f.physicalRooms.single().retry)
        assertEquals(1, f.deferredPhysical.size)
        assertFalse("resume" in f.calls || "finish" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun waitingForTheOriginalDeviceDefersPhysicalWorkWithoutAnImmediateLoop() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        val physical = f.queuePhysical(0)
        f.missingBindingOutcome =
            CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(listOf(physical.roomId), f.physicalAttempts)
        assertEquals(1, f.deferredPhysical.size)
        assertNotNull(f.physicalRooms.single().retry)
        assertNotNull(f.wake)
        assertFalse(f.calls.any { it in setOf("provider", "content", "resume", "finish") })
        f.calls.clear()
        assertIs<SyncResult.Retry>(f.worker())
        assertFalse(f.calls.any { it in setOf("binding", "adopt", "provider", "resume", "finish") })
    }

    @Test
    fun foreignOriginalWorkRemainsPendingWithoutImmediateContinuationOrProviderCalls() = runTest {
        for (physical in listOf(false, true)) {
            val f = SpaceIncomingWorkerFixture(testScheduler, 0)
            val other = f.source.copy(storeId = "2".repeat(64))
            val captured = f.queuePhysical(0, recoveryId(5000), other)
            if (!physical) {
                f.physicalRooms.clear()
                f.rooms +=
                    CollaborationSpaceIncomingRoomDemand(
                        recoveryId(5000),
                        captured.generation,
                        captured.position,
                        captured.headEventId,
                        CollaborationSpaceIncomingPhase.Latest,
                        sourceId = other.storeId,
                    )
            }
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(1, f.calls.count { it == "adopt" })
            assertTrue(f.attempted.isEmpty() && f.physicalAttempts.isEmpty())
            assertFalse("resume" in f.calls || "finish" in f.calls || "provider" in f.calls)
            assertNotNull(f.wake)
        }
    }

    @Test
    fun actualSourceAfterAdoptionDeterminesWhichCapturedOriginalCanRun() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.queuePhysical(0, recoveryId(5000))
        val other = f.source.copy(storeId = "2".repeat(64))
        val selected = f.queuePhysical(1, recoveryId(5001), other)
        f.before = { if (it == "adopt") f.installed = other }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(selected.roomId), f.physicalAttempts)
        assertEquals(f.source.storeId, f.physicalRooms.single().sourceId)
        assertEquals(other.storeId, f.rooms.single().sourceId)
    }

    @Test
    fun normalQueueContentionRemainsAShortRetryAndDoesNotBlockOrDeferWork() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        val original = f.queuePhysical(0, recoveryId(5000))
        f.bindingFailure = collaborationSpaceIncomingQueueContention().failure
        val result = assertIs<SyncResult.Retry>(f.worker())
        assertEquals(f.bindingFailure, result.failure)
        assertEquals(1L, assertNotNull(result.failure).retryAfterSeconds)
        assertEquals(listOf(original), f.physicalRooms)
        assertTrue(f.deferredPhysical.isEmpty())
        assertFalse("provider" in f.calls || "resume" in f.calls || "finish" in f.calls)
    }

    @Test
    fun newPhysicalGenerationArrivingDuringConversionGetsItsOwnLaterTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.queuePhysical(0, recoveryId(5000))
        f.before = {
            if (it == "binding") {
                f.physicalRooms[0] =
                    f.physicalRooms[0].copy(generation = recoveryId(6000), headEventId = "\$new")
                f.sources[0] = f.sources[0].copy(position = "later")
            }
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.rooms.isEmpty())
        assertEquals(recoveryId(6000), f.physicalRooms.single().generation)
        assertEquals("later", f.sources.single().position)
        assertEquals(1, f.physicalAttempts.size)
        assertTrue(f.deferredPhysical.isEmpty())
        assertEquals(1, f.calls.count { it == "resume" })
    }

    @Test
    fun confirmedPhysicalDenialStopsBeforeDeferralAndFollowingTasks() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        repeat(2) { f.queuePhysical(it, recoveryId(5000 + it)) }
        f.bindingFailure = Failure(FailureKind.AccessDenied, "Fixture denial")
        assertIs<SyncResult.Blocked>(f.worker())
        assertEquals(1, f.physicalAttempts.size)
        assertFalse("defer-physical" in f.calls || "resume" in f.calls)
        assertEquals(2, f.physicalRooms.size)
    }

    @Test
    fun parentReplacementCancelsPendingPhysicalResolutionAndKeepsItsWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.queuePhysical(0, recoveryId(5000))
        val started = CompletableDeferred<Unit>()
        var stopped = false
        f.before = {
            if (it == "binding") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        val result = async { f.worker() }
        started.await()
        f.base.states.value = f.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(result.await())
        assertTrue(stopped)
        assertFalse("defer-physical" in f.calls || "provider" in f.calls)
        assertEquals(1, f.physicalRooms.size)
        assertNotNull(f.wake)
    }

    @Test
    fun concurrentPhysicalTriggersStillHaveOnlyOneProcessingOwner() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.queuePhysical(0, recoveryId(5000))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "binding") {
                started.complete(Unit)
                release.await()
            }
        }
        val first = async { f.worker() }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { f.worker() }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.physicalAttempts.size)
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.physicalAttempts.size)
        assertEquals(1, f.calls.count { it == "provider" })
        assertNull(f.wake)
    }

    @Test
    fun physicalTimeoutHasOneBoundedAttemptAndKeepsTheTaskRecoverable() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.queuePhysical(0, recoveryId(5000))
        f.before = { if (it == "binding") awaitCancellation() }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(40_000L, currentTime)
        assertEquals(1, f.physicalAttempts.size)
        assertNotNull(f.physicalRooms.single().retry)
        assertFalse("resume" in f.calls)
    }
}
