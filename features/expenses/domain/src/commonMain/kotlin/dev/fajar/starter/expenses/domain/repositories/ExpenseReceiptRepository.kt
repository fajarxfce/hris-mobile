package dev.fajar.starter.expenses.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface ExpenseReceiptRepository {
    /** Fresh resource authorization against the immutable submitted declaration. */
    suspend fun inspect(
        access: ExpenseAccess,
        submissionId: String,
        receipt: ExpenseReceipt,
    ): AppResult<Unit>

    /**
     * Sequential, backpressured content. Buffers are valid only during collection of each value.
     * Successful completion verifies the complete declaration and hash. No retry or read-ahead.
     */
    fun chunks(
        access: ExpenseAccess,
        submissionId: String,
        receipt: ExpenseReceipt,
    ): Flow<AppResult<ExpenseReceiptChunk>>
}
