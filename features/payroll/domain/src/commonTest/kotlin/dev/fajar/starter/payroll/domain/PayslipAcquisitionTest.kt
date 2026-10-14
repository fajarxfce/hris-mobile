@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class PayslipAcquisitionTest {
    @Test
    fun fullSlipAndPaymentFactsPublishTogetherAndYearSelectionUsesLocalCompanyTime() = runTest {
        val f = PayrollFixture { Instant.parse("2025-12-31T18:00:00Z") }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        val list =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value!!
        assertEquals(2026, list.value.selection.year)
        assertEquals(listOf(f.summary), list.value.page.items)
        assertEquals(
            f.slip,
            assertIs<AppResult.Success<PayslipSnapshot<PayslipView>?>>(
                    f.observeDetail(f.workspace.id, f.summary.id).first()
                )
                .value!!
                .value
                .payslip,
        )
        assertEquals(1, f.detailReads)
        assertEquals(1, f.paymentReads)
        assertEquals(0, f.listReads)
        f.load()
        assertEquals(1, f.detailReads)
        assertEquals(2, f.policyReads)
    }

    @Test
    fun permissionModuleAndSessionDenialsStopBeforeAnyFinancialRead() = runTest {
        for (case in 0..2) {
            val f = PayrollFixture()
            when (case) {
                0 ->
                    f.workspaceState.value =
                        AppResult.Success(f.workspace.copy(permissions = emptySet()))
                1 ->
                    f.workspaceState.value =
                        AppResult.Success(
                            f.workspace.copy(
                                policy =
                                    f.workspace.policy!!.copy(
                                        policy =
                                            f.policy.copy(
                                                enabledModules =
                                                    CompanyModule.entries.toSet() -
                                                        CompanyModule.PAYROLL
                                            )
                                    )
                            )
                        )
                2 -> f.sessionState.value = AppResult.Success(null)
            }
            assertIs<AppResult.Failed>(f.load())
            assertEquals(
                0,
                f.policyReads +
                    f.bootstrapReads +
                    f.detailReads +
                    f.paymentReads +
                    f.replicas.writes,
            )
        }
    }

    @Test
    fun invalidLocalSelectionsCannotReachStorageOrTransport() = runTest {
        val f = PayrollFixture()
        for (selection in
            listOf(
                PayslipSelection(2023),
                PayslipSelection(2101),
                PayslipSelection(after = "2026-09:${f.summary.id}"),
                PayslipSelection(2026, "2025-09:${f.summary.id}"),
                PayslipSelection(2026, "2026-13:${f.summary.id}"),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe(f.workspace.id, selection).first())
                    .failure
                    .kind,
            )
        }
        assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, "../payments").first())
        assertEquals(0, f.observers + f.policyReads + f.bootstrapReads + f.detailReads)
    }

    @Test
    fun changedCompanyOrIdentityRejectsLateSuccessAndFailureBeforeTheNextRead() = runTest {
        for (logout in listOf(false, true)) for (fails in listOf(false, true)) {
            val f = PayrollFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.detailCall = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (fails) payslipFailure("mfa_required", FailureKind.AccessDenied)
                else AppResult.Success(f.slip)
            }
            val request = async { f.load() }
            entered.await()
            if (logout) f.sessionState.value = AppResult.Success(null)
            else
                f.workspaceState.value =
                    AppResult.Success(f.workspace.copy(id = "another-selection"))
            release.complete(Unit)
            assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.replicas.writes + f.replicas.clears + f.paymentReads)
            assertNull(f.replicas.readAdmission.value.denial)
        }
    }

    @Test
    fun cancellationRejectsLatePaymentSuccessAndFailureWithoutPublishingOrDeleting() = runTest {
        for (fails in listOf(false, true)) {
            val f = PayrollFixture()
            f.load()
            val old = f.snapshot()
            val writes = f.replicas.writes
            f.changesCall = { AppResult.Success(f.changePage(listOf(f.changedPayment(1)))) }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.paymentCall = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (fails) payslipFailure("mfa_required", FailureKind.AccessDenied)
                else AppResult.Success(f.payment.copy(version = 1))
            }
            val request = async { f.load() }
            entered.await()
            request.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(old, f.snapshot())
            assertEquals(writes, f.replicas.writes)
            assertEquals(0, f.replicas.clears)
            assertNull(f.replicas.readAdmission.value.denial)
        }
    }

    @Test
    fun concurrentUiAndWorkerTriggersShareTheAcquisitionOwner() = runTest {
        val f = PayrollFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.detailCall = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.slip)
        }
        val first = async { f.load() }
        entered.await()
        val second = async { f.load() }
        runCurrent()
        assertEquals(1, f.policyReads)
        release.complete(Unit)
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(first.await()).value.caughtUp)
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(second.await()).value.caughtUp)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.detailReads)
        assertEquals(1, f.paymentReads)
        assertEquals(2, f.changeReads)
    }

    @Test
    fun transientPaymentFailureKeepsThePublishedFactsAndCursorForRetry() = runTest {
        val f = PayrollFixture()
        f.load()
        val old = f.snapshot()
        val checkpoint = f.replicas.current
        f.changesCall = { AppResult.Success(f.changePage(listOf(f.changedPayment(2)))) }
        f.paymentCall = { payslipFailure("network", FailureKind.Network) }
        assertEquals(FailureKind.Network, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(old, f.snapshot())
        assertEquals(checkpoint, f.replicas.current)
        assertNull(f.replicas.readAdmission.value.denial)
        f.paymentCall = { AppResult.Success(f.payment.copy(version = 2)) }
        f.load()
        assertEquals(
            2L,
            (f.snapshot()!!.entries.single { it.record is PayrollRecord.Payments }.record
                    as PayrollRecord.Payments)
                .version,
        )
        assertEquals(1, f.detailReads)
    }

    @Test
    fun deadlinesIncludeWaitingForTheLockAndDoNotPublishUnderAnExpiredLivePolicy() = runTest {
        val start = Instant.parse("2026-10-13T00:00:00Z")
        val f = PayrollFixture { start + testScheduler.currentTime.milliseconds }
        val entered = CompletableDeferred<Unit>()
        f.policyCall = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val first = async { f.load() }
        entered.await()
        val second = async { f.load() }
        assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(second.await()).value.caughtUp)
        assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(first.await()).value.caughtUp)
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(0, f.replicas.writes)
        var now = start
        val expired = PayrollFixture { now }
        expired.detailCall = {
            now += 61.seconds
            AppResult.Success(expired.slip)
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(expired.load()).failure.code,
        )
        assertEquals(0, expired.replicas.writes + expired.paymentReads)
    }

    @Test
    fun aReadFailureCancelsSiblingHydrationInsteadOfWaitingForItsTimeout() = runTest {
        val f = PayrollFixture()
        val other = f.summary.copy(id = "55555555-5555-5555-5555-555555555555")
        f.slips[other.id] = f.slip.copy(summary = other)
        var cancelled = false
        f.detailCall = { id ->
            if (id == f.summary.id) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            } else payslipFailure("mfa_required", FailureKind.AccessDenied)
        }
        assertEquals("mfa_required", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertTrue(cancelled)
        assertEquals(0, f.replicas.writes)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals("mfa_required", f.replicas.readAdmission.value.denial?.code)
    }
}
