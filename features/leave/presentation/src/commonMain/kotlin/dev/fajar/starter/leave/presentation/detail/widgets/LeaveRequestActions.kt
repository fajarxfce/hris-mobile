package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.AppButton
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.formatters.leaveActionLabel
import dev.fajar.starter.localization.*

@Composable
fun LeaveRequestActions(state: LeaveRequestState, onEvent: (LeaveRequestEvent) -> Unit) {
    state.actionOptions.forEach { action ->
        AppButton(
            appString(leaveActionLabel(action)),
            { onEvent(LeaveRequestEvent.ActionRequested(action)) },
            Modifier.fillMaxWidth(),
            primary = false,
            enabled = action in state.availableActions,
        )
    }
}
