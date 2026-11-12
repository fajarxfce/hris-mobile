package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationSpaceIncomingRoomTest {
    @Test
    fun newHintRetainsDemandAndIndependentWakeBeforeOneSchedule() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        assertEquals(AppResult.Success(true), f.queue())
        val task = f.snapshot!!.demand.rooms.single()
        assertEquals(f.request.generation, task.generation)
        assertEquals(f.request.headEventId, task.headEventId)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, task.phase)
        assertEquals(CollaborationSpaceIncomingWake(f.request.generation), f.wake)
        assertEquals(
            listOf("binding", "demand", "source", "write", "committed", "readback", "schedule"),
            f.base.calls,
        )
        assertEquals(1, f.writes)
        assertEquals(1, f.schedules)
    }

    @Test
    fun repeatedKnownHintDoesNotWriteRescheduleOrReadProviderHistory() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.queued()
        val before = f.snapshot
        assertEquals(
            AppResult.Success(true),
            f.queue(f.request.copy(generation = f.provider.queue.id(701))),
        )
        assertEquals(before, f.snapshot)
        assertEquals(listOf("binding", "demand"), f.base.calls)
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun acquiredIdleSourceDoesNotCreateDemandOrWakeEvenWhenPaginationTokenDiffers() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.covered()
        assertNotEquals(f.request.headEventId, f.source.progress!!.incoming!!.head)
        assertEquals(AppResult.Success(false), f.queue())
        assertNull(f.snapshot)
        assertNull(f.wake)
        assertEquals(listOf("binding", "demand", "source", "pending"), f.base.calls)
    }

    @Test
    fun opaquePaginationTokenMatchingTheHintIsNotCoverageWithoutAnExactSource() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        val progress = f.provider.checkpoint(null)
        f.source =
            CollaborationHistorySource(
                progress.copy(incoming = progress.incoming!!.copy(head = f.request.headEventId!!)),
                null,
            )
        assertEquals(AppResult.Success(true), f.queue())
        assertEquals(
            CollaborationSpaceIncomingPhase.Latest,
            f.snapshot!!.demand.rooms.single().phase,
        )
        assertFalse("pending" in f.base.calls)
    }

    @Test
    fun acquiredSourceWithUnresolvedContentQueuesPendingWithoutAnotherMetadataWave() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.covered(unresolved = true)
        assertEquals(AppResult.Success(true), f.queue())
        assertEquals(
            CollaborationSpaceIncomingPhase.Pending,
            f.snapshot!!.demand.rooms.single().phase,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun acquiredSourceInAnUnfinishedWaveContinuesItsSavedProviderPosition() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.covered(exhausted = false)
        assertEquals(AppResult.Success(true), f.queue())
        assertEquals(
            CollaborationSpaceIncomingPhase.Provider,
            f.snapshot!!.demand.rooms.single().phase,
        )
        assertFalse("pending" in f.base.calls)
    }

    @Test
    fun lateAcquiredSourceDoesNotReplaceNewerQueuedHeadOrGeneration() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.queued("\$newer")
        f.covered(unresolved = true)
        val before = f.snapshot
        assertEquals(
            AppResult.Success(true),
            f.queue(f.request.copy(generation = f.provider.queue.id(702))),
        )
        assertEquals(before, f.snapshot)
        assertEquals(0, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun uncertainWriteReplyIsConfirmedExactlyWithoutReissuingTheWrite() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.responseLoss = true
        assertEquals(AppResult.Success(true), f.queue())
        assertEquals(1, f.writes)
        assertEquals(1, f.schedules)
        assertEquals(f.request.generation, f.wake!!.generation)
    }

    @Test
    fun lostOrConflictingWriteWithoutExactReadbackNeverSchedules() = runTest {
        for (stage in listOf("write", "readback", "conflict")) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            if (stage == "conflict") f.commit = false
            else f.base.failures[stage] = Failure(FailureKind.Storage, "Fictional failure")
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(1, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun changedReadbackCannotConfirmOrOverwriteAConcurrentNewerDemand() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.base.hooks["readback"] = { f.queued("\$concurrent") }
        assertIs<AppResult.Failed>(f.queue())
        assertEquals("\$concurrent", f.snapshot!!.demand.rooms.single().headEventId)
        assertEquals(0, f.schedules)
    }

    @Test
    fun ordinaryCredentialAgeDoesNotPreventLocalQueueing() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.queue())
        assertFalse(f.base.calls.any { it in listOf("transport", "page", "resume", "acquire") })
    }

    @Test
    fun confirmedDenialPreventsEveryReadWriteAndSchedule() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.base.directory.deny()
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.queue()).failure)
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun lateAccountReplacementAfterEachLocalBoundaryNeverContinuesWithThatOwner() = runTest {
        for (stage in
            listOf(
                "binding",
                "demand",
                "source",
                "pending",
                "write",
                "committed",
                "readback",
                "schedule",
            )) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            if (stage == "pending") f.covered(unresolved = true)
            f.base.hooks[stage] = { f.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.queue())
            if (stage != "schedule") assertEquals(0, f.schedules)
            if (stage in listOf("binding", "demand", "source", "pending")) assertEquals(0, f.writes)
        }
    }

    @Test
    fun invalidOrUnboundHintsCannotCreateWork() = runTest {
        for (mode in 0..4) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            val result =
                when (mode) {
                    0 -> f.queue(f.request.copy(generation = "invalid"))
                    1 -> f.queue(f.request.copy(headEventId = "invalid"))
                    2 ->
                        f.useCase(
                            f.owner,
                            f.key.copy(purpose = CollaborationHistoryPurpose.Browse),
                            f.request,
                        )
                    3 -> {
                        f.base.retained = null
                        f.queue()
                    }
                    else -> {
                        f.base.retained =
                            f.base.retained!!.copy(
                                room = f.base.retained!!.room.copy(roomId = "!other:example.test")
                            )
                        f.queue()
                    }
                }
            assertIs<AppResult.Failed>(result)
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun mismatchedProviderCheckpointOrMalformedSourceCannotDischargeWork() = runTest {
        for (mode in 0..5) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            f.covered()
            when (mode) {
                0 -> f.source = f.source.copy(progress = null)
                1 -> f.source = f.source.copy(progress = f.source.progress!!.copy(provider = null))
                2 ->
                    f.source =
                        f.source.copy(
                            reference =
                                f.source.reference!!.copy(sequence = f.source.progress!!.references)
                        )
                3 ->
                    f.source =
                        f.source.copy(
                            reference =
                                f.source.reference!!.copy(
                                    event = f.source.reference!!.event.copy(eventId = "\$wrong")
                                )
                        )
                4 -> f.pending = f.pending.copy(progress = f.pending.progress!!.copy(revision = 5))
                else ->
                    f.source =
                        f.source.copy(reference = f.source.reference!!.copy(resolved = false))
            }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.writes)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun schedulerFailureOrTimeoutPreservesTheConfirmedDurableWake() = runTest {
        for (timeout in listOf(false, true)) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            f.schedule = {
                if (timeout) awaitCancellation()
                else
                    AppResult.Failed(
                        Failure(FailureKind.Unavailable, "Fictional scheduler failure")
                    )
            }
            assertEquals(AppResult.Success(true), f.queue())
            assertNotNull(f.snapshot)
            assertNotNull(f.wake)
            assertEquals(1, f.schedules)
        }
    }

    @Test
    fun stalledLocalReadHasAFiniteBudgetAndNeverRetriesOrWrites() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.base.hooks["source"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.queue()).failure.kind)
        assertEquals(1, f.base.calls.count { it == "source" })
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationAfterTheAtomicWriteRetainsTheOriginalDemandAndWake() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        val committed = CompletableDeferred<Unit>()
        f.base.hooks["committed"] = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.queue() }
        committed.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNotNull(f.snapshot)
        assertEquals(CollaborationSpaceIncomingWake(f.request.generation), f.wake)
        assertEquals(1, f.writes)
        assertEquals(0, f.schedules)
    }

    @Test
    fun mutableRepositoryRoomListCannotChangeTheAlreadyCapturedExpectedWrite() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.queued("\$older")
        val rooms = f.snapshot!!.demand.rooms.toMutableList()
        val original = f.snapshot!!.copy(demand = f.snapshot!!.demand.copy(rooms = rooms.toList()))
        f.snapshot = f.snapshot!!.copy(demand = f.snapshot!!.demand.copy(rooms = rooms))
        f.base.hooks["source"] = {
            rooms.clear()
            f.snapshot = original
        }
        assertEquals(
            AppResult.Success(true),
            f.queue(f.request.copy(generation = f.provider.queue.id(703))),
        )
        assertEquals(original, f.expectedAtWrite)
        assertEquals(f.request.headEventId, f.snapshot!!.demand.rooms.single().headEventId)
    }

    @Test
    fun fullQueueRetainsOverflowRescanButAnAcquiredIdleHintAddsNoWork() = runTest {
        for (knownIdle in listOf(false, true)) {
            val f = SpaceIncomingRequestFixture(originalRoom = false)
            val rooms =
                (0 until 64).map { n ->
                    CollaborationSpaceIncomingRoomDemand(
                        f.provider.queue.id(10_000 + n),
                        f.provider.queue.id(20_000 + n),
                        n.toLong(),
                        "\$old-$n",
                        CollaborationSpaceIncomingPhase.Pending,
                    )
                }
            f.snapshot =
                CollaborationSpaceIncomingDemandSnapshot(
                    f.provider.queue.id(801),
                    CollaborationSpaceIncomingDemand(64, rooms),
                )
            if (knownIdle) f.covered()
            assertEquals(AppResult.Success(!knownIdle), f.queue())
            assertEquals(rooms, f.snapshot!!.demand.rooms)
            assertEquals(
                if (knownIdle) null else f.request.generation,
                f.snapshot!!.demand.rescanGeneration,
            )
            assertEquals(if (knownIdle) 0 else 1, f.writes)
            assertEquals(if (knownIdle) 0 else 1, f.schedules)
        }
    }

    @Test
    fun newUnknownStartupAttemptRequestsLatestWithoutPretendingToHaveAHead() = runTest {
        val f = SpaceIncomingRequestFixture(originalRoom = false)
        f.queued(null)
        val request = f.request.copy(headEventId = null, generation = f.provider.queue.id(704))
        assertEquals(AppResult.Success(true), f.queue(request))
        val room = f.snapshot!!.demand.rooms.single()
        assertEquals(request.generation, room.generation)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, room.phase)
        assertFalse("source" in f.base.calls)
        assertEquals(1, f.writes)
    }
}
