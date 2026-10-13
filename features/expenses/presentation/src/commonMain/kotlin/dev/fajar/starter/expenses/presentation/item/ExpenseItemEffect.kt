package dev.fajar.starter.expenses.presentation.item

sealed interface ExpenseItemEffect {
    data class Saved(val value: ExpenseItemResult) : ExpenseItemEffect

    data object Back : ExpenseItemEffect

    data object VerifyAccount : ExpenseItemEffect

    data object RecheckWorkspace : ExpenseItemEffect
}
