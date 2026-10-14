@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

class ExpenseAcquisitionTest {
    @Test
    fun companyDateAndOwnedEmploymentSelectLocalRecordsWithoutPeoplePermissions() = runTest {
        val f = ExpenseFixture { Instant.parse("2025-12-31T18:00:00Z") }
        assertEquals(AppResult.Success(Unit), f.load())
        val snapshot =
            assertIs<AppResult.Success<ExpenseSnapshot<ExpenseOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value!!
        assertEquals(YearMonth(2026, 1), snapshot.value.selection.month)
        assertEquals(f.employee.id, snapshot.value.selection.employeeId)
        assertEquals(listOf(f.claim.id), snapshot.value.page.items.map { it.id })
        assertEquals(0, f.listReads)
        assertEquals(1, f.recordReads)
        assertEquals(AppResult.Success(Unit), f.load(true))
        assertEquals(
            snapshot,
            f.observe(f.workspace.id).first().let { (it as AppResult.Success).value },
        )
        assertEquals(2, f.recordReads)
        assertEquals(0, f.historyReads + f.submissionReads)
    }

    @Test
    fun emptyDirectoryDoesNotAcquireAnotherEmployeesClaims() = runTest {
        val f = ExpenseFixture()
        f.directoryCall = { AppResult.Success(f.directory.copy(items = emptyList())) }
        assertEquals(AppResult.Success(Unit), f.load())
        val view =
            assertIs<AppResult.Success<ExpenseSnapshot<ExpenseOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value!!
                .value
        assertNull(view.selection.employeeId)
        assertTrue(view.page.items.isEmpty())
        assertEquals(0, f.listReads + f.recordReads)
    }

    @Test
    fun invalidSelectionAndHistoryCursorCannotReachAnyRemoteRead() = runTest {
        val f = ExpenseFixture()
        for (selection in
            listOf(
                ExpenseSelection(employeeId = "../claims"),
                ExpenseSelection(month = YearMonth(2201, 1)),
                ExpenseSelection(employmentAfter = " "),
                ExpenseSelection(after = "invalid"),
            )) assertIs<AppResult.Failed>(f.observe(f.workspace.id, selection).first())
        assertIs<AppResult.Failed>(f.refreshDetail(f.workspace.id, f.claim.id, -1))
        assertIs<AppResult.Failed>(f.refreshDetail(f.workspace.id, "../history"))
        assertEquals(0, f.policyReads + f.directoryReads + f.claimReads)
    }

    @Test
    fun permissionModuleAndSessionDenialsStopBeforeFinancialReads() = runTest {
        for (detail in listOf(false, true)) for (case in 0..2) {
            val f = ExpenseFixture()
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
                                                        CompanyModule.EXPENSES
                                            )
                                    )
                            )
                        )
                2 -> f.sessionState.value = AppResult.Success(null)
            }
            assertIs<AppResult.Failed>(f.load(detail))
            assertEquals(0, f.policyReads + f.directoryReads + f.claimReads + f.writes)
        }
    }

    @Test
    fun lateResultsCannotCrossAccountCompanyPermissionOrTimezoneChanges() = runTest {
        for (detail in listOf(false, true)) for (case in 0..3) {
            val f = ExpenseFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun awaitResponse() {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            f.directoryCall = {
                awaitResponse()
                AppResult.Success(f.directory)
            }
            f.claimCall = {
                awaitResponse()
                AppResult.Success(f.claim)
            }
            val request = async { f.load(detail) }
            entered.await()
            when (case) {
                0 -> f.sessionState.value = AppResult.Success(null)
                1 ->
                    f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
                2 ->
                    f.workspaceState.value =
                        AppResult.Success(f.workspace.copy(permissions = setOf("expenses.read")))
                3 ->
                    f.workspaceState.value =
                        AppResult.Success(
                            f.workspace.copy(company = f.workspace.company.copy(timezone = "UTC"))
                        )
            }
            release.complete(Unit)
            assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.writes + f.historyReads + f.paymentReads)
        }
    }

    @Test
    fun cancellationDuringPendingAcquisitionRejectsEvenNonCancellableLateResponses() = runTest {
        for (detail in listOf(false, true)) {
            val f = ExpenseFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun awaitResponse() {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            f.directoryCall = {
                awaitResponse()
                AppResult.Success(f.directory)
            }
            f.claimCall = {
                awaitResponse()
                AppResult.Success(f.claim)
            }
            val request = launch { f.load(detail) }
            entered.await()
            request.cancel()
            release.complete(Unit)
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(0, f.writes + f.historyReads + f.paymentReads)
        }
    }

    @Test
    fun changedClaimOrMixedHistoryNeverPublishesACompositeView() = runTest {
        for (case in 0..2) {
            val f = ExpenseFixture()
            when (case) {
                0 -> f.claimCall = { AppResult.Success(f.claim.copy(version = 1)) }
                1 -> f.historyCall = {
                        AppResult.Success(ExpensePage(listOf(f.change.copy(version = 1))))
                    }
                2 -> f.historyCall = { AppResult.Success(ExpensePage(emptyList())) }
            }
            assertEquals(
                "client.expense_changed",
                assertIs<AppResult.Failed>(f.load(true)).failure.code,
            )
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun paymentProgressForAnotherClaimCannotPublishACompositeView() = runTest {
        val f = ExpenseFixture()
        f.paymentCall = { AppResult.Success(ExpenseClaimPayments(f.employee.id, 0, emptyList())) }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(f.load(true)).failure.code,
        )
        assertEquals(0, f.writes)
    }

    @Test
    fun syncAndSingleClaimAcquisitionShareOneBoundedExecutionOwner() = runTest {
        val f = ExpenseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.directoryCall = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.directory)
        }
        val first = async { f.load() }
        entered.await()
        val second = async { f.load(true) }
        runCurrent()
        assertEquals(0, f.recordReads)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(2, f.recordReads)
    }

    @Test
    fun structuredDenialSurvivesFailedOrTimedOutCleanup() = runTest {
        for (detail in listOf(false, true)) for (blocked in listOf(false, true)) {
            val f = ExpenseFixture()
            assertEquals(AppResult.Success(Unit), f.load(detail))
            val old = f.replicas.published.value
            val denied = expenseFailure("mfa_required", FailureKind.AccessDenied)
            f.directoryCall = { denied }
            f.claimCall = { denied }
            f.failCleanup = !blocked
            f.beforeWrite = { clearing -> if (clearing && blocked) awaitCancellation() }
            assertEquals(denied, f.load(detail))
            assertEquals(old, f.replicas.published.value)
            assertEquals(
                denied,
                if (detail) f.observeDetail(f.workspace.id, f.claim.id).first()
                else f.observe(f.workspace.id).first(),
            )
        }
    }

    @Test
    fun hungRequestsTimeoutWithoutAHiddenRetry() = runTest {
        for (detail in listOf(false, true)) {
            val origin = testScheduler.currentTime
            val f = ExpenseFixture {
                Instant.parse("2026-10-13T00:00:00Z") +
                    (testScheduler.currentTime - origin).milliseconds
            }
            f.directoryCall = { awaitCancellation() }
            f.claimCall = { awaitCancellation() }
            if (detail)
                assertEquals(
                    "client.expense_timeout",
                    assertIs<AppResult.Failed>(f.load(true)).failure.code,
                )
            else
                assertEquals(
                    AppResult.Success(ExpenseSyncOutcome(false, 5)),
                    f.sync(f.workspace.id),
                )
            assertEquals(1, f.directoryReads + f.claimReads)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun anUnownedClaimCannotEnterTheReplicaOrAdvanceItsCursor() = runTest {
        val f = ExpenseFixture()
        f.claimCall = {
            AppResult.Success(f.claim.copy(employeeId = "77777777-7777-7777-7777-777777777777"))
        }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(0, f.writes)
        assertNull(f.replicas.current?.cursor)
    }

    @Test
    fun exactDecimalValidationAcceptsTwentyMaximumLinesWithoutFloatingPointRounding() {
        val f = ExpenseFixture()
        assertEquals(10L, expenseMinorUnits(DecimalAmount.parse("0.1")))
        assertNull(expenseMinorUnits(DecimalAmount.parse("-0.01")))
        assertNull(expenseMinorUnits(DecimalAmount.parse("1.001")))
        val max = DecimalAmount.parse("999999999999.99")
        val lines = List(20) { f.line.copy(id = "line-$it", amount = max) }
        val draft = f.draft.copy(total = DecimalAmount.parse("19999999999999.80"), lines = lines)
        val view = ExpenseView(f.claim.copy(draft = draft), null, f.history, null, emptyList())
        assertEquals(AppResult.Success(Unit), validateExpenseView(view))
        assertIs<AppResult.Failed>(
            validateExpenseView(
                view.copy(
                    claim =
                        view.claim.copy(
                            draft = draft.copy(total = DecimalAmount.parse("19999999999999.81"))
                        )
                )
            )
        )
    }
}
