package dev.fajar.starter.expenses.presentation.item

import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.working.entities.*

data class ExpenseItemTarget(
    val workspaceId: String,
    val slot: ExpenseWorkingSlot,
    val copy: ExpenseWorkingCopy,
    val employmentAfter: String? = null,
) {
    val line: ExpenseLine?
        get() = copy.input.lines.singleOrNull { it.id == copy.item?.input?.id }
}
