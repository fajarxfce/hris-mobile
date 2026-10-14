package dev.fajar.starter.expenses.domain.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.ExpenseLineInput
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.working.entities.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

fun validateExpenseWorkingSlot(slot: ExpenseWorkingSlot): AppResult<Unit> =
    when (slot) {
        is ExpenseWorkingSlot.Creation -> validateExpenseId(slot.employeeId)
        is ExpenseWorkingSlot.Claim -> validateExpenseId(slot.claimId)
    }

/** Bounds local input without requiring a title, reason, finished item or live action review. */
fun validateExpenseWorkingCopy(
    slot: ExpenseWorkingSlot,
    copy: ExpenseWorkingCopy,
): AppResult<Unit> {
    val validSlot = validateExpenseWorkingSlot(slot)
    if (validSlot is AppResult.Failed) return validSlot
    if (
        validateExpenseId(copy.claimId) is AppResult.Failed ||
            validateExpenseId(copy.revision) is AppResult.Failed ||
            validateExpenseId(copy.employee.id) is AppResult.Failed ||
            copy.employee.employeeNumber.isBlank() ||
            copy.employee.employeeNumber.length > 32 ||
            copy.employee.version !in 0..9_007_199_254_740_991L ||
            copy.expectedVersion?.let { it !in 0 until 9_007_199_254_740_991L } == true ||
            copy.input.title.length > 161 ||
            copy.input.description.length > 2_001 ||
            copy.input.reason.length > 1_001 ||
            copy.input.lines.size > 20 ||
            copy.input.lines.distinctBy { it.id }.size != copy.input.lines.size ||
            copy.timezone.length !in 1..100
    )
        return expenseFailure("client.expense_working_copy_invalid")
    when (slot) {
        is ExpenseWorkingSlot.Creation ->
            if (slot.employeeId != copy.employee.id)
                return expenseFailure("client.expense_working_copy_invalid")
        is ExpenseWorkingSlot.Claim ->
            if (slot.claimId != copy.claimId || copy.expectedVersion == null)
                return expenseFailure("client.expense_working_copy_invalid")
    }
    try {
        TimeZone.of(copy.timezone)
    } catch (_: IllegalArgumentException) {
        return expenseFailure("client.expense_working_copy_invalid")
    }
    for (line in copy.input.lines) {
        val valid =
            validateExpenseLineInput(
                ExpenseLineInput(
                    line.id,
                    line.categoryId,
                    line.occurredOn.toString(),
                    line.amount.value,
                    line.description,
                    line.costCenterId,
                    line.receiptIds,
                ),
                line.id,
            )
        if (valid is AppResult.Failed) return valid
    }
    copy.references?.let { references ->
        val query = expenseReferenceQuery(copy.employee.id, copy.input.lines)
        if (query is AppResult.Failed) return query
        val valid = validateExpenseReferenceValues((query as AppResult.Success).value, references)
        if (valid is AppResult.Failed) return valid
    }
    return copy.item?.let(::validateExpenseWorkingItem) ?: AppResult.Success(Unit)
}

fun validateExpenseWorkingItem(item: ExpenseWorkingItem): AppResult<Unit> {
    val input = item.input
    if (
        input.id == null ||
            validateExpenseId(input.id) is AppResult.Failed ||
            (input.categoryId.isNotEmpty() &&
                validateExpenseId(input.categoryId) is AppResult.Failed) ||
            input.occurredOn.length != 10 ||
            input.amount.length > 65 ||
            input.description.length > 501 ||
            input.costCenterId?.let { validateExpenseId(it) is AppResult.Failed } == true ||
            input.receiptRevisionIds.size > 3 ||
            input.receiptRevisionIds.distinct().size != input.receiptRevisionIds.size ||
            input.receiptRevisionIds.any { validateExpenseId(it) is AppResult.Failed } ||
            item.category?.let { it.id != input.categoryId } == true ||
            item.costCenter?.let { it.id != input.costCenterId } == true ||
            item.receipts.size > 3 ||
            item.receipts.distinctBy { it.revisionId }.size != item.receipts.size ||
            item.receipts.any { it.revisionId !in input.receiptRevisionIds }
    )
        return expenseFailure("client.expense_working_copy_invalid")
    val date =
        try {
            LocalDate.parse(input.occurredOn)
        } catch (_: IllegalArgumentException) {
            return expenseFailure("client.expense_working_copy_invalid")
        }
    if (date.year !in 1900..2200 || date.toString() != input.occurredOn)
        return expenseFailure("client.expense_working_copy_invalid")
    return AppResult.Success(Unit)
}
