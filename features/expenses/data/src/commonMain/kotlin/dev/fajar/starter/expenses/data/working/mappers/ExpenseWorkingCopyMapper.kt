package dev.fajar.starter.expenses.data.working.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.working.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseReferenceQuery
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.validateExpenseWorkingCopy
import kotlin.time.Instant

fun ExpenseWorkingCopy.toWorkingDto() =
    ExpenseWorkingCopyDto(
        claimId,
        ExpenseEmploymentDto(
            employee.id,
            employee.employeeNumber,
            employee.status.name,
            employee.version,
        ),
        timezone,
        input.title,
        input.description,
        input.lines.map { it.toDto() },
        input.reason,
        expectedVersion,
        item?.toWorkingDto(),
        revision,
        savedAt.toString(),
        references?.let { value ->
            ExpenseReferenceValuesDto(
                value.employeeId,
                value.categories.map { it.toWorkingReference() },
                value.costCenters.map { it.toWorkingReference() },
                value.receipts.map { it.toWorkingReference() },
            )
        },
        hasEdits = hasEdits,
    )

fun ExpenseWorkingCopyDto.toWorkingCopy(slot: ExpenseWorkingSlot): ExpenseWorkingCopy {
    require(schema == 1 && lines.size <= 20 && savedAt.length in 1..40)
    val mappedLines = lines.map { it.toLine() }
    val query = expenseReferenceQuery(employee.id, mappedLines)
    require(query is AppResult.Success)
    val value =
        ExpenseWorkingCopy(
            claimId,
            ExpenseEmployment(
                employee.id,
                employee.employeeNumber,
                ExpenseEmploymentStatus.valueOf(employee.status),
                employee.version,
            ),
            timezone,
            ExpenseDraftInput(title, description, mappedLines, reason),
            expectedVersion,
            item?.toWorkingItem(),
            revision,
            Instant.parse(savedAt),
            references?.toReferences(query.value),
            hasEdits,
        )
    require(validateExpenseWorkingCopy(slot, value) is AppResult.Success)
    return value
}

private fun ExpenseWorkingItem.toWorkingDto() =
    ExpenseWorkingItemDto(
        requireNotNull(input.id),
        input.categoryId,
        input.occurredOn,
        input.amount,
        input.description,
        input.costCenterId,
        input.receiptRevisionIds.toList(),
        category?.toWorkingReference(),
        costCenter?.toWorkingReference(),
        receipts.map { it.toWorkingReference() },
    )

private fun ExpenseWorkingItemDto.toWorkingItem(): ExpenseWorkingItem {
    require(receipts.size <= 3 && receiptRevisionIds.size <= 3)
    return ExpenseWorkingItem(
        ExpenseLineInput(
            id,
            categoryId,
            occurredOn,
            amount,
            description,
            costCenterId,
            receiptRevisionIds.toList(),
        ),
        category?.toLabelReference(),
        costCenter?.toLabelReference(),
        receipts.map { it.toReceiptReference() },
    )
}

private fun ExpenseLabelReference.toWorkingReference() =
    ExpenseLabelReferenceDto(id, code, name, active)

private fun ExpenseReceiptReference.toWorkingReference() =
    ExpenseReceiptReferenceDto(revisionId, documentId, fileName, mediaType, size, status?.name)
