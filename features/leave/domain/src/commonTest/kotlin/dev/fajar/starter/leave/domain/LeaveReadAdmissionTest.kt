@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.usecases.ObserveLeaveRequest
import dev.fajar.starter.sync.domain.entities.SyncBootstrapPage
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class LeaveReadAdmissionTest {
    @Test
    fun denialClosesListAndDetailEvenWhenStorageAndCleanupFail() = runTest {
        val f = LeaveSyncFixture()
        assertIs<AppResult.Success<*>>(f.run())
        val facts = f.replica.published.value
        val overview = mutableListOf<AppResult<LeaveOverview?>>()
        val detail = mutableListOf<AppResult<LeaveRequestSnapshot?>>()
        val observeRequest =
            ObserveLeaveRequest(
                f.replica,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
            )
        val listOwner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.base.observe(f.base.workspace.id).toList(overview)
            }
        val detailOwner =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observeRequest(f.base.workspace.id, f.base.request.id).toList(detail)
            }
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.base.policyCall = { denied }
        f.replica.rejectWrite = true
        f.replica.failAdmissionWrite = true
        assertSame(denied, f.run())
        runCurrent()
        assertEquals(facts, f.replica.published.value)
        assertEquals(denied, overview.last())
        assertEquals(denied, detail.last())
        assertEquals(0, f.replica.directoryObservers)
        assertEquals(0, f.replica.pageObservers)
        f.base.policyCall = { leaveFailure("offline", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.run())
        runCurrent()
        assertEquals(denied, overview.last())
        assertEquals(denied, detail.last())
        assertEquals(denied, f.base.observe(f.base.workspace.id).first())
        listOwner.cancelAndJoin()
        detailOwner.cancelAndJoin()
        assertEquals(0, f.replica.admissionObservers)
    }

    @Test
    fun pendingOlderAcquisitionCannotReopenANewerDenial() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = f.changesCall
        f.changesCall = {
            entered.complete(Unit)
            release.await()
            original(it)
        }
        val pending = async { f.run() }
        entered.await()
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.replica.restrict(f.base.readAccess(), denied.failure)
        release.complete(Unit)
        assertEquals(denied, pending.await())
        assertEquals(denied, f.base.observe(f.base.workspace.id).first())
        f.changesCall = original
        assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run())
        assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
    }

    @Test
    fun recoveryWithoutADenialDoesNotAcquireOrRewriteData() = runTest {
        val f = LeaveSyncFixture()
        f.base.policyCall = { error("No recovery is needed") }
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.base.workspace.id))
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.bootstrapReads)
        assertEquals(0, f.replica.writes)
    }

    @Test
    fun partialRecoveryRemainsGatedUntilPublicationCompletes() = runTest {
        val f = LeaveSyncFixture()
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.replica.restrict(f.base.readAccess(), denied.failure)
        val original = f.bootstrapCall
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    f.collections,
                    emptyList(),
                    "page-${f.bootstrapReads}",
                    null,
                    f.base.clock.now(),
                )
            )
        }
        assertEquals(denied, f.sync.recoverAccess(f.base.workspace.id))
        assertEquals(10, f.bootstrapReads)
        assertEquals(denied, f.base.observe(f.base.workspace.id).first())
        f.bootstrapCall = original
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.base.workspace.id))
        assertIs<AppResult.Success<*>>(f.base.observe(f.base.workspace.id).first())
    }

    @Test
    fun cancelledRecoveryKeepsItsDenialAndReleasesTheExecutionGuard() = runTest {
        val f = LeaveSyncFixture()
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.replica.restrict(f.base.readAccess(), denied.failure)
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
        val pending = async { f.sync.recoverAccess(f.base.workspace.id) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, f.bootstrapReads)
        assertEquals(denied, f.base.observe(f.base.workspace.id).first())
        f.base.policyCall = original
        assertEquals(AppResult.Success(Unit), f.sync.recoverAccess(f.base.workspace.id))
    }

    @Test
    fun aKnownDenialBlocksLocalAndFreshIndividualReadsUntilFullRecovery() = runTest {
        val f = LeaveRequestFixture()
        f.load()
        val facts = f.records.value
        f.cleanupFails = true
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.requestCall = { denied }
        assertEquals(denied, f.load())
        assertEquals(facts, f.records.value)
        assertEquals(denied, f.observe(f.base.workspace.id, f.details.id).first())
        f.requestCall = { AppResult.Success(f.details) }
        val reads = f.reads
        assertEquals(
            denied,
            f.refresh(f.base.workspace.id, f.selection, LeaveRequestAcquisition.LOCAL_FIRST),
        )
        assertEquals(denied, f.load())
        assertEquals(reads, f.reads)
        assertEquals(
            AppResult.Success(Unit),
            LeaveSyncFixture(f.base).sync.recoverAccess(f.base.workspace.id),
        )
        assertEquals(AppResult.Success(Unit), f.load())
    }

    @Test
    fun lateDetailAndTypeResponsesCannotPublishAfterAnotherReadIsDenied() = runTest {
        val f = LeaveRequestFixture()
        val prepared = LeavePreparationFixture(f.base)
        val release = CompletableDeferred<Unit>()
        f.requestCall = {
            release.await()
            AppResult.Success(f.details)
        }
        prepared.typesCall = {
            release.await()
            AppResult.Success(LeavePage(listOf(prepared.type)))
        }
        val request = async { f.load() }
        val types = async {
            prepared.types(f.base.workspace.id, LeaveTypeSelection(f.base.directory.asOf))
        }
        runCurrent()
        assertEquals(1, f.reads)
        assertEquals(1, prepared.typeReads)
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.base.replica.restrict(f.base.readAccess(), denied.failure)
        release.complete(Unit)
        assertEquals(denied, request.await())
        assertEquals(denied, types.await())
        assertEquals(0, f.writes)
    }

    @Test
    fun deniedPreparationAlsoGatesRetainedSubmissionAndPreventsNewCommands() = runTest {
        val f = LeaveSubmissionFixture()
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.preparation.previewCall = { denied }
        assertEquals(denied, f.preparation.preview(f.base.workspace.id, f.preparation.selected))
        assertEquals(denied, f.observe(f.base.workspace.id, f.base.employee.id).first())
        assertEquals(denied, f.prepare(f.base.workspace.id, f.prepared, "Appointment"))
        assertEquals(
            denied,
            f.preparation.types(f.base.workspace.id, LeaveTypeSelection(f.base.directory.asOf)),
        )
        assertEquals(0, f.preparation.typeReads)
    }
}

private fun LeaveFixture.readAccess() =
    (admitLeave(workspace.id, workspace, session, build, clock.now()) as AppResult.Success).value
