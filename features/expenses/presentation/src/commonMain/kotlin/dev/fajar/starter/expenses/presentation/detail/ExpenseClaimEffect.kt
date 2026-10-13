package dev.fajar.starter.expenses.presentation.detail

sealed interface ExpenseClaimEffect {
    data object Back : ExpenseClaimEffect

    data object VerifyAccount : ExpenseClaimEffect

    data object RecheckWorkspace : ExpenseClaimEffect
}
