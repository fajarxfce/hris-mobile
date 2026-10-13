package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentRevisionStatus
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.validateExpenseReferenceValues

private val referenceCode = Regex("[A-Z0-9][A-Z0-9_-]{1,31}")

fun ExpenseLabelReferenceDto.toLabelReference(): ExpenseLabelReference {
    expenseId(id)
    if (code == null && name == null && active == null) return ExpenseLabelReference(id)
    require(code != null && referenceCode.matches(code))
    require(name != null && name.isNotBlank() && name.length <= 200 && active != null)
    return ExpenseLabelReference(id, code, name, active)
}

fun ExpenseReceiptReferenceDto.toReceiptReference(): ExpenseReceiptReference {
    expenseId(revisionId)
    if (
        documentId == null &&
            fileName == null &&
            mediaType == null &&
            size == null &&
            status == null
    )
        return ExpenseReceiptReference(revisionId)
    require(
        documentId != null && fileName != null && fileName.isNotBlank() && fileName.length <= 512
    )
    require(
        mediaType != null &&
            mediaType.length in 3..120 &&
            size != null &&
            size in 1..67_108_864L &&
            status != null
    )
    return ExpenseReceiptReference(
        revisionId,
        expenseId(documentId),
        fileName,
        mediaType,
        size,
        DocumentRevisionStatus.valueOf(status),
    )
}

fun ExpenseReferenceValuesDto.toReferences(query: ExpenseReferenceQuery): ExpenseReferenceValues {
    require(categories.size <= 20 && costCenters.size <= 20 && receipts.size <= 60)
    val values =
        ExpenseReferenceValues(
            expenseId(employmentId),
            categories.map { it.toLabelReference() },
            costCenters.map { it.toLabelReference() },
            receipts.map { it.toReceiptReference() },
        )
    require(validateExpenseReferenceValues(query, values) is AppResult.Success)
    return values
}
