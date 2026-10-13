package dev.fajar.starter.expenses.domain.entities

import kotlinx.datetime.YearMonth

data class ExpenseSelection(
    val employeeId: String? = null,
    val month: YearMonth? = null,
    val employmentAfter: String? = null,
    val status: ExpenseStatus? = null,
    val after: String? = null,
)
