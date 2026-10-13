package dev.fajar.starter.expenses.data.datasources

import dev.fajar.starter.expenses.data.dto.*

interface ExpenseRemoteDataSource {
    suspend fun references(
        sessionId: String,
        companyId: String,
        employeeId: String,
        categoryIds: List<String>,
        costCenterIds: List<String>,
        receiptRevisionIds: List<String>,
    ): ExpenseReferenceValuesDto

    suspend fun saveDraft(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSaveDraftRequest,
    ): ExpenseCommandReceiptDto

    suspend fun submit(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseSubmitRequest,
    ): ExpenseCommandReceiptDto

    suspend fun cancel(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto

    suspend fun withdraw(
        sessionId: String,
        companyId: String,
        id: String,
        operationId: String,
        request: ExpenseActionRequest,
    ): ExpenseCommandReceiptDto

    suspend fun categories(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): ExpensePageDto<ExpenseCategoryDto>

    suspend fun costCenters(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ): ExpensePageDto<ExpenseCostCenterDto>

    suspend fun directory(sessionId: String, companyId: String, after: String?): ExpenseDirectoryDto

    suspend fun claims(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): ExpensePageDto<ExpenseSummaryDto>

    suspend fun claim(sessionId: String, companyId: String, id: String): ExpenseClaimDto

    suspend fun history(
        sessionId: String,
        companyId: String,
        id: String,
        after: Long?,
    ): ExpensePageDto<ExpenseChangeDto>

    suspend fun submission(sessionId: String, companyId: String, id: String): ExpenseSubmissionDto

    suspend fun payments(
        sessionId: String,
        companyId: String,
        id: String,
    ): List<ExpensePaymentAttemptDto>
}
