package dev.fajar.starter.expenses.presentation.action.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.presentation.action.*
import dev.fajar.starter.localization.*

@Composable
fun ExpenseActionConfirmation(state: ExpenseActionState, onEvent: (ExpenseActionEvent) -> Unit) {
    val dialog = state.dialog ?: return
    AppDialog(
        appString(
            if (dialog == ExpenseActionDialog.EXIT) AppString.ExpenseDiscardChanges
            else AppString.ExpenseDiscardRequest
        ),
        appString(AppString.Close),
        { onEvent(ExpenseActionEvent.DialogDismissed) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText(
                appString(
                    if (dialog == ExpenseActionDialog.EXIT) AppString.ExpenseUnsavedChanges
                    else AppString.ExpenseDiscardRequestHint
                )
            )
            AppButton(
                appString(AppString.ExpenseDiscard),
                {
                    onEvent(
                        if (dialog == ExpenseActionDialog.EXIT) ExpenseActionEvent.ExitConfirmed
                        else ExpenseActionEvent.DiscardConfirmed
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = dialog == ExpenseActionDialog.EXIT || state.work == null,
            )
        }
    }
}
