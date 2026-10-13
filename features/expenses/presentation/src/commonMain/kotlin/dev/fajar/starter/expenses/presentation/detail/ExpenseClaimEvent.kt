package dev.fajar.starter.expenses.presentation.detail

sealed interface ExpenseClaimEvent {
    data object EditRequested : ExpenseClaimEvent

    data object Entered : ExpenseClaimEvent

    data object Stopped : ExpenseClaimEvent

    data object RefreshRequested : ExpenseClaimEvent

    data class HistoryRequested(val after: Long?) : ExpenseClaimEvent

    data object BackRequested : ExpenseClaimEvent

    data object VerificationRequested : ExpenseClaimEvent
}
