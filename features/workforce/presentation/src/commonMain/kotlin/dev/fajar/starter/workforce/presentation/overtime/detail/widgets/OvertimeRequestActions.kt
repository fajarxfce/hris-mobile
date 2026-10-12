package dev.fajar.starter.workforce.presentation.overtime.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.action.OvertimeActionKind
import dev.fajar.starter.workforce.presentation.overtime.detail.*

@Composable
fun OvertimeRequestActions(state: OvertimeRequestState, onEvent: (OvertimeRequestEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (OvertimeActionKind.ACTUAL in state.actionOptions)
            AppButton(
                appString(AppString.OvertimeSubmitActual),
                { onEvent(OvertimeRequestEvent.ActionRequested(OvertimeActionKind.ACTUAL)) },
                Modifier.fillMaxWidth(),
                enabled = state.canOpenAction,
            )
        if (OvertimeActionKind.WITHDRAW in state.actionOptions)
            AppTextButton(
                appString(AppString.OvertimeWithdrawRequest),
                { onEvent(OvertimeRequestEvent.ActionRequested(OvertimeActionKind.WITHDRAW)) },
                Modifier.fillMaxWidth(),
                enabled = state.canOpenAction,
            )
    }
}
