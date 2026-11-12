package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingSelectionTest {
    private val first =
        CollaborationSpaceIncomingSource("1".repeat(64), "@first:example.test", "FIRST")
    private val second =
        CollaborationSpaceIncomingSource("2".repeat(64), "@second:example.test", "SECOND")
    private val demand =
        CollaborationSpaceIncomingDemand(
            nextPosition = 6,
            rooms = listOf(logical(0, first), logical(2, second), logical(4, null)),
            physicalRooms = listOf(physical(1, first), physical(3, second), physical(5, first)),
            sources =
                listOf(
                    CollaborationSpaceIncomingSourceProgress(first, "first"),
                    CollaborationSpaceIncomingSourceProgress(second, "second"),
                ),
        )

    @Test
    fun initialReadSelectsFourCombinedPositionsRatherThanFourOfEachKind() {
        val work = selected(demand)
        assertEquals(listOf(0L, 2L), work.ready.map { it.position })
        assertEquals(listOf(1L, 3L), work.physicalReady.map { it.position })
        assertEquals(4, work.ready.size + work.physicalReady.size)
        assertEquals(demand, work.snapshot?.demand)
    }

    @Test
    fun selectedOriginalKeepsUnscopedWorkAndSkipsOtherSourcesBeforeLimitingTheTurn() {
        val work = selected(demand, first)
        assertEquals(listOf(0L, 4L), work.ready.map { it.position })
        assertEquals(listOf(1L, 5L), work.physicalReady.map { it.position })
        assertEquals(demand, work.snapshot?.demand)
        assertTrue(work.roomsPending)
    }

    @Test
    fun onlyAnotherOriginalsWorkIsPendingButNotReadyForImmediateRescheduling() {
        val third = first.copy(storeId = "3".repeat(64))
        val current = demand.copy(rooms = demand.rooms.map { it.copy(sourceId = second.storeId) })
        val work = selected(current, third)
        assertTrue(work.ready.isEmpty())
        assertTrue(work.physicalReady.isEmpty())
        assertTrue(work.roomsPending)
        assertFalse(work.scanPending)
        assertEquals(current, work.snapshot?.demand)
    }

    @Test
    fun physicalRetryDoesNotBlockAnotherRoomOrResetItsRetainedPosition() {
        val delayed =
            demand.physicalRooms
                .first()
                .copy(retry = nextCollaborationSpaceIncomingRetry(null, 1000))
        val current = demand.copy(physicalRooms = listOf(delayed) + demand.physicalRooms.drop(1))
        val work = selected(current, first, 2000)
        assertEquals(listOf(5L), work.physicalReady.map { it.position })
        assertEquals(listOf(0L, 4L), work.ready.map { it.position })
        assertEquals(current, work.snapshot?.demand)
        assertEquals(
            listOf(1L, 5L),
            selected(current, first, 31_000).physicalReady.map { it.position },
        )
    }

    @Test
    fun backwardClockAdjustmentCannotHidePhysicalWorkForever() {
        val delayed =
            demand.physicalRooms
                .first()
                .copy(retry = nextCollaborationSpaceIncomingRetry(null, 1000))
        assertTrue(eligibleCollaborationSpaceIncomingPhysicalRoom(delayed, 500))
        assertFalse(eligibleCollaborationSpaceIncomingPhysicalRoom(delayed, 2000))
        assertFalse(eligibleCollaborationSpaceIncomingPhysicalRoom(delayed, -1))
    }

    @Test
    fun sameStoreIdWithDifferentMatrixIdentityCannotSelectOwnedWork() {
        val current =
            demand.copy(
                sources =
                    demand.sources.map {
                        if (it.source == first)
                            it.copy(source = first.copy(matrixDeviceId = "CHANGED"))
                        else it
                    }
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    selectCollaborationSpaceIncomingWork(snapshot(current), 0, first)
                )
                .failure
                .kind,
        )
    }

    @Test
    fun providerInventoryKeepsWorkPendingEvenWithoutRoomTasks() {
        val current =
            demand.copy(
                rooms = emptyList(),
                physicalRooms = emptyList(),
                sources = demand.sources.map { it.copy(inventoryGeneration = id(900)) },
            )
        val work = selected(current, first)
        assertTrue(work.roomsPending)
        assertTrue(work.ready.isEmpty())
        assertTrue(work.physicalReady.isEmpty())
        assertFalse(work.scanPending)
    }

    @Test
    fun fullPhysicalQueueKeepsIndependentBindingScanPendingWithoutRequestingMoreCapacity() {
        val current =
            demand.copy(
                nextPosition = 64,
                rooms = emptyList(),
                physicalRooms = (0..63).map { physical(it, first) },
                rescanGeneration = id(900),
            )
        val work = selected(current, first)
        assertFalse(work.scanPending)
        assertEquals(4, work.physicalReady.size)
        assertEquals(id(900), work.snapshot?.demand?.rescanGeneration)
    }

    @Test
    fun malformedClockQueueAndSourceCannotProduceFalseIdle() {
        assertIs<AppResult.Failed>(selectCollaborationSpaceIncomingWork(null, -1, first))
        assertIs<AppResult.Failed>(
            selectCollaborationSpaceIncomingWork(null, 0, first.copy(storeId = "bad"))
        )
        assertIs<AppResult.Failed>(
            selectCollaborationSpaceIncomingWork(snapshot(demand.copy(nextPosition = 0)), 0, first)
        )
        val empty =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                    selectCollaborationSpaceIncomingWork(null, 0, first)
                )
                .value
        assertNull(empty.snapshot)
        assertFalse(empty.roomsPending)
    }

    @Test
    fun selectedSnapshotDoesNotRetainMutableRepositoryCollections() {
        val rooms = demand.rooms.toMutableList()
        val physical = demand.physicalRooms.toMutableList()
        val sources = demand.sources.toMutableList()
        val work =
            selected(demand.copy(rooms = rooms, physicalRooms = physical, sources = sources), first)
        rooms.clear()
        physical.clear()
        sources.clear()
        assertEquals(demand, work.snapshot?.demand)
        assertEquals(4, work.ready.size + work.physicalReady.size)
    }

    @Test
    fun onlyHistoryCanRequireAnAdoptedRuntime() {
        val history = CollaborationSpaceHistoryRecovery(id(901))
        val work = selected(CollaborationSpaceIncomingDemand(historyRecovery = history), first)
        assertTrue(work.turn.isEmpty())
        assertTrue(work.inventoryReady.isEmpty())
        assertEquals(history, work.historyReady)
        assertNull(work.historyDeferred)
    }

    @Test
    fun deferredHistoryCannotRequestImmediateContinuation() {
        val retry = assertNotNull(nextCollaborationSpaceIncomingRetry(null, 1000))
        val history = CollaborationSpaceHistoryRecovery(id(901), revision = 1, retry = retry)
        val work =
            selected(CollaborationSpaceIncomingDemand(historyRecovery = history), first, 2000)
        assertNull(work.historyReady)
        assertEquals(retry, work.historyDeferred)
        assertEquals(history, work.snapshot?.demand?.historyRecovery)
    }

    @Test
    fun historyRetryBecomesReadyAtItsDeadlineAndAfterClockRollback() {
        val retry = assertNotNull(nextCollaborationSpaceIncomingRetry(null, 1000))
        val history = CollaborationSpaceHistoryRecovery(id(901), revision = 1, retry = retry)
        for (now in listOf(500L, retry.eligibleAtEpochMillis)) {
            val work =
                selected(CollaborationSpaceIncomingDemand(historyRecovery = history), first, now)
            assertEquals(history, work.historyReady)
            assertNull(work.historyDeferred)
        }
    }

    @Test
    fun completedWaitingPassStaysAsleep() {
        val history =
            CollaborationSpaceHistoryRecovery(
                id(901),
                revision = 1,
                complete = true,
                unresolved = true,
            )
        val work = selected(CollaborationSpaceIncomingDemand(historyRecovery = history), first)
        assertNull(work.historyReady)
        assertNull(work.historyDeferred)
        assertEquals(history, work.snapshot?.demand?.historyRecovery)
    }

    @Test
    fun newActualRequestMakesCompletedWaitingHistoryReady() {
        val previous =
            CollaborationSpaceHistoryRecovery(
                id(901),
                revision = 1,
                complete = true,
                unresolved = true,
            )
        val next =
            assertIs<AppResult.Success<CollaborationSpaceHistoryRecovery>>(
                    planCollaborationSpaceHistoryRecoveryRequest(previous, id(902))
                )
                .value
        val work = selected(CollaborationSpaceIncomingDemand(historyRecovery = next), first)
        assertEquals(next, work.historyReady)
        assertNull(work.historyDeferred)
    }

    @Test
    fun fullQueueDoesNotStarveHistoryRecovery() {
        val history = CollaborationSpaceHistoryRecovery(id(901))
        val work =
            selected(
                demand.copy(
                    nextPosition = 64,
                    rooms = emptyList(),
                    physicalRooms = (0..63).map { physical(it, first) },
                    historyRecovery = history,
                ),
                first,
            )
        assertEquals(4, work.turn.size)
        assertEquals(history, work.historyReady)
        assertTrue(work.inventoryReady.isEmpty())
    }

    @Test
    fun accountHistoryIsNotFilteredOutWithAnotherPhysicalSource() {
        val history = CollaborationSpaceHistoryRecovery(id(901))
        val third = first.copy(storeId = "3".repeat(64))
        val work = selected(demand.copy(historyRecovery = history), third)
        assertEquals(history, work.historyReady)
        assertEquals(1, work.turn.size)
    }

    @Test
    fun deferredHistoryDoesNotHideOtherReadyRooms() {
        val retry = assertNotNull(nextCollaborationSpaceIncomingRetry(null, 1000))
        val history = CollaborationSpaceHistoryRecovery(id(901), revision = 1, retry = retry)
        val work = selected(demand.copy(historyRecovery = history), first, 2000)
        assertEquals(4, work.turn.size)
        assertNull(work.historyReady)
        assertEquals(retry, work.historyDeferred)
    }

    private fun selected(
        value: CollaborationSpaceIncomingDemand,
        source: CollaborationSpaceIncomingSource? = null,
        now: Long = 0,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                selectCollaborationSpaceIncomingWork(snapshot(value), now, source)
            )
            .value

    private fun snapshot(value: CollaborationSpaceIncomingDemand) =
        CollaborationSpaceIncomingDemandSnapshot(id(999), value)

    private fun logical(position: Int, source: CollaborationSpaceIncomingSource?) =
        CollaborationSpaceIncomingRoomDemand(
            id(100 + position),
            id(200 + position),
            position.toLong(),
            null,
            CollaborationSpaceIncomingPhase.Pending,
            sourceId = source?.storeId,
        )

    private fun physical(position: Int, source: CollaborationSpaceIncomingSource) =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            "!room-$position:example.test",
            id(300 + position),
            position.toLong(),
            null,
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )

    private fun id(value: Int) = "10000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
