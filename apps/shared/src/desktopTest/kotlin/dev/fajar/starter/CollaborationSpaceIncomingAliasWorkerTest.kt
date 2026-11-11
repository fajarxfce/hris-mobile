@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceIncomingWorkerFixture
import dev.fajar.starter.fixtures.recoveryId
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIncomingAliasWorkerTest {
    @Test
    fun aCanonicalHandoffSchedulesTheOriginalTurnWithoutPublishedProviderWork() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        val captured = f.rooms.single()
        val converted = original(captured, f.source.storeId)
        f.logicalRoute = {
            assertEquals(captured, it)
            f.rooms[0] = converted
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(converted), f.rooms)
        assertTrue(f.deferredCaptures.isEmpty())
        assertFalse(f.calls.any { it in setOf("room", "provider", "content", "original-room") })
        assertEquals(1, f.calls.count { it == "resume" })
        assertNotNull(f.wake)
        f.originalTurn = { _, task ->
            assertEquals(converted, task)
            f.rooms.remove(task)
            AppResult.Success(true)
        }
        f.calls.clear()
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "original-room" })
        assertFalse("logical-binding" in f.calls)
        assertNull(f.wake)
    }

    @Test
    fun aTruePublishedRoomContinuesOnlyAfterLogicalRouting() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        assertEquals(SyncResult.Complete, f.worker())
        assertTrue(f.calls.indexOf("logical-binding") < f.calls.indexOf("room"))
        assertTrue(f.calls.indexOf("room") < f.calls.indexOf("provider"))
        assertEquals(1, f.calls.count { it == "provider" })
        assertNull(f.wake)
    }

    @Test
    fun unresolvedAliasesUseExistingDeferralWithoutAnImmediateLoop() = runTest {
        for (outcome in
            listOf(
                CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource,
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
                CollaborationSpaceIncomingBindingResolution.BindingMissing,
            )) {
            val f = SpaceIncomingWorkerFixture(testScheduler, 1)
            val captured = f.rooms.single()
            f.logicalRoute = { AppResult.Success(outcome) }
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(captured, f.deferredCaptures.single())
            assertFalse(
                f.calls.any {
                    it in setOf("room", "provider", "content", "original-room", "finish")
                }
            )
            assertNotNull(f.wake)
            f.calls.clear()
            assertIs<SyncResult.Retry>(f.worker())
            assertFalse("logical-binding" in f.calls || "adopt" in f.calls)
        }
    }

    @Test
    fun routingDenialBlocksWithoutProviderWorkOrDeferral() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        f.logicalRoute = { AppResult.Failed(Failure(FailureKind.AccessDenied, "Fixture denial")) }
        assertIs<SyncResult.Blocked>(f.worker())
        assertTrue(f.deferredCaptures.isEmpty())
        assertFalse(f.calls.any { it in setOf("room", "provider", "content", "finish") })
        assertNotNull(f.wake)
    }

    @Test
    fun aLostRoutingReplyDoesNotDeferTheReplacedAliasOrItsNewOriginal() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        val converted = original(f.rooms.single(), f.source.storeId)
        f.logicalRoute = {
            f.rooms[0] = converted
            AppResult.Failed(Failure(FailureKind.Storage, "Fixture lost reply"))
        }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf(converted), f.rooms)
        assertTrue(f.deferredCaptures.isEmpty())
        assertEquals(1, f.calls.count { it == "resume" })
        assertNotNull(f.wake)
    }

    @Test
    fun parentReplacementCancelsAndJoinsSuspendedAliasRouting() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.logicalRoute = {
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
        assertTrue(f.deferredCaptures.isEmpty())
        assertFalse("provider" in f.calls || "room" in f.calls)
    }

    @Test
    fun logicalRoutingSharesTheExistingFortySecondRoomDeadline() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        var stopped = false
        f.logicalRoute = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val started = testScheduler.currentTime
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(40_000L, testScheduler.currentTime - started)
        assertTrue(stopped)
        assertEquals(1, f.deferredCaptures.size)
        assertFalse("provider" in f.calls)
    }

    @Test
    fun concurrentWorkerTriggersDoNotRouteTheSameAliasTwice() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 1)
        val converted = original(f.rooms.single(), f.source.storeId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.logicalRoute = {
            entered.complete(Unit)
            release.await()
            f.rooms[0] = converted
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued)
        }
        f.originalTurn = { _, task ->
            f.rooms.remove(task)
            AppResult.Success(true)
        }
        val first = async { f.worker() }
        entered.await()
        val second = async { f.worker() }
        runCurrent()
        assertEquals(1, f.calls.count { it == "logical-binding" })
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.calls.count { it == "logical-binding" })
        assertEquals(1, f.calls.count { it == "original-room" })
    }

    private fun original(captured: CollaborationSpaceIncomingRoomDemand, sourceId: String) =
        captured.copy(
            target =
                CollaborationSpaceIncomingTarget.Original(
                    CollaborationOriginalConversationPosition(recoveryId(950), recoveryId(951))
                ),
            generation = recoveryId(952),
            phase = CollaborationSpaceIncomingPhase.Latest,
            sourceId = sourceId,
        )
}
