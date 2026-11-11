@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceIncomingWorkerFixture
import dev.fajar.starter.fixtures.recoveryId
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIncomingFairnessTest {
    @Test
    fun longOriginalProviderHistoriesYieldToEveryExistingConversationWithoutBackoff() = runTest {
        val f = originalFixture(testScheduler, 6)
        val visits = mutableListOf<String>()
        providerPages(f, visits)
        val wake = f.wake
        repeat(2) { assertEquals(SyncResult.Complete, f.worker()) }
        assertEquals(
            (0 until 6).map { recoveryId(1000 + it) } + listOf(recoveryId(1000), recoveryId(1001)),
            visits,
        )
        assertEquals(6, f.rooms.size)
        assertTrue(
            f.rooms.all { it.phase == CollaborationSpaceIncomingPhase.Provider && it.retry == null }
        )
        assertTrue(f.deferred.all { it.second == null })
        assertTrue(f.deferredCaptures.all { it.phase == CollaborationSpaceIncomingPhase.Provider })
        assertEquals(wake, f.wake)
        assertEquals(2, f.calls.count { it == "resume" })
        assertFalse("finish" in f.calls)
    }

    @Test
    fun manyPendingReferencePagesDoNotKeepTheSameFourConversationsAtTheHead() = runTest {
        val f = originalFixture(testScheduler, 7, CollaborationSpaceIncomingPhase.Pending)
        val visits = mutableListOf<String>()
        f.originalTurn = { _, captured ->
            visits += conversationId(captured)
            AppResult.Success(false)
        }
        repeat(2) { assertEquals(SyncResult.Complete, f.worker()) }
        assertEquals((0 until 7).map { recoveryId(1000 + it) }, visits.take(7))
        assertTrue(
            f.rooms.all { it.phase == CollaborationSpaceIncomingPhase.Pending && it.retry == null }
        )
        assertEquals(8, f.deferredCaptures.size)
        assertTrue(f.deferred.all { it.second == null })
        assertFalse("provider" in f.calls || "recover" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun continuousNewRoomDemandDoesNotStarveAlreadyRetainedProviderWork() = runTest {
        val f = originalFixture(testScheduler, 8)
        val visits = mutableListOf<String>()
        providerPages(f, visits)
        repeat(8) { index ->
            assertEquals(SyncResult.Complete, f.worker())
            // A separate producer has retained a new task at the shared FIFO tail.
            f.rooms += originalRoom(f, index + 8, f.nextPosition++)
        }
        assertEquals((0 until 8).map { recoveryId(1000 + it) }, visits.take(8))
        assertTrue(
            (0 until 8).all { index -> visits.count { it == recoveryId(1000 + index) } >= 2 }
        )
        assertEquals(16, f.rooms.size)
        assertTrue(f.rooms.all { it.retry == null })
        assertTrue(f.deferred.all { it.second == null })
        assertNotNull(f.wake)
    }

    @Test
    fun inventoryGetsOneTurnBeforeSlowPrimaryWorkReachesTheWorkerDeadline() = runTest {
        val f = originalFixture(testScheduler, 6)
        val visits = mutableListOf<String>()
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6500))
        var inventoryAt: Long? = null
        f.inventoryTurn = { _, captured ->
            inventoryAt = currentTime
            f.sources[0] = captured.copy(inventoryGeneration = null)
            AppResult.Success(CollaborationSpaceInventoryOutcome.Complete)
        }
        providerPages(f, visits, durationMillis = 35_000)
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(90_000L, currentTime)
        assertEquals(0L, inventoryAt)
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(2, f.deferredCaptures.size)
        assertTrue(f.deferred.all { it.second == null })
        assertEquals(2L, f.rooms.single { conversationId(it) == recoveryId(1002) }.position)
        providerPages(f, visits)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals((2 until 6).map { recoveryId(1000 + it) }, visits.takeLast(4))
        assertEquals(1, f.inventoryAttempts.size)
        assertNotNull(f.wake)
    }

    @Test
    fun cancellationDuringPrimaryWorkKeepsCompletedInventoryAndUnfinishedRoomDemand() = runTest {
        val f = originalFixture(testScheduler, 1)
        val wake = f.wake
        val original = f.rooms.toList()
        val entered = CompletableDeferred<Unit>()
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6501))
        f.inventoryTurn = { _, captured ->
            f.sources[0] = captured.copy(inventoryGeneration = null)
            AppResult.Success(CollaborationSpaceInventoryOutcome.Complete)
        }
        f.originalTurn = { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.worker() }
        runCurrent()
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(1, f.inventoryAttempts.size)
        assertNull(f.sources.single().inventoryGeneration)
        assertEquals(original, f.rooms)
        assertEquals(wake, f.wake)
        assertFalse("finish" in f.calls)
    }

    @Test
    fun inventoryBackoffDoesNotDelaySuccessfulPrimaryPagesOrRepeatOnEveryWake() = runTest {
        val f = originalFixture(testScheduler, 4)
        val visits = mutableListOf<String>()
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6502))
        f.inventoryTurn = { _, _ -> AppResult.Failed(Failure(FailureKind.Unavailable, "fixture")) }
        providerPages(f, visits)
        assertEquals(SyncResult.Complete, f.worker())
        val retry = assertNotNull(f.sources.single().inventoryRetry)
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(retry, f.sources.single().inventoryRetry)
        assertEquals(1, f.inventoryAttempts.size)
        assertEquals(8, visits.size)
        assertTrue(f.rooms.all { it.retry == null })
        assertTrue(f.deferred.all { it.second == null })
    }

    @Test
    fun inventoryAdmissionDenialStopsBeforePrimaryExecutionAndKeepsAllDemand() = runTest {
        val f = originalFixture(testScheduler, 4)
        val visits = mutableListOf<String>()
        f.sources[0] = f.sources.single().copy(inventoryGeneration = recoveryId(6503))
        val original = f.work().snapshot
        f.inventoryTurn = { _, _ -> AppResult.Failed(Failure(FailureKind.AccessDenied, "fixture")) }
        providerPages(f, visits)
        assertIs<SyncResult.Blocked>(f.worker())
        assertEquals(original, f.work().snapshot)
        assertTrue(visits.isEmpty())
        assertTrue(f.deferredInventories.isEmpty())
        assertNotNull(f.wake)
        assertFalse("resume" in f.calls || "finish" in f.calls)
    }

    @Test
    fun genuineRoomFailuresBackOffIndependentlyFromSuccessfulPageContinuation() = runTest {
        val f = originalFixture(testScheduler, 4)
        val visits = mutableListOf<String>()
        f.originalTurn = { _, captured ->
            visits += conversationId(captured)
            if (conversationId(captured) == recoveryId(1001))
                AppResult.Failed(Failure(FailureKind.Unavailable, "fixture"))
            else {
                retainProviderPage(f, captured)
                AppResult.Success(false)
            }
        }
        assertEquals(SyncResult.Complete, f.worker())
        val delayed = f.rooms.single { conversationId(it) == recoveryId(1001) }
        assertNotNull(delayed.retry)
        assertTrue(f.rooms.filterNot { it == delayed }.all { it.retry == null })
        assertEquals(1, f.deferred.count { it.second != null })
        visits.clear()
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(1000, 1002, 1003).map(::recoveryId), visits)
        assertEquals(delayed, f.rooms.single { conversationId(it) == recoveryId(1001) })
    }

    private fun originalFixture(
        scheduler: TestCoroutineScheduler,
        count: Int,
        phase: CollaborationSpaceIncomingPhase = CollaborationSpaceIncomingPhase.Latest,
    ): SpaceIncomingWorkerFixture {
        val f = SpaceIncomingWorkerFixture(scheduler, count)
        f.rooms.clear()
        repeat(count) { f.rooms += originalRoom(f, it, it.toLong()).copy(phase = phase) }
        return f
    }

    private fun originalRoom(f: SpaceIncomingWorkerFixture, index: Int, position: Long) =
        CollaborationSpaceIncomingRoomDemand(
            CollaborationSpaceIncomingTarget.Original(
                CollaborationOriginalConversationPosition(
                    recoveryId(8500),
                    recoveryId(1000 + index),
                )
            ),
            recoveryId(2000 + index),
            position,
            "\$head-$index",
            CollaborationSpaceIncomingPhase.Latest,
            sourceId = f.source.storeId,
        )

    private fun providerPages(
        f: SpaceIncomingWorkerFixture,
        visits: MutableList<String>,
        durationMillis: Long = 0,
    ) {
        f.originalTurn = { _, captured ->
            visits += conversationId(captured)
            delay(durationMillis)
            retainProviderPage(f, captured)
            AppResult.Success(false)
        }
    }

    private fun retainProviderPage(
        f: SpaceIncomingWorkerFixture,
        captured: CollaborationSpaceIncomingRoomDemand,
    ) {
        val next =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        assertNotNull(f.work().snapshot).demand,
                        CollaborationSpaceIncomingDemandChange.ProviderAcquired(captured, false),
                    )
                )
                .value
        f.rooms.clear()
        f.rooms += next.rooms
        f.nextPosition = next.nextPosition
    }

    private fun conversationId(room: CollaborationSpaceIncomingRoomDemand) =
        (room.target as CollaborationSpaceIncomingTarget.Original).position.conversationId
}
