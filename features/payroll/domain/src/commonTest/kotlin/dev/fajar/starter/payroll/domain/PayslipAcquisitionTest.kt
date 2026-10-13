@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PayslipAcquisitionTest {
    @Test
    fun currentCompanyYearAndIndependentReadViewsComeFromAdmittedServerFacts() = runTest {
        val f = PayrollFixture { Instant.parse("2025-12-31T18:00:00Z") }
        var year: Int? = null
        f.listCall = { selection ->
            year = selection.year
            AppResult.Success(PayslipPage(emptyList()))
        }
        assertEquals(AppResult.Success(Unit), f.load())
        assertEquals(2026, year)
        val overview = f.snapshot(false)
        assertEquals(AppResult.Success(Unit), f.load(true))
        assertEquals(overview, f.snapshot(false))
        assertEquals(
            f.slip,
            assertNotNull(f.detailRecords.value.let { (it as AppResult.Success).value })
                .value
                .payslip,
        )
        assertEquals(2, f.policyReads)
    }

    @Test
    fun permissionModuleAndSessionDenialsStopBeforeAnyFinancialRead() = runTest {
        for (detail in listOf(false, true)) for (case in 0..2) {
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
            assertIs<AppResult.Failed>(f.load(detail))
            assertEquals(0, f.policyReads + f.listReads + f.detailReads + f.paymentReads + f.writes)
        }
    }

    @Test
    fun invalidDatesCursorsAndIdsCannotReachStorageOrTransport() = runTest {
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
                assertIs<AppResult.Failed>(f.refresh(f.workspace.id, selection)).failure.kind,
            )
        }
        assertIs<AppResult.Failed>(f.refreshDetail(f.workspace.id, "../payments"))
        assertEquals(0, f.policyReads + f.listReads + f.detailReads)
    }

    @Test
    fun changedCompanyOrIdentityCannotPublishLateResults() = runTest {
        for (detail in listOf(false, true)) for (logout in listOf(false, true)) {
            val f = PayrollFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun waitForResponse() {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            f.listCall = {
                waitForResponse()
                AppResult.Success(PayslipPage(listOf(f.summary)))
            }
            f.detailCall = {
                waitForResponse()
                AppResult.Success(f.slip)
            }
            val request = async { f.load(detail) }
            entered.await()
            if (logout) f.sessionState.value = AppResult.Success(null)
            else
                f.workspaceState.value =
                    AppResult.Success(f.workspace.copy(id = "another-selection"))
            release.complete(Unit)
            assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.writes)
            assertEquals(0, f.paymentReads)
        }
    }

    @Test
    fun cancellationRejectsLateSuccessAndFailureWithoutPublishingOrDeleting() = runTest {
        for (detail in listOf(false, true)) for (fails in listOf(false, true)) {
            val f = PayrollFixture()
            assertIs<AppResult.Success<Unit>>(f.load(detail))
            val old = f.snapshot(detail)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun paused() {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            val denial = payslipFailure("mfa_required", FailureKind.AccessDenied)
            f.listCall = {
                paused()
                if (fails) denial else AppResult.Success(PayslipPage(emptyList()))
            }
            f.detailCall = {
                paused()
                if (fails) denial else AppResult.Success(f.slip)
            }
            val request = async { f.load(detail) }
            entered.await()
            request.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(old, f.snapshot(detail))
            assertEquals(1, f.writes)
            assertEquals(0, f.clears)
        }
    }

    @Test
    fun concurrentReadsCannotOverwriteTheNewerPublishedGeneration() = runTest {
        for (detail in listOf(false, true)) {
            val f = PayrollFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            suspend fun pauseFirst() {
                if (++calls == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            f.listCall = {
                pauseFirst()
                AppResult.Success(PayslipPage(listOf(f.summary)))
            }
            f.detailCall = {
                pauseFirst()
                AppResult.Success(f.slip)
            }
            val first = async { f.load(detail) }
            entered.await()
            assertIs<AppResult.Success<Unit>>(f.load(detail))
            val published = f.snapshot(detail)
            release.complete(Unit)
            assertEquals(
                "client.payslip_changed",
                assertIs<AppResult.Failed>(first.await()).failure.code,
            )
            assertEquals(published, f.snapshot(detail))
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun knownDenialSurvivesFailedOrHangingCleanup() = runTest {
        for (detail in listOf(false, true)) for (hang in listOf(false, true)) {
            val f = PayrollFixture()
            f.load(detail)
            val retained = f.snapshot(detail)
            val denied = payslipFailure("mfa_required", FailureKind.AccessDenied)
            f.policyCall = { denied }
            f.failCleanup = !hang
            f.beforeWrite = { deleting -> if (deleting && hang) awaitCancellation() }
            assertEquals(denied, f.load(detail))
            assertEquals(retained, f.snapshot(detail))
        }
    }

    @Test
    fun transientPaymentFailureKeepsThePreviouslyAdmittedAggregate() = runTest {
        val f = PayrollFixture()
        f.load(true)
        val retained = f.snapshot(true)
        f.detailCall = { AppResult.Success(f.slip.copy(companyName = "Changed")) }
        f.paymentCall = { payslipFailure("network", FailureKind.Network) }
        assertEquals(FailureKind.Network, assertIs<AppResult.Failed>(f.load(true)).failure.kind)
        assertEquals(retained, f.snapshot(true))
        assertEquals(1, f.writes)
    }

    @Test
    fun readsAreBoundedAndExpiredLivePolicyCannotPublish() = runTest {
        for (detail in listOf(false, true)) {
            val start = Instant.parse("2026-10-13T00:00:00Z")
            val origin = testScheduler.currentTime
            val f = PayrollFixture { start + (testScheduler.currentTime - origin).milliseconds }
            f.policyCall = { awaitCancellation() }
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(f.load(detail)).failure.kind,
            )
            assertEquals(0, f.writes)
        }
        var now = Instant.parse("2026-10-13T00:00:00Z")
        val f = PayrollFixture { now }
        f.listCall = {
            now += 61.seconds
            AppResult.Success(PayslipPage(listOf(f.summary)))
        }
        assertIs<AppResult.Failed>(f.load())
        assertEquals(0, f.writes)
    }
}
