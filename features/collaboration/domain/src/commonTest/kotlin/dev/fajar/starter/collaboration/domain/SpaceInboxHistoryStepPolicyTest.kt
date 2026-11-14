package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryStepPolicyTest {
    @Test
    fun absentProgressCreatesOneCapturedPassBeforeAnyCatalogRead() {
        val f = HistoryStepPolicyFixture()
        val step = assertIs<CollaborationSpaceInboxHistoryStep.Change>(f.next(null))
        assertNull(step.expected)
        assertIs<CollaborationSpaceInboxHistoryChange.Observe>(step.change)
        val retained = f.commit(step)
        val next = assertIs<CollaborationSpaceInboxHistoryStep.Catalog>(f.next(retained))
        assertEquals(retained, next.expected)
        assertNull(next.after)
    }

    @Test
    fun completedUnchangedReopenIsIdleEvenWhenOwnerRecoveryWasRequested() {
        val f = HistoryStepPolicyFixture()
        val completed = f.checkpoint(f.base.finish(f.base.start()))
        assertEquals(CollaborationSpaceInboxHistoryStep.Idle, f.next(completed))
        assertEquals(CollaborationSpaceInboxHistoryStep.Idle, f.next(completed, recover = true))
    }

    @Test
    fun laterDemandIsRetainedBeforeResumingTheOriginalQueuedPass() {
        val f = HistoryStepPolicyFixture()
        val queued = f.checkpoint(f.base.batch(f.base.start(), listOf(f.base.id)))
        val trigger = f.base.trigger.copy(historySignal = f.base.other)
        val change = assertIs<CollaborationSpaceInboxHistoryStep.Change>(f.next(queued, trigger))
        val retained = f.commit(change)
        assertEquals(trigger, retained.progress.latest)
        assertEquals(queued.progress.pass, retained.progress.pass)
        val visit = assertIs<CollaborationSpaceInboxHistoryStep.Visit>(f.next(retained, trigger))
        assertEquals(f.base.pass, visit.passId)
        assertEquals(f.base.id, visit.conversationId)
    }

    @Test
    fun aRetainedQueueAlwaysVisitsItsHeadBeforeReadingAnotherPage() {
        val f = HistoryStepPolicyFixture()
        for (initial in listOf(f.base.start(), f.base.sources())) {
            val queued =
                f.checkpoint(f.base.batch(initial, listOf(f.base.id, f.base.other), "next"))
            val visit = assertIs<CollaborationSpaceInboxHistoryStep.Visit>(f.next(queued))
            assertEquals(f.base.id, visit.conversationId)
            assertEquals(queued, visit.expected)
        }
    }

    @Test
    fun emptyCatalogContinuationAndSourcesKeepTheirOwnRetainedCursor() {
        val f = HistoryStepPolicyFixture()
        val catalog = f.checkpoint(f.base.batch(f.base.start(), emptyList(), "catalog-next"))
        assertEquals(
            "catalog-next",
            assertIs<CollaborationSpaceInboxHistoryStep.Catalog>(f.next(catalog)).after,
        )
        val initial = f.base.sources()
        val sources =
            f.checkpoint(
                initial.copy(
                    pass =
                        initial.pass!!.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Sources(
                                    "source-next",
                                    null,
                                    true,
                                    false,
                                )
                        )
                )
            )
        assertEquals(
            "source-next",
            assertIs<CollaborationSpaceInboxHistoryStep.Sources>(f.next(sources)).after,
        )
    }

    @Test
    fun aFailedOnlyRoundNeedsExplicitRecoveryOrNewDemand() {
        val f = HistoryStepPolicyFixture()
        val parked = f.checkpoint(f.base.ack(f.base.batch(f.base.sources(), listOf(f.base.id))))
        assertEquals(CollaborationSpaceInboxHistoryStep.Idle, f.next(parked))
        val explicit =
            assertIs<CollaborationSpaceInboxHistoryStep.Change>(f.next(parked, recover = true))
        assertIs<CollaborationSpaceInboxHistoryChange.Resume>(explicit.change)
        val resumed = f.commit(explicit)
        assertIs<CollaborationSpaceInboxHistoryStep.Catalog>(f.next(resumed))
        val demand =
            assertIs<CollaborationSpaceInboxHistoryStep.Change>(
                f.next(parked, f.base.trigger.copy(completedRepairDemand = f.base.other))
            )
        assertIs<CollaborationSpaceInboxHistoryChange.Observe>(demand.change)
    }

    @Test
    fun readyStillRequiresAConditionalCompletionBeforeBecomingIdle() {
        val f = HistoryStepPolicyFixture()
        val ready = f.checkpoint(f.base.batch(f.base.sources(), emptyList()))
        val step = assertIs<CollaborationSpaceInboxHistoryStep.Change>(f.next(ready))
        assertIs<CollaborationSpaceInboxHistoryChange.Complete>(step.change)
        assertEquals(ready, step.expected)
        val completed = f.commit(step)
        assertEquals(CollaborationSpaceInboxHistoryStep.Idle, f.next(completed))
    }

    @Test
    fun replacingTheDestinationPrecedesAnOldQueuedVisit() {
        val f = HistoryStepPolicyFixture()
        val queued = f.checkpoint(f.base.batch(f.base.start(), listOf(f.base.id)))
        val destination = CollaborationSpaceInboxVersion(f.base.other, 1, 1)
        val trigger = f.base.trigger.copy(destinationGeneration = destination.generation)
        val step =
            assertIs<CollaborationSpaceInboxHistoryStep.Change>(
                f.next(queued, trigger, destination)
            )
        val replacement = f.commit(step)
        assertNotEquals(queued.progress.pass?.id, replacement.progress.pass?.id)
        assertEquals(trigger, replacement.progress.pass?.trigger)
        assertIs<CollaborationSpaceInboxHistoryStep.Catalog>(
            f.next(replacement, trigger, destination)
        )
    }

    @Test
    fun ordinaryIndexRevisionsNeverRequestAnotherHistoryPass() {
        val f = HistoryStepPolicyFixture()
        val destination = CollaborationSpaceInboxVersion(f.base.other, 1, 1)
        val trigger = f.base.trigger.copy(destinationGeneration = destination.generation)
        val first =
            f.commit(
                assertIs<CollaborationSpaceInboxHistoryStep.Change>(
                    f.next(null, trigger, destination)
                )
            )
        val advanced = destination.copy(revision = 500, lastOrdinal = 500)
        val next =
            assertIs<CollaborationSpaceInboxHistoryStep.Catalog>(f.next(first, trigger, advanced))
        assertEquals(first, next.expected)
    }

    @Test
    fun invalidInputsAndAnExhaustedChangedCheckpointCannotSelectWork() {
        val f = HistoryStepPolicyFixture()
        val current = f.checkpoint(f.base.start())
        val exhausted =
            current.copy(
                version = current.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER)
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistoryStep(
                        f.base.access,
                        exhausted,
                        f.base.trigger.copy(historySignal = f.base.other),
                        null,
                        f.base.other,
                        false,
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistoryStep(
                        f.base.access.copy(spaceId = "invalid"),
                        current,
                        f.base.trigger,
                        null,
                        f.base.other,
                        false,
                    )
                )
                .failure
                .kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    nextCollaborationSpaceInboxHistoryStep(
                        f.base.access,
                        current,
                        f.base.trigger,
                        CollaborationSpaceInboxVersion(f.base.other, 1, 1),
                        f.base.other,
                        false,
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun aSelectedVisitKeepsAnIndependentSnapshotOfItsRetainedQueue() {
        val f = HistoryStepPolicyFixture()
        val ids = mutableListOf(f.base.id, f.base.other)
        val initial = f.base.start()
        val queued =
            f.checkpoint(
                initial.copy(
                    pass =
                        initial.pass!!.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Catalog(
                                    null,
                                    CollaborationSpaceInboxHistoryBatch(ids, null),
                                )
                        )
                )
            )
        val step = assertIs<CollaborationSpaceInboxHistoryStep.Visit>(f.next(queued))
        ids.clear()
        assertEquals(
            listOf(f.base.id, f.base.other),
            assertIs<CollaborationSpaceInboxHistoryTraversal.Catalog>(
                    step.expected.progress.pass?.traversal
                )
                .batch
                ?.remaining,
        )
    }
}

private class HistoryStepPolicyFixture {
    val base = HistoryGlobalPolicyFixture()

    fun checkpoint(progress: CollaborationSpaceInboxHistoryProgress) =
        CollaborationSpaceInboxHistoryCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(base.other, 4),
            progress,
        )

    fun next(
        expected: CollaborationSpaceInboxHistoryCheckpoint?,
        trigger: CollaborationSpaceInboxHistoryTrigger = base.trigger,
        destination: CollaborationSpaceInboxVersion? = null,
        recover: Boolean = false,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryStep>>(
                nextCollaborationSpaceInboxHistoryStep(
                    base.access,
                    expected,
                    trigger,
                    destination,
                    base.other,
                    recover,
                )
            )
            .value

    fun commit(
        step: CollaborationSpaceInboxHistoryStep.Change
    ): CollaborationSpaceInboxHistoryCheckpoint {
        val proposed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryMutation>>(
                    collaborationSpaceInboxHistoryMutation(base.access, step.expected, step.change)
                )
                .value
        return CollaborationSpaceInboxHistoryCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(
                base.other,
                step.expected?.version?.revision?.plus(1) ?: 0L,
            ),
            proposed.replacement,
        )
    }
}
