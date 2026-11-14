package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistorySourceStepPolicyTest {
    @Test
    fun missingSourcePreparationIsAttemptedOncePerVisit() {
        val f = HistorySourceStepFixture()
        val first = assertIs<CollaborationSpaceInboxHistorySourceStep.Prepare>(f.next(null))
        assertEquals(f.base.id, first.conversationId)
        assertEquals(f.base.pass, first.passId)
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(null),
            f.next(null, setOf(CollaborationSpaceInboxHistoryVisitOperation.Prepare)),
        )
    }

    @Test
    fun unchangedFailedHistoryStillAllowsPendingThenYields() {
        val f = HistorySourceStepFixture()
        assertIs<CollaborationSpaceInboxHistorySourceStep.History>(f.next())
        val historyAttempted = setOf(CollaborationSpaceInboxHistoryVisitOperation.History)
        assertIs<CollaborationSpaceInboxHistorySourceStep.Pending>(
            f.next(attempted = historyAttempted)
        )
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(f.base.source),
            f.next(
                attempted = historyAttempted + CollaborationSpaceInboxHistoryVisitOperation.Pending
            ),
        )
    }

    @Test
    fun anOlderUnfinishedIntervalIsReadWithItsExactCheckpoint() {
        val f = HistorySourceStepFixture()
        val old = f.base.source.copy(progress = f.base.source.progress.copy(passId = f.base.other))
        val step = assertIs<CollaborationSpaceInboxHistorySourceStep.History>(f.next(old))
        assertEquals(old, step.expected)
        assertEquals(f.base.other, step.expected.progress.passId)
    }

    @Test
    fun finishingAnOlderIntervalYieldsBeforePreparingTheLaterPass() {
        val f = HistorySourceStepFixture()
        val old = f.complete().let { it.copy(progress = it.progress.copy(passId = f.base.other)) }
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(old),
            f.next(old, setOf(CollaborationSpaceInboxHistoryVisitOperation.Pending)),
        )
        val prepared = assertIs<CollaborationSpaceInboxHistorySourceStep.Prepare>(f.next(old))
        assertEquals(f.base.pass, prepared.passId)
    }

    @Test
    fun destinationReplacementMustPrepareBeforeReadingAnOldCursor() {
        val f = HistorySourceStepFixture()
        val destination = CollaborationSpaceInboxVersion(f.base.other, 5, 5)
        assertIs<CollaborationSpaceInboxHistorySourceStep.Prepare>(
            f.next(destination = destination)
        )
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(f.base.source),
            f.next(
                attempted = setOf(CollaborationSpaceInboxHistoryVisitOperation.Prepare),
                destination = destination,
            ),
        )
    }

    @Test
    fun resettingRawHistoryDoesNotRenewThisVisitsHistoryBudget() {
        val f = HistorySourceStepFixture()
        val reset = f.base.source.copy(version = f.base.source.version.copy(revision = 6))
        val attempted = setOf(CollaborationSpaceInboxHistoryVisitOperation.History)
        assertIs<CollaborationSpaceInboxHistorySourceStep.Pending>(f.next(reset, attempted))
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(reset),
            f.next(reset, attempted + CollaborationSpaceInboxHistoryVisitOperation.Pending),
        )
    }

    @Test
    fun completedLanesAreNotRepeatedAndHealthyHistoryYieldsAfterOnePage() {
        val f = HistorySourceStepFixture()
        val pendingComplete =
            f.base.source.copy(progress = f.base.source.progress.copy(pendingWindow = f.window))
        assertIs<CollaborationSpaceInboxHistorySourceStep.History>(f.next(pendingComplete))
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(pendingComplete),
            f.next(pendingComplete, setOf(CollaborationSpaceInboxHistoryVisitOperation.History)),
        )
        val historyComplete =
            f.base.source.copy(progress = f.base.source.progress.copy(historyComplete = true))
        assertIs<CollaborationSpaceInboxHistorySourceStep.Pending>(f.next(historyComplete))
        assertEquals(
            CollaborationSpaceInboxHistorySourceStep.Yield(historyComplete),
            f.next(historyComplete, setOf(CollaborationSpaceInboxHistoryVisitOperation.Pending)),
        )
    }

    @Test
    fun currentCompletedPassDoesNotPrepareOrReadAgain() {
        val f = HistorySourceStepFixture()
        val done = f.complete()
        assertEquals(CollaborationSpaceInboxHistorySourceStep.Yield(done), f.next(done))
        val exhausted =
            done.copy(version = done.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER))
        assertEquals(CollaborationSpaceInboxHistorySourceStep.Yield(exhausted), f.next(exhausted))
    }

    @Test
    fun invalidOwnershipSourceAndExhaustedWritesNeverChooseAnOperation() {
        val f = HistorySourceStepFixture()
        val exhausted =
            f.base.source.copy(
                version = f.base.source.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER)
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistorySourceStep(
                        f.base.access,
                        f.base.id,
                        f.base.pass,
                        null,
                        exhausted,
                        emptySet(),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistorySourceStep(
                        f.base.access,
                        f.base.other,
                        f.base.pass,
                        null,
                        f.base.source,
                        emptySet(),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistorySourceStep(
                        f.base.access.copy(spaceId = "invalid"),
                        f.base.id,
                        f.base.pass,
                        null,
                        null,
                        emptySet(),
                    )
                )
                .failure
                .kind,
        )
    }
}

private class HistorySourceStepFixture {
    val base = HistoryGlobalPolicyFixture()
    val window = CollaborationSpaceInboxHistoryPendingWindow(base.other, 5, null, true)

    fun complete() =
        base.source.copy(
            progress = base.source.progress.copy(historyComplete = true, pendingWindow = window)
        )

    fun next(
        current: CollaborationSpaceInboxHistorySourceCheckpoint? = base.source,
        attempted: Set<CollaborationSpaceInboxHistoryVisitOperation> = emptySet(),
        destination: CollaborationSpaceInboxVersion? = null,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceStep>>(
                nextCollaborationSpaceInboxHistorySourceStep(
                    base.access,
                    base.id,
                    base.pass,
                    destination,
                    current,
                    attempted,
                )
            )
            .value
}
