package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceInventorySelectionTest {
    @Test
    fun anInventoryOnlyQueueRemainsReadyForAdoptionWithoutInventingRoomTasks() {
        val work = selected(demand)
        assertEquals(listOf(progress), work.inventoryReady)
        assertTrue(work.turn.isEmpty())
        assertTrue(work.roomsPending)
        assertEquals(demand, work.snapshot?.demand)
    }

    @Test
    fun installedOriginalSelectionKeepsOtherSourcesPendingWithoutDispatchingThem() {
        val other =
            progress.copy(
                source = source.copy(storeId = "b".repeat(64)),
                inventoryGeneration = id(2),
            )
        val current = demand.copy(sources = listOf(progress, other))
        assertEquals(listOf(progress, other), selected(current).inventoryReady)
        assertEquals(listOf(progress), selected(current, source).inventoryReady)
        val unmatched = selected(current, source.copy(storeId = "c".repeat(64)))
        assertTrue(unmatched.inventoryReady.isEmpty())
        assertTrue(unmatched.roomsPending)
    }

    @Test
    fun aFutureRetryIsPendingWithoutImmediateReschedulingAndResumesWhenEligible() {
        val delayed =
            progress.copy(inventoryRetry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1))
        val current = demand.copy(sources = listOf(delayed))
        assertTrue(selected(current, now = 30_999).inventoryReady.isEmpty())
        assertTrue(selected(current, now = 30_999).roomsPending)
        assertEquals(listOf(delayed), selected(current, now = 31_000).inventoryReady)
        assertEquals(listOf(delayed), selected(current, now = 500).inventoryReady)
    }

    @Test
    fun aFullPrimaryQueueDoesNotRequestPointlessNativeEnumeration() {
        val full = demand.copy(nextPosition = 64, physicalRooms = (0 until 64).map(::physical))
        val work = selected(full)
        assertTrue(work.inventoryReady.isEmpty())
        assertEquals(4, work.turn.size)
        assertTrue(work.roomsPending)
        assertEquals(
            listOf(progress),
            selected(full.copy(physicalRooms = full.physicalRooms.drop(1))).inventoryReady,
        )
    }

    @Test
    fun inventoryDoesNotStealTheFourCombinedPrimaryTurnsOrAlterTheirFifo() {
        val current = demand.copy(nextPosition = 6, physicalRooms = (0 until 6).map(::physical))
        val work = selected(current)
        assertEquals(listOf(0L, 1L, 2L, 3L), work.turn.map { it.position })
        assertEquals(listOf(progress), work.inventoryReady)
        assertEquals(current, work.snapshot?.demand)
    }

    @Test
    fun completingInventoryRemovesReadinessWhileRetainedRoomWorkStaysPending() {
        val current =
            demand.copy(
                nextPosition = 1,
                physicalRooms = listOf(physical(0)),
                sources = listOf(progress.copy(inventoryGeneration = null)),
            )
        val work = selected(current)
        assertTrue(work.inventoryReady.isEmpty())
        assertTrue(work.roomsPending)
        assertEquals(1, work.turn.size)
    }

    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
    private val progress = CollaborationSpaceIncomingSourceProgress(source, "before", id(1))
    private val demand = CollaborationSpaceIncomingDemand(sources = listOf(progress))

    private fun physical(index: Int) =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            "!room$index:test",
            id(100 + index),
            index.toLong(),
            "\$head-$index",
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )

    private fun selected(
        current: CollaborationSpaceIncomingDemand,
        source: CollaborationSpaceIncomingSource? = null,
        now: Long = 0,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                selectCollaborationSpaceIncomingWork(
                    CollaborationSpaceIncomingDemandSnapshot(id(900), current),
                    now,
                    source,
                )
            )
            .value

    private fun id(value: Int) = "10000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
