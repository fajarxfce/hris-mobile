package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryBatchPolicyTest {
    @Test
    fun emptyCatalogContinuationAdvancesWithoutClaimingEnd() {
        val f = HistoryGlobalPolicyFixture()
        val continued = f.batch(f.start(), emptyList(), "next")
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog("next", null),
            continued.pass?.traversal,
        )
        val ended = f.batch(continued, emptyList())
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Sources(null, null, false, false),
            ended.pass?.traversal,
        )
    }

    @Test
    fun queuedIdsAreDetachedAndCursorWaitsForTheLastAcknowledgement() {
        val f = HistoryGlobalPolicyFixture()
        val ids = mutableListOf(f.id, f.other)
        val captured = f.batch(f.start(), ids, "next")
        ids.clear()
        val first = f.ack(captured)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(
                null,
                CollaborationSpaceInboxHistoryBatch(listOf(f.other), "next"),
            ),
            first.pass?.traversal,
        )
        val second = f.ack(first, f.other)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog("next", null),
            second.pass?.traversal,
        )
    }

    @Test
    fun repeatedOutOfOrderOrForeignAcknowledgementsCannotAdvance() {
        val f = HistoryGlobalPolicyFixture()
        val captured = f.batch(f.start(), listOf(f.id, f.other))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    acknowledgeCollaborationSpaceInboxHistorySource(
                        f.access,
                        captured,
                        f.other,
                        null,
                        null,
                    )
                )
                .failure
                .kind,
        )
        val first = f.ack(captured)
        assertIs<AppResult.Failed>(
            acknowledgeCollaborationSpaceInboxHistorySource(f.access, first, f.id, null, null)
        )
        val foreign =
            f.source.copy(
                progress = f.source.progress.copy(owner = f.owner.copy(accountId = f.other))
            )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    acknowledgeCollaborationSpaceInboxHistorySource(
                        f.access,
                        captured,
                        f.id,
                        null,
                        foreign,
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun malformedPagesCannotReplaceAnExistingQueueOrRepeatItsCursor() {
        val f = HistoryGlobalPolicyFixture()
        for (ids in listOf(listOf("invalid"), listOf(f.id, f.id), List(17) { f.id })) assertIs<
            AppResult.Failed
        >(
            captureCollaborationSpaceInboxHistoryBatch(f.start(), ids, null)
        )
        val captured = f.batch(f.start(), listOf(f.id))
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxHistoryBatch(captured, emptyList(), null)
        )
        val continued = f.batch(f.start(), emptyList(), "same")
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxHistoryBatch(continued, emptyList(), "same")
        )
        assertIs<AppResult.Failed>(
            captureCollaborationSpaceInboxHistoryBatch(f.sources(), emptyList(), "not-an-end")
        )
    }

    @Test
    fun failedOnlyRoundParksWithoutSelfScheduling() {
        val f = HistoryGlobalPolicyFixture()
        val captured = f.batch(f.sources(), listOf(f.id, f.other))
        val first = f.ack(captured)
        val second = f.ack(first, f.other)
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, second.pass?.traversal)
        assertEquals(
            AppResult.Success(second),
            observeCollaborationSpaceInboxHistory(second, f.trigger, f.other),
        )
    }

    @Test
    fun unknownPriorPositionCannotDriveAnotherAutomaticRound() {
        val f = HistoryGlobalPolicyFixture()
        val next = f.ack(f.batch(f.sources(), listOf(f.id)), after = f.source)
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, next.pass?.traversal)
    }

    @Test
    fun failedCatalogPreparationSurvivesEmptySourcesUntilExplicitRecovery() {
        val f = HistoryGlobalPolicyFixture()
        val sources = f.ack(f.batch(f.start(), listOf(f.id)))
        assertTrue(assertNotNull(sources.pass).catalogDeferred)
        val parked = f.batch(sources, emptyList())
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, parked.pass?.traversal)
        assertNull(parked.completed)
        assertIs<AppResult.Failed>(completeCollaborationSpaceInboxHistory(parked))
        assertEquals(
            AppResult.Success(parked),
            observeCollaborationSpaceInboxHistory(parked, f.trigger, f.other),
        )
        val resumed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    resumeCollaborationSpaceInboxHistory(parked)
                )
                .value
        val resumedPass = assertNotNull(resumed.pass)
        assertFalse(resumedPass.catalogDeferred)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            resumedPass.traversal,
        )
    }

    @Test
    fun healthySourceProgressCannotEraseEarlierCatalogFailure() {
        val f = HistoryGlobalPolicyFixture()
        val catalog = f.ack(f.batch(f.start(), listOf(f.other, f.id)), f.other)
        val sources = f.ack(catalog, after = f.source)
        val completedSource =
            f.source.copy(
                version = f.source.version.copy(revision = 6),
                progress =
                    f.source.progress.copy(
                        historyComplete = true,
                        pendingWindow =
                            CollaborationSpaceInboxHistoryPendingWindow(f.other, 5, null, true),
                    ),
            )
        val continued =
            f.ack(f.batch(sources, listOf(f.id)), before = f.source, after = completedSource)
        val continuedPass = assertNotNull(continued.pass)
        assertTrue(continuedPass.catalogDeferred)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Sources(null, null, true, false),
            continuedPass.traversal,
        )
        val parked =
            f.ack(
                f.batch(continued, listOf(f.id)),
                before = completedSource,
                after = completedSource,
            )
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, parked.pass?.traversal)
        assertNull(parked.completed)
        val changed = f.trigger.copy(historySignal = f.other)
        val resumed =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    observeCollaborationSpaceInboxHistory(parked, changed, f.other)
                )
                .value
        val resumedPass = assertNotNull(resumed.pass)
        assertFalse(resumedPass.catalogDeferred)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            resumedPass.traversal,
        )
    }

    @Test
    fun rawRestartAloneDoesNotCreateUsefulProgress() {
        val f = HistoryGlobalPolicyFixture()
        val before =
            f.source.copy(
                progress =
                    f.source.progress.copy(
                        historyCursor = CollaborationHistoryChangeCursor.After(f.owner, f.other, 2)
                    )
            )
        val reset = f.source.copy(version = f.source.version.copy(revision = 6))
        val next = f.ack(f.batch(f.sources(), listOf(f.id)), before = before, after = reset)
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, next.pass?.traversal)
    }

    @Test
    fun usefulHandoffAllowsAnotherRoundAfterOtherSourcesWereVisited() {
        val f = HistoryGlobalPolicyFixture()
        val advanced =
            f.source.copy(
                version = f.source.version.copy(revision = 6),
                progress =
                    f.source.progress.copy(
                        historyComplete = true,
                        pendingWindow =
                            CollaborationSpaceInboxHistoryPendingWindow(f.other, 5, null, true),
                    ),
            )
        val first =
            f.ack(f.batch(f.sources(), listOf(f.id, f.other)), before = f.source, after = advanced)
        val queued =
            assertIs<CollaborationSpaceInboxHistoryTraversal.Sources>(first.pass?.traversal)
        assertTrue(queued.advanced)
        assertEquals(listOf(f.other), queued.batch?.remaining)
        val next = f.ack(first, f.other)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Sources(null, null, false, false),
            next.pass?.traversal,
        )
    }

    @Test
    fun aCoalescedLaterTriggerIsConsumedAtMostOnceAfterAFailedRound() {
        val f = HistoryGlobalPolicyFixture()
        val latest = f.trigger.copy(historySignal = f.other)
        val active = f.sources().copy(latest = latest)
        val retry = f.ack(f.batch(active, listOf(f.id)))
        assertEquals(latest, retry.pass?.attemptedTrigger)
        assertEquals(f.trigger, retry.pass?.trigger)
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Catalog(null, null),
            retry.pass?.traversal,
        )
        val sources = f.batch(retry, emptyList())
        val parked = f.ack(f.batch(sources, listOf(f.id)))
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, parked.pass?.traversal)
    }

    @Test
    fun completedCurrentSourcesAllowOnlyAProposedFinalCompletion() {
        val f = HistoryGlobalPolicyFixture()
        val done =
            f.source.copy(
                progress =
                    f.source.progress.copy(
                        historyComplete = true,
                        pendingWindow =
                            CollaborationSpaceInboxHistoryPendingWindow(f.other, 5, null, true),
                    )
            )
        val ready = f.ack(f.batch(f.sources(), listOf(f.id)), before = done, after = done)
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Ready, ready.pass?.traversal)
        assertNull(ready.completed)
        val old = done.copy(progress = done.progress.copy(passId = f.other))
        val parked = f.ack(f.batch(f.sources(), listOf(f.id)), before = old, after = old)
        assertEquals(CollaborationSpaceInboxHistoryTraversal.Parked, parked.pass?.traversal)
    }

    @Test
    fun catalogRestartYieldsToRetainedSourcesWithoutARepeatedRestart() {
        val f = HistoryGlobalPolicyFixture()
        assertIs<AppResult.Failed>(restartCollaborationSpaceInboxHistoryCatalog(f.start()))
        val continued = f.batch(f.start(), emptyList(), "lost")
        val next =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProgress>>(
                    restartCollaborationSpaceInboxHistoryCatalog(continued)
                )
                .value
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Sources(null, null, true, false),
            next.pass?.traversal,
        )
        assertTrue(assertNotNull(next.pass).catalogDeferred)
        assertIs<AppResult.Failed>(restartCollaborationSpaceInboxHistoryCatalog(next))
    }
}
