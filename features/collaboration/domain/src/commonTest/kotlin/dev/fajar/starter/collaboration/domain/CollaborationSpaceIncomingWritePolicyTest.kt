package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingWritePolicyTest {
    private val source = CollaborationSpaceIncomingSource("1".repeat(64), "@source:test", "DEVICE")
    private val lost = AppResult.Failed(Failure(FailureKind.Storage, "Fictional lost reply"))

    @Test
    fun everyLogicalTransitionConfirmsAfterProducerCursorPhysicalAndInventoryChanges() {
        for (mode in 0..3) {
            val p = plan(mode)
            val physical =
                CollaborationSpaceIncomingPhysicalRoomDemand(
                    source.storeId,
                    "!new:test",
                    id(80),
                    p.next.nextPosition,
                    "\$new",
                    CollaborationSpaceIncomingMembership.Joined,
                    true,
                )
            val later =
                p.next.copy(
                    nextPosition = p.next.nextPosition + 1,
                    physicalRooms = listOf(physical),
                    sources =
                        listOf(
                            p.next.sources
                                .single()
                                .copy(position = "later", inventoryGeneration = id(81))
                        ),
                )
            assertEquals(
                AppResult.Success(CollaborationSpaceIncomingRoomWrite.Applied),
                confirm(p, later, lost),
            )
        }
    }

    @Test
    fun knownRejectedWriteKeepsTheUnchangedCaptureEligibleForShortRetry() {
        for (mode in 0..3) {
            val p = plan(mode)
            val result = assertIs<AppResult.Failed>(confirm(p, p.previous, AppResult.Success(null)))
            assertTrue(isCollaborationSpaceIncomingQueueContention(result.failure))
            assertEquals(1L, result.failure.retryAfterSeconds)
        }
    }

    @Test
    fun knownRejectedTransitionWithNewGenerationIsObsoleteWithoutClearingIt() {
        for (mode in 0..3) {
            val p = plan(mode)
            val later =
                p.previous.copy(
                    rooms = listOf(p.captured.copy(generation = id(90), headEventId = "\$newer"))
                )
            val expected =
                if (mode == 3) CollaborationSpaceIncomingRoomWrite.Applied
                else CollaborationSpaceIncomingRoomWrite.Obsolete
            assertEquals(AppResult.Success(expected), confirm(p, later, AppResult.Success(null)))
            assertEquals(id(90), later.rooms.single().generation)
        }
    }

    @Test
    fun uncertainNonRemovalWriteWithNoExactTargetPreservesOriginalFailure() {
        for (mode in 0..2) {
            val p = plan(mode)
            val later = p.previous.copy(rooms = listOf(p.captured.copy(generation = id(90))))
            assertEquals(lost, confirm(p, later, lost))
            assertEquals(lost, confirm(p, p.previous, lost))
        }
    }

    @Test
    fun acknowledgedButSupersededTransitionDoesNotClaimTheNewGeneration() {
        val p = plan(0)
        val later = p.next.copy(rooms = listOf(p.captured.copy(generation = id(90))))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingRoomWrite.Obsolete),
            confirm(p, later, AppResult.Success(snapshot(p.next))),
        )
    }

    @Test
    fun sameGenerationChangedHeadCannotBeMistakenForCasContentionOrSuccess() {
        for (mode in 0..3) {
            val p = plan(mode)
            val changed =
                p.previous.copy(rooms = listOf(p.captured.copy(headEventId = "\$changed")))
            val result = assertIs<AppResult.Failed>(confirm(p, changed, AppResult.Success(null)))
            assertEquals(FailureKind.Conflict, result.failure.kind)
            assertFalse(isCollaborationSpaceIncomingQueueContention(result.failure))
        }
    }

    @Test
    fun changedMatrixIdentityCannotConfirmEvenAnExactLogicalTarget() {
        for (mode in 0..3) {
            val p = plan(mode)
            val changed =
                p.next.copy(
                    sources =
                        listOf(
                            p.next.sources
                                .single()
                                .copy(source = source.copy(matrixDeviceId = "OTHER"))
                        )
                )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(confirm(p, changed, AppResult.Success(snapshot(p.next))))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun completedSourceCanRetireOnlyOnceNoTaskReferencesIt() {
        val original = plan(3)
        assertIs<AppResult.Failed>(
            confirm(
                original,
                original.next.copy(sources = emptyList()),
                AppResult.Success(snapshot(original.next)),
            )
        )
        val p =
            original.copy(
                previous =
                    original.previous.copy(
                        sources =
                            listOf(
                                original.previous.sources.single().copy(inventoryGeneration = null)
                            )
                    ),
                next =
                    original.next.copy(
                        sources =
                            listOf(original.next.sources.single().copy(inventoryGeneration = null))
                    ),
            )
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingRoomWrite.Applied),
            confirm(p, p.next.copy(sources = emptyList()), lost),
        )
        val stillPending = p.previous.copy(sources = emptyList())
        assertIs<AppResult.Failed>(confirm(p, stillPending, AppResult.Success(null)))
    }

    @Test
    fun erasedOtherWorkOrRewoundQueueCannotServeAsAnOwnedWitness() {
        val p = plan(0)
        val other =
            p.captured.copy(
                target = CollaborationSpaceIncomingTarget.Published(id(91)),
                generation = id(92),
                position = p.previous.nextPosition,
            )
        val before =
            p.previous.copy(
                nextPosition = p.previous.nextPosition + 1,
                rooms = p.previous.rooms + other,
            )
        val invalidPlan = p.copy(previous = before)
        assertIs<AppResult.Failed>(
            confirm(invalidPlan, p.next, AppResult.Success(snapshot(p.next)))
        )
        val complete = plan(3)
        assertIs<AppResult.Failed>(
            confirm(
                complete,
                complete.next.copy(nextPosition = 0),
                AppResult.Success(snapshot(complete.next)),
            )
        )
    }

    @Test
    fun oldGenerationMovedIntoPhysicalWorkIsNotACompletedRemoval() {
        val p = plan(3)
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                source.storeId,
                "!moved:test",
                p.captured.generation,
                p.captured.position,
                p.captured.headEventId,
                CollaborationSpaceIncomingMembership.Joined,
                false,
            )
        assertIs<AppResult.Failed>(
            confirm(
                p,
                p.next.copy(physicalRooms = listOf(physical)),
                AppResult.Success(snapshot(p.next)),
            )
        )
    }

    private data class Plan(
        val access: CollaborationSpaceLocalAccess,
        val previous: CollaborationSpaceIncomingDemand,
        val next: CollaborationSpaceIncomingDemand,
        val captured: CollaborationSpaceIncomingRoomDemand,
    )

    private fun plan(mode: Int): Plan {
        val f = SpaceIncomingCompletionFixture()
        val captured =
            f.captured.copy(
                sourceId = source.storeId,
                phase =
                    if (mode == 0) CollaborationSpaceIncomingPhase.Latest
                    else CollaborationSpaceIncomingPhase.Pending,
            )
        val previous =
            f.request.snapshot!!
                .demand
                .copy(
                    rooms = listOf(captured),
                    sources =
                        listOf(CollaborationSpaceIncomingSourceProgress(source, "position", id(70))),
                )
        val change =
            when (mode) {
                0 -> CollaborationSpaceIncomingDemandChange.ProviderAcquired(captured, true)
                1 -> CollaborationSpaceIncomingDemandChange.RestartProvider(captured, null)
                2 ->
                    CollaborationSpaceIncomingDemandChange.Defer(
                        captured,
                        nextCollaborationSpaceIncomingRetry(null, 1000),
                    )
                else -> CollaborationSpaceIncomingDemandChange.Complete(captured)
            }
        val next =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(previous, change)
                )
                .value
        return Plan(f.access, previous, next, captured)
    }

    private fun confirm(
        p: Plan,
        retained: CollaborationSpaceIncomingDemand,
        written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
    ) =
        confirmCollaborationSpaceIncomingRoomWrite(
            p.access,
            p.previous,
            p.next,
            p.captured,
            snapshot(retained),
            written,
        )

    private fun snapshot(demand: CollaborationSpaceIncomingDemand) =
        CollaborationSpaceIncomingDemandSnapshot(id(99), demand)

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
