@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.policies.initialPayrollCheckpoint
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth

class PayrollSyncProtocolTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = PayrollFixture()
        val observation: ObserveCompanyData = f.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = f.workspace.id
        assertEquals(CompanyDataSet.Payroll, observation.dataSet)
        assertEquals(observation.dataSet, acquisition.dataSet)
        val values = mutableListOf<AppResult<CompanyDataStatus>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observation.observeStatus(workspaceId).collect { values += it }
            }
        runCurrent()
        assertEquals(AppResult.Success(CompanyDataStatus(null)), values.last())
        assertTrue(
            assertIs<AppResult.Success<CompanyDataSyncOutcome>>(acquisition.refresh(workspaceId))
                .value
                .caughtUp
        )
        runCurrent()
        val published = requireNotNull(f.replicas.current)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialPayrollCheckpoint(published.access, "status-replacement", published.refreshedAt)
        assertEquals(AppResult.Success(true), f.replicas.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.replicas.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.replicas.published.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.replicas.published.subscriptionCount.value)
    }

    @Test
    fun bootstrapAcquiresAllYearsInBoundedBatchesAndLocalListsHaveIndependentPages() = runTest {
        val f = PayrollFixture()
        f.slips.clear()
        for (index in 1..25) {
            val id = "10000000-0000-0000-0000-" + index.toString().padStart(12, '0')
            val summary =
                f.summary.copy(id = id, month = YearMonth(if (index < 23) 2025 else 2026, 9))
            f.slips[id] = f.slip.copy(summary = summary)
            f.payments[id] = f.payment.copy(payslipId = id)
        }
        var active = 0
        var peak = 0
        f.detailCall = { id ->
            active++
            peak = maxOf(peak, active)
            try {
                delay(1)
                AppResult.Success(f.slips.getValue(id))
            } finally {
                active--
            }
        }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertEquals(4, f.bootstrapReads)
        assertTrue(peak in 1..4)
        assertEquals(0, active)
        val first =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id, PayslipSelection(2025)).first()
                )
                .value!!
        val last =
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id, PayslipSelection(2025, first.value.page.nextCursor))
                        .first()
                )
                .value!!
        assertEquals(20, first.value.page.items.size)
        assertEquals(2, last.value.page.items.size)
        assertNull(last.value.page.nextCursor)
        assertTrue(
            first.value.page.items
                .map { it.id }
                .intersect(last.value.page.items.map { it.id }.toSet())
                .isEmpty()
        )
        assertEquals(25, f.detailReads)
        assertEquals(0, f.listReads)
    }

    @Test
    fun duplicateReferencesHydrateOneAggregateAndAcceptNewerPaymentProgress() = runTest {
        val f = PayrollFixture()
        f.payments[f.summary.id] = f.payment.copy(version = 3)
        f.bootstrapCall = { _, _ ->
            AppResult.Success(
                SyncBootstrapPage(
                    PayrollFixture.collections,
                    listOf(
                        SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 0),
                        SyncReference(SyncCollection.PAYROLL_PAYMENTS, f.summary.id, 1),
                        SyncReference(SyncCollection.PAYROLL_PAYMENTS, f.summary.id, 2),
                    ),
                    null,
                    "changes/0",
                    f.clock.now(),
                )
            )
        }
        f.load()
        assertEquals(1, f.detailReads)
        assertEquals(1, f.paymentReads)
        val entries = f.snapshot()!!.entries
        assertEquals(2, entries.size)
        assertEquals(3L, entries.single { it.record is PayrollRecord.Payments }.reference.version)
    }

    @Test
    fun parentDeletionDoesNotFetchItsPaymentOrAllowAnOrphanToRemainVisible() = runTest {
        val f = PayrollFixture()
        f.load()
        f.changesCall = {
            AppResult.Success(
                f.changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 0),
                            SyncOperation.DELETE,
                        ),
                        f.changedPayment(99),
                    )
                )
            )
        }
        f.load()
        assertEquals(1, f.detailReads)
        assertEquals(1, f.paymentReads)
        assertEquals(AppResult.Success(null), f.observeDetail(f.workspace.id, f.summary.id).first())
        assertTrue(
            assertIs<AppResult.Success<PayslipSnapshot<PayslipOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value!!
                .value
                .page
                .items
                .isEmpty()
        )
    }

    @Test
    fun ordinaryCursorExpiryStagesAReplacementAndKeepsTheOldViewThroughFailure() = runTest {
        val f = PayrollFixture()
        f.load()
        val old = f.snapshot()
        var expire = true
        f.changesCall = {
            if (expire) {
                expire = false
                payslipFailure("sync_cursor_expired", FailureKind.Conflict)
            } else AppResult.Success(f.changePage())
        }
        f.detailCall = { payslipFailure("network", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.load())
        assertEquals(old, f.snapshot())
        assertFalse(f.replicas.current!!.published)
        assertNull(f.replicas.readAdmission.value.denial)
        f.detailCall = { AppResult.Success(f.slip) }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertNotEquals(old!!.checkpoint!!.generation, f.snapshot()!!.checkpoint!!.generation)
        assertEquals(0, f.replicas.clears)
    }

    @Test
    fun incompleteBootstrapsResumeFromTheirStoredCursorWithoutPublishingOrLooping() = runTest {
        val f = PayrollFixture()
        val cursors = mutableListOf<String?>()
        f.bootstrapCall = { cursor, _ ->
            cursors += cursor
            AppResult.Success(
                SyncBootstrapPage(
                    PayrollFixture.collections,
                    emptyList(),
                    "page/${cursors.size}",
                    null,
                    f.clock.now(),
                )
            )
        }
        repeat(2) {
            assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        }
        assertEquals(20, cursors.size)
        assertEquals("page/10", cursors[10])
        assertEquals(1, f.replicas.begins)
        assertNull(f.snapshot())
    }

    @Test
    fun nonAdvancingPagesAndNegativeVersionsCannotPublish() = runTest {
        for (invalidVersion in listOf(false, true)) {
            val f = PayrollFixture()
            f.bootstrapCall = { _, _ ->
                AppResult.Success(
                    SyncBootstrapPage(
                        PayrollFixture.collections,
                        if (invalidVersion)
                            listOf(SyncReference(SyncCollection.PAYSLIPS, f.summary.id, -1))
                        else emptyList(),
                        "same",
                        null,
                        f.clock.now(),
                    )
                )
            }
            assertEquals(
                "client.invalid_response",
                assertIs<AppResult.Failed>(f.load()).failure.code,
            )
            assertTrue(f.bootstrapReads <= 2)
            assertNull(f.snapshot())
            assertEquals(0, f.detailReads)
        }
    }

    @Test
    fun hydrationCannotAdvancePastAnOlderPaymentVersion() = runTest {
        val f = PayrollFixture()
        f.load()
        val saved = f.snapshot()
        f.changesCall = { AppResult.Success(f.changePage(listOf(f.changedPayment(1)))) }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(saved, f.snapshot())
        assertNull(f.replicas.readAdmission.value.denial)
    }
}
