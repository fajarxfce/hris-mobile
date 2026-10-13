package dev.fajar.starter.expenses.data

import dev.fajar.starter.expenses.data.datasources.ExpenseRemoteDataSource
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class ExpenseDataFixture : ExpenseRemoteDataSource {
    override suspend fun saveDraft(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSaveDraftRequest,
    ): ExpenseCommandReceiptDto = error("No mutation expected")

    override suspend fun submit(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSubmitRequest,
    ): ExpenseCommandReceiptDto = error("No mutation expected")

    override suspend fun cancel(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto = error("No mutation expected")

    override suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto = error("No mutation expected")

    val now = Instant.parse("2026-10-13T00:00:00Z")
    val access =
        ExpenseAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            "Asia/Jakarta",
            setOf("expenses.self.manage"),
            now + 24.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    val employee = ExpenseEmploymentDto("33333333-3333-3333-3333-333333333333", "E01", "ACTIVE", 0)
    var directory =
        ExpenseDirectoryDto(
            access.accountId,
            access.companyId,
            "2026-10-13",
            "Asia/Jakarta",
            ExpensePageDto(listOf(employee), null),
        )
    val summary =
        ExpenseSummaryDto(
            "44444444-4444-4444-4444-444444444444",
            employee.id,
            "E01",
            "Alex",
            "Client visit",
            "120000.25",
            "IDR",
            now.toString(),
            "DRAFT",
            0,
        )
    var page = ExpensePageDto(listOf(summary), null)
    val line =
        ExpenseLineDto(
            "55555555-5555-5555-5555-555555555555",
            "66666666-6666-6666-6666-666666666666",
            "2026-10-12",
            "120000.25",
            "Client transport",
            null,
            emptyList(),
        )
    val draft =
        ExpenseDraftDto(
            0,
            "E01",
            "Alex",
            summary.title,
            "Transport reimbursement",
            summary.totalAmount,
            "IDR",
            listOf(line),
            access.accountId,
            "Created",
            now.toString(),
        )
    var claim =
        ExpenseClaimDto(
            summary.id,
            employee.id,
            access.accountId,
            now.toString(),
            0,
            0,
            "DRAFT",
            draft,
            0,
        )
    val change =
        ExpenseChangeDto(0, 0, "DRAFT", "DRAFT_SAVED", access.accountId, "Created", now.toString())
    var history = ExpensePageDto(listOf(change), null)
    val receipt =
        ExpenseReceiptDto(
            "77777777-7777-7777-7777-777777777777",
            "Receipt.pdf",
            "application/pdf",
            1024,
            "a".repeat(64),
            false,
        )
    val submittedLine =
        ExpenseSubmittedLineDto(
            line.id,
            line.occurredOn,
            line.amount,
            line.description,
            ExpenseCategoryReferenceDto(line.categoryId, "TRAVEL", "Travel", 0, 0),
            null,
            listOf(receipt),
        )
    val approval =
        ExpenseApprovalDto(
            "88888888-8888-8888-8888-888888888888",
            "PENDING",
            0,
            0,
            listOf(setOf("99999999-9999-9999-9999-999999999999")),
        )
    var submission =
        ExpenseSubmissionDto(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            claim.id,
            employee.id,
            1,
            0,
            draft.title,
            draft.description,
            draft.totalAmount,
            "IDR",
            listOf(submittedLine),
            now.toString(),
            "Submitted",
            approval,
            1,
            "PENDING",
            true,
            emptyList(),
        )
    val payment =
        ExpensePaymentAttemptDto(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            "PENDING",
            now.toString(),
            now.toString(),
            null,
        )
    var payments: List<ExpensePaymentAttemptDto> = emptyList()
    var failure: Exception? = null

    val category =
        ExpenseCategoryDto(
            line.categoryId,
            "TRAVEL",
            "Business travel",
            "IDR",
            "2026-01-01",
            "500000.00",
            "2000000.00",
            true,
            true,
            30,
            listOf("PERMANENT", "FIXED_TERM"),
            true,
            0,
            0,
        )
    var categories = ExpensePageDto(listOf(category), null)
    val costCenter =
        ExpenseCostCenterDto("dddddddd-dddd-dddd-dddd-dddddddddddd", "CC01", "Operations", 0)
    var costCenters = ExpensePageDto(listOf(costCenter), null)

    override suspend fun categories(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): ExpensePageDto<ExpenseCategoryDto> {
        failure?.let { throw it }
        return categories
    }

    override suspend fun costCenters(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ): ExpensePageDto<ExpenseCostCenterDto> {
        failure?.let { throw it }
        return costCenters
    }

    override suspend fun directory(
        sessionId: String,
        companyId: String,
        after: String?,
    ): ExpenseDirectoryDto {
        failure?.let { throw it }
        return directory
    }

    override suspend fun claims(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): ExpensePageDto<ExpenseSummaryDto> {
        failure?.let { throw it }
        return page
    }

    override suspend fun claim(sessionId: String, companyId: String, id: String): ExpenseClaimDto {
        failure?.let { throw it }
        return claim
    }

    override suspend fun history(
        sessionId: String,
        companyId: String,
        id: String,
        after: Long?,
    ): ExpensePageDto<ExpenseChangeDto> {
        failure?.let { throw it }
        return history
    }

    override suspend fun submission(
        sessionId: String,
        companyId: String,
        id: String,
    ): ExpenseSubmissionDto {
        failure?.let { throw it }
        return submission
    }

    override suspend fun payments(
        sessionId: String,
        companyId: String,
        id: String,
    ): List<ExpensePaymentAttemptDto> {
        failure?.let { throw it }
        return payments
    }
}
