package dev.fajar.starter.expenses.presentation.draft

sealed interface ExpenseDraftEffect {
    data object Back : ExpenseDraftEffect

    data object VerifyAccount : ExpenseDraftEffect

    data object RecheckWorkspace : ExpenseDraftEffect

    data class OpenClaim(val id: String, val employmentAfter: String? = null) : ExpenseDraftEffect
}
