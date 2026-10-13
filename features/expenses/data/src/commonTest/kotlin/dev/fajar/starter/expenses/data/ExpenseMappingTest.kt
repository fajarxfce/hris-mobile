package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.repositories.RemoteExpenseRepository
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth

class ExpenseMappingTest {
    @Test
    fun malformedDirectoriesCannotSupplyOtherAccountsOrNonAdvancingEmployeePages() = runTest {
        val f = ExpenseDataFixture()
        val original = f.directory
        val repo = RemoteExpenseRepository(f)
        assertIs<AppResult.Success<ExpenseDirectory>>(repo.directory(f.access, null))
        for (directory in
            listOf(
                original.copy(accountId = f.employee.id),
                original.copy(companyId = f.employee.id),
                original.copy(asOf = "2026-02-30"),
                original.copy(timezone = "not/a-timezone"),
                original.copy(employments = ExpensePageDto(listOf(f.employee, f.employee), null)),
                original.copy(employments = ExpensePageDto(listOf(f.employee), "invalid")),
            )) {
            f.directory = directory
            assertIs<AppResult.Failed>(repo.directory(f.access, null))
        }
        f.directory = original.copy(employments = ExpensePageDto(listOf(f.employee), "E01"))
        assertIs<AppResult.Failed>(repo.directory(f.access, "E01"))
    }

    @Test
    fun claimPagesRejectRepeatedIdsUnorderedRowsAndUnboundedResults() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpenseRepository(f)
        val selection = ExpenseSelection(f.employee.id, YearMonth(2026, 10))
        for (page in
            listOf(
                ExpensePageDto(listOf(f.summary, f.summary), null),
                ExpensePageDto(List(21) { f.summary }, null),
                ExpensePageDto(listOf(f.summary, f.summary.copy(id = f.line.id)), null),
                ExpensePageDto(listOf(f.summary), "../next"),
            )) {
            f.page = page
            assertIs<AppResult.Failed>(repo.list(f.access, selection))
        }
        f.page = ExpensePageDto(listOf(f.summary.copy(id = f.line.id), f.summary), f.summary.id)
        assertEquals(
            2,
            assertIs<AppResult.Success<ExpensePage<ExpenseSummary>>>(repo.list(f.access, selection))
                .value
                .items
                .size,
        )
        assertIs<AppResult.Failed>(repo.list(f.access, selection.copy(after = f.summary.id)))
    }

    @Test
    fun rawMappingKeepsExactAmountsAndRejectsInvalidMoneyAndDuplicateLines() = runTest {
        val f = ExpenseDataFixture()
        val original = f.claim
        val repo = RemoteExpenseRepository(f)
        assertEquals(
            "120000.25",
            assertIs<AppResult.Success<ExpenseClaim>>(repo.claim(f.access, f.claim.id))
                .value
                .draft
                .total
                .value,
        )
        for (claim in
            listOf(
                original.copy(id = f.line.id),
                original.copy(version = -1),
                original.copy(draftRevision = 1),
                original.copy(draft = f.draft.copy(lines = listOf(f.line, f.line))),
                original.copy(draft = f.draft.copy(totalAmount = "NaN")),
                original.copy(draft = f.draft.copy(totalAmount = "-0.01")),
                original.copy(draft = f.draft.copy(totalAmount = "1.001")),
                original.copy(draft = f.draft.copy(currency = "USD")),
            )) {
            f.claim = claim
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repo.claim(f.access, f.summary.id)).failure.kind,
            )
        }
    }

    @Test
    fun immutableHistoryRequiresStrictlyDescendingRevisionsWithinTheRequestedPage() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpenseRepository(f)
        for (history in
            listOf(
                ExpensePageDto(listOf(f.change, f.change), null),
                ExpensePageDto(listOf(f.change.copy(version = 2)), null),
                ExpensePageDto(listOf(f.change), "another"),
            )) {
            f.history = history
            assertIs<AppResult.Failed>(repo.history(f.access, f.claim.id, 1))
        }
        f.history = ExpensePageDto(listOf(f.change), null)
        assertIs<AppResult.Success<ExpensePage<ExpenseChange>>>(
            repo.history(f.access, f.claim.id, 1)
        )
    }

    @Test
    fun submittedEvidenceAndPaymentProgressHaveIndependentValidatedFacts() = runTest {
        val f = ExpenseDataFixture()
        val repo = RemoteExpenseRepository(f)
        val original = f.submission
        val valid =
            assertIs<AppResult.Success<ExpenseSubmission>>(repo.submission(f.access, original.id))
                .value
        assertEquals(f.receipt.sha256, valid.lines.single().receipts.single().sha256)
        assertEquals(ExpenseStatus.PENDING, valid.claimStatus)
        for (submission in
            listOf(
                original.copy(approval = f.approval.copy(currentStep = 1)),
                original.copy(
                    lines =
                        listOf(
                            f.submittedLine.copy(
                                receipts = listOf(f.receipt.copy(sha256 = "invalid"))
                            )
                        )
                ),
                original.copy(
                    lines = listOf(f.submittedLine.copy(receipts = listOf(f.receipt, f.receipt)))
                ),
            )) {
            f.submission = submission
            assertIs<AppResult.Failed>(repo.submission(f.access, original.id))
        }
        f.payments = listOf(f.payment)
        assertEquals(
            ExpensePaymentStatus.PENDING,
            assertIs<AppResult.Success<List<ExpensePaymentAttempt>>>(
                    repo.payments(f.access, f.claim.id)
                )
                .value
                .single()
                .status,
        )
        f.payments = listOf(f.payment.copy(status = "SUCCEEDED"))
        assertIs<AppResult.Failed>(repo.payments(f.access, f.claim.id))
        f.payments = listOf(f.payment, f.payment)
        assertIs<AppResult.Failed>(repo.payments(f.access, f.claim.id))
    }

    @Test
    fun cancellationIsNotConvertedToARecoverableNetworkFailure() = runTest {
        val f = ExpenseDataFixture()
        f.failure = CancellationException("test cancellation")
        assertFailsWith<CancellationException> {
            RemoteExpenseRepository(f).claim(f.access, f.claim.id)
        }
    }
}
