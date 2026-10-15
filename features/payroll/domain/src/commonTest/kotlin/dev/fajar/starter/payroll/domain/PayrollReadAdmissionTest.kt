@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.PayrollSyncOutcome
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class PayrollReadAdmissionTest {
    @Test
    fun confirmedDenialHidesListAndDetailBeforeFailedOrPendingCleanupFinishes() = runTest {
        for (hang in listOf(false, true)) for (code in
            listOf("mfa_required", "sync_scope_changed")) {
            val f = PayrollFixture()
            f.load()
            val old = f.snapshot()
            val list = mutableListOf<AppResult<*>>()
            val details = mutableListOf<AppResult<*>>()
            val first =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe(f.workspace.id).collect { list += it }
                }
            val second =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observeDetail(f.workspace.id, f.summary.id).collect { details += it }
                }
            assertEquals(2, f.observers)
            val denial =
                payslipFailure(
                    code,
                    if (code == "sync_scope_changed") FailureKind.Conflict
                    else FailureKind.AccessDenied,
                )
            f.changesCall = { denial }
            f.replicas.failCleanup = !hang
            f.replicas.beforeWrite = { deleting -> if (deleting && hang) awaitCancellation() }
            val request = async { f.load() }
            runCurrent()
            assertEquals(code, assertIs<AppResult.Failed>(list.last()).failure.code)
            assertEquals(code, assertIs<AppResult.Failed>(details.last()).failure.code)
            assertEquals(0, f.observers)
            assertEquals(denial, request.await())
            assertEquals(old, f.snapshot())
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun aPendingOrFailedRetryCannotReopenFactsAndSuccessfulAcquisitionRebuildsThem() = runTest {
        val f = PayrollFixture()
        f.load()
        val old = f.snapshot()
        f.replicas.failCleanup = true
        val admittedPolicy = f.policyCall
        f.policyCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.load())
        val release = CompletableDeferred<Unit>()
        f.policyCall = {
            release.await()
            payslipFailure("network", FailureKind.Network)
        }
        val pending = async { f.load() }
        runCurrent()
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id).first()).failure.code,
        )
        release.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(old, f.snapshot())
        f.policyCall = admittedPolicy
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertNotEquals(old!!.checkpoint!!.generation, f.snapshot()!!.checkpoint!!.generation)
        assertNull(f.replicas.readAdmission.value.denial)
        assertNotNull(
            assertIs<AppResult.Success<PayslipRevisionRead?>>(
                    f.observeDetail(f.workspace.id, f.summary.id).first()
                )
                .value
        )
    }

    @Test
    fun anOlderAcquisitionCannotClearADenialThatArrivesDuringItsCommit() = runTest {
        val f = PayrollFixture()
        f.load()
        val denied = payslipFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.restrict(f.access, denied.failure)
        val first = f.replicas.readAdmission.value.revision
        f.replicas.beforeAdmit = { f.replicas.restrict(f.access, denied.failure) }
        assertEquals(denied, f.load())
        assertNotEquals(first, f.replicas.readAdmission.value.revision)
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, f.summary.id).first())
                .failure
                .code,
        )
    }

    @Test
    fun exportAccessDenialIsSharedWithListsButAnExpiredOperationDoesNotHideFacts() = runTest {
        val f = PayslipExportFixture()
        f.base.load()
        f.base.replicas.failCleanup = true
        f.inspectCall = {
            payslipFailure("client.authentication_required", FailureKind.Unavailable)
        }
        assertIs<AppResult.Failed>(f.save())
        assertNull(f.base.replicas.readAdmission.value.denial)
        assertNotNull(
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.base.observe(f.base.workspace.id).first()
                )
                .value
        )
        f.inspectCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.save())
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.base.observe(f.base.workspace.id).first()).failure.code,
        )
        assertNotNull(f.base.snapshot())
    }
}
