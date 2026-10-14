@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.YearMonth

class PayslipObservationTest {
    @Test
    fun anotherModuleGrantDoesNotInvalidateOwnedPayrollFacts() = runTest {
        val f = PayrollFixture()
        f.load()
        val saved = f.snapshot()
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(permissions = setOf("payroll.read", "expenses.self.manage"))
            )
        val view =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipView>?>>(
                    f.observeDetail(f.workspace.id, f.summary.id).first()
                )
                .value!!
        assertEquals(f.slip, view.value.payslip)
        assertEquals(saved, f.snapshot())
        assertEquals(1, f.detailReads)
    }

    @Test
    fun oldFactsRemainReadableWithoutTimersNetworkOrRenewedTimestamps() = runTest {
        val f = PayrollFixture {
            Instant.parse("2026-10-13T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        f.load()
        val stored = f.snapshot()
        for (detail in listOf(false, true)) {
            val seen = mutableListOf<AppResult<*>>()
            val source =
                if (detail) f.observeDetail(f.workspace.id, f.summary.id)
                else f.observe(f.workspace.id)
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    source.collect { seen += it }
                }
            assertIs<AppResult.Success<*>>(seen.last())
            assertEquals(1, f.observers)
            advanceTimeBy(73 * 60 * 60_000L)
            runCurrent()
            assertEquals(1, seen.size)
            assertEquals(stored, f.snapshot())
            assertEquals(1, f.detailReads)
            job.cancelAndJoin()
            assertEquals(0, f.observers)
            assertEquals(0, f.replicas.admissionObservers)
        }
        assertNotNull(
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value
        )
    }

    @Test
    fun sessionAndPermissionChangesStopTheOldSubscription() = runTest {
        for (logout in listOf(false, true)) {
            val f = PayrollFixture()
            f.load()
            val seen = mutableListOf<AppResult<*>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe(f.workspace.id).collect { seen += it }
                }
            if (logout) f.sessionState.value = AppResult.Success(null)
            else
                f.workspaceState.value =
                    AppResult.Success(f.workspace.copy(permissions = emptySet()))
            runCurrent()
            assertIs<AppResult.Failed>(seen.last())
            assertEquals(0, f.observers)
            job.cancelAndJoin()
        }
    }

    @Test
    fun severalYearsAndIndividualDetailsRemainQueryableAfterTheLastOpenedPageChanges() = runTest {
        val f = PayrollFixture()
        val older =
            f.slip.copy(
                summary =
                    f.summary.copy(
                        id = "55555555-5555-5555-5555-555555555555",
                        month = YearMonth(2025, 8),
                    )
            )
        f.slips[older.summary.id] = older
        f.payments[older.summary.id] = f.payment.copy(payslipId = older.summary.id)
        f.load()
        for (year in listOf(2025, 2026, 2024, 2025)) {
            val view =
                assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                        f.observe(f.workspace.id, PayslipSelection(year)).first()
                    )
                    .value!!
            assertTrue(view.value.page.items.all { it.month.year == year })
            assertEquals(if (year == 2024) 0 else 1, view.value.page.items.size)
        }
        for (slip in listOf(older, f.slip, older)) {
            val view =
                assertIs<AppResult.Success<PayslipSnapshot<PayslipView>?>>(
                        f.observeDetail(f.workspace.id, slip.summary.id).first()
                    )
                    .value!!
            assertEquals(slip, view.value.payslip)
        }
        assertEquals(
            AppResult.Success(null),
            f.observeDetail(f.workspace.id, "66666666-6666-6666-6666-666666666666").first(),
        )
        assertEquals(2, f.detailReads)
        assertEquals(0, f.listReads)
    }

    @Test
    fun aNewLoginForTheSameAccountRetainsFactsWithoutReusingTheOldRequestIdentity() = runTest {
        val f = PayrollFixture()
        f.load()
        val retained = f.snapshot()
        val session =
            f.session.copy(
                id = "new-login",
                binding = f.session.binding.copy(expiresAtEpochMillis = 0),
            )
        f.sessionState.value = AppResult.Success(session)
        f.workspaceState.value = AppResult.Success(f.workspace.copy(sessionId = session.id))
        val view =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipView>?>>(
                    f.observeDetail(f.workspace.id, f.summary.id).first()
                )
                .value!!
        assertEquals(f.slip, view.value.payslip)
        assertEquals(session.id, view.access.sessionId)
        assertEquals(retained, f.snapshot())
        assertEquals(1, f.detailReads)
    }

    @Test
    fun aRecordFailureCanRecoverOnAValidStorageEmission() = runTest {
        val f = PayrollFixture()
        f.load()
        val valid = f.replicas.published.value
        f.replicas.published.value = payslipFailure("fixture.storage", FailureKind.Storage)
        val seen = mutableListOf<AppResult<*>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id).collect { seen += it }
            }
        assertIs<AppResult.Failed>(seen.last())
        f.replicas.published.value = valid
        runCurrent()
        assertNotNull(assertIs<AppResult.Success<*>>(seen.last()).value)
        job.cancelAndJoin()
        assertEquals(0, f.observers)
    }

    @Test
    fun upgradeRetainsOnlyMatchingLegacyViewsUntilAnEmptyReplicaBecomesAuthoritative() = runTest {
        val f = PayrollFixture()
        val saved =
            PayslipSnapshot(
                f.access,
                "legacy",
                PayslipOverview(PayslipSelection(2025), PayslipPage(emptyList())),
                f.clock.now() - 2.days,
                f.clock.now() - 2.days + 1.hours,
            )
        f.records.value = AppResult.Success(saved)
        assertEquals(AppResult.Success(saved), f.observe(f.workspace.id).first())
        assertEquals(
            AppResult.Success(saved),
            f.observe(f.workspace.id, PayslipSelection(2025)).first(),
        )
        assertEquals(
            AppResult.Success(null),
            f.observe(f.workspace.id, PayslipSelection(2026)).first(),
        )
        f.slips.clear()
        f.load()
        val fresh =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id, PayslipSelection(2025)).first()
                )
                .value!!
        assertNotEquals(saved.generation, fresh.generation)
        assertTrue(fresh.value.page.items.isEmpty())
    }
}
