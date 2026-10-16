@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.usecases.ObserveOvertimeRequest
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class OvertimeReadAdmissionTest {
    @Test
    fun denialClosesCurrentAndNewReadersWhenFactCleanupFails() = runTest {
        val f = OvertimeFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val retained = f.replicas.published.value
        val overview = mutableListOf<AppResult<OvertimeSnapshot<OvertimeOverview>?>>()
        val detail = mutableListOf<AppResult<OvertimeSnapshot<OvertimeRequestView>?>>()
        val observeRequest =
            ObserveOvertimeRequest(
                f.replicas,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
            )
        val listOwner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id).toList(overview)
            }
        val detailOwner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observeRequest(f.workspace.id, f.selection).toList(detail)
            }
        f.replicas.failCleanup = true
        f.replicas.failAdmissionWrite = true
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.base.policyCall = { denied }
        assertEquals(denied, f.sync(f.workspace.id))
        runCurrent()
        assertEquals(retained, f.replicas.published.value)
        assertEquals(denied, overview.last())
        assertEquals(denied, detail.last())
        assertEquals(denied, f.observe(f.workspace.id).first())
        assertEquals(denied, observeRequest(f.workspace.id, f.selection).first())
        assertEquals(0, f.replicas.directoryObservers)
        assertEquals(0, f.replicas.pageObservers)
        f.base.policyCall = { overtimeFailure("offline", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        runCurrent()
        assertEquals(denied, overview.last())
        listOwner.cancelAndJoin()
        detailOwner.cancelAndJoin()
        assertEquals(0, f.replicas.admissionObservers)
    }

    @Test
    fun deniedDetailRemainsClosedWhenAReplacementReaderOpensOffline() = runTest {
        val f = OvertimeFixture()
        assertEquals(AppResult.Success(Unit), f.load(detail = true))
        val retained = f.detailRecords.value
        f.failCleanup = true
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.detailsCall = { denied }
        assertEquals(denied, f.load(detail = true))
        assertEquals(retained, f.detailRecords.value)
        f.detailsCall = { overtimeFailure("offline", FailureKind.Network) }
        assertEquals(denied, f.observeRequest(f.workspace.id, f.selection).first())
    }

    @Test
    fun olderAcquisitionCannotReopenANewerDenial() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = f.changesCall
        f.changesCall = {
            entered.complete(Unit)
            release.await()
            original(it)
        }
        val pending = async { f.sync(f.workspace.id) }
        entered.await()
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denied.failure)
        release.complete(Unit)
        assertEquals(denied, pending.await())
        assertEquals(denied, f.observe(f.workspace.id).first())
        f.changesCall = original
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertIs<AppResult.Success<*>>(f.observe(f.workspace.id).first())
    }

    @Test
    fun recoveryWithoutADenialDoesNotAcquireOrRewriteFacts() = runTest {
        val f = OvertimeFixture()
        f.base.policyCall = { error("No network recovery is needed") }
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.workspace.id))
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.bootstrapReads)
        assertEquals(0, f.replicas.writes)
    }

    @Test
    fun partialRecoveryKeepsTheProjectionClosed() = runTest {
        val f = OvertimeFixture()
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denied.failure)
        val original = f.bootstrapCall
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    setOf(SyncCollection.OVERTIME_REQUESTS),
                    emptyList(),
                    "page-${f.bootstrapReads}",
                    null,
                    f.base.clock.now(),
                )
            )
        }
        assertEquals(denied, f.sync.recoverAccess(f.workspace.id))
        assertEquals(10, f.bootstrapReads)
        assertEquals(denied, f.observe(f.workspace.id).first())
        f.bootstrapCall = original
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.workspace.id))
        assertIs<AppResult.Success<*>>(f.observe(f.workspace.id).first())
    }

    @Test
    fun cancellationKeepsTheDenialAndReleasesTheAcquisitionGuard() = runTest {
        val f = OvertimeFixture()
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denied.failure)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = f.base.policyCall
        f.base.policyCall = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                original()
            }
        }
        val pending = async { f.sync.recoverAccess(f.workspace.id) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, f.bootstrapReads)
        assertEquals(denied, f.observe(f.workspace.id).first())
        f.base.policyCall = original
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.workspace.id))
    }

    @Test
    fun freshAndLocalIndividualReadsWaitForFullReadmission() = runTest {
        val f = OvertimeFixture()
        f.load(detail = true)
        val denied = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denied.failure)
        val reads = f.detailReads
        assertEquals(denied, f.load(detail = true))
        assertEquals(
            denied,
            f.refreshRequest(f.workspace.id, f.selection, OvertimeRequestAcquisition.LOCAL_FIRST),
        )
        assertEquals(reads, f.detailReads)
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.workspace.id))
        assertEquals(AppResult.Success(Unit), f.load(detail = true))
    }

    @Test
    fun aRejectedCursorCanRecoverWithoutDeletingTheOldFacts() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        val generation = f.replicas.current!!.generation
        f.replicas.failCleanup = true
        val denied = overtimeFailure("sync_scope_changed", FailureKind.Conflict)
        f.changesCall = { cursor ->
            if (cursor == "cursor/0") denied
            else
                AppResult.Success(
                    SyncChangePage(emptyList(), cursor, false, false, 5, f.base.clock.now())
                )
        }
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    setOf(SyncCollection.OVERTIME_REQUESTS),
                    listOf(
                        SyncReference(
                            SyncCollection.OVERTIME_REQUESTS,
                            f.request.id,
                            f.request.version,
                        )
                    ),
                    null,
                    "replacement",
                    f.base.clock.now(),
                )
            )
        }
        assertEquals(denied, f.sync(f.workspace.id))
        assertEquals(denied, f.observe(f.workspace.id).first())
        assertEquals(1, f.bootstrapReads)
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.workspace.id))
        assertEquals(2, f.bootstrapReads)
        assertNotEquals(generation, f.replicas.current!!.generation)
        assertEquals("replacement", f.replicas.current!!.cursor)
        assertIs<AppResult.Success<*>>(f.observe(f.workspace.id).first())
    }
}
