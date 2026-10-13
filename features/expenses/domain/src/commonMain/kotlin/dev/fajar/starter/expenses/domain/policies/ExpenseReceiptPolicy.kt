@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentFileSelection
import dev.fajar.starter.documents.domain.policies.resolveDocumentFile
import dev.fajar.starter.expenses.domain.entities.ExpenseReceipt
import kotlin.uuid.Uuid

const val EXPENSE_RECEIPT_CHUNK_BYTES = 1_048_576

fun validateExpenseReceipt(receipt: ExpenseReceipt): AppResult<Unit> {
    if (
        Uuid.parseOrNull(receipt.id)?.toString() != receipt.id ||
            !Regex("[0-9a-f]{64}").matches(receipt.sha256) ||
            resolveDocumentFile(
                DocumentFileSelection(receipt.fileName, receipt.mediaType, receipt.size)
            ) is
                AppResult.Failed
    )
        return expenseFailure("client.invalid_response", FailureKind.Unexpected)
    return AppResult.Success(Unit)
}
