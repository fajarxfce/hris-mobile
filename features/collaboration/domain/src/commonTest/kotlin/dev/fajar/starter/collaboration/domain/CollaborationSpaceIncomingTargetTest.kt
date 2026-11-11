package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingTargetTest {
    private val access = SpaceDirectoryUseCaseFixture().access
    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@original:test", "DEVICE")
    private val published = CollaborationSpaceIncomingTarget.Published(id(10))
    private val original =
        CollaborationSpaceIncomingTarget.Original(
            CollaborationOriginalConversationPosition(id(1), id(10))
        )
    private val otherCompany =
        CollaborationSpaceIncomingTarget.Original(
            CollaborationOriginalConversationPosition(id(2), id(10))
        )

    @Test
    fun sameConversationIdInThreeNamespacesKeepsThreeIndependentTurns() {
        val demand = queue(published, original, otherCompany)
        assertTrue(validCollaborationSpaceIncomingDemand(access, demand))
        val work =
            value(
                selectCollaborationSpaceIncomingWork(
                    CollaborationSpaceIncomingDemandSnapshot(id(800), demand),
                    0,
                    source,
                )
            )
        assertEquals(listOf(published, original, otherCompany), work.ready.map { it.target })
    }

    @Test
    fun duplicateOriginalPositionIsRejectedEvenWithDifferentGenerations() {
        assertFalse(validCollaborationSpaceIncomingDemand(queue(original, original)))
        assertTrue(validCollaborationSpaceIncomingDemand(queue(original, otherCompany)))
    }

    @Test
    fun originalRequiresConcreteSourceWhileExistingPublishedDemandMayRemainUnbound() {
        val originalDemand = queue(original)
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                originalDemand.copy(rooms = originalDemand.rooms.map { it.copy(sourceId = null) })
            )
        )
        val existing = queue(published)
        assertTrue(
            validCollaborationSpaceIncomingDemand(
                existing.copy(rooms = existing.rooms.map { it.copy(sourceId = null) })
            )
        )
    }

    @Test
    fun malformedOrZeroOriginalCoordinatesCannotBecomeQueuedTargets() {
        val zero = "00000000-0000-0000-0000-000000000000"
        val invalid =
            listOf(
                CollaborationSpaceIncomingTarget.Published("bad"),
                original.copy(position = original.position.copy(companyId = zero)),
                original.copy(position = original.position.copy(conversationId = zero)),
                original.copy(position = original.position.copy(companyId = "bad")),
                original.copy(position = original.position.copy(conversationId = "bad")),
            )
        invalid.forEach { assertFalse(validCollaborationSpaceIncomingDemand(queue(it))) }
    }

    @Test
    fun physicalConversionKeepsOriginalCoordinatesSourceHeadRetryAndInventory() {
        val (before, physical) = withPhysical(queue())
        val captured = physical.copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000_000))
        val observed = before.copy(physicalRooms = listOf(captured))
        val converted =
            value(resolveCollaborationSpaceIncomingPhysicalRoom(observed, captured, original))
        assertEquals(CollaborationSpaceIncomingBindingResolution.Queued, converted.outcome)
        assertEquals(original, converted.demand.rooms.single().target)
        assertEquals(captured.generation, converted.demand.rooms.single().generation)
        assertEquals(captured.headEventId, converted.demand.rooms.single().headEventId)
        assertEquals(captured.retry, converted.demand.rooms.single().retry)
        assertEquals(captured.position, converted.demand.rooms.single().position)
        assertEquals(source.storeId, converted.demand.rooms.single().sourceId)
        assertEquals(observed.sources, converted.demand.sources)
        assertTrue(converted.demand.physicalRooms.isEmpty())
    }

    @Test
    fun publishedWithTheSameIdDoesNotBlockOriginalPhysicalConversion() {
        val (before, physical) = withPhysical(queue(published))
        val next = value(resolveCollaborationSpaceIncomingPhysicalRoom(before, physical, original))
        assertEquals(CollaborationSpaceIncomingBindingResolution.Queued, next.outcome)
        assertEquals(listOf(published, original), next.demand.rooms.map { it.target })
        assertEquals(before.rooms.first(), next.demand.rooms.first())
    }

    @Test
    fun unfinishedOriginalWorkKeepsItsPhaseAndTheSeparatePhysicalRequirement() {
        for (phase in CollaborationSpaceIncomingPhase.entries) {
            val initial = queue(original)
            val (before, physical) =
                withPhysical(initial.copy(rooms = initial.rooms.map { it.copy(phase = phase) }))
            val result =
                value(resolveCollaborationSpaceIncomingPhysicalRoom(before, physical, original))
            assertEquals(
                CollaborationSpaceIncomingBindingResolution.WaitingForPrevious,
                result.outcome,
            )
            assertEquals(before, result.demand)
        }
    }

    @Test
    fun publishedRequestWithSameIdDoesNotReplaceOriginalWork() {
        val before = queue(original)
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Request(
                        published.conversationId,
                        id(600),
                        "\$new",
                    ),
                )
            )
        assertEquals(before.rooms.single(), next.rooms.first())
        assertEquals(listOf(original, published), next.rooms.map { it.target })
    }

    @Test
    fun aGenerationCannotBeReusedAcrossDifferentTargetNamespaces() {
        val before = queue(original)
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                before,
                CollaborationSpaceIncomingDemandChange.Request(
                    published.conversationId,
                    before.rooms.single().generation,
                    "\$new",
                ),
            )
        )
    }

    @Test
    fun deferralMovesOnlyTheExactCompanyTargetBehindItsPeers() {
        val before = queue(published, original, otherCompany)
        val captured = before.rooms[1]
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Defer(captured),
                )
            )
        assertEquals(listOf(published, otherCompany, original), next.rooms.map { it.target })
        assertEquals(listOf(before.rooms[0], before.rooms[2]), next.rooms.take(2))
        assertEquals(captured.copy(position = before.nextPosition), next.rooms.last())
        assertEquals(before.sources, next.sources)
    }

    @Test
    fun providerProgressKeepsItsOriginalTargetAndLeavesSameIdPeersUntouched() {
        val initial = queue(published, original, otherCompany)
        val captured = initial.rooms[1].copy(phase = CollaborationSpaceIncomingPhase.Latest)
        val before = initial.copy(rooms = listOf(initial.rooms[0], captured, initial.rooms[2]))
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.ProviderAcquired(captured, false),
                )
            )
        assertEquals(captured.copy(phase = CollaborationSpaceIncomingPhase.Provider), next.rooms[1])
        assertEquals(
            before.rooms.filterNot { it.target == original },
            next.rooms.filterNot { it.target == original },
        )
    }

    @Test
    fun completionRemovesOnlyItsExactOriginalTarget() {
        val before = queue(published, original, otherCompany)
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Complete(before.rooms[1]),
                )
            )
        assertEquals(listOf(before.rooms[0], before.rooms[2]), next.rooms)
        assertEquals(before.sources, next.sources)
    }

    @Test
    fun changedSameGenerationTargetCannotRedirectAnExistingTurn() {
        val before = queue(original)
        val redirected = before.rooms.single().copy(target = otherCompany)
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Defer(redirected),
                )
            )
        assertEquals(before, next)
    }

    @Test
    fun lostOriginalTransitionReplyConfirmsDespiteIndependentProducerProgress() {
        val before = queue(published, original, otherCompany)
        val captured = before.rooms[1]
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Defer(captured),
                )
            )
        val (retained, _) =
            withPhysical(next.copy(sources = next.sources.map { it.copy(position = "later") }))
        val lost = AppResult.Failed(Failure(FailureKind.Storage, "Interrupted reply"))
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingRoomWrite.Applied),
            confirmCollaborationSpaceIncomingRoomWrite(
                access,
                before,
                next,
                captured,
                CollaborationSpaceIncomingDemandSnapshot(id(801), retained),
                lost,
            ),
        )
    }

    @Test
    fun anotherNamespaceCannotStandInForTheOriginalWriteWitness() {
        val before = queue(original)
        val captured = before.rooms.single()
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Defer(captured),
                )
            )
        val forged = next.copy(rooms = next.rooms.map { it.copy(target = published) })
        val lost = AppResult.Failed(Failure(FailureKind.Storage, "Interrupted reply"))
        assertEquals(
            lost,
            confirmCollaborationSpaceIncomingRoomWrite(
                access,
                before,
                next,
                captured,
                CollaborationSpaceIncomingDemandSnapshot(id(802), forged),
                lost,
            ),
        )
    }

    @Test
    fun newerOriginalGenerationStaysPendingWithoutAcknowledgingSameIdPeers() {
        val before = queue(published, original, otherCompany)
        val captured = before.rooms[1]
        val next =
            value(
                nextCollaborationSpaceIncomingDemand(
                    before,
                    CollaborationSpaceIncomingDemandChange.Defer(captured),
                )
            )
        val retained =
            before.copy(
                rooms =
                    before.rooms.map { if (it == captured) it.copy(generation = id(850)) else it }
            )
        assertEquals(
            AppResult.Success(CollaborationSpaceIncomingRoomWrite.Obsolete),
            confirmCollaborationSpaceIncomingRoomWrite(
                access,
                before,
                next,
                captured,
                CollaborationSpaceIncomingDemandSnapshot(id(803), retained),
                AppResult.Success(null),
            ),
        )
    }

    @Test
    fun anotherInstalledSourceCannotSelectOriginalTasks() {
        val before = queue(original, otherCompany)
        val other = source.copy(storeId = "b".repeat(64), matrixDeviceId = "OTHER")
        val work =
            value(
                selectCollaborationSpaceIncomingWork(
                    CollaborationSpaceIncomingDemandSnapshot(id(804), before),
                    0,
                    other,
                )
            )
        assertTrue(work.ready.isEmpty())
        assertTrue(work.roomsPending)
        assertEquals(before, work.snapshot?.demand)
    }

    private fun queue(vararg targets: CollaborationSpaceIncomingTarget) =
        CollaborationSpaceIncomingDemand(
            nextPosition = targets.size.toLong(),
            rooms =
                targets.mapIndexed { index, target ->
                    CollaborationSpaceIncomingRoomDemand(
                        target,
                        id(100 + index),
                        index.toLong(),
                        "\$head-$index",
                        CollaborationSpaceIncomingPhase.Pending,
                        sourceId = source.storeId,
                    )
                },
            sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position", id(900))),
        )

    private fun withPhysical(
        demand: CollaborationSpaceIncomingDemand
    ): Pair<CollaborationSpaceIncomingDemand, CollaborationSpaceIncomingPhysicalRoomDemand> {
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                source.storeId,
                "!room:test",
                id(700),
                demand.nextPosition,
                "\$head",
                CollaborationSpaceIncomingMembership.Joined,
                true,
            )
        return demand.copy(
            nextPosition = demand.nextPosition + 1,
            physicalRooms = listOf(physical),
        ) to physical
    }

    private fun <T> value(result: AppResult<T>): T = assertIs<AppResult.Success<T>>(result).value

    private fun id(value: Int) = "10000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
