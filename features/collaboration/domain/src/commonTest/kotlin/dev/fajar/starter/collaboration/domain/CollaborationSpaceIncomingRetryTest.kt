package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingRetryTest {
    @Test
    fun queueContentionDoesNotBecomeDurableThirtySecondBackoff() = runTest {
        val f = SpaceIncomingScanFixture()
        val captured = room(f)
        f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
        val before = f.current()
        val busy = collaborationSpaceIncomingQueueContention()
        assertEquals(busy, f.defer(f.access, captured, busy.failure))
        assertEquals(before, f.current())
        assertEquals(0, f.request.reads)
        assertEquals(0, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun currentDenialWinsOverAnEarlierQueueContentionWithoutStorageReads() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.directory.deny()
        val result = f.defer(f.access, room(f), collaborationSpaceIncomingQueueContention().failure)
        assertEquals(f.request.base.directory.denied, assertIs<AppResult.Failed>(result).failure)
        assertEquals(0, f.request.reads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun producerBeforeCasRetriesOnceAndAfterCommitConfirmsLostReply() = runTest {
        for (stage in listOf("write", "committed")) {
            val f = SpaceIncomingScanFixture()
            val captured = room(f)
            f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
            val request = f.request
            val wake = request.wake
            request.responseLoss = stage == "committed"
            request.base.hooks[stage] = { request.retainIndependentProducerWork() }
            val result =
                f.defer(f.access, captured, Failure(FailureKind.Network, "Fictional offline"))
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
    fun retryDelayGrowsPerRoomAndStopsAtFiveMinutes() {
        var retry: CollaborationSpaceIncomingRetry? = null
        val delays = listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L)
        delays.forEachIndexed { i, expected ->
            val now = 1_000_000L + i * 600_000L
            retry = assertNotNull(nextCollaborationSpaceIncomingRetry(retry, now))
            assertEquals(expected, retry!!.eligibleAtEpochMillis - now)
            assertTrue(validCollaborationSpaceIncomingRetry(retry!!))
        }
    }

    @Test
    fun unrelatedFreshTasksRemainEligibleDuringAnotherRoomsBackoff() {
        val f = SpaceIncomingScanFixture()
        val fresh = room(f)
        val delayed = fresh.copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        assertTrue(eligibleCollaborationSpaceIncomingRoom(fresh, 1_000_001))
        repeat(100) {
            assertFalse(eligibleCollaborationSpaceIncomingRoom(delayed, 1_000_001L + it))
        }
        assertTrue(eligibleCollaborationSpaceIncomingRoom(delayed, 1_030_000))
    }

    @Test
    fun backwardWallClockAdjustmentCannotHideTheTaskIndefinitely() {
        val f = SpaceIncomingScanFixture()
        val delayed = room(f).copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        assertTrue(eligibleCollaborationSpaceIncomingRoom(delayed, 999_999))
        assertFalse(eligibleCollaborationSpaceIncomingRoom(delayed, 1_000_000))
    }

    @Test
    fun invalidOrUnboundedRetryMetadataIsRejected() {
        val good = assertNotNull(nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        for (invalid in
            listOf(
                good.copy(failures = 0),
                good.copy(failures = 6),
                good.copy(attemptedAtEpochMillis = -1),
                good.copy(eligibleAtEpochMillis = Long.MAX_VALUE),
                good.copy(eligibleAtEpochMillis = 1_030_001),
                good.copy(eligibleAtEpochMillis = 1_000_000),
            )) {
            assertFalse(validCollaborationSpaceIncomingRetry(invalid))
            assertNull(nextCollaborationSpaceIncomingRetry(invalid, 1_000_000))
        }
        assertNull(nextCollaborationSpaceIncomingRetry(null, Long.MAX_VALUE))
    }

    @Test
    fun aNewHeadStartsFreshWhileSameHeadDoesNotBypassBackoff() {
        val f = SpaceIncomingScanFixture()
        val delayed = room(f).copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        val current = CollaborationSpaceIncomingDemand(1, listOf(delayed))
        val same =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        current,
                        CollaborationSpaceIncomingDemandChange.Request(
                            delayed.conversationId,
                            f.id(805),
                            delayed.headEventId,
                        ),
                    )
                )
                .value
        assertEquals(current, same)
        val next =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        current,
                        CollaborationSpaceIncomingDemandChange.Request(
                            delayed.conversationId,
                            f.id(805),
                            "\$new",
                        ),
                    )
                )
                .value
        assertNull(next.rooms.single().retry)
        assertEquals(f.id(805), next.rooms.single().generation)
    }

    @Test
    fun aCommittedProviderPageClearsBackoffEvenWhenItsPhaseStaysProvider() {
        val f = SpaceIncomingScanFixture()
        val delayed =
            room(f)
                .copy(
                    phase = CollaborationSpaceIncomingPhase.Provider,
                    retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000),
                )
        val next =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        CollaborationSpaceIncomingDemand(1, listOf(delayed)),
                        CollaborationSpaceIncomingDemandChange.ProviderAcquired(delayed, false),
                    )
                )
                .value
        assertNull(next.rooms.single().retry)
        assertEquals(CollaborationSpaceIncomingPhase.Provider, next.rooms.single().phase)
    }

    @Test
    fun startupScanKeepsTheExistingRoomsRetryEligibility() {
        val f = SpaceIncomingScanFixture()
        val delayed = room(f).copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        val current = f.current().copy(nextPosition = 1, rooms = listOf(delayed))
        val next =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    discoverCollaborationSpaceIncomingRooms(
                        f.access,
                        current,
                        f.page(1, null),
                        emptyList(),
                        f.id(990),
                    )
                )
                .value
        assertEquals(delayed, next.rooms.single())
    }

    @Test
    fun malformedRoomCannotBecomeEligibleByHavingNoRetryMetadata() {
        val f = SpaceIncomingScanFixture()
        assertFalse(
            eligibleCollaborationSpaceIncomingRoom(room(f).copy(generation = "bad"), 1_000_000)
        )
        assertFalse(eligibleCollaborationSpaceIncomingRoom(room(f), -1))
    }

    @Test
    fun failedTurnRetainsEligibilityAndDefersOnlyItsRoom() = runTest {
        val f = SpaceIncomingScanFixture()
        val captured = room(f)
        f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
        val now = f.request.base.native.clock.now().toEpochMilliseconds()
        assertEquals(
            AppResult.Success(true),
            f.defer(f.access, captured, Failure(FailureKind.Unavailable, "Fictional missing key")),
        )
        assertEquals(
            nextCollaborationSpaceIncomingRetry(null, now),
            f.current().rooms.single().retry,
        )
        assertEquals(1L, f.current().rooms.single().position)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun lostRetryWriteResponseDoesNotIncreaseBackoffTwice() = runTest {
        val f = SpaceIncomingScanFixture()
        val captured = room(f)
        f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
        f.request.responseLoss = true
        val failure = Failure(FailureKind.Network, "Fictional offline")
        assertEquals(AppResult.Success(true), f.defer(f.access, captured, failure))
        val after = f.current()
        assertEquals(AppResult.Success(true), f.defer(f.access, captured, failure))
        assertEquals(after, f.current())
        assertEquals(1, f.request.writes)
    }

    @Test
    fun confirmedDenialIsNotConvertedIntoAutomaticRetry() = runTest {
        val f = SpaceIncomingScanFixture()
        val captured = room(f)
        f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
        val failure = Failure(FailureKind.AccessDenied, "Fictional denial")
        assertEquals(AppResult.Failed(failure), f.defer(f.access, captured, failure))
        assertEquals(0, f.request.writes)
    }

    @Test
    fun successfulContinuationClearsAnEarlierRetryWithoutAcknowledgingWake() = runTest {
        val f = SpaceIncomingScanFixture()
        val captured = room(f).copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        f.set(f.current().copy(nextPosition = 1, rooms = listOf(captured)))
        assertEquals(AppResult.Success(true), f.defer(f.access, captured))
        assertNull(f.current().rooms.single().retry)
        assertEquals(f.originalWake, f.request.wake)
    }

    private fun room(f: SpaceIncomingScanFixture) =
        CollaborationSpaceIncomingRoomDemand(
            f.binding(0).conversation.id,
            f.id(804),
            0,
            "\$head",
            CollaborationSpaceIncomingPhase.Pending,
        )
}
