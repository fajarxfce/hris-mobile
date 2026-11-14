package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxRepairProgressPolicyTest {
    @Test
    fun aNewDemandStartsOneFinitePassAndBeginningItAgainDoesNotResetItsCursor() {
        val requested = next(null, CollaborationSpaceInboxRepairProgressChange.Request(id(1)), 2)
        val begun = next(requested, CollaborationSpaceInboxRepairProgressChange.Begin, 3)
        val advanced =
            next(
                begun,
                CollaborationSpaceInboxRepairProgressChange.Advance(begun.pass!!, "page-two"),
                4,
            )
        assertEquals(advanced, next(advanced, CollaborationSpaceInboxRepairProgressChange.Begin, 5))
        assertEquals("page-two", advanced.pass?.after)
    }

    @Test
    fun demandDuringAnActivePassIsCoalescedWithoutDiscardingItsCapturedProgress() {
        val active = active()
        val newer = next(active, CollaborationSpaceInboxRepairProgressChange.Request(id(6)), 7)
        val latest = next(newer, CollaborationSpaceInboxRepairProgressChange.Request(id(8)), 9)
        assertEquals(active.pass, latest.pass)
        assertEquals(id(8), latest.latestDemand)
        val finished =
            next(
                latest,
                CollaborationSpaceInboxRepairProgressChange.Advance(active.pass!!, null),
                10,
            )
        assertEquals(id(1), finished.completedDemand)
        assertEquals(id(8), finished.latestDemand)
        val resumed = next(finished, CollaborationSpaceInboxRepairProgressChange.Begin, 11)
        assertEquals(CollaborationSpaceInboxRepairPass(id(8), null), resumed.pass)
    }

    @Test
    fun aPendingOnlyPassBecomesIdleAfterItsLastPageAndDoesNotGenerateAnotherDemand() {
        val active = active()
        val done =
            next(
                active,
                CollaborationSpaceInboxRepairProgressChange.Advance(active.pass!!, null),
                5,
            )
        assertNull(done.pass)
        assertEquals(done.latestDemand, done.completedDemand)
        assertEquals(done, next(done, CollaborationSpaceInboxRepairProgressChange.Begin, 6))
        assertEquals(
            done,
            next(done, CollaborationSpaceInboxRepairProgressChange.Request(id(1)), 7),
        )
    }

    @Test
    fun aRepeatedCurrentDemandDoesNotRewriteAnActivePassOrCreateAnotherGeneration() {
        val active = active()
        assertEquals(
            active,
            next(active, CollaborationSpaceInboxRepairProgressChange.Request(id(1)), 8),
        )
    }

    @Test
    fun stalePageCompletionCannotAcknowledgeANewerCursorOrNewlyStartedPass() {
        val active = active()
        val moved =
            next(
                active,
                CollaborationSpaceInboxRepairProgressChange.Advance(active.pass!!, "page-three"),
                6,
            )
        assertEquals(
            FailureKind.Conflict,
            failure(moved, CollaborationSpaceInboxRepairProgressChange.Advance(active.pass, null))
                .kind,
        )
        val done =
            next(moved, CollaborationSpaceInboxRepairProgressChange.Advance(moved.pass!!, null), 7)
        val requested = next(done, CollaborationSpaceInboxRepairProgressChange.Request(id(8)), 9)
        val restarted = next(requested, CollaborationSpaceInboxRepairProgressChange.Begin, 10)
        assertEquals(
            FailureKind.Conflict,
            failure(
                    restarted,
                    CollaborationSpaceInboxRepairProgressChange.Advance(moved.pass, null),
                )
                .kind,
        )
    }

    @Test
    fun aPageThatDoesNotAdvanceCannotKeepTheWorkerRunning() {
        val active = active()
        assertEquals(
            FailureKind.Conflict,
            failure(
                    active,
                    CollaborationSpaceInboxRepairProgressChange.Advance(
                        active.pass!!,
                        active.pass.after,
                    ),
                )
                .kind,
        )
    }

    @Test
    fun generationReplacementCanExplicitlyRestartOnlyTheMatchingCapturedPass() {
        val active = active()
        val newer = next(active, CollaborationSpaceInboxRepairProgressChange.Request(id(6)), 7)
        val restarted =
            next(newer, CollaborationSpaceInboxRepairProgressChange.Restart(active.pass!!), 8)
        assertEquals(id(6), restarted.latestDemand)
        assertEquals(CollaborationSpaceInboxRepairPass(id(1), null), restarted.pass)
        assertEquals(
            FailureKind.Conflict,
            failure(restarted, CollaborationSpaceInboxRepairProgressChange.Restart(active.pass))
                .kind,
        )
        assertEquals(
            FailureKind.Validation,
            failure(
                    restarted,
                    CollaborationSpaceInboxRepairProgressChange.Restart(restarted.pass!!),
                )
                .kind,
        )
    }

    @Test
    fun replayingAnOlderActiveOrCompletedDemandCannotReplaceALaterDemand() {
        val active = active()
        val newer = next(active, CollaborationSpaceInboxRepairProgressChange.Request(id(6)), 7)
        assertEquals(
            FailureKind.Conflict,
            failure(newer, CollaborationSpaceInboxRepairProgressChange.Request(id(1))).kind,
        )
        val done =
            next(newer, CollaborationSpaceInboxRepairProgressChange.Advance(active.pass!!, null), 8)
        assertEquals(
            FailureKind.Conflict,
            failure(done, CollaborationSpaceInboxRepairProgressChange.Request(id(1))).kind,
        )
    }

    @Test
    fun invalidCoordinatesAndMalformedStatesAreRejectedBeforeAProgressWrite() {
        val active = active()
        for (invalid in
            listOf(
                active.copy(generation = "bad"),
                active.copy(latestDemand = "bad"),
                active.copy(completedDemand = active.latestDemand),
                active.copy(pass = active.pass!!.copy(after = "")),
            )) {
            assertFalse(validCollaborationSpaceInboxRepairProgress(invalid))
            assertEquals(
                FailureKind.Validation,
                failure(invalid, CollaborationSpaceInboxRepairProgressChange.Begin).kind,
            )
        }
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxRepairProgress(
                active,
                CollaborationSpaceInboxRepairProgressChange.Request(id(6)),
                active.generation,
            )
        )
        assertEquals(
            FailureKind.Validation,
            failure(
                    active,
                    CollaborationSpaceInboxRepairProgressChange.Advance(
                        active.pass,
                        "x".repeat(4097),
                    ),
                )
                .kind,
        )
    }

    @Test
    fun bootstrapCreatesAMissingRequestWithoutStartingOrCompletingItsPass() {
        val result =
            next(null, CollaborationSpaceInboxRepairProgressChange.EnsureInitialized(id(1)), 2)
        assertEquals(id(1), result.latestDemand)
        assertEquals(id(2), result.generation)
        assertNull(result.completedDemand)
        assertNull(result.pass)
    }

    @Test
    fun bootstrapPreservesExistingPendingActiveAndCompletedProgressExactly() {
        val pending = next(null, CollaborationSpaceInboxRepairProgressChange.Request(id(1)), 2)
        val active = active()
        val done = active.copy(pass = null, completedDemand = active.latestDemand)
        for (current in listOf(pending, active, done)) {
            assertEquals(
                current,
                next(
                    current,
                    CollaborationSpaceInboxRepairProgressChange.EnsureInitialized(id(90)),
                    91,
                ),
            )
            assertEquals(
                current,
                next(
                    current,
                    CollaborationSpaceInboxRepairProgressChange.EnsureInitialized(id(92)),
                    93,
                ),
            )
        }
    }

    @Test
    fun bootstrapCannotReplaceAGenuineRequestThatArrivedBeforeItsRetry() {
        val old = active()
        val genuine = next(old, CollaborationSpaceInboxRepairProgressChange.Request(id(10)), 11)
        val result =
            next(genuine, CollaborationSpaceInboxRepairProgressChange.EnsureInitialized(id(12)), 13)
        assertEquals(genuine, result)
        assertEquals(old.pass, result.pass)
        assertEquals(id(10), result.latestDemand)
    }

    @Test
    fun malformedBootstrapCoordinatesAreRejectedEvenForAnExistingPass() {
        for (current in listOf(null, active())) {
            assertEquals(
                FailureKind.Validation,
                failure(
                        current,
                        CollaborationSpaceInboxRepairProgressChange.EnsureInitialized("invalid"),
                    )
                    .kind,
            )
        }
        assertFalse(
            validCollaborationSpaceInboxRepairProgressChange(
                CollaborationSpaceInboxRepairProgressChange.EnsureInitialized("invalid")
            )
        )
        assertTrue(
            validCollaborationSpaceInboxRepairProgressChange(
                CollaborationSpaceInboxRepairProgressChange.EnsureInitialized(id(1))
            )
        )
    }

    private fun active(): CollaborationSpaceInboxRepairProgress {
        val requested = next(null, CollaborationSpaceInboxRepairProgressChange.Request(id(1)), 2)
        val begun = next(requested, CollaborationSpaceInboxRepairProgressChange.Begin, 3)
        return next(
            begun,
            CollaborationSpaceInboxRepairProgressChange.Advance(begun.pass!!, "page-two"),
            4,
        )
    }

    private fun next(
        current: CollaborationSpaceInboxRepairProgress?,
        change: CollaborationSpaceInboxRepairProgressChange,
        generation: Int,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                collaborationSpaceInboxRepairProgress(current, change, id(generation))
            )
            .value

    private fun failure(
        current: CollaborationSpaceInboxRepairProgress?,
        change: CollaborationSpaceInboxRepairProgressChange,
    ) =
        assertIs<AppResult.Failed>(collaborationSpaceInboxRepairProgress(current, change, id(99)))
            .failure

    private fun id(value: Int) = "00000000-0000-4000-8000-" + value.toString().padStart(12, '0')
}
