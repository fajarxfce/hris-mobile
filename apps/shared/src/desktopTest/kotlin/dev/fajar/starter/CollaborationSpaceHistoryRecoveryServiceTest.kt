@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceHistoryRecoveryServiceTest {
    @Test
    fun idleAndDeferredSelectionDoNotAcquireContentOrInventCompletion() = runTest {
        val retry = CollaborationSpaceIncomingRetry(0, 30_000, 1)
        for (selection in
            listOf(
                CollaborationSpaceHistoryRecoverySelection.Idle,
                CollaborationSpaceHistoryRecoverySelection.Deferred(retry),
            )) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            f.selection = selection
            val expected =
                if (selection == CollaborationSpaceHistoryRecoverySelection.Idle)
                    CollaborationSpaceHistoryRecoveryOutcome.Idle
                else CollaborationSpaceHistoryRecoveryOutcome.Deferred
            assertEquals(AppResult.Success(expected), f.run())
            assertEquals(listOf("select"), f.calls)
            assertTrue(f.deferrals.isEmpty())
        }
    }

    @Test
    fun originalVisitCompletesLocalPageBeforeGlobalTurn() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced), f.run())
        assertEquals(
            listOf(
                "select",
                "load-original",
                "read-original",
                "acquire-original",
                "complete-original",
                "complete",
            ),
            f.calls,
        )
        assertEquals(listOf(f.company.companyId to f.company.history), f.companyQueries)
        val attempt =
            assertIs<CollaborationSpaceHistoryRecoveryAttempt.Original>(f.attempts.single())
        assertEquals(f.originalHistory, attempt.history)
        assertEquals(f.pageReceipt, attempt.completion)
        assertEquals(f.read, attempt.read)
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun publishedVisitPreservesLogicalSpaceAndHistoricalDevice() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.useSpace()
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced), f.run())
        assertEquals(
            listOf(
                "select",
                "load-space",
                "read-space",
                "acquire-space",
                "complete-space",
                "complete",
            ),
            f.calls,
        )
        assertEquals(f.turn.page.locators, f.spaceQueries)
        assertTrue(f.companyQueries.isEmpty())
        val attempt =
            assertIs<CollaborationSpaceHistoryRecoveryAttempt.Published>(f.attempts.single())
        assertEquals(f.spaceHistory, attempt.history)
        assertNotEquals(f.runtime.deviceId, attempt.history.traversal.deviceId)
    }

    @Test
    fun absentAndAlreadyCompleteReadsSkipContentAcquisition() = runTest {
        for (space in listOf(false, true)) for (absent in listOf(false, true)) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            if (space) f.useSpace()
            val progress = assertNotNull(f.references.progress)
            f.read =
                if (absent) CollaborationHistoryRecoveryRead.Absent
                else
                    CollaborationHistoryRecoveryRead.Complete(
                        progress.copy(
                            recovery = assertNotNull(progress.recovery).copy(complete = true)
                        )
                    )
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced),
                f.run(),
            )
            assertTrue(f.acquiredPages.isEmpty())
            assertTrue(f.completedPages.isEmpty())
            assertEquals(1, f.attempts.size)
            assertEquals("complete", f.calls.last())
        }
    }

    @Test
    fun unavailableSourceAndMetadataUseExplicitGlobalAttempt() = runTest {
        for (space in listOf(false, true)) for (metadata in listOf(false, true)) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            if (space) {
                f.useSpace()
                f.spaceResolution =
                    if (metadata) CollaborationSpaceKeyHistoryResolution.MetadataUnavailable
                    else CollaborationSpaceKeyHistoryResolution.SourceUnavailable
            } else
                f.originalResolution =
                    if (metadata) CollaborationSpaceOriginalKeyHistoryResolution.MetadataUnavailable
                    else CollaborationSpaceOriginalKeyHistoryResolution.SourceUnavailable
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced),
                f.run(),
            )
            assertEquals(
                listOf<CollaborationSpaceHistoryRecoveryAttempt?>(
                    CollaborationSpaceHistoryRecoveryAttempt.Unavailable
                ),
                f.attempts,
            )
            assertTrue(f.acquiredPages.isEmpty())
            assertTrue(f.deferrals.isEmpty())
        }
    }

    @Test
    fun emptyInternalMetadataDoesNotInventAHistory() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.turn =
            f.turn.copy(
                page = CollaborationAccountIncomingHistoryLocatorPage(emptyList(), "next-partition")
            )
        f.selection = CollaborationSpaceHistoryRecoverySelection.Ready(f.turn)
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced), f.run())
        assertEquals(listOf("select", "complete"), f.calls)
        assertEquals(listOf<CollaborationSpaceHistoryRecoveryAttempt?>(null), f.attempts)
    }

    @Test
    fun operationalFailureDefersCapturedTurnExactlyOnce() = runTest {
        for (stage in
            listOf(
                "load-original",
                "read-original",
                "acquire-original",
                "complete-original",
                "complete",
            )) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            f.failures[stage] = f.failure
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Deferred),
                f.run(),
            )
            assertEquals(listOf(f.turn to f.failure), f.deferrals)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals("defer", f.calls.last())
        }
    }

    @Test
    fun failureBeforeSelectionCannotDeferUnknownTurn() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.failures["select"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.run())
        assertEquals(listOf("select"), f.calls)
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun authorityAndValidationFailuresStayFailures() = runTest {
        for (kind in
            listOf(FailureKind.Unauthorized, FailureKind.AccessDenied, FailureKind.Validation)) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            val failure = f.failure.copy(kind = kind)
            f.failures["acquire-original"] = failure
            assertEquals(AppResult.Failed(failure), f.run())
            assertTrue(f.deferrals.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun unchangedConflictIsNotImmediateReschedulingOrBackoff() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val failure = f.failure.copy(kind = FailureKind.Conflict)
        f.failures["complete"] = failure
        assertEquals(AppResult.Failed(failure), f.run())
        assertEquals(2, f.calls.count { it == "select" })
        assertEquals(1, f.calls.count { it == "complete" })
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun completedOrDeferredActualWorkMayBeReselectedAfterConflict() = runTest {
        for (deferred in listOf(false, true)) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            f.failures["complete"] = f.failure.copy(kind = FailureKind.Conflict)
            f.before = { stage ->
                if (stage == "complete")
                    f.selection =
                        if (deferred)
                            CollaborationSpaceHistoryRecoverySelection.Deferred(
                                CollaborationSpaceIncomingRetry(0, 30_000, 1)
                            )
                        else CollaborationSpaceHistoryRecoverySelection.Idle
            }
            val expected =
                if (deferred) CollaborationSpaceHistoryRecoveryOutcome.Deferred
                else CollaborationSpaceHistoryRecoveryOutcome.Obsolete
            assertEquals(AppResult.Success(expected), f.run())
            assertTrue(f.deferrals.isEmpty())
            assertEquals(2, f.calls.count { it == "select" })
        }
    }

    @Test
    fun laterRequestedGenerationAloneDoesNotMakeFailedTurnObsolete() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val failure = f.failure.copy(kind = FailureKind.Conflict)
        f.failures["complete"] = failure
        f.before = { stage ->
            if (stage == "complete")
                f.selection =
                    CollaborationSpaceHistoryRecoverySelection.Ready(
                        f.turn.copy(
                            recovery = f.recovery.copy(requestedGeneration = recoveryId(980))
                        )
                    )
        }
        assertEquals(AppResult.Failed(failure), f.run())
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun actuallyAdvancedTurnCanBeReselectedWithoutAnotherWrite() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.failures["complete"] = f.failure.copy(kind = FailureKind.Conflict)
        f.before = { stage ->
            if (stage == "complete")
                f.selection =
                    CollaborationSpaceHistoryRecoverySelection.Ready(
                        f.turn.copy(
                            recovery = f.recovery.copy(revision = 1, afterPartition = "next")
                        )
                    )
        }
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Obsolete), f.run())
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun changedRegisteredSourceCannotTurnConflictIntoObsolete() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val failure = f.failure.copy(kind = FailureKind.Conflict)
        f.failures["complete"] = failure
        f.before = { stage ->
            if (stage == "complete")
                f.selection =
                    CollaborationSpaceHistoryRecoverySelection.Ready(
                        f.turn.copy(
                            source = f.source.copy(matrixDeviceId = "OTHER"),
                            recovery = f.recovery.copy(revision = 1, afterPartition = "next"),
                        )
                    )
        }
        assertEquals(AppResult.Failed(failure), f.run())
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun successfulFalseCompletionIsObsoleteWithoutDeferral() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.completed = false
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Obsolete), f.run())
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun selectedMetadataAndReadReferencesAreCopiedBeforeFurtherSuspension() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val rows = f.turn.page.locators.toMutableList()
        val events = f.references.events.toMutableList()
        f.selection =
            CollaborationSpaceHistoryRecoverySelection.Ready(
                f.turn.copy(page = f.turn.page.copy(locators = rows))
            )
        f.read = CollaborationHistoryRecoveryRead.Pending(f.references.copy(events = events))
        f.before = { stage ->
            if (stage == "load-original") rows.clear()
            if (stage == "acquire-original") events.clear()
        }
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced), f.run())
        assertEquals(listOf(f.company), f.completedTurns.single().page.locators)
        assertEquals(f.references, f.acquiredPages.single())
        assertEquals(f.references, f.completedPages.single())
        assertEquals(
            f.references,
            assertIs<CollaborationHistoryRecoveryRead.Pending>(
                    assertIs<CollaborationSpaceHistoryRecoveryAttempt.Original>(f.attempts.single())
                        .read
                )
                .references,
        )
    }

    @Test
    fun oversizedSelectionAndReferencesAreRejectedBeforeAcquisition() = runTest {
        for (selection in listOf(false, true)) {
            val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
            if (selection)
                f.selection =
                    CollaborationSpaceHistoryRecoverySelection.Ready(
                        f.turn.copy(page = f.turn.page.copy(locators = List(2) { f.company }))
                    )
            else
                f.read =
                    CollaborationHistoryRecoveryRead.Pending(
                        f.references.copy(events = List(17) { f.references.events.first() })
                    )
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.acquiredPages.isEmpty())
            assertTrue(f.deferrals.isEmpty())
        }
    }

    @Test
    fun emptyPendingPageStillUsesAdmittedLocalCompletion() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.read = CollaborationHistoryRecoveryRead.Pending(f.references.copy(events = emptyList()))
        f.pageReceipt = f.pageReceipt.copy(resolved = 0)
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Advanced), f.run())
        assertTrue(f.acquiredPages.single().events.isEmpty())
        assertTrue(f.completedPages.single().events.isEmpty())
        assertEquals(
            f.pageReceipt,
            assertIs<CollaborationSpaceHistoryRecoveryAttempt.Original>(f.attempts.single())
                .completion,
        )
    }

    @Test
    fun totalVisitTimeoutHasOneBoundedDeferral() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.before = { stage -> if (stage == "acquire-original") awaitCancellation() }
        val started = testScheduler.currentTime
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Deferred), f.run())
        assertEquals(30_000L, testScheduler.currentTime - started)
        assertEquals(FailureKind.Timeout, f.deferrals.single().second.kind)
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun blockedDeferralCannotExtendVisitBeyondThirtyFiveSeconds() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.before = { stage ->
            if (stage in listOf("acquire-original", "defer")) awaitCancellation()
        }
        val started = testScheduler.currentTime
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(35_000L, testScheduler.currentTime - started)
        assertEquals(1, f.deferrals.size)
    }

    @Test
    fun parentCancellationDoesNotBecomeOperationalRetry() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val reached = CompletableDeferred<Unit>()
        f.before = { stage ->
            if (stage == "acquire-original") {
                reached.complete(Unit)
                awaitCancellation()
            }
        }
        val task = async { f.run() }
        reached.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(f.deferrals.isEmpty())
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun deferralFailureIsReturnedWithoutAnotherAttempt() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.failures["acquire-original"] = f.failure
        val denied = f.failure.copy(kind = FailureKind.AccessDenied)
        f.failures["defer"] = denied
        assertEquals(AppResult.Failed(denied), f.run())
        assertEquals(1, f.deferrals.size)
        assertEquals(1, f.calls.count { it == "select" })
    }

    @Test
    fun unchangedQueueContentionDoesNotBecomeOperationalDeferral() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        val failed =
            dev.fajar.starter.collaboration.domain.policies
                .collaborationSpaceIncomingQueueContention()
        f.failures["complete"] = failed.failure
        assertEquals(failed, f.run())
        assertEquals(2, f.calls.count { it == "select" })
        assertEquals(1, f.calls.count { it == "complete" })
        assertTrue(f.deferrals.isEmpty())
    }

    @Test
    fun queueContentionObservesAnActuallyAdvancedTurnWithoutAnotherWrite() = runTest {
        val f = SpaceHistoryRecoveryServiceFixture(testScheduler)
        f.failures["complete"] =
            dev.fajar.starter.collaboration.domain.policies
                .collaborationSpaceIncomingQueueContention()
                .failure
        f.before = { stage ->
            if (stage == "complete")
                f.selection =
                    CollaborationSpaceHistoryRecoverySelection.Ready(
                        f.turn.copy(
                            recovery = f.recovery.copy(revision = 1, afterPartition = "next")
                        )
                    )
        }
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoveryOutcome.Obsolete), f.run())
        assertEquals(2, f.calls.count { it == "select" })
        assertEquals(1, f.attempts.size)
        assertTrue(f.deferrals.isEmpty())
    }
}
