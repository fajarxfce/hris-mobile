@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceIncomingWorkerFixture
import dev.fajar.starter.sync.domain.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceIncomingWorkerTest {
    @Test
    fun logicalWriteContentionRetriesWithoutPersistingRoomBackoffOrSchedulingALoop() = runTest {
        for (duringAdvance in listOf(false, true)) {
            val f = SpaceIncomingWorkerFixture(testScheduler)
            val original = f.rooms.toList()
            val wake = f.wake
            val busy = collaborationSpaceIncomingQueueContention().failure
            if (duringAdvance) f.advanceFailure = busy else f.pageFailure = busy
            assertEquals(SyncResult.Retry(busy), f.worker())
            assertEquals(original, f.rooms)
            assertEquals(wake, f.wake)
            assertEquals(1, f.calls.count { it == "provider" })
            assertTrue(f.deferred.isEmpty())
            assertFalse("resume" in f.calls || "finish" in f.calls)
        }
    }

    @Test
    fun absentRequestDoesNotAcquireADeviceOrProvider() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.wake = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(listOf("wake"), f.calls)
    }

    @Test
    fun scheduledTurnSeedsDiscoveryEvenWithoutAnEarlierDemandDocument() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.wake = null
        f.present = false
        assertEquals(SyncResult.Complete, f.worker(SyncTrigger.Scheduled))
        assertEquals(1, f.calls.count { it == "scan-request" })
        assertTrue(f.calls.indexOf("scan-request") < f.calls.indexOf("discover"))
        assertFalse("adopt" in f.calls)
        assertNull(f.wake)
    }

    @Test
    fun missingNativeRootDoesNotAcknowledgeAnotherScopesWake() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        val original = f.wake
        f.original = null
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(original, f.wake)
        assertFalse("finish" in f.calls)
        assertFalse("adopt" in f.calls)
    }

    @Test
    fun idleOwnedDemandCanFinishWithoutOpeningAnotherRuntime() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        assertEquals(SyncResult.Complete, f.worker())
        assertNull(f.wake)
        assertFalse("adopt" in f.calls)
        assertFalse("provider" in f.calls)
    }

    @Test
    fun oneTurnProcessesOnlyFourCapturedRoomsThenSchedulesTheRest() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 6)
        val original =
            f.rooms.map {
                assertIs<CollaborationSpaceIncomingTarget.Published>(it.target).conversationId
            }
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(original.take(4), f.attempted)
        assertEquals(
            original.drop(4),
            f.rooms.map {
                assertIs<CollaborationSpaceIncomingTarget.Published>(it.target).conversationId
            },
        )
        assertEquals(1, f.calls.count { it == "resume" })
        assertFalse("finish" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun contentIsRetainedBeforeTheProviderFinishesOlderMetadata() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.providerMore = true
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "provider" })
        assertEquals(1, f.calls.count { it == "content" })
        assertEquals(1, f.calls.count { it == "retain" })
        assertTrue(f.calls.indexOf("retain") < f.calls.indexOf("defer"))
        assertEquals(CollaborationSpaceIncomingPhase.Provider, f.rooms.single().phase)
        assertNull(f.rooms.single().retry)
        assertFalse("complete" in f.calls)
    }

    @Test
    fun savedPendingPhaseAcquiresContentWithoutRestartingMetadata() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        assertEquals(SyncResult.Complete, f.worker())
        assertFalse("provider" in f.calls)
        assertEquals(1, f.calls.count { it == "content" })
        assertNull(f.wake)
    }

    @Test
    fun missingKeysInOneRoomDoNotBlockLaterRoomsInTheSameTurn() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 2)
        val first =
            assertIs<CollaborationSpaceIncomingTarget.Published>(f.rooms.first().target)
                .conversationId
        f.before = { if (it == "content") f.missingKeys = f.attempted.last() == first }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(2, f.attempted.size)
        assertEquals(
            first,
            assertIs<CollaborationSpaceIncomingTarget.Published>(f.rooms.single().target)
                .conversationId,
        )
        assertNotNull(f.rooms.single().retry)
        assertEquals(1, f.calls.count { it == "defer" })
        assertFalse("resume" in f.calls)
    }

    @Test
    fun missingKeysDoNotDelayTheRestOfAFinitePendingPass() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.referencesMore = true
        f.missingKeys = true
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "content" })
        assertNull(f.rooms.single().retry)
        assertEquals(1, f.calls.count { it == "resume" })
        assertFalse("recover" in f.calls)
    }

    @Test
    fun unseenKnownHeadRetainsOneDelayedRecheckInsteadOfSpinningAtEof() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.hiddenHead = true
        val worker = f.worker
        assertIs<SyncResult.Retry>(worker())
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.rooms.single().phase)
        assertNotNull(f.rooms.single().retry)
        assertEquals(1, f.calls.count { it == "recover" })
        assertFalse("resume" in f.calls)
        f.calls.clear()
        assertIs<SyncResult.Retry>(worker())
        assertFalse("adopt" in f.calls)
        assertFalse("provider" in f.calls)
        assertFalse("recover" in f.calls)
    }

    @Test
    fun confirmedDenialStopsTheTurnWithoutMutatingRetryOrFollowingRooms() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 2)
        f.pageFailure = Failure(FailureKind.AccessDenied, "fixture")
        assertIs<SyncResult.Blocked>(f.worker())
        assertEquals(1, f.attempted.size)
        assertTrue(f.deferred.isEmpty())
        assertEquals(2, f.rooms.size)
        assertFalse("resume" in f.calls)
    }

    @Test
    fun parentReplacementCancelsProviderWorkAndPreventsLateRetention() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        val started = CompletableDeferred<Unit>()
        f.before = {
            if (it == "provider") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val result = async { f.worker() }
        started.await()
        f.base.states.value = f.base.publication(owner = null)
        assertIs<SyncResult.Blocked>(result.await())
        assertFalse("advance" in f.calls)
        assertFalse("retain" in f.calls)
        assertFalse("defer" in f.calls)
        assertNotNull(f.wake)
    }

    @Test
    fun stalledRoomGetsOneBoundedAttemptAndLetsTheNextRoomProceed() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 2)
        val first =
            assertIs<CollaborationSpaceIncomingTarget.Published>(f.rooms.first().target)
                .conversationId
        f.before = { if (it == "provider" && f.attempted.last() == first) awaitCancellation() }
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(40_000L, testScheduler.currentTime)
        assertEquals(2, f.attempted.size)
        assertEquals(
            first,
            assertIs<CollaborationSpaceIncomingTarget.Published>(f.rooms.single().target)
                .conversationId,
        )
        assertEquals(FailureKind.Timeout, f.deferred.single().second!!.kind)
    }

    @Test
    fun cancelledInvocationReleasesItsAdmissionSlotAndSerializationLock() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        val worker = f.worker
        val started = CompletableDeferred<Unit>()
        f.before = {
            if (it == "provider") {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { worker() }
        started.await()
        job.cancelAndJoin()
        assertNotNull(f.wake)
        f.before = {}
        assertEquals(SyncResult.Complete, worker())
        assertNull(f.wake)
    }

    @Test
    fun continuationSchedulingFailureRetainsRemainingRoomWork() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 5)
        f.resumeFailure = Failure(FailureKind.Storage, "fixture")
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(1, f.rooms.size)
        assertNotNull(f.wake)
        assertEquals(1, f.calls.count { it == "resume" })
    }

    @Test
    fun changedFinalWakeGetsOneContinuationWithoutRepeatedAcknowledgement() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler, 0)
        f.finishResult = false
        assertEquals(SyncResult.Complete, f.worker())
        assertEquals(1, f.calls.count { it == "finish" })
        assertEquals(1, f.calls.count { it == "resume" })
        assertNotNull(f.wake)
    }

    @Test
    fun concurrentTriggersHaveOneRunningAndOneWaitingAdmissionOnly() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        val worker = f.worker
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "provider") {
                started.complete(Unit)
                release.await()
            }
        }
        val first = async { worker() }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { worker() }
        assertIs<SyncResult.Retry>(worker())
        assertEquals(1, f.calls.count { it == "provider" })
        release.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, f.calls.count { it == "provider" })
    }

    @Test
    fun desktopAndBrowserDoNotOpenNativeRuntimeOrCreateWork() = runTest {
        for (platform in listOf(AppPlatform.Desktop, AppPlatform.Web)) {
            val f = SpaceIncomingWorkerFixture(testScheduler, platform = platform)
            assertEquals(SyncResult.Complete, f.worker(SyncTrigger.Scheduled))
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun pendingRoomWithNoCurrentDeviceProgressStartsAFreshTraversal() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.missingProgress = true
        val worker = f.worker
        assertEquals(SyncResult.Complete, worker())
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.rooms.single().phase)
        assertNull(f.rooms.single().retry)
        assertFalse("content" in f.calls)
        assertFalse("retain" in f.calls)
        assertEquals(1, f.calls.count { it == "recover" })
        assertNotNull(f.wake)
        f.calls.clear()
        assertEquals(SyncResult.Complete, worker())
        assertEquals(1, f.calls.count { it == "provider" })
        assertEquals(1, f.calls.count { it == "content" })
        assertNull(f.wake)
    }

    @Test
    fun failedCurrentHistoryReadKeepsItsPhaseInsteadOfRestartingMetadata() = runTest {
        val f = SpaceIncomingWorkerFixture(testScheduler)
        f.rooms[0] = f.rooms[0].copy(phase = CollaborationSpaceIncomingPhase.Pending)
        f.referencesFailure = Failure(FailureKind.Storage, "fixture")
        assertIs<SyncResult.Retry>(f.worker())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.rooms.single().phase)
        assertNotNull(f.rooms.single().retry)
        assertFalse("recover" in f.calls)
        assertFalse("provider" in f.calls)
        assertNotNull(f.wake)
    }
}
