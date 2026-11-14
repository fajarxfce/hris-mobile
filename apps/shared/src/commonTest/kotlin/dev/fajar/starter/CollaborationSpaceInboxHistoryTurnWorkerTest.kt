package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceInboxHistoryTurnWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceInboxHistoryTurnWorkerTest {
    @Test
    fun bootstrapRetainsOneStepAndYieldsBeforeReadingTheCatalog() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryTurnWorker.Outcome>>(f.turn())
        assertEquals(listOf("global-read", "global-write"), f.calls)
        f.turn()
        assertEquals(1, f.calls.count { it == "catalog" })
        assertEquals(0, f.calls.count { it == "sources" })
    }

    @Test
    fun completedUnchangedReopenReadsOnlyItsLocalCheckpoint() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.ready()
        f.turn()
        val completed = f.global
        f.calls.clear()
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle),
            f.turn(true),
        )
        assertEquals(listOf("global-read"), f.calls)
        assertEquals(completed, f.global)
    }

    @Test
    fun oneTurnVisitsOnlyTheCapturedHeadAndRunsEachLaneOnce() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued(listOf(f.conversation, f.otherConversation))
        val first = f.source()
        val second = f.source(f.otherConversation)
        f.sources[f.conversation] = first
        f.sources[f.otherConversation] = second
        f.turn()
        assertEquals(1, f.calls.count { it == "history" })
        assertEquals(1, f.calls.count { it == "pending" })
        assertEquals(second, f.sources[f.otherConversation])
        assertEquals(
            listOf(f.otherConversation),
            assertIs<CollaborationSpaceInboxHistoryTraversal.Sources>(
                    f.global?.progress?.pass?.traversal
                )
                .batch
                ?.remaining,
        )
    }

    @Test
    fun failedPreparationRetainsItsDeferredOutcomeAndYieldsToTheNextRoom() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued(listOf(f.conversation, f.otherConversation), catalog = true)
        f.failures["prepare"] = f.failure
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryTurnWorker.Outcome>>(f.turn())
                .value
        assertEquals(
            f.failure,
            assertIs<CollaborationSpaceInboxHistoryTurnWorker.Outcome.Advanced>(result)
                .deferredFailure,
        )
        assertEquals(1, f.calls.count { it == "prepare" })
        val pass = assertNotNull(f.global?.progress?.pass)
        assertTrue(pass.catalogDeferred)
        assertEquals(
            listOf(f.otherConversation),
            assertIs<CollaborationSpaceInboxHistoryTraversal.Catalog>(pass.traversal)
                .batch
                ?.remaining,
        )
        f.failures.clear()
        f.turn()
        assertTrue(assertNotNull(f.sources[f.otherConversation]).progress.historyComplete)
        assertNull(f.sources[f.conversation])
    }

    @Test
    fun pendingFailureCannotEraseHealthyRawProgressOrLoopTheSameLane() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued()
        f.sources[f.conversation] = f.source()
        f.failures["pending"] = f.failure
        f.turn()
        assertTrue(assertNotNull(f.sources[f.conversation]).progress.historyComplete)
        assertIs<CollaborationSpaceInboxHistoryTraversal.Sources>(
            f.global?.progress?.pass?.traversal
        )
        assertEquals(1, f.calls.count { it == "pending" })
        f.turn()
        f.turn()
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Parked,
            f.global?.progress?.pass?.traversal,
        )
        assertEquals(2, f.calls.count { it == "pending" })
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle),
            f.turn(),
        )
    }

    @Test
    fun historyFailureStillGivesPendingReferencesTheirFiniteTurn() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued()
        f.sources[f.conversation] = f.source()
        f.failures["history"] = f.failure
        f.turn()
        val current = assertNotNull(f.sources[f.conversation])
        assertFalse(current.progress.historyComplete)
        assertTrue(assertNotNull(current.progress.pendingWindow).complete)
        assertEquals(1, f.calls.count { it == "history" })
        assertEquals(1, f.calls.count { it == "pending-complete" })
    }

    @Test
    fun aLostHistoryCommitIsInspectedWithoutRepublishingAndPendingUsesItsNewVersion() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued()
        f.sources[f.conversation] = f.source()
        f.lostReplies["history-retain"] = f.failure
        f.turn()
        val source = assertNotNull(f.sources[f.conversation])
        assertTrue(source.progress.historyComplete)
        assertTrue(assertNotNull(source.progress.pendingWindow).complete)
        assertEquals(2, f.calls.count { it == "source-read" })
        assertEquals(1, f.calls.count { it == "publish" })
        assertEquals(1, f.calls.count { it == "history-retain" })
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Ready,
            f.global?.progress?.pass?.traversal,
        )
    }

    @Test
    fun olderIntervalsFinishBeforeTheCurrentPassIsPreparedOnAnotherTurn() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued()
        val oldPass = f.id(80)
        f.sources[f.conversation] = f.source(pass = oldPass)
        f.turn()
        assertEquals(oldPass, f.sources[f.conversation]?.progress?.passId)
        assertEquals(0, f.calls.count { it == "prepare" })
        f.turn()
        f.turn()
        assertEquals(f.passId, f.sources[f.conversation]?.progress?.passId)
        assertEquals(1, f.calls.count { it == "prepare" })
    }

    @Test
    fun aRawRestartGetsOneFreshReadAndCannotRestartForever() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued()
        val initial = f.source()
        f.sources[f.conversation] =
            initial.copy(
                progress =
                    initial.progress.copy(
                        historyCursor =
                            CollaborationHistoryChangeCursor.After(
                                initial.progress.owner,
                                f.id(60),
                                2,
                            )
                    )
            )
        f.historyResponse = {
            CollaborationSpaceHistoryChangesRead(
                it.progress.owner,
                CollaborationHistoryChanges.Restart,
            )
        }
        f.turn()
        assertEquals(2, f.calls.count { it == "history" })
        assertEquals(1, f.calls.count { it == "restart" })
        assertEquals(1, f.calls.count { it == "pending" })
        f.turn()
        f.turn()
        assertEquals(
            CollaborationSpaceInboxHistoryTraversal.Parked,
            f.global?.progress?.pass?.traversal,
        )
        assertEquals(1, f.calls.count { it == "restart" })
    }

    @Test
    fun aSourceReadFailureRetainsAnExplicitDeferredAcknowledgement() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.queued(catalog = true)
        f.failures["source-read"] = f.failure
        f.turn()
        assertEquals(1, f.calls.count { it == "source-read" })
        assertEquals(0, f.calls.count { it == "prepare" })
        assertTrue(assertNotNull(f.global?.progress?.pass).catalogDeferred)
        val change =
            assertIs<CollaborationSpaceInboxHistoryChange.AcknowledgeSource>(f.changes.single())
        assertNull(change.before)
        assertNull(change.after)
    }

    @Test
    fun denialAtEachSourceBoundaryStopsBeforeGlobalAcknowledgement() = runTest {
        for (boundary in
            listOf(
                "source-read",
                "history",
                "publish",
                "history-retain",
                "pending",
                "pending-complete",
            )) {
            val f = SpaceInboxHistoryTurnFixture()
            f.queued()
            f.sources[f.conversation] = f.source()
            val initial = f.global
            f.failures[boundary] = f.denied
            assertEquals(AppResult.Failed(f.denied), f.turn())
            assertEquals(initial, f.global)
            assertEquals(0, f.calls.count { it == "global-write" })
        }
    }

    @Test
    fun cancellationAtEachAwaitedBoundaryCannotAdvanceTheGlobalQueue() = runTest {
        for (boundary in
            listOf(
                "global-read",
                "source-read",
                "history",
                "publish",
                "history-retain",
                "pending",
                "pending-complete",
                "global-write",
            )) {
            val f = SpaceInboxHistoryTurnFixture()
            f.queued()
            f.sources[f.conversation] = f.source()
            val initial = f.global
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val job = launch { f.turn() }
            entered.await()
            job.cancelAndJoin()
            assertEquals(initial, f.global)
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun destinationConflictAtFinalCompletionIsNotRetriedOrCalledComplete() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.ready()
        val initial = f.global
        f.before = {
            if (it == "global-write") f.destination = CollaborationSpaceInboxVersion(f.id(90), 0, 1)
        }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.turn()).failure.kind)
        assertEquals(initial, f.global)
        assertEquals(1, f.calls.count { it == "global-read" })
        assertEquals(1, f.calls.count { it == "global-write" })
    }

    @Test
    fun aLostGlobalCompletionResponseReopensTheCommittedStateWithoutAnotherWrite() = runTest {
        val f = SpaceInboxHistoryTurnFixture()
        f.ready()
        f.lostReplies["global-write"] = f.failure
        assertEquals(AppResult.Failed(f.failure), f.turn())
        assertNotNull(f.global?.progress?.completed)
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle),
            f.turn(),
        )
        assertEquals(1, f.calls.count { it == "global-write" })
    }
}
