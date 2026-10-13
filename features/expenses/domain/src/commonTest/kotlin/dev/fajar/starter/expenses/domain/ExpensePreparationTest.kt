@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.decimal.DecimalAmount
import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class ExpensePreparationTest {
    @Test
    fun newAndEditableDraftsKeepTheOwnedEmploymentCompanyDateAndObservedVersion() = runTest {
        val p = ExpensePreparationFixture { Instant.parse("2025-12-31T18:00:00Z") }
        val f = p.f
        val fresh =
            assertIs<AppResult.Success<ExpenseDraftPreparation>>(
                    p.draft(f.workspace.id, f.employee.id)
                )
                .value
        assertEquals(LocalDate(2026, 1, 1), fresh.asOf)
        assertEquals(f.employee, fresh.employee)
        assertNull(fresh.claim)
        assertEquals(0, f.claimReads)
        for (status in listOf(ExpenseStatus.DRAFT, ExpenseStatus.RETURNED)) {
            val current = f.claim.copy(status = status, version = 4)
            f.claimCall = { AppResult.Success(current) }
            val edited =
                assertIs<AppResult.Success<ExpenseDraftPreparation>>(
                        p.load(ExpensePreparationKind.DRAFT)
                    )
                    .value
            assertEquals(current, edited.claim)
            assertEquals(f.clock.now() + 60.seconds, edited.expiresAt)
        }
        assertEquals(0, f.writes)
    }

    @Test
    fun policyAndCostCenterQueriesPreserveDatesCursorsAndNormalizeSearch() = runTest {
        val p = ExpensePreparationFixture()
        val categories =
            assertIs<AppResult.Success<ExpenseCategories>>(
                    p.load(ExpensePreparationKind.CATEGORIES)
                )
                .value
        assertEquals(p.f.directory.asOf to "PREVIOUS", p.categoryQuery)
        assertEquals(listOf(p.category), categories.page.items)
        assertEquals(1L, categories.page.items.single().appliedRevision)
        val centers =
            assertIs<AppResult.Success<ExpenseCostCenters>>(
                    p.load(ExpensePreparationKind.COST_CENTERS)
                )
                .value
        assertEquals("Operations" to "COST_CENTER:CC00", p.centerQuery)
        assertEquals(listOf(p.center), centers.page.items)
        assertEquals("Operations", centers.query)
        assertEquals(p.f.clock.now() + 60.seconds, centers.expiresAt)
    }

    @Test
    fun readOnlyPermissionCannotOpenEditingEvenWhenClaimReadsAreAllowed() = runTest {
        for (kind in ExpensePreparationKind.entries) {
            val p = ExpensePreparationFixture()
            p.f.workspaceState.value =
                AppResult.Success(p.f.workspace.copy(permissions = setOf("expenses.read")))
            assertEquals(
                FailureKind.AccessDenied,
                assertIs<AppResult.Failed>(p.load(kind)).failure.kind,
            )
            assertEquals(0, p.reads + p.f.policyReads + p.f.claimReads)
        }
    }

    @Test
    fun invalidIdsDatesAndCursorsStopBeforePolicyOrPayloadAcquisition() = runTest {
        val p = ExpensePreparationFixture()
        val f = p.f
        listOf(
                p.draft(f.workspace.id, "../employees"),
                p.draft(f.workspace.id, f.employee.id, "../claims"),
                p.draft(f.workspace.id, f.employee.id, employmentAfter = " "),
                p.categories(f.workspace.id, LocalDate(2201, 1, 1)),
                p.categories(f.workspace.id, f.directory.asOf, "with-dashes"),
                p.centers(f.workspace.id, "x".repeat(121)),
                p.centers(f.workspace.id, "", "DEPARTMENT:CC01"),
            )
            .forEach { assertIs<AppResult.Failed>(it) }
        assertEquals(0, p.reads + f.policyReads + f.claimReads)
    }

    @Test
    fun foreignDirectoryAndUnownedEmploymentNeverReadAClaim() = runTest {
        for (case in 0..3) {
            val p = ExpensePreparationFixture()
            val f = p.f
            val directory =
                when (case) {
                    0 -> f.directory.copy(accountId = "foreign")
                    1 -> f.directory.copy(companyId = "foreign")
                    2 -> f.directory.copy(timezone = "UTC")
                    else -> f.directory.copy(items = emptyList())
                }
            f.directoryCall = { AppResult.Success(directory) }
            assertIs<AppResult.Failed>(p.load(ExpensePreparationKind.DRAFT))
            assertEquals(0, f.claimReads)
        }
    }

    @Test
    fun draftEditingRejectsOtherClaimsOwnersStatusesAndInconsistentAmounts() = runTest {
        val p = ExpensePreparationFixture()
        val f = p.f
        val invalid =
            listOf(
                f.claim.copy(id = "foreign"),
                f.claim.copy(employeeId = "foreign"),
                f.claim.copy(status = ExpenseStatus.PENDING),
                f.claim.copy(draft = f.draft.copy(total = DecimalAmount.parse("120000.26"))),
                f.claim.copy(draft = f.draft.copy(lines = List(21) { f.line })),
            )
        for (claim in invalid) {
            f.claimCall = { AppResult.Success(claim) }
            assertIs<AppResult.Failed>(p.load(ExpensePreparationKind.DRAFT))
        }
        assertEquals(0, f.writes)
    }

    @Test
    fun latePayloadSuccessAndFailureCannotCrossAccountCompanyGrantOrTimezoneChanges() = runTest {
        for (kind in ExpensePreparationKind.entries) for (case in 0..3) for (failed in
            listOf(false, true)) {
            val p = ExpensePreparationFixture()
            val f = p.f
            p.beforeRead = {
                when (case) {
                    0 -> f.sessionState.value = AppResult.Success(null)
                    1 ->
                        f.workspaceState.value =
                            AppResult.Success(f.workspace.copy(id = "replaced"))
                    2 ->
                        f.workspaceState.value =
                            AppResult.Success(
                                f.workspace.copy(permissions = setOf("expenses.read"))
                            )
                    3 ->
                        f.workspaceState.value =
                            AppResult.Success(
                                f.workspace.copy(
                                    company = f.workspace.company.copy(timezone = "UTC")
                                )
                            )
                }
            }
            if (failed) p.failure = expenseFailure("fixture.transport", FailureKind.Network)
            val result = assertIs<AppResult.Failed>(p.load(kind))
            assertTrue(
                result.failure.kind in setOf(FailureKind.AccessDenied, FailureKind.Unauthorized)
            )
            assertNotEquals("fixture.transport", result.failure.code)
            assertEquals(0, f.claimReads + f.writes)
        }
    }

    @Test
    fun latePolicyFailuresCannotReplaceAChangedScopeOrStartDataAcquisition() = runTest {
        for (kind in ExpensePreparationKind.entries) {
            val p = ExpensePreparationFixture()
            p.f.policyCall = {
                p.f.sessionState.value = AppResult.Success(null)
                expenseFailure("fixture.transport", FailureKind.Network)
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(p.load(kind)).failure.kind,
            )
            assertEquals(0, p.reads + p.f.claimReads)
        }
    }

    @Test
    fun cancellationRejectsNonCancellableLatePolicyAndPayloadResults() = runTest {
        for (kind in ExpensePreparationKind.entries) for (policy in listOf(false, true)) {
            val p = ExpensePreparationFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val wait: suspend () -> Unit = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            if (policy)
                p.f.policyCall = {
                    wait()
                    AppResult.Success(p.f.policy)
                }
            else p.beforeRead = wait
            var published = false
            val request = launch {
                p.load(kind)
                published = true
            }
            entered.await()
            request.cancel()
            release.complete(Unit)
            request.join()
            assertFalse(published)
            assertTrue(request.isCancelled)
            assertEquals(if (policy) 0 else 1, p.reads)
            assertEquals(0, p.f.claimReads + p.f.writes)
        }
    }

    @Test
    fun hungAcquisitionTimesOutOnceAndNeverRetries() = runTest {
        for (kind in ExpensePreparationKind.entries) {
            val origin = testScheduler.currentTime
            val p = ExpensePreparationFixture {
                Instant.parse("2026-10-13T00:00:00Z") +
                    (testScheduler.currentTime - origin).milliseconds
            }
            p.beforeRead = { awaitCancellation() }
            assertEquals(
                "client.expense_timeout",
                assertIs<AppResult.Failed>(p.load(kind)).failure.code,
            )
            assertEquals(30_000L, testScheduler.currentTime - origin)
            assertEquals(1, p.reads)
            assertEquals(0, p.f.claimReads + p.f.writes)
        }
    }

    @Test
    fun scheduledMaintenanceAndExpiredPolicyBoundPublication() = runTest {
        for (kind in ExpensePreparationKind.entries) {
            var now = Instant.parse("2026-10-13T00:00:00Z")
            val p = ExpensePreparationFixture { now }
            val f = p.f
            f.policyCall = {
                AppResult.Success(
                    f.policy.copy(
                        maintenance = CompanyMaintenance(now + 10.seconds, now + 20.seconds)
                    )
                )
            }
            val value = assertIs<AppResult.Success<*>>(p.load(kind)).value
            val expiry =
                when (value) {
                    is ExpenseDraftPreparation -> value.expiresAt
                    is ExpenseCategories -> value.expiresAt
                    is ExpenseCostCenters -> value.expiresAt
                    else -> error("Unexpected preparation")
                }
            assertEquals(now + 10.seconds, expiry)
            f.policyCall = { AppResult.Success(f.policy) }
            p.beforeRead = { now += 61.seconds }
            assertIs<AppResult.Failed>(p.load(kind))
        }
    }

    @Test
    fun preparationLeaseRejectsClockRollbackAndUsesTheShortestAuthority() {
        val p = ExpensePreparationFixture()
        val f = p.f
        val now = f.clock.now()
        val policy = CompanyPolicySnapshot(f.policy, now)
        assertIs<AppResult.Failed>(expensePreparationExpiry(f.access, policy, now, now - 1.seconds))
        assertIs<AppResult.Failed>(
            expensePreparationExpiry(f.access, policy, now, now + 61.seconds)
        )
        assertEquals(
            AppResult.Success(now + 5.seconds),
            expensePreparationExpiry(
                f.access.copy(sessionExpiresAt = now + 5.seconds),
                policy,
                now,
                now,
            ),
        )
        assertEquals(
            AppResult.Success(now + 8.seconds),
            expensePreparationExpiry(f.access.copy(readUntil = now + 8.seconds), policy, now, now),
        )
    }
}
