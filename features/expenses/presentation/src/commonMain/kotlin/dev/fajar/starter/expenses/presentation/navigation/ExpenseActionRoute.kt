package dev.fajar.starter.expenses.presentation.navigation

import dev.fajar.starter.expenses.presentation.action.ExpenseActionMode
import kotlinx.serialization.Serializable

@Serializable
data class ExpenseActionRoute(
    val id: String,
    val mode: ExpenseActionMode,
    val employmentAfter: String? = null,
)
