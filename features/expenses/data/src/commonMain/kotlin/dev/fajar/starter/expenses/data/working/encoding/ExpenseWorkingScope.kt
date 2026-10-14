package dev.fajar.starter.expenses.data.working.encoding

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.validateExpenseId
import dev.fajar.starter.expenses.domain.working.entities.ExpenseWorkingSlot
import dev.fajar.starter.expenses.domain.working.policies.validateExpenseWorkingSlot
import kotlinx.serialization.json.Json

fun expenseWorkingPartition(access: ExpenseAccess, slot: ExpenseWorkingSlot): String {
    require(validateExpenseId(access.companyId) is AppResult.Success)
    require(validateExpenseWorkingSlot(slot) is AppResult.Success)
    return "expense-working-copy/${access.companyId}/" +
        when (slot) {
            is ExpenseWorkingSlot.Creation -> "new/${slot.employeeId}"
            is ExpenseWorkingSlot.Claim -> "claim/${slot.claimId}"
        }
}

fun expenseWorkingAssociatedData(
    access: ExpenseAccess,
    slot: ExpenseWorkingSlot,
    revision: String,
    part: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-expense-working-copy-v1",
                access.audience,
                access.accountId,
                expenseWorkingPartition(access, slot),
                revision,
                part,
            )
        )
        .encodeToByteArray()
