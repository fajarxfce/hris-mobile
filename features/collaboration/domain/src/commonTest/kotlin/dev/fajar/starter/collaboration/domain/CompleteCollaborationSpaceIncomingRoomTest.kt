package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationSpaceIncomingRoomTest {
    @Test
    fun originalTargetCannotAcknowledgePublishedWorkWithTheSameConversationId() = runTest {
        val f = SpaceIncomingCompletionFixture()
        val target =
            CollaborationSpaceIncomingTarget.Original(
                CollaborationOriginalConversationPosition(
                    "10000000-0000-4000-8000-000000007100",
                    assertIs<CollaborationSpaceIncomingTarget.Published>(f.captured.target)
                        .conversationId,
                )
            )
        val captured = f.captured.copy(target = target, sourceId = "1".repeat(64))
        val before = f.request.snapshot
        val result = f.useCase(f.access, f.key, captured)
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(result).failure.kind)
        assertEquals(before, f.request.snapshot)
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(0, f.request.writes)
        assertTrue(f.request.base.calls.isEmpty())
    }

    @Test
    fun producerBeforeCasRetriesOnceAndAfterCommitConfirmsLostReply() = runTest {
        for (stage in listOf("write", "committed")) {
            val f = SpaceIncomingCompletionFixture()
            val request = f.request
            val wake = request.wake
            request.responseLoss = stage == "committed"
            request.base.hooks[stage] = { request.retainIndependentProducerWork() }
            val result = f.complete()
            if (stage == "write") {
                val failure = assertIs<AppResult.Failed>(result).failure
                assertTrue(isCollaborationSpaceIncomingQueueContention(failure))
                assertEquals(1L, failure.retryAfterSeconds)
            } else assertEquals(AppResult.Success(true), result)
            val retained = request.snapshot!!.demand
            assertEquals("later", retained.sources.single().position)
            assertNotNull(retained.sources.single().inventoryGeneration)
            assertEquals("!producer:test", retained.physicalRooms.single().roomId)
            assertEquals(wake, request.wake)
            assertEquals(1, request.writes)
            assertEquals(0, request.schedules)
        }
    }

    @Test
    fun cleanExactSourceCompletesOnlyTheCapturedRoomAndRetainsItsWake() = runTest {
        val f = SpaceIncomingCompletionFixture()
        assertEquals(AppResult.Success(true), f.complete())
        assertTrue(f.request.snapshot!!.demand.rooms.isEmpty())
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(0, f.request.schedules)
        assertEquals(
            listOf(
                "demand",
                "binding",
                "pending",
                "source",
                "progress",
                "write",
                "committed",
                "readback",
            ),
            f.request.base.calls,
        )
    }

    @Test
    fun providerContinuationPendingContentOrMissingProgressCannotComplete() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingCompletionFixture()
            when (mode) {
                0 -> f.request.covered(exhausted = false)
                1 -> f.request.covered(unresolved = true)
                else -> f.request.pending = CollaborationHistoryReferences(null, emptyList(), null)
            }
            val before = f.request.snapshot
            assertEquals(AppResult.Success(false), f.complete())
            assertEquals(before, f.request.snapshot)
            assertEquals(0, f.request.writes)
            assertFalse("source" in f.request.base.calls)
        }
    }

    @Test
    fun requestedHeadMustBeAnExactResolvedSourceAndNotAPaginationToken() = runTest {
        for (mode in 0..3) {
            val f = SpaceIncomingCompletionFixture()
            when (mode) {
                0 -> f.request.source = f.request.source.copy(reference = null)
                1 ->
                    f.request.source =
                        f.request.source.copy(
                            reference = f.request.source.reference!!.copy(resolved = false)
                        )
                2 ->
                    f.request.source =
                        f.request.source.copy(
                            reference =
                                f.request.source.reference!!.copy(
                                    event =
                                        f.request.source.reference!!.event.copy(eventId = "\$other")
                                )
                        )
                else ->
                    f.request.source =
                        f.request.source.copy(
                            progress = f.request.source.progress!!.copy(revision = 9)
                        )
            }
            val result = f.complete()
            if (mode == 0) assertEquals(AppResult.Success(false), result)
            else assertIs<AppResult.Failed>(result)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun unknownStartupHeadStillRequiresRealProviderExhaustionAndNoPendingSources() = runTest {
        val f = SpaceIncomingCompletionFixture()
        val captured = f.captured.copy(headEventId = null)
        f.request.snapshot =
            f.request.snapshot!!.copy(
                demand = f.request.snapshot!!.demand.copy(rooms = listOf(captured))
            )
        assertEquals(AppResult.Success(true), f.useCase(f.access, f.key, captured))
        assertFalse("source" in f.request.base.calls)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun newerProviderProgressBeforeCommitKeepsTheRoomQueued() = runTest {
        val f = SpaceIncomingCompletionFixture()
        f.latest = f.latest!!.copy(revision = f.latest!!.revision + 1)
        assertEquals(AppResult.Success(false), f.complete())
        assertEquals(0, f.request.writes)
        assertEquals(listOf(f.captured), f.request.snapshot!!.demand.rooms)
    }

    @Test
    fun absentOrNewerGenerationIsAnObsoleteCaptureWithoutAnyProviderRead() = runTest {
        for (absent in listOf(false, true)) {
            val f = SpaceIncomingCompletionFixture()
            if (absent) f.request.snapshot = null
            else
                f.request.snapshot =
                    f.request.snapshot!!.copy(
                        demand =
                            f.request.snapshot!!
                                .demand
                                .copy(
                                    rooms =
                                        listOf(
                                            f.captured.copy(
                                                generation = f.request.provider.queue.id(710)
                                            )
                                        )
                                )
                    )
            val before = f.request.snapshot
            assertEquals(AppResult.Success(true), f.complete())
            assertEquals(before, f.request.snapshot)
            assertEquals(listOf("demand"), f.request.base.calls)
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun changedFactsUnderTheSameGenerationCannotBeCompleted() = runTest {
        val f = SpaceIncomingCompletionFixture()
        f.request.snapshot =
            f.request.snapshot!!.copy(
                demand =
                    f.request.snapshot!!
                        .demand
                        .copy(rooms = listOf(f.captured.copy(headEventId = "\$changed")))
            )
        assertIs<AppResult.Failed>(f.complete())
        assertEquals(listOf("demand"), f.request.base.calls)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun completionCannotClearAConcurrentNewGenerationOrIndependentRescan() = runTest {
        val f = SpaceIncomingCompletionFixture()
        val before = f.request.snapshot!!
        f.request.base.hooks["write"] = {
            f.request.snapshot =
                before.copy(
                    generation = f.request.provider.queue.id(811),
                    demand =
                        before.demand.copy(
                            rooms =
                                listOf(
                                    f.captured.copy(generation = f.request.provider.queue.id(711))
                                ),
                            rescanGeneration = f.request.provider.queue.id(712),
                        ),
                )
        }
        assertEquals(AppResult.Success(true), f.complete())
        assertEquals(
            f.request.provider.queue.id(711),
            f.request.snapshot!!.demand.rooms.single().generation,
        )
        assertEquals(f.request.provider.queue.id(712), f.request.snapshot!!.demand.rescanGeneration)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun cleanRoomCompletionPreservesAnExistingRescan() = runTest {
        val f = SpaceIncomingCompletionFixture()
        val rescan = f.request.provider.queue.id(713)
        f.request.snapshot =
            f.request.snapshot!!.copy(
                demand = f.request.snapshot!!.demand.copy(rescanGeneration = rescan)
            )
        assertEquals(AppResult.Success(true), f.complete())
        assertEquals(rescan, f.request.snapshot!!.demand.rescanGeneration)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun uncertainCommitIsConfirmedByExactReadbackWithoutASecondWrite() = runTest {
        val f = SpaceIncomingCompletionFixture()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.complete())
        assertEquals(1, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
        assertTrue(f.request.snapshot!!.demand.rooms.isEmpty())
    }

    @Test
    fun lostCommitOrFailedReadbackRetainsUncertaintyAndNeverAcknowledgesTheWake() = runTest {
        for (mode in 0..1) {
            val f = SpaceIncomingCompletionFixture()
            if (mode == 0) f.request.commit = false
            else
                f.request.base.failures["readback"] =
                    Failure(FailureKind.Storage, "Fictional lost read")
            assertIs<AppResult.Failed>(f.complete())
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(1, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun expiredCredentialsDoNotHideOwnedCompletionFacts() = runTest {
        val f = SpaceIncomingCompletionFixture()
        f.request.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.complete())
    }

    @Test
    fun denialAndAccountReplacementAtEachReadOrWriteBoundaryCannotContinue() = runTest {
        for (stage in
            listOf(
                "denied",
                "demand",
                "binding",
                "pending",
                "source",
                "progress",
                "write",
                "committed",
                "readback",
            )) {
            val f = SpaceIncomingCompletionFixture()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.complete())
            assertEquals(f.originalWake, f.request.wake)
            if (stage in listOf("denied", "demand", "binding", "pending", "source", "progress"))
                assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun invalidCaptureAndNonPendingPhaseCannotReadOrComplete() = runTest {
        for (capture in
            listOf(
                CollaborationSpaceIncomingPhase.Latest,
                CollaborationSpaceIncomingPhase.Provider,
            )) {
            val f = SpaceIncomingCompletionFixture()
            assertIs<AppResult.Failed>(f.useCase(f.access, f.key, f.captured.copy(phase = capture)))
            assertTrue(f.request.base.calls.isEmpty())
        }
    }

    @Test
    fun stalledProviderReadTimesOutWithoutRetryingOrMutatingTheQueue() = runTest {
        val f = SpaceIncomingCompletionFixture()
        f.request.base.hooks["pending"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.complete()).failure.kind)
        assertEquals(1, f.request.base.calls.count { it == "pending" })
        assertEquals(0, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun cancellationAfterCommitDoesNotRestoreOldDemandOrAcknowledgeTheWake() = runTest {
        val f = SpaceIncomingCompletionFixture()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["committed"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.complete() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.request.snapshot!!.demand.rooms.isEmpty())
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(0, f.request.schedules)
    }
}
