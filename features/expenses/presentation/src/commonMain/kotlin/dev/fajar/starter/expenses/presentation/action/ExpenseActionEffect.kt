package dev.fajar.starter.expenses.presentation.action

sealed interface ExpenseActionEffect {
    data object Back : ExpenseActionEffect

    data object VerifyAccount : ExpenseActionEffect

    data object RecheckWorkspace : ExpenseActionEffect

    data class OpenClaim(val id: String, val employmentAfter: String?) : ExpenseActionEffect

    data class EditDraft(
        val employeeId: String,
        val claimId: String,
        val employmentAfter: String?,
    ) : ExpenseActionEffect
}
