package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceHistoryTargetPolicyTest {
    @Test
    fun multibyteRoomTargetsBeyondTheHeaderBudgetEscalateToAccountRecovery() {
        val rooms = List(64) { "!${it.toString().padStart(2, '0')}" + "漢".repeat(150) }
        assertTrue(rooms.all(::validCollaborationSpaceIncomingRoomId))
        assertNull(
            success(planCollaborationSpaceHistoryRecoveryRequest(null, id(1), rooms)).roomIds
        )
    }

    @Test
    fun mergingValidMultibyteRequestsBeyondTheHeaderBudgetPreservesAnAccountRequest() {
        val first = List(32) { "!a${it.toString().padStart(2, '0')}" + "漢".repeat(150) }
        val second = List(32) { "!b${it.toString().padStart(2, '0')}" + "漢".repeat(150) }
        val pending = request(targeted(), 2, first)
        assertEquals(first, pending.requestedRoomIds)
        val combined = request(pending, 3, second)
        assertNull(combined.requestedRoomIds)
        assertEquals(targeted().roomIds, combined.roomIds)
    }

    @Test
    fun existingRequestWithoutAPredicateRemainsAccountWide() {
        assertNull(success(planCollaborationSpaceHistoryRecoveryRequest(null, id(1))).roomIds)
    }

    @Test
    fun knownRoomRequestCopiesAndCanonicalizesItsInput() {
        val rooms = mutableListOf("!b:test", "!a:test")
        val result = success(planCollaborationSpaceHistoryRecoveryRequest(null, id(1), rooms))
        rooms.clear()
        assertEquals(listOf("!a:test", "!b:test"), result.roomIds)
        assertTrue(validCollaborationSpaceHistoryRecovery(result))
    }

    @Test
    fun malformedEmptyDuplicateOrOversizedRequestIsRejected() {
        for (rooms in
            listOf(
                emptyList(),
                listOf("bad"),
                listOf("!a:test", "!a:test"),
                List(65) { "!$it:test" },
            )) assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryRequest(null, id(1), rooms)
        )
    }

    @Test
    fun aLaterTargetDoesNotChangeTheActiveScopeOrCursor() {
        val current = targeted().copy(revision = 1, afterPartition = "a")
        val next = request(current, 2, listOf("!b:test"))
        assertEquals(current.generation, next.generation)
        assertEquals(current.roomIds, next.roomIds)
        assertEquals(current.afterPartition, next.afterPartition)
        assertEquals(current.revision, next.revision)
        assertEquals(listOf("!b:test"), next.requestedRoomIds)
    }

    @Test
    fun laterRequestedRoomsCoalesceWithoutLosingThePreviousRequest() {
        val next = request(request(targeted(), 2, listOf("!b:test")), 3, listOf("!c:test"))
        assertEquals(id(3), next.requestedGeneration)
        assertEquals(listOf("!b:test", "!c:test"), next.requestedRoomIds)
        assertEquals(listOf("!a:test"), next.roomIds)
    }

    @Test
    fun requestedUnionOverflowEscalatesToAccountWideRecovery() {
        val first = request(targeted(), 2, List(64) { "!${it.toString().padStart(2, '0')}:test" })
        val next = request(first, 3, listOf("!overflow:test"))
        assertNull(next.requestedRoomIds)
        assertEquals(id(3), next.requestedGeneration)
        assertEquals(first.roomIds, next.roomIds)
    }

    @Test
    fun aRequestedAccountWidePassCannotBeNarrowedByLaterKnownRooms() {
        val current = request(targeted(), 2, null)
        assertNull(request(current, 3, listOf("!b:test")).requestedRoomIds)
    }

    @Test
    fun anAccountWideRequestWidensPreviouslyRequestedRooms() {
        val current = request(targeted(), 2, listOf("!b:test"))
        assertNull(request(current, 3, null).requestedRoomIds)
    }

    @Test
    fun aRunningAccountWidePassCanRetainASeparateLaterTargetedPass() {
        val current = CollaborationSpaceHistoryRecovery(id(1))
        val next = request(current, 2, listOf("!b:test"))
        assertNull(next.roomIds)
        assertEquals(listOf("!b:test"), next.requestedRoomIds)
    }

    @Test
    fun completedWorkAcceptsANewScopeWithAFreshCursor() {
        val current = targeted().copy(revision = 2, complete = true, unresolved = true)
        val next = request(current, 2, listOf("!b:test"))
        assertEquals(id(2), next.generation)
        assertEquals(0L, next.revision)
        assertFalse(next.complete)
        assertEquals(listOf("!b:test"), next.roomIds)
        assertNull(next.requestedGeneration)
    }

    @Test
    fun metadataEndPromotesTheRequestedPredicateWithItsGeneration() {
        val current = request(targeted(), 2, listOf("!b:test"))
        val next =
            success(
                planCollaborationSpaceHistoryRecoveryAdvance(current, current, null, false, false)
            )
        assertEquals(id(2), next.generation)
        assertEquals(listOf("!b:test"), next.roomIds)
        assertNull(next.requestedGeneration)
        assertNull(next.requestedRoomIds)
    }

    @Test
    fun anotherReferenceRoundKeepsActiveAndRequestedPredicatesSeparate() {
        val current = request(targeted(), 2, listOf("!b:test"))
        val next =
            success(
                planCollaborationSpaceHistoryRecoveryAdvance(current, current, null, true, true)
            )
        assertEquals(current.generation, next.generation)
        assertEquals(current.roomIds, next.roomIds)
        assertEquals(current.requestedRoomIds, next.requestedRoomIds)
        assertFalse(next.complete)
    }

    @Test
    fun sameGenerationReplayMustAlreadyBeCoveredByItsRetainedScope() {
        val current = request(request(targeted(), 2, listOf("!b:test")), 3, listOf("!c:test"))
        assertEquals(current, request(current, 3, listOf("!c:test")))
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryRequest(current, id(3), listOf("!outside:test"))
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryRequest(current, id(1), null)
        )
    }

    @Test
    fun changingActiveScopeInvalidatesACompletionEvenWhenGenerationMatches() {
        val captured = targeted()
        val changed = captured.copy(roomIds = listOf("!b:test"))
        assertFalse(sameCollaborationSpaceHistoryRecoveryTurn(captured, changed))
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryAdvance(changed, captured, null, false, false)
        )
    }

    @Test
    fun legitimatePendingScopeWideningDoesNotInvalidateTheActiveTurn() {
        val captured = request(targeted(), 2, listOf("!b:test"))
        val changed = request(captured, 3, listOf("!c:test"))
        assertTrue(sameCollaborationSpaceHistoryRecoveryTurn(captured, changed))
        assertIs<AppResult.Success<*>>(
            planCollaborationSpaceHistoryRecoveryAdvance(changed, captured, "a", false, false)
        )
    }

    @Test
    fun narrowedPendingScopeCannotPassAsAnIndependentNewRequest() {
        val captured = request(targeted(), 2, listOf("!b:test", "!c:test"))
        val narrowed =
            captured.copy(requestedGeneration = id(3), requestedRoomIds = listOf("!b:test"))
        assertFalse(sameCollaborationSpaceHistoryRecoveryTurn(captured, narrowed))
        val all = request(targeted(), 2, null)
        assertFalse(
            sameCollaborationSpaceHistoryRecoveryTurn(
                all,
                all.copy(requestedGeneration = id(3), requestedRoomIds = listOf("!b:test")),
            )
        )
    }

    @Test
    fun snapshotCopiesBothNestedPredicatesBeforeSuspension() {
        val active = mutableListOf("!a:test")
        val pending = mutableListOf("!b:test")
        val value =
            targeted()
                .copy(roomIds = active, requestedGeneration = id(2), requestedRoomIds = pending)
        val snapshot =
            CollaborationSpaceIncomingDemandSnapshot(
                id(3),
                CollaborationSpaceIncomingDemand(historyRecovery = value),
            )
        val captured =
            assertNotNull(captureCollaborationSpaceIncomingDemandSnapshot(snapshot))
                .demand
                .historyRecovery!!
        active.clear()
        pending.clear()
        assertEquals(listOf("!a:test"), captured.roomIds)
        assertEquals(listOf("!b:test"), captured.requestedRoomIds)
    }

    @Test
    fun completingAndDeferringNeverReturnCallerOwnedPredicateLists() {
        val active = mutableListOf("!a:test")
        val current = targeted().copy(roomIds = active)
        val completed =
            success(
                planCollaborationSpaceHistoryRecoveryAdvance(current, current, null, false, false)
            )
        val deferred =
            success(
                planCollaborationSpaceHistoryRecoveryDefer(
                    current,
                    current,
                    CollaborationSpaceIncomingRetry(100, 30_100, 1),
                )
            )
        active.clear()
        assertEquals(listOf("!a:test"), completed.roomIds)
        assertEquals(listOf("!a:test"), deferred.roomIds)
    }

    @Test
    fun malformedStoredPredicatesAreRejectedWithoutNarrowingThem() {
        for (value in
            listOf(
                targeted().copy(roomIds = emptyList()),
                targeted().copy(roomIds = listOf("!b:test", "!a:test")),
                targeted().copy(requestedRoomIds = listOf("!b:test")),
            )) assertFalse(validCollaborationSpaceHistoryRecovery(value))
    }

    @Test
    fun localMatchingRespectsActiveScopeAndRejectsMalformedRoomIds() {
        assertTrue(matchesCollaborationSpaceHistoryRecoveryRoom(targeted(), "!a:test"))
        assertFalse(matchesCollaborationSpaceHistoryRecoveryRoom(targeted(), "!b:test"))
        assertTrue(
            matchesCollaborationSpaceHistoryRecoveryRoom(targeted().copy(roomIds = null), "!b:test")
        )
        assertFalse(
            matchesCollaborationSpaceHistoryRecoveryRoom(targeted().copy(roomIds = null), "invalid")
        )
    }

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private fun targeted() = CollaborationSpaceHistoryRecovery(id(1), roomIds = listOf("!a:test"))

    private fun request(
        current: CollaborationSpaceHistoryRecovery,
        generation: Int,
        rooms: List<String>?,
    ) = success(planCollaborationSpaceHistoryRecoveryRequest(current, id(generation), rooms))

    private fun success(value: AppResult<CollaborationSpaceHistoryRecovery>) =
        assertIs<AppResult.Success<CollaborationSpaceHistoryRecovery>>(value).value
}
