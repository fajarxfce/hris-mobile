package dev.fajar.starter.expenses.data.datasources

import dev.fajar.starter.expenses.data.dto.ExpenseReceiptHeadersDto

interface ExpenseReceiptDataSource {
    suspend fun inspect(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
    ): ExpenseReceiptHeadersDto

    suspend fun chunk(
        sessionId: String,
        companyId: String,
        submissionId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray
}
