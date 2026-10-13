package dev.fajar.starter.expenses.presentation.draft

import dev.fajar.starter.expenses.presentation.item.*

sealed interface ExpenseDraftEvent {
    data object Entered : ExpenseDraftEvent

    data object Stopped : ExpenseDraftEvent

    data object ReloadRequested : ExpenseDraftEvent

    data object ReloadConfirmed : ExpenseDraftEvent

    data class TitleChanged(val value: String) : ExpenseDraftEvent

    data class DescriptionChanged(val value: String) : ExpenseDraftEvent

    data class ReasonChanged(val value: String) : ExpenseDraftEvent

    data object ItemAdded : ExpenseDraftEvent

    data class ItemEdited(val id: String) : ExpenseDraftEvent

    data class ItemSaved(val target: ExpenseItemTarget, val value: ExpenseItemResult) :
        ExpenseDraftEvent

    data class ItemDismissed(val target: ExpenseItemTarget) : ExpenseDraftEvent

    data class ItemRemoved(val id: String) : ExpenseDraftEvent

    data object SaveRequested : ExpenseDraftEvent

    data object RetryRequested : ExpenseDraftEvent

    data object OpenClaimRequested : ExpenseDraftEvent

    data object DiscardCommandRequested : ExpenseDraftEvent

    data object DiscardCommandConfirmed : ExpenseDraftEvent

    data object DialogDismissed : ExpenseDraftEvent

    data object BackRequested : ExpenseDraftEvent

    data object ExitConfirmed : ExpenseDraftEvent

    data object VerificationRequested : ExpenseDraftEvent
}
