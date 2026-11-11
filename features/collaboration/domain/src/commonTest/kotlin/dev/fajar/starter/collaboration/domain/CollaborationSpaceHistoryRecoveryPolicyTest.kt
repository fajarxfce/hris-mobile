package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceHistoryRecoveryPolicyTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private fun initial() = CollaborationSpaceHistoryRecovery(id(1))

    private fun <T> value(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun request(current: CollaborationSpaceHistoryRecovery?, n: Int) =
        value(planCollaborationSpaceHistoryRecoveryRequest(current, id(n)))

    private fun advance(
        current: CollaborationSpaceHistoryRecovery,
        next: String? = null,
        more: Boolean = false,
        unresolved: Boolean = false,
    ) =
        value(
            planCollaborationSpaceHistoryRecoveryAdvance(current, current, next, more, unresolved)
        )

    @Test
    fun aKeyRequestCanExistBeforeAnySourcePositionOrRoom() {
        val pass = request(null, 1)
        assertEquals(initial(), pass)
        val demand = CollaborationSpaceIncomingDemand(historyRecovery = pass)
        assertTrue(validCollaborationSpaceIncomingDemand(demand))
        assertTrue(demand.sources.isEmpty())
        assertTrue(demand.rooms.isEmpty())
        assertEquals(0, demand.nextPosition)
    }

    @Test
    fun repeatedActiveAndRequestedIdentitiesPreserveTheCurrentTurn() {
        val pass =
            initial()
                .copy(
                    revision = 3,
                    afterPartition = "history/b",
                    moreHistoryPages = true,
                    unresolved = true,
                    requestedGeneration = id(2),
                    retry = CollaborationSpaceIncomingRetry(0, 30_000, 1),
                )
        assertEquals(pass, request(pass, 1))
        assertEquals(pass, request(pass, 2))
    }

    @Test
    fun continuousLaterRequestsCoalesceWithoutRewindingOrClearingDelay() {
        var pass =
            initial()
                .copy(
                    revision = 1,
                    afterPartition = "history/b",
                    retry = CollaborationSpaceIncomingRetry(0, 30_000, 1),
                )
        for (n in 2..40) pass = request(pass, n)
        assertEquals(id(40), pass.requestedGeneration)
        assertEquals("history/b", pass.afterPartition)
        assertEquals(1, pass.revision)
        assertEquals(CollaborationSpaceIncomingRetry(0, 30_000, 1), pass.retry)
    }

    @Test
    fun completedCurrentGenerationCannotBeRestartedByAReplay() {
        val done = advance(initial(), unresolved = true)
        assertTrue(done.complete)
        assertEquals(done, request(done, 1))
    }

    @Test
    fun aLaterGenerationStartsFreshAfterCompletion() {
        val done = advance(initial(), unresolved = true)
        assertEquals(CollaborationSpaceHistoryRecovery(id(2)), request(done, 2))
    }

    @Test
    fun malformedRequestIdentitiesAreValidationFailures() {
        for (bad in listOf("", "bad", " ", id(1) + "x")) {
            val result =
                assertIs<AppResult.Failed>(
                    planCollaborationSpaceHistoryRecoveryRequest(initial(), bad)
                )
            assertEquals(FailureKind.Validation, result.failure.kind)
        }
    }

    @Test
    fun corruptRetainedStateCannotBeReplacedByAFreshRequest() {
        for (bad in
            listOf(
                initial().copy(generation = "bad"),
                initial().copy(revision = -1),
                initial().copy(requestedGeneration = id(1)),
                initial().copy(requestedGeneration = "bad"),
            )) {
            assertFalse(validCollaborationSpaceHistoryRecovery(bad))
            assertIs<AppResult.Failed>(planCollaborationSpaceHistoryRecoveryRequest(bad, id(2)))
        }
    }

    @Test
    fun theInitialRevisionCannotPretendToContainVisitedOrCompletedWork() {
        for (bad in
            listOf(
                initial().copy(afterPartition = "history/a"),
                initial().copy(moreHistoryPages = true),
                initial().copy(unresolved = true),
                initial().copy(complete = true),
                initial().copy(retry = CollaborationSpaceIncomingRetry(0, 30_000, 1)),
            )) {
            assertFalse(validCollaborationSpaceHistoryRecovery(bad))
        }
        assertTrue(
            validCollaborationSpaceHistoryRecovery(initial().copy(requestedGeneration = id(2)))
        )
    }

    @Test
    fun aMetadataPageAdvancesItsRevisionAndRetainsRoundEvidence() {
        val first = advance(initial(), "history/b", more = true, unresolved = true)
        val next = advance(first, "history/c")
        assertEquals(2, next.revision)
        assertEquals("history/c", next.afterPartition)
        assertTrue(next.moreHistoryPages)
        assertTrue(next.unresolved)
        assertFalse(next.complete)
    }

    @Test
    fun anEmptyCatalogFinishesWithoutAnotherRound() {
        val done = advance(initial())
        assertTrue(done.complete)
        assertFalse(done.unresolved)
        assertNull(done.afterPartition)
        assertEquals(1, done.revision)
    }

    @Test
    fun unavailableContentAloneFinishesAndWaitsForAnotherTrigger() {
        val done = advance(advance(initial(), "history/a", unresolved = true))
        assertTrue(done.complete)
        assertTrue(done.unresolved)
        assertFalse(done.moreHistoryPages)
        assertNull(done.retry)
        assertEquals(done, request(done, 1))
    }

    @Test
    fun anotherHistoryPageStartsANewRoundWithoutChangingThePassGeneration() {
        val before = advance(initial(), "history/z", more = true)
        val round = advance(before)
        assertEquals(id(1), round.generation)
        assertEquals(2, round.revision)
        assertNull(round.afterPartition)
        assertFalse(round.moreHistoryPages)
        assertFalse(round.complete)
        val done = advance(advance(round, "history/z"))
        assertTrue(done.complete)
    }

    @Test
    fun laterRequestsWaitUntilCurrentHistoriesFinishTheirFinitePasses() {
        val active = request(advance(initial(), "history/z", more = true), 2)
        val round = advance(active)
        assertEquals(id(1), round.generation)
        assertEquals(id(2), round.requestedGeneration)
        assertEquals(CollaborationSpaceHistoryRecovery(id(2)), advance(round))
    }

    @Test
    fun currentEofPromotesOnlyTheLatestRequestedGeneration() {
        val active = request(request(advance(initial(), "history/a", unresolved = true), 2), 3)
        assertEquals(CollaborationSpaceHistoryRecovery(id(3)), advance(active))
    }

    @Test
    fun aLaterRequestArrivingDuringTheCapturedPageIsPreserved() {
        val captured = advance(initial(), "history/a")
        val current = request(captured, 2)
        val next =
            value(
                planCollaborationSpaceHistoryRecoveryAdvance(
                    current,
                    captured,
                    "history/b",
                    false,
                    false,
                )
            )
        assertEquals(id(2), next.requestedGeneration)
        assertEquals("history/b", next.afterPartition)
    }

    @Test
    fun anAlreadyCapturedRequestCannotDisappearDuringAdvancement() {
        val captured = request(initial(), 2)
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryAdvance(initial(), captured, null, false, false)
        )
    }

    @Test
    fun anOldTurnCannotAdvanceAfterANewRoundReturnsToTheSameCursor() {
        val captured = initial()
        val later = advance(initial(), more = true)
        assertNull(later.afterPartition)
        assertNotEquals(captured.revision, later.revision)
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryAdvance(later, captured, "history/a", false, false)
        )
    }

    @Test
    fun aCapturedTurnCannotCrossChangedGenerationProgressOrDelay() {
        val captured = advance(initial(), "history/a")
        for (other in
            listOf(
                captured.copy(generation = id(2)),
                captured.copy(unresolved = true),
                captured.copy(moreHistoryPages = true),
                captured.copy(retry = CollaborationSpaceIncomingRetry(0, 30_000, 1)),
            )) {
            assertIs<AppResult.Failed>(
                planCollaborationSpaceHistoryRecoveryAdvance(
                    other,
                    captured,
                    "history/b",
                    false,
                    false,
                )
            )
        }
    }

    @Test
    fun duplicateBackwardAndUnboundedMetadataCursorsAreRejected() {
        val pass = advance(initial(), "history/b")
        for (next in
            listOf("history/b", "history/a", "", "x".repeat(161), "with space", "nonascii-ä")) {
            assertIs<AppResult.Failed>(
                planCollaborationSpaceHistoryRecoveryAdvance(pass, pass, next, false, false)
            )
        }
        assertTrue(
            validCollaborationSpaceHistoryRecovery(pass.copy(afterPartition = "x".repeat(160)))
        )
    }

    @Test
    fun operationalDeferralPreservesPageRoundEvidenceAndALaterRequest() {
        val captured = advance(initial(), "history/a", more = true, unresolved = true)
        val current = request(captured, 2)
        val retry = CollaborationSpaceIncomingRetry(1_000, 31_000, 1)
        val deferred = value(planCollaborationSpaceHistoryRecoveryDefer(current, captured, retry))
        assertEquals(current.copy(revision = current.revision + 1, retry = retry), deferred)
        val next = advance(deferred, "history/b")
        assertNull(next.retry)
        assertTrue(next.moreHistoryPages)
    }

    @Test
    fun staleCompletedAndInvalidDeferralsCannotRequeueWork() {
        val captured = initial()
        val retry = CollaborationSpaceIncomingRetry(0, 30_000, 1)
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryDefer(advance(captured), captured, retry)
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryDefer(
                advance(captured, "history/a"),
                captured,
                retry,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryDefer(captured, captured, retry.copy(failures = 0))
        )
    }

    @Test
    fun turnRevisionOverflowCannotWrapAndAcceptAnOldCapture() {
        val last = initial().copy(revision = 9_007_199_254_740_990L)
        val next = advance(last, "history/a")
        assertEquals(9_007_199_254_740_991L, next.revision)
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryAdvance(next, next, "history/b", false, false)
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceHistoryRecoveryDefer(
                next,
                next,
                CollaborationSpaceIncomingRetry(0, 30_000, 1),
            )
        )
        assertFalse(validCollaborationSpaceHistoryRecovery(next.copy(revision = Long.MAX_VALUE)))
    }

    @Test
    fun completedStateCannotRetainActiveCursorRoundRequestOrRetry() {
        val done = advance(initial())
        for (bad in
            listOf(
                done.copy(afterPartition = "history/a"),
                done.copy(moreHistoryPages = true),
                done.copy(requestedGeneration = id(2)),
                done.copy(retry = CollaborationSpaceIncomingRetry(0, 30_000, 1)),
            )) {
            assertFalse(validCollaborationSpaceHistoryRecovery(bad))
            assertFalse(
                validCollaborationSpaceIncomingDemand(
                    CollaborationSpaceIncomingDemand(historyRecovery = bad)
                )
            )
        }
    }

    @Test
    fun ordinaryRoomSchedulingPreservesIndependentHistoryRecovery() {
        val pass = advance(initial(), "history/a", more = true, unresolved = true)
        val original = CollaborationSpaceIncomingDemand(historyRecovery = pass)
        val changed =
            value(
                nextCollaborationSpaceIncomingDemand(
                    original,
                    CollaborationSpaceIncomingDemandChange.Request(id(8), id(9), null),
                )
            )
        assertEquals(pass, changed.historyRecovery)
        assertEquals(1, changed.rooms.size)
        assertEquals(
            pass,
            captureCollaborationSpaceIncomingDemandSnapshot(
                    CollaborationSpaceIncomingDemandSnapshot(id(10), changed)
                )
                ?.demand
                ?.historyRecovery,
        )
        assertFalse(pass.toString().contains("history/a"))
    }
}
