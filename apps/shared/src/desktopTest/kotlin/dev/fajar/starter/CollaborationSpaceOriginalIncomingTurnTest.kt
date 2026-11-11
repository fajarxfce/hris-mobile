@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.SpaceOriginalIncomingTurnFixture
import dev.fajar.starter.fixtures.recoveryId
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceOriginalIncomingTurnTest {
    @Test
    fun providerCompletionReloadsThePersistedPendingCaptureBeforeAcquiringOnlyUnresolvedEvents() =
        runTest {
            val f = SpaceOriginalIncomingTurnFixture(testScheduler)
            assertEquals(AppResult.Success(true), f.run())
            assertEquals(1, f.providerReads)
            assertEquals(2, f.roomCaptures.size)
            assertEquals(CollaborationSpaceIncomingPhase.Latest, f.roomCaptures.first().phase)
            assertEquals(CollaborationSpaceIncomingPhase.Pending, f.roomCaptures.last().phase)
            assertEquals(f.roomCaptures.last(), f.contentRooms.single().captured)
            assertEquals(listOf(listOf("$" + "remote")), f.contentRequests)
            assertEquals(f.position, f.contentRooms.single().conversation.conversation.position)
            assertEquals(f.physicalDevice, f.contentRooms.single().traversal.deviceId)
            assertNull(f.current)
            assertTrue(f.calls.indexOf("advanced") < f.calls.indexOf("demand"))
            assertTrue(f.calls.indexOf("reconcile") < f.calls.indexOf("references-2"))
            assertTrue(f.calls.indexOf("complete-references") < f.calls.indexOf("complete-room"))
        }

    @Test
    fun pendingKeyRecoveryNeverStartsProviderPagination() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(0, f.providerReads)
        assertFalse("advance" in f.calls || "demand" in f.calls)
        assertEquals(listOf(listOf("$" + "remote")), f.contentRequests)
    }

    @Test
    fun whollyRetainedContentNeedsNoEventRequest() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.localEventIds = f.references.map { it.event.eventId }.toSet()
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.contentRequests.isEmpty())
        assertTrue(f.completionSources.single().events.isEmpty())
        assertEquals(0, f.providerReads)
    }

    @Test
    fun incompleteProviderPageFinishesOneTurnWithoutTreatingItAsMissingKeys() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.providerMore = true
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Provider, f.current?.phase)
        assertEquals(1, f.providerReads)
        assertTrue(f.contentRequests.isEmpty())
        assertEquals(0, f.referenceReads)
        assertFalse("complete-room" in f.calls || "recover" in f.calls)
    }

    @Test
    fun obsoleteLocalCaptureStopsBeforeProviderAndContentWork() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.current = null
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(listOf("room"), f.calls)
        assertEquals(0, f.providerReads)
    }

    @Test
    fun obsoleteProviderTransitionDoesNotInventAPendingTask() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.advanceResult = false
        f.before = { if (it == "advance") f.current = null }
        assertEquals(AppResult.Success(true), f.run())
        assertFalse("demand" in f.calls || "complete-room" in f.calls)
        assertTrue(f.contentRequests.isEmpty())
    }

    @Test
    fun aNewerTaskAfterProviderCommitKeepsItsOwnTurn() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.before = {
            if (it == "advanced") f.current = f.current?.copy(generation = recoveryId(990))
        }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(recoveryId(990), f.current?.generation)
        assertEquals(1, f.roomCaptures.size)
        assertEquals(0, f.referenceReads)
    }

    @Test
    fun lostProviderTransitionReplyReopensItsActualPendingPhaseWithoutAnotherPage() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.failures["advanced"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        val pending = assertNotNull(f.current)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, pending.phase)
        assertTrue(f.contentRequests.isEmpty())
        f.failures.clear()
        assertEquals(AppResult.Success(true), f.run(pending))
        assertEquals(1, f.providerReads)
    }

    @Test
    fun providerFailureCannotAdvanceOrRetireTheCapturedTask() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.failures["provider"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertEquals(f.captured, f.current)
        assertFalse("advance" in f.calls || "complete-room" in f.calls)
    }

    @Test
    fun missingProgressUsesItsRecoveryOwnerWithoutTryingEventContent() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.progress = null
        f.recoveryResult = true
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.current?.phase)
        assertEquals(0, f.providerReads)
        assertTrue(f.contentRequests.isEmpty())
        assertFalse("reconcile" in f.calls)
    }

    @Test
    fun missingProgressOnTheSecondReadStillCannotGrantContentAcquisition() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.before = { if (it == "references-2") f.progress = null }
        f.recoveryResult = true
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.contentRequests.isEmpty())
        assertTrue("recover" in f.calls)
        assertFalse("complete-references" in f.calls)
    }

    @Test
    fun obsoleteReferenceReconciliationOrCompletionDoesNotRetireAnotherTask() = runTest {
        for (stage in listOf("references-1", "reconcile", "references-2", "complete-references")) {
            val f =
                SpaceOriginalIncomingTurnFixture(
                    testScheduler,
                    CollaborationSpaceIncomingPhase.Pending,
                )
            f.nullAt = stage
            assertEquals(AppResult.Success(true), f.run(), stage)
            assertFalse("complete-room" in f.calls, stage)
            assertNotNull(f.current)
        }
    }

    @Test
    fun eventFailureCannotCompleteTheFiniteSeekOrQueue() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.failures["events"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertNotNull(f.current)
        assertFalse("complete-references" in f.calls || "complete-room" in f.calls)
    }

    @Test
    fun uncertainReferenceCompletionCannotFallThroughToQueueRetirement() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.failures["complete-references"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertNotNull(f.current)
        assertFalse("complete-room" in f.calls || "recover" in f.calls)
    }

    @Test
    fun queueCompletionFailureIsPreservedWithoutStartingAnotherRecoveryPath() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.failures["complete-room"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertNotNull(f.current)
        assertFalse("recover" in f.calls)
    }

    @Test
    fun anotherFiniteReferencePageCanContinueEvenWhenThisPageHadMissingKeys() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.completeResult = false
        f.moreReferences = true
        f.retryRequired = true
        assertEquals(AppResult.Success(true), f.run())
        assertNotNull(f.current)
        assertFalse("recover" in f.calls)
        assertEquals(0, f.providerReads)
        assertEquals(1, f.contentRequests.size)
    }

    @Test
    fun missingKeysAtTheEndOfAFinitePassRequireDeferralWithoutProviderRestart() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.completeResult = false
        f.retryRequired = true
        val failure = assertIs<AppResult.Failed>(f.run()).failure
        assertEquals("client.collaboration_space_incoming_keys_pending", failure.code)
        assertEquals(FailureKind.Unavailable, failure.kind)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.current?.phase)
        assertFalse("recover" in f.calls)
        assertEquals(0, f.providerReads)
    }

    @Test
    fun providerRecoveryAfterCleanContentOwnsTheNextPhase() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.completeResult = false
        f.recoveryResult = true
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.current?.phase)
        assertEquals(1, f.calls.count { it == "recover" })
        assertEquals(0, f.providerReads)
    }

    @Test
    fun unchangedAmbiguousProgressCannotCauseAnImmediateEmptyWorkLoop() = runTest {
        val f =
            SpaceOriginalIncomingTurnFixture(testScheduler, CollaborationSpaceIncomingPhase.Pending)
        f.completeResult = false
        assertEquals(
            "client.collaboration_space_incoming_source_pending",
            assertIs<AppResult.Failed>(f.run()).failure.code,
        )
        assertNotNull(f.current)
        assertEquals(1, f.calls.count { it == "recover" })
        assertEquals(0, f.providerReads)
    }

    @Test
    fun unreadableCurrentDemandAfterACommittedPhaseDoesNotUseAFabricatedCapture() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        f.failures["demand"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.current?.phase)
        assertEquals(1, f.roomCaptures.size)
        assertTrue(f.contentRequests.isEmpty())
    }

    @Test
    fun cancellationReleasesEachSuspendedProviderContentAndLocalStage() = runTest {
        for (stage in
            listOf(
                "provider",
                "advance",
                "demand",
                "references-1",
                "reconcile",
                "references-2",
                "events",
                "complete-references",
                "complete-room",
            )) {
            val f = SpaceOriginalIncomingTurnFixture(testScheduler)
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.before = {
                if (it == stage)
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
            }
            val job = async { f.run() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped, stage)
            assertNotNull(f.current)
        }
    }

    @Test
    fun aPublishedTaskCannotEnterTheOriginalAdapter() = runTest {
        val f = SpaceOriginalIncomingTurnFixture(testScheduler)
        val published =
            f.captured.copy(target = CollaborationSpaceIncomingTarget.Published(recoveryId(999)))
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.run(published)).failure.kind,
        )
        assertTrue(f.calls.isEmpty())
    }
}
