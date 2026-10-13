package dev.fajar.starter.expenses.presentation.draft.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.presentation.draft.*
import dev.fajar.starter.localization.*

@Composable
fun ExpenseDraftConfirmation(state: ExpenseDraftState, onEvent: (ExpenseDraftEvent) -> Unit) {
    val dialog = state.dialog ?: return
    AppDialog(
        appString(
            when (dialog) {
                ExpenseDraftDialog.EXIT -> AppString.ExpenseDiscardChanges
                ExpenseDraftDialog.RELOAD -> AppString.ExpenseReloadDraft
                ExpenseDraftDialog.DISCARD_COMMAND -> AppString.ExpenseEditDraft
            }
        ),
        appString(AppString.Close),
        { onEvent(ExpenseDraftEvent.DialogDismissed) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText(
                appString(
                    when (dialog) {
                        ExpenseDraftDialog.EXIT -> AppString.ExpenseUnsavedChanges
                        ExpenseDraftDialog.RELOAD -> AppString.ExpenseReloadExplanation
                        ExpenseDraftDialog.DISCARD_COMMAND ->
                            AppString.ExpenseEditRequestExplanation
                    }
                )
            )
            AppButton(
                appString(
                    when (dialog) {
                        ExpenseDraftDialog.EXIT -> AppString.ExpenseDiscard
                        ExpenseDraftDialog.RELOAD -> AppString.Refresh
                        ExpenseDraftDialog.DISCARD_COMMAND -> AppString.ExpenseEditDraft
                    }
                ),
                {
                    onEvent(
                        when (dialog) {
                            ExpenseDraftDialog.EXIT -> ExpenseDraftEvent.ExitConfirmed
                            ExpenseDraftDialog.RELOAD -> ExpenseDraftEvent.ReloadConfirmed
                            ExpenseDraftDialog.DISCARD_COMMAND ->
                                ExpenseDraftEvent.DiscardCommandConfirmed
                        }
                    )
                },
                Modifier.fillMaxWidth(),
                enabled = state.work == null,
            )
        }
    }
}
