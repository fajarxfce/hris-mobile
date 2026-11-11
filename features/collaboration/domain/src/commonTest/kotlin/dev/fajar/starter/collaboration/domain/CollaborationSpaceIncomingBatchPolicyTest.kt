package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingBatchPolicyTest {
    @Test
    fun firstAttachRetainsInventoryEvenWhenNoRoomWasReported() {
        val result = retain(CollaborationSpaceIncomingDemand(), batch(rooms = emptyList()))
        assertTrue(result.wakeRequired)
        assertEquals(
            listOf(CollaborationSpaceIncomingSourceProgress(source, "next", id(200))),
            result.demand.sources,
        )
        assertTrue(result.demand.physicalRooms.isEmpty())
        assertNull(result.demand.rescanGeneration)
    }

    @Test
    fun continuousEmptyResponseAdvancesOnlyItsOwnCursorWithoutWakingOldWork() {
        val before =
            queued()
                .copy(
                    sources =
                        listOf(progress().copy(inventoryGeneration = id(50)), progress(otherSource))
                )
        val result = retain(before, batch(rooms = emptyList()))
        assertFalse(result.wakeRequired)
        assertEquals(
            before.copy(
                sources = listOf(before.sources[0].copy(position = "next"), before.sources[1])
            ),
            result.demand,
        )
    }

    @Test
    fun exactReplayAfterCompletionDoesNotRecreateRoomOrInventoryDemand() {
        val received = batch()
        val first = retain(CollaborationSpaceIncomingDemand(), received).demand
        val completed =
            first.copy(
                physicalRooms = emptyList(),
                sources = listOf(first.sources.single().copy(inventoryGeneration = null)),
            )
        repeat(100) {
            val replay = retain(completed, received)
            assertEquals(completed, replay.demand)
            assertFalse(replay.wakeRequired)
        }
    }

    @Test
    fun unchangedNonNullHeadPreservesGenerationFifoBackoffAndStrongerLimitedFlag() {
        val before = queued(limited = true)
        val result = retain(before, batch())
        assertFalse(result.wakeRequired)
        assertEquals(before.physicalRooms, result.demand.physicalRooms)
        assertEquals("next", result.demand.sources.single().position)
    }

    @Test
    fun changedOrUnknownHeadKeepsItsTurnAndBackoffWithANewGeneration() {
        for (head in listOf("\$new", null)) {
            val before = queued(limited = true)
            val result = retain(before, batch(rooms = listOf(hint().copy(headEventId = head))))
            assertTrue(result.wakeRequired)
            assertEquals(
                before.physicalRooms.single().copy(generation = id(100), headEventId = head),
                result.demand.physicalRooms.single(),
            )
            assertEquals(before.nextPosition, result.demand.nextPosition)
            assertNull(result.demand.sources.single().inventoryGeneration)
        }
    }

    @Test
    fun repeatedUnknownHeadCannotLetEarlierCompletionDischargeNewerWork() {
        val before =
            queued()
                .copy(physicalRooms = queued().physicalRooms.map { it.copy(headEventId = null) })
        val result = retain(before, batch(rooms = listOf(hint().copy(headEventId = null))))
        assertTrue(result.wakeRequired)
        assertNotEquals(
            before.physicalRooms.single().generation,
            result.demand.physicalRooms.single().generation,
        )
    }

    @Test
    fun membershipOrNewLimitedRequirementCreatesWorkWithoutTreatingItAsRevocation() {
        for (hint in
            listOf(
                hint().copy(membership = CollaborationSpaceIncomingMembership.Left),
                hint().copy(limited = true),
            )) {
            val before = queued()
            val result = retain(before, batch(rooms = listOf(hint)))
            assertTrue(result.wakeRequired)
            val room = result.demand.physicalRooms.single()
            assertEquals(hint.membership, room.membership)
            assertEquals(hint.limited, room.limited)
            assertEquals(before.physicalRooms.single().retry, room.retry)
            assertEquals(before.physicalRooms.single().position, room.position)
        }
    }

    @Test
    fun independentSourcesNeverCoalesceTheSamePhysicalRoom() {
        val before = queued()
        val result = retain(before, batch().copy(source = otherSource))
        assertTrue(result.wakeRequired)
        assertEquals(2, result.demand.physicalRooms.size)
        assertEquals(before.physicalRooms.single(), result.demand.physicalRooms.first())
        assertEquals(otherSource.storeId, result.demand.physicalRooms.last().sourceId)
        assertEquals(before.nextPosition, result.demand.physicalRooms.last().position)
        assertEquals(before.sources.single(), result.demand.sources.first())
    }

    @Test
    fun newRoomsFollowExistingCombinedFifoWithoutResettingLogicalPhases() {
        val logical =
            CollaborationSpaceIncomingRoomDemand(
                id(11),
                id(12),
                0,
                "\$logical",
                CollaborationSpaceIncomingPhase.Pending,
            )
        val before =
            queued()
                .copy(
                    nextPosition = 2,
                    rooms = listOf(logical),
                    physicalRooms = queued().physicalRooms.map { it.copy(position = 1) },
                )
        val result =
            retain(before, batch(rooms = listOf(hint("!second:test"), hint("!third:test"))))
        assertEquals(before.rooms, result.demand.rooms)
        assertEquals(before.physicalRooms.single(), result.demand.physicalRooms.first())
        assertEquals(listOf(1L, 2L, 3L), result.demand.physicalRooms.map { it.position })
        assertEquals(4L, result.demand.nextPosition)
    }

    @Test
    fun fullCombinedRoomCapacityRetainsLossBeforeAdvancingCursor() {
        val before = fullQueue()
        val result = retain(before, batch(rooms = listOf(hint("!overflow:test"))))
        assertTrue(result.wakeRequired)
        assertEquals(before.rooms, result.demand.rooms)
        assertEquals(before.physicalRooms, result.demand.physicalRooms)
        assertEquals(before.nextPosition, result.demand.nextPosition)
        assertEquals(
            progress().copy(position = "next", inventoryGeneration = id(200)),
            result.demand.sources.single(),
        )
    }

    @Test
    fun existingPhysicalRoomStillUpdatesWhenCombinedCapacityIsFull() {
        val before = fullQueue()
        val result = retain(before, batch(rooms = listOf(hint().copy(headEventId = "\$new"))))
        assertTrue(result.wakeRequired)
        assertEquals(before.rooms, result.demand.rooms)
        assertEquals("\$new", result.demand.physicalRooms.single().headEventId)
        assertNull(result.demand.sources.single().inventoryGeneration)
    }

    @Test
    fun missingPreviousSkippedResponseAndExplicitSdkLossRequireInventory() {
        for (response in
            listOf(
                batch(previous = null),
                batch(previous = "skipped"),
                batch().copy(inventoryRequired = true),
            )) {
            val result = retain(queued(), response)
            assertTrue(result.wakeRequired)
            assertEquals(id(200), result.demand.sources.single().inventoryGeneration)
        }
    }

    @Test
    fun laterLossHasANewGenerationWithoutResettingTheIndependentBindingScan() {
        val scan = CollaborationSpaceIncomingScan(id(41))
        val before =
            queued()
                .copy(
                    rescanGeneration = id(42),
                    scan = scan,
                    sources = listOf(progress().copy(inventoryGeneration = id(43))),
                )
        val result = retain(before, batch().copy(inventoryRequired = true))
        assertEquals(id(43), result.demand.sources.single().inventoryGeneration)
        assertEquals(id(200), result.demand.sources.single().inventoryRequestedGeneration)
        assertEquals(before.scan, result.demand.scan)
        assertEquals(before.rescanGeneration, result.demand.rescanGeneration)
        assertEquals(before.physicalRooms, result.demand.physicalRooms)
    }

    @Test
    fun storeIdentityCollisionCannotChangeMatrixUserOrDeviceEvenForReplay() {
        val before = queued()
        for (changed in
            listOf(
                source.copy(matrixUserId = "@other:matrix.test"),
                source.copy(matrixDeviceId = "OTHER"),
            )) {
            for (next in listOf("old", "next")) {
                val result = plan(before, batch(next = next).copy(source = changed))
                assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
            }
        }
        assertEquals(listOf(progress()), before.sources)
    }

    @Test
    fun newSourceAtCapacityRetiresOnlyAnUnreferencedCleanProgressRecord() {
        val sources =
            List(64) { progress(source.copy(storeId = (it + 10).toString(16).padStart(64, '0'))) }
        val before = CollaborationSpaceIncomingDemand(sources = sources)
        val result = retain(before, batch(rooms = emptyList()))
        assertEquals(sources.drop(1), result.demand.sources.dropLast(1))
        assertEquals(source, result.demand.sources.last().source)
        assertEquals(64, result.demand.sources.size)
        assertTrue(result.wakeRequired)
    }

    @Test
    fun pendingInventoryAndLogicalOrPhysicalReferencesProtectSourceCapacity() {
        val sources =
            List(64) {
                    progress(
                        source.copy(storeId = (it + 10).toString(16).padStart(64, '0')),
                        inventory = id(it + 500),
                    )
                }
                .toMutableList()
        sources[0] = sources[0].copy(inventoryGeneration = null)
        sources[1] = sources[1].copy(inventoryGeneration = null)
        val logical =
            CollaborationSpaceIncomingRoomDemand(
                id(2),
                id(3),
                0,
                null,
                CollaborationSpaceIncomingPhase.Provider,
                sourceId = sources[0].source.storeId,
            )
        val physical =
            queued().physicalRooms.single().copy(sourceId = sources[1].source.storeId, position = 1)
        val before =
            CollaborationSpaceIncomingDemand(
                2,
                listOf(logical),
                sources = sources,
                physicalRooms = listOf(physical),
            )
        val result = assertIs<AppResult.Failed>(plan(before, batch()))
        assertEquals("client.collaboration_incoming_source_capacity", result.failure.code)
        assertEquals(64, before.sources.size)
    }

    @Test
    fun responseForAnExistingSourceDoesNotEvictAnySourceAtCapacity() {
        val sources =
            listOf(progress()) +
                List(63) {
                    progress(
                        source.copy(storeId = (it + 10).toString(16).padStart(64, '0')),
                        inventory = id(it + 500),
                    )
                }
        val before = CollaborationSpaceIncomingDemand(sources = sources)
        val result = retain(before, batch(rooms = emptyList()))
        assertFalse(result.wakeRequired)
        assertEquals(sources.drop(1), result.demand.sources.drop(1))
    }

    @Test
    fun exhaustedFifoCounterCannotPublishAPositionWithoutRetainingNewWork() {
        val before =
            CollaborationSpaceIncomingDemand(
                nextPosition = 9_007_199_254_740_991L,
                sources = listOf(progress()),
            )
        assertIs<AppResult.Failed>(plan(before, batch()))
        assertEquals("old", before.sources.single().position)
    }

    @Test
    fun invalidOrReusedGenerationsCannotReplaceLiveWork() {
        val before = queued()
        for ((rooms, inventory) in
            listOf(
                emptyList<String>() to id(200),
                listOf("invalid") to id(200),
                listOf(id(100)) to "invalid",
                listOf(id(100)) to id(100),
                listOf(before.physicalRooms.single().generation) to id(200),
            )) {
            assertIs<AppResult.Failed>(
                retainCollaborationSpaceSyncBatch(before, batch(), rooms, inventory)
            )
        }
    }

    @Test
    fun duplicateOversizedOrInvalidResponseLocatorsAreRejectedBeforeRetention() {
        for (response in
            listOf(
                batch(next = ""),
                batch(previous = ""),
                batch(next = "x".repeat(4097)),
                batch(rooms = List(65) { hint("!$it:test") }),
                batch(rooms = listOf(hint(), hint())),
                batch(rooms = listOf(hint().copy(roomId = "not-a-room"))),
                batch(rooms = listOf(hint().copy(headEventId = "invalid"))),
                batch().copy(source = source.copy(storeId = id(9))),
            )) {
            assertNull(captureCollaborationSpaceSyncBatch(response))
            assertIs<AppResult.Failed>(plan(queued(), response))
        }
    }

    @Test
    fun capturedBatchAndRetainedDemandDoNotFollowMutableInputLists() {
        val rooms = mutableListOf(hint())
        val captured = assertNotNull(captureCollaborationSpaceSyncBatch(batch(rooms = rooms)))
        rooms.clear()
        assertEquals(listOf(hint()), captured.rooms)
        val physical = queued().physicalRooms.toMutableList()
        val sources = queued().sources.toMutableList()
        val result = retain(queued().copy(physicalRooms = physical, sources = sources), captured)
        physical.clear()
        sources.clear()
        assertEquals(1, result.demand.physicalRooms.size)
        assertEquals(1, result.demand.sources.size)
    }

    @Test
    fun diagnosticsDoNotExposePhysicalLocatorsOrOpaquePositions() {
        val batch = batch(next = "secret-position")
        for (rendered in
            listOf(batch.toString(), hint().toString(), retain(queued(), batch).toString())) {
            assertFalse("secret-position" in rendered)
            assertFalse(source.storeId in rendered)
            assertFalse(hint().roomId in rendered)
        }
    }

    private fun retain(
        current: CollaborationSpaceIncomingDemand,
        response: CollaborationSpaceSyncBatch,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingBatchRetention>>(
                plan(current, response)
            )
            .value

    private fun plan(
        current: CollaborationSpaceIncomingDemand,
        response: CollaborationSpaceSyncBatch,
    ) =
        retainCollaborationSpaceSyncBatch(
            current,
            response,
            List(response.rooms.size) { id(100 + it) },
            id(200),
        )

    private fun queued(limited: Boolean = false) =
        CollaborationSpaceIncomingDemand(
            nextPosition = 1,
            sources = listOf(progress()),
            physicalRooms =
                listOf(
                    CollaborationSpaceIncomingPhysicalRoomDemand(
                        source.storeId,
                        hint().roomId,
                        id(20),
                        0,
                        hint().headEventId,
                        hint().membership,
                        limited,
                        CollaborationSpaceIncomingRetry(1_000, 61_000, 2),
                    )
                ),
        )

    private fun fullQueue(): CollaborationSpaceIncomingDemand {
        val before = queued()
        return before.copy(
            nextPosition = 64,
            rooms =
                List(63) {
                    CollaborationSpaceIncomingRoomDemand(
                        id(1000 + it),
                        id(2000 + it),
                        it + 1L,
                        null,
                        CollaborationSpaceIncomingPhase.Pending,
                    )
                },
        )
    }

    private fun batch(
        next: String = "next",
        previous: String? = "old",
        rooms: List<CollaborationSpaceIncomingRoomHint> = listOf(hint()),
    ) = CollaborationSpaceSyncBatch(runtime, source, previous, next, rooms, false)

    private fun progress(
        value: CollaborationSpaceIncomingSource = source,
        inventory: String? = null,
    ) = CollaborationSpaceIncomingSourceProgress(value, "old", inventory)

    private fun hint(roomId: String = "!room:test") =
        CollaborationSpaceIncomingRoomHint(
            roomId,
            "\$head",
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private val source =
        CollaborationSpaceIncomingSource("1".repeat(64), "@sender:matrix.test", "DEVICE")
    private val otherSource = source.copy(storeId = "2".repeat(64))
    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(2)),
                id(3),
            ),
            id(4),
            id(5),
        )
}
