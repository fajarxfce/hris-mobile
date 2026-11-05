package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SyncCollaborationListTest {
    @Test
    fun bootstrapAlwaysCatchesUpThenReentryUsesOnlyTheStoredDeltaCursor() = runTest {
        val f = CollaborationListSyncFixture()
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(true, 0)), f.sync("selection"))
        assertEquals(listOf<String?>(null), f.bootstraps)
        assertEquals(listOf("initial-changes"), f.changes)
        assertEquals(listOf(syncSummary(1)), f.visible.map { it.summary })
        val session = f.setup.session!!
        f.setup.session = session.copy(tokens = session.tokens.copy(accessToken = "rotated-access"))
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(true, 0)), f.sync("selection"))
        assertEquals(1, f.begins)
        assertEquals(listOf("initial-changes", "next-changes"), f.changes)
        assertEquals(1, f.checkpoint!!.local.count)
        assertEquals(0, f.list.remoteCalls)
    }

    @Test
    fun pendingPublicationRetainsVisibleFactsAndDoesNotClearConfirmedDenial() = runTest {
        val f = CollaborationListSyncFixture()
        f.visible = listOf(CollaborationListStoredFact(syncId(2), null, syncSummary(2)))
        f.list.gate.value = CollaborationListAdmission("denied", f.list.denied)
        f.delta = {
            AppResult.Success(
                CollaborationSummaryChanges(emptyList(), "pending", false, true, f.setup.now)
            )
        }
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(false, 5)), f.sync("selection"))
        assertEquals(listOf(syncSummary(2)), f.visible.map { it.summary })
        assertFalse(f.checkpoint!!.published)
        assertEquals(0, f.list.admits)
        assertEquals(f.list.denied, f.list.gate.value.denial)
        f.delta = {
            AppResult.Success(
                CollaborationSummaryChanges(emptyList(), "complete", false, false, f.setup.now)
            )
        }
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(true, 0)), f.sync("selection"))
        assertEquals(1, f.bootstraps.size)
        assertEquals(listOf("initial-changes", "pending"), f.changes)
        assertNull(f.list.gate.value.denial)
        assertEquals(listOf(syncSummary(1)), f.visible.map { it.summary })
    }

    @Test
    fun lostLocalAcknowledgementResumesTheCommittedCursorWithoutDuplicatingBootstrap() = runTest {
        val f = CollaborationListSyncFixture()
        f.afterCommitFailure = f.list.storage
        assertIs<AppResult.Failed>(f.sync("selection"))
        assertEquals(CollaborationListSyncPhase.CATCHUP, f.checkpoint?.sync?.phase)
        assertEquals(1, f.commits)
        f.afterCommitFailure = null
        assertIs<AppResult.Success<*>>(f.sync("selection"))
        assertEquals(1, f.bootstraps.size)
        assertEquals(listOf("initial-changes"), f.changes)
        assertEquals(1, f.checkpoint!!.local.count)
    }

    @Test
    fun expiredCursorAndNewParentReplaceTheRemoteContinuationWithBoundedReset() = runTest {
        val f = CollaborationListSyncFixture()
        assertIs<AppResult.Success<*>>(f.sync("selection"))
        val first = f.checkpoint!!.local.generation
        var expired = true
        f.delta = {
            if (expired) {
                expired = false
                AppResult.Failed(
                    Failure(
                        FailureKind.Conflict,
                        "Expired",
                        code = "collaboration_summary_cursor_expired",
                    )
                )
            } else
                AppResult.Success(
                    CollaborationSummaryChanges(emptyList(), "renewed", false, false, f.setup.now)
                )
        }
        assertIs<AppResult.Success<*>>(f.sync("selection"))
        assertNotEquals(first, f.checkpoint!!.local.generation)
        assertEquals(2, f.begins)
        f.replaceParent(syncId(9101))
        assertIs<AppResult.Success<*>>(f.sync("selection"))
        assertEquals(3, f.begins)
        assertEquals(syncId(9101), f.checkpoint?.sync?.nativeSessionId)
        f.bootstrap = {
            AppResult.Failed(
                Failure(
                    FailureKind.Validation,
                    "Invalid cursor",
                    code = "invalid_collaboration_sync_cursor",
                )
            )
        }
        f.replaceParent(syncId(9102))
        val before = f.bootstraps.size
        assertIs<AppResult.Failed>(f.sync("selection"))
        assertEquals(2, f.bootstraps.size - before)
    }

    @Test
    fun eachRunStopsAtTenPagesWithoutDiscardingItsDurableContinuation() = runTest {
        val f = CollaborationListSyncFixture()
        f.bootstrap = {
            val page = f.bootstraps.size
            AppResult.Success(
                CollaborationSummaryBootstrap(
                    (1..50).map {
                        val id = (page - 1) * 50 + it
                        CollaborationSummaryFact(syncId(id), 0, syncSummary(id))
                    },
                    "page-$page",
                    null,
                    true,
                    f.setup.now,
                )
            )
        }
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(false, 1)), f.sync("selection"))
        assertEquals(10, f.bootstraps.size)
        assertEquals("page-10", f.checkpoint?.sync?.cursor)
        assertEquals(500, f.checkpoint?.local?.count)
        assertTrue(f.visible.isEmpty())
        assertEquals(0, f.list.admits)
    }

    @Test
    fun cancelledOrReplacedOwnerCannotPublishALateSuccessfulResponse() = runTest {
        for (cancel in listOf(false, true)) {
            val f = CollaborationListSyncFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeRemote = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val task = async { f.sync("selection") }
            entered.await()
            if (cancel) task.cancel() else f.setup.session = null
            release.complete(Unit)
            if (cancel) task.join() else assertIs<AppResult.Failed>(task.await())
            assertEquals(0, f.commits)
            assertEquals(0, f.list.admits)
            assertTrue(f.visible.isEmpty())
        }
    }

    @Test
    fun aDenialReceivedWithCancellationStaysGatedEvenWhenPersistenceFails() = runTest {
        val f = CollaborationListSyncFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bootstrap = { AppResult.Failed(f.list.denied) }
        f.list.restrictResult = AppResult.Failed(f.list.storage)
        f.beforeRemote = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val task = async { f.sync("selection") }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertEquals(f.list.denied, f.list.gate.value.denial)
        assertEquals(0, f.commits)
        f.beforeRemote = {}
        f.bootstrap = { AppResult.Failed(f.list.network) }
        assertEquals(f.list.denied, assertIs<AppResult.Failed>(f.sync("selection")).failure)
    }

    @Test
    fun newerDenialDuringCommitCannotBeReopenedByAnOlderAdmittedRead() = runTest {
        val f = CollaborationListSyncFixture()
        f.beforeCommit = {
            if (f.commits == 1)
                f.list.replica.restrict(
                    CollaborationLocalAccess(
                        "local",
                        f.setup.initial.binding.audience,
                        syncId(9000),
                        syncId(9001),
                    ),
                    f.list.denied,
                )
        }
        assertEquals(f.list.denied, assertIs<AppResult.Failed>(f.sync("selection")).failure)
        assertEquals(f.list.denied, f.list.gate.value.denial)
        assertEquals(1, f.list.admits)
    }

    @Test
    fun timeoutAndInteractivePreemptionRetainProgressWithoutClearingAdmission() = runTest {
        val f = CollaborationListSyncFixture()
        f.beforeRemote = { awaitCancellation() }
        assertEquals(AppResult.Success(CollaborationListSyncOutcome(false, 1)), f.sync("selection"))
        assertEquals(0, f.commits)
        assertEquals(0, f.list.admits)
        f.beforeRemote = { f.checkpoint = null }
        assertIs<AppResult.Failed>(f.sync("selection"))
        assertEquals(0, f.commits)
    }
}
