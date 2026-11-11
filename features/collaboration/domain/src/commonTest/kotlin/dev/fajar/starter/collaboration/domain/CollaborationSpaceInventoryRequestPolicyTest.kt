package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceInventoryRequestPolicyTest {
    @Test
    fun repeatedLossCoalescesTheLaterPassWithoutRewindingActiveProgressOrBackoff() {
        var current =
            demand(progress.copy(inventoryAfterRoomId = roomId(15), inventoryRetry = retry))
        repeat(32) { index ->
            val before = current.sources.single()
            val next = batch(current, index, loss = true)
            assertTrue(next.wakeRequired)
            assertEquals(
                before.copy(
                    position = "next-$index",
                    inventoryRequestedGeneration = id(900 + index),
                ),
                next.demand.sources.single(),
            )
            current = next.demand
        }
        assertFalse(eligibleCollaborationSpaceInventory(current.sources.single(), 2_000))
        assertTrue(eligibleCollaborationSpaceInventory(current.sources.single(), 31_000))
    }

    @Test
    fun continuousResponsesKeepTheLaterRequestWithoutInventingAnotherWake() {
        val before = demand(requested)
        val next = batch(before, 0, loss = false)
        assertFalse(next.wakeRequired)
        assertEquals(requested.copy(position = "next-0"), next.demand.sources.single())
    }

    @Test
    fun replayCannotReplaceTheRequestedPassOrExtendRetry() {
        val before = demand(requested.copy(position = "next-0", inventoryRetry = retry))
        repeat(16) {
            val next = batch(before, 0, loss = true)
            assertEquals(before, next.demand)
            assertFalse(next.wakeRequired)
        }
    }

    @Test
    fun aLaterRequestRequiresADistinctValidActiveGeneration() {
        for (invalid in listOf("", "bad", id(700))) {
            assertFalse(
                validCollaborationSpaceIncomingSourceProgress(
                    progress.copy(inventoryRequestedGeneration = invalid)
                )
            )
        }
        assertFalse(
            validCollaborationSpaceIncomingSourceProgress(
                requested.copy(inventoryGeneration = null)
            )
        )
        assertTrue(validCollaborationSpaceIncomingSourceProgress(requested))
    }

    @Test
    fun requestedGenerationsCannotAliasRetainedWorkOrAnotherSourcePass() {
        val other =
            progress.copy(
                source = source.copy(storeId = "b".repeat(64)),
                inventoryGeneration = id(702),
            )
        val original =
            demand(requested)
                .copy(
                    nextPosition = 2,
                    rooms = listOf(logical),
                    physicalRooms = listOf(physical(1)),
                    sources = listOf(requested, other),
                    rescanGeneration = id(703),
                    scan = CollaborationSpaceIncomingScan(id(704)),
                )
        assertTrue(validCollaborationSpaceIncomingDemand(original))
        for (collision in
            listOf(
                logical.generation,
                physical(1).generation,
                id(700),
                id(702),
                id(703),
                id(704),
            )) {
            assertFalse(
                validCollaborationSpaceIncomingDemand(
                    original.copy(
                        sources =
                            listOf(requested.copy(inventoryRequestedGeneration = collision), other)
                    )
                )
            )
        }
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                original.copy(
                    sources = listOf(requested, other.copy(inventoryRequestedGeneration = id(701)))
                )
            )
        )
    }

    @Test
    fun syncBatchAllocationsCannotConsumeAReservedLaterGeneration() {
        val current = demand(requested)
        val response =
            CollaborationSpaceSyncBatch(runtime, source, "before", "after", emptyList(), true)
        assertIs<AppResult.Failed>(
            retainCollaborationSpaceSyncBatch(current, response, emptyList(), id(701))
        )
        val hint =
            CollaborationSpaceIncomingRoomHint(
                roomId(0),
                null,
                CollaborationSpaceIncomingMembership.Joined,
                false,
            )
        assertIs<AppResult.Failed>(
            retainCollaborationSpaceSyncBatch(
                current,
                response.copy(rooms = listOf(hint)),
                listOf(id(701)),
                id(900),
            )
        )
    }

    @Test
    fun inventoryAndAliasAllocationsCannotConsumeAReservedLaterGeneration() {
        val current = demand(requested)
        assertIs<AppResult.Failed>(
            retainCollaborationSpaceInventoryPage(
                current,
                requested,
                page(requested, listOf(room(0))),
                listOf(id(701)),
            )
        )
        assertIs<AppResult.Failed>(
            routeCollaborationSpaceIncomingAlias(
                current.copy(nextPosition = 1, rooms = listOf(logical)),
                logical,
                source,
                CollaborationSpaceIncomingTarget.Original(
                    CollaborationOriginalConversationPosition(id(10), id(11))
                ),
                id(701),
            )
        )
    }

    @Test
    fun eofPromotesTheLaterPassAndRetainsAllAlreadyQueuedWork() {
        val before = requested.copy(inventoryAfterRoomId = roomId(15), inventoryRetry = retry)
        val current = demand(before).copy(nextPosition = 1, physicalRooms = listOf(physical(0)))
        val result = retain(current, before, emptyList())
        assertEquals(CollaborationSpaceInventoryOutcome.Advanced, result.outcome)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "before", id(701)),
            result.demand.sources.single(),
        )
        assertEquals(current.physicalRooms, result.demand.physicalRooms)
        val final = retain(result.demand, result.demand.sources.single(), emptyList())
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, final.outcome)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "before"),
            final.demand.sources.single(),
        )
        assertEquals(current.physicalRooms, final.demand.physicalRooms)
    }

    @Test
    fun aRequestArrivingAfterCaptureIsPromotedWithoutLosingItsActualPosition() {
        val current =
            demand(requested.copy(position = "after", inventoryRequestedGeneration = id(702)))
        val result = retain(current, progress, emptyList())
        assertEquals(CollaborationSpaceInventoryOutcome.Advanced, result.outcome)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "after", id(702)),
            result.demand.sources.single(),
        )
        val replay = retain(result.demand, progress, emptyList())
        assertEquals(CollaborationSpaceInventoryOutcome.Obsolete, replay.outcome)
        assertEquals(result.demand, replay.demand)
    }

    @Test
    fun finitePagesReachTheirEndDespiteNewLossBetweenEveryPage() {
        var current = demand()
        val retainedRooms = mutableListOf<String>()
        repeat(4) { index ->
            current = batch(current, index, loss = true).demand
            val captured = current.sources.single()
            val rooms = (index * 16 until (index + 1) * 16).map(::room)
            val result =
                retain(
                    current,
                    captured,
                    rooms,
                    if (index < 3) rooms.last().roomId else null,
                    2_000 + index * 16,
                )
            retainedRooms += rooms.map { it.roomId }
            assertEquals(retainedRooms, result.demand.physicalRooms.map { it.roomId })
            assertEquals(CollaborationSpaceInventoryOutcome.Advanced, result.outcome)
            if (index < 3) {
                assertEquals(id(700), result.demand.sources.single().inventoryGeneration)
                assertEquals(
                    rooms.last().roomId,
                    result.demand.sources.single().inventoryAfterRoomId,
                )
            }
            current = result.demand
        }
        assertEquals(64, current.physicalRooms.size)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "next-3", id(903)),
            current.sources.single(),
        )
        // This proves finite cursor progress while slots are available, not key-blocked queue
        // drainage.
    }

    @Test
    fun overflowKeepsTheBlockedRoomAndBothPassesUntilCapacityIsAvailable() {
        val old = (0 until 64).map(::physical)
        val current = demand(requested).copy(nextPosition = 64, physicalRooms = old)
        val hint =
            CollaborationSpaceIncomingRoomHint(
                roomId(100),
                null,
                CollaborationSpaceIncomingMembership.Joined,
                false,
            )
        val response =
            CollaborationSpaceSyncBatch(runtime, source, "before", "after", listOf(hint), false)
        val merged =
            assertIs<AppResult.Success<CollaborationSpaceIncomingBatchRetention>>(
                    retainCollaborationSpaceSyncBatch(current, response, listOf(id(2_000)), id(900))
                )
                .value
        val captured = merged.demand.sources.single()
        assertEquals(id(700), captured.inventoryGeneration)
        assertEquals(id(900), captured.inventoryRequestedGeneration)
        val blocked = retain(merged.demand, captured, listOf(room(100)))
        assertEquals(CollaborationSpaceInventoryOutcome.Capacity, blocked.outcome)
        assertEquals(merged.demand, blocked.demand)
        assertEquals(old, blocked.demand.physicalRooms)
    }

    @Test
    fun anotherSourceRetainsItsActiveAndRequestedPassesWhenThisOneCompletes() {
        val other =
            requested.copy(
                source = source.copy(storeId = "b".repeat(64)),
                inventoryGeneration = id(702),
                inventoryRequestedGeneration = id(703),
                inventoryAfterRoomId = roomId(15),
                inventoryRetry = retry,
            )
        val current = demand(requested).copy(sources = listOf(requested, other))
        val result = retain(current, requested, emptyList())
        assertEquals(other, result.demand.sources[1])
        assertEquals(id(701), result.demand.sources[0].inventoryGeneration)
    }

    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
    private val progress = CollaborationSpaceIncomingSourceProgress(source, "before", id(700))
    private val requested = progress.copy(inventoryRequestedGeneration = id(701))
    private val retry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1)
    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://fixture.test/", id(1), id(2)),
                id(3),
            ),
            id(4),
            id(5),
        )
    private val logical =
        CollaborationSpaceIncomingRoomDemand(
            CollaborationSpaceIncomingTarget.Published(id(400)),
            id(300),
            0,
            null,
            CollaborationSpaceIncomingPhase.Latest,
            null,
            source.storeId,
        )

    private fun demand(value: CollaborationSpaceIncomingSourceProgress = progress) =
        CollaborationSpaceIncomingDemand(sources = listOf(value))

    private fun batch(current: CollaborationSpaceIncomingDemand, index: Int, loss: Boolean) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingBatchRetention>>(
                retainCollaborationSpaceSyncBatch(
                    current,
                    CollaborationSpaceSyncBatch(
                        runtime,
                        source,
                        current.sources.single().position,
                        "next-$index",
                        emptyList(),
                        loss,
                    ),
                    emptyList(),
                    id(900 + index),
                )
            )
            .value

    private fun page(
        captured: CollaborationSpaceIncomingSourceProgress,
        rooms: List<CollaborationSpaceInventoryRoom>,
        next: String? = null,
    ) =
        CollaborationSpaceRoomInventoryPage(
            runtime,
            source,
            captured.inventoryAfterRoomId,
            rooms,
            next,
        )

    private fun retain(
        current: CollaborationSpaceIncomingDemand,
        captured: CollaborationSpaceIncomingSourceProgress,
        rooms: List<CollaborationSpaceInventoryRoom>,
        next: String? = null,
        generationBase: Int = 2_000,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInventoryRetention>>(
                retainCollaborationSpaceInventoryPage(
                    current,
                    captured,
                    page(captured, rooms, next),
                    rooms.indices.map { id(generationBase + it) },
                )
            )
            .value

    private fun room(index: Int) =
        CollaborationSpaceInventoryRoom(roomId(index), CollaborationSpaceInventoryMembership.Joined)

    private fun physical(index: Int) =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            roomId(index),
            id(100 + index),
            index.toLong(),
            null,
            CollaborationSpaceIncomingMembership.Joined,
            true,
        )

    private fun roomId(index: Int) = "!" + index.toString().padStart(3, '0') + ":test"

    private fun id(value: Int) = "10000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
