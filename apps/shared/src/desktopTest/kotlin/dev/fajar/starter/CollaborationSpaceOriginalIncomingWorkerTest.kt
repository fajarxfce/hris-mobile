@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceOriginalIncomingTurnFixture
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceOriginalIncomingWorkerTest {
    @Test
    fun originalContentRunsInsideTheExistingWorkerAndWakeCompletionRemainsSeparate() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        assertEquals(SyncResult.Complete, f.worker.worker())
        assertEquals(1, f.worker.calls.count { it == "original-room" })
        assertFalse(f.worker.calls.any { it in setOf("room", "provider", "content") })
        assertEquals(1, f.providerReads)
        assertEquals(1, f.contentRequests.size)
        assertNull(f.current)
        assertNull(f.worker.wake)
        assertEquals(1, f.worker.calls.count { it == "finish" })
    }

    @Test
    fun providerProgressSchedulesALaterBoundedTurnWithoutRoomBackoff() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        f.providerMore = true
        assertEquals(SyncResult.Complete, f.worker.worker())
        assertEquals(CollaborationSpaceIncomingPhase.Provider, f.current?.phase)
        assertNull(f.current?.retry)
        assertNotNull(f.worker.wake)
        assertEquals(1, f.providerReads)
        assertEquals(1, f.worker.calls.count { it == "resume" })
        val yielded = f.worker.deferredCaptures.single()
        assertEquals(CollaborationSpaceIncomingPhase.Provider, yielded.phase)
        assertEquals(f.captured.sourceId, yielded.sourceId)
        assertNull(f.worker.deferred.single().second)
        assertTrue(assertNotNull(f.current).position > f.captured.position)
        assertNull(assertNotNull(f.current).retry)
        assertEquals(listOf(listOf("$" + "remote")), f.contentRequests)
        assertEquals(1, f.completionSources.size)
        assertFalse(assertNotNull(f.progress).exhausted)
        assertFalse(f.calls.any { it in setOf("complete-room", "demote-room", "recover") })
    }

    @Test
    fun missingKeysKeepOriginalIdentityAndWaitWithoutAnImmediateProcessingLoop() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        f.completeResult = false
        f.retryRequired = true
        assertIs<SyncResult.Retry>(f.worker.worker())
        val deferred = f.worker.deferredCaptures.single()
        assertEquals(f.captured.target, deferred.target)
        assertEquals(f.captured.sourceId, deferred.sourceId)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.current?.phase)
        assertNotNull(f.current?.retry)
        assertEquals(0, f.providerReads)
        assertNotNull(f.worker.wake)
        f.worker.calls.clear()
        assertIs<SyncResult.Retry>(f.worker.worker())
        assertFalse(
            f.worker.calls.any { it in setOf("adopt", "original-room", "resume", "finish") }
        )
    }

    @Test
    fun parentReplacementCancelsAndJoinsPendingOriginalContent() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.before = {
            if (it == "events")
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
        }
        val running = async { f.worker.worker() }
        entered.await()
        f.worker.base.states.value = f.worker.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(running.await())
        assertTrue(stopped)
        assertNotNull(f.current)
        assertNotNull(f.worker.wake)
        assertTrue(f.worker.deferredCaptures.isEmpty())
        assertFalse("complete-references" in f.calls)
    }

    @Test
    fun stalledOriginalIoUsesTheWorkersExistingFortySecondRoomDeadline() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        var stopped = false
        f.before = {
            if (it == "provider")
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
        }
        val started = testScheduler.currentTime
        assertIs<SyncResult.Retry>(f.worker.worker())
        assertEquals(40_000L, testScheduler.currentTime - started)
        assertTrue(stopped)
        assertNotNull(f.current?.retry)
        assertNotNull(f.worker.wake)
        assertEquals(1, f.providerReads)
        assertTrue(f.contentRequests.isEmpty())
    }

    @Test
    fun concurrentTriggersShareTheSameOriginalProcessingMutex() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "provider") {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = async { f.worker.worker() }
        entered.await()
        val second = async { f.worker.worker() }
        runCurrent()
        assertEquals(1, f.providerReads)
        assertEquals(1, f.worker.calls.count { it == "original-room" })
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.providerReads)
        assertNull(f.current)
    }

    @Test
    fun originalAccessDenialBlocksBeforeQueueDeferralOrWakeRetirement() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        f.failures["events"] = Failure(FailureKind.AccessDenied, "Fixture denial")
        assertIs<SyncResult.Blocked>(f.worker.worker())
        assertNotNull(f.current)
        assertNotNull(f.worker.wake)
        assertTrue(f.worker.deferredCaptures.isEmpty())
        assertFalse("finish" in f.worker.calls || "resume" in f.worker.calls)
    }

    @Test
    fun aLostProviderReplyDefersItsActualCommittedPhaseAndOriginalSource() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.worker.originalTurn = { runtime, captured -> f.turn(runtime, captured) }
        f.failures["advanced"] = f.failure
        assertIs<SyncResult.Retry>(f.worker.worker())
        val deferred = f.worker.deferredCaptures.single()
        assertEquals(CollaborationSpaceIncomingPhase.Pending, deferred.phase)
        assertEquals(f.captured.target, deferred.target)
        assertEquals(f.captured.sourceId, deferred.sourceId)
        assertNotNull(f.current?.retry)
        assertTrue(f.contentRequests.isEmpty())
        assertEquals(1, f.providerReads)
    }
}
