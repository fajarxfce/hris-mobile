package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceInventoryRetentionPolicyTest {
    @Test
    fun firstPageRetainsFreshLatestRequirementsBeforeAdvancingItsCursor() {
        val page = page((0 until 16).map { joined(roomId(it)) }, next = roomId(15))
        val result = retained(demand(), progress, page)
        assertEquals(CollaborationSpaceInventoryOutcome.Advanced, result.outcome)
        assertEquals(16, result.acceptedRooms)
        assertEquals(16L, result.demand.nextPosition)
        assertEquals(
            progress.copy(inventoryAfterRoomId = roomId(15)),
            result.demand.sources.single(),
        )
        for ((index, room) in result.demand.physicalRooms.withIndex()) {
            assertEquals(
                physical(index)
                    .copy(generation = id(800 + index), headEventId = null, limited = true),
                room,
            )
        }
    }

    @Test
    fun finishingAPageKeepsWorkAndTheRealPositionWhileClearingOnlyInventoryFields() {
        val before = progress.copy(inventoryAfterRoomId = roomId(1), inventoryRetry = retry)
        val result =
            retained(demand(before), before, page(listOf(joined(roomId(2))), after = roomId(1)))
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, result.outcome)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "before"),
            result.demand.sources.single(),
        )
        assertEquals(listOf(roomId(2)), result.demand.physicalRooms.map { it.roomId })
    }

    @Test
    fun anEmptyFinalPageFinishesWithoutInventingRoomWorkOrAProviderPosition() {
        val before = progress.copy(inventoryAfterRoomId = roomId(3))
        val result = retained(demand(before), before, page(emptyList(), after = roomId(3)))
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, result.outcome)
        assertEquals(0, result.acceptedRooms)
        assertTrue(result.demand.physicalRooms.isEmpty())
        assertEquals("before", result.demand.sources.single().position)
    }

    @Test
    fun continuousResponsesCanAdvanceWhileAnInventoryPageIsInFlight() {
        val current = demand(progress.copy(position = "after-continuous-response"))
        val result = retained(current, progress, page(listOf(joined(roomId(0)))))
        assertEquals("after-continuous-response", result.demand.sources.single().position)
        assertEquals(1, result.demand.physicalRooms.size)
    }

    @Test
    fun aNewLossCannotBeClearedByAnOlderPage() {
        val current =
            demand(progress.copy(position = "new-response", inventoryGeneration = id(701)))
        val result = retained(current, progress, page(emptyList()))
        assertEquals(CollaborationSpaceInventoryOutcome.Obsolete, result.outcome)
        assertEquals(current, result.demand)
    }

    @Test
    fun replayCannotRewindACursorOrDuplicateThePreviouslyRetainedPrefix() {
        val first =
            retained(
                demand(),
                progress,
                page((0 until 16).map { joined(roomId(it)) }, next = roomId(15)),
            )
        repeat(3) {
            val replay =
                retained(
                    first.demand,
                    progress,
                    page((0 until 16).map { joined(roomId(it)) }, next = roomId(15)),
                )
            assertEquals(CollaborationSpaceInventoryOutcome.Obsolete, replay.outcome)
            assertEquals(first.demand, replay.demand)
        }
    }

    @Test
    fun newerDeferralPreventsAnOlderAcquisitionFromClearingBackoff() {
        val current = demand(progress.copy(inventoryRetry = retry))
        val result = retained(current, progress, page(emptyList()))
        assertEquals(CollaborationSpaceInventoryOutcome.Obsolete, result.outcome)
        assertEquals(current, result.demand)
    }

    @Test
    fun inventoryStrengthensPhysicalWorkWithoutLosingFifoBackoffOrSource() {
        val old = physical(0).copy(retry = retry)
        val current = demand().copy(nextPosition = 1, physicalRooms = listOf(old))
        val result = retained(current, progress, page(listOf(joined(roomId(0)))))
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, result.outcome)
        assertEquals(1, result.acceptedRooms)
        assertEquals(current.nextPosition, result.demand.nextPosition)
        val next = result.demand.physicalRooms.single()
        assertEquals(old.copy(generation = id(800), headEventId = null, limited = true), next)
        val stale =
            assertIs<AppResult.Success<CollaborationSpaceIncomingPhysicalResolution>>(
                    resolveCollaborationSpaceIncomingPhysicalRoom(result.demand, old, id(900))
                )
                .value
        assertEquals(CollaborationSpaceIncomingBindingResolution.Obsolete, stale.outcome)
        assertEquals(result.demand, stale.demand)
    }

    @Test
    fun aPartialPagePreservesALeftHintAndRetriesThatExactRoomAfterItDrains() {
        val old =
            physical(2).copy(position = 0, membership = CollaborationSpaceIncomingMembership.Left)
        val current = demand().copy(nextPosition = 1, physicalRooms = listOf(old))
        val first = retained(current, progress, page((0..3).map { joined(roomId(it)) }))
        assertEquals(CollaborationSpaceInventoryOutcome.WaitingForRoom, first.outcome)
        assertEquals(2, first.acceptedRooms)
        assertEquals(roomId(1), first.demand.sources.single().inventoryAfterRoomId)
        assertEquals(old, first.demand.physicalRooms.first())
        val drained =
            first.demand.copy(physicalRooms = first.demand.physicalRooms.filterNot { it == old })
        val capture = drained.sources.single()
        val next =
            retained(
                drained,
                capture,
                page((2..3).map { joined(roomId(it)) }, after = roomId(1)),
                generationBase = 900,
            )
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, next.outcome)
        assertEquals(4, next.demand.physicalRooms.size)
        assertNull(next.demand.physicalRooms.single { it.roomId == roomId(2) }.headEventId)
    }

    @Test
    fun capacityRetainsOnlyThePrefixThatFitsWithoutSkippingTheBlockedRoom() {
        val old =
            (100 until 163).mapIndexed { index, n -> physical(n).copy(position = index.toLong()) }
        val current = demand().copy(nextPosition = 63, physicalRooms = old)
        val result = retained(current, progress, page((0..2).map { joined(roomId(it)) }))
        assertEquals(CollaborationSpaceInventoryOutcome.Capacity, result.outcome)
        assertEquals(1, result.acceptedRooms)
        assertEquals(roomId(0), result.demand.sources.single().inventoryAfterRoomId)
        assertEquals(old, result.demand.physicalRooms.take(63))
        assertEquals(64, result.demand.physicalRooms.size)
    }

    @Test
    fun logicalAndPhysicalWorkShareCapacityWithoutDiscardingEither() {
        val logical = (0 until 64).map { logical(it) }
        val current = demand().copy(nextPosition = 64, rooms = logical)
        val result = retained(current, progress, page(listOf(joined(roomId(0)))))
        assertEquals(CollaborationSpaceInventoryOutcome.Capacity, result.outcome)
        assertEquals(current, result.demand)
    }

    @Test
    fun unfinishedLogicalProgressCanCoexistWithAnIndependentFreshPhysicalRequirement() {
        val old = logical(0).copy(phase = CollaborationSpaceIncomingPhase.Pending, retry = retry)
        val current = demand().copy(nextPosition = 1, rooms = listOf(old))
        val result = retained(current, progress, page(listOf(joined(roomId(0)))))
        assertEquals(listOf(old), result.demand.rooms)
        assertEquals(1, result.demand.physicalRooms.size)
        assertEquals(1L, result.demand.physicalRooms.single().position)
    }

    @Test
    fun nonJoinedMembershipsCreateNoContentWorkAndDoNotRemoveExistingWork() {
        val old = physical(0)
        val current = demand().copy(nextPosition = 1, physicalRooms = listOf(old))
        val memberships =
            listOf(
                CollaborationSpaceInventoryMembership.Invited,
                CollaborationSpaceInventoryMembership.Knocked,
                CollaborationSpaceInventoryMembership.Left,
                CollaborationSpaceInventoryMembership.Banned,
            )
        val result =
            retained(
                current,
                progress,
                page(
                    memberships.mapIndexed { index, membership ->
                        CollaborationSpaceInventoryRoom(roomId(index), membership)
                    }
                ),
            )
        assertEquals(CollaborationSpaceInventoryOutcome.Complete, result.outcome)
        assertEquals(4, result.acceptedRooms)
        assertEquals(listOf(old), result.demand.physicalRooms)
    }

    @Test
    fun anotherOriginalStoreWithTheSameRoomIdNeverBlocksOrLosesItsWork() {
        val other =
            source.copy(
                storeId = "b".repeat(64),
                matrixUserId = "@second:test",
                matrixDeviceId = "SECOND",
            )
        val original = physical(0).copy(sourceId = other.storeId)
        val current =
            demand()
                .copy(
                    nextPosition = 1,
                    physicalRooms = listOf(original),
                    sources =
                        listOf(
                            progress,
                            CollaborationSpaceIncomingSourceProgress(
                                other,
                                "second",
                                id(701),
                                roomId(7),
                                retry,
                            ),
                        ),
                )
        val result = retained(current, progress, page(listOf(joined(roomId(0)))))
        assertEquals(original, result.demand.physicalRooms.first())
        assertEquals(current.sources[1], result.demand.sources[1])
        assertEquals(2, result.demand.physicalRooms.size)
    }

    @Test
    fun mismatchedSourceOrCursorCannotRetainAPage() {
        val pages =
            listOf(
                page(emptyList()).copy(source = source.copy(matrixDeviceId = "OTHER")),
                page(emptyList(), after = roomId(0)),
            )
        for (page in pages) assertIs<AppResult.Failed>(
            retainCollaborationSpaceInventoryPage(demand(), progress, page, emptyList())
        )
    }

    @Test
    fun malformedOrderingBoundsOrContinuationCannotProduceCoverage() {
        val pages =
            listOf(
                page(listOf(joined(roomId(1)), joined(roomId(0)))),
                page(listOf(joined(roomId(0)), joined(roomId(0)))),
                page((0..16).map { joined(roomId(it)) }),
                page(emptyList(), next = roomId(0)),
                page(listOf(joined(roomId(0))), next = roomId(0)),
                page(listOf(joined("!\uD800:test"))),
                page(listOf(joined(roomId(0))), after = roomId(0)),
            )
        for (page in pages) assertNull(captureCollaborationSpaceInventoryPage(page))
    }

    @Test
    fun generationCollisionsAndExhaustedFifoNeverPartiallyAdvanceInventory() {
        val page = page(listOf(joined(roomId(0))))
        for (ids in listOf(emptyList(), listOf("bad"), listOf(progress.inventoryGeneration!!))) {
            assertIs<AppResult.Failed>(
                retainCollaborationSpaceInventoryPage(demand(), progress, page, ids)
            )
        }
        assertIs<AppResult.Failed>(
            retainCollaborationSpaceInventoryPage(
                demand().copy(nextPosition = 9_007_199_254_740_991L),
                progress,
                page,
                listOf(id(800)),
            )
        )
    }

    @Test
    fun capturedMetadataIsDetachedFromTheDatasourceList() {
        val original = mutableListOf(joined(roomId(0)))
        val captured = assertNotNull(captureCollaborationSpaceInventoryPage(page(original)))
        original.clear()
        assertEquals(listOf(joined(roomId(0))), captured.rooms)
    }

    @Test
    fun fullCapacityStillAllowsStrengtheningTheExactPhysicalRequirement() {
        val rooms = (0 until 64).map(::physical)
        val current = demand().copy(nextPosition = 64, physicalRooms = rooms)
        val next = retained(current, progress, page(listOf(joined(roomId(0))))).demand
        assertEquals(64, next.physicalRooms.size)
        assertEquals(64L, next.nextPosition)
        assertEquals(rooms.drop(1), next.physicalRooms.drop(1))
        assertNull(next.physicalRooms.first().headEventId)
        assertNotEquals(rooms.first().generation, next.physicalRooms.first().generation)
    }

    @Test
    fun alreadyUnknownWorkStillGetsANewGenerationForAnIndependentInventoryPass() {
        val old = physical(0).copy(headEventId = null, limited = true)
        val current = demand().copy(nextPosition = 1, physicalRooms = listOf(old))
        val next = retained(current, progress, page(listOf(joined(roomId(0))))).demand
        assertEquals(old.copy(generation = id(800)), next.physicalRooms.single())
    }

    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
    private val progress = CollaborationSpaceIncomingSourceProgress(source, "before", id(700))
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

    private fun demand(value: CollaborationSpaceIncomingSourceProgress = progress) =
        CollaborationSpaceIncomingDemand(sources = listOf(value))

    private fun page(
        rooms: List<CollaborationSpaceInventoryRoom>,
        after: String? = null,
        next: String? = null,
    ) = CollaborationSpaceRoomInventoryPage(runtime, source, after, rooms, next)

    private fun joined(roomId: String) =
        CollaborationSpaceInventoryRoom(roomId, CollaborationSpaceInventoryMembership.Joined)

    private fun physical(index: Int) =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            roomId(index),
            id(100 + index),
            index.toLong(),
            "\$known-$index",
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )

    private fun logical(index: Int) =
        CollaborationSpaceIncomingRoomDemand(
            CollaborationSpaceIncomingTarget.Published(id(400 + index)),
            id(100 + index),
            index.toLong(),
            "\$known-$index",
            CollaborationSpaceIncomingPhase.Provider,
            null,
            source.storeId,
        )

    private fun retained(
        current: CollaborationSpaceIncomingDemand,
        capture: CollaborationSpaceIncomingSourceProgress,
        page: CollaborationSpaceRoomInventoryPage,
        generationBase: Int = 800,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInventoryRetention>>(
                retainCollaborationSpaceInventoryPage(
                    current,
                    capture,
                    page,
                    page.rooms.indices.map { id(generationBase + it) },
                )
            )
            .value

    private fun roomId(index: Int) = "!" + index.toString().padStart(3, '0') + ":test"

    private fun id(value: Int) = "10000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
