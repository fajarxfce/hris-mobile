package dev.fajar.starter.expenses.presentation.action

sealed interface ExpenseActionEvent {
    data object Entered : ExpenseActionEvent

    data object Stopped : ExpenseActionEvent

    data object RefreshRequested : ExpenseActionEvent

    data class ReasonChanged(val value: String) : ExpenseActionEvent

    data object ReviewLatestRequested : ExpenseActionEvent

    data object Confirmed : ExpenseActionEvent

    data object RetryRequested : ExpenseActionEvent

    data object OpenClaimRequested : ExpenseActionEvent

    data object EditRetainedDraftRequested : ExpenseActionEvent

    data object DiscardRequested : ExpenseActionEvent

    data object DiscardConfirmed : ExpenseActionEvent

    data object DialogDismissed : ExpenseActionEvent

    data object BackRequested : ExpenseActionEvent

    data object ExitConfirmed : ExpenseActionEvent

    data object VerificationRequested : ExpenseActionEvent
}
