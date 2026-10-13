@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class PayslipObservationTest {
    @Test
    fun staleReadsKeepTheirCollectorUntilDisposal() = runTest {
        for (detail in listOf(false, true)) {
            val origin = testScheduler.currentTime
            val f = PayrollFixture {
                Instant.parse("2026-10-13T00:00:00Z") +
                    (testScheduler.currentTime - origin).milliseconds
            }
            f.load(detail)
            val current = f.clock.now()
            if (detail)
                f.detailRecords.value =
                    AppResult.Success(
                        (f.detailRecords.value as AppResult.Success)
                            .value!!
                            .copy(refreshAfter = current + 10.seconds)
                    )
            else
                f.records.value =
                    AppResult.Success(
                        (f.records.value as AppResult.Success)
                            .value!!
                            .copy(refreshAfter = current + 10.seconds)
                    )
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
            advanceTimeBy(3 * 24 * 60 * 60_000L)
            runCurrent()
            assertIs<AppResult.Success<*>>(seen.last())
            assertEquals(1, seen.size)
            job.cancelAndJoin()
            assertEquals(0, f.observers)
        }
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
    fun unrelatedDetailIsHiddenWhileAnOwnedStaleSlipRemainsReadable() = runTest {
        val f = PayrollFixture()
        f.load(true)
        val snapshot = (f.detailRecords.value as AppResult.Success).value!!
        f.detailRecords.value =
            AppResult.Success(snapshot.copy(refreshAfter = f.clock.now() - 1.seconds))
        assertEquals(
            AppResult.Success(null),
            f.observeDetail(f.workspace.id, "55555555-5555-5555-5555-555555555555").first(),
        )
        assertEquals(f.detailRecords.value, f.observeDetail(f.workspace.id, f.summary.id).first())
    }

    @Test
    fun aRecordFailureCanRecoverOnAValidStorageEmission() = runTest {
        val f = PayrollFixture()
        f.load()
        val valid = f.records.value
        f.records.value = payslipFailure("fixture.storage", FailureKind.Storage)
        val seen = mutableListOf<AppResult<*>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id).collect { seen += it }
            }
        assertIs<AppResult.Failed>(seen.last())
        f.records.value = valid
        runCurrent()
        assertEquals(valid, seen.last())
        job.cancelAndJoin()
        assertEquals(0, f.observers)
    }
}
