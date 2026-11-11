package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceInventoryProgressPolicyTest {
    @Test
    fun activeProgressAcceptsACursorWithoutRequiringARetry() {
        assertTrue(
            validCollaborationSpaceIncomingSourceProgress(progress().copy(inventoryRetry = null))
        )
    }

    @Test
    fun retryBeforeTheFirstPageDoesNotInventACursor() {
        val before = progress().copy(inventoryAfterRoomId = null)
        assertTrue(validCollaborationSpaceIncomingSourceProgress(before))
        assertNull(before.inventoryAfterRoomId)
        assertEquals("before", before.position)
    }

    @Test
    fun missingInventoryGenerationRejectsEveryRelatedField() {
        for (invalid in
            listOf(
                progress().copy(inventoryGeneration = null),
                progress().copy(inventoryGeneration = null, inventoryAfterRoomId = null),
                progress().copy(inventoryGeneration = null, inventoryRetry = null),
            )) assertFalse(validCollaborationSpaceIncomingSourceProgress(invalid))
        assertTrue(
            validCollaborationSpaceIncomingSourceProgress(
                progress()
                    .copy(
                        inventoryGeneration = null,
                        inventoryAfterRoomId = null,
                        inventoryRetry = null,
                    )
            )
        )
    }

    @Test
    fun malformedCursorCannotBeStored() {
        for (cursor in
            listOf(
                "",
                "bad",
                "!",
                "!a b:test",
                "!a\nb:test",
                "!" + "x".repeat(255),
                "!\uD800:test",
            )) {
            assertFalse(
                validCollaborationSpaceIncomingSourceProgress(
                    progress().copy(inventoryAfterRoomId = cursor)
                )
            )
        }
    }

    @Test
    fun retryMustUseTheExistingValidatedSchedule() {
        for (bad in
            listOf(
                retry.copy(failures = 0),
                retry.copy(failures = 6),
                retry.copy(eligibleAtEpochMillis = 2_000),
                retry.copy(attemptedAtEpochMillis = -1),
            )) assertFalse(
            validCollaborationSpaceIncomingSourceProgress(progress().copy(inventoryRetry = bad))
        )
    }

    @Test
    fun aRealSourcePositionRemainsRequiredWhenInventoryHasAdvanced() {
        for (position in listOf("", "x".repeat(4097), "\uD800")) {
            assertFalse(
                validCollaborationSpaceIncomingSourceProgress(progress().copy(position = position))
            )
        }
        assertFalse(
            validCollaborationSpaceIncomingSourceProgress(
                progress().copy(source = source.copy(storeId = id(1)))
            )
        )
    }

    @Test
    fun continuousBatchAdvancesItsPositionAndPreservesInventoryCursorAndRetry() {
        val before = demand()
        val next = retain(before, batch())
        assertEquals(listOf(before.sources.single().copy(position = "after")), next.demand.sources)
        assertFalse(next.wakeRequired)
    }

    @Test
    fun replayDoesNotRewindInventoryOrInventAnotherWake() {
        val before = demand().copy(sources = listOf(progress().copy(position = "after")))
        repeat(10) {
            val replay = retain(before, batch())
            assertEquals(before, replay.demand)
            assertFalse(replay.wakeRequired)
        }
    }

    @Test
    fun aResponseGapCreatesANewGenerationAndClearsItsOldProgress() {
        val next = retain(demand(), batch().copy(previousPosition = "missed"))
        assertEquals(
            progress().copy(position = "after", inventoryRequestedGeneration = id(900)),
            next.demand.sources.single(),
        )
        val completed = finishActivePass(next.demand)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "after", id(900)),
            completed.sources.single(),
        )
        assertEquals(next.demand.physicalRooms, completed.physicalRooms)
        assertTrue(next.wakeRequired)
    }

    @Test
    fun explicitNativeLossRestartsEvenAContinuousPass() {
        val next = retain(demand(), batch().copy(inventoryRequired = true))
        assertEquals(
            progress().copy(position = "after", inventoryRequestedGeneration = id(900)),
            next.demand.sources.single(),
        )
        val completed = finishActivePass(next.demand)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "after", id(900)),
            completed.sources.single(),
        )
        assertEquals(next.demand.physicalRooms, completed.physicalRooms)
        assertTrue(next.wakeRequired)
    }

    @Test
    fun capacityLossRestartsInventoryWithoutDiscardingExistingPhysicalWork() {
        val existing =
            (0 until 64).map {
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    source.storeId,
                    "!$it:test",
                    id(100 + it),
                    it.toLong(),
                    "\$known-$it",
                    CollaborationSpaceIncomingMembership.Joined,
                    false,
                )
            }
        val before = demand().copy(nextPosition = 64, physicalRooms = existing)
        val next =
            retain(
                before,
                batch()
                    .copy(
                        rooms =
                            listOf(
                                CollaborationSpaceIncomingRoomHint(
                                    "!new:test",
                                    "\$new",
                                    CollaborationSpaceIncomingMembership.Joined,
                                    false,
                                )
                            )
                    ),
            )
        assertEquals(existing, next.demand.physicalRooms)
        assertEquals(
            progress().copy(position = "after", inventoryRequestedGeneration = id(900)),
            next.demand.sources.single(),
        )
        val completed = finishActivePass(next.demand)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "after", id(900)),
            completed.sources.single(),
        )
        assertEquals(next.demand.physicalRooms, completed.physicalRooms)
        assertTrue(next.wakeRequired)
    }

    @Test
    fun oneSourceCannotResetAnotherSourcesCursorOrRetry() {
        val other =
            progress()
                .copy(
                    source =
                        source.copy(
                            storeId = "b".repeat(64),
                            matrixUserId = "@second:test",
                            matrixDeviceId = "SECOND",
                        ),
                    inventoryGeneration = id(701),
                )
        val before = demand().copy(sources = listOf(progress(), other))
        val next = retain(before, batch().copy(inventoryRequired = true))
        assertEquals(other, next.demand.sources[1])
        assertEquals(
            progress().copy(position = "after", inventoryRequestedGeneration = id(900)),
            next.demand.sources[0],
        )
        val completed = finishActivePass(next.demand)
        assertEquals(
            CollaborationSpaceIncomingSourceProgress(source, "after", id(900)),
            completed.sources[0],
        )
        assertEquals(other, completed.sources[1])
        assertTrue(next.wakeRequired)
    }

    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "ORIGINAL")
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

    private fun progress() =
        CollaborationSpaceIncomingSourceProgress(source, "before", id(700), "!m:test", retry)

    private fun demand() = CollaborationSpaceIncomingDemand(sources = listOf(progress()))

    private fun batch() =
        CollaborationSpaceSyncBatch(runtime, source, "before", "after", emptyList(), false)

    private fun retain(
        before: CollaborationSpaceIncomingDemand,
        response: CollaborationSpaceSyncBatch,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingBatchRetention>>(
                retainCollaborationSpaceSyncBatch(
                    before,
                    response,
                    response.rooms.indices.map { id(800 + it) },
                    id(900),
                )
            )
            .value

    private fun finishActivePass(
        current: CollaborationSpaceIncomingDemand
    ): CollaborationSpaceIncomingDemand {
        val active = current.sources.single { it.source == source }
        val page =
            CollaborationSpaceRoomInventoryPage(
                runtime,
                source,
                active.inventoryAfterRoomId,
                emptyList(),
                null,
            )
        val retained =
            assertIs<AppResult.Success<CollaborationSpaceInventoryRetention>>(
                    retainCollaborationSpaceInventoryPage(current, active, page, emptyList())
                )
                .value
        assertEquals(CollaborationSpaceInventoryOutcome.Advanced, retained.outcome)
        return retained.demand
    }

    private fun id(value: Int) = "10000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
