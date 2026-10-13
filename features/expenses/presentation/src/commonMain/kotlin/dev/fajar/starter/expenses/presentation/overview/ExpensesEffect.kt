package dev.fajar.starter.expenses.presentation.overview

sealed interface ExpensesEffect {
    data object Back : ExpensesEffect

    data object VerifyAccount : ExpensesEffect

    data object RecheckWorkspace : ExpensesEffect

    data class OpenClaim(val id: String) : ExpensesEffect
}
