package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingAliasPolicyTest {
    @Test
    fun nullSourceAliasBecomesAFreshOriginalTaskWithoutReusingItsSpacePhase() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val before = assertNotNull(f.request.snapshot).demand
        val next = route(f, captured)
        val task = next.demand.rooms.single()
        assertEquals(CollaborationSpaceIncomingBindingResolution.Queued, next.outcome)
        assertEquals(CollaborationSpaceIncomingTarget.Original(f.originalPosition), task.target)
        assertEquals(f.id(725), task.generation)
        assertNotEquals(captured.generation, task.generation)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, task.phase)
        assertEquals(f.source.storeId, task.sourceId)
        assertEquals(captured.position, task.position)
        assertEquals(captured.headEventId, task.headEventId)
        assertEquals(before.sources, next.demand.sources)
        assertEquals(before.rescanGeneration, next.demand.rescanGeneration)
        assertEquals(before.nextPosition, next.demand.nextPosition)
        assertEquals(before, f.request.snapshot?.demand)
    }

    @Test
    fun aCurrentSourceAliasKeepsItsSourceAndHeadRequirement() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias(true)
        val task = route(f, captured).demand.rooms.single()
        assertEquals(captured.sourceId, task.sourceId)
        assertEquals(captured.headEventId, task.headEventId)
    }

    @Test
    fun unknownHeadRemainsUnknownInTheNewTask() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias().copy(headEventId = null)
        replaceAlias(f, captured)
        assertNull(route(f, captured).demand.rooms.single().headEventId)
    }

    @Test
    fun missingGenuineSourceProgressWaitsWithoutInventingAPosition() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val old = assertNotNull(f.request.snapshot)
        f.request.snapshot = old.copy(demand = old.demand.copy(sources = emptyList()))
        val next = route(f, captured)
        assertEquals(
            CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource,
            next.outcome,
        )
        assertEquals(f.request.snapshot?.demand, next.demand)
        assertTrue(next.demand.sources.isEmpty())
    }

    @Test
    fun matchingStoreIdCannotHideAnotherPhysicalIdentity() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val current = assertNotNull(f.request.snapshot).demand
        assertIs<AppResult.Failed>(
            routeCollaborationSpaceIncomingAlias(
                current,
                captured,
                f.source.copy(matrixDeviceId = "OTHER"),
                CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                f.id(725),
            )
        )
    }

    @Test
    fun sourceBoundWorkCannotBeReboundToAnotherStore() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias(true)
        assertIs<AppResult.Failed>(
            routeCollaborationSpaceIncomingAlias(
                assertNotNull(f.request.snapshot).demand,
                captured,
                f.source.copy(storeId = "2".repeat(64)),
                CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                f.id(725),
            )
        )
    }

    @Test
    fun unfinishedOriginalWorkPreservesBothRequirementsEvenWithMatchingHeads() {
        for (head in listOf(null, "\$new", "\$different")) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            val captured = f.logicalAlias()
            val old = assertNotNull(f.request.snapshot)
            val original =
                CollaborationSpaceIncomingRoomDemand(
                    CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                    f.id(730),
                    1,
                    head,
                    CollaborationSpaceIncomingPhase.Pending,
                    sourceId = f.source.storeId,
                )
            f.request.snapshot =
                old.copy(
                    demand = old.demand.copy(nextPosition = 2, rooms = listOf(captured, original))
                )
            val next = route(f, captured)
            assertEquals(
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
                next.outcome,
            )
            assertEquals(f.request.snapshot?.demand, next.demand)
        }
    }

    @Test
    fun unrelatedPhysicalWorkAndLossDemandSurviveTheHandoff() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val old = assertNotNull(f.request.snapshot)
        val physical = f.physical.copy(position = 1)
        f.request.snapshot =
            old.copy(demand = old.demand.copy(nextPosition = 2, physicalRooms = listOf(physical)))
        val next = route(f, captured).demand
        assertEquals(listOf(physical), next.physicalRooms)
        assertEquals(old.demand.sources, next.sources)
        assertNotNull(next.sources.single().inventoryGeneration)
        assertEquals(old.demand.rescanGeneration, next.rescanGeneration)
    }

    @Test
    fun generationCannotReuseRoomOrLossIdentities() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val current = assertNotNull(f.request.snapshot).demand
        for (generation in
            listOf(
                captured.generation,
                assertNotNull(current.rescanGeneration),
                assertNotNull(current.sources.single().inventoryGeneration),
            )) {
            assertIs<AppResult.Failed>(
                routeCollaborationSpaceIncomingAlias(
                    current,
                    captured,
                    f.source,
                    CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                    generation,
                )
            )
        }
    }

    @Test
    fun obsoleteAliasDoesNotReplaceANewerProducerTask() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val newer = captured.copy(generation = f.id(731), headEventId = "\$later")
        replaceAlias(f, newer)
        val next = route(f, captured)
        assertEquals(CollaborationSpaceIncomingBindingResolution.Obsolete, next.outcome)
        assertEquals(listOf(newer), next.demand.rooms)
    }

    @Test
    fun sameGenerationWithChangedPhaseIsNotTheCapturedTask() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        replaceAlias(f, captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        assertIs<AppResult.Failed>(
            routeCollaborationSpaceIncomingAlias(
                assertNotNull(f.request.snapshot).demand,
                captured,
                f.source,
                CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                f.id(725),
            )
        )
    }

    @Test
    fun exactCommittedTaskConfirmsAnUncertainReply() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val retained = route(f, captured).demand
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                retained.rooms.single(),
                retained,
                false,
            ),
        )
    }

    @Test
    fun uncommittedWriteCannotConfirmTheInventedTask() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val proposed = route(f, captured).demand.rooms.single()
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                proposed,
                assertNotNull(f.request.snapshot).demand,
                false,
            )
        )
    }

    @Test
    fun knownCasMissKeepsTheSameAliasAsQueueContention() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val proposed = route(f, captured).demand.rooms.single()
        val result =
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                proposed,
                assertNotNull(f.request.snapshot).demand,
                true,
            )
        assertEquals(collaborationSpaceIncomingQueueContention(), result)
    }

    @Test
    fun aNewerAliasAfterCasMissRemainsObsoleteForTheOldCapture() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val proposed = route(f, captured).demand.rooms.single()
        replaceAlias(f, captured.copy(generation = f.id(735)))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Obsolete),
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                proposed,
                assertNotNull(f.request.snapshot).demand,
                true,
            ),
        )
    }

    @Test
    fun copiedOldGenerationOrPhaseCannotProveCanonicalHandoff() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val next = route(f, captured).demand
        val task = next.rooms.single()
        for (invalid in
            listOf(
                task.copy(generation = captured.generation),
                task.copy(phase = captured.phase),
                task.copy(headEventId = "\$wrong"),
            )) {
            assertIs<AppResult.Failed>(
                confirmCollaborationSpaceIncomingAliasRouting(
                    f.source,
                    captured,
                    invalid,
                    next.copy(rooms = listOf(invalid)),
                    false,
                )
            )
        }
    }

    @Test
    fun exactNewTaskDoesNotConfirmWhileTheOldAliasStillExists() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val next = route(f, captured).demand
        val invalid = next.copy(nextPosition = 2, rooms = next.rooms + captured.copy(position = 1))
        assertTrue(validCollaborationSpaceIncomingDemand(invalid))
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                next.rooms.single(),
                invalid,
                false,
            )
        )
    }

    @Test
    fun newProducerAliasAfterHandoffDoesNotInvalidateTheExactOriginalReceipt() {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val captured = f.logicalAlias()
        val next = route(f, captured).demand
        val later = captured.copy(generation = f.id(735), position = 1, headEventId = "\$later")
        val retained = next.copy(nextPosition = 2, rooms = next.rooms + later)
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingBindingResolution.Queued),
            confirmCollaborationSpaceIncomingAliasRouting(
                f.source,
                captured,
                next.rooms.single(),
                retained,
                false,
            ),
        )
    }

    private fun route(
        f: SpaceIncomingPhysicalResolutionFixture,
        captured: CollaborationSpaceIncomingRoomDemand,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingAliasResolution>>(
                routeCollaborationSpaceIncomingAlias(
                    assertNotNull(f.request.snapshot).demand,
                    captured,
                    f.source,
                    CollaborationSpaceIncomingTarget.Original(f.originalPosition),
                    f.id(725),
                )
            )
            .value

    private fun replaceAlias(
        f: SpaceIncomingPhysicalResolutionFixture,
        captured: CollaborationSpaceIncomingRoomDemand,
    ) {
        val old = assertNotNull(f.request.snapshot)
        f.request.snapshot = old.copy(demand = old.demand.copy(rooms = listOf(captured)))
    }
}
