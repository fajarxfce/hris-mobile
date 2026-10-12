package dev.fajar.starter.leave.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveCommandOutcome
import dev.fajar.starter.leave.presentation.detail.*
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveActionStatusCard(state: LeaveRequestState, onEvent: (LeaveRequestEvent) -> Unit) {
    val action = state.action
    val command = action.command
    if (command != null) {
        AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
            AppText(
                appString(leaveActionLabel(command.command.action)),
                style = FluentTheme.typography.bodyStrong,
            )
            AppText(command.command.reason)
            AppText(
                appString(
                    if (action.working) AppString.LeaveActionSending
                    else leaveActionStatus(command.outcome)
                ),
                color = AppColors.muted,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (command.outcome) {
                    LeaveCommandOutcome.Pending,
                    is LeaveCommandOutcome.Unconfirmed -> {
                        AppTextButton(
                            appString(AppString.LeaveRetryDelivery),
                            { onEvent(LeaveRequestEvent.RetryActionRequested) },
                            enabled = state.canRetryAction,
                        )
                        if (command.outcome == LeaveCommandOutcome.Pending)
                            AppTextButton(
                                appString(AppString.LeaveDiscardAction),
                                { onEvent(LeaveRequestEvent.DiscardActionRequested) },
                                enabled = state.canDiscardAction,
                            )
                    }
                    is LeaveCommandOutcome.Confirmed,
                    is LeaveCommandOutcome.Rejected ->
                        AppTextButton(
                            appString(AppString.LeaveReviewAction),
                            { onEvent(LeaveRequestEvent.ReviewActionRequested) },
                            enabled = state.canReviewAction,
                        )
                }
            }
        }
    }
}
