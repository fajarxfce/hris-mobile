package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryProgressPolicyTest {
    @Test
    fun allNullDemandIsCapturedOnceAndCompletedReopenStaysUnchanged() {
        val f = HistoryGlobalPolicyFixture()
        val first = f.start()
        assertTrue(validCollaborationSpaceInboxHistoryProgress(first))
        assertEquals(
            AppResult.Success(first),
            observeCollaborationSpaceInboxHistory(first, f.trigger, f.other),
        )
        val completed = f.finish(first)
        assertEquals(f.pass, completed.completed?.passId)
        assertEquals(f.trigger, completed.completed?.trigger)
        assertEquals(
            AppResult.Success(completed),
            observeCollaborationSpaceInboxHistory(completed, f.trigger, f.other),
        )
    }

    @Test
    fun laterDemandNeverRelabelsTheActiveCapturedPass() {
        val f = HistoryGlobalPolicyFixture()
        val initial = f.start()
        val changed = f.trigger.copy(historySignal = f.other)
        val next =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(initial, changed, f.other)
                )
                .value
        assertEquals(changed, next.latest)
        assertEquals(initial.pass, next.pass)
        val completed = f.finish(next)
        assertEquals(f.trigger, completed.completed?.trigger)
        assertEquals(changed, completed.latest)
        val resumed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(completed, changed, f.other)
                )
                .value
        assertEquals(f.other, resumed.pass?.id)
        assertEquals(changed, resumed.pass?.trigger)
    }

    @Test
    fun aCompletedPassIdCannotBeReusedForLaterDemand() {
        val f = HistoryGlobalPolicyFixture()
        val completed = f.finish(f.start())
        val changed = f.trigger.copy(completedRepairDemand = f.other)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    observeCollaborationSpaceInboxHistory(completed, changed, f.pass)
                )
                .failure
                .kind,
        )
    }

    @Test
    fun destinationReplacementRequiresAFreshPassAndResetsTraversal() {
        val f = HistoryGlobalPolicyFixture()
        val active = f.sources()
        val replaced = f.trigger.copy(destinationGeneration = f.other)
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    observeCollaborationSpaceInboxHistory(active, replaced, f.pass)
                )
                .failure
                .kind,
        )
        val next =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(active, replaced, f.other)
                )
                .value
        assertEquals(f.other, next.pass?.id)
        assertEquals(replaced, next.pass?.trigger)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            next.pass?.traversal,
        )
    }

    @Test
    fun unchangedParkedStateNeedsExplicitOwnerRecovery() {
        val f = HistoryGlobalPolicyFixture()
        val initial = f.start()
        val parked =
            initial.copy(
                pass =
                    initial.pass!!.copy(traversal = CollaborationSpaceInboxHistoryTraversal.Parked)
            )
        assertEquals(
            AppResult.Success(parked),
            observeCollaborationSpaceInboxHistory(parked, f.trigger, f.other),
        )
        val resumed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    resumeCollaborationSpaceInboxHistory(parked)
                )
                .value
        assertEquals(initial, resumed)
        assertEquals(AppResult.Success(resumed), resumeCollaborationSpaceInboxHistory(resumed))
    }

    @Test
    fun realDemandResumesParkedWorkWithoutReplacingItsOriginalInterval() {
        val f = HistoryGlobalPolicyFixture()
        val initial = f.start()
        val parked =
            initial.copy(
                pass =
                    initial.pass!!.copy(traversal = CollaborationSpaceInboxHistoryTraversal.Parked)
            )
        val changed = f.trigger.copy(historySignal = f.other)
        val resumed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(parked, changed, f.other)
                )
                .value
        assertEquals(f.pass, resumed.pass?.id)
        assertEquals(f.trigger, resumed.pass?.trigger)
        assertEquals(changed, resumed.pass?.attemptedTrigger)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            resumed.pass?.traversal,
        )
    }

    @Test
    fun onlyReadyTraversalCanProposeCompletion() {
        val f = HistoryGlobalPolicyFixture()
        for (traversal in
            listOf(
                CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
                CollaborationSpaceInboxHistoryTraversal.Sources(null, null, true, false),
                CollaborationSpaceInboxHistoryTraversal.Parked,
            )) {
            val initial = f.start()
            val active = initial.copy(pass = initial.pass!!.copy(traversal = traversal))
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(completeCollaborationSpaceInboxHistory(active))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun malformedTriggerBatchAndCrossGenerationStateAreRejected() {
        val f = HistoryGlobalPolicyFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    observeCollaborationSpaceInboxHistory(
                        null,
                        f.trigger.copy(historySignal = "invalid"),
                        f.pass,
                    )
                )
                .failure
                .kind,
        )
        val initial = f.start()
        val pass = assertNotNull(initial.pass)
        for (state in
            listOf(
                initial.copy(latest = f.trigger.copy(destinationGeneration = f.other)),
                initial.copy(
                    pass =
                        pass.copy(
                            attemptedTrigger = f.trigger.copy(destinationGeneration = f.other)
                        )
                ),
                initial.copy(
                    pass =
                        pass.copy(
                            traversal = CollaborationSpaceInboxHistoryTraversal.Catalog("", null)
                        )
                ),
                initial.copy(
                    pass =
                        pass.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Catalog(
                                    null,
                                    CollaborationSpaceInboxHistoryBatch(emptyList(), null),
                                )
                        )
                ),
                initial.copy(
                    pass =
                        pass.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Catalog(
                                    null,
                                    CollaborationSpaceInboxHistoryBatch(listOf(f.id, f.id), null),
                                )
                        )
                ),
                initial.copy(pass = null),
                initial.copy(
                    pass =
                        pass.copy(
                            traversal = CollaborationSpaceInboxHistoryTraversal.Ready,
                            catalogDeferred = true,
                        )
                ),
                initial.copy(
                    pass =
                        pass.copy(
                            traversal =
                                CollaborationSpaceInboxHistoryTraversal.Sources(
                                    null,
                                    null,
                                    false,
                                    false,
                                ),
                            catalogDeferred = true,
                        )
                ),
            )) {
            assertFalse(validCollaborationSpaceInboxHistoryProgress(state))
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(resumeCollaborationSpaceInboxHistory(state)).failure.kind,
            )
        }
    }
}

internal class HistoryGlobalPolicyFixture {
    val index = SpaceInboxHistoryAdmissionFixture()
    val access = index.access
    val id = index.id
    val owner = collaborationHistoryChangeOwner(index.base.location)
    val pass = "e6000000-0000-4000-8000-000000000001"
    val other = "e6000000-0000-4000-8000-000000000099"
    val trigger = CollaborationSpaceInboxHistoryTrigger(null, null, null, null)
    val source =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(other, 5),
            CollaborationSpaceInboxHistorySourceProgress(id, owner, null, pass, null, false, null),
        )

    fun start() =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                observeCollaborationSpaceInboxHistory(null, trigger, pass)
            )
            .value

    fun sources(): CollaborationSpaceInboxHistoryProgress {
        val initial = start()
        return initial.copy(
            pass =
                initial.pass!!.copy(
                    traversal =
                        CollaborationSpaceInboxHistoryTraversal.Sources(null, null, false, false)
                )
        )
    }

    fun finish(current: CollaborationSpaceInboxHistoryProgress) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                completeCollaborationSpaceInboxHistory(
                    current.copy(
                        pass =
                            current.pass!!.copy(
                                traversal = CollaborationSpaceInboxHistoryTraversal.Ready
                            )
                    )
                )
            )
            .value

    fun batch(
        current: CollaborationSpaceInboxHistoryProgress,
        ids: List<String>,
        next: String? = null,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                captureCollaborationSpaceInboxHistoryBatch(current, ids, next)
            )
            .value

    fun ack(
        current: CollaborationSpaceInboxHistoryProgress,
        id: String = this.id,
        before: CollaborationSpaceInboxHistorySourceCheckpoint? = null,
        after: CollaborationSpaceInboxHistorySourceCheckpoint? = null,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                acknowledgeCollaborationSpaceInboxHistorySource(access, current, id, before, after)
            )
            .value
}
