package dev.fajar.starter.expenses.data.datasources

import dev.fajar.starter.expenses.data.api.ExpenseApi
import org.koin.core.annotation.Single

@Single
class ApiExpenseReceiptDataSource(private val api: ExpenseApi) : ExpenseReceiptDataSource {
    override suspend fun inspect(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
    ) = api.inspectReceipt(sessionId, companyId, submissionId, revisionId)

    override suspend fun chunk(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ) =
        api.receiptChunk(
            sessionId,
            companyId,
            submissionId,
            revisionId,
            entityTag,
            size,
            offset,
            maximumBytes,
        )
}
