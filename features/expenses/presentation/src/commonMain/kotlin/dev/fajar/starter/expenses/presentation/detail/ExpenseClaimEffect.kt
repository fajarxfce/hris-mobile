package dev.fajar.starter.expenses.presentation.detail

sealed interface ExpenseClaimEffect {
    data class OpenAction(
        val id: String,
        val mode: dev.fajar.starter.expenses.presentation.action.ExpenseActionMode,
        val employmentAfter: String?,
    ) : ExpenseClaimEffect

    data class EditDraft(
        val employeeId: String,
        val claimId: String,
        val employmentAfter: String? = null,
    ) : ExpenseClaimEffect

    data object Back : ExpenseClaimEffect

    data object VerifyAccount : ExpenseClaimEffect

    data object RecheckWorkspace : ExpenseClaimEffect
}
