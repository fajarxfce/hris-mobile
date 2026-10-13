package dev.fajar.starter.expenses.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable data object ExpensesRoute

@Serializable data class ExpenseClaimRoute(val id: String)
