package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingPhysicalResolutionTest {
    private val source = CollaborationSpaceIncomingSource("1".repeat(64), "@user:test", "DEVICE")
    private val physical =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            "!room:opaque",
            id(1),
            1,
            "\$head",
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )
    private val base =
        CollaborationSpaceIncomingDemand(
            nextPosition = 3,
            sources =
                listOf(CollaborationSpaceIncomingSourceProgress(source, "opaque-position", id(10))),
            physicalRooms = listOf(physical),
            rescanGeneration = id(11),
        )

    @Test
    fun transfersOriginalGenerationTurnHeadSourceAndRetryWithoutChangingProgress() {
        val retry = assertNotNull(nextCollaborationSpaceIncomingRetry(null, 1000))
        val captured = physical.copy(retry = retry)
        val current = base.copy(physicalRooms = listOf(captured))
        val result = resolved(current, captured)
        assertEquals(CollaborationSpaceIncomingBindingResolution.Queued, result.outcome)
        assertEquals(
            CollaborationSpaceIncomingRoomDemand(
                id(20),
                id(1),
                1,
                "\$head",
                CollaborationSpaceIncomingPhase.Latest,
                retry,
                source.storeId,
            ),
            result.demand.rooms.single(),
        )
        assertEquals(
            current.copy(rooms = result.demand.rooms, physicalRooms = emptyList()),
            result.demand,
        )
    }

    @Test
    fun joinedLeftLimitedAndUnknownHeadsAlwaysStartFreshProviderWave() {
        for (membership in CollaborationSpaceIncomingMembership.entries) for (limited in
            listOf(false, true)) for (head in listOf(null, "\$head")) {
            val captured =
                physical.copy(membership = membership, limited = limited, headEventId = head)
            val result = resolved(base.copy(physicalRooms = listOf(captured)), captured)
            assertEquals(CollaborationSpaceIncomingPhase.Latest, result.demand.rooms.single().phase)
            assertEquals(head, result.demand.rooms.single().headEventId)
            assertTrue(result.demand.physicalRooms.isEmpty())
        }
    }

    @Test
    fun everyUnfinishedLogicalPhaseAndSourceKeepsBothRequirements() {
        val other = source.copy(storeId = "2".repeat(64))
        for (phase in CollaborationSpaceIncomingPhase.entries) for (owner in
            listOf(null, source.storeId, other.storeId)) {
            val logical =
                CollaborationSpaceIncomingRoomDemand(
                    id(20),
                    id(30),
                    0,
                    "\$head",
                    phase,
                    sourceId = owner,
                )
            val current =
                base.copy(
                    rooms = listOf(logical),
                    sources =
                        base.sources +
                            CollaborationSpaceIncomingSourceProgress(other, "other-position"),
                )
            val result = resolved(current)
            assertEquals(
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
                result.outcome,
            )
            assertEquals(current, result.demand)
        }
    }

    @Test
    fun transfersAfterPreviousWorkCompletesWithoutReusingItsCoverage() {
        val before =
            base.copy(
                rooms =
                    listOf(
                        CollaborationSpaceIncomingRoomDemand(
                            id(20),
                            id(30),
                            0,
                            "\$older",
                            CollaborationSpaceIncomingPhase.Pending,
                        )
                    )
            )
        assertEquals(
            CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
            resolved(before).outcome,
        )
        val completed =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        before,
                        CollaborationSpaceIncomingDemandChange.Complete(before.rooms.single()),
                    )
                )
                .value
        val next = resolved(completed)
        assertEquals(physical.generation, next.demand.rooms.single().generation)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, next.demand.rooms.single().phase)
    }

    @Test
    fun conversionPreservesGlobalFifoOrderAndWorksAtCombinedCapacity() {
        val rooms =
            (0..63)
                .filter { it != 1 }
                .map {
                    CollaborationSpaceIncomingRoomDemand(
                        id(100 + it),
                        id(200 + it),
                        it.toLong(),
                        null,
                        CollaborationSpaceIncomingPhase.Pending,
                    )
                }
        val full = base.copy(nextPosition = 64, rooms = rooms)
        val next = resolved(full).demand
        assertEquals(64, next.rooms.size)
        assertEquals((0L..63L).toList(), next.rooms.map { it.position })
        assertEquals(64L, next.nextPosition)
        assertTrue(next.physicalRooms.isEmpty())
        assertEquals(rooms, next.rooms.filter { it.generation != physical.generation })
    }

    @Test
    fun absentOrNewerPhysicalGenerationLeavesEveryFactUnchanged() {
        for (rooms in
            listOf(
                emptyList(),
                listOf(physical.copy(generation = id(99), headEventId = "\$newer")),
            )) {
            val current = base.copy(physicalRooms = rooms)
            val result = resolved(current)
            assertEquals(CollaborationSpaceIncomingBindingResolution.Obsolete, result.outcome)
            assertEquals(current, result.demand)
        }
    }

    @Test
    fun changedFactsUnderSameGenerationFailWithoutDiscardingThem() {
        for (changed in
            listOf(
                physical.copy(headEventId = "\$changed"),
                physical.copy(limited = true),
                physical.copy(position = 2),
                physical.copy(membership = CollaborationSpaceIncomingMembership.Left),
            )) {
            assertIs<AppResult.Failed>(
                resolveCollaborationSpaceIncomingPhysicalRoom(
                    base.copy(physicalRooms = listOf(changed)),
                    physical,
                    id(20),
                )
            )
        }
    }

    @Test
    fun sameRoomInAnotherStoreRemainsIndependent() {
        val other = source.copy(storeId = "2".repeat(64))
        val otherRoom = physical.copy(sourceId = other.storeId, generation = id(90), position = 2)
        val current =
            base.copy(
                sources = base.sources + CollaborationSpaceIncomingSourceProgress(other, "other"),
                physicalRooms = listOf(physical, otherRoom),
            )
        val next = resolved(current).demand
        assertEquals(listOf(otherRoom), next.physicalRooms)
        assertEquals(current.sources, next.sources)
        assertEquals(
            CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
            resolved(next, otherRoom).outcome,
        )
    }

    @Test
    fun invalidCapturedInputOrQueueIsRejected() {
        assertIs<AppResult.Failed>(
            resolveCollaborationSpaceIncomingPhysicalRoom(base, physical, "bad")
        )
        assertIs<AppResult.Failed>(
            resolveCollaborationSpaceIncomingPhysicalRoom(
                base,
                physical.copy(sourceId = "bad"),
                id(20),
            )
        )
        for (current in
            listOf(
                base.copy(sources = emptyList()),
                base.copy(nextPosition = 1),
                base.copy(physicalRooms = listOf(physical, physical)),
            )) {
            assertIs<AppResult.Failed>(
                resolveCollaborationSpaceIncomingPhysicalRoom(current, physical, id(20))
            )
        }
    }

    @Test
    fun returnedCollectionsDoNotBorrowMutableRepositoryLists() {
        val sources = base.sources.toMutableList()
        val rooms = base.physicalRooms.toMutableList()
        val next = resolved(base.copy(sources = sources, physicalRooms = rooms)).demand
        sources.clear()
        rooms.clear()
        assertEquals(base.sources, next.sources)
        assertEquals(1, next.rooms.size)
    }

    private fun resolved(
        current: CollaborationSpaceIncomingDemand,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand = physical,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingPhysicalResolution>>(
                resolveCollaborationSpaceIncomingPhysicalRoom(current, captured, id(20))
            )
            .value

    private fun id(value: Int) = "10000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
