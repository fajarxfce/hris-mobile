package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingPhysicalRetryTest {
    private val source = CollaborationSpaceIncomingSource("1".repeat(64), "@user:test", "DEVICE")
    private val physical =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            "!room:opaque",
            id(1),
            0,
            "\$head",
            CollaborationSpaceIncomingMembership.Joined,
            true,
        )
    private val logical =
        CollaborationSpaceIncomingRoomDemand(
            id(2),
            id(3),
            1,
            null,
            CollaborationSpaceIncomingPhase.Pending,
            sourceId = source.storeId,
        )
    private val base =
        CollaborationSpaceIncomingDemand(
            2,
            rooms = listOf(logical),
            physicalRooms = listOf(physical),
            rescanGeneration = id(4),
            sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position", id(5))),
        )

    @Test
    fun dependencyWaitKeepsEveryPhysicalFactWhileGivingTheLogicalTaskItsTurn() {
        val next = deferred()
        val held = next.physicalRooms.single()
        assertEquals(physical, held.copy(position = physical.position, retry = null))
        assertEquals(2L, held.position)
        assertEquals(3L, next.nextPosition)
        assertEquals(
            base,
            next.copy(nextPosition = base.nextPosition, physicalRooms = base.physicalRooms),
        )
        val selected =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                    selectCollaborationSpaceIncomingWork(
                        CollaborationSpaceIncomingDemandSnapshot(id(9), next),
                        1000,
                        source,
                    )
                )
                .value
        assertEquals(listOf(logical), selected.ready)
        assertTrue(selected.physicalReady.isEmpty())
        assertTrue(selected.roomsPending)
    }

    @Test
    fun replayAfterLostReplyCannotMoveTheTurnOrExtendItsDelay() {
        val next = deferred()
        assertEquals(
            AppResult.Success(next),
            deferCollaborationSpaceIncomingPhysicalRoom(next, physical, 9000),
        )
    }

    @Test
    fun repeatedFailuresAreBoundedAndRemainEligibleAfterClockMovesBackward() {
        var current = base
        repeat(8) {
            val captured = current.physicalRooms.single()
            val now = captured.retry?.eligibleAtEpochMillis ?: 1000L
            current =
                assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                        deferCollaborationSpaceIncomingPhysicalRoom(current, captured, now)
                    )
                    .value
        }
        val held = current.physicalRooms.single()
        val retry = assertNotNull(held.retry)
        assertEquals(5, retry.failures)
        assertEquals(300_000L, retry.eligibleAtEpochMillis - retry.attemptedAtEpochMillis)
        assertTrue(eligibleCollaborationSpaceIncomingPhysicalRoom(held, 0))
        assertFalse(
            eligibleCollaborationSpaceIncomingPhysicalRoom(held, retry.eligibleAtEpochMillis - 1)
        )
    }

    @Test
    fun obsoleteDeferralCannotDelayOrClearNewerWork() {
        for (rooms in
            listOf(
                emptyList(),
                listOf(physical.copy(generation = id(99), headEventId = "\$new")),
            )) {
            val current = base.copy(physicalRooms = rooms)
            assertEquals(
                AppResult.Success(current),
                deferCollaborationSpaceIncomingPhysicalRoom(current, physical, 1000),
            )
        }
    }

    @Test
    fun sameGenerationMutationAndExhaustedPositionCannotBeAcceptedAsRetry() {
        for (changed in
            listOf(
                physical.copy(headEventId = "\$changed"),
                physical.copy(limited = false),
                physical.copy(membership = CollaborationSpaceIncomingMembership.Left),
            )) {
            assertIs<AppResult.Failed>(
                deferCollaborationSpaceIncomingPhysicalRoom(
                    base.copy(physicalRooms = listOf(changed)),
                    physical,
                    1000,
                )
            )
        }
        assertIs<AppResult.Failed>(
            deferCollaborationSpaceIncomingPhysicalRoom(
                base.copy(nextPosition = 9_007_199_254_740_991L),
                physical,
                1000,
            )
        )
        assertIs<AppResult.Failed>(deferCollaborationSpaceIncomingPhysicalRoom(base, physical, -1))
    }

    @Test
    fun exactDeferredWitnessSurvivesIndependentCursorAndInventoryChanges() {
        val next = deferred()
        val later =
            next.copy(
                sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "later", id(88)))
            )
        for (rejected in listOf(false, true)) assertEquals(
            AppResult.Success(true),
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                physical,
                next.physicalRooms.single(),
                later,
                rejected,
            ),
        )
    }

    @Test
    fun knownRejectedDeferralUsesShortRetryWithoutPretendingTheWriteCommitted() {
        val next = deferred()
        val result =
            assertIs<AppResult.Failed>(
                confirmCollaborationSpaceIncomingPhysicalDeferral(
                    source,
                    physical,
                    next.physicalRooms.single(),
                    base,
                    true,
                )
            )
        assertTrue(isCollaborationSpaceIncomingQueueContention(result.failure))
        assertEquals(1L, result.failure.retryAfterSeconds)
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                physical,
                next.physicalRooms.single(),
                base,
                false,
            )
        )
    }

    @Test
    fun onlyKnownRejectedWriteCanTreatANewerGenerationAsObsolete() {
        val next = deferred()
        val later = base.copy(physicalRooms = listOf(physical.copy(generation = id(77))))
        assertEquals(
            AppResult.Success(false),
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                physical,
                next.physicalRooms.single(),
                later,
                true,
            ),
        )
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                physical,
                next.physicalRooms.single(),
                later,
                false,
            )
        )
    }

    @Test
    fun changedSourceCannotConfirmDeferralOrConversion() {
        val next = deferred()
        val changed =
            next.copy(
                sources =
                    listOf(
                        next.sources.single().copy(source = source.copy(matrixDeviceId = "OTHER"))
                    )
            )
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingPhysicalDeferral(
                source,
                physical,
                next.physicalRooms.single(),
                changed,
                false,
            )
        )
        val converted =
            assertIs<AppResult.Success<CollaborationSpaceIncomingPhysicalResolution>>(
                    resolveCollaborationSpaceIncomingPhysicalRoom(base, physical, id(66))
                )
                .value
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingPhysicalResolution(
                source,
                physical,
                converted.demand.rooms.single { it.generation == physical.generation },
                converted.demand.copy(sources = changed.sources),
                false,
            )
        )
    }

    @Test
    fun queueContentionDoesNotAbsorbDeniedStorageOrUnrelatedUnavailableFailures() {
        for (kind in listOf(FailureKind.AccessDenied, FailureKind.Storage, FailureKind.Conflict)) {
            assertFalse(
                isCollaborationSpaceIncomingQueueContention(
                    collaborationSpaceIncomingQueueContention().failure.copy(kind = kind)
                )
            )
        }
        assertFalse(
            isCollaborationSpaceIncomingQueueContention(
                Failure(FailureKind.Unavailable, "Unrelated")
            )
        )
    }

    private fun deferred() =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                deferCollaborationSpaceIncomingPhysicalRoom(base, physical, 1000)
            )
            .value

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
