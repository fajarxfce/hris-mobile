package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.presentation.action.ExpenseActionMode
import dev.fajar.starter.expenses.presentation.action.formatters.title
import dev.fajar.starter.expenses.presentation.detail.*
import dev.fajar.starter.localization.*

@Composable
fun ExpenseClaimActions(state: ExpenseClaimState, onEvent: (ExpenseClaimEvent) -> Unit) {
    if (state.command != null)
        AppButton(
            appString(AppString.ExpenseRecoverRequest),
            { onEvent(ExpenseClaimEvent.RecoveryRequested) },
            Modifier.fillMaxWidth(),
            enabled = state.canRecover,
        )
    else {
        for (mode in state.actionOptions) {
            if (mode == ExpenseActionMode.CANCEL)
                AppTextButton(
                    appString(mode.title()),
                    { onEvent(ExpenseClaimEvent.ActionRequested(mode)) },
                    Modifier.fillMaxWidth(),
                    enabled = state.canOpenAction,
                )
            else
                AppButton(
                    appString(mode.title()),
                    { onEvent(ExpenseClaimEvent.ActionRequested(mode)) },
                    Modifier.fillMaxWidth(),
                    enabled = state.canOpenAction,
                )
        }
    }
}
